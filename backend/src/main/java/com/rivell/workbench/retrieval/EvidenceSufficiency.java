package com.rivell.workbench.retrieval;

/**
 * Deterministic sufficiency state of an assembled EvidenceBundle (AD #8.7). Code
 * derives it from the assembled lanes and their degradation state — never an LLM
 * judgment. Missing or ambiguous policy is never repaired from precedent.
 */
public enum EvidenceSufficiency {
    SUFFICIENT,
    PARTIAL,
    POLICY_MISSING,
    AMBIGUOUS_POLICY,
    DRAFTING_MISSING,
    PRECEDENT_WEAK,
    CONTRACT_CONTEXT_INCOMPLETE,
    SOURCE_CONFLICT
}
