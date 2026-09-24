/**
 * Authority-aware evidence construction (Architecture Decision #8). Retrieval returns
 * provenance-backed EvidenceBundles with typed sufficiency — never legal decisions.
 * Structured policy and approved drafting resolve first; precedent is discovered
 * through metadata-filtered lexical/vector retrieval with deterministic fusion and
 * bounded Jev reranking. Authority and applicability outrank similarity.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Retrieval",
        allowedDependencies = {"foundation", "organization", "knowledge", "contracts",
                "intelligence"})
package com.rivell.workbench.retrieval;
