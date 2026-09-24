package com.rivell.workbench.knowledge;

import com.rivell.workbench.contracts.LegalConcept;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoQuestion;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * M4 intelligent compilation acceptance against the fixture laboratory with deterministic
 * fake intelligence. Fixtures and scripted expectations double as long-term eval assets:
 * expected artifact assessment, concepts, positions, and duplicate detection.
 */
@SpringBootTest
@Testcontainers
class IntelligentCompilationIT {

    private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE =
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-trixie")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + java.util.UUID.randomUUID());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIntelligenceConfiguration {

        @Bean
        @Primary
        ModelPolicy fakeModelPolicy() {
            return asset -> new ModelBinding("fake", "fake-model-latest");
        }

        @Bean
        @Primary
        SemanticDecisionProvider fakeSemanticDecisionProvider() {
            return new ScriptedProvider();
        }

        @Bean
        @Primary
        com.rivell.workbench.intelligence.GenerativeReasoningProvider fakeGenerativeProvider() {
            return new FakeGenerativeProvider();
        }
    }

    /**
     * Deterministic generative extraction: returns the verbatim escalation-cue sentence
     * from the source so the fake never invents content.
     */
    static final class FakeGenerativeProvider
            implements com.rivell.workbench.intelligence.GenerativeReasoningProvider {

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public com.rivell.workbench.intelligence.GenerationResult generate(
                com.rivell.workbench.intelligence.GenerationRequest request) {
            if (!(request.state().payload() instanceof Map<?, ?> payload)
                    || !(payload.get("text") instanceof String text)) {
                throw new com.rivell.workbench.intelligence.ProviderRejectedException("no text");
            }
            var sentence = java.util.Arrays.stream(text.split("\\."))
                    .filter(part -> part.toLowerCase().contains("escalat"))
                    .findFirst()
                    .map(String::strip)
                    .orElseThrow(() -> new com.rivell.workbench.intelligence.ProviderProtocolException(
                            "no escalation statement"));
            return new com.rivell.workbench.intelligence.GenerationResult(
                    Map.of("escalationTrigger", sentence), "fake-gen-v1", new TokenUsage(90, 10));
        }
    }

    /**
     * Answers every judgment from the fixture expectations:
     * positions are per-role nouls, choice questions select their scripted option.
     */
    static final class ScriptedProvider implements SemanticDecisionProvider {

        private Map<String, Double> nouls = Map.of();
        private Map<String, String> choices = Map.of();

        void script(Map<String, Double> nouls, Map<String, String> choices) {
            this.nouls = nouls;
            this.choices = choices;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            var answers = new java.util.LinkedHashMap<String, com.rivell.workbench.intelligence.SemanticAnswer>();
            for (var entry : request.pack().questions().entrySet()) {
                SemanticQuestion question = entry.getValue();
                if (question instanceof ChoiceQuestion choice) {
                    var selected = choiceFor(entry.getKey(), choice, request);
                    var probabilities = new java.util.LinkedHashMap<String, Double>();
                    choice.criteria().keySet().forEach(option ->
                            probabilities.put(option, option.equals(selected) ? 1.0 : 0.0));
                    answers.put(entry.getKey(), new com.rivell.workbench.intelligence.ChoiceAnswer(
                            selected, 1.0, probabilities));
                } else {
                    var probability = nouls.getOrDefault(entry.getKey(), positionCue(entry.getKey(), request));
                    answers.put(entry.getKey(), new com.rivell.workbench.intelligence.YesNoAnswer(probability));
                }
            }
            return new ProviderExecutionResult(
                    answers, "fake-resolved-v4", new TokenUsage(210, 8));
        }

        /**
         * Fixture-eval scripting: playbook position roles follow the source cues so the
         * fake behaves like the expected labels recorded in the fixture expectations.
         */
        private double positionCue(String name, DecisionExecutionRequest request) {
            var text = textOf(request);
            if (text == null) {
                return 0.02;
            }
            var lower = text.toLowerCase();
            return switch (name) {
                case "role_preferred" -> lower.contains("preferred") ? 0.9 : 0.02;
                case "role_fallback" -> lower.contains("fallback") ? 0.9 : 0.02;
                case "role_prohibited" -> lower.contains("prohibited") ? 0.9 : 0.02;
                case "role_escalation" -> lower.contains("escalat") ? 0.9 : 0.02;
                case "role_commentary" -> lower.contains("commentary") ? 0.9 : 0.02;
                case "requires_approval" -> lower.contains("approval") ? 0.9 : 0.02;
                case "narrows_scope" -> lower.contains("united states") ? 0.9 : 0.02;
                case "source_supports" -> 0.9;
                default -> 0.02;
            };
        }

        private String textOf(DecisionExecutionRequest request) {
            if (!(request.state().payload() instanceof Map<?, ?> payload)) {
                return null;
            }
            if (payload.get("text") instanceof String text) {
                return text;
            }
            return payload.get("sourceText") instanceof String sourceText ? sourceText : null;
        }

        private String choiceFor(
                String name, ChoiceQuestion question, DecisionExecutionRequest request) {
            var scripted = choices.get(name);
            if (scripted != null) {
                return scripted;
            }
            if (name.equals("artifact_class")
                    && request.state().payload() instanceof Map<?, ?> payload
                    && payload.get("declaredType") instanceof String declared) {
                return declared;
            }
            return question.criteria().keySet().iterator().next();
        }
    }

    @Autowired
    ScriptedProvider fakeProvider;

    @Autowired
    KnowledgeIngestionService ingestionService;

    @Autowired
    KnowledgeQueryService queryService;

    @Autowired
    KnowledgePublicationService publicationService;

    @Autowired
    MutableIdentity identityProvider;

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        MutableIdentity mutableIdentityProvider() {
            return new MutableIdentity();
        }
    }

    static final class MutableIdentity implements IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), Set.of("ADMIN"));

        void use(UserIdentity identity) {
            current = identity;
        }

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    @BeforeEach
    void scriptDefaults() {
        // concepts high for confidentiality playbook rows; all position roles strong
        fakeProvider.script(Map.ofEntries(
                Map.entry("concept_confidentiality", 0.9),
                Map.entry("concept_term_termination", 0.1),
                Map.entry("concept_limitation_of_liability", 0.3),
                Map.entry("concept_indemnification", 0.05),
                Map.entry("concept_governing_law", 0.1),
                Map.entry("requires_approval", 0.1)), Map.of());
    }

    @Test
    void playbookCompilationProducesTypedSemanticCandidates() {
        var artifact = ingest("fixtures/m4-playbook.docx", "m4-playbook.docx", ArtifactType.PLAYBOOK);

        assertThat(artifact.assessment()).isNotNull();
        assertThat(artifact.assessment().state())
                .isEqualTo(ArtifactTypeAssessment.AssessmentState.MATCH);

        var candidates = queryService.getCandidates(artifact.id());
        assertThat(candidates).isNotEmpty();
        var confidential = candidates.stream()
                .filter(candidate -> candidate.contentText() != null
                        && candidate.contentText().toLowerCase().contains("confidentiality obligations"))
                .findFirst().orElseThrow();
        assertThat(confidential.effectivePositions()).contains(PositionRole.PREFERRED);
        assertThat(confidential.effectiveRequiresApproval()).isFalse();
        // scripted below the 0.80 approval threshold: honestly no approval requirement
        assertThat(confidential.semanticFields().conceptProbabilities()
                .get(LegalConcept.CONFIDENTIALITY)).isEqualTo(0.9);
assertThat(confidential.interpretations())
                .anyMatch(interpretation -> interpretation.field().equals("positions"));

        // the joined confidentiality section legitimately carries several position roles
        // (multi-role honesty) — one dominant Preferred, one Prohibited with escalation
        assertThat(confidential.effectivePositions())
                .contains(PositionRole.PREFERRED, PositionRole.PROHIBITED, PositionRole.ESCALATION);
        assertThat(confidential.effectiveEscalationTrigger())
                .isEqualTo("Escalate to general counsel before agreeing");
        assertThat(confidential.interpretations().stream()
                .filter(interpretation -> interpretation.field().equals("escalationTrigger"))
                .findFirst().orElseThrow().verification())
                .isEqualTo(VerificationState.VERIFIED);
        assertThat(confidential.reviewReasons())
                .contains(ReviewReason.NEW_PROHIBITED_OR_ESCALATION_POSITION);
        assertThat(confidential.reviewCategory()).isEqualTo(ReviewCategory.ELEVATED);

        assertThat(candidates.stream())
                .anyMatch(candidate -> candidate.reviewCategory() == ReviewCategory.ROUTINE);
    }

    @Test
    void clauseLibraryCompilationPreservesExactSourceText() {
        var artifact = ingest("fixtures/m4-clause-library.docx", "m4-lib.docx",
                ArtifactType.CLAUSE_LIBRARY);

        var clause = queryService.getCandidates(artifact.id()).stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.CLAUSE_VARIANT)
                .findFirst().orElseThrow();

        assertThat(clause.effectiveContentText())
                .isEqualTo(clause.contentText());
        assertThat(clause.effectiveVariantRole()).isEqualTo(PositionRole.PREFERRED);
        assertThat(clause.legalConceptKey()).isEqualTo(LegalConcept.CONFIDENTIALITY.name());
    }

    @Test
    void misdeclaredArtifactTypeProducesAdvisoryMismatch() {
        fakeProvider.script(Map.of(), Map.of("artifact_class", "EXECUTED_PRECEDENT"));

        var artifact = ingest("fixtures/m4-executed-precedent-style.docx", "m4-mis.docx",
                ArtifactType.PLAYBOOK);

        assertThat(artifact.ingestStatus()).isEqualTo(IngestStatus.EXTRACTED);
        assertThat(artifact.artifactType()).isEqualTo(ArtifactType.PLAYBOOK);
        assertThat(artifact.assessment()).isNotNull();
        assertThat(artifact.assessment().assessedType()).isEqualTo(ArtifactType.EXECUTED_PRECEDENT);
        assertThat(artifact.assessment().state())
                .isEqualTo(ArtifactTypeAssessment.AssessmentState.POSSIBLE_MISMATCH);
        assertThat(artifact.assessment().warnings())
                .contains("HUMAN_DECLARED_TYPE_DIFFERS_FROM_MACHINE_ASSESSMENT");
    }

    @Autowired
    CandidateReviewService reviewService;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Test
    void conflictingPublishedPositionProducesHighRiskReviewCategory() {
        var first = ingest("fixtures/m4-clause-library.docx", "m4-conflict-a.docx",
                ArtifactType.CLAUSE_LIBRARY);
        // deterministic pair selection: same-role candidates break created_at ties by
        // random UUID, so the published peer and the new candidate are pinned by label
        var preferred = queryService.getCandidates(first.id()).stream()
                .filter(candidate -> candidate.effectiveVariantRole() == PositionRole.PREFERRED)
                .filter(candidate -> candidate.rawLabel() != null
                        && candidate.rawLabel().contains("Preferred"))
                .filter(candidate -> candidate.rawLabel().contains("Limitation"))
                .findFirst().orElseThrow();
        reviewService.approve(preferred.id(), null);
        publicationService.publish(java.util.List.of(preferred.id()), null, "v1");

        fakeProvider.script(
                Map.ofEntries(
                        Map.entry("concept_confidentiality", 0.9),
                        Map.entry("contradicts", 0.95),
                        Map.entry("supersedes", 0.02)),
                Map.of("variant_role", "FALLBACK"));
        var second = ingest("fixtures/m4-clause-library.docx", "m4-conflict-b.docx",
                ArtifactType.CLAUSE_LIBRARY);
        // deterministic selection: the peer pair must share the published concept;
        // ordering among same-role candidates is not a contract (created_at ties
        // break by random UUID), so select by published-concept match instead
        var fallback = queryService.getCandidates(second.id()).stream()
                .filter(candidate -> candidate.effectiveVariantRole() == PositionRole.FALLBACK)
                .filter(candidate -> candidate.rawLabel() != null
                        && candidate.rawLabel().contains("Fallback"))
                .findFirst().orElseThrow();

        assertThat(fallback.conflictSignals())
                .anyMatch(signal -> signal.conflictType() == ConflictType.CONTRADICTS_EXISTING);
        assertThat(fallback.reviewCategory()).isEqualTo(ReviewCategory.HIGH);
        assertThat(fallback.reviewReasons()).contains(ReviewReason.CONFLICT_DETECTED);
        assertThat(fallback.reviewCategory()).isNotEqualTo(ReviewCategory.ROUTINE);
    }

    @Test
    void bulkApprovalApprovesOnlyServerDeterminedEligibleCandidates() {
        var artifact = ingest("fixtures/m4-clause-library.docx", "m4-bulk.docx",
                ArtifactType.CLAUSE_LIBRARY);
        var candidates = queryService.getCandidates(artifact.id());
        // scripted defaults: variant roles below the prohibited/escalation cues -> some ROUTINE
        var routine = candidates.stream()
                .filter(candidate -> candidate.reviewCategory() == ReviewCategory.ROUTINE)
                .findFirst().orElseThrow();
        var elevatedId = candidates.stream()
                .filter(candidate -> candidate.reviewCategory() == ReviewCategory.ELEVATED)
                .findFirst()
                .map(c -> c.id())
                .orElseGet(java.util.UUID::randomUUID);

        var result = reviewService.bulkApprove(java.util.List.of(routine.id(), elevatedId));

        assertThat(result.approvedIds()).containsExactly(routine.id());
        assertThat(result.skipped()).containsKey(elevatedId);
        assertThat(queryService.getCandidate(routine.id()).reviewStatus())
                .isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void humanScopeAndSemanticCorrectionsPersist() {
        var artifact = ingest("fixtures/m4-clause-library.docx", "m4-edit.docx",
                ArtifactType.CLAUSE_LIBRARY);
        var candidate = queryService.getCandidates(artifact.id()).stream()
                .filter(c -> c.candidateType() == CandidateType.CLAUSE_VARIANT)
                .findFirst().orElseThrow();

        var edited = reviewService.edit(
                candidate.id(), null, null,
                new ApplicabilityScope("MSA_PACK", Set.of("MSA"), "Customer", "Supplier paper",
                        "UK", java.time.LocalDate.of(2027, 1, 1), null),
                new SemanticFields(java.util.Set.of(PositionRole.PREFERRED), null, null, null, Map.of()),
                "human correction");

        var reloaded = queryService.getCandidate(candidate.id());
        assertThat(reloaded.reviewStatus()).isEqualTo(ReviewStatus.EDITED);
        assertThat(reloaded.scope().jurisdiction()).isEqualTo("UK");
        assertThat(reloaded.scope().contractPackKey()).isEqualTo("MSA_PACK");
        assertThat(reloaded.editedSemanticFields()).isNotNull();
        assertThat(reloaded.effectiveVariantRole()).isEqualTo(PositionRole.PREFERRED);
        assertThat(reloaded.scope()).isEqualTo(edited.scope());
        // machine proposal preserved beside human correction
        assertThat(reloaded.semanticFields().variantRole()).isNotNull();
    }

    private KnowledgeArtifact ingest(
            String fixture, String filename, ArtifactType artifactType) {
        try (var stream = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(stream).isNotNull();
            return ingestionService.ingest(
                    filename, "application/octet-stream", artifactType, null, stream);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
