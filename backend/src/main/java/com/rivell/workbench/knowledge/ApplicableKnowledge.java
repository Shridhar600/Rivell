package com.rivell.workbench.knowledge;

import java.util.Objects;
import java.util.UUID;

/**
 * One published playbook or clause library that applies to a review's declared context, with the
 * knowledge version that made it applicable. Resolved by the same published-knowledge lookup the
 * analysis uses, so a preview and the frozen review cannot disagree.
 */
public record ApplicableKnowledge(
        UUID artifactId,
        ArtifactType artifactType,
        String name,
        long publicationNumber) {

    public ApplicableKnowledge {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(artifactType, "artifactType");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (publicationNumber < 1) {
            throw new IllegalArgumentException("publicationNumber must be positive");
        }
        if (artifactType != ArtifactType.PLAYBOOK && artifactType != ArtifactType.CLAUSE_LIBRARY) {
            throw new IllegalArgumentException(
                    "Only playbooks and clause libraries are applicable knowledge");
        }
    }
}
