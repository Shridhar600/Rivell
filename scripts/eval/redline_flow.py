"""Redline round trip for one reviewed contract, driven over the real API.

A scripted reviewer accepts every actionable finding that carries an executable proposal (one
change per clause, highest severity first) and rejects the rest with a comment. Then: finalize,
redline, download, independent checks (redline_check.py), and a re-upload of the redline to see
how the parser reads a tracked-change document.
"""
import pathlib
import uuid

import redline_check
from rivell_api import ApiError, items

SEVERITY_ORDER = {"HIGH": 0, "MEDIUM": 1, "LOW": 2}
EXECUTABLE = {"USE_APPROVED_FALLBACK", "CONSIDER_GENERATED_LANGUAGE"}


def _decide(api, review_id, findings):
    counts = {"accepted": 0, "rejected": 0, "decideErrors": 0}
    used = set()
    actionable = [f for f in findings if f.get("reviewClassification") in ("BLOCKING", "REVIEW_REQUIRED")]
    for f in sorted(actionable, key=lambda f: SEVERITY_ORDER.get(f.get("severity"), 3)):
        target = f.get("subjectHeadingNodeId") or ("absence", f.get("absenceConcept"))
        executable = f.get("proposedAction") in EXECUTABLE and f.get("proposedLanguageSource") != "NONE"
        if executable:
            detail = api.get(f"/api/reviews/{review_id}/findings/{f['id']}")
            executable = bool((detail.get("proposedLanguage") or "").strip())
        body = {"expectedVersion": 0, "clientRequestId": str(uuid.uuid4())}
        if executable and target not in used:
            used.add(target)
            body["action"] = "ACCEPT"
            counts["accepted"] += 1
        else:
            reason = "another accepted change targets this clause" if executable else "no executable proposal"
            body.update(action="REJECT", comment=f"eval reviewer: {reason}")
            counts["rejected"] += 1
        try:
            api.put(f"/api/reviews/{review_id}/findings/{f['id']}/decision", body)
        except ApiError as e:
            if body["action"] == "ACCEPT" and e.status == 409:
                # The product refused the change (e.g. a table or schedule target): reject it.
                counts["accepted"] -= 1
                counts["acceptRefused"] = counts.get("acceptRefused", 0) + 1
                used.discard(target)
                body = {"expectedVersion": 0, "clientRequestId": str(uuid.uuid4()), "action": "REJECT",
                        "comment": "eval reviewer: the product refused the change"}
                try:
                    api.put(f"/api/reviews/{review_id}/findings/{f['id']}/decision", body)
                    counts["rejected"] += 1
                    continue
                except ApiError as retry:
                    e = retry
            counts["decideErrors"] += 1
            counts.setdefault("decideErrorSamples", []).append(f"{body['action']} {f['id']}: {e.status}")
    return counts


def _top_headings(nodes):
    return sum(1 for n in nodes if n.get("nodeType") == "HEADING")


def run_redline(api, review_id, original_bytes, nodes, file_name):
    out = {"stage": "decide"}
    out.update(_decide(api, review_id, items(api.get(f"/api/reviews/{review_id}/findings?limit=500"))))
    human = api.get(f"/api/reviews/{review_id}/human-review")
    if not human.get("readyToFinalize"):
        out.update(stage="finalize", blockers=[b["code"] for b in human.get("blockers", [])])
        return out
    change_set = api.post(f"/api/reviews/{review_id}/finalize")
    changes = change_set.get("changes", [])
    out.update(stage="redline", changes=len(changes),
               changeTypes=sorted({c["actionType"] for c in changes}))
    try:
        execution = api.post(f"/api/reviews/{review_id}/redline")
    except ApiError as e:
        if e.status != 409:
            raise
        execution = api.get(f"/api/reviews/{review_id}/redline")
    out.update(redlineStatus=execution.get("status"), outcome=execution.get("outcome"),
               failureCode=execution.get("failureCode"),
               changeFailures=sorted({c.get("failureCode") for c in execution.get("changes", [])
                                      if c.get("failureCode")}))
    if not execution.get("downloadAvailable"):
        return out
    redline_bytes = api.get_bytes(f"/api/reviews/{review_id}/redline/download")
    out["stage"] = "checks"
    checks = redline_check.check(original_bytes, redline_bytes, nodes, changes)
    out["checks"] = checks
    # A tracked-change DOCX coming back in (e.g. a counterparty's markup) must still parse.
    name = pathlib.Path(file_name).stem + "-redline.docx"
    try:
        upload = api.upload("/api/contracts", pathlib.Path(name), content=redline_bytes)
        version = f"/api/contracts/{upload['contractId']}/versions/{upload['versionNumber']}"
        structure = api.get(f"{version}/structure")
        renodes = structure if isinstance(structure, list) else structure.get("nodes", [])
        parsed = redline_check.norm(" ".join(n.get("text") or "" for n in renodes))
        out["reparse"] = {
            "parseStatus": upload.get("parseStatus"),
            "headings": _top_headings(renodes), "originalHeadings": _top_headings(nodes),
            "seesInsertedText": all(redline_check.norm(c.get("approvedLanguage")) in parsed
                                    for c in changes if c.get("approvedLanguage")),
            "seesDeletedText": any(f in parsed for f in redline_check.deleted_fragments(redline_bytes)),
        }
    except ApiError as e:
        out["reparse"] = {"error": f"{e.status}"}
    out["stage"] = "done"
    return out
