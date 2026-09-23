package com.rivell.workbench.contracts;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The typed semantic model of one contract version: immutable source + hierarchical
 * structure (M1) + this semantic layer (AD #5). Represents only what the contract
 * contains — never whether it is acceptable. Carries safe enrichment observations
 * (AD #30) for later evaluation work.
 */
public record ContractSemantics(
        UUID id,
        UUID contractVersionId,
        UUID organizationId,
        EnrichmentStatus enrichmentStatus,
        String pipelineVersion,
        ReviewProfile reviewProfile,        List<MetadataFact> metadata,
        List<ContractParty> parties,
        List<DefinedTerm> definedTerms,
        List<ContractReference> references,
        List<ClauseSemantics> clauses,
        List<SemanticRelationship> relationships,
        QualityReasons quality,
        Observations observations,
        List<String> warnings) {

    public static final String PIPELINE_VERSION = "rivell-contract-enrichment-1";

    public ContractSemantics {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(enrichmentStatus, "enrichmentStatus");
        if (pipelineVersion == null || pipelineVersion.isBlank()) {
            throw new IllegalArgumentException("Pipeline version must not be blank");
        }
        Objects.requireNonNull(reviewProfile, "reviewProfile");
        metadata = metadata == null ? List.of() : List.copyOf(metadata);
        parties = parties == null ? List.of() : List.copyOf(parties);
        definedTerms = definedTerms == null ? List.of() : List.copyOf(definedTerms);
        references = references == null ? List.of() : List.copyOf(references);
        clauses = clauses == null ? List.of() : List.copyOf(clauses);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        Objects.requireNonNull(quality, "quality");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** The persisted header id is stable per version; children must key to it. */
    public ContractSemantics withHeaderId(UUID stableId) {
        return new ContractSemantics(stableId, contractVersionId, organizationId,
                enrichmentStatus, pipelineVersion, reviewProfile, metadata, parties,
                definedTerms, references, clauses, relationships, quality, observations,
                warnings);
    }
}

