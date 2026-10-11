#!/usr/bin/env python3
"""Step-6 baselines: review the eval corpus without Rivell, scored like Rivell.

  whole  the whole playbook, clause library and contract in one prompt to an LLM
  rag    plain RAG: contract chunks, top-k playbook/library chunks by embedding, one LLM
         judge call per chunk

Both get what Rivell gets (contract, playbook, clause library, contract type, our side) and
answer with findings that quote the contract. The scorer places each quote in the answer-key
section that contains it (headings matched like Rivell's), so issues, serious issues and
false alarms are counted exactly as `scoring.score_contract` counts them for Rivell.

Usage (keys from the environment, never printed):
  python3 scripts/eval/baselines.py --mode whole --model gpt-6-luna \
      --corpus ../Rivell-context/eval/corpus/dev2 --out ../Rivell-context/eval/runs/dev2-baseline
"""
import argparse
import concurrent.futures
import json
import math
import os
import pathlib
import re
import time
import urllib.error
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

from scoring import _section_locator, match_sections, score_contract

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
S = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
CATEGORIES = ["WALK_AWAY_BREACH", "NEEDS_FALLBACK", "ACCEPTABLE_FALLBACK", "AMBIGUOUS",
              "CROSS_CLAUSE_CONFLICT", "MISSING_PROVISION", "UNADDRESSED_BY_PLAYBOOK"]
# USD per 1M tokens (input, output); third-party listings 2026-10, approximate
PRICES = {"gpt-6-luna": (0.10, 0.50), "gpt-6-sol": (2.0, 10.0), "gpt-6.1-sol": (2.0, 10.0)}


# ---------- documents ----------

MC_FALLBACK = "{http://schemas.openxmlformats.org/markup-compatibility/2006}Fallback"


def _para_text(p):
    """Visible text; a text box's paragraphs are separated by spaces and its legacy
    mc:Fallback copy is skipped."""
    out = []

    def walk(node):
        if node.tag == MC_FALLBACK:
            return
        if node.tag == W + "t":
            out.append(node.text or "")
        elif node.tag == W + "tab" or (node.tag == W + "p" and node is not p):
            out.append(" ")
        for child in node:
            walk(child)

    walk(p)
    return re.sub(r"\s+", " ", "".join(out)).strip()


def docx_paragraphs(path):
    """Body paragraphs in document order, tables included (one line per row), accept-all view
    (w:delText is never read, so deleted text is dropped and inserted text kept)."""
    with zipfile.ZipFile(path) as z:
        body = ET.fromstring(z.read("word/document.xml")).find(W + "body")
    out = []

    def walk(el):
        if el.tag == W + "p":
            text = _para_text(el)
            if text:
                out.append(text)
        elif el.tag == W + "tbl":
            for tr in el.iter(W + "tr"):
                cells = [" ".join(_para_text(p) for p in tc.iter(W + "p")).strip()
                         for tc in tr.findall(W + "tc")]
                line = " | ".join(c for c in cells if c)
                if line:
                    out.append(line)
        else:
            for child in el:
                walk(child)

    for child in body:
        walk(child)
    return out


def xlsx_lines(path):
    lines = []
    with zipfile.ZipFile(path) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            for si in ET.fromstring(z.read("xl/sharedStrings.xml")).iter(S + "si"):
                shared.append("".join(t.text or "" for t in si.iter(S + "t")))
        rels = {r.get("Id"): r.get("Target") for r in
                ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))}
        for sheet in ET.fromstring(z.read("xl/workbook.xml")).iter(S + "sheet"):
            target = rels[sheet.get(R + "id")].lstrip("/")
            target = target if target.startswith("xl/") else "xl/" + target
            lines.append(f"Sheet: {sheet.get('name')}")
            for row in ET.fromstring(z.read(target)).iter(S + "row"):
                cells = []
                for c in row.findall(S + "c"):
                    v = c.find(S + "v")
                    if c.get("t") == "s" and v is not None:
                        cells.append(shared[int(v.text)])
                    elif c.get("t") == "inlineStr":
                        cells.append("".join(t.text or "" for t in c.iter(S + "t")))
                    elif v is not None:
                        cells.append(v.text or "")
                line = " | ".join(x.strip() for x in cells if x and x.strip())
                if line:
                    lines.append(line)
    return lines


def knowledge_lines(path):
    return xlsx_lines(path) if path.suffix == ".xlsx" else docx_paragraphs(path)


# ---------- model calls ----------

def chat(model, system, user, schema, usage):
    body = json.dumps({
        "model": model,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "response_format": {"type": "json_schema",
                            "json_schema": {"name": "review", "strict": True, "schema": schema}},
    }).encode()
    request = urllib.request.Request(
        os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1") + "/chat/completions",
        data=body, headers={"Authorization": "Bearer " + os.environ["OPENAI_API_KEY"],
                            "Content-Type": "application/json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                data = json.load(response)
            break
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503) and attempt < 3:
                time.sleep(5 * (attempt + 1))
                continue
            usage["failed"] += 1
            return None
        except (urllib.error.URLError, TimeoutError):
            if attempt < 3:
                time.sleep(5)
                continue
            usage["failed"] += 1
            return None
    usage["calls"] += 1
    usage["input"] += data.get("usage", {}).get("prompt_tokens", 0)
    usage["output"] += data.get("usage", {}).get("completion_tokens", 0)
    try:
        return json.loads(data["choices"][0]["message"]["content"])
    except (KeyError, ValueError, TypeError):
        usage["failed"] += 1
        return None


def embed(texts):
    base = os.environ.get("RIVELL_EMBEDDINGS_BASE_URL", "http://localhost:11434/v1")
    model = os.environ.get("RIVELL_EMBEDDINGS_MODEL", "embeddinggemma:latest")
    out = []
    for i in range(0, len(texts), 32):
        body = json.dumps({"model": model, "input": texts[i:i + 32]}).encode()
        request = urllib.request.Request(base + "/embeddings", data=body, headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + os.environ.get("RIVELL_EMBEDDINGS_API_KEY", "none")})
        with urllib.request.urlopen(request, timeout=120) as response:
            data = json.load(response)
        out.extend(item["embedding"] for item in sorted(data["data"], key=lambda d: d["index"]))
    return out


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    return dot / (math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b)) or 1.0)


# ---------- prompts ----------

FINDINGS_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["issues"],
    "properties": {"issues": {"type": "array", "items": {
        "type": "object",
        "additionalProperties": False,
        "required": ["quote", "missing_topic", "severity", "category", "explanation", "proposed_change"],
        "properties": {
            "quote": {"type": "string", "description": "Exact words copied from the contract "
                      "(10-40 words) where the issue sits; empty for a missing provision."},
            "missing_topic": {"type": "string", "description": "For a missing provision only: "
                              "the topic in at most six words; else empty."},
            "severity": {"type": "string", "enum": ["HIGH", "MEDIUM", "LOW"]},
            "category": {"type": "string", "enum": CATEGORIES},
            "explanation": {"type": "string"},
            "proposed_change": {"type": "string"},
        }}}},
}

SYSTEM = ("You are a senior commercial lawyer. You review a contract for our company against our "
          "playbook and clause library and report what our side must act on before signing.")

TASK = """Report every issue a lawyer on our side must act on:
- WALK_AWAY_BREACH: the clause contains what a walk-away forbids.
- NEEDS_FALLBACK: the clause departs from our preferred position; a listed fallback fixes it.
- ACCEPTABLE_FALLBACK: the clause already sits within a listed fallback; accept and note (LOW).
- AMBIGUOUS: the wording is unclear, so whether it meets our position cannot be decided.
- CROSS_CLAUSE_CONFLICT: two clauses or a clause and a schedule contradict each other.
- MISSING_PROVISION: a provision our playbook expects for this contract is absent.
- UNADDRESSED_BY_PLAYBOOK: the playbook is silent, but the clause is unusual and against us.
Do not report clauses that meet our preferred position. Severity: HIGH for walk-away breaches
and serious risk, MEDIUM for clauses that need a change, LOW for notes. For each issue copy the
contract's exact words into "quote" (10-40 words) so the clause can be found; for a missing
provision leave "quote" empty and name the topic in "missing_topic"."""


def context_block(answer, contract_type):
    return (f"Our company: {answer.get('ourParty')}. Our role: {answer.get('ourRole')}. "
            f"Counterparty: {answer.get('counterparty')}. Contract type: {contract_type}.")


# ---------- baselines ----------

def run_whole(model, answer, contract_type, contract_paras, knowledge, usage):
    user = (context_block(answer, contract_type) + "\n\n" + TASK
            + "".join(f"\n\n=== {name} ===\n" + "\n".join(lines) for name, lines in knowledge)
            + "\n\n=== CONTRACT ===\n" + "\n".join(contract_paras))
    result = chat(model, SYSTEM, user, FINDINGS_SCHEMA, usage)
    return result["issues"] if result else None


def chunks_of(lines, words=220, overlap=40):
    out, current, count = [], [], 0
    for line in lines:
        current.append(line)
        count += len(line.split())
        if count >= words:
            out.append("\n".join(current))
            tail = " ".join("\n".join(current).split()[-overlap:])
            current, count = [tail], overlap
    if current and count > overlap:
        out.append("\n".join(current))
    return out


def run_rag(model, answer, contract_type, contract_paras, knowledge, usage, top_k=6):
    kn_chunks = [f"[{name}] {c}" for name, lines in knowledge for c in chunks_of(lines, 120, 20)]
    ct_chunks = chunks_of(contract_paras)
    kn_vec = embed(kn_chunks)
    ct_vec = embed(ct_chunks)

    def judge(i):
        ranked = sorted(range(len(kn_chunks)), key=lambda j: -cosine(ct_vec[i], kn_vec[j]))[:top_k]
        user = (context_block(answer, contract_type) + "\n\n" + TASK
                + "\nReport issues in the contract excerpt only; you see only part of the contract."
                + "\n\n=== PLAYBOOK AND CLAUSE LIBRARY EXCERPTS ===\n"
                + "\n---\n".join(kn_chunks[j] for j in ranked)
                + "\n\n=== CONTRACT EXCERPT ===\n" + ct_chunks[i])
        result = chat(model, SYSTEM, user, FINDINGS_SCHEMA, usage)
        return result["issues"] if result else []

    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        return [issue for issues in pool.map(judge, range(len(ct_chunks))) for issue in issues]


# ---------- scoring ----------

def _norm(text):
    text = (text or "").replace("’", "'").replace("‘", "'")
    text = text.replace("“", '"').replace("”", '"')
    return re.sub(r"\s+", " ", text).strip().lower()


def locate(quote, paras_norm):
    """Index of the paragraph that holds the quote (or its first / last eight words)."""
    q = _norm(quote).strip(' ."\'')
    if not q:
        return None
    words = q.split()
    for probe in (q, " ".join(words[:8]), " ".join(words[-8:])):
        if len(probe) < 12:
            continue
        for i, p in enumerate(paras_norm):
            if probe in p:
                return i
    return None


def score(answer, contract_paras, issues):
    # every short or numbered paragraph may be a heading, like Rivell's detected headings
    nodes = [{"id": i, "nodeType": "HEADING" if (len(p.split()) <= 15 or re.match(r"^\s*\d", p))
              else "PARAGRAPH", "text": p} for i, p in enumerate(contract_paras)]
    paras_norm = [_norm(p) for p in contract_paras]
    findings, unlocated = [], 0
    for issue in issues:
        if issue.get("category") == "MISSING_PROVISION" and not issue.get("quote", "").strip():
            findings.append({"subjectKind": "ABSENCE", "absenceConcept": issue.get("missing_topic"),
                             "severity": issue.get("severity")})
            continue
        index = locate(issue.get("quote"), paras_norm)
        if index is None:
            unlocated += 1
            continue
        findings.append({"subjectHeadingNodeId": index, "severity": issue.get("severity")})
    result = score_contract(answer, nodes, [], findings)
    result["unlocatedFindings"] = unlocated
    return result


# ---------- run ----------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["whole", "rag"], required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--corpus", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    args = ap.parse_args()

    import run_eval  # contract_type() mapping shared with the Rivell run
    manifest = json.loads((args.corpus / "manifest.json").read_text())
    run_dir = args.out / f"{args.mode}-{args.model}" / time.strftime("%Y%m%d-%H%M%S")
    (run_dir / "raw").mkdir(parents=True)
    usage = {"calls": 0, "failed": 0, "input": 0, "output": 0}
    jobs = []
    for org in manifest["organisations"]:
        knowledge = []
        for kind in ("playbook", "clauseLibrary"):
            if org.get(kind):
                knowledge.append(("PLAYBOOK" if kind == "playbook" else "CLAUSE LIBRARY",
                                  knowledge_lines(args.corpus / org[kind])))
        for rel in org["contracts"]:
            answer = json.loads((args.corpus / "answers" / (pathlib.Path(rel).stem + ".json")).read_text())
            jobs.append((rel, answer, knowledge))

    def review(job):
        rel, answer, knowledge = job
        paras = docx_paragraphs(args.corpus / rel)
        ctype = run_eval.contract_type(answer)
        started = time.time()
        if args.mode == "whole":
            issues = run_whole(args.model, answer, ctype, paras, knowledge, usage)
        else:
            issues = run_rag(args.model, answer, ctype, paras, knowledge, usage)
        if issues is None:
            return rel, None, time.time() - started
        (run_dir / "raw" / (pathlib.Path(rel).stem + ".json")).write_text(
            json.dumps({"issues": issues}, indent=1))
        return rel, score(answer, paras, issues), time.time() - started

    with concurrent.futures.ThreadPoolExecutor(4 if args.mode == "whole" else 2) as pool:
        results = list(pool.map(review, jobs))

    write_summary(run_dir, args, results, usage)
    print(f"report: {run_dir / 'summary.md'}")


def write_summary(run_dir, args, results, usage):
    def pct(n, d):
        return f"{n}/{d} ({100 * n // d if d else 0}%)"

    rows, tot = [], {"issues": 0, "found": 0, "serious": 0, "seriousFound": 0, "compliant": 0,
                     "fa": 0, "mfa": 0, "findings": 0, "unlocated": 0, "failed": 0}
    by_cat = {}
    for rel, s, secs in results:
        if s is None:
            tot["failed"] += 1
            rows.append(f"| {rel} | FAILED | | | | | |")
            continue
        serious = [i for i in s["issues"] if i["severity"] in ("HIGH", "MEDIUM")]
        tot["issues"] += len(s["issues"])
        tot["found"] += s["issuesDetected"]
        tot["serious"] += len(serious)
        tot["seriousFound"] += sum(1 for i in serious if i["detectedSerious"])
        tot["compliant"] += s["compliantSections"]
        tot["fa"] += s["falseAlarmSections"]
        tot["mfa"] += s["seriousFalseAlarmSections"]
        tot["findings"] += s["findings"] + s["unlocatedFindings"]
        tot["unlocated"] += s["unlocatedFindings"]
        for i in s["issues"]:
            c = by_cat.setdefault(i["category"], [0, 0])
            c[0] += i["detected"]
            c[1] += 1
        rows.append(f"| {rel} | {pct(s['issuesDetected'], len(s['issues']))} | "
                    f"{s['falseAlarmSections']}/{s['compliantSections']} | {s['seriousFalseAlarmSections']} | "
                    f"{s['findings'] + s['unlocatedFindings']} | {s['unlocatedFindings']} | {secs:.0f}s |")
    price = PRICES.get(args.model)
    cost = (f"${usage['input'] / 1e6 * price[0] + usage['output'] / 1e6 * price[1]:.2f} (approx.)"
            if price else "unknown price")
    lines = [f"# Baseline {args.mode} · {args.model}", "",
             f"Corpus `{args.corpus}` · {time.strftime('%Y-%m-%d %H:%M')}", "",
             "| Contract | Issues detected | False-alarm sections | of which MEDIUM+ | Findings | Unlocated | Time |",
             "|---|---|---|---|---|---|---|", *rows, "", "## Totals", "",
             f"- Planted issues detected (section-level): {pct(tot['found'], tot['issues'])}",
             f"- HIGH/MEDIUM planted issues caught by a MEDIUM+ finding: {pct(tot['seriousFound'], tot['serious'])}",
             f"- False-alarm compliant sections: {pct(tot['fa'], tot['compliant'])} "
             f"(MEDIUM/HIGH: {pct(tot['mfa'], tot['compliant'])})",
             f"- Findings: {tot['findings']}, of which unlocated (quote not in contract): {tot['unlocated']}",
             f"- Contracts failed: {tot['failed']}",
             f"- Model calls: {usage['calls']} (failed {usage['failed']}), input tokens {usage['input']}, "
             f"output tokens {usage['output']}, cost {cost}", "",
             "| Issue category | Detected |", "|---|---|",
             *[f"| {k} | {pct(v[0], v[1])} |" for k, v in sorted(by_cat.items())]]
    (run_dir / "summary.md").write_text("\n".join(lines) + "\n")
    (run_dir / "results.json").write_text(json.dumps(
        {"results": [{"file": r, "score": s} for r, s, _ in results], "usage": usage}, indent=1))


if __name__ == "__main__":
    main()
