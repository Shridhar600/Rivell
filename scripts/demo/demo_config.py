"""Loads and validates the reviewed demo driver config.

The applicability rule mirrors ContractType.applicableCodes() for the contract
types the Bramber corpus uses. Kept here so the config test can prove that each
contract's declared knowledge is exactly the set its type admits.
"""
import json
import pathlib

KNOWLEDGE_TYPES = {"PLAYBOOK", "CLAUSE_LIBRARY"}
PHASES = {"base", "tour"}
STAGES = {"analyzed", "mid-review", "decided", "finalized"}
CONTRACT_TYPES = {
    "NDA", "MNDA", "ONDA", "MSA", "SAAS", "DPA", "SOW", "SWL", "SUPPLY",
    "SERVICES", "RESELLER", "ORDER_FORM", "AMENDMENT", "OTHER",
}
PARTY_ROLES = {
    "CUSTOMER", "SUPPLIER", "DISCLOSING_PARTY", "RECEIVING_PARTY", "LICENSOR",
    "LICENSEE", "PARTY_A", "PARTY_B",
}
PAPER_SOURCES = {"OUR_PAPER", "COUNTERPARTY_PAPER", "THIRD_PARTY"}
CONTRACT_PACK_KEYS = {"MSA_PACK", "NDA_PACK"}
JURISDICTIONS = {
    "US", "UK", "AUSTRALIA", "CANADA", "IRELAND", "INDIA", "NEW_ZEALAND",
    "SINGAPORE", "HONG_KONG", "GERMANY", "FRANCE", "NETHERLANDS", "OTHER",
    "UNSPECIFIED",
}

APPLICABLE_CODES = {
    "MSA": frozenset({"MSA"}),
    "ORDER_FORM": frozenset({"ORDER_FORM"}),
    "SOW": frozenset({"SOW"}),
    "DPA": frozenset({"DPA"}),
    "MNDA": frozenset({"MNDA", "NDA"}),
}


class ConfigError(ValueError):
    """The reviewed demo config is not usable as written."""


def applicable_codes(contract_type: str) -> frozenset[str]:
    try:
        return APPLICABLE_CODES[contract_type]
    except KeyError:
        raise ConfigError(f"unknown contract type {contract_type!r}") from None


def _fail(where: str, message: str):
    raise ConfigError(f"{where}: {message}")


def _require_mapping(value, where: str) -> dict:
    if not isinstance(value, dict):
        _fail(where, "must be an object")
    return value


def _require_list(value, where: str) -> list:
    if not isinstance(value, list):
        _fail(where, "must be a list")
    return value


def _require_text(value, where: str) -> str:
    if not isinstance(value, str) or not value.strip():
        _fail(where, "must be a non-blank string")
    return value


def _check_enum(value, allowed: set[str], where: str):
    if not isinstance(value, str) or value not in allowed:
        _fail(where, f"unknown value {value!r}; expected one of {sorted(allowed)}")


def _validate_knowledge(entries: list, corpus_root: pathlib.Path) -> None:
    seen = set()
    tour = 0
    for index, entry in enumerate(entries):
        entry = _require_mapping(entry, f"knowledge[{index}]")
        file = _require_text(entry.get("file"), f"knowledge[{index}].file")
        where = f"knowledge {file!r}"
        if file in seen:
            _fail(where, "duplicate knowledge file")
        seen.add(file)
        if not (corpus_root / file).is_file():
            _fail(where, "corpus file does not exist")
        _check_enum(entry.get("artifactType"), KNOWLEDGE_TYPES, f"{where} artifactType")
        _check_enum(entry.get("phase"), PHASES, f"{where} phase")
        declared = entry.get("declaredContractTypes")
        if not isinstance(declared, list) or not declared:
            _fail(where, "declaredContractTypes must be a non-empty list")
        if any(not isinstance(code, str) for code in declared):
            _fail(where, "declaredContractTypes entries must be strings")
        for code in declared:
            if code not in CONTRACT_TYPES:
                _fail(where, f"declaredContractTypes has unknown value {code!r}")
        if entry["phase"] == "tour":
            tour += 1
    if tour > 1:
        _fail("knowledge", "more than one tour knowledge source")


def _validate_contracts(entries: list, knowledge: list, corpus_root: pathlib.Path) -> None:
    seen = set()
    tour = 0
    knowledge_files = {entry["file"] for entry in knowledge}
    for index, entry in enumerate(entries):
        entry = _require_mapping(entry, f"contracts[{index}]")
        file = _require_text(entry.get("file"), f"contracts[{index}].file")
        where = f"contract {file!r}"
        if file in seen:
            _fail(where, "duplicate contract file")
        seen.add(file)
        if not (corpus_root / file).is_file():
            _fail(where, "corpus file does not exist")
        _check_enum(entry.get("stage"), STAGES, f"{where} stage")
        _check_enum(entry.get("phase"), PHASES, f"{where} phase")
        if entry["phase"] == "tour":
            tour += 1
        review = _require_mapping(entry.get("review"), f"{where} review")
        contract_type = review.get("contractType")
        _check_enum(contract_type, CONTRACT_TYPES, f"{where} review.contractType")
        if contract_type not in APPLICABLE_CODES:
            _fail(f"{where} review.contractType",
                  f"{contract_type} is not supported by this demo config")
        _check_enum(review.get("ourSideRole"), PARTY_ROLES, f"{where} review.ourSideRole")
        _check_enum(review.get("paperSource"), PAPER_SOURCES, f"{where} review.paperSource")
        _check_enum(review.get("jurisdiction"), JURISDICTIONS, f"{where} review.jurisdiction")
        pack = review.get("contractPackKey")
        if pack is not None and not isinstance(pack, str):
            _fail(f"{where} review.contractPackKey", "must be a string or null")
        if pack is not None and pack not in CONTRACT_PACK_KEYS:
            _fail(f"{where} review.contractPackKey",
                  f"unknown pack {pack!r}; expected one of {sorted(CONTRACT_PACK_KEYS)}")
        applies = entry.get("appliesKnowledge")
        if not isinstance(applies, list):
            _fail(f"{where} appliesKnowledge", "must be a list")
        if any(not isinstance(name, str) for name in applies):
            _fail(f"{where} appliesKnowledge", "every entry must be a string")
        if len(applies) != len(set(applies)):
            _fail(f"{where} appliesKnowledge", "contains a duplicate knowledge file")
        unknown = [name for name in applies if name not in knowledge_files]
        if unknown:
            _fail(f"{where} appliesKnowledge", f"unknown knowledge file {unknown[0]!r}")
        expected = {k["file"] for k in knowledge
                    if set(k["declaredContractTypes"]) & applicable_codes(contract_type)}
        if not applies:
            _fail(f"{where} appliesKnowledge", "must not be empty")
        if set(applies) != expected:
            _fail(f"{where} appliesKnowledge",
                  f"is {sorted(set(applies))}, but the declared types admit {sorted(expected)}")
    if tour > 1:
        _fail("contracts", "more than one tour contract")


def validate_config(config: dict, corpus_root: pathlib.Path) -> None:
    config = _require_mapping(config, "config")
    version = config.get("version")
    if version != 1:
        _fail("config version", f"unknown version {version!r}; expected 1")
    if not isinstance(corpus_root, pathlib.Path):
        corpus_root = pathlib.Path(corpus_root)
    knowledge = _require_list(config.get("knowledge"), "config knowledge")
    contracts = _require_list(config.get("contracts"), "config contracts")
    _validate_knowledge(knowledge, corpus_root)
    _validate_contracts(contracts, knowledge, corpus_root)


def load_config(path: pathlib.Path) -> dict:
    path = pathlib.Path(path)
    try:
        config = json.loads(path.read_text())
    except FileNotFoundError:
        raise ConfigError(f"config not found: {path}") from None
    except ValueError as error:
        raise ConfigError(f"config is not valid JSON: {path}: {error}") from None
    validate_config(config, corpus_root=path.parent / "corpus")
    return config


def contracts_for_phase(config: dict, phase: str) -> list[dict]:
    return [entry for entry in config["contracts"] if entry["phase"] == phase]


def knowledge_for_phase(config: dict, phase: str) -> list[dict]:
    return [entry for entry in config["knowledge"] if entry["phase"] == phase]
