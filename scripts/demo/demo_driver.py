#!/usr/bin/env python3
"""Build the Bramber demo state through the public Rivell API.

Phase A publishes the base knowledge, uploads and enriches the base contracts,
starts their reviews, proves each review used exactly the expected knowledge,
and dumps the findings a person turns into the reviewed decisions file.
"""
import argparse
import hashlib
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "eval"))
import demo_config  # noqa: E402
from rivell_api import ApiError, RivellApi, items  # noqa: E402


class DemoError(RuntimeError):
    """The demo run cannot continue without producing a false demo state."""


def empty_state() -> dict:
    return {"knowledge": {}, "reviews": {}, "checkpoints": []}


def load_state(path) -> dict:
    path = pathlib.Path(path)
    if not path.is_file():
        return empty_state()
    try:
        state = json.loads(path.read_text())
    except ValueError as error:
        raise DemoError(f"state file is not valid JSON: {path}: {error}") from None
    state.setdefault("knowledge", {})
    state.setdefault("reviews", {})
    state.setdefault("checkpoints", [])
    return state


def save_state(path, state) -> None:
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(state, indent=2, ensure_ascii=False) + "\n")
    os.replace(temporary, path)


def api_client(base_url: str) -> RivellApi:
    return RivellApi(base_url)


def assert_empty_database(api) -> None:
    for path in ("/api/contracts?limit=1",
                 "/api/knowledge/artifacts?limit=1",
                 "/api/reviews?limit=1"):
        if items(api.get(path)):
            raise DemoError("database is not empty; reset it before phase a")


def tape_health(tape_url: str) -> dict:
    url = tape_url.rstrip("/") + "/__tape__/health"
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError) as error:
        raise DemoError(f"tape health is unavailable at {url}: {error}") from None


def require_no_misses(health: dict) -> None:
    misses = health.get("misses")
    if misses is None:
        raise DemoError("tape health did not report misses; is the tape running?")
    if misses:
        listed = "; ".join(f"{miss.get('path', '?')} (key {str(miss.get('key', ''))[:16]})"
                           for miss in misses[:3])
        raise DemoError(f"replay miss: {len(misses)} request(s) had no recording, first: "
                        f"{listed}. The demo would silently lose recorded output; re-record "
                        "the tape.")
    unrouted = health.get("unrouted") or []
    if unrouted:
        listed = "; ".join(f"{request.get('method', '?')} {request.get('path', '?')}"
                           for request in unrouted[:3])
        raise DemoError(f"replay miss: {len(unrouted)} request(s) hit no tape route, first: "
                        f"{listed}. A provider path is wrong or the tape is missing an endpoint.")


def find_review(api, contract_file: str) -> dict | None:
    filename = pathlib.Path(contract_file).name
    matches = [review for review in items(api.get("/api/reviews?limit=100"))
               if review.get("contractFilename") == filename]
    if len(matches) > 1:
        raise DemoError(f"{len(matches)} reviews match {filename}; the database has duplicates")
    return matches[0] if matches else None


def find_artifact(api, filename: str) -> dict | None:
    name = pathlib.Path(filename).name
    matches = [artifact for artifact in items(api.get("/api/knowledge/artifacts"))
               if artifact.get("filename") == name]
    if len(matches) > 1:
        raise DemoError(f"{len(matches)} artifacts match {name}; the database has duplicates")
    return matches[0] if matches else None


def max_publication_number(api) -> int:
    publications = items(api.get("/api/knowledge/publications"))
    return max((publication.get("publicationNumber", 0) for publication in publications),
               default=0)


def publish_knowledge(api, corpus, entry: dict, expected_number: int) -> dict:
    path = pathlib.Path(corpus) / entry["file"]
    query = urllib.parse.urlencode(
        [("artifactType", entry["artifactType"])]
        + [("declaredContractTypes", code) for code in entry["declaredContractTypes"]])
    artifact = api.upload(f"/api/knowledge/artifacts?{query}", path)
    artifact_id = artifact["id"]
    if artifact.get("ingestStatus") != "EXTRACTED":
        raise DemoError(f"knowledge {entry['file']}: ingest status is "
                        f"{artifact.get('ingestStatus')}, expected EXTRACTED")
    warnings = list(artifact.get("parseWarnings") or [])
    assessment = artifact.get("artifactAssessment") or {}
    warnings += list(assessment.get("warnings") or [])
    if "SEMANTIC_COMPILATION_UNAVAILABLE" in warnings:
        raise DemoError(f"knowledge {entry['file']}: semantic compilation was unavailable; "
                        "the record run would publish incomplete knowledge")
    declared = set(artifact.get("declaredContractTypes") or [])
    expected = set(entry["declaredContractTypes"])
    if declared != expected:
        raise DemoError(f"knowledge {entry['file']}: declared types are {sorted(declared)}, "
                        f"expected {sorted(expected)}")
    candidates = items(api.get(f"/api/knowledge/artifacts/{artifact_id}/candidates"))
    if not candidates:
        raise DemoError(f"knowledge {entry['file']}: the source compiled no candidates")
    for candidate in candidates:
        if candidate.get("reviewStatus") == "REJECTED":
            raise DemoError(f"knowledge {entry['file']}: candidate {candidate['id']} was rejected")
        if candidate.get("reviewStatus") == "PUBLISHED":
            raise DemoError(f"knowledge {entry['file']}: candidate {candidate['id']} is already "
                            "published; the database is not empty (phase A's guard was bypassed)")
    for candidate in candidates:
        if candidate.get("reviewStatus") == "PENDING":
            api.post(f"/api/knowledge/candidates/{candidate['id']}/approve",
                     {"note": "demo seed"})
    final = items(api.get(f"/api/knowledge/artifacts/{artifact_id}/candidates"))
    for candidate in final:
        if candidate.get("reviewStatus") == "PUBLISHED":
            raise DemoError(f"knowledge {entry['file']}: candidate {candidate['id']} is already "
                            "published; the database is not empty (phase A's guard was bypassed)")
    unfinished = [candidate for candidate in final
                  if candidate.get("reviewStatus") not in ("APPROVED", "EDITED")]
    if unfinished:
        raise DemoError(f"knowledge {entry['file']}: candidate {unfinished[0]['id']} is "
                        f"{unfinished[0].get('reviewStatus')}, expected APPROVED or EDITED")
    publication = api.post("/api/knowledge/publications",
                           {"candidateIds": [candidate["id"] for candidate in final],
                            "note": "demo seed"})
    number = publication.get("publicationNumber")
    if number != expected_number:
        raise DemoError(f"knowledge {entry['file']}: publication number is {number}, "
                        f"expected {expected_number}")
    print(f"knowledge {entry['file']} -> artifact {artifact_id} publication {number}")
    return {"artifactId": artifact_id, "publicationNumber": number,
            "candidates": len(final)}


def _contract_base(contract: dict) -> str:
    return (f"/api/contracts/{contract['contractId']}"
            f"/versions/{contract['versionNumber']}")


def upload_contract(api, corpus, entry: dict) -> dict:
    path = pathlib.Path(corpus) / entry["file"]
    upload = api.upload("/api/contracts", path)
    if upload.get("parseStatus") != "OK":
        raise DemoError(f"contract {entry['file']}: parse status is {upload.get('parseStatus')}")
    if not upload.get("nodeCount", 0) > 0:
        raise DemoError(f"contract {entry['file']}: parsed no structure nodes")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if upload.get("sha256") != digest:
        raise DemoError(f"contract {entry['file']}: stored sha256 does not match the corpus file")
    return {"contractId": upload["contractId"], "versionNumber": upload["versionNumber"],
            "parseStatus": upload["parseStatus"], "filename": upload["filename"],
            "sha256": upload["sha256"]}


def enrich_contract(api, contract: dict, entry: dict) -> dict:
    enrichment = api.post(_contract_base(contract) + "/enrichment", entry["review"])
    if enrichment.get("enrichmentStatus") != "READY":
        raise DemoError(f"contract {entry['file']}: enrichment is "
                        f"{enrichment.get('enrichmentStatus')} "
                        f"({enrichment.get('failureReason')})")
    observations = enrichment.get("observations") or {}
    if observations.get("providerDegraded") is True:
        raise DemoError(f"contract {entry['file']}: enrichment degraded to a provider "
                        "fallback; the recorded run would not be the real one")
    if observations.get("budgetExhausted") is True:
        raise DemoError(f"contract {entry['file']}: enrichment exhausted its model budget; "
                        "the recorded run would be incomplete")
    clauses = enrichment.get("clauses") or []
    if not clauses:
        raise DemoError(f"contract {entry['file']}: enrichment found no clauses")
    return enrichment


def wait_review(api, review_id: str, timeout_s: int) -> dict:
    deadline = time.monotonic() + timeout_s
    while True:
        review = api.get(f"/api/reviews/{review_id}")
        status = review.get("status")
        if status == "COMPLETED":
            return review
        if status == "FAILED":
            failure = review.get("failure") or {}
            raise DemoError(f"review {review_id} FAILED: "
                            f"{failure.get('message') or failure.get('code') or 'no reason given'}")
        if time.monotonic() >= deadline:
            raise DemoError(f"review {review_id} did not finish within {timeout_s}s "
                            f"(last status {status})")
        time.sleep(4)


def create_review(api, contract: dict, entry: dict, timeout_s: int = 1800) -> dict:
    pack = entry["review"].get("contractPackKey")
    body = {"contractPackKey": pack} if pack else {}
    created = api.post(_contract_base(contract) + "/reviews", body)
    return wait_review(api, created["id"], timeout_s)


def assert_review_analysis(api, review: dict, expected_artifact_ids: set[str],
                           expected_pin: int) -> dict:
    current = api.get(f"/api/reviews/{review['id']}")
    if current.get("status") != "COMPLETED":
        raise DemoError(f"review {review['id']}: status is {current.get('status')}")
    if not current.get("findings", 0) > 0:
        raise DemoError(f"review {review['id']}: completed with no findings")
    snapshot = current.get("snapshot") or {}
    pin = snapshot.get("knowledgePinNumber")
    if pin != expected_pin:
        raise DemoError(f"review {review['id']}: knowledge pin is {pin}, expected {expected_pin}")
    used = snapshot.get("playbooksUsed")
    if used is None:
        raise DemoError(f"review {review['id']}: froze no playbook list; expected "
                        f"{sorted(expected_artifact_ids)}")
    used_ids = {playbook.get("artifactId") for playbook in used}
    if used_ids != set(expected_artifact_ids):
        raise DemoError(f"review {review['id']}: applied knowledge is {sorted(used_ids)}, "
                        f"expected {sorted(expected_artifact_ids)}")
    diagnostics = api.get(f"/api/reviews/{review['id']}/diagnostics")
    if diagnostics.get("backgroundWorkerEnabled") is not True:
        raise DemoError(f"review {review['id']}: the background worker was not enabled")
    analysis = diagnostics.get("analysis") or {}
    if analysis.get("status") != "COMPLETED":
        raise DemoError(f"review {review['id']}: analysis status is {analysis.get('status')}")
    if analysis.get("runNumber") != 1:
        raise DemoError(f"review {review['id']}: analysis run number is "
                        f"{analysis.get('runNumber')}, expected 1")
    if analysis.get("environmentMatchesSnapshot") is not True:
        raise DemoError(f"review {review['id']}: environment no longer matches the snapshot")
    stalled = diagnostics.get("stalled")
    if stalled != []:
        raise DemoError(f"review {review['id']}: stalled is {stalled!r}, expected []")
    checks = analysis.get("checks") or []
    if not checks:
        raise DemoError(f"review {review['id']}: analysis recorded no checks")
    bad = [check for check in checks if check.get("status") != "COMPLETED"]
    if bad:
        raise DemoError(f"review {review['id']}: {len(bad)} check(s) did not complete, first "
                        f"{bad[0].get('key')} is {bad[0].get('status')}")
    names = ", ".join(sorted(str(playbook.get("name")) for playbook in used))
    print(f"review {review.get('document', {}).get('contractFilename')}: COMPLETED "
          f"findings={current['findings']} checks={len(checks)} knowledge={names}")
    return diagnostics


def section_texts(api, contract_id, version_number) -> dict[str, str]:
    body = api.get(f"/api/contracts/{contract_id}/versions/{version_number}/structure")
    nodes = body["nodes"] if isinstance(body, dict) else body
    texts = {}
    for node in nodes:
        text = node.get("text")
        if isinstance(text, str) and text.strip():
            texts[str(node["id"])] = text.strip()
    return texts


def dump_findings(api, entries: list[tuple[str, dict]], out_dir, stem: str) -> None:
    out_dir = pathlib.Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    contracts = {}
    for contract_file, review in entries:
        sections = section_texts(api, review["contractId"],
                                 review["contractVersionNumber"])
        payload = api.get(f"/api/reviews/{review['id']}/findings?limit=500")
        if isinstance(payload, dict) and payload.get("truncated"):
            raise DemoError(f"review {review['id']}: more findings than the 500 read; "
                            "the decisions template would be incomplete")
        decisions = []
        for finding in items(payload):
            subject = finding.get("subjectKind")
            concept = (finding.get("absenceConcept") if subject == "ABSENCE"
                       else finding.get("concept"))
            heading = finding.get("subjectHeadingNodeId")
            section = sections.get(str(heading)) if heading else None
            rows.append({
                "contract": contract_file,
                "subject": subject,
                "section": section,
                "concept": concept,
                "issueFamily": finding.get("issueFamily"),
                "severity": finding.get("severity"),
                "reviewClassification": finding.get("reviewClassification"),
                "proposedAction": finding.get("proposedAction"),
                "proposedLanguageSource": finding.get("proposedLanguageSource"),
                "decidedBy": finding.get("decidedBy"),
                "positionLabel": finding.get("positionLabel"),
            })
            if finding.get("reviewClassification") != "INFORMATIONAL":
                decisions.append({
                    "subject": subject,
                    "section": section,
                    "concept": concept,
                    "issueFamily": finding.get("issueFamily"),
                    "action": None,
                    "approvedAction": None,
                    "approvedLanguage": None,
                    "comment": None,
                })
        contracts[contract_file] = {"decisions": decisions}
    findings_path = out_dir / f"findings-{stem}.json"
    template_path = out_dir / f"decisions-{stem}.template.json"
    findings_path.write_text(json.dumps(rows, indent=2, ensure_ascii=False) + "\n")
    template_path.write_text(json.dumps({"version": 1, "contracts": contracts}, indent=2,
                                        ensure_ascii=False) + "\n")
    print(f"findings written to {findings_path}")
    print(f"decisions template written to {template_path}")
    print("next: fill in the template, then run this driver with --check-decisions")


def run_phase_a(config, corpus, api, tape_url, out_dir, state, review_timeout=1800) -> dict:
    require_no_misses(tape_health(tape_url))
    assert_empty_database(api)
    for entry in demo_config.knowledge_for_phase(config, "base"):
        expected_number = max(
            (known["publicationNumber"] for known in state["knowledge"].values()), default=0) + 1
        published = publish_knowledge(api, corpus, entry, expected_number)
        state["knowledge"][entry["file"]] = {
            "artifactId": published["artifactId"],
            "publicationNumber": published["publicationNumber"],
        }
    expected_pin = max((known["publicationNumber"] for known in state["knowledge"].values()),
                       default=0)
    entries = []
    for entry in demo_config.contracts_for_phase(config, "base"):
        contract = upload_contract(api, corpus, entry)
        enrich_contract(api, contract, entry)
        review = create_review(api, contract, entry, review_timeout)
        expected_ids = {state["knowledge"][name]["artifactId"]
                        for name in entry["appliesKnowledge"]}
        assert_review_analysis(api, review, expected_ids, expected_pin)
        state["reviews"][entry["file"]] = {
            "reviewId": review["id"],
            "contractId": review["contractId"],
            "versionNumber": review["contractVersionNumber"],
        }
        entries.append((entry["file"], review))
    dump_findings(api, entries, out_dir, "base")
    require_no_misses(tape_health(tape_url))
    if "phase-a" not in state["checkpoints"]:
        # Internal handoff for phase B; part 2's capture states are the D5 names.
        state["checkpoints"].append("phase-a")
    save_state(pathlib.Path(out_dir) / "state.json", state)
    print("CHECKPOINT phase-a")
    return state


def _path(value) -> pathlib.Path:
    path = pathlib.Path(value)
    return path if path.is_absolute() else ROOT / path


def _check_config(config: dict) -> None:
    print(f"config version {config['version']}")
    for entry in config["knowledge"]:
        print(f"knowledge {entry['file']} phase={entry['phase']} "
              f"type={entry['artifactType']} "
              f"declared={','.join(entry['declaredContractTypes'])}")
    for entry in config["contracts"]:
        review = entry["review"]
        print(f"contract {entry['file']} phase={entry['phase']} stage={entry['stage']} "
              f"profile={review['contractType']}/{review['ourSideRole']}/"
              f"{review['paperSource']}/{review['jurisdiction']} "
              f"knowledge={', '.join(entry['appliesKnowledge'])}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Build the Bramber demo through the API")
    parser.add_argument("--config", default="demo/config.json")
    parser.add_argument("--corpus", default="demo/corpus")
    parser.add_argument("--decisions", default="demo/decisions/decisions.json")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--tape-url", default="http://127.0.0.1:18090")
    parser.add_argument("--phases", default="a")
    parser.add_argument("--dump-dir", default="backend/target/demo")
    parser.add_argument("--review-timeout", type=int, default=1800)
    parser.add_argument("--check-config", action="store_true")
    args = parser.parse_args(argv)

    config_path = _path(args.config)
    try:
        config = demo_config.load_config(config_path)
    except demo_config.ConfigError as error:
        print(error, file=sys.stderr)
        return 2
    if args.check_config:
        _check_config(config)
        return 0

    requested = set()
    for token in args.phases.split(","):
        token = token.strip()
        if not token:
            continue
        if token == "all":
            requested |= {"a", "b", "c"}
        elif token in {"a", "b", "c"}:
            requested.add(token)
        else:
            parser.error(f"unknown phase {token!r}; expected a, b, c or all")
    for phase in ("b", "c"):
        if phase in requested:
            print(f"phase {phase} is not implemented yet", file=sys.stderr)
            return 2

    corpus = _path(args.corpus)
    dump_dir = _path(args.dump_dir)
    dump_dir.mkdir(parents=True, exist_ok=True)
    state_path = dump_dir / "state.json"
    if "a" in requested:
        state = empty_state()
        save_state(state_path, state)
    else:
        state = load_state(state_path)
    api = api_client(args.base_url)
    try:
        if "a" in requested:
            run_phase_a(config, corpus, api, args.tape_url, dump_dir, state,
                        args.review_timeout)
    except (DemoError, ApiError) as error:
        print(f"demo failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
