"""Unit tests for the A (action / approval) scorer. Synthetic action sets and raw runs only.

Run: cd scripts/eval && python3 -m unittest test_fidelity_a.py
"""
import json
import pathlib
import shutil
import tempfile
import unittest

import fidelity_a

SCRATCH = (pathlib.Path(__file__).resolve().parents[2] / ".superpowers" / "sdd"
           / "r4-part2-scorers" / "tmp")
SCRATCH.mkdir(parents=True, exist_ok=True)
TMP = tempfile.TemporaryDirectory(dir=SCRATCH)

NODES = [{"id": "h1", "nodeType": "HEADING", "text": "1. Liability"},
         {"id": "h2", "nodeType": "HEADING", "text": "2. Payment"},
         {"id": "h3", "nodeType": "HEADING", "text": "3. Notices"}]
SECTIONS = [{"id": "S1", "heading": "Liability"}, {"id": "S2", "heading": "Payment"}]


def issue(issue_id="I1", category="WALK_AWAY_BREACH", sections=("S1",), stance="walkAway",
          needed="ESCALATION", required=True, acceptable=("General Counsel",),
          ref="F-01 Limitation of liability"):
    return {"id": f"one.json#{issue_id}", "issueId": issue_id, "answerKey": "one.json",
            "rawFile": "one.json", "org": "F", "contract": "contracts/one.docx",
            "category": category, "stance": stance, "playbookRef": ref,
            "sectionIds": list(sections),
            "rule": {"labelId": "f-1", "quote": "q"},
            "approval": {"needed": needed, "required": required, "kind": "ESCALATE",
                         "approvers": list(acceptable),
                         "acceptableApprovers": list(acceptable),
                         "authorityConflict": False}}


def finding(node="h1", action="ESCALATE_TO_HUMAN", classification="BLOCKING",
            issue_family="POSITION_WALK_AWAY", **quality):
    row = {"id": f"f-{node}-{action}", "subjectKind": "CLAUSE", "subjectHeadingNodeId": node,
           "absenceConcept": None, "issueFamily": issue_family, "severity": "HIGH",
           "reviewClassification": classification, "proposedAction": action,
           "quality": dict(quality)}
    return row


def raw(findings, checks=()):
    return {"nodes": NODES, "semantics": {"clauses": []}, "findings": list(findings),
            "checks": list(checks)}


def contract(issues, raw_file="one.json"):
    return {"answerKey": "one.json", "rawFile": raw_file, "file": "contracts/one.docx",
            "org": "F", "playbook": "playbooks/f.docx", "sections": SECTIONS}


def action_set(issues, unjoined=()):
    return {"set": "tune", "issues": list(issues), "unjoined": list(unjoined),
            "contracts": [contract([])]}


class MatchTest(unittest.TestCase):

    def test_a_finding_in_the_issue_section_matches(self):
        found = fidelity_a.findings_for(issue(), contract([]), raw([finding("h1")]))
        self.assertEqual(len(found), 1)

    def test_a_finding_in_another_section_does_not_match(self):
        found = fidelity_a.findings_for(issue(), contract([]), raw([finding("h2")]))
        self.assertEqual(found, [])

    def test_an_absence_finding_matches_a_missing_provision_by_its_label(self):
        row = issue("I9", category="MISSING_PROVISION", sections=(), stance="preferred",
                    needed="NONE", required=False, acceptable=(),
                    ref="G-03 Data protection")
        absence = {"id": "a1", "subjectKind": "ABSENCE", "subjectHeadingNodeId": None,
                   "absenceConcept": "INTERNATIONAL_TRANSFERS",
                   "positionLabel": "G-03 Data protection", "proposedAction": "ADD_REQUIRED_PROVISION",
                   "reviewClassification": "REVIEW_REQUIRED", "quality": {}}
        found = fidelity_a.findings_for(row, contract([]), raw([absence]))
        self.assertEqual([f["id"] for f in found], ["a1"])


class IssueScoreTest(unittest.TestCase):

    def score(self, action, findings):
        return fidelity_a.score_issue(action, fidelity_a.findings_for(
            action, contract([]), raw(findings)))

    def test_a_required_approval_raised_is_a_recall_hit_and_a_precision_hit(self):
        row = self.score(issue(), [finding("h1")])
        self.assertTrue(row["approvalRaised"])
        self.assertTrue(row["expected"]["required"])
        self.assertEqual(row["approvers"], [])

    def test_a_required_approval_never_raised_is_a_recall_miss(self):
        row = self.score(issue(), [finding("h1", action="USE_APPROVED_FALLBACK",
                                           classification="REVIEW_REQUIRED")])
        self.assertFalse(row["approvalRaised"])

    def test_an_escalation_on_a_rule_that_needs_none_is_an_escalation_without_rule(self):
        row = self.score(issue(needed="NONE", required=False, acceptable=()), [finding("h1")])
        self.assertTrue(row["escalationWithoutRule"])

    def test_an_acceptable_approver_from_a_conflict_counts_as_correct(self):
        row = self.score(issue(acceptable=("Commercial Manager", "General Counsel")),
                         [finding("h1", approvers=["General Counsel"])])
        self.assertEqual(row["approvers"], ["General Counsel"])
        self.assertEqual(row["correctApprovers"], ["General Counsel"])

    def test_an_approver_outside_the_acceptable_set_is_wrong(self):
        row = self.score(issue(), [finding("h1", approvers=["Head of Finance"])])
        self.assertEqual(row["correctApprovers"], [])
        self.assertEqual(row["wrongApprovers"], ["Head of Finance"])

    def test_a_playbook_silent_advisory_is_not_an_approval_demand(self):
        advisory = finding("h1", issue_family="POLICY_UNAVAILABLE")
        self.assertFalse(fidelity_a.raised(advisory))

    def test_requires_approval_quality_without_a_decision_still_raises(self):
        row = self.score(issue(), [finding("h1", action="USE_APPROVED_FALLBACK",
                                           classification="REVIEW_REQUIRED",
                                           requiresApproval=True)])
        self.assertTrue(row["approvalRaised"])


class RunScoreTest(unittest.TestCase):

    def run_score(self, issues, raws, unjoined=()):
        data = action_set(issues, unjoined)
        return fidelity_a.score_run(data, raws)

    def test_totals_count_issues_not_findings(self):
        scored = self.run_score(
            [issue("I1"), issue("I2", sections=("S2",), needed="NONE", required=False,
                                acceptable=())],
            {"one.json": raw([finding("h1"), finding("h2"), finding("h2")])})
        totals = scored["totals"]
        self.assertEqual((totals["approvalRequired"], totals["approvalHit"]), (1, 1))
        # two issues raised an approval; only I1's rule requires one
        self.assertEqual((totals["raised"], totals["raisedRequired"]), (2, 1))
        self.assertEqual(totals["escalationsWithoutRule"], 1)  # I2's rule needs none

    def test_one_finding_in_a_shared_section_is_claimed_by_one_issue_only(self):
        # I1 is a fallback issue and I2 a walk-away issue in the same section; the finding was
        # judged on the walk-away, so only I2 may count it
        scored = self.run_score(
            [issue("I1", category="NEEDS_FALLBACK", stance="fallback", needed="REQUIRED"),
             issue("I2", category="WALK_AWAY_BREACH", stance="walkAway")],
            {"one.json": raw([finding("h1", issue_family="POSITION_WALK_AWAY")])})
        rows = {row["issueId"]: row for row in scored["issues"]}
        self.assertEqual(rows["I1"]["matchedFindings"], 0)
        self.assertEqual(rows["I2"]["matchedFindings"], 1)
        self.assertFalse(rows["I1"]["escalationWithoutRule"])

    def test_the_most_specific_missing_provision_claims_the_absence_finding(self):
        def missing(issue_id, ref):
            row = issue(issue_id, category="MISSING_PROVISION", sections=(), stance="preferred",
                        needed="NONE", required=False, acceptable=(), ref=ref)
            return row
        scored = self.run_score([missing("I1", "G-03 Data protection"),
                                 missing("I2", "G-03 Data protection and privacy")],
                                {"one.json": raw([{
                                    "id": "a1", "subjectKind": "ABSENCE",
                                    "subjectHeadingNodeId": None,
                                    "absenceConcept": "DATA_PROTECTION",
                                    "positionLabel": "G-03 Data protection and privacy",
                                    "proposedAction": "ADD_REQUIRED_PROVISION",
                                    "reviewClassification": "REVIEW_REQUIRED",
                                    "quality": {}}])})
        self.assertEqual([row["matchedFindings"] for row in scored["issues"]], [0, 1])

    def test_an_escalating_finding_outside_every_issue_section_is_reported(self):
        scored = self.run_score([issue()], {"one.json": raw(
            [finding("h1"), finding("h3")])})
        self.assertEqual(scored["totals"]["escalationsOffKey"], 1)
        self.assertEqual(scored["contracts"][0]["escalationsOffKey"], 1)

    def test_a_duplicate_action_set_issue_is_an_error_not_a_second_score(self):
        scored = self.run_score([issue("I1"), issue("I1")], {"one.json": raw([finding("h1")])})
        self.assertEqual(len(scored["issues"]), 1)
        self.assertIn("duplicate", scored["errors"][0]["reason"])

    def test_unjoined_issues_are_listed_with_their_reason(self):
        scored = self.run_score([issue()], {"one.json": raw([])},
                                unjoined=[{"id": "one.json#I2",
                                           "reason": "category 'AMBIGUOUS' names no stance"}])
        self.assertEqual(scored["unjoined"][0]["id"], "one.json#I2")
        self.assertIn("names no stance", scored["unjoined"][0]["reason"])

    def test_a_missing_raw_file_is_an_error_not_a_zero(self):
        scored = self.run_score([issue()], {})
        self.assertEqual(scored["errors"][0]["rawFile"], "one.json")
        self.assertEqual(scored["issues"], [])
        self.assertEqual(scored["issuesWithoutRun"], 1)

    def test_an_escalation_trigger_names_the_approver(self):
        scored = self.run_score([issue()], {"one.json": raw([
            finding("h1", escalationTrigger="General Counsel; Head of Finance")])})
        self.assertEqual(scored["issues"][0]["approvers"],
                         ["General Counsel", "Head of Finance"])
        self.assertEqual(scored["issues"][0]["correctApprovers"], ["General Counsel"])

    def test_approver_accuracy_is_by_named_pair(self):
        scored = self.run_score([issue("I1"), issue("I2", sections=("S2",))], {"one.json": raw([
            finding("h1", approvers=["General Counsel", "Head of Finance"]),
            finding("h2", approvers=["General Counsel", "Head of Finance"])])})
        totals = scored["totals"]
        self.assertEqual((totals["approversNamed"], totals["approversCorrect"]), (4, 2))
        self.assertEqual(totals["approverAccuracy"], 0.5)


class LoadRunTest(unittest.TestCase):

    def test_a_corrupt_raw_file_is_reported_not_dropped(self):
        run = pathlib.Path(TMP.name, "load-run")
        if run.exists():
            shutil.rmtree(run)
        (run / "raw").mkdir(parents=True)
        (run / "raw" / "bad.json").write_text("{not json")
        (run / "raw" / "empty.json").write_text(json.dumps({"nodes": []}))
        (run / "raw" / "knowledge.json").write_text(json.dumps([{"id": "c1"}]))
        (run / "raw" / "good.json").write_text(json.dumps(raw([])))
        _, raws, _, errors = fidelity_a.load_run(run)
        self.assertEqual(sorted(raws), ["good.json"])
        self.assertEqual({row["rawFile"] for row in errors}, {"bad.json", "empty.json"})
        self.assertTrue(all("reason" in row for row in errors))


class ReportTest(unittest.TestCase):

    def test_the_report_names_every_metric_and_prints_the_path(self):
        out = pathlib.Path(TMP.name, "a-report")
        if out.exists():
            shutil.rmtree(out)
        data = action_set([issue()], [{"id": "one.json#I2", "reason": "no stance"}])
        scored = fidelity_a.score_run(data, {"one.json": raw([finding("h1")])})
        path = fidelity_a.write_outputs(out, scored, {"set": "tune", "run": "r",
                                                      "context": "ctx"})
        text = (out / "summary.md").read_text()
        self.assertEqual(path, out / "summary.md")
        for marker in ("# Action and approval (A)", "set `tune`", "Approval precision",
                       "Approver accuracy", "Escalations raised where no rule applies",
                       "## Unjoined issues"):
            self.assertIn(marker, text)
        written = json.loads((out / "results.json").read_text())
        self.assertEqual(written["meta"]["run"], "r")
        self.assertEqual(len(written["results"]["issues"]), 1)
        self.assertIn("Absence credit in this run", text)
        self.assertIn("predates escalationTrigger capture", text)


if __name__ == "__main__":
    unittest.main()
