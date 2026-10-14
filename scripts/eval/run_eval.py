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
from collections import Counter
import os
import pathlib
import re
import subprocess
import sys
import time
import uuid

import prices
from provider_guard import LogWatch, ProviderFatal, preflight
from redline_flow import run_redline
from rivell_api import ApiError, RivellApi, items
from scoring import score_contract, score_knowledge
import p3_metrics
import p4_metrics

REPO = pathlib.Path(__file__).resolve().parents[2]
BACKEND = REPO / "backend"
# a dedicated port so the eval never talks to a developer's running backend
PORT = os.environ.get("RIVELL_EVAL_PORT", "18080")
BASE_URL = f"http://localhost:{PORT}"
IN_PROGRESS = {"ANALYSIS_QUEUED", "ANALYZING"}
SPOTTED = "SPOTTED_IN_WHOLE_CONTRACT_READ"  # finding raised by the opt-in whole-contract read
NEEDS_LOOK = "NEEDS_YOUR_LOOK"  # engine v2: the reviewers disagreed or no second opinion; the user decides
WATCH = None  # LogWatch of the running backend: aborts the run when a provider cannot serve it


def guard():
    if WATCH is not None:
        WATCH.check()


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
    env = dict(os.environ, RIVELL_DEV_ORGANIZATION_ID=org_id, RIVELL_JOB_WORKER_ENABLED="true",
               SERVER_PORT=PORT)
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
    """Answer-key free text -> the product's contract type code. Order matters: an SOW under an
    MSA is an SOW; a data processing addendum is a DPA."""
    text = answer.get("contractType", "").lower()
    if "data processing" in text or "dpa" in text.split():
        return "DPA"
    if "statement of work" in text or "sow" in text.split():
        return "SOW"
    if "saas" in text or "subscription" in text or "order form" in text:
        return "SAAS"
    if "licen" in text:
        return "SWL"
    if "nda" in text or "non-disclosure" in text or "confidentiality agreement" in text:
        if "mutual" in text:
            return "MNDA"
        if "one-way" in text or "one way" in text or "unilateral" in text:
            return "ONDA"
        return "NDA"
    if "master services" in text or "msa" in text.split() or "services agreement" in text:
        return "MSA"
    return "OTHER"


def review_profile(answer):
    role = answer.get("ourRole", "").lower()
    law = answer.get("governingLaw", "").lower()
    ctype = contract_type(answer)
    profile = {"contractType": ctype, "paperSource": "COUNTERPARTY_PAPER"}
    if re.search(r"england|(?<!south )wales|scotland|united kingdom", law):
        profile["jurisdiction"] = "UK"
    elif any(s in law for s in ("new york", "delaware", "california", "texas", "illinois",
                                "washington", "massachusetts", "united states", "ohio",
                                "wisconsin")):
        profile["jurisdiction"] = "US"
    else:
        profile["jurisdiction"] = "UNSPECIFIED"
    if "mutual" in answer.get("contractType", "").lower() and ctype in ("NDA", "MNDA", "ONDA"):
        profile["ourSideRole"] = "PARTY_A"
    elif "receiv" in role:
        profile["ourSideRole"] = "RECEIVING_PARTY"
    elif "disclos" in role:
        profile["ourSideRole"] = "DISCLOSING_PARTY"
    else:
        # our role comes first ("controller; the counterparty is the processor"); British spelling
        # of licensee/licensor is common
        ours = role.split(";")[0]
        if re.search(r"licen[cs]ee", ours):
            profile["ourSideRole"] = "LICENSEE"
        elif re.search(r"licen[cs]or", ours):
            profile["ourSideRole"] = "LICENSOR"
        elif any(w in ours for w in ("supplier", "provider", "vendor", "processor")):
            profile["ourSideRole"] = "SUPPLIER"
        elif any(w in ours for w in ("customer", "client", "controller", "buyer")):
            profile["ourSideRole"] = "CUSTOMER"
    if ctype in ("NDA", "MNDA", "ONDA"):
        profile["contractPackKey"] = "NDA_PACK"
    elif ctype == "MSA":
        profile["contractPackKey"] = "MSA_PACK"
    return profile


# ---------- pipeline ----------

def wait_candidates(api, artifact_id, timeout_s=600):
    deadline, last = time.time() + timeout_s, -1
    while time.time() < deadline:
        guard()
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
        guard()
        review = api.get(f"/api/reviews/{review_id}")
        if review["status"] not in IN_PROGRESS:
            return review
        time.sleep(4)
    return api.get(f"/api/reviews/{review_id}")


def run_contract(api, corpus, entry, answer, raw_dir, redline=True):
    timings, t0 = {}, time.monotonic()
    upload = api.upload("/api/contracts", corpus / entry)
    cid, version = upload["contractId"], upload["versionNumber"]
    base = f"/api/contracts/{cid}/versions/{version}"
    profile = review_profile(answer)
    timings["parseS"], t0 = round(time.monotonic() - t0, 1), time.monotonic()
    enrichment = api.post(f"{base}/enrichment", profile)
    timings["enrichS"], t0 = round(time.monotonic() - t0, 1), time.monotonic()
    review_req = {"clientRequestId": str(uuid.uuid4())}
    if profile.get("contractPackKey"):
        review_req["contractPackKey"] = profile["contractPackKey"]
    review = wait_review(api, api.post(f"{base}/reviews", review_req)["id"])
    timings["reviewS"], t0 = round(time.monotonic() - t0, 1), time.monotonic()
    structure = api.get(f"{base}/structure")
    nodes = structure if isinstance(structure, list) else structure.get("nodes", [])
    semantics = api.get(f"{base}/semantics")
    findings = items(api.get(f"/api/reviews/{review['id']}/findings?limit=500"))
    for finding in findings:
        # one detail read per finding: keep the quality subset the P4 metrics are defined on
        detail = api.get(f"/api/reviews/{review['id']}/findings/{finding['id']}")
        quality = detail.get("quality") or {}
        finding["quality"] = {key: quality[key] for key in p4_metrics.QUALITY_KEYS
                              if quality.get(key) is not None}
    coverage = api.get(f"{base}/coverage")
    checks = (api.get(f"/api/reviews/{review['id']}/diagnostics").get("analysis") or {}).get("checks") or []
    clauses = semantics.get("clauses", [])
    raw = {"upload": upload, "profile": profile, "enrichmentStatus": enrichment.get("enrichmentStatus"),
           "review": review, "nodes": nodes, "semantics": semantics, "findings": findings,
           "coverage": coverage, "checks": checks}
    score = score_contract(answer, nodes, clauses, findings)
    if any(f.get("observationKind") == SPOTTED for f in findings):
        # the spotter only adds findings where no position judged a clause, so the same run
        # scored without them is Rivell alone on identical decisions
        alone = score_contract(answer, nodes, clauses,
                               [f for f in findings if f.get("observationKind") != SPOTTED])
        score["withoutSpotted"] = {k: alone[k] for k in ("issues", "issuesDetected", "falseAlarmSections",
                                                         "seriousFalseAlarmSections", "findings")}
        score["spottedFindings"] = sum(f.get("observationKind") == SPOTTED for f in findings)
    score["decidedBy"] = dict(Counter(f.get("decidedBy") or "NONE" for f in findings))
    if any(f.get("decidedBy") == NEEDS_LOOK for f in findings):
        # the same run scored without the "needs your look" group: what the group adds for the user
        rest = score_contract(answer, nodes, clauses,
                              [f for f in findings if f.get("decidedBy") != NEEDS_LOOK])
        score["withoutNeedsYourLook"] = {k: rest[k] for k in ("issues", "issuesDetected", "falseAlarmSections",
                                                              "seriousFalseAlarmSections", "findings")}
    # engine v2 P4: the levers and the diagnostics-only measurements, pure functions of this run
    score["search"] = p4_metrics.search_metrics(checks)
    score["advisory"] = p4_metrics.advisory_metrics(answer, nodes, clauses, findings)
    score["omissions"] = p4_metrics.omission_metrics(answer, findings)
    score["lookReasons"] = p4_metrics.look_reasons(findings)
    score["triageVsRead"] = p4_metrics.triage_vs_read(answer, nodes, clauses, checks)
    # engine v2 P3: definition extraction and reference resolution, pure functions of this run
    score["definitions"] = p3_metrics.definition_metrics(semantics, checks)
    score["references"] = p3_metrics.reference_metrics(semantics)
    for lever, drop in (("withoutSearchFound", p4_metrics.drop_search_found),
                        ("withoutAdvisory", p4_metrics.drop_advisory),
                        ("withoutOmissions", p4_metrics.drop_omissions)):
        score[lever] = p4_metrics.lever_score(
            score_contract, answer, nodes, clauses, findings, drop)
    # engine v2 P6 R1: the same run re-scored with the second-opinion-raised items at LOW
    score["withoutSeverityRaise"] = p4_metrics.severity_raise_score(
        score_contract, answer, nodes, clauses, findings)
    score["coverage"] = coverage
    score["checkStatus"] = {}
    for c in checks:
        score["checkStatus"][c["status"]] = score["checkStatus"].get(c["status"], 0) + 1
    score["checkFailures"] = sorted({f"{c['key'].split(':')[0]} {c.get('failureType') or c.get('degradedReason')}"
                                     for c in checks if c["status"] in ("FAILED", "DEGRADED")})
    if redline and review["status"] == "COMPLETED":
        try:
            raw["redline"] = run_redline(api, review["id"], (corpus / entry).read_bytes(), nodes, entry)
        except (ApiError, TimeoutError) as e:
            raw["redline"] = {"stage": "error", "error": str(e)[:300]}
        timings["redlineS"] = round(time.monotonic() - t0, 1)
        score["redline"] = raw["redline"]
    (raw_dir / f"{pathlib.Path(entry).stem}.json").write_text(json.dumps(raw, indent=1))
    score.update(file=entry, parseStatus=upload.get("parseStatus"), profile=profile, timings=timings,
                 enrichmentStatus=enrichment.get("enrichmentStatus"), reviewStatus=review["status"])
    return score


def load_answer(corpus, entry):
    path = corpus / "answers" / (pathlib.Path(entry).stem + ".json")
    if not path.exists():
        return {}
    answer = json.loads(path.read_text())
    # newer corpora (holdout3) key a playbook as a bare list of positions
    return {"positions": answer} if isinstance(answer, list) else answer


def load_orgs(corpus):
    """The manifest's organisations, with every file entry as a corpus-relative path. Newer corpora
    (holdout3) give each entry as an object {"path": ..., ...}."""
    manifest = json.loads((corpus / "manifest.json").read_text())

    def path(entry):
        return entry["path"] if isinstance(entry, dict) else entry

    orgs = []
    for org in manifest.get("orgs") or manifest.get("organisations") or []:
        org = dict(org)
        for kind in ("playbook", "clauseLibrary"):
            if org.get(kind):
                org[kind] = path(org[kind])
        for kind in ("contracts", "precedents"):
            if kind in org:
                org[kind] = [path(e) for e in org[kind]]
        orgs.append(org)
    return orgs


def select_orgs(orgs, org_ids, stems):
    """Organisations to run: those named (all when none), each with only the named contracts
    (by file stem; all when none). An organisation left with no contract is dropped."""
    selected = []
    for org in orgs:
        if org_ids and org["id"] not in org_ids:
            continue
        if stems:
            org = dict(org, contracts=[c for c in org["contracts"] if pathlib.Path(c).stem in stems])
            if not org["contracts"]:
                continue
        selected.append(org)
    return selected


def run_org(api, corpus, org, raw_dir, redline=True):
    errors = []
    result = {"org": org["id"], "knowledge": [], "contracts": [], "errors": errors}
    contract_answers = {c: load_answer(corpus, c) for c in org["contracts"]}
    types = sorted({contract_type(a) for a in contract_answers.values() if a})
    approved = []
    for entry, kind in ((org.get("playbook"), "PLAYBOOK"), (org.get("clauseLibrary"), "CLAUSE_LIBRARY")):
        if not entry:
            continue
        try:
            artifact_id, cands, ok = run_knowledge(api, corpus, entry, kind, types, errors)
            approved += ok
            coverage = api.get(f"/api/knowledge/artifacts/{artifact_id}/coverage")
            (raw_dir / f"knowledge-{pathlib.Path(entry).stem}.json").write_text(json.dumps(cands, indent=1))
            score = score_knowledge(load_answer(corpus, entry), cands)
            result["knowledge"].append(dict(score, file=entry, approved=len(ok), coverage=coverage))
        except ApiError as e:
            errors.append(f"knowledge {entry}: {e}")
    if approved:
        try:
            api.post("/api/knowledge/publications", {"candidateIds": approved, "note": "eval"})
        except ApiError as e:
            errors.append(f"publish: {e}")
    for entry, answer in contract_answers.items():
        try:
            result["contracts"].append(run_contract(api, corpus, entry, answer, raw_dir, redline))
        except (ApiError, TimeoutError) as e:
            errors.append(f"contract {entry}: {e}")
    for path in ("/api/reviews?limit=25", "/api/knowledge/artifacts"):
        try:
            api.get(path)
        except ApiError as e:
            errors.append(f"list health {path}: {e.status}")
    return result


def model_usage(log_path):
    """Totals of the backend's metadata-only model_execution log events, by kind and spec."""
    usage = {}
    for line in open(log_path, errors="replace"):
        if "model_execution" not in line:
            continue
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if event.get("message") != "model_execution":
            continue
        if event.get("outcome") == "RETRIED":
            continue
        row = usage.setdefault(f"{event.get('kind')}:{event.get('spec')}",
                               {"calls": 0, "failed": 0, "ms": 0, "inputTokens": 0, "outputTokens": 0,
                                "costUsd": 0.0, "unpricedTokens": 0, "models": []})
        tokens_in, tokens_out = int(event.get("inputTokens") or 0), int(event.get("outputTokens") or 0)
        row["calls"] += 1
        row["failed"] += event.get("outcome") == "FAILED"
        row["ms"] += int(event.get("durationMs") or 0)
        row["inputTokens"] += tokens_in
        row["outputTokens"] += tokens_out
        call_cost = prices.cost(event.get("model"), tokens_in, tokens_out)
        if call_cost is None:
            row["unpricedTokens"] += tokens_in + tokens_out
        else:
            row["costUsd"] += call_cost
        if event.get("model") and event["model"] not in row["models"]:
            row["models"].append(event["model"])
    return usage


# ---------- report ----------

def pct(n, d):
    return f"{n}/{d} ({100 * n // d}%)" if d else "n/a"


CHECKS = ("opens", "preserved", "local", "applied")
MAIN_LOCATIONS = ("BODY", "TABLE", "TEXT_BOX")


def coverage_report(results):
    rows = [c for r in results for c in r["knowledge"] + r["contracts"]
            if (c.get("coverage") or {}).get("checked")]
    if not rows:
        return []
    lines = ["", "## Parse coverage (independent raw-file read)", "",
             "| File | Main text captured | Not represented (header/footer/notes/comments, words) "
             "| Extra words | Gaps by location |", "|---|---|---|---|---|"]
    tot_src = tot_cap = tot_other = tot_extra = 0
    for c in rows:
        cov = c["coverage"]
        main = [t for t in cov["totals"] if t["location"] in MAIN_LOCATIONS]
        src = sum(t["sourceWords"] for t in main)
        cap = sum(t["capturedWords"] for t in main)
        other = sum(t["sourceWords"] - t["capturedWords"] for t in cov["totals"] if t["location"] not in MAIN_LOCATIONS)
        by_loc = {}
        for g in cov["gaps"]:
            by_loc[g["location"]] = by_loc.get(g["location"], 0) + g["words"]
        tot_src += src; tot_cap += cap; tot_other += other; tot_extra += cov["extraWords"]
        exact = "" if cov["exact"] else " (approx.)"
        lines.append(f"| {c['file']} | {pct(cap, src)}{exact} | {other} | {cov['extraWords']} "
                     f"| {', '.join(f'{k} {v}' for k, v in sorted(by_loc.items())) or '-'} |")
    lines += ["", f"- Main text captured: {pct(tot_cap, tot_src)}; not represented elsewhere: {tot_other} words; "
                  f"extra (invented) words: {tot_extra}"]
    return lines


def redline_report(results):
    rows = [c for r in results for c in r["contracts"] if "redline" in c]
    if not rows:
        return []
    lines = ["", "## Redline round trip", "",
             "| File | Accepted / rejected | Changes | Redline | Opens | Original preserved | Changes local "
             "| Applied | Re-parse (status, headings, sees ins/del) | First problem |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    done = clean = with_changes = 0
    for c in rows:
        x = c["redline"]
        checks = x.get("checks") or {}
        rp = x.get("reparse") or {}
        ok = [checks.get(k) for k in CHECKS]
        done += x.get("redlineStatus") == "COMPLETED"
        with_changes += bool(x.get("changes"))
        clean += bool(checks) and all(ok)
        status = x.get("redlineStatus") or f"stopped at {x.get('stage')} {x.get('blockers') or x.get('error') or ''}"
        if x.get("failureCode"):
            status += f" {x['failureCode']} {x.get('changeFailures') or ''}"
        rp_text = (f"{rp.get('parseStatus')}, {rp.get('headings')}/{rp.get('originalHeadings')}, "
                   f"{'Y' if rp.get('seesInsertedText') else 'N'}/{'Y' if rp.get('seesDeletedText') else 'N'}"
                   if rp and "error" not in rp else rp.get("error", "-"))
        problem = (checks.get("problems") or ["-"])[0][:90].replace("|", "/")
        lines.append(f"| {c['file']} | {x.get('accepted', 0)} / {x.get('rejected', 0)} | {x.get('changes', '-')} "
                     f"| {status} | " + " | ".join("-" if v is None else ("✅" if v else "❌") for v in ok)
                     + f" | {rp_text} | {problem} |")
    lines += ["", f"- Contracts with at least one accepted change: {with_changes}/{len(rows)}; "
                  f"redlines completed: {pct(done, len(rows))}; all four independent checks pass: "
                  f"{pct(clean, with_changes)} of redlines with changes"]
    return lines


def usage_report(results):
    lines = ["", "## Cost and latency", "",
             "| Org | Model calls | Failed | Input tokens | Output tokens | Cost (USD) | Model time (s, summed) "
             "| Contract wall time p50 / max (s) |", "|---|---|---|---|---|---|---|---|"]
    by_spec = {}
    for r in results:
        u = r.get("modelUsage") or {}
        tot = {k: sum(v.get(k, 0) for v in u.values())
               for k in ("calls", "failed", "inputTokens", "outputTokens", "ms", "costUsd")}
        for spec, v in u.items():
            agg = by_spec.setdefault(spec, {"calls": 0, "failed": 0, "inputTokens": 0, "outputTokens": 0,
                                            "ms": 0, "costUsd": 0.0})
            for k in ("calls", "failed", "inputTokens", "outputTokens", "ms", "costUsd"):
                agg[k] += v.get(k, 0)
            agg.setdefault("models", set()).update(v.get("models", []))
        walls = sorted(sum(c.get("timings", {}).values()) for c in r["contracts"])
        wall = f"{walls[len(walls) // 2]:.0f} / {walls[-1]:.0f}" if walls else "-"
        lines.append(f"| {r['org']} | {tot['calls']} | {tot['failed']} | {tot['inputTokens']} | {tot['outputTokens']} "
                     f"| {tot['costUsd']:.3f} | {tot['ms'] / 1000:.0f} | {wall} |")
    failed = sum(v["failed"] for r in results for v in (r.get("modelUsage") or {}).values())
    if failed:
        lines += ["", f"- ⚠️ {failed} model calls failed (see the Failed column; check provider keys)."]
    lines += ["", "| Kind:spec | Model | Calls | Failed | Input tokens | Output tokens | Cost (USD) | Model time (s) |",
              "|---|---|---|---|---|---|---|---|"]
    for spec, v in sorted(by_spec.items(), key=lambda kv: -kv[1]["ms"]):
        lines.append(f"| {spec} | {', '.join(sorted(v.get('models', ()))) or '-'} | {v['calls']} | {v['failed']} "
                     f"| {v['inputTokens']} | {v['outputTokens']} | {v['costUsd']:.3f} | {v['ms'] / 1000:.0f} |")
    return lines


def cost_speed_line(results, meta):
    """One line for step-6 comparisons: list-price cost and wall time per contract."""
    contracts = [c for r in results for c in r["contracts"]]
    usage = [v for r in results for v in (r.get("modelUsage") or {}).values()]
    cost = sum(v.get("costUsd", 0) for v in usage)
    unpriced = sum(v.get("unpricedTokens", 0) for v in usage)
    calls = sum(v["calls"] for v in usage)
    walls = sorted(sum(c.get("timings", {}).values()) for c in contracts)
    if not contracts:
        return "- Cost and speed: no contracts"
    return (f"- Cost and speed: ${cost:.2f} at list prices (${cost / len(contracts):.3f} per contract"
            + (f"; {unpriced} tokens unpriced" if unpriced else "") + f"), {calls / len(contracts):.0f} model calls "
            f"per contract; contract wall time p50 {walls[len(walls) // 2]:.0f} s, max {walls[-1]:.0f} s; "
            f"run {meta.get('wallS', 0) / 60:.0f} min")


def check_line(results):
    counts = {}
    for r in results:
        for c in r["contracts"]:
            for status, n in (c.get("checkStatus") or {}).items():
                counts[status] = counts.get(status, 0) + n
    text = ", ".join(f"{k} {v}" for k, v in sorted(counts.items()))
    bad = counts.get("FAILED", 0) + counts.get("DEGRADED", 0)
    return f"- Review checks: {text}" + (" ⚠️ some clauses were not analysed (see Errors)" if bad else "")


def serious_caught(results):
    issues = [i for r in results for c in r["contracts"] for i in c["issues"]
              if i.get("severity") in ("HIGH", "MEDIUM")]
    return sum(1 for i in issues if i.get("detectedSerious")), len(issues)


def volume_line(results):
    """Findings a lawyer reads, against issues found: the section-level scorer rewards volume."""
    contracts = [c for r in results for c in r["contracts"]]
    findings = sum(c["findings"] for c in contracts)
    found = sum(c["issuesDetected"] for c in contracts)
    per_ten = f"{10 * found / findings:.1f}" if findings else "-"
    return (f"- Findings: {findings} ({findings / len(contracts):.1f} per contract); "
            f"planted issues found per 10 findings: {per_ten}") if contracts else "- Findings: 0"


def spotting_line(results):
    contracts = [c for r in results for c in r["contracts"]]
    spotted = sum(c.get("spottedFindings", 0) for c in contracts)
    if not spotted:
        return []
    alone = [c.get("withoutSpotted") or c for c in contracts]
    issues = [i for a in alone for i in a["issues"]]
    serious = [i for i in issues if i.get("severity") in ("HIGH", "MEDIUM")]
    compliant = sum(c["compliantSections"] for c in contracts)
    findings = sum(a["findings"] for a in alone)
    return [f"- Without the {spotted} spotted findings (Rivell alone, same run): issues "
            f"{pct(sum(a['issuesDetected'] for a in alone), len(issues))}, serious "
            f"{pct(sum(1 for i in serious if i.get('detectedSerious')), len(serious))}, false alarms "
            f"{pct(sum(a['falseAlarmSections'] for a in alone), compliant)} (MEDIUM/HIGH: "
            f"{pct(sum(a['seriousFalseAlarmSections'] for a in alone), compliant)}), findings {findings}"]


def needs_look_line(results):
    """Engine v2: findings a second opinion settled, and the "needs your look" group the user reads."""
    contracts = [c for r in results for c in r["contracts"]]
    looks = sum((c.get("decidedBy") or {}).get(NEEDS_LOOK, 0) for c in contracts)
    second = sum((c.get("decidedBy") or {}).get("SECOND_OPINION", 0) for c in contracts)
    if not looks and not second:
        return []
    rest = [c.get("withoutNeedsYourLook") or c for c in contracts]
    only_look = sum(c["issuesDetected"] for c in contracts) - sum(r["issuesDetected"] for r in rest)
    on_compliant = sum(c["falseAlarmSections"] for c in contracts) - sum(r["falseAlarmSections"] for r in rest)
    return [f"- Engine v2: {second} findings decided with a second opinion; {looks} \"needs your look\" "
            f"({looks / len(contracts):.1f} per contract); planted issues only they reach: {only_look}; "
            f"compliant sections only they touch: {on_compliant}"]


P4_LOOK_REASONS = ("second_opinion_disagrees", "second_opinion_unavailable",
                   "playbook_silent", "omission_unsure", "other")
P4_D12B_CELLS = ("flaggedPicked", "flaggedUnpicked", "clearPicked", "clearUnpicked")


def p4_data(contract):
    """Any engine v2 P4 activity in one scored contract; a v1 contract has none."""
    search = contract.get("search") or {}
    return bool(search.get("route") or search.get("found")
                or (contract.get("advisory") or {}).get("picks")
                or (contract.get("omissions") or {}).get("list")
                or any((contract.get("lookReasons") or {}).values()))


def _d12b_totals(contracts, key):
    totals = {cell: 0 for cell in P4_D12B_CELLS}
    for contract in contracts:
        for cell, count in ((contract.get("triageVsRead") or {}).get(key) or {}).items():
            if cell in totals:
                totals[cell] += count
    return totals


def _d12b_line(label, totals):
    return (f"- D12b {label} (triage x read): flagged+picked {totals['flaggedPicked']}, "
            f"flagged+not picked {totals['flaggedUnpicked']}, clear+picked {totals['clearPicked']}, "
            f"clear+not picked {totals['clearUnpicked']}")


def p4_lines(results):
    """Engine v2 P4 measurements: search, advisory picks, omissions, look reasons, D12b, levers.

    Only emitted when at least one contract carries P4 activity, so a v1 run's report is unchanged.
    """
    contracts = [c for r in results for c in r.get("contracts", [])]
    if not any(p4_data(contract) for contract in contracts):
        return []
    n = len(contracts)
    searched = [c.get("search") or {} for c in contracts]
    advisory = [c.get("advisory") or {} for c in contracts]
    omissions = [c.get("omissions") or {} for c in contracts]
    routes = sum(s.get("route", 0) for s in searched)
    found = sum(s.get("found", 0) for s in searched)
    addressing = sum(s.get("addressing", 0) for s in searched)
    top1 = sum(s.get("top1Confirmed", 0) for s in searched)
    top1_checks = sum(s.get("top1Checks", 0) for s in searched)
    only_meets = sum(s.get("addressedOnlyBySearchMeets", 0) for s in searched)
    picks = sum(a.get("picks", 0) for a in advisory)
    high = sum(a.get("high", 0) for a in advisory)
    hits = sum(a.get("hits", 0) for a in advisory)
    high_hits = sum(a.get("highHits", 0) for a in advisory)
    medium = sum(o.get("medium", 0) for o in omissions)
    low = sum(o.get("low", 0) for o in omissions)
    alarms = sum(o.get("alarms", 0) for o in omissions)
    reasons = {key: sum((c.get("lookReasons") or {}).get(key, 0) for c in contracts)
               for key in P4_LOOK_REASONS}
    lines = ["", "## Engine v2 P4", "",
             f"- Search: {routes} routes ({routes / n:.1f}/contract); {found} positions found; "
             f"addressing {pct(addressing, found)}; top-1 confirmed {pct(top1, top1_checks)}; "
             f"addressed only by search-meets {pct(only_meets, routes)}",
             f"- Advisory picks: {picks} ({picks / n:.1f}/contract; as if all were HIGH: {high}); "
             f"issue-section hits {pct(hits, picks)} (HIGH {pct(high_hits, high)})",
             f"- Omissions: MEDIUM {medium} ({medium / n:.1f}/contract), LOW {low} "
             f"({low / n:.1f}/contract); alarms (MEDIUM matching no MISSING_PROVISION issue) {alarms}"]
    for contract in contracts:
        for omission in (contract.get("omissions") or {}).get("list", []):
            presence = omission.get("presence")
            presence_text = "-" if presence is None else f"{presence:g}"
            lines.append(f"- Omission: {contract.get('file', '?')}: {omission.get('label') or '?'} "
                         f"· {omission.get('severity') or '?'} · {presence_text} · "
                         f"{'matched' if omission.get('matched') else 'unmatched'}")
    lines.append("- Look reasons: " + ", ".join(f"{key} {reasons[key]}" for key in P4_LOOK_REASONS))
    lines.append(_d12b_line("unaddressed-by-playbook sections",
                            _d12b_totals(contracts, "unaddressed")))
    lines.append(_d12b_line("compliant sections", _d12b_totals(contracts, "compliant")))
    for lever, label in (("withoutSearchFound", "search-found"),
                         ("withoutAdvisory", "advisory picks"),
                         ("withoutOmissions", "omissions")):
        with_issues = sum(c.get("issuesDetected", 0) for c in contracts)
        without_issues = sum((c.get(lever) or {}).get("issuesDetected", 0) for c in contracts)
        with_alarms = sum(c.get("falseAlarmSections", 0) for c in contracts)
        without_alarms = sum((c.get(lever) or {}).get("falseAlarmSections", 0) for c in contracts)
        lines.append(f"- Without {label}: issues detected {with_issues} -> {without_issues}; "
                     f"false-alarm sections {with_alarms} -> {without_alarms}")
    severity_raise = [c.get("withoutSeverityRaise") or {} for c in contracts]
    raised = sum(s.get("raised", 0) for s in severity_raise)
    with_caught = sum(p4_metrics.serious_caught(c.get("issues") or []) for c in contracts)
    without_caught = sum(s.get("seriousCaught", 0) for s in severity_raise)
    with_alarms = sum(c.get("seriousFalseAlarmSections", 0) for c in contracts)
    without_alarms = sum(s.get("seriousFalseAlarmSections", 0) for s in severity_raise)
    lines.append(f"- Severity R1: {raised} items raised to MEDIUM; serious caught at M+ "
                 f"{with_caught} -> {without_caught} and M+ false-alarm sections "
                 f"{with_alarms} -> {without_alarms} without the raise")
    return lines


P3_REFERENCE_KEYS = ("resolved", "broken", "ambiguous", "external")


def p3_data(contract):
    """Any P3 definition or reference activity in one scored contract; an older run has none."""
    definitions = contract.get("definitions") or {}
    references = contract.get("references") or {}
    return bool(definitions.get("terms")
                or any(references.get(key, 0) for key in P3_REFERENCE_KEYS))


def p3_lines(results):
    """Engine v2 P3 measurements: definitions extracted and shown, references by status.

    Only emitted when at least one contract reports an extracted term or a reference, so a run
    with neither leaves the report unchanged.
    """
    contracts = [c for r in results for c in r.get("contracts", [])]
    if not any(p3_data(contract) for contract in contracts):
        return []
    n = len(contracts)
    definitions = [c.get("definitions") or {} for c in contracts]
    terms = sum(d.get("terms", 0) for d in definitions)
    judged = sum(d.get("judgedClauses", 0) for d in definitions)
    shown = sum(d.get("clausesShown", 0) for d in definitions)
    terms_shown = sum(d.get("termsShown", 0) for d in definitions)
    references = [c.get("references") or {} for c in contracts]
    counts = {key: sum(r.get(key, 0) for r in references) for key in P3_REFERENCE_KEYS}
    return ["", "## Structure (P3)", "",
            f"- Definitions: {terms} extracted ({terms / n:.1f}/contract); judged clauses shown "
            f"definitions {pct(shown, judged)}; definitions shown {terms_shown}",
            f"- References: resolved {counts['resolved']}, broken {counts['broken']}, "
            f"ambiguous {counts['ambiguous']}, external {counts['external']}"]


def engine_meta():
    """The engine settings the backend starts with, as an eval run reports them."""
    return {"engine": os.environ.get("RIVELL_REVIEW_ENGINE", "v2").lower(),
            "omissions": os.environ.get("RIVELL_REVIEW_OMISSIONS", "false").lower(),
            "definitions": os.environ.get("RIVELL_REVIEW_DEFINITIONS", "false").lower()}


def header_lines(meta):
    return [f"# Eval run {meta['runId']}", "",
            f"Corpus `{meta['corpus']}` · git `{meta['git']}` · {meta['started']}"
            + (f" · decisions `{meta['decisions']}`" if meta.get("decisions") else "")
            + (" · issue spotting on" if meta.get("issueSpotting") else "")
            + (f" · order probe `{meta['orderProbe']}` (measurement only)"
               if meta.get("orderProbe", "off") != "off" else "")
            + (f" · engine `{meta['engine']}`" if meta.get("engine", "v2") != "v2" else "")
            + (" · omissions on" if meta.get("omissions") == "true" else "")
            + (" · definitions on" if meta.get("definitions") == "true" else ""), ""]


def write_report(out, results, meta):
    lines = header_lines(meta)
    if meta.get("aborted"):
        lines += [f"**⚠️ RUN ABORTED: {meta['aborted']}. Numbers below cover finished orgs only.**", ""]
    tot = {k: 0 for k in ("sx", "sm", "cl", "cc", "ix", "id", "cs", "fa", "fas", "pos", "posf", "cand", "candc")}
    lines += ["## Knowledge", "", "| File | Candidates | With concept | Positions whole | Positions split | Unmatched candidates |",
              "|---|---|---|---|---|---|"]
    for r in results:
        for k in r["knowledge"]:
            tot["pos"] += k["expectedPositions"]; tot["posf"] += k["positionsFound"]
            tot["cand"] += k["candidates"]; tot["candc"] += k["candidatesWithConcept"]
            lines.append(f"| {k['file']} | {k['candidates']} | {pct(k['candidatesWithConcept'], k['candidates'])} "
                         f"| {pct(k['positionsFound'], k['expectedPositions'])} | {k.get('positionsSplit', 0)} | {k['unmatchedCandidates']} |")
    lines += ["", "## Contracts", "",
              "| File | Review | Sections matched | Clauses classified | Findings (absence) | Issues detected | False-alarm sections | of which MEDIUM+ |",
              "|---|---|---|---|---|---|---|---|"]
    for r in results:
        for c in r["contracts"]:
            tot["sx"] += c["sectionsExpected"]; tot["sm"] += c["sectionsMatched"]
            tot["cl"] += c["clauses"]; tot["cc"] += c["clausesClassified"]
            tot["ix"] += len(c["issues"]); tot["id"] += c["issuesDetected"]
            tot["cs"] += c["compliantSections"]; tot["fa"] += c["falseAlarmSections"]
            tot["fas"] += c.get("seriousFalseAlarmSections", 0)
            lines.append(f"| {c['file']} | {c['reviewStatus']} | {pct(c['sectionsMatched'], c['sectionsExpected'])} "
                         f"| {pct(c['clausesClassified'], c['clauses'])} | {c['findings']} ({c['absenceFindings']}) "
                         f"| {pct(c['issuesDetected'], len(c['issues']))} | {pct(c['falseAlarmSections'], c['compliantSections'])} "
                         f"| {c.get('seriousFalseAlarmSections', 0)} |")
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
              f"- Positions found whole (preferred + walk-away in one candidate): {pct(tot['posf'], tot['pos'])}",
              f"- Planted issues detected (section-level): {pct(tot['id'], tot['ix'])}",
              f"- HIGH/MEDIUM planted issues caught by a MEDIUM+ finding: {pct(*serious_caught(results))}",
              f"- False-alarm compliant sections: {pct(tot['fa'], tot['cs'])}"
              f" (MEDIUM/HIGH: {pct(tot['fas'], tot['cs'])})",
              volume_line(results),
              cost_speed_line(results, meta),
              *spotting_line(results),
              *needs_look_line(results),
              check_line(results), "",
              "| Issue category | Detected |", "|---|---|"]
    lines += [f"| {k} | {pct(*v)} |" for k, v in sorted(by_cat.items())]
    lines += p4_lines(results)
    lines += p3_lines(results)
    lines += coverage_report(results) + redline_report(results) + usage_report(results)
    errs = [f"- {r['org']}: {e}" for r in results for e in r["errors"]]
    errs += [f"- {c['file']}: checks not completed: {', '.join(c['checkFailures'])}"
             for r in results for c in r["contracts"] if c.get("checkFailures")]
    lines += ["", "## Errors", ""] + (errs or ["- none"])
    (out / "summary.md").write_text("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    ap.add_argument("--org", action="append", help="limit to these org ids")
    ap.add_argument("--contract", action="append", help="limit to these contract file stems")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--no-redline", action="store_true", help="skip the redline round trip")
    args = ap.parse_args()

    missing = [k for k in ("TYPESAFE_API_KEY", "OPENAI_API_KEY") if not os.environ.get(k)]
    if missing:
        sys.exit(f"missing environment variables: {', '.join(missing)}")
    decisions = os.environ.get("RIVELL_INTELLIGENCE_PROVIDER", "jev")
    problems = preflight(jev=decisions != "openai-decider", openai_model=os.environ.get("OPENAI_MODEL", "gpt-6-luna"))
    if problems:
        sys.exit("preflight failed, nothing run:\n  " + "\n  ".join(problems))
    all_orgs = load_orgs(args.corpus)
    orgs = select_orgs(all_orgs, args.org, args.contract)

    run_id = time.strftime("%Y%m%d-%H%M%S")
    out = args.out / run_id
    raw_dir = out / "raw"
    raw_dir.mkdir(parents=True)
    git = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=REPO,
                         capture_output=True, text=True).stdout.strip()
    meta = {"runId": run_id, "corpus": str(args.corpus), "git": git,
            "started": time.strftime("%Y-%m-%d %H:%M"), "decisions": decisions,
            "issueSpotting": os.environ.get("RIVELL_ISSUE_SPOTTING", "false").lower() == "true",
            "orderProbe": os.environ.get("RIVELL_ORDER_PROBE", "off").lower(),
            **engine_meta()}
    started = time.monotonic()

    if not args.no_build:
        build_jar()
    jar = find_jar()
    api = RivellApi(BASE_URL)
    results = []
    global WATCH
    for org in orgs:
        org_id = str(uuid.uuid4())
        log_path = out / f"backend-{org['id']}.log"
        proc, log = start_backend(jar, org_id, log_path)
        WATCH = LogWatch(log_path)
        try:
            api.wait_ready()
            if proc.poll() is not None:
                sys.exit(f"backend for org {org['id']} exited; see {log.name}")
            print(f"org {org['id']}: tenant {org_id}", flush=True)
            results.append(run_org(api, args.corpus, org, raw_dir, not args.no_redline))
            WATCH.check()
        except ProviderFatal as e:
            meta["aborted"] = f"org {org['id']}: {e}"
        finally:
            stop_backend(proc, log)
        if results and results[-1]["org"] == org["id"]:
            results[-1]["modelUsage"] = model_usage(log_path)
        if meta.get("aborted"):
            break
    meta["wallS"] = round(time.monotonic() - started)
    (out / "results.json").write_text(json.dumps({"meta": meta, "results": results}, indent=1))
    write_report(out, results, meta)
    print(f"report: {out / 'summary.md'}")
    if meta.get("aborted"):
        sys.exit(f"ABORTED: {meta['aborted']}")


if __name__ == "__main__":
    main()
