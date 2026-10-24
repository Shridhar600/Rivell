"""Unit tests for the P0 calibration dataset. Run: cd scripts/eval && python3 -m unittest test_calibration.py"""
import json
import pathlib
import tempfile
import unittest

import calibration

# scratch lives inside the project (never /tmp or $TMPDIR)
SCRATCH = pathlib.Path(__file__).resolve().parents[2] / ".superpowers" / "sdd" / "r4-part2-scorers" / "tmp"
SCRATCH.mkdir(parents=True, exist_ok=True)
TMP = tempfile.TemporaryDirectory(dir=SCRATCH)

UNIFORM = {"MEETS_PREFERRED": 0.02, "WITHIN_FALLBACK": 0.02, "BEYOND_FALLBACK": 0.02,
           "WALK_AWAY": 0.02, "UNCLEAR": 0.02, "NOT_ADDRESSED": 0.02}


def probs(**top):
    p = dict(UNIFORM)
    p.update(top)
    return p


def check(node, positions, status="COMPLETED", probe=None, uncovered=None):
    return {"key": f"CLAUSE:POLICY_COMPARISON:TOPIC:{node}", "subjectHeadingNodeId": node,
            "status": status, "judgedPositions": positions, "probe": probe, "uncoveredProbability": uncovered}


def position(p, flagged=False, category="", probe=None, probe_flagged=None):
    row = {"positionKey": "position_1", "positionCandidateId": "c1", "category": category,
           "flagged": flagged, "probabilities": p}
    if probe is not None:
        row.update(probeProbabilities=probe, probeFlagged=probe_flagged, probeCategory="")
    return row


ANSWER = {
    "sections": [{"id": "S1", "heading": "Limitation of Liability"}, {"id": "S2", "heading": "Confidentiality"},
                 {"id": "S3", "heading": "Governing Law"}, {"id": "S4", "heading": "Notices"},
                 {"id": "S5", "heading": "Schedule 9 Fees"}],
    "expectedIssues": [
        {"id": "I1", "category": "NEEDS_FALLBACK", "severity": "MEDIUM", "sectionIds": ["S1"]},
        {"id": "I2", "category": "AMBIGUOUS", "severity": "LOW", "sectionIds": ["S2"]},
        {"id": "I3", "category": "UNADDRESSED", "severity": "MEDIUM", "sectionIds": ["S3"]},
        {"id": "I4", "category": "NEEDS_FALLBACK", "severity": "HIGH", "sectionIds": ["S5"]}],
    "compliantSectionIds": ["S4"],
}


def raw(checks, findings=()):
    nodes = [{"id": f"h{i}", "nodeType": "HEADING", "text": f"{i}. {t}"}
             for i, t in enumerate(["Limitation of Liability", "Confidentiality", "Governing Law", "Notices"], 1)]
    return {"nodes": nodes, "semantics": {"clauses": []}, "findings": list(findings), "checks": checks}


def standard_raw(**probe_rows):
    return raw([
        # I1: the judge leaned to "meets" without conviction -> doubtful miss
        check("h1", [position(probs(MEETS_PREFERRED=0.50, BEYOND_FALLBACK=0.40), **probe_rows)]),
        # I2: the judge was sure the clause meets the position -> confident miss
        check("h2", [position(probs(MEETS_PREFERRED=0.90))]),
        # I3: no position matched; only the triage read it -> not judged
        check("h3", []),
        # S4 compliant: a doubtful UNCLEAR flag raised a MEDIUM finding -> doubtful alarm
        check("h4", [position(probs(UNCLEAR=0.55, MEETS_PREFERRED=0.35), flagged=True, category="UNCLEAR")]),
    ], findings=[{"subjectHeadingNodeId": "h4", "severity": "MEDIUM"}])


class BucketTest(unittest.TestCase):

    def test_misses_are_sorted_by_what_the_judge_saw(self):
        records = calibration.build_records("r1", "c", ANSWER, standard_raw())
        self.assertEqual(calibration.miss_buckets(records),
                         {"DOUBTFUL_MISS": {"NEEDS_FALLBACK": 1}, "CONFIDENT_MISS": {"AMBIGUOUS": 1},
                          "NOT_JUDGED": {"UNADDRESSED": 1}, "NO_CHECK": {"NEEDS_FALLBACK": 1}})

    def test_a_miss_the_key_places_in_no_section_is_an_absence(self):
        # a provision missing from the contract has no section; it is not a section without a check
        answer = dict(ANSWER, expectedIssues=ANSWER["expectedIssues"] + [
            {"id": "I5", "category": "MISSING_PROVISION", "severity": "HIGH", "sectionIds": []}])
        records = calibration.build_records("r1", "c", answer, standard_raw())
        self.assertEqual(calibration.miss_buckets(records)["ABSENCE"], {"MISSING_PROVISION": 1})
        self.assertEqual(calibration.miss_buckets(records)["NO_CHECK"], {"NEEDS_FALLBACK": 1})

    def test_a_failed_check_is_its_own_bucket(self):
        records = calibration.build_records("r1", "c", ANSWER, raw([check("h1", [], status="DEGRADED")]))
        self.assertEqual(calibration.miss_buckets(records)["CHECK_FAILED"], {"NEEDS_FALLBACK": 1})

    def test_a_miss_no_matched_position_addresses_is_left_to_the_triage(self):
        # every matched position judged the clause off its subject: the uncovered triage decided
        records = calibration.build_records("r1", "c", ANSWER, raw([
            check("h2", [position(probs(NOT_ADDRESSED=0.90))], uncovered=0.3),
            check("h3", [], uncovered=0.1)]))
        self.assertEqual(calibration.miss_buckets(records)["OFF_TOPIC"], {"AMBIGUOUS": 1})
        self.assertEqual(calibration.triage_misses(records), [0.1, 0.3])

    def test_alarms_are_sorted_by_source_and_confidence(self):
        records = calibration.build_records("r1", "c", ANSWER, standard_raw())
        self.assertEqual(calibration.alarm_buckets(records), {"DOUBTFUL_ALARM": 1})

    def test_probe_counts_flips_and_the_misses_it_would_flag(self):
        flipped = probs(BEYOND_FALLBACK=0.70, MEETS_PREFERRED=0.20)
        records = calibration.build_records("r1", "c", ANSWER, standard_raw(probe=flipped, probe_flagged=True))
        summary = calibration.probe_summary(records)
        self.assertEqual((summary["positions"], summary["topFlips"], summary["flagFlips"]), (1, 1, 1))
        self.assertEqual((summary["missesProbeFlags"], summary["misses"]), (1, 4))

    def test_doubtful_positions_estimate_the_adjudication_load(self):
        records = calibration.build_records("r1", "c", ANSWER, standard_raw())
        self.assertEqual(calibration.adjudication_load(records), (3, 2, 2.0))


class GuardTest(unittest.TestCase):

    def test_second_opinions_count_outcomes_and_whom_the_key_backs(self):
        def adjudicated(p, flagged, outcome, category):
            return dict(position(p, flagged=flagged), route="ADJUDICATE",
                        secondOpinionOutcome=outcome, secondOpinionCategory=category)
        records = calibration.build_records("run", "c", ANSWER, raw([
            # S1 holds a planted issue: Jev passed it, the second opinion flagged it -> key backs the second opinion
            check("h1", [adjudicated(probs(MEETS_PREFERRED=0.45, BEYOND_FALLBACK=0.45), False,
                                     "DISAGREED", "BEYOND_FALLBACK")]),
            # S4 is compliant: Jev flagged it, the second opinion did not -> key backs the second opinion
            check("h4", [adjudicated(probs(UNCLEAR=0.6, MEETS_PREFERRED=0.3), True,
                                     "DISAGREED", "MEETS_PREFERRED")]),
            # S2: both flagged
            check("h2", [adjudicated(probs(BEYOND_FALLBACK=0.6, MEETS_PREFERRED=0.3), True,
                                     "AGREED", "BEYOND_FALLBACK")]),
        ]))
        summary = calibration.second_opinion_summary(records)
        self.assertEqual(summary["adjudicated"], 3)
        self.assertEqual(summary["perContract"], 3.0)
        self.assertEqual(summary["outcomes"], {"DISAGREED": 2, "AGREED": 1})
        self.assertEqual(summary["disagreementsBack"], {"SECOND_OPINION": 2})

    def test_a_holdout_is_refused(self):
        with self.assertRaises(SystemExit):
            calibration.build(pathlib.Path("/x/eval/corpus/holdout2-fixed"), [])

    def test_a_run_from_before_p0_is_refused(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
            corpus, run = pathlib.Path(tmp, "dev2"), pathlib.Path(tmp, "run1")
            (corpus / "answers").mkdir(parents=True)
            (run / "raw").mkdir(parents=True)
            (corpus / "answers" / "c.json").write_text(json.dumps(ANSWER))
            old = raw([{"key": "CLAUSE:POLICY_COMPARISON:TOPIC:h1", "subjectHeadingNodeId": "h1",
                        "status": "COMPLETED"}])
            (run / "raw" / "c.json").write_text(json.dumps(old))
            with self.assertRaises(SystemExit):
                calibration.build(corpus, [run])


class Dw059Test(unittest.TestCase):
    """Pack D, DW-059: the four eval-tooling minors."""

    def setUp(self):
        self.root = pathlib.Path(TMP.name, f"dw059-{self._testMethodName}")
        if self.root.exists():
            import shutil
            shutil.rmtree(self.root)
        (self.root / "corpus" / "answers").mkdir(parents=True)
        (self.root / "corpus" / "answers" / "c.json").write_text(json.dumps(ANSWER))

    def run_dir(self, name, checks, meta=None):
        run = self.root / "runs" / name
        (run / "raw").mkdir(parents=True)
        (run / "raw" / "c.json").write_text(json.dumps(raw(checks)))
        if meta is not None:
            (run / "results.json").write_text(json.dumps({"meta": meta}))
        return run

    def test_a_run_whose_own_meta_names_a_holdout_is_refused(self):
        # DW-059(1): the --corpus path said dev2, but the run recorded a holdout in its meta
        run = self.run_dir("holdout-run", [check("h1", [position(probs(MEETS_PREFERRED=0.9))])],
                           meta={"corpus": "/x/eval/corpus/holdout3"})
        with self.assertRaises(SystemExit) as raised:
            calibration.build(self.root / "corpus", [run])
        self.assertIn("holdout", str(raised.exception))

    def test_a_run_whose_meta_names_train_data_is_accepted(self):
        run = self.run_dir("dev2-run", [check("h1", [position(probs(MEETS_PREFERRED=0.9))])],
                           meta={"corpus": "/x/eval/corpus/dev2"})
        records = calibration.build(self.root / "corpus", [run])
        self.assertTrue(records)

    def test_a_run_root_resolves_to_its_latest_timestamped_run(self):
        # the owner passes eval/runs/<run>; the data sits in eval/runs/<run>/<ts>
        older = self.run_dir("root/20260101-000000", [check("h1", [position(probs(MEETS_PREFERRED=0.9))])])
        newer = self.run_dir("root/20260102-000000", [check("h1", [position(probs(MEETS_PREFERRED=0.9))])])
        # the run's own corpus may be recorded at the root instead of the timestamped child
        (self.root / "runs" / "root" / "results.json").write_text(
            json.dumps({"meta": {"corpus": "/x/eval/corpus/dev2"}}))
        records = calibration.build(self.root / "corpus", [self.root / "runs" / "root"])
        self.assertTrue(records)
        self.assertEqual({r["run"] for r in records}, {newer.name})
        self.assertEqual(older.name, "20260101-000000")

    def test_a_run_with_no_recorded_corpus_is_refused(self):
        # no meta: a holdout run with its meta stripped would otherwise pass
        run = self.run_dir("no-meta", [check("h1", [position(probs(MEETS_PREFERRED=0.9))])])
        with self.assertRaises(SystemExit) as raised:
            calibration.build(self.root / "corpus", [run])
        self.assertIn("records no corpus", str(raised.exception))

    def test_a_score_with_a_different_issue_count_is_refused(self):
        # DW-059(2): build_records zipped the key and the score without checking the lengths
        with self.assertRaises(ValueError) as raised:
            calibration.build_records("r", "c", ANSWER, raw([]), score={"issues": []})
        self.assertIn("expectedIssues", str(raised.exception))

    def test_one_policy_check_without_judged_positions_refuses_the_run(self):
        # DW-059(3): any() let a partially P0 run through; every policy check must carry the field
        run = self.run_dir("partial", [
            check("h1", [position(probs(MEETS_PREFERRED=0.9))]),
            {"key": "CLAUSE:POLICY_COMPARISON:TOPIC:h2", "subjectHeadingNodeId": "h2",
             "status": "COMPLETED"},
        ], meta={"corpus": "/x/eval/corpus/dev2"})
        with self.assertRaises(SystemExit) as raised:
            calibration.build(self.root / "corpus", [run])
        self.assertIn("judgedPositions", str(raised.exception))

    def test_a_run_with_no_policy_check_is_refused(self):
        run = self.run_dir("no-policy", [{"key": "CLAUSE:OTHER:h1", "status": "COMPLETED",
                                          "judgedPositions": []}],
                           meta={"corpus": "/x/eval/corpus/dev2"})
        with self.assertRaises(SystemExit):
            calibration.build(self.root / "corpus", [run])

    def test_a_null_judged_positions_is_not_a_p0_check(self):
        run = self.run_dir("null-positions", [
            {"key": "CLAUSE:POLICY_COMPARISON:TOPIC:h1", "subjectHeadingNodeId": "h1",
             "status": "COMPLETED", "judgedPositions": None}],
            meta={"corpus": "/x/eval/corpus/dev2"})
        with self.assertRaises(SystemExit):
            calibration.build(self.root / "corpus", [run])

    def test_every_calibration_row_carries_the_absence_credit_rule(self):
        records = calibration.build_records("r", "c", ANSWER, standard_raw())
        self.assertEqual({row["absenceCredit"] for row in records},
                         {calibration.ABSENCE_CREDIT})
        text = calibration.report(records)
        self.assertIn("Absence credit: label/reference whole-word", text)

    def test_old_rows_without_the_marker_say_so(self):
        records = calibration.build_records("r", "c", ANSWER, standard_raw())
        for row in records:
            row.pop("absenceCredit")
        self.assertIn("Absence credit: not recorded", calibration.report(records))

    def test_a_blank_probe_category_reads_as_null_like_the_api(self):
        # DW-059(5): the check detail writes "" while the diagnostics API writes null
        records = calibration.build_records("r", "c", ANSWER, standard_raw(
            probe=probs(BEYOND_FALLBACK=0.7), probe_flagged=True))
        judged = [r for r in records if r["kind"] == "judged" and r.get("positionKey")]
        self.assertEqual(judged[0]["probeCategory"], None)
        self.assertTrue(judged[0]["probeFlagged"])


if __name__ == "__main__":
    unittest.main()

