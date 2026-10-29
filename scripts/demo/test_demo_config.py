import pathlib
import unittest

import demo_config

CONFIG = pathlib.Path(__file__).resolve().parents[2] / "demo" / "config.json"
CORPUS = CONFIG.parent / "corpus"


class ConfigTest(unittest.TestCase):

    def test_the_expected_knowledge_is_exactly_what_the_declared_types_admit(self):
        mirrored = {
            "MSA": {"MSA"},
            "ORDER_FORM": {"ORDER_FORM"},
            "SOW": {"SOW"},
            "DPA": {"DPA"},
            "MNDA": {"MNDA", "NDA"},
        }
        for contract_type, codes in mirrored.items():
            self.assertEqual(demo_config.applicable_codes(contract_type), frozenset(codes))
        config = demo_config.load_config(CONFIG)
        for contract in config["contracts"]:
            admitted = {
                k["file"] for k in config["knowledge"]
                if set(k["declaredContractTypes"])
                & demo_config.applicable_codes(contract["review"]["contractType"])
            }
            self.assertEqual(set(contract["appliesKnowledge"]), admitted,
                             f"{contract['file']}: D4's stage map would be false")

    def test_every_corpus_file_exists_and_is_not_empty(self):
        config = demo_config.load_config(CONFIG)
        for entry in config["knowledge"] + config["contracts"]:
            path = CORPUS / entry["file"]
            self.assertTrue(path.is_file(), path)
            self.assertGreater(path.stat().st_size, 0, path)

    def test_there_is_one_tour_contract_and_one_tour_knowledge_source(self):
        config = demo_config.load_config(CONFIG)
        self.assertEqual([c["file"] for c in demo_config.contracts_for_phase(config, "tour")],
                         ["contracts/Sablepoint Analytics - Mutual NDA.docx"])
        self.assertEqual([k["file"] for k in demo_config.knowledge_for_phase(config, "tour")],
                         ["knowledge/NDA Playbook.xlsx"])

    def test_no_eval_set_is_named(self):
        text = CONFIG.read_text().lower()
        for word in ("dev2", "holdout", "eval/corpus"):
            self.assertNotIn(word, text)
        for entry in demo_config.load_config(CONFIG)["knowledge"] + \
                demo_config.load_config(CONFIG)["contracts"]:
            for part in pathlib.Path(entry["file"]).parts:
                self.assertNotIn(part.lower(), {"dev", "dev2", "holdout"})


if __name__ == "__main__":
    unittest.main()
