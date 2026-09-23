package com.rivell.workbench.contracts;

/**
 * Semantic-quality reason codes (M5). Meaningful reasons, never a universal numeric
 * confidence score. Findings remain M7 territory; these only surface upstream state.
 */
public enum ReviewQualityNote {
    NONE,
    PARSER_WARNINGS_PRESENT,
    DUPLICATE_DEFINED_TERM,
    AMBIGUOUS_DEFINITIONS,
    UNRESOLVED_REFERENCES,
    AMBIGUOUS_PARTY_ROLES,
    UNCLASSIFIED_REGIONS,
    UNVERIFIED_FACTS,
    UNSUPPORTED_FACT_SHAPE,
    PROVIDER_DEGRADATION,
    BUDGET_EXHAUSTED,
    NO_PRIMARY_CONCEPT
}
