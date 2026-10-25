#!/usr/bin/env python3
"""T scorer: fit of a matched rule (does it govern the clause?) and of offered wording (does it
meet the position and cure the gap?), against the adjudicated labels.

Rivell's verdicts are kept in a separate file (`rivell-verdicts-<set>.json`) so the labels were
written blind. Files only: no backend, no model call.

`governs` is three-valued in the labels (YES / PARTLY / NO) and two-valued in Rivell's verdict
(`judgedAddressing`): the primary read treats YES and PARTLY as positive, and the strict YES-only
read is reported beside it. A pair whose label is NO_POSITION has no position to meet; it is
excluded from `meetsPosition` and named in the report, never scored as a miss.

Usage:
  python3 fidelity_t.py --set tune --out <dir>
  python3 fidelity_t.py --set tune --pairs ... --labels ... --verdicts ... --out <dir>
"""
import argparse
import pathlib
import sys

import fidelity_common as common

DECISIONS = ("meetsPosition", "curesClause")


def binary_metrics(pairs):
    """Precision, recall, accuracy and the base rate from (predicted, actual) pairs."""
    tp = sum(1 for predicted, actual in pairs if predicted and actual)
    fp = sum(1 for predicted, actual in pairs if predicted and not actual)
    fn = sum(1 for predicted, actual in pairs if not predicted and actual)
    tn = sum(1 for predicted, actual in pairs if not predicted and not actual)
    total = len(pairs)
    return {"scored": total, "tp": tp, "fp": fp, "fn": fn, "tn": tn,
            "predictedPositive": tp + fp, "positives": tp + fn,
            "precision": tp / (tp + fp) if tp + fp else None,
            "recall": tp / (tp + fn) if tp + fn else None,
            "accuracy": (tp + tn) / total if total else None,
            "baseRate": (tp + fn) / total if total else None}


def _search_decision(verdict):
    value = (verdict or {}).get("judgedAddressing")
    return value if isinstance(value, bool) else None


def _drafting_decision(verdict, key):
    """True = Rivell accepts the wording; False = rejects it; None = no usable verdict."""
    if not verdict:
        return None
    explicit = verdict.get(key)
    if isinstance(explicit, bool):
        return explicit
    if isinstance(explicit, str):
        value = explicit.strip().upper()
        if value in ("YES", "TRUE", "ACCEPTED"):
            return True
        if value in ("NO", "FALSE", "REJECTED"):
            return False
    label = str(verdict.get("rivellLabel") or "").strip().lower()
    if not label:
        return None
    if label.startswith("approved") or label.startswith("accepted") or "usable" == label:
        return True
    if "reject" in label or "not approved" in label or "unusable" in label or "not usable" in label:
        return False
    return None


def score_search_matched(pairs, labels):
    """The no-fit-check read: every pair Rivell's search matched is taken as `governs` positive."""
    rows = []
    for pair in pairs:
        label = labels.get(pair["id"])
        if label is None:
            continue
        value = str(label.get("governs") or "").upper()
        if value not in ("YES", "PARTLY", "NO"):
            continue
        rows.append({"id": pair["id"], "contract": pair.get("contract"),
                     "predicted": True, "actual": value != "NO", "label": value})
    result = dict(binary_metrics([(row["predicted"], row["actual"]) for row in rows]),
                  key="governs", allPositive=True, rows=rows)
    result["byContract"] = _by_contract(rows)
    return result


def score_search(pairs, labels, verdicts):
    """`governs` as the comparison judge saw it: YES+PARTLY positive, YES only in the strict read."""
    pair_ids = {pair["id"] for pair in pairs}
    scored_rows, unscored = [], []
    label_counts = {"YES": 0, "PARTLY": 0, "NO": 0}
    for pair in pairs:
        label = labels.get(pair["id"])
        if label is None:
            unscored.append({"id": pair["id"], "reason": "no search label"})
            continue
        value = str(label.get("governs") or "").upper()
        if value not in label_counts:
            unscored.append({"id": pair["id"], "reason": f"unknown governs label {value!r}"})
            continue
        label_counts[value] += 1
        verdict = verdicts.get(pair["id"])
        if verdict is None:
            unscored.append({"id": pair["id"], "reason": "no Rivell verdict"})
            continue
        decision = _search_decision(verdict)
        if decision is None:
            unscored.append({"id": pair["id"], "reason": "no judgedAddressing verdict"})
            continue
        scored_rows.append({"id": pair["id"], "contract": pair.get("contract"),
                            "predicted": decision, "actual": value != "NO",
                            "label": value})
    primary = binary_metrics([(row["predicted"], row["actual"]) for row in scored_rows])
    strict_rows = [row for row in scored_rows if row["label"] != "PARTLY"]
    strict = binary_metrics([(row["predicted"], row["actual"]) for row in strict_rows])
    result = dict(primary, key="governs", labels=label_counts, strict=strict,
                  unscored=unscored,
                  labelsWithoutPairs=[{"id": label_id, "reason": "no pair"}
                                      for label_id in labels if label_id not in pair_ids],
                  allPositive=bool(scored_rows) and all(row["predicted"] for row in scored_rows),
                  rows=scored_rows)
    result["byContract"] = _by_contract(scored_rows)
    return result


def _by_contract(rows):
    contracts = {}
    for row in rows:
        contracts.setdefault(row.get("contract") or "?", []).append(row)
    return {contract: binary_metrics([(row["predicted"], row["actual"]) for row in group])
            for contract, group in sorted(contracts.items())}


def score_drafting(pairs, labels, verdicts, key):
    """One wording decision (`meetsPosition` or `curesClause`) against the adjudicated label."""
    scored_rows, unscored, excluded = [], [], []
    for pair in pairs:
        label = labels.get(pair["id"])
        if label is None:
            continue
        value = str(label.get(key) or "").upper()
        if value == "NO_POSITION":
            excluded.append({"id": pair["id"], "reason": "NO_POSITION"})
            continue
        if value not in ("YES", "NO"):
            excluded.append({"id": pair["id"], "reason": f"no {key} label"})
            continue
        verdict = verdicts.get(pair["id"])
        if verdict is None:
            unscored.append({"id": pair["id"], "reason": "no Rivell verdict"})
            continue
        decision = _drafting_decision(verdict, key)
        if decision is None:
            unscored.append({"id": pair["id"],
                             "reason": f"unrecognised Rivell verdict "
                                       f"{verdict.get('rivellLabel')!r}"})
            continue
        scored_rows.append({"id": pair["id"], "contract": pair.get("contract"),
                            "predicted": decision, "actual": value == "YES"})
    result = dict(binary_metrics([(row["predicted"], row["actual"]) for row in scored_rows]),
                  key=key, unscored=unscored, excluded=excluded,
                  allPositive=bool(scored_rows) and all(row["predicted"] for row in scored_rows),
                  byContract=_by_contract(scored_rows))
    return result


def score(pairs, labels, verdicts):
    for kind in ("search", "drafting"):
        ids = [row.get("id") for row in labels.get(kind) or []]
        if len(ids) != len(set(ids)):
            raise ValueError(f"duplicate {kind} label ids")
        pair_ids = [pair.get("id") for pair in pairs.get(kind) or []]
        if len(pair_ids) != len(set(pair_ids)):
            raise ValueError(f"duplicate {kind} pair ids")
    search_labels = {row["id"]: row for row in labels.get("search") or []}
    drafting_labels = {row["id"]: row for row in labels.get("drafting") or []}
    search_pairs = list(pairs.get("search") or [])
    drafting_pairs = list(pairs.get("drafting") or [])
    all_pair_ids = {pair["id"] for pair in search_pairs + drafting_pairs}
    return {
        "search": {"matched": score_search_matched(search_pairs, search_labels),
                   "judgedAddressing": score_search(search_pairs, search_labels, verdicts)},
        "drafting": {key: score_drafting(drafting_pairs, drafting_labels, verdicts, key)
                     for key in DECISIONS},
        "pairs": {"search": len(search_pairs), "drafting": len(drafting_pairs)},
        "pairsWithoutLabels": [{"id": pair["id"], "reason": f"no {kind} label"}
                               for kind, kind_pairs, kind_labels in
                               (("search", search_pairs, search_labels),
                                ("drafting", drafting_pairs, drafting_labels))
                               for pair in kind_pairs if pair["id"] not in kind_labels],
        "labelsWithoutPairs": [{"id": label_id, "reason": "no pair"}
                               for label_id in list(search_labels) + list(drafting_labels)
                               if label_id not in all_pair_ids],
        "verdictsWithoutPairs": [verdict_id for verdict_id in verdicts
                                 if verdict_id not in all_pair_ids],
    }


def _line(label, metrics):
    return (f"- {label}: precision {common.pct(metrics['tp'], metrics['tp'] + metrics['fp'])}, "
            f"recall {common.pct(metrics['tp'], metrics['tp'] + metrics['fn'])}, "
            f"accuracy {common.pct(metrics['tp'] + metrics['tn'], metrics['scored'])}, "
            f"base rate {common.pct(metrics['positives'], metrics['scored'])} "
            f"({metrics['scored']} pairs scored)")


def report_lines(scored, meta):
    matched = scored["search"]["matched"]
    judged = scored["search"]["judgedAddressing"]
    lines = [f"# Fit (T), set `{meta['set']}`", "",
             f"Pairs `{meta.get('pairs')}` · labels `{meta.get('labels')}` · "
             f"verdicts `{meta.get('verdicts')}` · {meta.get('generated')}", "",
             f"{scored['pairs']['search']} rule/clause pairs and "
             f"{scored['pairs']['drafting']} gap/wording pairs in the set.", "",
             "The search pairs are Rivell's own search matches (every flagged or addressing pair "
             "plus a random 60 of the rest), so a matched pair is a positive fit decision: with no "
             "fit check, precision equals the base rate on this set. The comparison judge's "
             "`judgedAddressing` is reported beside it as a proxy, not as the fit decision.", "",
             "## Search: does the rule govern the clause?", "",
             _line("governs, no fit check (every matched pair is positive)", matched),
             _line("proxy: judgedAddressing (YES or PARTLY counted positive)", judged),
             _line("proxy: judgedAddressing, strict YES only (PARTLY excluded)", judged["strict"]),
             f"- Labels: YES {judged['labels']['YES']}, PARTLY {judged['labels']['PARTLY']}, "
             f"NO {judged['labels']['NO']}",
             f"- The judge addressed {judged['predictedPositive']} of {judged['scored']} pairs"
             + (" (always positive)" if judged["allPositive"] else ""), ""]
    lines += ["## Wording: meets the position, and cures the gap", ""]
    lines += common.table(
        ["Decision", "Pairs scored", "Positives", "Precision", "Recall", "Base rate",
         "Predicted positive"],
        [[key, metrics["scored"], metrics["positives"],
          common.pct(metrics["tp"], metrics["tp"] + metrics["fp"]),
          common.pct(metrics["tp"], metrics["tp"] + metrics["fn"]),
          common.pct(metrics["positives"], metrics["scored"]),
          f"{metrics['predictedPositive']} of {metrics['scored']}"
          + (" (always)" if metrics["allPositive"] else "")]
         for key, metrics in scored["drafting"].items()])
    lines.append("")
    lines += ["## Per contract", ""]
    contracts = sorted({contract for metrics in [matched, judged, *scored["drafting"].values()]
                        for contract in metrics["byContract"]})
    rows = []
    for contract in contracts:
        cells = [contract]
        for metrics in (matched, scored["drafting"]["meetsPosition"],
                        scored["drafting"]["curesClause"]):
            row = metrics["byContract"].get(contract)
            cells.append(common.pct(row["positives"], row["scored"]) if row else "-")
        rows.append(cells)
    lines += common.table(["Contract", "governs: positive labels / pairs",
                           "meets: positive labels / pairs", "cures: positive labels / pairs"],
                          rows)
    lines += ["", "## Unscored", ""]
    lines += [f"- no adjudicated label: {row['id']}" for row in scored["pairsWithoutLabels"]]
    lines += [f"- no pair for label: {row['id']}" for row in scored["labelsWithoutPairs"]]
    for name, metrics in (("judgedAddressing", judged),
                          *((key, scored["drafting"][key]) for key in DECISIONS)):
        lines += [f"- {name}: {row['id']}: {row['reason']}" for row in metrics["unscored"]]
        lines += [f"- {name}: {row['id']}: {row['reason']}"
                  for row in metrics.get("excluded", [])]
    outside = scored["verdictsWithoutPairs"]
    lines += [f"- Rivell verdicts outside the labelled pair set (not scored): {len(outside)}"
              + (f" (first: {', '.join(outside[:5])})" if outside else "")]
    lines += ["", "## Errors", "", "- none"]
    return lines


def write_outputs(out, scored, meta):
    return common.write_outputs(out, scored, meta, report_lines(scored, meta))


def input_paths(context, set_name, pairs=None, labels=None, verdicts=None):
    fidelity = common.context_root(context) / "eval" / "fidelity" / "fit"
    paths = {"pairs": pairs or fidelity / f"pairs-{set_name}.json",
             "labels": labels or fidelity / f"labels-{set_name}.json",
             "verdicts": verdicts or fidelity / f"rivell-verdicts-{set_name}.json"}
    for name, path in paths.items():
        if not pathlib.Path(path).is_file():
            sys.exit(f"no {name} file at {path}")
    return {name: pathlib.Path(path) for name, path in paths.items()}


def load_inputs(context, set_name, pairs=None, labels=None, verdicts=None):
    paths = input_paths(context, set_name, pairs, labels, verdicts)
    return {name: common.load_json(path) for name, path in paths.items()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--set", required=True, dest="set_name")
    parser.add_argument("--out", required=True, type=pathlib.Path)
    parser.add_argument("--context", type=pathlib.Path)
    parser.add_argument("--pairs", type=pathlib.Path)
    parser.add_argument("--labels", type=pathlib.Path)
    parser.add_argument("--verdicts", type=pathlib.Path)
    args = parser.parse_args()
    paths = input_paths(args.context, args.set_name, args.pairs, args.labels, args.verdicts)
    data = {name: common.load_json(path) for name, path in paths.items()}
    scored = score(data["pairs"], data["labels"], data["verdicts"])
    meta = {"scorer": "T", "set": args.set_name,
            **{name: str(path) for name, path in paths.items()},
            "generated": common.generated()}
    report = write_outputs(args.out, scored, meta)
    print(f"report: {report}")


if __name__ == "__main__":
    main()
