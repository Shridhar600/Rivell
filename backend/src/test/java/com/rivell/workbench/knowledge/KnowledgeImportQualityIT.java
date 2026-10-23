package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

import com.rivell.workbench.contracts.ContractType;
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
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Review quality for two real user files (fictional dev data): a Word negotiation guide and an
 * Excel clause library, through the real ingestion flow with a deterministic fake for Jev. What
 * the fake answers is stated per test; what the code does with the answers is under test.
 */
@SpringBootTest
@Testcontainers
class KnowledgeImportQualityIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
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
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QualityConfiguration {

        @Bean
        @Primary
        ModelPolicy qualityModelPolicy() {
            return asset -> new ModelBinding("quality", "quality-model");
        }

        @Bean
        @Primary
        QualityDecisionProvider qualityDecisionProvider() {
            return new QualityDecisionProvider();
        }

        @Bean
        @Primary
        GenerativeReasoningProvider qualityGenerativeProvider() {
            return new QualityGenerativeProvider();
        }

        @Bean
        @Primary
        MutableIdentityProvider qualityIdentityProvider() {
            return new MutableIdentityProvider();
        }
    }

    static final class MutableIdentityProvider implements IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    /**
     * Stand-in for Jev. Mirrors what the real one did on these files (dev eval): it reads an
     * unknown "Variant" label as applicability, and it answers each yes/no from a script.
     */
    static final class QualityDecisionProvider implements SemanticDecisionProvider {

        volatile double requiresApproval = 0.5;
        volatile double ipIndemnityTopic = 0.7;

        @Override
        public String id() {
            return "quality";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            var answers = new LinkedHashMap<String, SemanticAnswer>();
            for (var entry : request.pack().questions().entrySet()) {
                if (entry.getValue() instanceof ChoiceQuestion choice) {
                    var selected = selected(entry.getKey(), choice, request.state().payload());
                    var probabilities = new LinkedHashMap<String, Double>();
                    choice.criteria().keySet().forEach(option ->
                            probabilities.put(option, option.equals(selected) ? 1.0 : 0.0));
                    answers.put(entry.getKey(), new ChoiceAnswer(selected, 1.0, probabilities));
                } else {
                    answers.put(entry.getKey(),
                            new YesNoAnswer(probability(entry.getKey(), request.state().payload())));
                }
            }
            return new ProviderExecutionResult(answers, "quality-1", new TokenUsage(0, 0));
        }

        private String selected(String name, ChoiceQuestion question, Object state) {
            if (name.equals("artifact_class")) {
                return String.valueOf(stateValue(state, "declaredType"));
            }
            if (name.equals("record_kind")) {
                return stateValue(state, "sectionPath").equals("Guide") ? "GUIDANCE"
                        : stateValue(state, "sectionPath").equals("Clause Library") ? "WORDING"
                        : "POSITION";
            }
            if (name.startsWith("label_")) {
                return question.instructions().contains("\"Variant\"") ? "APPLICABILITY" : "OTHER";
            }
            return question.criteria().keySet().iterator().next();
        }

        private double probability(String name, Object state) {
            var text = (stateValue(state, "text") + " " + stateValue(state, "recordText")).toLowerCase(Locale.ROOT);
            return switch (name) {
                case "topic_ip_infringement_indemnity" -> ipIndemnityTopic;
                case "role_preferred" -> text.contains("preferred") ? 0.9 : 0.02;
                case "role_fallback" -> text.contains("fallback") ? 0.9 : 0.02;
                case "role_escalation" -> text.contains("escalat") ? 0.9 : 0.02;
                case "requires_approval" -> requiresApproval;
                case "source_supports" -> 0.9;
                default -> 0.02;
            };
        }

        private static String stateValue(Object state, String key) {
            return state instanceof Map<?, ?> payload && payload.get(key) instanceof String value
                    ? value : "";
        }
    }

    /** Returns the escalation sentence from the source, so nothing is invented. */
    static final class QualityGenerativeProvider implements GenerativeReasoningProvider {

        @Override
        public String id() {
            return "quality";
        }

        @Override
        public GenerationResult generate(GenerationRequest request) {
            var text = request.state().payload() instanceof Map<?, ?> payload
                    && payload.get("text") instanceof String value ? value : "";
            var sentence = java.util.Arrays.stream(text.split("\\."))
                    .filter(part -> part.toLowerCase(Locale.ROOT).contains("escalat"))
                    .findFirst().map(String::strip).orElse("Escalate.");
            return new GenerationResult(Map.of("escalationTrigger", sentence), "quality-gen-1",
                    new TokenUsage(0, 0));
        }
    }

    @Autowired
    KnowledgeIngestionService ingestionService;

    @Autowired
    KnowledgeQueryService queryService;

    @Autowired
    QualityDecisionProvider provider;

    @BeforeEach
    void defaults() {
        provider.requiresApproval = 0.5;
        provider.ipIndemnityTopic = 0.7;
    }

    @Test
    void aClauseVariantKeepsItsClauseTextAsWordingAndItsVariantAsItsRole() throws Exception {
        var artifact = ingest("fixtures/import-quality-clause-library.xlsx", "g-clause-library.xlsx",
                ArtifactType.CLAUSE_LIBRARY);

        var candidates = queryService.getCandidates(artifact.id());
        var clauseRows = candidates.stream()
                .filter(candidate -> candidate.payload().columns().containsKey("Variant"))
                .toList();
        assertThat(clauseRows).hasSize(14);
        assertThat(clauseRows).allSatisfy(candidate -> assertThat(candidate.effectiveContentText())
                .isEqualTo(candidate.payload().columns().get("Clause text")));

        var fallback = variant(clauseRows, "G-IND-01", "Fallback 1");
        assertThat(fallback.effectiveVariantRole()).isEqualTo(PositionRole.FALLBACK);
        assertThat(fallback.interpretations())
                .anyMatch(interpretation -> interpretation.field().equals("variantRole")
                        && interpretation.kind() == InterpretationKind.DETERMINISTIC);
        assertThat(variant(clauseRows, "G-IND-01", "Preferred").effectiveVariantRole())
                .isEqualTo(PositionRole.PREFERRED);
        assertThat(variant(clauseRows, "G-LIAB-01", "Fallback 2").effectiveVariantRole())
                .isEqualTo(PositionRole.FALLBACK);
    }

    @Test
    void aClauseVariantTakesItsApplicabilityFromItsAppliesToColumn() throws Exception {
        var artifact = ingest("fixtures/import-quality-clause-library.xlsx", "g-clause-library.xlsx",
                ArtifactType.CLAUSE_LIBRARY);

        var row = variant(queryService.getCandidates(artifact.id()), "G-IND-01", "Fallback 1");

        assertThat(row.scope().sourceContractTypes())
                .containsExactlyInAnyOrder(ContractType.SWL.name(), ContractType.SAAS.name());
    }

    @Test
    void anEntryWhoseTopicColumnNamesItsTopicIsNotFlaggedAsUnplaced() throws Exception {
        var artifact = ingest("fixtures/import-quality-clause-library.xlsx", "g-clause-library.xlsx",
                ArtifactType.CLAUSE_LIBRARY);

        var row = variant(queryService.getCandidates(artifact.id()), "G-IND-01", "Fallback 1");

        assertThat(row.effectiveConceptKey()).isEqualTo("IP_INFRINGEMENT_INDEMNITY");
        assertThat(row.reviewReasons()).doesNotContain(ReviewReason.LOW_CLASSIFICATION_CONFIDENCE);
    }

    @Test
    void anApprovalAnswerBetweenTheThresholdsIsNotStatedAsNotRequired() throws Exception {
        provider.requiresApproval = 0.5;

        var termAndSurvival = guideEntry("Term and survival");

        assertThat(termAndSurvival.effectivePositions())
                .contains(PositionRole.PREFERRED, PositionRole.FALLBACK, PositionRole.ESCALATION);
        assertThat(termAndSurvival.effectiveRequiresApproval())
                .as("an unsure answer says nothing, not 'not required'")
                .isNull();
    }

    @Test
    void aClearApprovalAnswerIsStillRecorded() throws Exception {
        provider.requiresApproval = 0.95;
        assertThat(guideEntry("Term and survival").effectiveRequiresApproval()).isTrue();

        provider.requiresApproval = 0.05;
        assertThat(guideEntry("Term and survival").effectiveRequiresApproval()).isFalse();
    }

    @Test
    void aCalloutEntryHasAShortTitleAndNoPlaceholderColumn() throws Exception {
        var entry = guideEntry("DO NOT TRADE THIS BOUNDARY FOR SPEED");

        assertThat(entry.contentText()).startsWith("No live or identifiable fleet data");
        assertThat(entry.payload().columns()).isEmpty();
    }

    private KnowledgeCandidate guideEntry(String title) throws Exception {
        var artifact = ingest("fixtures/import-quality-nda-guide.docx", "company-negotiation-playbook.docx",
                ArtifactType.PLAYBOOK);
        return queryService.getCandidates(artifact.id()).stream()
                .filter(candidate -> title.equals(candidate.rawLabel()))
                .findFirst().orElseThrow();
    }

    private static KnowledgeCandidate variant(
            java.util.List<KnowledgeCandidate> candidates, String clauseId, String variant) {
        return candidates.stream()
                .filter(candidate -> clauseId.equals(candidate.payload().columns().get("Clause ID"))
                        && variant.equals(candidate.payload().columns().get("Variant")))
                .findFirst().orElseThrow();
    }

    private KnowledgeArtifact ingest(String fixture, String filename, ArtifactType type) throws Exception {
        var manifest = new ArtifactManifest(Set.of("NDA"), null, null, null, null, null, null, null, null);
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(stream).as("classpath resource %s", fixture).isNotNull();
            return ingestionService.ingest(filename, "application/octet-stream", type, manifest, stream);
        }
    }
}
