package com.rivell.workbench.knowledge.api;

import java.util.List;
import java.util.UUID;

/**
 * Human publication request. Supersession declarations are HUMAN decisions: the machine
 * may only propose them via signals; a published peer id declared here makes the
 * publication record an explicit SUPERSEDE item. The server validates that the
 * superseded candidate is published, in the same organization, and of the same concept.
 */
public record PublishRequest(
        List<UUID> candidateIds,
        java.util.Map<UUID, UUID> supersededCandidates,
        String note) {
}
