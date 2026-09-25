package com.rivell.workbench.review;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One evidence-bound rationale claim of a Finding. */
public record FindingClaim(
        UUID id,
        int ordinal,
        ClaimKind claimKind,
        String text,
        boolean absence,
        List<UUID> evidenceItemIds) {

    public FindingClaim {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(claimKind, "claimKind");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Claim text must not be blank");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("Claim ordinal must be >= 0");
        }
        evidenceItemIds = evidenceItemIds == null ? List.of() : List.copyOf(evidenceItemIds);
        if (!absence && evidenceItemIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Evidence-bound claims require at least one evidence reference");
        }
    }

    public static FindingClaim of(
            int ordinal, ClaimKind kind, String text, boolean absence, List<UUID> evidence) {
        return new FindingClaim(UUID.randomUUID(), ordinal, kind, text, absence, evidence);
    }
}
