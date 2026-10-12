package com.rivell.workbench.review;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;

/**
 * Immutable review input snapshot (Pre-M7 decision 3): copied values and referenced
 * immutable identities, resolved exactly once at review creation. Nothing downstream
 * re-reads "latest".
 */
public record ReviewSnapshot(
        UUID reviewId,
        UUID organizationId,
        UUID contractId,
        UUID contractVersionId,
        int contractVersionNumber,
        ContractType contractType,
        PartyRole ourSideRole,
        PaperSource paperSource,
        Jurisdiction jurisdiction,
        String contractPackKey,
        String packVersion,
        UUID contractSemanticsId,
        String semanticsPipelineVersion,
        String semanticsDigest,
        long knowledgePinNumber,
        LocalDate effectiveOn,
        String reviewPipelineVersion,
        String embeddingIndexVersion,
        String embeddingModelIdentity,
        ModelPolicyIdentity modelPolicy,
        ReviewEngine reviewEngine,
        Instant createdAt) {

    public ReviewSnapshot {
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        Objects.requireNonNull(contractType, "contractType");
        Objects.requireNonNull(jurisdiction, "jurisdiction");
        Objects.requireNonNull(contractSemanticsId, "contractSemanticsId");
        Objects.requireNonNull(semanticsPipelineVersion, "semanticsPipelineVersion");
        Objects.requireNonNull(semanticsDigest, "semanticsDigest");
        Objects.requireNonNull(effectiveOn, "effectiveOn");
        Objects.requireNonNull(reviewPipelineVersion, "reviewPipelineVersion");
        Objects.requireNonNull(embeddingIndexVersion, "embeddingIndexVersion");
        Objects.requireNonNull(modelPolicy, "modelPolicy");
        Objects.requireNonNull(reviewEngine, "reviewEngine");
        Objects.requireNonNull(createdAt, "createdAt");
        if (knowledgePinNumber < 0) {
            throw new IllegalArgumentException("knowledgePinNumber must be >= 0");
        }
        if (contractVersionNumber < 1) {
            throw new IllegalArgumentException("contractVersionNumber must be >= 1");
        }
        if (contractPackKey == null && packVersion != null) {
            throw new IllegalArgumentException("packVersion requires contractPackKey");
        }
    }
}
