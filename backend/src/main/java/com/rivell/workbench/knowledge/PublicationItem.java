package com.rivell.workbench.knowledge;

import java.util.Objects;
import java.util.UUID;

/**
 * One entry of a published knowledge snapshot: the candidate that entered the published
 * set (ADD) or an explicit supersession of a previously published candidate.
 */
public record PublicationItem(
        UUID id,
        UUID publicationId,
        UUID candidateId,
        UUID supersededCandidateId,
        PublicationAction action) {

    public PublicationItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(publicationId, "publicationId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(action, "action");
        if (action == PublicationAction.SUPERSEDE && supersededCandidateId == null) {
            throw new IllegalArgumentException("Supersede item must reference the superseded candidate");
        }
    }
}
