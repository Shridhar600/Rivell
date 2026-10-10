#!/usr/bin/env python3
"""Rivell blind-corpus evaluation.

Drives the real product over HTTP for every organisation in a corpus manifest:
knowledge upload -> approve -> publish -> contract upload -> enrichment -> review,
then scores the result against the corpus answer key.

Each organisation runs in its own tenant (fresh organisation id, fresh backend process),
so one org's published knowledge never leaks into another's review.

Needs: PostgreSQL up (`docker compose up -d` in backend/), JAVA_HOME (JDK 21), and provider
credentials in the environment (TYPESAFE_API_KEY, OPENAI_API_KEY). Credentials are passed
through to the backend and never printed. This is a local evaluation, not a CI test: it
spends provider tokens.

Usage: scripts/eval/run_eval.py --corpus <dir> --out <dir> [--org A] [--no-build]
"""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import time
import uuid

from rivell_api import ApiError, RivellApi, items
from scoring import score_contract, score_knowledge

REPO = pathlib.Path(__file__).resolve().parents[2]
BACKEND = REPO / "backend"
BASE_URL = "http://localhost:8080"
IN_PROGRESS = {"ANALYSIS_QUEUED", "ANALYZING"}


# ---------- backend process ----------

def build_jar():
    subprocess.run(["./mvnw", "-q", "-DskipTests", "package"], cwd=BACKEND, check=True)


def find_jar():
    jars = [p for p in (BACKEND / "target").glob("*.jar") if not p.name.endswith("-plain.jar")]
    if not jars:
        sys.exit("no backend jar in backend/target; run without --no-build")
    return max(jars, key=lambda p: p.stat().st_mtime)


def start_backend(jar, org_id, log_path):
    java = pathlib.Path(os.environ["JAVA_HOME"], "bin", "java") if "JAVA_HOME" in os.environ else "java"
    env = dict(os.environ, RIVELL_DEV_ORGANIZATION_ID=org_id, RIVELL_JOB_WORKER_ENABLED="true")
    log = open(log_path, "w")
    proc = subprocess.Popen([str(java), "-jar", str(jar)], cwd=BACKEND, env=env,
                            stdout=log, stderr=subprocess.STDOUT)
    return proc, log


def stop_backend(proc, log):
    proc.terminate()
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()
    log.close()


# ---------- answer key -> current product inputs ----------
# Isolated on purpose: the product's input vocabulary is expected to grow.

def contract_type(answer):
    text = answer.get("contractType", "").lower()
    if "nda" in text or "non-disclosure" in text or "confidentiality agreement" in text:
        return "NDA"
    if "master services" in text or "msa" in text:
        return "MSA"
    return "OTHER"


def review_profile(answer):
    role = answer.get("ourRole", "").lower()
    law = answer.get("governingLaw", "").lower()
    ctype = contract_type(answer)
    profile = {"contractType": ctype, "paperSource": "COUNTERPARTY_PAPER"}
    if "england" in law or "wales" in law or "scotland" in law or "united kingdom" in law:
        profile["jurisdiction"] = "UK"
    elif any(s in law for s in ("new york", "delaware", "california", "texas", "illinois",
                                "washington", "massachusetts", "united states", "ohio",
                                "wisconsin")):
        profile["jurisdiction"] = "US"
    else:
        profile["jurisdiction"] = "UNSPECIFIED"
    if "mutual" in answer.get("contractType", "").lower():
        profile["ourSideRole"] = "PARTY_A"
    elif "receiv" in role:
        profile["ourSideRole"] = "RECEIVING_PARTY"
    elif "disclos" in role:
        profile["ourSideRole"] = "DISCLOSING_PARTY"
    elif any(w in role for w in ("supplier", "provider", "vendor", "processor", "licensor")):
        profile["ourSideRole"] = "LICENSOR" if "licensor" in role else "SUPPLIER"
    elif any(w in role for w in ("customer", "client", "controller", "licensee", "buyer")):
        profile["ourSideRole"] = "LICENSEE" if "licensee" in role else "CUSTOMER"
    if ctype in ("NDA", "MSA"):
        profile["contractPackKey"] = f"{ctype}_PACK"
    return profile


# ---------- pipeline ----------

def wait_candidates(api, artifact_id, timeout_s=600):
    deadline, last = time.time() + timeout_s, -1
    while time.time() < deadline:
        cands = items(api.get(f"/api/knowledge/artifacts/{artifact_id}/candidates"))
        if cands and len(cands) == last and all(c.get("reviewStatus") for c in cands):
            return cands
        last = len(cands)
        time.sleep(5)
    return items(api.get(f"/api/knowledge/artifacts/{artifact_id}/candidates"))


def run_knowledge(api, corpus, entry, kind, contract_types, errors):
    path = corpus / entry
    query = f"artifactType={kind}" + "".join(f"&declaredContractTypes={t}" for t in contract_types)
    artifact = api.upload(f"/api/knowledge/artifacts?{query}", path)
    cands = wait_candidates(api, artifact["id"])
    approved = []
    for c in cands:
        try:
            api.post(f"/api/knowledge/candidates/{c['id']}/approve", {"note": "eval"})
            approved.append(c["id"])
        except ApiError as e:
            errors.append(f"approve {path.name}: {e.status} {str(e.body)[:120]}")
    return artifact["id"], cands, approved


def wait_review(api, review_id, timeout_s=900):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        review = api.get(f"/api/reviews/{review_id}")
        if review["status"] not in IN_PROGRESS:
            return review
        time.sleep(4)
    return api.get(f"/api/reviews/{review_id}")


def run_contract(api, corpus, entry, answer, raw_dir):
    upload = api.upload("/api/contracts", corpus / entry)
    cid, version = upload["contractId"], upload["versionNumber"]
    base = f"/api/contracts/{cid}/versions/{version}"
    profile = review_profile(answer)
    enrichment = api.post(f"{base}/enrichment", profile)
    review_req = {"clientRequestId": str(uuid.uuid4())}
    if profile.get("contractPackKey"):
        review_req["contractPackKey"] = profile["contractPackKey"]
    review = wait_review(api, api.post(f"{base}/reviews", review_req)["id"])
    structure = api.get(f"{base}/structure")
    nodes = structure if isinstance(structure, list) else structure.get("nodes", [])
    semantics = api.get(f"{base}/semantics")
    findings = items(api.get(f"/api/reviews/{review['id']}/findings?limit=500"))
    raw = {"upload": upload, "profile": profile, "enrichmentStatus": enrichment.get("enrichmentStatus"),
           "review": review, "nodes": nodes, "semantics": semantics, "findings": findings}
    (raw_dir / f"{pathlib.Path(entry).stem}.json").write_text(json.dumps(raw, indent=1))
    score = score_contract(answer, nodes, semantics.get("clauses", []), findings)
    score.update(file=entry, parseStatus=upload.get("parseStatus"), profile=profile,
                 enrichmentStatus=enrichment.get("enrichmentStatus"), reviewStatus=review["status"])
    return score


def load_answer(corpus, entry):
    path = corpus / "answers" / (pathlib.Path(entry).stem + ".json")
    return json.loads(path.read_text()) if path.exists() else {}


def run_org(api, corpus, org, raw_dir):
    errors = []
    result = {"org": org["id"], "knowledge": [], "contracts": [], "errors": errors}
    contract_answers = {c: load_answer(corpus, c) for c in org["contracts"]}
    types = sorted({contract_type(a) for a in contract_answers.values() if a})
    approved = []
    for entry, kind in ((org.get("playbook"), "PLAYBOOK"), (org.get("clauseLibrary"), "CLAUSE_LIBRARY")):
        if not entry:
            continue
        try:
            _, cands, ok = run_knowledge(api, corpus, entry, kind, types, errors)
            approved += ok
            score = score_knowledge(load_answer(corpus, entry), cands)
            result["knowledge"].append(dict(score, file=entry, approved=len(ok)))
        except ApiError as e:
            errors.append(f"knowledge {entry}: {e}")
    if approved:
        try:
            api.post("/api/knowledge/publications", {"candidateIds": approved, "note": "eval"})
        except ApiError as e:
            errors.append(f"publish: {e}")
    for entry, answer in contract_answers.items():
        try:
            result["contracts"].append(run_contract(api, corpus, entry, answer, raw_dir))
        except (ApiError, TimeoutError) as e:
            errors.append(f"contract {entry}: {e}")
    for path in ("/api/reviews?limit=25", "/api/knowledge/artifacts"):
        try:
            api.get(path)
        except ApiError as e:
            errors.append(f"list health {path}: {e.status}")
    return result


# ---------- report ----------

def pct(n, d):
    return f"{n}/{d} ({100 * n // d}%)" if d else "n/a"


def write_report(out, results, meta):
    lines = [f"# Eval run {meta['runId']}", "",
             f"Corpus `{meta['corpus']}` · git `{meta['git']}` · {meta['started']}", ""]
    tot = {k: 0 for k in ("sx", "sm", "cl", "cc", "ix", "id", "cs", "fa", "pos", "posf", "cand", "candc")}
    lines += ["## Knowledge", "", "| File | Candidates | With concept | Positions found | Unmatched candidates |",
              "|---|---|---|---|---|"]
    for r in results:
        for k in r["knowledge"]:
            tot["pos"] += k["expectedPositions"]; tot["posf"] += k["positionsFound"]
            tot["cand"] += k["candidates"]; tot["candc"] += k["candidatesWithConcept"]
            lines.append(f"| {k['file']} | {k['candidates']} | {pct(k['candidatesWithConcept'], k['candidates'])} "
                         f"| {pct(k['positionsFound'], k['expectedPositions'])} | {k['unmatchedCandidates']} |")
    lines += ["", "## Contracts", "",
              "| File | Review | Sections matched | Clauses classified | Findings (absence) | Issues detected | False-alarm sections |",
              "|---|---|---|---|---|---|---|"]
    for r in results:
        for c in r["contracts"]:
            tot["sx"] += c["sectionsExpected"]; tot["sm"] += c["sectionsMatched"]
            tot["cl"] += c["clauses"]; tot["cc"] += c["clausesClassified"]
            tot["ix"] += len(c["issues"]); tot["id"] += c["issuesDetected"]
            tot["cs"] += c["compliantSections"]; tot["fa"] += c["falseAlarmSections"]
            lines.append(f"| {c['file']} | {c['reviewStatus']} | {pct(c['sectionsMatched'], c['sectionsExpected'])} "
                         f"| {pct(c['clausesClassified'], c['clauses'])} | {c['findings']} ({c['absenceFindings']}) "
                         f"| {pct(c['issuesDetected'], len(c['issues']))} | {pct(c['falseAlarmSections'], c['compliantSections'])} |")
    by_cat = {}
    for r in results:
        for c in r["contracts"]:
            for i in c["issues"]:
                d, n = by_cat.get(i["category"], (0, 0))
                by_cat[i["category"]] = (d + i["detected"], n + 1)
    lines += ["", "## Totals", "",
              f"- Sections matched: {pct(tot['sm'], tot['sx'])}",
              f"- Clauses classified: {pct(tot['cc'], tot['cl'])}",
              f"- Knowledge candidates with concept: {pct(tot['candc'], tot['cand'])}",
              f"- Playbook positions found: {pct(tot['posf'], tot['pos'])}",
              f"- Planted issues detected (section-level): {pct(tot['id'], tot['ix'])}",
              f"- False-alarm compliant sections: {pct(tot['fa'], tot['cs'])}", "",
              "| Issue category | Detected |", "|---|---|"]
    lines += [f"| {k} | {pct(*v)} |" for k, v in sorted(by_cat.items())]
    errs = [f"- {r['org']}: {e}" for r in results for e in r["errors"]]
    lines += ["", "## Errors", ""] + (errs or ["- none"])
    (out / "summary.md").write_text("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    ap.add_argument("--org", action="append", help="limit to these org ids")
    ap.add_argument("--no-build", action="store_true")
    args = ap.parse_args()

    missing = [k for k in ("TYPESAFE_API_KEY", "OPENAI_API_KEY") if not os.environ.get(k)]
    if missing:
        sys.exit(f"missing environment variables: {', '.join(missing)}")
    manifest = json.loads((args.corpus / "manifest.json").read_text())
    all_orgs = manifest.get("orgs") or manifest.get("organisations") or []
    orgs = [o for o in all_orgs if not args.org or o["id"] in args.org]

    run_id = time.strftime("%Y%m%d-%H%M%S")
    out = args.out / run_id
    raw_dir = out / "raw"
    raw_dir.mkdir(parents=True)
    git = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=REPO,
                         capture_output=True, text=True).stdout.strip()
    meta = {"runId": run_id, "corpus": str(args.corpus), "git": git,
            "started": time.strftime("%Y-%m-%d %H:%M")}

    if not args.no_build:
        build_jar()
    jar = find_jar()
    api = RivellApi(BASE_URL)
    results = []
    for org in orgs:
        org_id = str(uuid.uuid4())
        proc, log = start_backend(jar, org_id, out / f"backend-{org['id']}.log")
        try:
            api.wait_ready()
            print(f"org {org['id']}: tenant {org_id}", flush=True)
            results.append(run_org(api, args.corpus, org, raw_dir))
        finally:
            stop_backend(proc, log)
    (out / "results.json").write_text(json.dumps({"meta": meta, "results": results}, indent=1))
    write_report(out, results, meta)
    print(f"report: {out / 'summary.md'}")


if __name__ == "__main__":
    main()
