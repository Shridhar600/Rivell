package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.GenerationRequest;
import com.rivell.workbench.intelligence.GenerationResult;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * Regression for the null-text unit crash, re-anchored to the one-record-per-position
 * model. A DOCX table record carries no prose, and the old compiler still classified it
 * as an escalation position and reached {@code GenerativeFieldExtractor.bounded(null)},
 * which threw a NullPointerException that escaped every semantic catch, marked the
 * artifact FAILED and surfaced as a 500.
 *
 * <p>Under record shaping a cell record never reaches the generative extractor at all:
 * its stance comes from role-tagged fields. This test pins that contract - the table
 * record compiles, its candidate carries deterministic positions and no escalation
 * trigger, and the artifact stays EXTRACTED.
 */
@SpringBootTest
@Testcontainers
class NullTextUnitCompilationIT {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-trixie")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NullTextIntelligenceConfiguration {

        @Bean
        @Primary
        ModelPolicy nullTextModelPolicy() {
            return asset -> new ModelBinding("null-text", "null-text-model");
        }

        @Bean
        @Primary
        SemanticDecisionProvider nullTextDecisionProvider() {
            return new NullTextEscalationProvider();
        }

        @Bean
        @Primary
        GenerativeReasoningProvider nullTextGenerativeProvider() {
            return new NullTolerantGenerativeProvider();
        }
    }

    /** Every unit looks like an escalation position; choices fall back to the declared type. */
    static final class NullTextEscalationProvider implements SemanticDecisionProvider {

        @Override
        public String id() {
            return "null-text";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            Map<String, SemanticAnswer> answers = new LinkedHashMap<>();
            for (var entry : request.pack().questions().entrySet()) {
                SemanticQuestion question = entry.getValue();
                if (question instanceof ChoiceQuestion choice) {
                    String selected = "artifact_class".equals(entry.getKey())
                            && choice.criteria().containsKey("PLAYBOOK")
                                    ? "PLAYBOOK"
                                    : choice.criteria().keySet().iterator().next();
                    Map<String, Double> probabilities = new LinkedHashMap<>();
                    choice.criteria().keySet().forEach(option ->
                            probabilities.put(option, option.equals(selected) ? 1.0 : 0.0));
                    answers.put(entry.getKey(), new ChoiceAnswer(selected, 1.0, probabilities));
                } else if ("role_escalation".equals(entry.getKey())
                        || "source_supports".equals(entry.getKey())) {
                    answers.put(entry.getKey(), new YesNoAnswer(0.9));
                } else {
                    answers.put(entry.getKey(), new YesNoAnswer(0.02));
                }
            }
            return new ProviderExecutionResult(answers, "null-text-resolved",
                    new TokenUsage(0, 0));
        }
    }

    /** Answers the escalation-trigger field even when the unit has no text at all. */
    static final class NullTolerantGenerativeProvider implements GenerativeReasoningProvider {

        @Override
        public String id() {
            return "null-text";
        }

        @Override
        public GenerationResult generate(GenerationRequest request) {
            return new GenerationResult(
                    Map.of("escalationTrigger", "Escalate to general counsel."),
                    "null-text-generated", new TokenUsage(0, 0));
        }
    }

    @Autowired
    KnowledgeIngestionService ingestionService;

    @Autowired
    KnowledgeQueryService queryService;

    @Test
    void aTableRecordWithoutProseCompilesWithDeterministicPositions() throws Exception {
        KnowledgeArtifact artifact;
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/knowledge-playbook.docx")) {
            assertThat(stream).isNotNull();
            artifact = ingestionService.ingest("table-unit-playbook.docx",
                    "application/octet-stream", ArtifactType.PLAYBOOK, null, stream);
        }

        assertThat(artifact.ingestStatus())
                .as("a cell record with no prose must not fail the artifact")
                .isEqualTo(IngestStatus.EXTRACTED);

        ExtractionUnit tableUnit = queryService.getUnits(artifact.id()).stream()
                .filter(unit -> unit.kind() == ExtractionUnitKind.TABLE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("fixture must contain a table unit"));
        assertThat(tableUnit.contentText())
                .as("the table record renders its label -> value fields")
                .isEqualTo("Liability cap | 1x annual fees | 2x annual fees");
        assertThat(tableUnit.recordKind())
                .as("role-tagged fields make the record a POSITION without a model call")
                .isEqualTo(KnowledgeRecordKind.POSITION);

        KnowledgeCandidate candidate = queryService.getCandidates(artifact.id()).stream()
                .filter(c -> c.extractionUnitId().equals(tableUnit.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("table record must yield a candidate"));
        assertThat(candidate.effectivePositions())
                .containsExactlyInAnyOrder(PositionRole.PREFERRED, PositionRole.FALLBACK);
        assertThat(candidate.effectiveEscalationTrigger())
                .as("a cell record never reaches the generative extractor")
                .isNull();
        assertThat(candidate.interpretations()).anyMatch(interpretation ->
                "positions".equals(interpretation.field())
                        && interpretation.kind() == InterpretationKind.DETERMINISTIC);
        assertThat(candidate.interpretations()).noneMatch(interpretation ->
                "escalationTrigger".equals(interpretation.field()));
    }
}
