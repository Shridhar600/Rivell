"""Unit tests for the engine v2 P4 eval metrics. Run: cd scripts/eval && python3 -m unittest test_p4_metrics.py"""
import unittest

import p4_metrics
import run_eval


def check(subject="n1", route=None, addressed=None, search_found=None, search_failed=None,
          uncovered=None, spotted=None, positions=(), omission_candidates=None,
          searched=None, presence=None, addressed_by=None, flagged_by=None):
    return {
        "subjectHeadingNodeId": subject,
        "route": route,
        "addressed": addressed,
        "searchFound": search_found,
        "searchFailed": search_failed,
        "uncoveredProbability": uncovered,
        "spottedSeverity": spotted,
        "judgedPositions": list(positions),
        "omissionCandidates": omission_candidates,
        "searchedClauses": searched,
        "presenceProbabilities": presence,
        "addressedBySearch": addressed_by,
        "flaggedBySearch": flagged_by,
    }


def position(key, category=None, flag=0.0, meets=0.0, candidate="c1"):
    return {"positionKey": key, "positionCandidateId": candidate, "category": category,
            "flagProbability": flag, "probabilities": {"MEETS_PREFERRED": meets}}


def finding(kind, decided=None, node=None, severity=None, quality=None, concept=None):
    return {"observationKind": kind, "decidedBy": decided, "subjectHeadingNodeId": node,
            "severity": severity, "quality": quality or {}, "absenceConcept": concept,
            "observationText": "Missing: " + ((quality or {}).get("positionLabel") or "") + ", searched."}


ANSWER = {
    "sections": [{"id": "s1", "heading": "Governing Law"},
                 {"id": "s2", "heading": "Limitation of Liability"},
                 {"id": "s3", "heading": "Survival"}],
    "expectedIssues": [
        {"id": "i1", "category": "UNADDRESSED_BY_PLAYBOOK", "severity": "MEDIUM",
         "sectionIds": ["s1"], "playbookRef": "P-1 Governing law"},
        {"id": "i2", "category": "MISSING_PROVISION", "severity": "MEDIUM",
         "sectionIds": ["s3"], "playbookRef": "P-11 Governing law"},
    ],
    "compliantSectionIds": ["s2", "s3"],
}

NODES = [
    {"id": "n1", "nodeType": "HEADING", "text": "Governing Law"},
    {"id": "n2", "nodeType": "HEADING", "text": "Limitation of Liability"},
]


class SearchMetricsTest(unittest.TestCase):

    def test_counts_routes_found_addressing_and_top1(self):
        checks = [
            check(subject="n1", route="SEARCH", addressed=True, search_found=2,
                  positions=[position("search_position_1", "MEETS_PREFERRED", meets=0.9),
                             position("search_position_2", "WALK_AWAY", flag=0.2, meets=0.1)]),
            check(subject="n2", route="SEARCH", addressed=True, search_found=2,
                  positions=[position("position_1", "MEETS_PREFERRED", meets=0.9),
                             position("search_position_1", "NOT_ADDRESSED", flag=0.1, meets=0.1),
                             position("search_position_2", "MEETS_PREFERRED", meets=0.9)]),
            check(subject="n2", addressed=False, search_found=0,
                  positions=[position("search_position_1", "NOT_ADDRESSED", meets=0.2, flag=0.1)]),
        ]
        metrics = p4_metrics.search_metrics(checks)
        self.assertEqual(metrics["route"], 2)
        self.assertEqual(metrics["found"], 4)
        self.assertEqual(metrics["addressing"], 2)
        # the second check's first search position is not addressing while a later one is:
        # top-1 must not count any addressing search position
        self.assertEqual(metrics["top1Confirmed"], 1)
        self.assertEqual(metrics["top1Checks"], 2)
        self.assertEqual(metrics["addressedOnlyBySearchMeets"], 1)

    def test_a_search_meets_has_no_category_as_the_backend_sends_it(self):
        # the judge sets a category only when a position is flagged; a meets reads as null
        meets = position("search_position_1", None, meets=0.9)
        meets["flagged"] = False
        flagged = position("search_position_1", "WALK_AWAY", flag=0.8, meets=0.1)
        flagged["flagged"] = True
        checks = [check(subject="n1", route="SEARCH", addressed=True, search_found=1,
                        positions=[meets]),
                  check(subject="n2", route="SEARCH", addressed=True, search_found=1,
                        positions=[flagged])]
        self.assertEqual(p4_metrics.search_metrics(checks)["addressedOnlyBySearchMeets"], 1)

    def test_the_addressing_line_is_inclusive(self):
        exactly_half = [check(route="SEARCH", addressed=True, search_found=1,
                              positions=[position("search_position_1", "NOT_ADDRESSED",
                                                  flag=0.4, meets=0.1)])]
        self.assertEqual(p4_metrics.search_metrics(exactly_half)["addressing"], 1)

    def test_a_v1_shape_has_no_search(self):
        metrics = p4_metrics.search_metrics([check(addressed=True, positions=[])])
        self.assertEqual(metrics, {"route": 0, "found": 0, "addressing": 0,
                                   "top1Confirmed": 0, "top1Checks": 0,
                                   "addressedOnlyBySearchMeets": 0})


class AdvisoryMetricsTest(unittest.TestCase):

    def test_picks_high_hits_and_v1_exclusion(self):
        findings = [
            finding("SPOTTED_IN_WHOLE_CONTRACT_READ", "NEEDS_YOUR_LOOK", "n1",
                    quality={"spottedSeverity": "HIGH"}),
            finding("SPOTTED_IN_WHOLE_CONTRACT_READ", "NEEDS_YOUR_LOOK", "n2",
                    quality={"spottedSeverity": "MEDIUM"}),
            finding("SPOTTED_IN_WHOLE_CONTRACT_READ", "NONE", "n1",
                    quality={"spottedSeverity": "HIGH"}),
            finding("POSITION_WALK_AWAY", "JEV", "n2"),
        ]
        metrics = p4_metrics.advisory_metrics(ANSWER, NODES, [], findings)
        self.assertEqual(metrics["picks"], 2)
        self.assertEqual(metrics["high"], 1)
        self.assertEqual(metrics["hits"], 1)
        self.assertEqual(metrics["highHits"], 1)


class OmissionMetricsTest(unittest.TestCase):

    def test_matches_by_topic_and_by_label_words(self):
        findings = [
            # matches by concept topic only: its label shares no words with the playbookRef
            finding("MISSING_POSITION", severity="MEDIUM", concept="GOVERNING_LAW",
                    quality={"positionLabel": "Choice of forum", "presenceProbability": 0.92}),
            finding("MISSING_POSITION", severity="MEDIUM", concept="CAP_EXCEPTIONS",
                    quality={"positionLabel": "Cap Exceptions", "presenceProbability": 0.8}),
            finding("MISSING_POSITION", severity="LOW", concept="UNRELATED_CONCEPT",
                    quality={"positionLabel": "Governing law preference",
                             "presenceProbability": 0.6}),
        ]
        metrics = p4_metrics.omission_metrics(ANSWER, findings)
        self.assertEqual(metrics["medium"], 2)
        self.assertEqual(metrics["low"], 1)
        self.assertEqual(metrics["alarms"], 1)
        self.assertEqual([entry["label"] for entry in metrics["list"]],
                         ["Choice of forum", "Cap Exceptions", "Governing law preference"])
        self.assertEqual([entry["matched"] for entry in metrics["list"]], [True, False, True])
        self.assertEqual(metrics["list"][0]["presence"], 0.92)

    def test_another_observation_kind_is_not_an_omission(self):
        metrics = p4_metrics.omission_metrics(ANSWER, [finding("MISSING_REQUIRED_CONCEPT")])
        self.assertEqual(metrics["medium"], 0)
        self.assertEqual(metrics["list"], [])


class LookReasonsTest(unittest.TestCase):

    def test_counts_every_reason_and_other(self):
        findings = [
            finding("UNCERTAINTY:second_opinion_disagrees", "NEEDS_YOUR_LOOK"),
            finding("UNCERTAINTY:second_opinion_disagrees", "NEEDS_YOUR_LOOK"),
            finding("UNCERTAINTY:second_opinion_unavailable", "NEEDS_YOUR_LOOK"),
            finding("POLICY_UNAVAILABLE", "NEEDS_YOUR_LOOK", quality={"uncertaintyReason": "playbook_silent"}),
            finding("MISSING_POSITION", "NEEDS_YOUR_LOOK", quality={"uncertaintyReason": "omission_unsure"}),
            finding("POLICY_UNAVAILABLE", "NEEDS_YOUR_LOOK"),
            finding("POLICY_UNAVAILABLE", "JEV", quality={"uncertaintyReason": "playbook_silent"}),
        ]
        reasons = p4_metrics.look_reasons(findings)
        self.assertEqual(reasons, {"second_opinion_disagrees": 2, "second_opinion_unavailable": 1,
                                   "playbook_silent": 1, "omission_unsure": 1, "other": 1})


class TriageVsReadTest(unittest.TestCase):

    def test_two_by_two_over_unaddressed_and_compliant_sections(self):
        checks = [
            check(subject="n1", uncovered=0.7, spotted="HIGH"),
            check(subject="n2", uncovered=0.2, spotted=None),
        ]
        metrics = p4_metrics.triage_vs_read(ANSWER, NODES, [], checks)
        self.assertEqual(metrics["unaddressed"],
                         {"flaggedPicked": 1, "flaggedUnpicked": 0,
                          "clearPicked": 0, "clearUnpicked": 0})
        # s3 has no contract heading, so no check: neither flagged nor picked
        self.assertEqual(metrics["compliant"],
                         {"flaggedPicked": 0, "flaggedUnpicked": 0,
                          "clearPicked": 0, "clearUnpicked": 2})

    def test_a_flagged_check_the_read_left_alone_is_one_cell(self):
        checks = [check(subject="n1", uncovered=0.5, spotted=None)]
        metrics = p4_metrics.triage_vs_read(ANSWER, NODES, [], checks)
        self.assertEqual(metrics["unaddressed"],
                         {"flaggedPicked": 0, "flaggedUnpicked": 1,
                          "clearPicked": 0, "clearUnpicked": 0})

    def test_a_section_listed_twice_is_counted_once(self):
        answer = {"sections": [{"id": "s1", "heading": "Governing Law"}],
                  "expectedIssues": [], "compliantSectionIds": ["s1", "s1"]}
        nodes = [{"id": "n1", "nodeType": "HEADING", "text": "Governing Law"}]
        metrics = p4_metrics.triage_vs_read(answer, nodes, [], [])
        self.assertEqual(metrics["compliant"],
                         {"flaggedPicked": 0, "flaggedUnpicked": 0,
                          "clearPicked": 0, "clearUnpicked": 1})


class LeverTest(unittest.TestCase):

    def test_drop_predicates(self):
        search = finding("POSITION_WALK_AWAY", quality={"foundBy": "SEARCH"})
        advisory = finding("SPOTTED_IN_WHOLE_CONTRACT_READ", "NEEDS_YOUR_LOOK")
        omission = finding("MISSING_POSITION", "NEEDS_YOUR_LOOK",
                           quality={"uncertaintyReason": "omission_unsure"})
        kept = finding("POSITION_WALK_AWAY", "JEV")
        self.assertTrue(p4_metrics.drop_search_found(search))
        self.assertFalse(p4_metrics.drop_search_found(kept))
        self.assertTrue(p4_metrics.drop_advisory(advisory))
        self.assertFalse(p4_metrics.drop_advisory(omission))
        self.assertFalse(p4_metrics.drop_advisory(kept))
        self.assertTrue(p4_metrics.drop_omissions(omission))
        self.assertFalse(p4_metrics.drop_omissions(search))

    def test_lever_score_filters_and_keeps_the_without_spotted_shape(self):
        seen = {}

        def fake_score(answer, nodes, clauses, findings):
            seen["findings"] = findings
            return {"issues": [], "issuesDetected": 3, "falseAlarmSections": 1,
                    "seriousFalseAlarmSections": 0, "findings": len(findings), "extra": 9}

        search = finding("POSITION_WALK_AWAY", quality={"foundBy": "SEARCH"})
        kept = finding("POSITION_WALK_AWAY", "JEV")
        out = p4_metrics.lever_score(fake_score, ANSWER, NODES, [], [search, kept],
                                     p4_metrics.drop_search_found)
        self.assertEqual(seen["findings"], [kept])
        self.assertEqual(set(out), {"issues", "issuesDetected", "falseAlarmSections",
                                    "seriousFalseAlarmSections", "findings"})


class P4LinesTest(unittest.TestCase):

    def v2_contract(self):
        return {
            "file": "msa.docx", "issuesDetected": 5, "falseAlarmSections": 2,
            "search": {"route": 2, "found": 3, "addressing": 2, "top1Confirmed": 1,
                       "top1Checks": 2, "addressedOnlyBySearchMeets": 1},
            "advisory": {"picks": 3, "high": 1, "hits": 2, "highHits": 1},
            "omissions": {"medium": 1, "low": 1, "alarms": 0, "list": [
                {"label": "Cap Exceptions", "severity": "MEDIUM", "presence": 0.92,
                 "matched": True},
                {"label": "Governance", "severity": "LOW", "presence": 0.6, "matched": False}]},
            "lookReasons": {"second_opinion_disagrees": 1, "second_opinion_unavailable": 0,
                            "playbook_silent": 2, "omission_unsure": 1, "other": 0},
            "triageVsRead": {
                "unaddressed": {"flaggedPicked": 1, "flaggedUnpicked": 0,
                                "clearPicked": 0, "clearUnpicked": 1},
                "compliant": {"flaggedPicked": 0, "flaggedUnpicked": 0,
                              "clearPicked": 1, "clearUnpicked": 2}},
            "withoutSearchFound": {"issuesDetected": 4, "falseAlarmSections": 1},
            "withoutAdvisory": {"issuesDetected": 4, "falseAlarmSections": 1},
            "withoutOmissions": {"issuesDetected": 4, "falseAlarmSections": 2},
        }

    def test_reports_totals_per_contract_and_every_omission(self):
        lines = run_eval.p4_lines([{"contracts": [self.v2_contract()]}])
        text = "\n".join(lines)
        self.assertIn("## Engine v2 P4", text)
        self.assertIn("Search: 2 routes (2.0/contract); 3 positions found; addressing 2/3 (66%)", text)
        self.assertIn("top-1 confirmed 1/2 (50%)", text)
        self.assertIn("Advisory picks: 3 (3.0/contract; as if all were HIGH: 1)", text)
        self.assertIn("Omissions: MEDIUM 1 (1.0/contract), LOW 1 (1.0/contract); "
                      "alarms (MEDIUM matching no MISSING_PROVISION issue) 0", text)
        self.assertIn("msa.docx: Cap Exceptions · MEDIUM · 0.92 · matched", text)
        self.assertIn("msa.docx: Governance · LOW · 0.6 · unmatched", text)
        self.assertIn("Look reasons: second_opinion_disagrees 1, second_opinion_unavailable 0, "
                      "playbook_silent 2, omission_unsure 1, other 0", text)
        self.assertIn("D12b unaddressed-by-playbook sections", text)
        self.assertIn("flagged+picked 1", text)
        self.assertIn("D12b compliant sections", text)
        self.assertIn("Without search-found: issues detected 5 -> 4; false-alarm sections 2 -> 1", text)
        self.assertIn("Without advisory picks: issues detected 5 -> 4; false-alarm sections 2 -> 1", text)
        self.assertIn("Without omissions: issues detected 5 -> 4; false-alarm sections 2 -> 2", text)

    def test_a_v1_run_adds_no_line(self):
        v1 = {"file": "old.docx", "issuesDetected": 3, "falseAlarmSections": 0,
              "findings": 4, "decidedBy": {"JEV": 4}}
        self.assertEqual(run_eval.p4_lines([{"contracts": [v1]}]), [])
        self.assertEqual(run_eval.p4_lines([{"contracts": [v1, {}]}]), [])


if __name__ == "__main__":
    unittest.main()
