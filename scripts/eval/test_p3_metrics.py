"""Unit tests for the engine v2 P3 eval metrics. Run: cd scripts/eval && python3 -m unittest test_p3_metrics.py"""
import unittest

import p3_metrics


def check(positions=(), definition_terms=None):
    return {"judgedPositions": list(positions), "definitionTerms": definition_terms}


class DefinitionMetricsTest(unittest.TestCase):

    def test_counts_extracted_terms_shown_definitions_and_judged_clauses(self):
        semantics = {"definedTerms": [{"term": "Confidential Information"},
                                      {"term": "Affiliate"},
                                      {"term": "Term"}]}
        checks = [
            check(positions=[{"positionKey": "p1"}],
                  definition_terms=["Confidential Information", "Affiliate"]),
            check(positions=[{"positionKey": "p2"}], definition_terms=["Term"]),
            check(positions=[{"positionKey": "p3"}]),
        ]
        self.assertEqual(p3_metrics.definition_metrics(semantics, checks),
                         {"terms": 3, "judgedClauses": 3, "clausesShown": 2, "termsShown": 3})

    def test_missing_keys_count_as_empty(self):
        self.assertEqual(p3_metrics.definition_metrics({}, [{}]),
                         {"terms": 0, "judgedClauses": 0, "clausesShown": 0, "termsShown": 0})
        self.assertEqual(p3_metrics.definition_metrics({}, []),
                         {"terms": 0, "judgedClauses": 0, "clausesShown": 0, "termsShown": 0})

    def test_a_check_without_positions_is_not_a_judged_clause(self):
        checks = [check(positions=[{"positionKey": "p1"}], definition_terms=["Term"]),
                  check(positions=[], definition_terms=["Term"])]
        metrics = p3_metrics.definition_metrics({"definedTerms": [{"term": "Term"}]}, checks)
        self.assertEqual(metrics["judgedClauses"], 1)
        self.assertEqual(metrics["clausesShown"], 1)
        self.assertEqual(metrics["termsShown"], 2)


class ReferenceMetricsTest(unittest.TestCase):

    def test_counts_every_status_and_ignores_anything_else(self):
        semantics = {"references": [{"status": "RESOLVED"}, {"status": "RESOLVED"},
                                    {"status": "BROKEN"}, {"status": "AMBIGUOUS"},
                                    {"status": "EXTERNAL"}, {"status": "EXTERNAL"},
                                    {"status": "PENDING"}, {}]}
        self.assertEqual(p3_metrics.reference_metrics(semantics),
                         {"resolved": 2, "broken": 1, "ambiguous": 1, "external": 2})

    def test_missing_keys_count_as_empty(self):
        self.assertEqual(p3_metrics.reference_metrics({}),
                         {"resolved": 0, "broken": 0, "ambiguous": 0, "external": 0})
        self.assertEqual(p3_metrics.reference_metrics({"references": []}),
                         {"resolved": 0, "broken": 0, "ambiguous": 0, "external": 0})


if __name__ == "__main__":
    unittest.main()
