"""Unit tests for DW-069: missing provisions are credited by position label or reference,
whole-word only. Run: cd scripts/eval && python3 -m unittest test_scoring_absence.py"""
import unittest

import p4_metrics
import scoring


def absence(label, concept=None, reference=None):
    finding = {"observationKind": "MISSING_POSITION", "subjectKind": "ABSENCE",
               "absenceConcept": concept, "positionLabel": label, "severity": "MEDIUM",
               "quality": {"positionLabel": label, "presenceProbability": 0.8}}
    if reference is not None:
        finding["positionReference"] = reference
    return finding


def issue(reference, category="MISSING_PROVISION"):
    return {"id": "I1", "category": category, "severity": "HIGH", "sectionIds": [],
            "playbookRef": reference}


def answer(reference):
    return {"sections": [], "expectedIssues": [issue(reference)], "compliantSectionIds": []}


class WholeWordAbsenceMatchTest(unittest.TestCase):

    def test_a_label_equal_to_the_reference_is_credited_even_when_the_concept_is_odd(self):
        # DW-069: novacloud G-03 Data protection came back as INTERNATIONAL_TRANSFERS
        finding = absence("G-03 Data protection", concept="INTERNATIONAL_TRANSFERS")
        self.assertTrue(scoring.absence_matches(finding, issue("G-03 Data protection")))

    def test_stem_overlap_no_longer_credits_a_different_position(self):
        # DW-069: "Assistance with rights requests" used to match "Governing law and regulatory
        # carve-out" through the shared stem "regulat"
        finding = absence("Assistance with rights requests", concept="RIGHTS_REQUESTS")
        self.assertFalse(scoring.absence_matches(
            finding, issue("G-02 Governing law and regulatory carve-out")))

    def test_a_bare_position_code_is_a_reference_of_its_own(self):
        finding = absence("H-08", concept="PUBLICITY")
        self.assertTrue(scoring.absence_matches(finding, issue("H-08 Publicity and case studies")))

    def test_a_different_position_code_is_never_text_matched(self):
        finding = absence("F-03 Payment terms", concept="PAYMENT_TERMS")
        self.assertFalse(scoring.absence_matches(finding, issue("F-01 Limitation of liability")))

    def test_a_label_that_misses_one_reference_word_is_not_credited(self):
        finding = absence("Governing law", concept="GOVERNING_LAW")
        self.assertFalse(scoring.absence_matches(
            finding, issue("G-02 Governing law and regulatory carve-out")))

    def test_the_concept_is_a_whole_word_fallback_when_no_label_is_carried(self):
        finding = absence(None, concept="GOVERNING_LAW")
        self.assertTrue(scoring.absence_matches(finding, issue("P-11 Governing law")))
        self.assertFalse(scoring.absence_matches(finding, issue("P-12 Governing law carve-out")))

    def test_a_carried_reference_is_used_when_the_label_is_missing(self):
        finding = absence(None, concept="X", reference="G-03 Data protection")
        self.assertTrue(scoring.absence_matches(finding, issue("G-03 Data protection")))

    def test_a_finding_with_no_label_reference_or_concept_is_not_credited(self):
        self.assertFalse(scoring.absence_matches({}, issue("G-03 Data protection")))


class ScoreContractAbsenceTest(unittest.TestCase):

    def test_score_contract_credits_the_label_whole_word_match(self):
        scored = scoring.score_contract(
            answer("G-03 Data protection"), [],
            [], [absence("G-03 Data protection", concept="INTERNATIONAL_TRANSFERS")])
        self.assertTrue(scored["issues"][0]["detected"])

    def test_score_contract_refuses_the_stem_false_positive(self):
        scored = scoring.score_contract(
            answer("G-02 Governing law and regulatory carve-out"), [],
            [], [absence("Assistance with rights requests", concept="RIGHTS_REQUESTS")])
        self.assertFalse(scored["issues"][0]["detected"])


class OneAbsenceOneIssueTest(unittest.TestCase):

    def test_one_absence_finding_credits_only_the_most_specific_issue(self):
        one = {"id": "I1", "category": "MISSING_PROVISION", "severity": "HIGH",
               "sectionIds": [], "playbookRef": "G-03 Data protection"}
        two = {"id": "I2", "category": "MISSING_PROVISION", "severity": "HIGH",
               "sectionIds": [], "playbookRef": "G-03 Data protection and privacy"}
        finding = absence("G-03 Data protection and privacy", concept="DATA_PROTECTION")
        scored = scoring.score_contract(
            {"sections": [], "expectedIssues": [one, two], "compliantSectionIds": []},
            [], [], [finding])
        self.assertFalse(scored["issues"][0]["detected"])
        self.assertTrue(scored["issues"][1]["detected"])

    def test_two_absence_findings_can_credit_two_issues(self):
        one = {"id": "I1", "category": "MISSING_PROVISION", "severity": "HIGH",
               "sectionIds": [], "playbookRef": "G-03 Data protection"}
        two = {"id": "I2", "category": "MISSING_PROVISION", "severity": "HIGH",
               "sectionIds": [], "playbookRef": "G-04 Governing law"}
        scored = scoring.score_contract(
            {"sections": [], "expectedIssues": [one, two], "compliantSectionIds": []},
            [], [], [absence("G-03 Data protection", concept="X"),
                     absence("G-04 Governing law", concept="X")])
        self.assertEqual([row["detected"] for row in scored["issues"]], [True, True])


class NamedAsMissingTest(unittest.TestCase):

    def test_a_finding_in_the_section_detects_but_does_not_name_the_missing_provision(self):
        planted = {"id": "I4", "category": "MISSING_PROVISION", "severity": "MEDIUM",
                   "sectionIds": ["S12"], "playbookRef": "F-11 Termination for convenience"}
        nodes = [{"id": "n12", "nodeType": "HEADING", "text": "12. Term and Termination"}]
        on_section = {"subjectHeadingNodeId": "n12", "severity": "LOW"}
        scored = scoring.score_contract(
            {"sections": [{"id": "S12", "heading": "12. Term and Termination"}],
             "expectedIssues": [planted], "compliantSectionIds": []},
            nodes, [], [on_section])
        self.assertTrue(scored["issues"][0]["detected"])
        self.assertFalse(scored["issues"][0]["namedAsMissing"])
        self.assertEqual(scored["missingNamed"], 0)

    def test_an_absence_finding_naming_the_position_names_it(self):
        scored = scoring.score_contract(
            answer("F-11 Termination for convenience"), [], [],
            [absence("F-11 Termination for convenience", concept="TERMINATION_FOR_CONVENIENCE")])
        self.assertTrue(scored["issues"][0]["namedAsMissing"])
        self.assertEqual(scored["missingNamed"], 1)


class P4AgreesWithScoringTest(unittest.TestCase):

    def test_omission_metrics_use_the_same_whole_word_rule(self):
        matched = p4_metrics.omission_metrics(
            answer("G-03 Data protection"), [absence("G-03 Data protection",
                                                     concept="INTERNATIONAL_TRANSFERS")])
        alarm = p4_metrics.omission_metrics(
            answer("G-02 Governing law and regulatory carve-out"),
            [absence("Assistance with rights requests", concept="RIGHTS_REQUESTS")])
        self.assertTrue(matched["list"][0]["matched"])
        self.assertEqual(matched["alarms"], 0)
        self.assertEqual(alarm["alarms"], 1)
        # the same finding scored by score_contract and by the omission metrics agrees
        scored = scoring.score_contract(
            answer("G-03 Data protection"), [],
            [], [absence("G-03 Data protection", concept="INTERNATIONAL_TRANSFERS")])
        self.assertTrue(scored["issues"][0]["detected"])


if __name__ == "__main__":
    unittest.main()
