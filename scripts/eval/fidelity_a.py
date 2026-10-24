#!/usr/bin/env python3
"""A scorer: action / approval fidelity on an existing eval run.

Reads the action set built by `$CTX/eval/fidelity/tools/build_action_set.py` and one eval run's
`raw/*.json` (findings already fetched by run_eval.py). Findings are matched to action-set issues
the way run_eval.py matches issues: the answer key's sections are matched to the run's headings,
and an absence finding is credited by its position label or reference (DW-069). Files only: no
backend, no model call.

Metrics:
  approval recall      issues whose rule requires approval and whose findings raise one
  approval precision   raised approvals whose rule actually requires one
  approver accuracy    named (approver, stance) pairs the rule accepts / pairs named
  escalations with no  approvals raised on an issue whose rule needs none, plus escalating
  rule                 findings that match no action-set issue at all

Usage:
  python3 fidelity_a.py --set tune --run $CTX/eval/runs/dev2-release4-partR --out <dir>
"""
import argparse
import pathlib
import sys

import fidelity_common as common
import scoring
from calibration import resolve_run

RAISED_ACTIONS = {"ESCALATE_TO_HUMAN"}
# the comparison family a finding belongs to, for claiming it to the issue with that stance
FAMILY_STANCE = {"POSITION_WALK_AWAY": "walkAway", "POSITION_NEEDS_FALLBACK": "fallback",
                 "POSITION_ACCEPTABLE_FALLBACK": "fallback", "POSITION_AMBIGUOUS": "ambiguous",
                 "POSITION_UNCERTAIN": "uncertain"}


def _quality(finding):
    return finding.get("quality") or {}


def raised(finding):
    """Whether a rule comparison asks for approval or escalation.

    A POLICY_UNAVAILABLE (playbook-silent) advisory says a person should decide because no rule
    exists; it is not an approval demand, so it is counted separately and never as a false one.
    """
    quality = _quality(finding)
    family = str(finding.get("issueFamily") or "")
    rule_based = (family.startswith("POSITION_")
                  or quality.get("requiresApproval") is not None
                  or bool(quality.get("escalationTrigger")))
    if not rule_based:
        return False
    return (finding.get("proposedAction") in RAISED_ACTIONS
            or finding.get("reviewClassification") == "BLOCKING"
            or quality.get("requiresApproval") is True
            or bool(quality.get("escalationTrigger")))


def approvers_of(finding):
    """The approver a finding names, from the escalation authority the comparison recorded.

    The escalation trigger is the condition ("escalate above net 90"), not a person, so it never
    counts as an approver.
    """
    name = _quality(finding).get("escalationApprover")
    if not isinstance(name, str) or not name.strip():
        return []
    return [part.strip() for part in name.split(";") if part.strip()]


def findings_for(issue, contract, raw):
    """Findings the issue's rule is read from, matched as run_eval.py matches issues."""
    findings = list(raw.get("findings") or [])
    answer = {"sections": contract.get("sections") or []}
    section_of, _ = scoring.section_locator(
        answer, raw.get("nodes") or [], (raw.get("semantics") or {}).get("clauses") or [])
    wanted = set(issue.get("sectionIds") or [])
    matched = [finding for finding in findings
               if section_of(finding.get("subjectHeadingNodeId")) in wanted]
    if issue.get("category") == "MISSING_PROVISION":
        for finding in findings:
            if (finding.get("subjectKind") == "ABSENCE"
                    and scoring.absence_matches(finding, issue)
                    and finding not in matched):
                matched.append(finding)
    return matched


def _finding_key(finding):
    return finding.get("id") or id(finding)


def _claim_priority(issue, finding):
    """Prefer the issue whose stance the finding was judged on; then the more specific absence."""
    if (issue.get("category") == "MISSING_PROVISION"
            and finding.get("subjectKind") == "ABSENCE"):
        return (0, -len(common.content_words(issue.get("playbookRef") or "")))
    stance = FAMILY_STANCE.get(str(finding.get("issueFamily") or ""))
    return (0 if stance == issue.get("stance") else 1, 0)


def claim_findings(issue_list, candidate_lists):
    """One finding evidences one issue: the best stance match, then the earlier issue."""
    claims = {}
    for index, findings in enumerate(candidate_lists):
        for finding in findings:
            key = _finding_key(finding)
            priority = _claim_priority(issue_list[index], finding)
            if key not in claims or priority < claims[key][0]:
                claims[key] = (priority, index)
    return [[finding for finding in findings
             if claims[_finding_key(finding)][1] == index]
            for index, findings in enumerate(candidate_lists)]


def score_issue(issue, findings):
    expected = issue.get("approval") or {}
    acceptable = list(expected.get("acceptableApprovers") or [])
    approval_raised = any(raised(finding) for finding in findings)
    names = list(dict.fromkeys(name for finding in findings
                              for name in approvers_of(finding)))
    correct = [name for name in names if any(_same_name(name, allowed)
                                             for allowed in acceptable)]
    required = bool(expected.get("required"))
    return {
        "id": issue.get("id"),
        "issueId": issue.get("issueId"),
        "answerKey": issue.get("answerKey"),
        "rawFile": issue.get("rawFile"),
        "org": issue.get("org"),
        "contract": issue.get("contract"),
        "category": issue.get("category"),
        "stance": issue.get("stance"),
        "playbookRef": issue.get("playbookRef"),
        "ruleLabelId": (issue.get("rule") or {}).get("labelId"),
        "expected": {"needed": expected.get("needed"), "required": required,
                     "acceptableApprovers": acceptable},
        "findingIds": [finding.get("id") for finding in findings],
        "matchedFindings": len(findings),
        "approvalRaised": approval_raised,
        "approvers": names,
        "correctApprovers": correct,
        "wrongApprovers": [name for name in names if name not in correct],
        "escalationWithoutRule": approval_raised and not required,
    }


def _norm(name):
    return " ".join(word for word in common.WORD.findall((name or "").lower())
                    if word != "the")


def _same_name(left, right):
    return bool(_norm(left)) and _norm(left) == _norm(right)


def score_run(action_set, raws):
    """Score every contract with a raw file; errors, unjoined issues and off-key escalations stay."""
    issues_by_file = {}
    for issue in action_set.get("issues") or []:
        issues_by_file.setdefault(issue.get("rawFile"), []).append(issue)
    rows, contracts, errors = [], [], []
    skipped = issues_without_run = 0
    for contract in action_set.get("contracts") or []:
        raw_file = contract.get("rawFile")
        if raw_file not in raws:
            if issues_by_file.get(raw_file):
                errors.append({"rawFile": raw_file, "reason": "the run has no raw file"})
                issues_without_run += len(issues_by_file[raw_file])
            continue
        if not issues_by_file.get(raw_file):
            # no joined issue: this contract is not part of set A (an unjoined issue is listed)
            skipped += 1
            continue
        raw = raws[raw_file]
        matched_ids = set()
        contract_issues, seen_issues, issue_list, candidate_lists = [], set(), [], []
        for issue in issues_by_file.get(raw_file) or []:
            if issue.get("id") in seen_issues:
                errors.append({"rawFile": raw_file,
                               "reason": f"duplicate action-set issue {issue.get('id')}"})
                continue
            seen_issues.add(issue.get("id"))
            issue_list.append(issue)
            candidate_lists.append(findings_for(issue, contract, raw))
        claimed = claim_findings(issue_list, candidate_lists)
        for issue, findings in zip(issue_list, claimed):
            row = score_issue(issue, findings)
            rows.append(row)
            contract_issues.append(row)
            matched_ids.update(_finding_key(finding) for finding in findings)
        findings = list(raw.get("findings") or [])
        off_key = [finding for finding in findings
                   if _finding_key(finding) not in matched_ids and raised(finding)]
        advisories = [finding for finding in findings
                      if finding.get("id") not in matched_ids
                      and not raised(finding)
                      and finding.get("proposedAction") in RAISED_ACTIONS]
        contracts.append({
            "rawFile": raw_file,
            "contract": contract.get("file"),
            "org": contract.get("org"),
            "issues": len(contract_issues),
            "findings": len(findings),
            "matchedFindings": len(matched_ids),
            "escalationsWithoutRule": sum(row["escalationWithoutRule"]
                                          for row in contract_issues),
            "escalationsOffKey": len(off_key),
            "advisoryEscalations": len(advisories),
            "findingsOffKey": len(findings) - len(matched_ids),
        })
    captured = any("escalationTrigger" in (finding.get("quality") or {})
                   for raw in raws.values() for finding in raw.get("findings") or [])
    scored = {"issues": rows, "contracts": contracts, "contractsSkipped": skipped,
              "issuesWithoutRun": issues_without_run,
              "escalationTriggerCaptured": captured,
              "unjoined": list(action_set.get("unjoined") or []), "errors": errors}
    scored["totals"] = totals(scored)
    return scored


def totals(scored):
    issues = scored.get("issues") or []
    required = [row for row in issues if row["expected"]["required"]]
    raised_rows = [row for row in issues if row["approvalRaised"]]
    names = [name for row in issues for name in row["approvers"]]
    correct = [name for row in issues for name in row["correctApprovers"]]
    return {
        "issues": len(issues),
        "approvalRequired": len(required),
        "approvalHit": sum(1 for row in required if row["approvalRaised"]),
        "raised": len(raised_rows),
        "raisedRequired": sum(1 for row in raised_rows if row["expected"]["required"]),
        "approversNamed": len(names),
        "approversCorrect": len(correct),
        "escalationsWithoutRule": sum(1 for row in issues if row["escalationWithoutRule"]),
        "escalationsOffKey": sum(row["escalationsOffKey"]
                                 for row in scored.get("contracts") or []),
        "advisoryEscalations": sum(row["advisoryEscalations"]
                                   for row in scored.get("contracts") or []),
        "approvalRecall": (sum(1 for row in required if row["approvalRaised"]) / len(required)
                           if required else None),
        "approvalPrecision": (sum(1 for row in raised_rows if row["expected"]["required"])
                              / len(raised_rows) if raised_rows else None),
        "approverAccuracy": len(correct) / len(names) if names else None,
    }


def report_lines(scored, meta):
    total = scored["totals"]
    lines = [f"# Action and approval (A), set `{meta['set']}`", "",
             f"Run `{meta.get('run')}` · context `{meta.get('context')}` · "
             f"{meta.get('generated')} · action set `{meta.get('actionSet')}`", ""]
    lines += ["## Totals", "",
              f"- Issues scored: {total['issues']} "
              f"(+ {len(scored['unjoined'])} unjoined, listed below; "
              f"{scored.get('issuesWithoutRun', 0)} not scored because the run has no raw file)",
              f"- Approval recall: {common.pct(total['approvalHit'], total['approvalRequired'])}",
              f"- Approval precision: {common.pct(total['raisedRequired'], total['raised'])}"
              + (" (no approval was raised)" if not total["raised"] else ""),
              f"- Approver accuracy: {common.pct(total['approversCorrect'], total['approversNamed'])}"
              + ((" (the run predates escalationTrigger capture; re-run to measure it)"
                  if not scored.get("escalationTriggerCaptured")
                  else " (the run captured no approver)") if not total["approversNamed"] else ""),
              f"- Escalations raised where no rule applies: {total['escalationsWithoutRule']} "
              f"on matched issues; {total['escalationsOffKey']} more on rule findings that match "
              f"no action-set issue",
              f"- Findings matching no action-set issue: {sum(row['findingsOffKey'] for row in scored['contracts'])} "
              f"(of which {total['escalationsOffKey']} escalate)",
              f"- Policy-silent advisories (excluded from the escalation count): "
              f"{total['advisoryEscalations']}",
              f"- Contracts skipped because every issue there is unjoined: "
              f"{scored.get('contractsSkipped', 0)}",
              f"- Absence credit in this run: "
              f"{(meta.get('runMeta') or {}).get('absenceCredit') or 'not recorded (the run predates DW-069)'}",
              "- One finding is claimed by at most one issue (a section shared by two issues does "
              "not count one escalation twice); totals are issue-level.", ""]
    lines += ["## Per contract", ""]
    lines += common.table(
        ["Contract", "Issues", "Findings", "Matched findings", "Escalations without rule",
         "Off-key escalations", "Policy-silent advisories"],
        [[row["contract"] or row["rawFile"], row["issues"], row["findings"],
          row["matchedFindings"], row["escalationsWithoutRule"], row["escalationsOffKey"],
          row["advisoryEscalations"]]
         for row in scored["contracts"]])
    lines += ["", "## Issues", ""]
    lines += common.table(
        ["Issue", "Category", "Stance", "Approval needed", "Findings", "Raised", "Approvers"],
        [[row["id"], row["category"], row["stance"], row["expected"]["needed"],
          row["matchedFindings"], "yes" if row["approvalRaised"] else "no",
          ", ".join(row["approvers"]) or "-"]
         for row in scored["issues"]])
    lines += ["", "## Unjoined issues", ""]
    lines += [f"- {row['id']}: {row['reason']}" for row in scored["unjoined"]] or ["- none"]
    lines += ["", "## Errors", ""]
    lines += [f"- {row['rawFile']}: {row['reason']}" for row in scored["errors"]] or ["- none"]
    return lines


def write_outputs(out, scored, meta):
    return common.write_outputs(out, scored, meta, report_lines(scored, meta))


def load_run(run_dir):
    run_dir = resolve_run(pathlib.Path(run_dir))
    raws, errors = {}, []
    for path in sorted((run_dir / "raw").glob("*.json")):
        try:
            data = common.load_json(path)
        except ValueError as error:
            errors.append({"rawFile": path.name, "reason": f"invalid JSON: {error}"})
            continue
        if isinstance(data, dict) and "findings" in data:
            raws[path.name] = data
        elif isinstance(data, dict):
            errors.append({"rawFile": path.name, "reason": "no findings in this raw file"})
        # a list is a knowledge dump, not a contract run
    meta = {}
    if (run_dir / "results.json").exists():
        meta = (common.load_json(run_dir / "results.json").get("meta") or {})
    return run_dir, raws, meta, errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--set", required=True, dest="set_name")
    parser.add_argument("--run", required=True, type=pathlib.Path)
    parser.add_argument("--out", required=True, type=pathlib.Path)
    parser.add_argument("--context", type=pathlib.Path)
    parser.add_argument("--action-set", type=pathlib.Path)
    args = parser.parse_args()
    context = common.context_root(args.context)
    action_path = args.action_set or (context / "eval" / "fidelity" / args.set_name
                                      / "action-set.json")
    if not action_path.is_file():
        sys.exit(f"no action set at {action_path}; run build_action_set.py --set {args.set_name}")
    action_set = common.load_json(action_path)
    run_dir, raws, run_meta, load_errors = load_run(args.run)
    scored = score_run(action_set, raws)
    scored["errors"] = load_errors + scored["errors"]
    meta = {"scorer": "A", "set": args.set_name, "context": str(context),
            "run": str(run_dir), "actionSet": str(action_path),
            "runMeta": {key: run_meta.get(key) for key in
                        ("runId", "corpus", "git", "started", "engine", "absenceCredit")},
            "generated": common.generated()}
    report = write_outputs(args.out, scored, meta)
    print(f"report: {report}")


if __name__ == "__main__":
    main()
