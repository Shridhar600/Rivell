package com.rivell.workbench.knowledge.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.knowledge.PublicationAction;

public record PublicationView(
        UUID id,
        UUID organizationId,
        long publicationNumber,
        String note,
        List<ItemView> items,
        Instant createdAt) {

    public record ItemView(
            UUID id,
            UUID candidateId,
            UUID supersededCandidateId,
            PublicationAction action) {
    }
}
