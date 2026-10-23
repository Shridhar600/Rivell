package com.rivell.workbench.knowledge.api;

import java.util.UUID;

import com.rivell.workbench.knowledge.ApplicableKnowledge;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One published playbook or clause library that applies to the declared contract context, with
 * the knowledge version that made it applicable.
 */
@Schema(name = "ApplicableKnowledgeView")
public record ApplicableKnowledgeView(
        UUID artifactId,
        String artifactType,
        String name,
        long publicationNumber) {

    static ApplicableKnowledgeView from(ApplicableKnowledge knowledge) {
        return new ApplicableKnowledgeView(
                knowledge.artifactId(),
                knowledge.artifactType().name(),
                knowledge.name(),
                knowledge.publicationNumber());
    }
}
