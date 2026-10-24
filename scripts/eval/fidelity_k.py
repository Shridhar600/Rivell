#!/usr/bin/env python3
"""K scorer: knowledge fidelity of the knowledge compile.

Uploads each source document of a fidelity set as knowledge, waits for the compile, reads the
draft entries (never publishes), and matches every label to a draft entry by the source text
(anchor and labelled line span). One adapter, `adapt_candidate`, maps today's draft-entry fields
to the label shape; a later engine change edits only that function.

Metrics, per document and in total:
  K1 entry coverage          labels with a draft entry / labels
  K2 no-role safety          matched CONTEXT labels whose entry is not a comparator / matched
  K3 stance key-fact recall  key facts present in the matched entry / key facts
  K4 authority               (approver, stance) units hit / units; predicted pairs correct /
                             predicted; `acceptableApprovers` from an authority conflict is
                             accepted for any named approver in the list

Usage:
  python3 fidelity_k.py --set tune --out <dir> [--no-build] [--document name=path ...]

Needs PostgreSQL up, JAVA_HOME (JDK 21) and provider credentials in the environment: the compile
calls a model. This script never runs an eval review and never publishes a draft.
"""
import argparse
import json
import pathlib
import re
import sys
import time
import uuid

import fidelity_common as common
import run_eval
from provider_guard import LogWatch, ProviderFatal
from rivell_api import RivellApi

STANCES = ("preferred", "fallback", "walkAway")
COMPARATOR_ROLES = {"PREFERRED", "FALLBACK", "WALK_AWAY", "PROHIBITED", "ESCALATION"}
STANCE_BY_ROLE = {"PREFERRED": "preferred", "FALLBACK": "fallback", "WALK_AWAY": "walkAway"}
MIN_MATCH = 0.6   # Jaccard overlap of the label's source words and the draft entry's words
MERGED_MIN = 0.8  # share of an unmatched label's source words one draft holds: merged, not missing
MIN_SHARED = 3    # ... and at least this many (or all, for a tiny label) whole words

# The user-test NDA playbook is labelled under its own file name and lives in the seed corpus.
SEED_ALIASES = {"company-negotiation-playbook.docx": "01_Company_Negotiation_Playbook.docx"}

_STANCE_MARKERS = (
    ("preferred", re.compile(r"^\s*preferred(?:\s+position)?\s*:\s*", re.IGNORECASE)),
    ("fallback", re.compile(r"^\s*fallback(?:\s+position)?\s*:\s*", re.IGNORECASE)),
    ("walkAway", re.compile(r"^\s*(?:walk[\s-]?away|prohibited)\s*:\s*", re.IGNORECASE)),
)


# ---------- adapter: today's draft-entry fields -> the label shape ----------

def split_stances(text, title=""):
    """The per-stance text a draft entry carries, from its markers (or a clause variant's title)."""
    out = {stance: None for stance in STANCES}
    current, buffer = None, []

    def flush():
        if current is not None:
            value = "\n".join(buffer).strip()
            out[current] = value or None

    for line in (text or "").splitlines():
        matched = None
        for stance, pattern in _STANCE_MARKERS:
            marker = pattern.match(line)
            if marker:
                matched = (stance, line[marker.end():])
                break
        if matched:
            flush()
            current, first = matched
            buffer = [first]
        elif current is not None:
            buffer.append(line)
    flush()
    if not any(out.values()):
        label = (title or "").lower()
        if "fallback" in label:
            out["fallback"] = (text or "").strip() or None
        elif "preferred" in label:
            out["preferred"] = (text or "").strip() or None
        elif "walk-away" in label or "walk away" in label or "prohibited" in label:
            out["walkAway"] = (text or "").strip() or None
    return out


def adapt_authority(row, roles):
    """(approver, stance) pairs the draft states. Today's drafts expose no approver at all."""
    pairs = []
    explicit = row.get("authority")
    if explicit is None and row.get("approvers") is not None:
        explicit = row.get("approvers")
    if isinstance(explicit, list):
        for item in explicit:
            if isinstance(item, str) and item.strip():
                pairs.append({"approver": item.strip(), "stances": ["UNSPECIFIED"]})
            elif isinstance(item, dict):
                stances = [str(stance).upper() for stance in (item.get("stances") or ["UNSPECIFIED"])]
                pairs.append({"approver": item.get("approver"), "stances": stances})
    elif isinstance(explicit, str) and explicit.strip():
        pairs.append({"approver": explicit.strip(), "stances": ["UNSPECIFIED"]})
    elif row.get("approver"):
        pairs.append({"approver": row["approver"], "stances": ["UNSPECIFIED"]})
    if not pairs:
        if row.get("requiresApproval") is True:
            pairs.append({"approver": None, "stances": ["UNSPECIFIED"]})
        if row.get("escalationTrigger"):
            # today the compile's only authority-bearing field is the escalation trigger, and the
            # drafts fill it with the walk-away approver's name; a later engine that puts a
            # condition here changes this line only
            pairs.append({"approver": str(row["escalationTrigger"]).strip(),
                          "stances": ["WALK_AWAY"]})
    return pairs


def _payload_stances(row):
    """Stance text from labelled payload columns (xlsx rows), when the compile keeps them."""
    roles = row.get("payloadFieldRoles") or {}
    out = {}
    for column, value in (row.get("payloadColumns") or {}).items():
        role = str(roles.get(column) or "").upper()
        stance = {"PREFERRED": "preferred", "FALLBACK": "fallback",
                  "WALK_AWAY": "walkAway", "PROHIBITED": "walkAway"}.get(role)
        if stance and value:
            out[stance] = f"{out.get(stance, '')} {value}".strip()
    return out


def adapt_candidate(row):
    """One place to change when the engine's draft-entry fields change (plan part 3)."""
    text = row.get("editedContentText") or row.get("contentText") or ""
    title = row.get("title") or row.get("rawLabel") or ""
    roles = sorted({str(role).upper() for role in (row.get("positions") or [])})
    candidate_type = (row.get("candidateType") or "").upper()
    comparator = candidate_type == "PLAYBOOK_RULE" or bool(set(roles) & COMPARATOR_ROLES)
    kind = ("POSITION" if comparator
            else "WORDING" if candidate_type == "CLAUSE_VARIANT" else "CONTEXT")
    payload = " ".join(str(value) for value in (row.get("payloadColumns") or {}).values())
    payload_stances = _payload_stances(row)
    text_stances = split_stances(text, title)
    return {
        "entryId": str(row.get("id") or title),
        "title": title,
        # matching reads only what the compile kept from the source, never Rivell's own title
        "text": f"{text} {payload}".strip(),
        "kind": kind,
        "comparator": comparator,
        "roles": [role for role in roles if role in COMPARATOR_ROLES],
        "stances": {stance: payload_stances.get(stance) or text_stances.get(stance)
                    for stance in STANCES},
        "authority": adapt_authority(row, roles),
    }


# ---------- matching ----------

def label_source_text(entry, source_lines):
    start, end = (entry.get("lines") or [1, 1])[:2]
    span = " ".join(source_lines[start - 1:end]) if source_lines else ""
    return f"{entry.get('anchor') or ''} {span}".strip()


def match_score(entry, draft, source_lines):
    """Jaccard, so a long summary that merely contains the label cannot outscore the entry itself."""
    wanted = common.content_words(label_source_text(entry, source_lines))
    have = common.content_words(draft["text"])
    if not wanted or not have:
        return 0.0, 0
    shared = wanted & have
    return len(shared) / len(wanted | have), len(shared)


def match_entries(entries, drafts, source_lines, min_match=MIN_MATCH):
    """Greedy one-to-one match: each label claims at most one entry, each entry one label."""
    pairs = []
    for index, entry in enumerate(entries):
        wanted = len(common.content_words(label_source_text(entry, source_lines)))
        for draft_index, draft in enumerate(drafts):
            score, shared = match_score(entry, draft, source_lines)
            if score >= min_match and shared >= min(MIN_SHARED, wanted):
                pairs.append((score, index, draft_index, len(common.content_words(draft["text"]))))
    # best overlap, then the smallest superset, then document order: deterministic and not
    # biased toward a long entry or the first draft
    pairs.sort(key=lambda row: (-row[0], row[3], row[1], row[2]))
    matches, used = {}, set()
    for score, index, draft_index, _ in pairs:
        if index in matches or draft_index in used:
            continue
        matches[index] = {"entryId": drafts[draft_index]["entryId"],
                          "entryTitle": drafts[draft_index]["title"],
                          "score": round(score, 3)}
        used.add(draft_index)
    # an unmatched label whose text sits inside a longer draft was compiled as part of another
    # entry (a compound unit): report it apart from a label the compile lost
    merged = {}
    for index, entry in enumerate(entries):
        if index in matches:
            continue
        wanted = common.content_words(label_source_text(entry, source_lines))
        if not wanted:
            continue
        best = max(((len(wanted & common.content_words(draft["text"])) / len(wanted),
                     draft["entryId"]) for draft in drafts), default=(0.0, None))
        if best[0] >= MERGED_MIN:
            merged[entry["id"]] = best[1]
    return {"matches": {entries[index]["id"]: match for index, match in matches.items()},
            "mergedInto": merged,
            "unmatched": [entry["id"] for index, entry in enumerate(entries)
                          if index not in matches],
            "unmatchedDrafts": [{"entryId": draft["entryId"], "title": draft["title"]}
                                for index, draft in enumerate(drafts) if index not in used],
            "entryCount": len(drafts)}


# ---------- metrics ----------

def _norm_name(name):
    return " ".join(word for word in common.WORD.findall((name or "").lower())
                    if word != "the")


def name_in(name, acceptable):
    """Whole-name equality, ignoring case, punctuation and a leading 'the'."""
    wanted = _norm_name(name)
    return bool(wanted) and any(_norm_name(candidate) == wanted for candidate in acceptable)


def expected_units(entry):
    """The approvals the label requires, one unit per (stance, acceptable approvers)."""
    units, unnameable = [], []
    for stance in STANCES:
        effective = (entry.get("effective") or {}).get(stance) or {}
        kind = (effective.get("approval") or effective.get("action") or "").upper()
        if kind not in ("REQUIRED", "ESCALATE"):
            continue
        acceptable = [name for name in (effective.get("acceptableApprovers") or []) if name]
        if not acceptable and effective.get("approver"):
            acceptable = [effective["approver"]]
        if not acceptable:
            unnameable.append({"labelId": entry["id"], "stance": stance, "kind": kind})
            continue
        units.append({"labelId": entry["id"], "stance": stance, "kind": kind,
                      "acceptable": acceptable,
                      "authorityConflict": bool(effective.get("authorityConflict"))})
    return units, unnameable


def _predicted(entry, draft):
    pairs = []
    for pair in (draft or {}).get("authority") or []:
        for role in pair.get("stances") or ["UNSPECIFIED"]:
            stance = STANCE_BY_ROLE.get(str(role).upper(), str(role).lower())
            pairs.append({"labelId": entry["id"], "approver": pair.get("approver"),
                          "stance": stance})
    return pairs


def score_document(label_data, candidates, source_text):
    entries = list(label_data.get("entries") or [])
    ids = [entry.get("id") for entry in entries]
    if len(ids) != len(set(ids)):
        # one label must be counted once; duplicate ids would let one source entry be scored twice
        raise ValueError(f"{label_data.get('source')}: duplicate label ids")
    source_lines = (list(source_text) if isinstance(source_text, (list, tuple))
                    else (source_text or "").splitlines())
    drafts = [adapt_candidate(candidate) for candidate in candidates]
    draft_ids = [draft["entryId"] for draft in drafts]
    if len(draft_ids) != len(set(draft_ids)):
        raise ValueError(f"{label_data.get('source')}: duplicate draft entry ids")
    matching = match_entries(entries, drafts, source_lines)
    draft_by_id = {draft["entryId"]: draft for draft in drafts}

    kinds = {}
    for entry in entries:
        row = kinds.setdefault(entry.get("kind") or "CONTEXT", {"labels": 0, "matched": 0})
        row["labels"] += 1
    for entry in entries:
        if entry["id"] in matching["matches"]:
            kinds[entry.get("kind") or "CONTEXT"]["matched"] += 1

    violations, matched_context = [], 0
    for entry in entries:
        if entry.get("kind") != "CONTEXT":
            continue
        match = matching["matches"].get(entry["id"])
        if not match:
            continue
        matched_context += 1
        draft = draft_by_id[match["entryId"]]
        if draft["comparator"]:
            violations.append({"labelId": entry["id"], "labelTitle": entry.get("title"),
                               "entryId": draft["entryId"], "entryTitle": draft["title"],
                               "roles": draft["roles"]})

    by_stance = {stance: {"keyFacts": 0, "found": 0, "stances": 0, "stancesComplete": 0,
                          "attributed": 0, "merged": 0}
                 for stance in STANCES}
    k3_misses = []
    for entry in entries:
        match = matching["matches"].get(entry["id"])
        draft = draft_by_id.get(match["entryId"]) if match else None
        for stance in STANCES:
            stated = (entry.get("stances") or {}).get(stance) or {}
            facts = list(dict.fromkeys(fact for fact in (stated.get("keyFacts") or []) if fact))
            if not facts:
                continue
            by_stance[stance]["stances"] += 1
            # when the draft separates stances, a key fact must sit in its own stance; a merged
            # entry (no stance labels) is credited from its whole text and counted as merged
            slices = (draft or {}).get("stances") or {}
            attributed = bool(draft) and any(slices.values())
            if attributed:
                text = slices.get(stance) or ""
                by_stance[stance]["attributed"] += 1
            else:
                text = (draft or {}).get("text") or ""
                if draft:
                    by_stance[stance]["merged"] += 1
            found_here = 0
            for fact in facts:
                by_stance[stance]["keyFacts"] += 1
                if common.phrase_in(fact, text):
                    by_stance[stance]["found"] += 1
                    found_here += 1
                else:
                    k3_misses.append({"labelId": entry["id"], "stance": stance, "fact": fact,
                                      "matched": bool(match)})
            if found_here == len(facts):
                by_stance[stance]["stancesComplete"] += 1

    units, unnameable, predicted = [], [], []
    for entry in entries:
        entry_units, entry_unnameable = expected_units(entry)
        units += entry_units
        unnameable += entry_unnameable
        match = matching["matches"].get(entry["id"])
        predicted += _predicted(entry, draft_by_id.get(match["entryId"]) if match else None)
    unique, seen_pairs = [], set()
    for pair in predicted:
        key = (pair["labelId"], pair["approver"], pair["stance"])
        if key in seen_pairs:
            continue
        seen_pairs.add(key)
        unique.append(pair)
    predicted = unique
    named = [pair for pair in predicted if pair["approver"]]
    unnamed = [pair for pair in predicted if not pair["approver"]]
    hits, k4_misses = [], []
    for unit in units:
        hit = any(pair["labelId"] == unit["labelId"] and pair["stance"] == unit["stance"]
                  and name_in(pair["approver"], unit["acceptable"]) for pair in named)
        (hits if hit else k4_misses).append(unit)
    correct, k4_wrong = [], []
    for pair in named:
        acceptable = [unit["acceptable"] for unit in units
                      if unit["labelId"] == pair["labelId"] and unit["stance"] == pair["stance"]]
        if any(name_in(pair["approver"], names) for names in acceptable):
            correct.append(pair)
        else:
            k4_wrong.append(pair)

    return {
        "source": label_data.get("source"),
        "labels": len(entries),
        "drafts": len(candidates),
        "k1": {"labels": len(entries), "matched": len(matching["matches"]),
               "merged": len(matching["mergedInto"]), "byKind": kinds},
        "mergedInto": matching["mergedInto"],
        "k2": {"contextLabels": sum(1 for entry in entries if entry.get("kind") == "CONTEXT"),
               "matchedContext": matched_context,
               "violations": len(violations),
               "safe": matched_context - len(violations)},
        "k3": {"keyFacts": sum(row["keyFacts"] for row in by_stance.values()),
               "found": sum(row["found"] for row in by_stance.values()),
               "stances": sum(row["stances"] for row in by_stance.values()),
               "stancesComplete": sum(row["stancesComplete"] for row in by_stance.values()),
               "stancesAttributed": sum(row["attributed"] for row in by_stance.values()),
               "stancesMerged": sum(row["merged"] for row in by_stance.values()),
               "byStance": by_stance},
        "k4": {"units": len(units), "hit": len(hits), "predicted": len(named),
               "correct": len(correct), "wrong": len(k4_wrong), "unnamed": len(unnamed),
               "unnameable": len(unnameable)},
        "matches": matching["matches"],
        "unmatched": matching["unmatched"],
        "unmatchedDrafts": matching["unmatchedDrafts"],
        "k2Violations": violations,
        "k3Misses": k3_misses,
        "k4Misses": k4_misses,
        "k4Wrong": k4_wrong,
        "k4Unnamed": unnamed,
        "k4Unnameable": unnameable,
    }


def totals(results):
    """Sum numerators and denominators across documents; never average ratios."""
    scored = [row for row in results if "k1" in row]
    by_kind = {}
    for row in scored:
        for kind, value in row["k1"]["byKind"].items():
            target = by_kind.setdefault(kind, {"labels": 0, "matched": 0})
            target["labels"] += value["labels"]
            target["matched"] += value["matched"]
    by_stance = {}
    for row in scored:
        for stance, value in row["k3"]["byStance"].items():
            target = by_stance.setdefault(stance, {"keyFacts": 0, "found": 0, "stances": 0,
                                                   "stancesComplete": 0, "attributed": 0,
                                                   "merged": 0})
            for key in target:
                target[key] += value[key]
    return {
        "documents": len(scored),
        "labels": sum(row["k1"]["labels"] for row in scored),
        "drafts": sum(row["drafts"] for row in scored),
        "k1": {"labels": sum(row["k1"]["labels"] for row in scored),
               "matched": sum(row["k1"]["matched"] for row in scored),
               "merged": sum(row["k1"].get("merged", 0) for row in scored), "byKind": by_kind},
        "k2": {key: sum(row["k2"][key] for row in scored)
               for key in ("contextLabels", "matchedContext", "violations", "safe")},
        "k3": {"keyFacts": sum(row["k3"]["keyFacts"] for row in scored),
               "found": sum(row["k3"]["found"] for row in scored),
               "stances": sum(row["k3"]["stances"] for row in scored),
               "stancesComplete": sum(row["k3"]["stancesComplete"] for row in scored),
               "stancesAttributed": sum(row["k3"]["stancesAttributed"] for row in scored),
               "stancesMerged": sum(row["k3"]["stancesMerged"] for row in scored),
               "byStance": by_stance},
        "k4": {key: sum(row["k4"][key] for row in scored)
               for key in ("units", "hit", "predicted", "correct", "wrong", "unnamed",
                           "unnameable")},
    }


# ---------- report ----------

def report_lines(results, total, meta):
    scored = [row for row in results if "k1" in row]
    errors = [row for row in results if "error" in row]
    lines = [f"# Knowledge fidelity (K), set `{meta['set']}`", "",
             f"Context `{meta.get('context')}` · {meta.get('generated')} · "
             f"documents scored {len(scored)} of {len(results)} · "
             "drafts read from the compile, never published", ""]
    lines += ["## Per document", ""]
    lines += common.table(
        ["Document", "Drafts", "K1 entry coverage", "K2 no-role safety", "K3 key facts",
         "K4 authority (approver, stance)"],
        [[row["source"], row["drafts"],
          common.pct(row["k1"]["matched"], row["k1"]["labels"]),
          (common.pct(row["k2"]["safe"], row["k2"]["matchedContext"])
           if row["k2"]["matchedContext"] else "n/a"),
          common.pct(row["k3"]["found"], row["k3"]["keyFacts"]),
          f"recall {common.pct(row['k4']['hit'], row['k4']['units'])}; "
          f"precision {common.pct(row['k4']['correct'], row['k4']['predicted'])}"]
         for row in scored])
    lines += ["", "## Totals", "",
              f"- K1 entry coverage: {common.pct(total['k1']['matched'], total['k1']['labels'])}",
              f"- K1 unmatched labels held inside a longer draft (compound unit, not lost): "
              f"{total['k1'].get('merged', 0)}",
              "- K1 by kind: " + ", ".join(
                  f"{kind} {common.pct(value['matched'], value['labels'])}"
                  for kind, value in sorted(total["k1"]["byKind"].items())),
              (f"- K2 no-role safety: {common.pct(total['k2']['safe'], total['k2']['matchedContext'])} "
               f"matched context entries stayed non-comparators; violations "
               f"{total['k2']['violations']} (of {total['k2']['contextLabels']} context labels)"
               if total["k2"]["matchedContext"] else
               f"- K2 no-role safety: n/a (no context label matched a draft entry; "
               f"{total['k2']['contextLabels']} context labels, 0 violations)"),
              f"- K3 stance key-fact recall: {common.pct(total['k3']['found'], total['k3']['keyFacts'])}; "
              f"stances with every key fact {common.pct(total['k3']['stancesComplete'], total['k3']['stances'])}; "
              f"attributed to a stance slice {total['k3']['stancesAttributed']}, credited from a "
              f"merged entry {total['k3']['stancesMerged']}",
              "- K3 by stance: " + ", ".join(
                  f"{stance} {common.pct(value['found'], value['keyFacts'])}"
                  for stance, value in sorted(total["k3"]["byStance"].items())),
              f"- K4 authority recall: {common.pct(total['k4']['hit'], total['k4']['units'])} "
              f"named (approver, stance) units",
              f"- K4 authority precision: "
              f"{common.pct(total['k4']['correct'], total['k4']['predicted'])} of predicted pairs"
              + (" (no draft names an approver)" if not total["k4"]["predicted"] else ""),
              f"- K4 unnamed approvals (raised, no approver named): {total['k4']['unnamed']}; "
              f"required but the source names no approver: {total['k4']['unnameable']}", ""]
    if total["k2"]["violations"]:
        lines += ["## K2: a CONTEXT entry became a comparator", ""]
        for row in scored:
            for violation in row["k2Violations"]:
                lines.append(f"- {row['source']}: {violation['labelId']} "
                             f"{violation['labelTitle']!r} -> draft {violation['entryTitle']!r} "
                             f"(roles {', '.join(violation['roles']) or 'playbook rule'})")
        lines.append("")
    unmatched = [(row["source"], label) for row in scored for label in row["unmatched"]]
    lines += ["## Unmatched labels", ""]
    lines += [f"- {source}: {label}" for source, label in unmatched] or ["- none"]
    lines.append("")
    lines += ["## Draft entries matching no label", ""]
    drafts = [(row["source"], draft) for row in scored for draft in row["unmatchedDrafts"]]
    lines += [f"- {source}: {draft['title']!r} ({draft['entryId']})" for source, draft in drafts]         or ["- none"]
    lines.append("")
    misses = [(row["source"], miss) for row in scored for miss in row["k3Misses"]]
    lines += ["## K3 key facts not in the matched draft", ""]
    lines += [f"- {source}: {miss['labelId']} {miss['stance']} {miss['fact']!r}"
              + ("" if miss["matched"] else " (label unmatched)")
              for source, miss in misses]
    lines.append("")
    lines += ["## K4 authority units missed", ""]
    lines += [f"- {row['source']}: {unit['labelId']} {unit['stance']} "
              f"(acceptable: {', '.join(unit['acceptable'])})"
              for row in scored for unit in row["k4Misses"]] or ["- none"]
    lines.append("")
    wrong = [(row["source"], pair) for row in scored for pair in row["k4Wrong"]]
    lines += ["## K4 predicted approvers outside the acceptable set", ""]
    lines += [f"- {source}: {pair['labelId']} {pair['stance']} {pair['approver']!r}"
              for source, pair in wrong] or ["- none"]
    lines += ["", "## Errors", ""]
    lines += [f"- {row['source']}: {row['error']}" for row in errors] or ["- none"]
    return lines


def write_outputs(out, results, total, meta):
    return common.write_outputs(out, results, meta, report_lines(results, total, meta))


# ---------- live run ----------

def resolve_document(context, source, overrides):
    context = pathlib.Path(context)
    if source in overrides:
        path = pathlib.Path(overrides[source])
        if not path.is_file():
            raise FileNotFoundError(f"no source document at {path} for {source}")
        return path
    names = [source]
    if source in SEED_ALIASES:
        names.append(SEED_ALIASES[source])
    roots = [context / "eval" / "corpus" / "dev2" / "playbooks",
             context / "eval" / "corpus" / "dev2" / "clause-libraries",
             context / "eval" / "corpus" / "seed"]
    roots += [context / "eval" / "corpus" / "dev" / org / kind
              for org in ("a", "b", "c") for kind in ("playbooks", "clause-libraries")]
    for name in names:
        for root in roots:
            path = root / name
            if path.is_file():
                return path
    raise FileNotFoundError(
        f"no source document for {source}; pass --document {source}=<path>")


def artifact_type(source):
    return "CLAUSE_LIBRARY" if "clause-library" in source.lower() else "PLAYBOOK"


def declared_types(labels):
    known = {"NDA", "MNDA", "ONDA", "MSA", "SAAS", "DPA", "SOW", "SWL", "SUPPLY",
             "SERVICES", "RESELLER", "ORDER_FORM", "AMENDMENT", "OTHER"}
    codes = {code for entry in labels.get("entries") or []
             for code in entry.get("appliesToCodes") or []}
    return sorted(codes & known)


def compile_document(api, labels, document):
    query = f"artifactType={artifact_type(labels['source'])}" + "".join(
        f"&declaredContractTypes={code}" for code in declared_types(labels))
    artifact = api.upload(f"/api/knowledge/artifacts?{query}", document)
    candidates, completed = run_eval.wait_candidates(api, artifact["id"], with_status=True)
    if not completed:
        raise RuntimeError("the knowledge compile did not finish before the wait timed out")
    return candidates


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--set", required=True, dest="set_name")
    parser.add_argument("--out", required=True, type=pathlib.Path)
    parser.add_argument("--context", type=pathlib.Path)
    parser.add_argument("--document", action="append", default=[],
                        metavar="NAME=PATH", help="override a source document path")
    parser.add_argument("--jar", type=pathlib.Path, help="backend jar (skips the build)")
    parser.add_argument("--no-build", action="store_true")
    args = parser.parse_args()
    overrides = {}
    for value in args.document:
        name, sep, path = value.partition("=")
        if not sep:
            sys.exit(f"--document wants NAME=PATH, got {value!r}")
        overrides[name] = path

    fidelity = common.fidelity_set(args.set_name, args.context)
    labels_dir = fidelity / "labels"
    if not labels_dir.is_dir():
        sys.exit(f"no labels/ under {fidelity}")
    label_files = sorted(labels_dir.glob("*.json"))
    if not label_files:
        sys.exit(f"no label files under {labels_dir}")
    context = common.context_root(args.context)
    out = args.out
    logs = out / "logs"
    raw_dir = out / "raw"
    logs.mkdir(parents=True, exist_ok=True)
    raw_dir.mkdir(parents=True, exist_ok=True)

    if not args.jar and not args.no_build:
        run_eval.build_jar()
    jar = args.jar or run_eval.find_jar()
    api = RivellApi(run_eval.BASE_URL)
    results, errors = [], []
    fatal = None
    for label_file in label_files:
        started = time.monotonic()
        proc = log = None
        source, document, labels = label_file.stem, None, None
        try:
            labels = json.loads(label_file.read_text())
            source = labels.get("source") or source
            document = resolve_document(context, source, overrides)
            source_text = (fidelity / "sources" / f"{source}.txt").read_text()
            if fatal:
                results.append({"source": source, "document": str(document),
                                "error": f"not run: {fatal}"})
                continue
            log_path = logs / f"backend-{source}.log"
            proc, log = run_eval.start_backend(jar, str(uuid.uuid4()), log_path)
            run_eval.WATCH = LogWatch(log_path)
            api.wait_ready()
            candidates = compile_document(api, labels, document)
            run_eval.WATCH.check()
            (raw_dir / f"{label_file.stem}.json").write_text(json.dumps(candidates, indent=1))
            scored = score_document(labels, candidates, source_text)
            scored["document"] = str(document)
            scored["seconds"] = round(time.monotonic() - started, 1)
            results.append(scored)
            print(f"{source}: {len(candidates)} drafts, K1 "
                  f"{scored['k1']['matched']}/{scored['k1']['labels']}", flush=True)
        except ProviderFatal as error:
            fatal = str(error)
            results.append({"source": source, "document": str(document),
                            "error": f"{type(error).__name__}: {error}"})
            errors.append(f"{source}: {error}")
            print(f"{source}: PROVIDER FATAL {error}", flush=True)
        except Exception as error:  # one document must not hide the others
            results.append({"source": source, "document": str(document),
                            "error": f"{type(error).__name__}: {error}"})
            errors.append(f"{source}: {error}")
            print(f"{source}: FAILED {error}", flush=True)
        finally:
            if proc is not None:
                run_eval.stop_backend(proc, log)
            run_eval.WATCH = None
    total = totals(results)
    meta = {"scorer": "K", "set": args.set_name, "context": str(context),
            "generated": common.generated(), "documents": len(results), "errors": errors}
    report = write_outputs(out, results, total, meta)
    print(f"report: {report}")


if __name__ == "__main__":
    main()
