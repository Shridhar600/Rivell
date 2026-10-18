"""Unit tests for the engine v2 lines of the eval report. Run: cd scripts/eval && python3 -m unittest test_run_eval_report.py"""
import os
import unittest
from unittest import mock

import run_eval


def contract(detected, alarms, decided, without=None):
    scored = {"issuesDetected": detected, "falseAlarmSections": alarms, "decidedBy": decided}
    if without:
        scored["withoutNeedsYourLook"] = without
    return scored


class NeedsLookLineTest(unittest.TestCase):

    def test_reports_the_group_and_what_only_it_reaches(self):
        results = [{"contracts": [
            contract(5, 1, {"NEEDS_YOUR_LOOK": 2, "SECOND_OPINION": 1, "JEV": 4},
                     without={"issuesDetected": 4, "falseAlarmSections": 0}),
            contract(3, 0, {"JEV": 3}),
        ]}]
        line = run_eval.needs_look_line(results)
        self.assertEqual(len(line), 1)
        self.assertIn("1 findings decided with a second opinion", line[0])
        self.assertIn('2 "needs your look" (1.0 per contract)', line[0])
        self.assertIn("planted issues only they reach: 1", line[0])
        self.assertIn("compliant sections only they touch: 1", line[0])

    def test_an_engine_v1_run_adds_no_line(self):
        self.assertEqual(run_eval.needs_look_line([{"contracts": [contract(3, 0, {"JEV": 3})]}]), [])


class EngineMetaTest(unittest.TestCase):

    def test_the_defaults_describe_the_engine_the_backend_now_uses(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(run_eval.engine_meta(),
                             {"engine": "v2", "omissions": "false", "definitions": "false"})

    def test_the_default_definitions_read_is_off(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(run_eval.engine_meta()["definitions"], "false")

    def test_the_environment_overrides_all_three_and_is_lower_cased(self):
        # the engine switch is retired: the backend always freezes v2, so only the live switches
        # (omissions, definitions) still come from the environment
        with mock.patch.dict(os.environ,
                             {"RIVELL_REVIEW_ENGINE": "V1", "RIVELL_REVIEW_OMISSIONS": "TRUE",
                              "RIVELL_REVIEW_DEFINITIONS": "FALSE"}):
            self.assertEqual(run_eval.engine_meta(),
                             {"engine": "v2", "omissions": "true", "definitions": "false"})


class StoredEngineTest(unittest.TestCase):

    def test_the_run_label_comes_from_the_stored_review_snapshot(self):
        self.assertEqual(run_eval.review_engine({"snapshot": {"engine": "V2"}}), "v2")
        results = [{"contracts": [{"reviewEngine": "v2"}, {"reviewEngine": "v2"}]}]
        self.assertEqual(run_eval.frozen_engine(results), "v2")

    def test_a_review_that_froze_on_another_engine_names_it(self):
        results = [{"contracts": [{"reviewEngine": "v1"}]}]
        self.assertEqual(run_eval.frozen_engine(results), "v1")

    def test_a_review_without_a_snapshot_defers_to_v2(self):
        self.assertEqual(run_eval.review_engine({}), "v2")
        self.assertEqual(run_eval.frozen_engine([]), "v2")
        self.assertEqual(run_eval.frozen_engine([{"contracts": [{}]}]), "v2")


class ReportHeaderTest(unittest.TestCase):

    @staticmethod
    def meta(**overrides):
        return {"runId": "r", "corpus": "c", "git": "g", "started": "s", **overrides}

    def test_the_v2_default_engine_and_the_lane_off_add_nothing(self):
        text = "\n".join(run_eval.header_lines(self.meta(engine="v2", omissions="false")))
        self.assertNotIn("engine", text)
        self.assertNotIn("omissions", text)

    def test_issue_spotting_is_not_claimed_from_a_retired_switch(self):
        text = "\n".join(run_eval.header_lines(self.meta(engine="v2")))
        self.assertNotIn("issue spotting", text)

    def test_a_v1_run_names_the_engine_and_the_lane_only_when_on(self):
        text = "\n".join(run_eval.header_lines(self.meta(engine="v1", omissions="true")))
        self.assertIn("engine `v1`", text)
        self.assertIn("omissions on", text)

    def test_a_v1_run_with_the_lane_off_does_not_name_the_lane(self):
        text = "\n".join(run_eval.header_lines(self.meta(engine="v1", omissions="false")))
        self.assertNotIn("omissions", text)

    def test_definitions_on_is_named_only_when_on(self):
        on = "\n".join(run_eval.header_lines(self.meta(engine="v2", omissions="false",
                                                       definitions="true")))
        self.assertIn("definitions on", on)
        off = "\n".join(run_eval.header_lines(self.meta(engine="v2", omissions="false",
                                                        definitions="false")))
        self.assertNotIn("definitions", off)


class P3LinesTest(unittest.TestCase):

    @staticmethod
    def contract(terms, judged, shown, terms_shown, **references):
        counts = {"resolved": 0, "broken": 0, "ambiguous": 0, "external": 0}
        counts.update(references)
        return {"file": "x.docx",
                "definitions": {"terms": terms, "judgedClauses": judged,
                                "clausesShown": shown, "termsShown": terms_shown},
                "references": counts}

    def test_reports_definitions_and_references_across_contracts(self):
        results = [{"contracts": [self.contract(4, 2, 1, 3, resolved=3, broken=1, external=2),
                                  self.contract(2, 1, 0, 0, resolved=1)]}]
        text = "\n".join(run_eval.p3_lines(results))
        self.assertIn("## Structure (P3)", text)
        self.assertIn("- Definitions: 6 extracted (3.0/contract); judged clauses shown definitions "
                      "1/3 (33%); definitions shown 3", text)
        self.assertIn("- References: resolved 4, broken 1, ambiguous 0, external 2", text)

    def test_a_run_with_neither_definitions_nor_references_adds_nothing(self):
        empty = self.contract(0, 2, 0, 0)
        self.assertEqual(run_eval.p3_lines([{"contracts": [empty]}]), [])
        self.assertEqual(run_eval.p3_lines([{"contracts": [{}]}]), [])


if __name__ == "__main__":
    unittest.main()
