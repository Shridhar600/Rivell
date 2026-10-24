"""Unit tests for the K (knowledge fidelity) scorer. Synthetic fixtures, plus one resolution
test against the real context repo (the user-test document exists only there).

Run: cd scripts/eval && python3 -m unittest test_fidelity_k.py
"""
import json
import pathlib
import shutil
import sys
import tempfile
import unittest
from unittest import mock

import fidelity_k

SCRATCH = (pathlib.Path(__file__).resolve().parents[2] / ".superpowers" / "sdd"
           / "r4-part2-scorers" / "tmp")
SCRATCH.mkdir(parents=True, exist_ok=True)
TMP = tempfile.TemporaryDirectory(dir=SCRATCH)

SOURCE = [
    "DOCUMENT TITLE",                                                   # 1
    "The cap is twelve (12) months of fees.",                           # 2
    "Cap fallback: AUD 1,000,000.",                                     # 3
    "Never accept unlimited liability.",                                # 4
    "Governing law is Delaware law.",                                   # 5
    "Approval: Commercial Legal Director for fallbacks.",               # 6
]


def stance(quote, *facts):
    return {"quote": quote, "keyFacts": list(facts)}


def label(entry_id, kind="POSITION", title="Liability", lines=(2, 4), stances=None,
          effective=None):
    return {
        "id": entry_id, "kind": kind, "title": title, "lines": list(lines),
        "anchor": SOURCE[lines[0] - 1][:40],
        "stances": stances if stances is not None else {
            "preferred": stance("The cap is twelve (12) months of fees.",
                                "twelve (12) months"),
            "fallback": stance("Cap fallback: AUD 1,000,000.", "AUD 1,000,000"),
            "walkAway": stance("Never accept unlimited liability.", "unlimited liability"),
        },
        "wording": None,
        "effective": effective if effective is not None else {
            "preferred": {"approval": "NONE", "approver": None},
            "fallback": {"approval": "REQUIRED", "approver": "Commercial Legal Director",
                         "authorityConflict": True,
                         "acceptableApprovers": ["Commercial Legal Director",
                                                 "General Counsel"]},
            "walkAway": {"action": "ESCALATE", "approver": "General Counsel"},
        },
    }


def candidate(text, title="Liability", cid="c1", candidate_type="PLAYBOOK_RULE",
              positions=("PREFERRED", "FALLBACK"), **extra):
    row = {"id": cid, "candidateType": candidate_type, "title": title, "rawLabel": title,
           "contentText": text, "positions": list(positions), "payloadColumns": {},
           "requiresApproval": None, "escalationTrigger": None}
    row.update(extra)
    return row


FULL = ("Applies to: MSA.\n"
        "Preferred position: The cap is twelve (12) months of fees.\n"
        "Fallback position: Cap fallback: AUD 1,000,000.\n"
        "Walk-away: Never accept unlimited liability.")


class AdapterTest(unittest.TestCase):

    def test_todays_playbook_rule_maps_to_a_position_with_parsed_stances(self):
        draft = fidelity_k.adapt_candidate(candidate(FULL))
        self.assertEqual(draft["kind"], "POSITION")
        self.assertTrue(draft["comparator"])
        self.assertIn("twelve (12) months", draft["stances"]["preferred"])
        self.assertIn("AUD 1,000,000", draft["stances"]["fallback"])
        self.assertIn("unlimited liability", draft["stances"]["walkAway"])
        # the product exposes no approver on a draft entry today
        self.assertEqual(draft["authority"], [])

    def test_a_clause_variant_with_commentary_is_wording_not_a_comparator(self):
        draft = fidelity_k.adapt_candidate(candidate(
            "The Supplier shall notify within 48 hours.", title="Breach · Preferred",
            candidate_type="CLAUSE_VARIANT", positions=["COMMENTARY"]))
        self.assertEqual(draft["kind"], "WORDING")
        self.assertFalse(draft["comparator"])

    def test_a_future_approver_field_maps_to_authority_pairs(self):
        draft = fidelity_k.adapt_candidate(candidate(
            FULL, authority=[{"approver": "General Counsel", "stances": ["FALLBACK"]}]))
        self.assertEqual(draft["authority"],
                         [{"approver": "General Counsel", "stances": ["FALLBACK"]}])

    def test_requires_approval_without_a_name_is_an_unnamed_authority(self):
        draft = fidelity_k.adapt_candidate(candidate(FULL, requiresApproval=True))
        self.assertEqual(draft["authority"], [{"approver": None, "stances": ["UNSPECIFIED"]}])

    def test_labelled_payload_columns_supply_the_stance_text(self):
        draft = fidelity_k.adapt_candidate(candidate(
            "row text", payloadColumns={"Wording": "The cap is twelve (12) months.",
                                        "Backstop": "AUD 1,000,000."},
            payloadFieldRoles={"Wording": "PREFERRED", "Backstop": "FALLBACK"}))
        self.assertEqual(draft["stances"]["preferred"], "The cap is twelve (12) months.")
        self.assertEqual(draft["stances"]["fallback"], "AUD 1,000,000.")

    def test_todays_escalation_trigger_is_the_walk_away_approver(self):
        draft = fidelity_k.adapt_candidate(candidate(FULL, escalationTrigger="General Counsel"))
        self.assertEqual(draft["authority"],
                         [{"approver": "General Counsel", "stances": ["WALK_AWAY"]}])


class MatchTest(unittest.TestCase):

    def test_a_label_matches_the_draft_that_carries_its_source_text(self):
        labels = {"source": "p.docx", "entries": [
            label("p-001"), label("p-002", title="Governing law", lines=(5, 5),
                                  stances={"preferred": stance("Governing law is Delaware law.",
                                                               "Delaware law")},
                                  effective={"preferred": {"approval": "NONE"}})]}
        drafts = [fidelity_k.adapt_candidate(candidate(FULL)),
                  fidelity_k.adapt_candidate(candidate("Governing law is Delaware law.",
                                                       title="Governing law", cid="c2"))]
        matched = fidelity_k.match_entries(labels["entries"], drafts, SOURCE)
        self.assertEqual(matched["matches"]["p-001"]["entryId"], "c1")
        self.assertEqual(matched["matches"]["p-002"]["entryId"], "c2")
        self.assertEqual(matched["unmatched"], [])

    def test_one_draft_entry_cannot_be_counted_for_two_labels(self):
        # both labels' words sit in one long entry; exactly one label may claim it
        labels = [label("p-001"), label("p-002", title="Liability fallback", lines=(3, 3),
                                        stances={"fallback": stance("Cap fallback: AUD 1,000,000.",
                                                                    "AUD 1,000,000")},
                                        effective={"fallback": {"approval": "NONE"}})]
        drafts = [fidelity_k.adapt_candidate(candidate(FULL))]
        matched = fidelity_k.match_entries(labels, drafts, SOURCE)
        self.assertEqual(len(matched["matches"]), 1)
        self.assertEqual(len(matched["unmatched"]), 1)

    def test_the_smallest_superset_wins_over_a_long_summary_that_contains_the_label(self):
        labels = [label("p-001")]
        long_summary = candidate("Summary of the whole playbook. " + FULL + " "
                                 "Additional commentary that belongs to no other rule.",
                                 title="Summary", cid="summary")
        exact = candidate(FULL, cid="exact")
        matched = fidelity_k.match_entries(labels, [fidelity_k.adapt_candidate(long_summary),
                                                    fidelity_k.adapt_candidate(exact)], SOURCE)
        self.assertEqual(matched["matches"]["p-001"]["entryId"], "exact")
        self.assertEqual([row["entryId"] for row in matched["unmatchedDrafts"]], ["summary"])

    def test_an_unmatched_label_inside_a_longer_draft_is_reported_as_merged_not_missing(self):
        # one draft holds two labels' text: one label matches it, the other is merged into it
        labels = [label("p-001"), label("p-002", title="Liability fallback", lines=(3, 3),
                                        stances={"fallback": stance("Cap fallback: AUD 1,000,000.",
                                                                    "AUD 1,000,000")},
                                        effective={"fallback": {"approval": "NONE"}})]
        drafts = [fidelity_k.adapt_candidate(candidate(FULL))]
        matched = fidelity_k.match_entries(labels, drafts, SOURCE)
        self.assertEqual(len(matched["unmatched"]), 1)
        self.assertEqual(matched["mergedInto"], {matched["unmatched"][0]: "c1"})

    def test_a_label_whose_text_no_draft_holds_is_not_merged(self):
        labels = [label("p-001", title="Liability")]
        drafts = [fidelity_k.adapt_candidate(candidate("A cap applies.", title="Liability"))]
        matched = fidelity_k.match_entries(labels, drafts, SOURCE)
        self.assertEqual(matched["mergedInto"], {})

    def test_a_title_alone_does_not_match_a_label_whose_source_text_is_missing(self):
        labels = [label("p-001", title="Liability")]
        drafts = [fidelity_k.adapt_candidate(candidate("A cap applies.", title="Liability"))]
        matched = fidelity_k.match_entries(labels, drafts, SOURCE)
        self.assertEqual(matched["matches"], {})
        self.assertEqual(matched["unmatched"], ["p-001"])


class ScoreTest(unittest.TestCase):

    def score(self, drafts, entries=None):
        labels = {"source": "p.docx", "entries": entries or [label("p-001")]}
        return fidelity_k.score_document(labels, drafts, SOURCE)

    def test_coverage_and_key_facts_and_authority_are_scored_once_per_label(self):
        scored = self.score([candidate(FULL)])
        self.assertEqual(scored["k1"]["labels"], 1)
        self.assertEqual(scored["k1"]["matched"], 1)
        self.assertEqual((scored["k3"]["found"], scored["k3"]["keyFacts"]), (3, 3))
        # the draft keeps every stance but names no approver: both authorities are missed
        self.assertEqual((scored["k4"]["hit"], scored["k4"]["units"]), (0, 2))
        self.assertEqual(scored["k4"]["predicted"], 0)

    def test_an_authority_conflict_accepts_any_acceptable_approver(self):
        scored = self.score([candidate(FULL, authority=[
            {"approver": "General Counsel", "stances": ["FALLBACK", "WALK_AWAY"]}])])
        self.assertEqual(scored["k4"]["hit"], 2)
        self.assertEqual(scored["k4"]["correct"], 2)

    def test_naming_an_approver_outside_the_acceptable_set_is_a_miss_and_a_false_positive(self):
        scored = self.score([candidate(FULL, authority=[
            {"approver": "Head of Finance", "stances": ["FALLBACK"]}])])
        self.assertEqual(scored["k4"]["hit"], 0)
        self.assertEqual(scored["k4"]["predicted"], 1)
        self.assertEqual(scored["k4"]["correct"], 0)
        self.assertEqual(scored["k4Wrong"][0]["approver"], "Head of Finance")

    def test_a_context_entry_made_a_comparator_is_a_role_violation(self):
        entry = label("p-009", kind="CONTEXT", title="Purpose", lines=(1, 1), stances={},
                      effective={"preferred": {"approval": "NONE"}})
        draft = candidate("DOCUMENT TITLE", title="Purpose", cid="c9",
                          positions=["ESCALATION"])
        scored = self.score([draft], entries=[entry])
        self.assertEqual(scored["k2"]["matchedContext"], 1)
        self.assertEqual(scored["k2"]["violations"], 1)
        self.assertEqual(scored["k2Violations"][0]["labelId"], "p-009")
        self.assertEqual(scored["k2Violations"][0]["roles"], ["ESCALATION"])

    def test_a_context_entry_kept_as_context_is_safe(self):
        entry = label("p-009", kind="CONTEXT", title="Purpose", lines=(1, 1), stances={},
                      effective={"preferred": {"approval": "NONE"}})
        draft = candidate("DOCUMENT TITLE", title="Purpose", cid="c9",
                          candidate_type="CONTEXT", positions=[])
        scored = self.score([draft], entries=[entry])
        self.assertEqual((scored["k2"]["matchedContext"], scored["k2"]["violations"]), (1, 0))

    def test_a_fact_in_the_wrong_stance_is_not_credited_to_its_label_stance(self):
        swapped = ("Preferred position: Cap fallback: AUD 1,000,000.\n"
                   "Fallback position: The cap is twelve (12) months of fees.\n"
                   "Walk-away: Never accept unlimited liability.")
        scored = self.score([candidate(swapped)])
        self.assertEqual((scored["k3"]["found"], scored["k3"]["keyFacts"]), (1, 3))
        self.assertEqual([miss["stance"] for miss in scored["k3Misses"]],
                         ["preferred", "fallback"])

    def test_a_merged_entry_is_credited_but_counted_as_merged_not_attributed(self):
        merged = ("H-01 | Limitation of liability | MSA | The cap is twelve (12) months of fees. | "
                  "Cap fallback: AUD 1,000,000. | Never accept unlimited liability. | "
                  "General Counsel")
        scored = self.score([candidate(merged, title="H-01")])
        self.assertEqual((scored["k3"]["found"], scored["k3"]["keyFacts"]), (3, 3))
        self.assertEqual(scored["k3"]["stancesMerged"], 3)
        self.assertEqual(scored["k3"]["stancesAttributed"], 0)

    def test_duplicate_draft_ids_are_refused(self):
        with self.assertRaises(ValueError):
            self.score([candidate(FULL, cid="same"), candidate(FULL, cid="same")])

    def test_a_duplicate_predicted_pair_is_counted_once(self):
        scored = self.score([candidate(FULL, authority=[
            {"approver": "General Counsel", "stances": ["WALK_AWAY"]},
            {"approver": "General Counsel", "stances": ["WALK_AWAY"]}])])
        self.assertEqual(scored["k4"]["predicted"], 1)
        self.assertEqual(scored["k4"]["correct"], 1)

    def test_a_duplicate_key_fact_in_a_label_is_counted_once(self):
        entry = label("p-001", stances={
            "preferred": stance("The cap is twelve (12) months of fees.",
                                "twelve (12) months", "twelve (12) months"),
            "fallback": None, "walkAway": None},
            effective={"preferred": {"approval": "NONE"}})
        scored = self.score([candidate(FULL)], entries=[entry])
        self.assertEqual((scored["k3"]["found"], scored["k3"]["keyFacts"]), (1, 1))

    def test_an_unmatched_label_counts_every_fact_and_authority_as_missed(self):
        entry = label("p-001", lines=(2, 4))
        scored = self.score([], entries=[entry])
        self.assertEqual(scored["k1"]["matched"], 0)
        self.assertEqual(scored["unmatched"], ["p-001"])
        self.assertEqual((scored["k3"]["found"], scored["k3"]["keyFacts"]), (0, 3))
        self.assertEqual((scored["k4"]["hit"], scored["k4"]["units"]), (0, 2))

    def test_a_repeated_key_fact_is_counted_once(self):
        entry = label("p-001", stances={
            "preferred": stance("The cap is twelve (12) months of fees.", "twelve (12) months"),
            "fallback": None, "walkAway": None},
            effective={"preferred": {"approval": "NONE"}})
        text = FULL + "\nTwelve (12) months is also the cap."
        scored = self.score([candidate(text)], entries=[entry])
        self.assertEqual((scored["k3"]["found"], scored["k3"]["keyFacts"]), (1, 1))

    def test_a_pair_is_matched_only_with_the_stance_it_was_raised_for(self):
        # the right person, but the wrong stance: nothing is credited
        scored = self.score([candidate(FULL, authority=[
            {"approver": "General Counsel", "stances": ["PREFERRED"]}])])
        self.assertEqual(scored["k4"]["hit"], 0)
        self.assertEqual(scored["k4"]["correct"], 0)
        self.assertEqual(scored["k4Wrong"][0]["approver"], "General Counsel")

    def test_an_authority_conflict_is_one_unit_not_one_per_acceptable_approver(self):
        units, _ = fidelity_k.expected_units(label("p-001"))
        self.assertEqual(len(units), 2)  # fallback and walk-away; not three for two acceptable names
        self.assertEqual(units[0]["acceptable"], ["Commercial Legal Director", "General Counsel"])

    def test_duplicate_label_ids_are_refused_not_scored_twice(self):
        with self.assertRaises(ValueError):
            self.score([candidate(FULL)], entries=[label("p-001"), label("p-001")])

    def test_a_draft_that_raises_approval_without_a_name_is_reported_not_scored(self):
        scored = self.score([candidate(FULL, requiresApproval=True)])
        self.assertEqual(scored["k4"]["unnamed"], 1)
        self.assertEqual(scored["k4"]["predicted"], 0)


class TotalsAndReportTest(unittest.TestCase):

    def scored(self, source, entries, drafts):
        return fidelity_k.score_document({"source": source, "entries": entries}, drafts, SOURCE)

    def test_totals_sum_counts_not_ratios(self):
        one = self.scored("a.docx", [label("a-001")], [candidate(FULL, cid="a1")])
        two = self.scored("b.docx", [label("b-001")], [])
        totals = fidelity_k.totals([one, two])
        self.assertEqual((totals["k1"]["labels"], totals["k1"]["matched"]), (2, 1))
        self.assertEqual((totals["k3"]["found"], totals["k3"]["keyFacts"]), (3, 6))
        self.assertEqual((totals["k4"]["hit"], totals["k4"]["units"]), (0, 4))

    def test_unmatched_drafts_are_reported(self):
        scored = self.scored("p.docx", [label("p-001")],
                             [candidate(FULL, cid="c1"),
                              candidate("Unrelated live vehicle data.", title="Data", cid="c2")])
        self.assertEqual([row["entryId"] for row in scored["unmatchedDrafts"]], ["c2"])

    def test_the_report_names_every_metric_and_prints_the_path(self):
        out = pathlib.Path(TMP.name, "k-report")
        if out.exists():
            shutil.rmtree(out)
        scored = self.scored("p.docx", [label("p-001")], [candidate(FULL)])
        path = fidelity_k.write_outputs(out, [scored], fidelity_k.totals([scored]),
                                        {"set": "tune", "context": "ctx"})
        text = (out / "summary.md").read_text()
        self.assertEqual(path, out / "summary.md")
        for marker in ("# Knowledge fidelity (K)", "set `tune`", "K1 entry coverage",
                       "K2 no-role safety", "K3 stance key-fact recall",
                       "K4 authority (approver, stance)", "## Per document",
                       "Draft entries matching no label"):
            self.assertIn(marker, text)
        data = json.loads((out / "results.json").read_text())
        self.assertEqual(data["meta"]["set"], "tune")
        self.assertEqual(data["results"][0]["source"], "p.docx")


class MainPlumbingTest(unittest.TestCase):
    """The live loop with a fake backend: no model, no publish, one report."""

    def test_the_loop_scores_the_drafts_and_never_publishes(self):
        root = pathlib.Path(TMP.name, "k-live")
        if root.exists():
            shutil.rmtree(root)
        context = root / "context"
        labels_dir = context / "eval" / "fidelity" / "tune" / "labels"
        sources = context / "eval" / "fidelity" / "tune" / "sources"
        labels_dir.mkdir(parents=True)
        sources.mkdir(parents=True)
        (labels_dir / "p.docx.json").write_text(json.dumps(
            {"source": "p.docx", "labeller": "test", "entries": [label("p-001")]}))
        (sources / "p.docx.txt").write_text("\n".join(SOURCE))
        document = root / "p.docx"
        document.write_bytes(b"fake")
        out = root / "out"

        class FakeApi:
            posted = []

            def __init__(self, base_url):
                pass

            def wait_ready(self):
                pass

            def upload(self, path, file_path):
                return {"id": "artifact-1"}

            def post(self, path, body=None):
                self.posted.append(path)

            def get(self, path):
                return []

        class FakeWatch:
            def __init__(self, path):
                pass

            def check(self):
                pass

        argv = ["fidelity_k.py", "--set", "tune", "--out", str(out), "--context", str(context),
                "--document", f"p.docx={document}", "--no-build"]
        api = FakeApi("x")
        with mock.patch.object(sys, "argv", argv), \
                mock.patch.object(fidelity_k, "RivellApi", lambda base: api), \
                mock.patch.object(fidelity_k, "LogWatch", FakeWatch), \
                mock.patch.object(fidelity_k.run_eval, "find_jar", lambda: pathlib.Path("jar")), \
                mock.patch.object(fidelity_k.run_eval, "start_backend",
                                  lambda jar, org, log: (object(), None)), \
                mock.patch.object(fidelity_k.run_eval, "stop_backend", lambda proc, log: None), \
                mock.patch.object(fidelity_k.run_eval, "wait_candidates",
                                  lambda api_, artifact, with_status=False:
                                  ([candidate(FULL)], True)):
            fidelity_k.main()
        self.assertTrue((out / "summary.md").is_file())
        written = json.loads((out / "results.json").read_text())
        self.assertEqual(written["results"][0]["k1"]["matched"], 1)
        self.assertEqual(api.posted, [])  # never publish, never approve


class DocumentTest(unittest.TestCase):

    def test_the_user_test_nda_playbook_resolves_inside_the_context_repo(self):
        context = pathlib.Path("/Users/shridhar600/Dev/Projects/Rivell-context")
        found = fidelity_k.resolve_document(
            context, "company-negotiation-playbook.docx", {})
        self.assertEqual(found.name, "01_Company_Negotiation_Playbook.docx")
        self.assertTrue(found.is_file())

    def test_an_explicit_override_wins(self):
        context = pathlib.Path("/Users/shridhar600/Dev/Projects/Rivell-context")
        found = fidelity_k.resolve_document(context, "company-negotiation-playbook.docx",
                                            {"company-negotiation-playbook.docx":
                                             context / "eval" / "corpus" / "seed"
                                             / "02_Incoming_Counterparty_NDA.docx"})
        self.assertEqual(found.name, "02_Incoming_Counterparty_NDA.docx")


if __name__ == "__main__":
    unittest.main()
