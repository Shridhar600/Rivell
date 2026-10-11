package com.rivell.workbench.review.check;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewPlanCheck;
import com.rivell.workbench.review.ReviewSnapshot;

/**
 * Everything one check execution may read; all snapshot-frozen except the semantics.
 * {@code headingTexts} maps contract structure node ids to their text (empty when the
 * structure could not be read) so a check can name its subject clause.
 */
public record ReviewCheckContext(
        Review review,
        ReviewSnapshot snapshot,
        ReviewPlanCheck check,
        UUID runId,
        ContractSemantics semantics,
        AnalysisEnvironment environment,
        ReviewEvidenceCache evidenceCache,
        Map<UUID, String> headingTexts,
        Instant executedAt) {

    public ReviewCheckContext {
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(semantics, "semantics");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(evidenceCache, "evidenceCache");
        headingTexts = headingTexts == null ? Map.of() : Map.copyOf(headingTexts);
        Objects.requireNonNull(executedAt, "executedAt");
    }
}
