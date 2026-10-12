"""Unit tests for the P0 calibration dataset. Run: cd scripts/eval && python3 -m unittest test_calibration.py"""
import json
import pathlib
import tempfile
import unittest

import calibration

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

    def test_a_holdout_is_refused(self):
        with self.assertRaises(SystemExit):
            calibration.build(pathlib.Path("/x/eval/corpus/holdout2-fixed"), [])

    def test_a_run_from_before_p0_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            corpus, run = pathlib.Path(tmp, "dev2"), pathlib.Path(tmp, "run1")
            (corpus / "answers").mkdir(parents=True)
            (run / "raw").mkdir(parents=True)
            (corpus / "answers" / "c.json").write_text(json.dumps(ANSWER))
            old = raw([{"key": "CLAUSE:POLICY_COMPARISON:TOPIC:h1", "subjectHeadingNodeId": "h1",
                        "status": "COMPLETED"}])
            (run / "raw" / "c.json").write_text(json.dumps(old))
            with self.assertRaises(SystemExit):
                calibration.build(corpus, [run])


if __name__ == "__main__":
    unittest.main()
