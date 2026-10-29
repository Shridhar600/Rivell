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
import capture  # noqa: E402
import capture_sink  # noqa: E402
import demo_config  # noqa: E402
import redline_check  # noqa: E402
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


def _bounded(payload, what: str) -> list[dict]:
    if isinstance(payload, dict) and payload.get("truncated"):
        raise DemoError(f"{what} is truncated; a bounded read would silently miss rows")
    return items(payload)


def find_review(api, contract_file: str) -> dict | None:
    filename = pathlib.Path(contract_file).name
    payload = api.get("/api/reviews?limit=100")
    if isinstance(payload, dict) and payload.get("truncated"):
        raise DemoError("more reviews than the 100 read; a bounded read could pick the wrong one")
    matches = [review for review in items(payload)
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


def _validate_knowledge_artifact(entry: dict, artifact: dict) -> None:
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


def _knowledge_upload(api, corpus, entry: dict) -> tuple[str, dict, list[dict]]:
    path = pathlib.Path(corpus) / entry["file"]
    query = urllib.parse.urlencode({
        "artifactType": entry["artifactType"],
        "declaredContractTypes": ",".join(entry["declaredContractTypes"]),
    })
    artifact = api.upload(f"/api/knowledge/artifacts?{query}", path)
    artifact_id = artifact["id"]
    _validate_knowledge_artifact(entry, artifact)
    candidates = items(api.get(f"/api/knowledge/artifacts/{artifact_id}/candidates"))
    if not candidates:
        raise DemoError(f"knowledge {entry['file']}: the source compiled no candidates")
    return artifact_id, artifact, candidates


def publish_knowledge(api, corpus, entry: dict, expected_number: int) -> dict:
    artifact_id, artifact, candidates = _knowledge_upload(api, corpus, entry)
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
    return {"artifactId": artifact_id, "publicationId": publication.get("id"),
            "publicationNumber": number, "candidates": len(final)}


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


DECISION_ACTIONS = {"ACCEPT", "EDIT", "REJECT", "ESCALATE"}
DECISION_APPROVED_ACTIONS = {"REPLACE", "INSERT", "DELETE"}
REJECT_REASONS = {"KEEP_AS_DRAFTED", "FINDING_WRONG"}
FORBIDDEN_DECISION_FIELDS = ("id", "reviewId", "findingId", "decisionId")
DECISION_FIELDS = {
    "subject", "section", "concept", "issueFamily", "action", "approvedAction",
    "approvedLanguage", "comment", "rejectReason",
}


def decision_key(contract_file: str, finding: dict, sections: dict[str, str]) -> tuple:
    subject = finding.get("subjectKind")
    if subject == "ABSENCE":
        section = ""
        concept = finding.get("absenceConcept") or ""
    else:
        heading = finding.get("subjectHeadingNodeId")
        section = sections.get(str(heading)) if heading else None
        if not section:
            raise DemoError(f"{contract_file}: finding {finding.get('id')} has no section "
                            "heading text; cannot key it")
        concept = finding.get("concept") or ""
    return (subject, section, concept, finding.get("issueFamily"))


def _decision_key(decision: dict) -> tuple:
    return (decision.get("subject"), decision.get("section") or "",
            decision.get("concept") or "", decision.get("issueFamily"))


def resolve_finding(contract_file: str, findings: list[dict], sections: dict[str, str],
                    key: tuple) -> dict:
    matches = []
    available = set()
    for finding in findings:
        candidate = decision_key(contract_file, finding, sections)
        available.add(candidate)
        if candidate == tuple(key):
            matches.append(finding)
    if len(matches) == 1:
        return matches[0]
    listed = sorted(available)
    if len(matches) > 1:
        raise DemoError(f"{contract_file}: decision key {tuple(key)} matches {len(matches)} "
                        f"findings; an ambiguous key would decide the wrong one. Available keys: "
                        f"{listed}")
    raise DemoError(f"{contract_file}: decision key {tuple(key)} matches no finding; available "
                    f"keys: {listed}")


def load_decisions(path) -> dict:
    path = pathlib.Path(path)
    if not path.is_file():
        raise demo_config.ConfigError(f"decisions file not found: {path}")
    try:
        decisions = json.loads(path.read_text())
    except ValueError as error:
        raise demo_config.ConfigError(
            f"decisions file is not valid JSON: {path}: {error}") from None
    if not isinstance(decisions, dict):
        raise demo_config.ConfigError(f"decisions file {path}: top level must be an object")
    return decisions


def _decision_fail(where: str, message: str):
    raise demo_config.ConfigError(f"{where}: {message}")


def _is_text(value) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _validate_decision(where: str, decision, seen: set[tuple]) -> None:
    if not isinstance(decision, dict):
        _decision_fail(where, "must be an object")
    for field in FORBIDDEN_DECISION_FIELDS:
        if field in decision:
            _decision_fail(where, f"field {field!r} is refused; decisions are keyed on the "
                                  "finding's content, never on generated ids")
    if "approvedBy" in decision:
        _decision_fail(where, "approvedBy belongs only to RESOLVE, which a first pass does not use")
    for field in decision:
        if field not in DECISION_FIELDS:
            _decision_fail(where, f"unknown field {field!r}")
    subject = decision.get("subject")
    if subject not in ("CLAUSE", "ABSENCE"):
        _decision_fail(where, f"unknown subject {subject!r}; expected CLAUSE or ABSENCE")
    section = decision.get("section")
    if subject == "CLAUSE":
        if not _is_text(section):
            _decision_fail(where, "CLAUSE requires a non-blank section heading")
    elif section not in (None, ""):
        _decision_fail(where, "ABSENCE must have no section heading")
    concept = decision.get("concept")
    if concept is not None and not isinstance(concept, str):
        _decision_fail(where, "concept must be a string or null")
    if subject == "ABSENCE" and not _is_text(concept):
        _decision_fail(where, "ABSENCE requires the absence concept as a non-blank concept")
    if not _is_text(decision.get("issueFamily")):
        _decision_fail(where, "issueFamily must be a non-blank string")
    action = decision.get("action")
    if action == "RESOLVE":
        _decision_fail(where, "action RESOLVE is refused; a first pass has no escalation to resolve")
    if action not in DECISION_ACTIONS:
        _decision_fail(where, f"unknown action {action!r}; expected one of "
                              f"{sorted(DECISION_ACTIONS)}")
    approved_action = decision.get("approvedAction")
    if approved_action is not None and approved_action not in DECISION_APPROVED_ACTIONS:
        _decision_fail(where, f"unknown approvedAction {approved_action!r}; expected one of "
                              f"{sorted(DECISION_APPROVED_ACTIONS)} or null")
    language = decision.get("approvedLanguage")
    if language is not None and not _is_text(language):
        _decision_fail(where, "approvedLanguage must be a non-blank string when present")
    reason = decision.get("rejectReason")
    if reason is not None and reason not in REJECT_REASONS:
        _decision_fail(where, f"unknown rejectReason {reason!r}; expected one of "
                              f"{sorted(REJECT_REASONS)} or null")
    if action == "EDIT":
        if subject == "CLAUSE" and approved_action == "INSERT":
            _decision_fail(where, "a CLAUSE finding is edited as a replacement or deletion, "
                                  "not an insertion")
        if subject == "ABSENCE" and approved_action in ("REPLACE", "DELETE"):
            _decision_fail(where, "an ABSENCE finding can only be edited as an insertion")
        if approved_action == "DELETE":
            if language is not None:
                _decision_fail(where, "approvedLanguage is forbidden with approvedAction DELETE")
        else:
            if not language:
                _decision_fail(where, "EDIT requires a non-blank approvedLanguage unless "
                                      "approvedAction is DELETE")
        if reason is not None:
            _decision_fail(where, "rejectReason is only allowed on REJECT")
    else:
        if approved_action is not None:
            _decision_fail(where, f"{action} carries no approvedAction")
        if language is not None:
            _decision_fail(where, "approvedLanguage is only allowed on EDIT")
        if reason is not None and action != "REJECT":
            _decision_fail(where, "rejectReason is only allowed on REJECT")
        if action == "REJECT" and not _is_text(decision.get("comment")) and reason is None:
            _decision_fail(where, "REJECT requires a comment or a rejectReason")
        if action == "ESCALATE" and not _is_text(decision.get("comment")):
            _decision_fail(where, "ESCALATE requires a comment")
    key = _decision_key(decision)
    if key in seen:
        _decision_fail(where, f"duplicate decision key {key}")
    seen.add(key)


def validate_decisions(config, decisions) -> None:
    if isinstance(config, (str, pathlib.Path)):
        config = demo_config.load_config(pathlib.Path(config))
    if not isinstance(decisions, dict):
        _decision_fail("decisions", "must be an object")
    if decisions.get("version") != 1:
        _decision_fail("decisions version", f"unknown version {decisions.get('version')!r}; "
                                            "expected 1")
    contracts = decisions.get("contracts")
    if not isinstance(contracts, dict):
        _decision_fail("decisions contracts", "must be an object keyed by contract file")
    configured = {entry["file"] for entry in config["contracts"]}
    for contract_file, payload in contracts.items():
        where = f"decisions contract {contract_file!r}"
        if contract_file not in configured:
            _decision_fail(where, "is not a configured contract")
        if not isinstance(payload, dict):
            _decision_fail(where, "must be an object")
        entries = payload.get("decisions")
        if not isinstance(entries, list):
            _decision_fail(where, "decisions must be a list")
        seen = set()
        for index, decision in enumerate(entries):
            _validate_decision(f"{where} entry {index}", decision, seen)


def decision_body(decision: dict) -> dict:
    body = {"expectedVersion": 0, "action": decision["action"]}
    for field in ("approvedAction", "approvedLanguage", "comment", "rejectReason"):
        if decision.get(field) is not None:
            body[field] = decision[field]
    return body


def apply_decisions(api, review_id, findings, sections, decisions, contract_file=None) -> list[dict]:
    label = contract_file or f"review {review_id}"
    resolved = [(decision, resolve_finding(label, findings, sections, _decision_key(decision)))
                for decision in decisions]
    for decision, finding in resolved:
        returned = api.put(f"/api/reviews/{review_id}/findings/{finding['id']}/decision",
                           decision_body(decision))
        if returned.get("action") != decision["action"]:
            raise DemoError(f"review {review_id}: decision on finding {finding['id']} was "
                            f"stored as {returned.get('action')}, expected {decision['action']}")
        expected_action = decision.get("approvedAction")
        if expected_action is not None and returned.get("approvedAction") != expected_action:
            raise DemoError(f"review {review_id}: decision on finding {finding['id']} stored "
                            f"approvedAction {returned.get('approvedAction')!r}, expected "
                            f"{expected_action!r}")
    payload = api.get(f"/api/reviews/{review_id}/decisions?limit=100")
    if isinstance(payload, dict) and payload.get("truncated"):
        raise DemoError(f"review {review_id}: more decisions than the 100 read")
    return items(payload)


def assert_decisions_cover_stage(stage: str, findings: list[dict], decisions: list[dict],
                                 sections: dict[str, str]) -> None:
    if stage == "analyzed":
        return
    actionable = [finding for finding in findings
                  if finding.get("reviewClassification") != "INFORMATIONAL"]
    finding_keys = {}
    for finding in actionable:
        key = decision_key("review", finding, sections)
        if key in finding_keys:
            raise DemoError(f"two actionable findings share decision key {key}; a decision "
                            "would match both")
        finding_keys[key] = finding
    decision_keys = {_decision_key(decision) for decision in decisions}
    for decision in decisions:
        if decision.get("action") != "ACCEPT":
            continue
        finding = finding_keys.get(_decision_key(decision))
        if finding is None:
            continue
        if finding.get("proposedAction") not in (
                "USE_APPROVED_FALLBACK", "CONSIDER_GENERATED_LANGUAGE"):
            raise DemoError(f"the decision for {finding.get('id')} cannot ACCEPT a finding whose "
                            f"proposedAction is {finding.get('proposedAction')!r}; use EDIT or "
                            "REJECT")
        if finding.get("proposedLanguageSource") in (None, "NONE"):
            raise DemoError(f"the decision for {finding.get('id')} cannot ACCEPT a finding with "
                            "no proposed language; use EDIT or REJECT")
    extra = decision_keys - set(finding_keys)
    if extra:
        raise DemoError(f"decision key {sorted(extra)[0]} matches no actionable finding in this "
                        f"contract; available keys: {sorted(finding_keys)}")
    open_findings = [finding for key, finding in finding_keys.items()
                     if key not in decision_keys]
    if stage in ("decided", "finalized"):
        if open_findings:
            first = open_findings[0]
            label = (first.get("subjectHeadingNodeId") and sections.get(
                str(first.get("subjectHeadingNodeId")))) or first.get("absenceConcept") \
                or first.get("concept") or first.get("id")
            raise DemoError(f"undecided actionable finding: {len(open_findings)} remain; first "
                            f"{label}. Every actionable finding needs a decision at stage "
                            f"{stage}")
        return
    if stage == "mid-review":
        if not open_findings:
            raise DemoError("mid-review needs an open finding; every actionable finding already "
                            "has a decision")
        look = [finding for finding in open_findings
                if finding.get("decidedBy") == "NEEDS_YOUR_LOOK"]
        if not look:
            raise DemoError("mid-review needs an open finding with decidedBy NEEDS_YOUR_LOOK; "
                            "the stage would show no 'needs your look' item")
        return
    raise DemoError(f"unknown stage {stage!r}")


def assert_stage(api, review_id, stage) -> dict:
    view = api.get(f"/api/reviews/{review_id}/human-review")
    diagnostics = api.get(f"/api/reviews/{review_id}/diagnostics")
    if stage == "analyzed":
        decisions = _bounded(api.get(f"/api/reviews/{review_id}/decisions?limit=100"),
                             f"review {review_id} decisions")
        if decisions:
            raise DemoError(f"review {review_id}: the decisions list is not empty at stage analyzed")
        if view.get("resolvedFindings") != 0:
            raise DemoError(f"review {review_id}: {view.get('resolvedFindings')} finding(s) are "
                            "resolved at stage analyzed")
        if not (diagnostics.get("findings") or {}).get("total", 0) > 0:
            raise DemoError(f"review {review_id}: analysis produced no findings to review")
    elif stage == "mid-review":
        if view.get("resolvedFindings", 0) < 1:
            raise DemoError(f"review {review_id}: mid-review needs at least one resolved finding")
        if view.get("remainingFindings", 0) < 1:
            raise DemoError(f"review {review_id}: mid-review needs at least one remaining finding")
        if view.get("readyToFinalize") is not False:
            raise DemoError(f"review {review_id}: mid-review must not be ready to finalize")
        findings = _bounded(api.get(f"/api/reviews/{review_id}/findings?limit=500"),
                            f"review {review_id} findings")
        decisions = _bounded(api.get(f"/api/reviews/{review_id}/decisions?limit=100"),
                             f"review {review_id} decisions")
        decided = {decision.get("findingId") for decision in decisions}
        look = [finding for finding in findings
                if finding.get("decidedBy") == "NEEDS_YOUR_LOOK"
                and finding.get("id") not in decided]
        if not look:
            raise DemoError(f"review {review_id}: mid-review needs an open NEEDS_YOUR_LOOK finding")
    elif stage == "decided":
        if view.get("remainingFindings") != 0:
            raise DemoError(f"review {review_id}: {view.get('remainingFindings')} finding(s) "
                            "remain undecided")
        if view.get("readyToFinalize") is not True:
            raise DemoError(f"review {review_id}: stage decided must be ready to finalize; "
                            f"blockers={view.get('blockers')!r}")
        if view.get("changeSetId") is not None:
            raise DemoError(f"review {review_id}: stage decided must have no change set")
        if view.get("blockers") != []:
            raise DemoError(f"review {review_id}: stage decided has blockers "
                            f"{view.get('blockers')!r}")
    elif stage == "finalized":
        if view.get("changeSetId") is None:
            raise DemoError(f"review {review_id}: stage finalized has no change set")
        if view.get("status") != "COMPLETED":
            raise DemoError(f"review {review_id}: stage finalized has status "
                            f"{view.get('status')!r}, expected COMPLETED")
        if view.get("blockers") != []:
            raise DemoError(f"review {review_id}: stage finalized has blockers "
                            f"{view.get('blockers')!r}")
    else:
        raise DemoError(f"unknown stage {stage!r}")
    return view


def finalize_and_verify(api, review, corpus_file, after_finalize=None) -> dict:
    corpus_file = pathlib.Path(corpus_file)
    review_id = review["id"]
    change_set = api.post(f"/api/reviews/{review_id}/finalize")
    approved = change_set.get("changes") or []
    if not approved:
        raise DemoError(f"review {review_id}: the decisions produced no change; the stage needs "
                        "at least one ACCEPT or EDIT with executable language")
    if after_finalize is not None:
        after_finalize()
    contract_id = review["contractId"]
    version = review["contractVersionNumber"]
    structure = api.get(f"/api/contracts/{contract_id}/versions/{version}/structure")
    nodes = structure["nodes"] if isinstance(structure, dict) else structure
    try:
        execution = api.post(f"/api/reviews/{review_id}/redline")
    except ApiError as error:
        if error.status != 409:
            raise
        execution = api.get(f"/api/reviews/{review_id}/redline")
    if execution.get("status") != "COMPLETED":
        raise DemoError(f"review {review_id}: redline status is {execution.get('status')!r}")
    if execution.get("downloadAvailable") is not True:
        raise DemoError(f"review {review_id}: the redline has no verified download")
    if execution.get("outcome") == "NO_CHANGES":
        raise DemoError(f"review {review_id}: the redline reports NO_CHANGES")
    executed = {change.get("approvedChangeId"): change
                for change in (execution.get("changes") or [])}
    merged = []
    for change in approved:
        report = executed.get(change["id"])
        if report is None:
            raise DemoError(f"review {review_id}: approved change {change.get('ordinal')} has "
                            "no redline report")
        merged.append({**change, "status": report.get("status"),
                       "verification": report.get("verification")})
    for change in executed.values():
        if change.get("status") != "APPLIED":
            raise DemoError(f"review {review_id}: change {change.get('ordinal')} is "
                            f"{change.get('status')!r}, expected APPLIED")
        if change.get("verification") != "VERIFIED":
            raise DemoError(f"review {review_id}: change {change.get('ordinal')} verification is "
                            f"{change.get('verification')!r}, expected VERIFIED")
    redline_bytes = api.get_bytes(f"/api/reviews/{review_id}/redline/download")
    digest = hashlib.sha256(redline_bytes).hexdigest()
    if digest != execution.get("artifactSha256"):
        raise DemoError(f"review {review_id}: downloaded redline sha256 {digest} does not match "
                        f"{execution.get('artifactSha256')}")
    if not redline_bytes.startswith(b"PK"):
        raise DemoError(f"review {review_id}: downloaded redline is not a DOCX (no PK header)")
    checks = redline_check.check(corpus_file.read_bytes(), redline_bytes, nodes, merged)
    failed = [name for name in ("opens", "preserved", "local", "applied") if not checks[name]]
    if failed:
        raise DemoError(f"review {review_id}: independent redline check failed "
                        f"{failed}: {checks['problems'][:5]}")
    original = api.get_bytes(f"/api/contracts/{contract_id}/versions/{version}/original")
    original_digest = hashlib.sha256(original).hexdigest()
    if original_digest != hashlib.sha256(corpus_file.read_bytes()).hexdigest():
        raise DemoError(f"review {review_id}: stored original does not match the corpus file")
    print(f"redline {corpus_file.name}: {len(merged)} changes, verified, sha256 {digest[:12]}")
    return {"execution": execution, "changeSet": change_set, "changes": merged}


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
            if subject == "ABSENCE":
                section = None
            else:
                section = sections.get(str(heading)) if heading else None
                if not section and finding.get("reviewClassification") != "INFORMATIONAL":
                    raise DemoError(f"review {review['id']}: finding {finding.get('id')} has no "
                                    "section heading text; the decisions template could not key it")
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
            "publicationId": published["publicationId"],
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


def _checkpoint(state, out_dir, name: str, sink=None) -> None:
    if name not in state["checkpoints"]:
        state["checkpoints"].append(name)
    save_state(pathlib.Path(out_dir) / "state.json", state)
    print(f"CHECKPOINT {name}")
    if sink is not None:
        sink.capture(state, name)


def _review_findings(api, review_id: str) -> list[dict]:
    payload = api.get(f"/api/reviews/{review_id}/findings?limit=500")
    if isinstance(payload, dict) and payload.get("truncated"):
        raise DemoError(f"review {review_id}: more findings than the 500 read; decisions cannot "
                        "cover them all")
    return items(payload)


def _expected_knowledge(api, entry: dict, state: dict) -> tuple[set[str], int]:
    ids = set()
    for name in entry["appliesKnowledge"]:
        known = state["knowledge"].get(name)
        if known:
            ids.add(known["artifactId"])
        else:
            artifact = find_artifact(api, name)
            if artifact is None:
                raise DemoError(f"knowledge {name} is not in the state or the database; cannot "
                                "assert the review's applied knowledge")
            ids.add(artifact["id"])
    # The published number is the API's, not len(state["knowledge"]): a phase-B process
    # that started without phase A's state would otherwise pin C5 to 1.
    return ids, max_publication_number(api)


def pending_k2_ids(candidates: list[dict]) -> list[str]:
    for candidate in candidates:
        status = candidate.get("reviewStatus")
        if status in ("REJECTED", "SUPERSEDED"):
            raise DemoError(f"K2 candidate {candidate.get('id')} is {status}; the tour cannot "
                            "continue with a refused candidate")
    return [candidate["id"] for candidate in candidates
            if candidate.get("reviewStatus") == "PENDING"]


def approve_k2(api, artifact_id) -> dict:
    candidates = items(api.get(f"/api/knowledge/artifacts/{artifact_id}/candidates"))
    pending = pending_k2_ids(candidates)
    bulk = 0
    if pending:
        result = api.post("/api/knowledge/candidates/bulk-approve", {"candidateIds": pending})
        bulk = len(result.get("approvedIds") or [])
    after_bulk = items(api.get(f"/api/knowledge/artifacts/{artifact_id}/candidates"))
    individual = 0
    for candidate in after_bulk:
        if candidate.get("reviewStatus") == "PENDING":
            api.post(f"/api/knowledge/candidates/{candidate['id']}/approve",
                     {"note": "demo tour"})
            individual += 1
    final = items(api.get(f"/api/knowledge/artifacts/{artifact_id}/candidates"))
    still_pending = [candidate for candidate in final
                     if candidate.get("reviewStatus") == "PENDING"]
    if still_pending:
        raise DemoError(f"K2 still has {len(still_pending)} pending candidate(s) after approval")
    if not any(candidate.get("reviewStatus") in ("APPROVED", "EDITED")
               for candidate in final):
        raise DemoError("K2 has no approved candidate after approval")
    print(f"k2 approval: bulk={bulk} individual={individual}")
    if individual > 0:
        print("WARNING: the tour's \"Approve all\" step does not cover every K2 candidate")
    return {"bulk": bulk, "individual": individual, "pending": 0}


def _find_or_upload_k2(api, corpus, config) -> tuple[dict, str, list[dict]]:
    entries = demo_config.knowledge_for_phase(config, "tour")
    if len(entries) != 1:
        raise DemoError(f"expected exactly one tour knowledge source, found {len(entries)}")
    entry = entries[0]
    artifact = find_artifact(api, entry["file"])
    if artifact is None:
        artifact_id, artifact, candidates = _knowledge_upload(api, corpus, entry)
    else:
        artifact_id = artifact["id"]
        detail = api.get(f"/api/knowledge/artifacts/{artifact_id}")
        _validate_knowledge_artifact(entry, detail)
        candidates = items(api.get(f"/api/knowledge/artifacts/{artifact_id}/candidates"))
    if not candidates:
        raise DemoError(f"K2 {entry['file']}: the source compiled no candidates")
    not_pending = [candidate for candidate in candidates
                   if candidate.get("reviewStatus") != "PENDING"]
    if not_pending:
        raise DemoError(f"K2 {entry['file']}: {len(not_pending)} candidate(s) are already "
                        f"{not_pending[0].get('reviewStatus')}; the database was reused without "
                        "--recover")
    return entry, artifact_id, candidates


def _publish_k2(api, artifact_id: str, state: dict, entry: dict) -> int:
    candidates = items(api.get(f"/api/knowledge/artifacts/{artifact_id}/candidates"))
    approved = [candidate for candidate in candidates
                if candidate.get("reviewStatus") in ("APPROVED", "EDITED")]
    pending = [candidate for candidate in candidates
               if candidate.get("reviewStatus") == "PENDING"]
    if pending:
        raise DemoError(f"K2 has {len(pending)} pending candidate(s); approval did not finish")
    if not approved:
        raise DemoError("K2 has no approved candidate to publish")
    previous = max_publication_number(api)
    publication = api.post("/api/knowledge/publications",
                           {"candidateIds": [candidate["id"] for candidate in approved],
                            "note": "demo tour"})
    number = publication.get("publicationNumber")
    if number != previous + 1:
        raise DemoError(f"K2 publication number is {number}, expected {previous + 1}")
    if max_publication_number(api) != 4:
        raise DemoError("K2 must be publication 4: the tape's knowledge pin would change")
    mnda = items(api.get("/api/knowledge/applicable?contractType=MNDA"))
    msa = items(api.get("/api/knowledge/applicable?contractType=MSA"))
    if artifact_id not in {item.get("artifactId") for item in mnda}:
        raise DemoError("K2 does not apply to MNDA after publication")
    if artifact_id in {item.get("artifactId") for item in msa}:
        raise DemoError("K2 applies to MSA after publication; the tour would leak NDA knowledge")
    state["knowledge"][entry["file"]] = {"artifactId": artifact_id,
                                         "publicationId": publication.get("id"),
                                         "publicationNumber": number}
    print(f"knowledge {entry['file']} -> artifact {artifact_id} publication {number}")
    return number


def run_phase_b(config, corpus, api, tape_url, decisions_path, out_dir, state,
                review_timeout=1800, requested=("b",), sink=None) -> dict:
    decisions = load_decisions(decisions_path)
    validate_decisions(config, decisions)
    base_entries = demo_config.contracts_for_phase(config, "base")
    required = [entry["file"] for entry in base_entries if entry["stage"] != "analyzed"]
    missing = [name for name in required
               if not (decisions["contracts"].get(name) or {}).get("decisions")]
    if missing:
        raise DemoError(f"decisions file has no entries for {', '.join(missing)}; every base "
                        "contract that is not merely analyzed needs decisions")
    for entry in base_entries:
        if entry["stage"] == "analyzed" and (decisions["contracts"].get(entry["file"]) or {}).get(
                "decisions"):
            print(f"warning: decisions for {entry['file']} are ignored: its stage is analyzed")
    for entry in base_entries:
        review = find_review(api, entry["file"])
        if review is None:
            raise DemoError(f"review for {entry['file']} not found; run --phases a first")
        if review.get("status") != "COMPLETED":
            raise DemoError(f"review for {entry['file']} is {review.get('status')}, expected "
                            "COMPLETED")
        findings = _review_findings(api, review["id"])
        sections = section_texts(api, review["contractId"], review["contractVersionNumber"])
        expected_ids, expected_pin = _expected_knowledge(api, entry, state)
        assert_review_analysis(api, review, expected_ids, expected_pin)
        stage = entry["stage"]
        contract_decisions = (decisions["contracts"].get(entry["file"]) or {}).get("decisions", [])
        assert_decisions_cover_stage(stage, findings, contract_decisions, sections)
        if stage != "analyzed":
            apply_decisions(api, review["id"], findings, sections, contract_decisions,
                            entry["file"])
        if stage == "finalized":
            assert_stage(api, review["id"], "decided")
            finalize_and_verify(api, review, pathlib.Path(corpus) / entry["file"])
            assert_stage(api, review["id"], "finalized")
        else:
            assert_stage(api, review["id"], stage)
        state["reviews"][entry["file"]] = {
            "reviewId": review["id"],
            "contractId": review["contractId"],
            "versionNumber": review["contractVersionNumber"],
        }
    require_no_misses(tape_health(tape_url))
    _checkpoint(state, out_dir, "base", sink)

    k2_entry, k2_id, _ = _find_or_upload_k2(api, corpus, config)
    state["knowledge"].setdefault(k2_entry["file"], {})["artifactId"] = k2_id
    _checkpoint(state, out_dir, "tour-k2-compiled", sink)
    approve_k2(api, k2_id)
    _checkpoint(state, out_dir, "tour-k2-approved", sink)
    _publish_k2(api, k2_id, state, k2_entry)

    tour_entries = demo_config.contracts_for_phase(config, "tour")
    if len(tour_entries) != 1:
        raise DemoError(f"expected exactly one tour contract, found {len(tour_entries)}")
    tour_entry = tour_entries[0]
    contract = upload_contract(api, corpus, tour_entry)
    enrich_contract(api, contract, tour_entry)
    review = create_review(api, contract, tour_entry, review_timeout)
    expected_ids, expected_pin = _expected_knowledge(api, tour_entry, state)
    assert_review_analysis(api, review, expected_ids, expected_pin)
    state["reviews"][tour_entry["file"]] = {
        "reviewId": review["id"],
        "contractId": review["contractId"],
        "versionNumber": review["contractVersionNumber"],
    }
    dump_findings(api, [(tour_entry["file"], review)], out_dir, "c5")
    require_no_misses(tape_health(tape_url))
    _checkpoint(state, out_dir, "tour-k2-published", sink)
    if "c" not in requested:
        print(f"next: add the C5 decisions to {decisions_path}, then run --phases c")
    return state


def run_phase_c(config, corpus, api, tape_url, decisions_path, out_dir, state,
                review_timeout=1800, requested=("c",), sink=None) -> dict:
    tour_entries = demo_config.contracts_for_phase(config, "tour")
    if len(tour_entries) != 1:
        raise DemoError(f"expected exactly one tour contract, found {len(tour_entries)}")
    entry = tour_entries[0]
    review = find_review(api, entry["file"])
    if review is None:
        raise DemoError(f"review for {entry['file']} not found; run --phases b first")
    if review.get("status") != "COMPLETED":
        raise DemoError(f"review for {entry['file']} is {review.get('status')}, expected "
                        "COMPLETED")
    decisions = load_decisions(decisions_path)
    contract_decisions = (decisions["contracts"].get(entry["file"]) or {}).get("decisions", [])
    if not contract_decisions:
        raise DemoError(f"decisions file has no entries for {entry['file']}")
    validate_decisions(config, decisions)
    findings = _review_findings(api, review["id"])
    sections = section_texts(api, review["contractId"], review["contractVersionNumber"])
    expected_ids, expected_pin = _expected_knowledge(api, entry, state)
    assert_review_analysis(api, review, expected_ids, expected_pin)
    assert_decisions_cover_stage("decided", findings, contract_decisions, sections)
    apply_decisions(api, review["id"], findings, sections, contract_decisions, entry["file"])
    assert_stage(api, review["id"], "decided")
    _checkpoint(state, out_dir, "tour-c5-decided", sink)
    finalize_and_verify(
        api, review, pathlib.Path(corpus) / entry["file"],
        after_finalize=lambda: _checkpoint(state, out_dir, "tour-c5-finalized", sink))
    assert_stage(api, review["id"], "finalized")
    require_no_misses(tape_health(tape_url))
    _checkpoint(state, out_dir, "tour-c5-done", sink)
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
    parser.add_argument("--capture-dir",
                        help="write static API captures here at every checkpoint")
    parser.add_argument("--review-timeout", type=int, default=1800)
    parser.add_argument("--check-config", action="store_true")
    parser.add_argument("--check-decisions", action="store_true")
    args = parser.parse_args(argv)

    config_path = _path(args.config)
    decisions_path = _path(args.decisions)
    try:
        config = demo_config.load_config(config_path)
    except demo_config.ConfigError as error:
        print(error, file=sys.stderr)
        return 2
    if args.check_config:
        _check_config(config)
        return 0
    if args.check_decisions:
        try:
            decisions = load_decisions(decisions_path)
            validate_decisions(config, decisions)
        except demo_config.ConfigError as error:
            print(error, file=sys.stderr)
            return 2
        count = sum(len(payload.get("decisions", []))
                    for payload in decisions["contracts"].values())
        print(f"decisions OK: {count} entries for {len(decisions['contracts'])} contracts")
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
    if requested & {"b", "c"} and not decisions_path.is_file():
        print(f"decisions file not found: {decisions_path}", file=sys.stderr)
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
    recorder = capture_sink.MutationRecorder(api) if args.capture_dir else api
    sink = None
    if args.capture_dir:
        sink = capture_sink.CaptureSink(
            _path(args.capture_dir), args.base_url, recorder,
            corpus / "knowledge" / "NDA Playbook.xlsx")
    try:
        if "a" in requested:
            run_phase_a(config, corpus, recorder, args.tape_url, dump_dir, state,
                        args.review_timeout)
        if "b" in requested:
            run_phase_b(config, corpus, recorder, args.tape_url, decisions_path, dump_dir, state,
                        args.review_timeout, requested, sink)
        if "c" in requested:
            run_phase_c(config, corpus, recorder, args.tape_url, decisions_path, dump_dir, state,
                        args.review_timeout, requested, sink)
    except (DemoError, ApiError, demo_config.ConfigError, capture.CaptureError) as error:
        print(f"demo failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
