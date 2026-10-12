"""Unit tests for the engine v2 lines of the eval report. Run: cd scripts/eval && python3 -m unittest test_run_eval_report.py"""
import unittest

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


if __name__ == "__main__":
    unittest.main()
