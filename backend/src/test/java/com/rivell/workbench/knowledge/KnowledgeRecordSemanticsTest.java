package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.SemanticState;

class KnowledgeRecordSemanticsTest {

    @Test
    void aFailedLabelRoleCallIsReportedAsUnavailableNotAsNoUnknownLabels() {
        var semantics = new KnowledgeRecordSemantics(new SemanticDecisionService(
                asset -> new ModelBinding("fake", "fake-model"),
                id -> Optional.empty(), Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(
                    com.rivell.workbench.intelligence.DecisionSpec spec, SemanticState state) {
                throw new ProviderUnavailableException("field role provider unavailable");
            }
        });

        var result = semantics.resolveUnknownLabels(artifact(), List.of(candidate(artifact())));

        assertThat(result.available()).isFalse();
        assertThat(result.roles()).isEmpty();
    }

    private static KnowledgeArtifact artifact() {
        var manifest = new ArtifactManifest(Set.of(), null, null, null, null, null, null, null, null);
        return new KnowledgeArtifact(UUID.randomUUID(), UUID.randomUUID(), ArtifactType.PLAYBOOK,
                "playbook.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                10L, "sha", "key", manifest, IngestStatus.EXTRACTED, "parser", List.of(), null,
                Instant.now(), null);
    }

    private static KnowledgeCandidate candidate(KnowledgeArtifact artifact) {
        var now = Instant.now();
        return new KnowledgeCandidate(UUID.randomUUID(), artifact.id(), artifact.organizationId(),
                UUID.randomUUID(), CandidateType.PLAYBOOK_RULE, ReviewStatus.PENDING,
                "Record", "text",
                new CandidatePayload(Map.of("Mystery", "value"), List.of("Mystery"), Map.of()),
                null, List.of(), null, null, null, SemanticFields.EMPTY, null, List.of(),
                null, null, null, ApplicabilityScope.fromManifest(artifact.manifest()),
                List.of(), null, Set.of(), null, null, now, now, null);
    }
}
