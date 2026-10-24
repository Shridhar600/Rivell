"""Unit tests for the T (fit) scorer. Synthetic pairs, labels and verdicts only.

Run: cd scripts/eval && python3 -m unittest test_fidelity_t.py
"""
import json
import pathlib
import shutil
import tempfile
import unittest

import fidelity_t

SCRATCH = (pathlib.Path(__file__).resolve().parents[2] / ".superpowers" / "sdd"
           / "r4-part2-scorers" / "tmp")
SCRATCH.mkdir(parents=True, exist_ok=True)
TMP = tempfile.TemporaryDirectory(dir=SCRATCH)


def search(pair_id, governs, addressed):
    return ({"id": pair_id, "contract": "c1", "clauseText": "text"},
            {"id": pair_id, "governs": governs},
            {"id": pair_id, "judgedAddressing": addressed})


def drafting(pair_id, meets, cures, label="approved library wording", **extra):
    verdict = {"id": pair_id, "rivellLabel": label}
    verdict.update(extra)
    return ({"id": pair_id, "contract": "c1", "offeredWording": "w"},
            {"id": pair_id, "sameSubject": "YES", "meetsPosition": meets, "curesClause": cures,
             "usable": "YES"},
            verdict)


class BinaryTest(unittest.TestCase):

    def test_precision_recall_accuracy_and_base_rate(self):
        metrics = fidelity_t.binary_metrics([
            (True, True), (False, True), (True, False), (False, False)])
        self.assertEqual((metrics["tp"], metrics["fp"], metrics["fn"], metrics["tn"]), (1, 1, 1, 1))
        self.assertEqual(metrics["precision"], 0.5)
        self.assertEqual(metrics["recall"], 0.5)
        self.assertEqual(metrics["accuracy"], 0.5)
        self.assertEqual(metrics["baseRate"], 0.5)

    def test_no_prediction_is_not_a_zero(self):
        metrics = fidelity_t.binary_metrics([(False, True), (False, False)])
        self.assertIsNone(metrics["precision"])
        self.assertEqual(metrics["recall"], 0.0)
        self.assertEqual(metrics["baseRate"], 0.5)


class SearchTest(unittest.TestCase):

    def score(self, rows):
        pairs = [{"id": row[0]["id"], "contract": "c1"} for row in rows]
        labels = {row[1]["id"]: row[1] for row in rows}
        verdicts = {row[2]["id"]: row[2] for row in rows}
        return fidelity_t.score_search(pairs, labels, verdicts)

    def test_with_no_fit_check_every_matched_pair_is_positive_and_precision_is_the_base_rate(self):
        rows = [search("p1", "YES", True), search("p2", "NO", False),
                search("p3", "PARTLY", False)]
        pairs = [{"id": row[0]["id"], "contract": "c1"} for row in rows]
        labels = {row[1]["id"]: row[1] for row in rows}
        scored = fidelity_t.score_search_matched(pairs, labels)
        self.assertEqual(scored["predictedPositive"], 3)
        self.assertEqual(scored["precision"], scored["baseRate"])
        self.assertEqual(scored["baseRate"], 2 / 3)
        self.assertEqual(scored["recall"], 1.0)

    def test_governs_yes_and_partly_are_positive(self):
        scored = self.score([
            search("p1", "YES", True), search("p2", "YES", False),
            search("p3", "NO", True), search("p4", "NO", False),
            search("p5", "PARTLY", True)])
        self.assertEqual(scored["scored"], 5)
        self.assertEqual((scored["tp"], scored["fp"], scored["fn"], scored["tn"]), (2, 1, 1, 1))
        self.assertEqual(scored["precision"], 2 / 3)
        self.assertEqual(scored["recall"], 2 / 3)
        self.assertEqual(scored["baseRate"], 3 / 5)
        self.assertEqual(scored["labels"], {"YES": 2, "NO": 2, "PARTLY": 1})

    def test_the_strict_yes_only_read_is_reported_beside_the_primary_one(self):
        scored = self.score([search("p1", "YES", True), search("p2", "PARTLY", True),
                             search("p3", "NO", True), search("p4", "NO", False)])
        self.assertEqual((scored["tp"], scored["fp"]), (2, 1))
        self.assertEqual((scored["strict"]["tp"], scored["strict"]["fp"]), (1, 1))
        self.assertEqual(scored["strict"]["scored"], 3)
        self.assertEqual(scored["strict"]["baseRate"], 1 / 3)

    def test_a_pair_with_no_search_label_is_listed(self):
        pairs = [{"id": "p1", "contract": "c1"}]
        scored = fidelity_t.score_search(pairs, {}, {})
        self.assertEqual(scored["unscored"], [{"id": "p1", "reason": "no search label"}])

    def test_a_pair_without_a_verdict_is_listed_not_scored(self):
        pairs = [{"id": "p1", "contract": "c1"}, {"id": "p2", "contract": "c1"}]
        labels = {"p1": {"governs": "YES"}, "p2": {"governs": "NO"}}
        scored = fidelity_t.score_search(pairs, labels, {"p1": {"judgedAddressing": True}})
        self.assertEqual(scored["scored"], 1)
        self.assertEqual(scored["unscored"], [{"id": "p2", "reason": "no Rivell verdict"}])

    def test_a_label_without_a_pair_is_listed(self):
        scored = fidelity_t.score_search(
            [{"id": "p1", "contract": "c1"}], {"p1": {"governs": "YES"}, "p9": {"governs": "NO"}},
            {"p1": {"judgedAddressing": True}})
        self.assertIn({"id": "p9", "reason": "no pair"}, scored["labelsWithoutPairs"])

    def test_all_positive_predictions_are_flagged(self):
        scored = self.score([search("p1", "YES", True), search("p2", "NO", True)])
        self.assertTrue(scored["allPositive"])
        self.assertEqual(scored["precision"], scored["baseRate"])


class DraftingTest(unittest.TestCase):

    def score(self, rows, key="meetsPosition"):
        pairs = [{"id": row[0]["id"], "contract": "c1"} for row in rows]
        labels = {row[1]["id"]: row[1] for row in rows}
        verdicts = {row[2]["id"]: row[2] for row in rows}
        return fidelity_t.score_drafting(pairs, labels, verdicts, key)

    def test_a_no_position_pair_is_excluded_and_named(self):
        scored = self.score([drafting("d1", "YES", "YES"), drafting("d2", "NO", "NO"),
                             drafting("d3", "NO_POSITION", "NO")])
        self.assertEqual(scored["scored"], 2)
        self.assertEqual(scored["baseRate"], 0.5)
        self.assertEqual(scored["excluded"], [{"id": "d3", "reason": "NO_POSITION"}])

    def test_todays_approved_library_verdict_predicts_accepted_for_every_pair(self):
        scored = self.score([drafting("d1", "YES", "YES"), drafting("d2", "NO", "NO")])
        # Rivell has no fit check: it accepts the wording, so precision equals the base rate
        self.assertTrue(scored["allPositive"])
        self.assertEqual((scored["precision"], scored["recall"]), (0.5, 1.0))
        self.assertEqual(scored["baseRate"], 0.5)

    def test_an_unknown_verdict_is_listed_not_guessed(self):
        scored = self.score([drafting("d1", "YES", "YES", label="something new"),
                             drafting("d2", "NO", "NO")])
        self.assertEqual(scored["scored"], 1)
        self.assertEqual(scored["unscored"],
                         [{"id": "d1", "reason": "unrecognised Rivell verdict 'something new'"}])

    def test_an_explicit_future_verdict_field_is_used(self):
        scored = self.score([drafting("d1", "YES", "YES", meetsPosition="NO")])
        self.assertEqual(scored["predictedPositive"], 0)
        self.assertEqual(scored["tp"], 0)
        self.assertEqual(scored["fn"], 1)

    def test_cures_clause_is_scored_separately(self):
        scored = self.score([drafting("d1", "YES", "NO"), drafting("d2", "NO", "YES")],
                            key="curesClause")
        self.assertEqual(scored["scored"], 2)
        self.assertEqual(scored["baseRate"], 0.5)
        self.assertEqual(scored["tp"], 1)
        self.assertIn("curesClause", scored["key"])


class ScoreAndReportTest(unittest.TestCase):

    def inputs(self):
        pairs = {"search": [{"id": "p1", "contract": "c1"}],
                 "drafting": [{"id": "d1", "contract": "c1"}]}
        labels = {"search": [{"id": "p1", "governs": "YES"}],
                  "drafting": [{"id": "d1", "meetsPosition": "YES", "curesClause": "NO"}]}
        verdicts = {"p1": {"judgedAddressing": True},
                    "d1": {"rivellLabel": "approved library wording"}}
        return pairs, labels, verdicts

    def test_score_keeps_coverage_and_every_unscored_item(self):
        scored = fidelity_t.score(*self.inputs())
        self.assertEqual(scored["search"]["matched"]["scored"], 1)
        self.assertEqual(scored["search"]["judgedAddressing"]["scored"], 1)
        self.assertEqual(scored["drafting"]["meetsPosition"]["scored"], 1)
        self.assertEqual(scored["verdictsWithoutPairs"], [])
        self.assertEqual(scored["pairsWithoutLabels"], [])

    def test_a_pair_filed_under_the_other_kind_is_named_not_dropped(self):
        pairs, labels, verdicts = self.inputs()
        pairs["search"].append({"id": "d1", "contract": "c1"})
        scored = fidelity_t.score(pairs, labels, verdicts)
        self.assertIn({"id": "d1", "reason": "no search label"}, scored["pairsWithoutLabels"])

    def test_duplicate_pair_ids_are_refused(self):
        pairs, labels, verdicts = self.inputs()
        pairs["search"].append(dict(pairs["search"][0]))
        with self.assertRaises(ValueError):
            fidelity_t.score(pairs, labels, verdicts)

    def test_verdicts_outside_the_label_set_are_counted_not_scored(self):
        pairs, labels, verdicts = self.inputs()
        verdicts["extra"] = {"judgedAddressing": True}
        scored = fidelity_t.score(pairs, labels, verdicts)
        self.assertEqual(scored["verdictsWithoutPairs"], ["extra"])

    def test_duplicate_label_ids_are_refused_not_scored_twice(self):
        pairs, labels, verdicts = self.inputs()
        labels["search"].append(dict(labels["search"][0]))
        with self.assertRaises(ValueError):
            fidelity_t.score(pairs, labels, verdicts)

    def test_the_report_names_every_metric_and_prints_the_path(self):
        out = pathlib.Path(TMP.name, "t-report")
        if out.exists():
            shutil.rmtree(out)
        scored = fidelity_t.score(*self.inputs())
        path = fidelity_t.write_outputs(out, scored, {"set": "tune", "pairs": "p",
                                                       "labels": "l", "verdicts": "v",
                                                       "generated": "now"})
        text = (out / "summary.md").read_text()
        self.assertEqual(path, out / "summary.md")
        for marker in ("# Fit (T)", "set `tune`", "Precision", "Recall", "Base rate",
                       "## Unscored", "governs", "no fit check", "judgedAddressing"):
            self.assertIn(marker, text)
        written = json.loads((out / "results.json").read_text())
        self.assertEqual(written["meta"]["set"], "tune")
        self.assertEqual(written["results"]["search"]["matched"]["scored"], 1)


if __name__ == "__main__":
    unittest.main()
