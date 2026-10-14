package com.rivell.workbench.redline;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The frozen M8 → M9 boundary artifact: exactly the changes a human authorized for one
 * review, bound to the review's contract version and active analysis run. Immutable
 * once finalized; M9 consumes it without legal or model reasoning.
 *
 * <p>{@code semanticsDigest} is the review snapshot's digest of the contract semantics the
 * review planned against. M9 uses it to prove that the effective concepts feeding INSERT
 * placement are still the ones the review froze, and fails closed when they are not.
 */
public record ApprovedChangeSet(
        UUID id,
        UUID organizationId,
        UUID reviewId,
        UUID runId,
        UUID contractId,
        UUID contractVersionId,
        int contractVersionNumber,
        int changeSetVersion,
        String semanticsDigest,
        UUID finalizedBy,
        Instant finalizedAt,
        List<ApprovedChange> changes) {

    public ApprovedChangeSet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        Objects.requireNonNull(finalizedBy, "finalizedBy");
        Objects.requireNonNull(finalizedAt, "finalizedAt");
        Objects.requireNonNull(semanticsDigest, "semanticsDigest");
        changes = changes == null ? List.of() : List.copyOf(changes);
        if (contractVersionNumber < 1 || changeSetVersion < 1) {
            throw new IllegalArgumentException("version numbers must be >= 1");
        }
        for (var change : changes) {
            if (!id.equals(change.changeSetId())) {
                throw new IllegalArgumentException("change belongs to another set");
            }
        }
    }
}
