"""Unit tests for reading both corpus layouts. Run: cd scripts/eval && python3 -m unittest test_corpus_manifest.py"""
import json
import pathlib
import tempfile
import unittest

import run_eval


def write(root, rel, data):
    path = pathlib.Path(root) / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data))


class ManifestLayoutTest(unittest.TestCase):

    def test_reads_entries_given_as_objects_with_a_path(self):
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as root:
            write(root, "manifest.json", {"set": "x", "organisations": [{
                "id": "M", "side": "customer",
                "playbook": {"path": "playbooks/m-playbook.xlsx", "format": "XLSX", "positions": 3},
                "clauseLibrary": None,
                "contracts": [{"path": "contracts/m01-msa.docx", "answerKey": "answers/m01-msa.json"}],
                "precedents": [{"path": "precedents/m-p1.docx", "answerKey": "answers/m-p1.json"}]}]})
            orgs = run_eval.load_orgs(pathlib.Path(root))
        self.assertEqual(orgs, [{"id": "M", "side": "customer", "playbook": "playbooks/m-playbook.xlsx",
                                 "clauseLibrary": None, "contracts": ["contracts/m01-msa.docx"],
                                 "precedents": ["precedents/m-p1.docx"]}])

    def test_reads_entries_given_as_plain_paths_unchanged(self):
        org = {"id": "A", "playbook": "playbooks/a.docx", "clauseLibrary": "libs/a.docx", "contracts": ["contracts/a1.docx"]}
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as root:
            write(root, "manifest.json", {"organisations": [org]})
            self.assertEqual(run_eval.load_orgs(pathlib.Path(root)), [org])


class KnowledgeAnswerLayoutTest(unittest.TestCase):

    def test_a_key_that_is_a_list_of_positions_reads_as_positions(self):
        positions = [{"id": "P1", "preferred": "a", "walkAway": None}]
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as root:
            write(root, "answers/m-playbook.json", positions)
            answer = run_eval.load_answer(pathlib.Path(root), "playbooks/m-playbook.xlsx")
        self.assertEqual(answer, {"positions": positions})


class SelectOrgsTest(unittest.TestCase):
    ORGS = [{"id": "M", "playbook": "p/m.xlsx", "contracts": ["contracts/m01-a.docx", "contracts/m02-b.docx"]},
            {"id": "N", "playbook": "p/n.docx", "contracts": ["contracts/n01-c.docx"]}]

    def test_no_filter_keeps_everything(self):
        self.assertEqual(run_eval.select_orgs(self.ORGS, None, None), self.ORGS)

    def test_a_contract_filter_keeps_only_those_contracts_and_their_orgs(self):
        orgs = run_eval.select_orgs(self.ORGS, None, ["m02-b"])
        self.assertEqual(orgs, [{"id": "M", "playbook": "p/m.xlsx", "contracts": ["contracts/m02-b.docx"]}])

    def test_an_org_filter_still_works(self):
        self.assertEqual([o["id"] for o in run_eval.select_orgs(self.ORGS, ["N"], None)], ["N"])


if __name__ == "__main__":
    unittest.main()
