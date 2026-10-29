import json
import pathlib
import shutil
import unittest
import uuid

import demo_config
import demo_driver

SCRATCH = (pathlib.Path(__file__).resolve().parents[2]
           / ".superpowers" / "sdd" / "demo-part2" / "tmp")


def scratch_dir() -> pathlib.Path:
    SCRATCH.mkdir(parents=True, exist_ok=True)
    directory = SCRATCH / f"test-driver-{uuid.uuid4().hex}"
    directory.mkdir()
    return directory


class StubApi:
    def __init__(self, responses):
        self.responses = responses

    def get(self, path):
        return self.responses[path]


class EmptyDatabaseTest(unittest.TestCase):

    def test_phase_a_refuses_a_database_that_already_has_rows(self):
        empty = {"/api/contracts?limit=1": {"items": []},
                 "/api/knowledge/artifacts?limit=1": [],
                 "/api/reviews?limit=1": {"items": []}}
        for path in empty:
            with self.subTest(path=path):
                responses = dict(empty)
                responses[path] = ([{"id": "x"}] if path == "/api/knowledge/artifacts?limit=1"
                                   else {"items": [{"id": "x"}]})
                with self.assertRaisesRegex(demo_driver.DemoError, "database is not empty"):
                    demo_driver.assert_empty_database(StubApi(responses))

    def test_phase_a_accepts_three_empty_lists(self):
        api = StubApi({"/api/contracts?limit=1": {"items": []},
                       "/api/knowledge/artifacts?limit=1": [],
                       "/api/reviews?limit=1": {"items": []}})
        demo_driver.assert_empty_database(api)


class TapeMissTest(unittest.TestCase):

    def test_a_recorded_miss_stops_the_run(self):
        for health in ({"misses": [{"path": "/v1/systemone"}]},
                       {"misses": [], "unrouted": [{"method": "POST",
                                                   "path": "/v1/systemone"}]}):
            with self.subTest(health=health):
                with self.assertRaisesRegex(demo_driver.DemoError, "replay miss.*systemone"):
                    demo_driver.require_no_misses(health)

    def test_no_misses_is_fine(self):
        demo_driver.require_no_misses({"misses": []})


CONFIG = pathlib.Path(__file__).resolve().parents[2] / "demo" / "config.json"
SECTIONS = {"h1": "10. Limitation of liability", "h2": "12. Indemnities"}
C1 = "contracts/Kestrel Assurance - Master Services Agreement (returned markup).docx"


def finding(subject, section_id, concept, family, **extra):
    return {"id": str(uuid.uuid4()), "subjectKind": subject,
            "subjectHeadingNodeId": section_id, "concept": concept,
            "absenceConcept": None, "issueFamily": family,
            "reviewClassification": "REVIEW_REQUIRED", **extra}


class DecisionKeyTest(unittest.TestCase):

    def test_a_clause_finding_is_keyed_by_its_heading_text(self):
        f = finding("CLAUSE", "h1", "LIMITATION_OF_LIABILITY", "WALK_AWAY_BREACH")
        self.assertEqual(demo_driver.decision_key("contracts/c.docx", f, SECTIONS),
                         ("CLAUSE", "10. Limitation of liability",
                          "LIMITATION_OF_LIABILITY", "WALK_AWAY_BREACH"))

    def test_an_absence_finding_is_keyed_by_its_absence_concept(self):
        f = finding("ABSENCE", None, None, "MISSING_PROVISION",
                    absenceConcept="DATA_EXPORT")
        self.assertEqual(demo_driver.decision_key("contracts/c.docx", f, SECTIONS),
                         ("ABSENCE", "", "DATA_EXPORT", "MISSING_PROVISION"))

    def test_a_key_that_matches_two_findings_is_refused(self):
        f = finding("CLAUSE", "h1", "LIMITATION_OF_LIABILITY", "WALK_AWAY_BREACH")
        with self.assertRaisesRegex(demo_driver.DemoError, "matches 2 findings"):
            demo_driver.resolve_finding(
                "contracts/c.docx", [f, dict(f, id=str(uuid.uuid4()))], SECTIONS,
                demo_driver.decision_key("contracts/c.docx", f, SECTIONS))

    def test_a_key_that_matches_nothing_lists_the_available_keys(self):
        with self.assertRaisesRegex(demo_driver.DemoError, "available keys"):
            demo_driver.resolve_finding("contracts/c.docx", [], SECTIONS,
                                        ("CLAUSE", "x", "y", "Z"))


class DecisionFileTest(unittest.TestCase):

    def test_an_edit_without_language_is_refused_before_any_write(self):
        decision = {"subject": "CLAUSE", "section": "10. Limitation of liability",
                    "concept": "LIMITATION_OF_LIABILITY", "issueFamily": "WALK_AWAY_BREACH",
                    "action": "EDIT", "approvedAction": "REPLACE"}
        with self.assertRaisesRegex(demo_config.ConfigError, "approvedLanguage"):
            demo_driver.validate_decisions(CONFIG, {"version": 1, "contracts": {
                C1: {"decisions": [decision]}}})

    def test_an_id_field_is_refused(self):
        decision = {"subject": "ABSENCE", "section": None, "concept": "DATA_EXPORT",
                    "issueFamily": "MISSING_PROVISION", "action": "ACCEPT",
                    "findingId": "00000000-0000-0000-0000-000000000000"}
        with self.assertRaisesRegex(demo_config.ConfigError, "findingId"):
            demo_driver.validate_decisions(CONFIG, {"version": 1, "contracts": {
                C1: {"decisions": [decision]}}})


class StageCoverageTest(unittest.TestCase):

    def test_decided_requires_every_actionable_finding_to_have_a_decision(self):
        f = finding("CLAUSE", "h1", "C", "WALK_AWAY_BREACH")
        with self.assertRaisesRegex(demo_driver.DemoError, "undecided.*10. Limitation"):
            demo_driver.assert_decisions_cover_stage("decided", [f], [], SECTIONS)

    def test_analyzed_ignores_the_decisions_file(self):
        f = finding("CLAUSE", "h1", "C", "WALK_AWAY_BREACH")
        demo_driver.assert_decisions_cover_stage("analyzed", [f], [], SECTIONS)

    def test_mid_review_requires_at_least_one_open_actionable_finding(self):
        f = finding("CLAUSE", "h1", "C", "WALK_AWAY_BREACH")
        decision = {"subject": "CLAUSE", "section": "10. Limitation of liability",
                    "concept": "C", "issueFamily": "WALK_AWAY_BREACH", "action": "REJECT",
                    "comment": "accepted as drafted"}
        with self.assertRaisesRegex(demo_driver.DemoError, "mid-review needs an open finding"):
            demo_driver.assert_decisions_cover_stage("mid-review", [f], [decision], SECTIONS)

    def test_informational_findings_are_not_required(self):
        f = finding("CLAUSE", "h1", "C", "WALK_AWAY_BREACH",
                    reviewClassification="INFORMATIONAL")
        demo_driver.assert_decisions_cover_stage("decided", [f], [], SECTIONS)

    def test_an_accept_of_an_escalation_is_refused_before_any_write(self):
        f = finding("CLAUSE", "h1", "C", "WALK_AWAY_BREACH",
                    proposedAction="ESCALATE_TO_HUMAN", proposedLanguageSource="NONE")
        decision = {"subject": "CLAUSE", "section": "10. Limitation of liability",
                    "concept": "C", "issueFamily": "WALK_AWAY_BREACH", "action": "ACCEPT"}
        with self.assertRaisesRegex(demo_driver.DemoError, "cannot ACCEPT"):
            demo_driver.assert_decisions_cover_stage("decided", [f], [decision], SECTIONS)


class K2ApprovalTest(unittest.TestCase):

    def test_only_pending_candidates_are_approved(self):
        candidates = [{"id": "a", "reviewStatus": "PENDING"},
                      {"id": "b", "reviewStatus": "PENDING"},
                      {"id": "c", "reviewStatus": "APPROVED"}]
        self.assertEqual(demo_driver.pending_k2_ids(candidates), ["a", "b"])

    def test_a_rejected_candidate_fails_the_stage(self):
        candidates = [{"id": "a", "reviewStatus": "PENDING"},
                      {"id": "b", "reviewStatus": "REJECTED"}]
        with self.assertRaisesRegex(demo_driver.DemoError, "REJECTED"):
            demo_driver.pending_k2_ids(candidates)


class RecordingApi:

    def __init__(self, response):
        self.response = response
        self.puts = []

    def put(self, path, body):
        self.puts.append((path, body))
        return self.response

    def get(self, path):
        return {"items": [], "truncated": False}


class ApplyDecisionTest(unittest.TestCase):

    def test_an_accept_keeps_the_server_derived_approved_action(self):
        f = finding("CLAUSE", "h1", "C", "WALK_AWAY_BREACH")
        decision = {"subject": "CLAUSE", "section": "10. Limitation of liability",
                    "concept": "C", "issueFamily": "WALK_AWAY_BREACH", "action": "ACCEPT"}
        api = RecordingApi({"action": "ACCEPT", "approvedAction": "REPLACE",
                            "approvedLanguage": "The cap is the fees paid."})
        demo_driver.apply_decisions(api, "review-1", [f], SECTIONS, [decision],
                                    "contracts/c.docx")
        self.assertEqual(api.puts, [
            (f"/api/reviews/review-1/findings/{f['id']}/decision",
             {"expectedVersion": 0, "action": "ACCEPT"})])


class CheckpointCaptureTest(unittest.TestCase):

    def test_a_checkpoint_saves_the_state_then_asks_the_sink_to_capture(self):
        directory = scratch_dir()
        try:
            state = demo_driver.empty_state()
            captured = []

            class Sink:
                def capture(self, current, name):
                    saved = json.loads((directory / "state.json").read_text())
                    captured.append((name, current, saved["checkpoints"]))

            demo_driver._checkpoint(state, directory, "tour-c5-decided", Sink())
            self.assertEqual(captured[0][0], "tour-c5-decided")
            self.assertEqual(captured[0][2], ["tour-c5-decided"])
        finally:
            shutil.rmtree(directory, ignore_errors=True)

    def test_a_checkpoint_without_a_sink_still_writes_the_state(self):
        directory = scratch_dir()
        try:
            state = demo_driver.empty_state()
            demo_driver._checkpoint(state, directory, "base")
            saved = json.loads((directory / "state.json").read_text())
            self.assertEqual(saved["checkpoints"], ["base"])
        finally:
            shutil.rmtree(directory, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
