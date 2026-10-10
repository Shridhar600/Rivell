package com.rivell.workbench.knowledge;

import com.rivell.workbench.taxonomy.CoreConcepts;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
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

import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * The one M3 production semantic flow against real PostgreSQL with a deterministic fake
 * provider: tenant scoping, state minimality, metadata, and controlled failures.
 */
@SpringBootTest
@Testcontainers
class ExtractionUnitConceptClassificationIT {

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
                () -> "target/test-documents/" + java.util.UUID.randomUUID());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIntelligenceConfiguration {

        @Bean
        @Primary
        ModelPolicy fakeModelPolicy() {
            return spec -> new ModelBinding("fake", "fake-model-latest");
        }

        @Bean
        @Primary
        SemanticDecisionProvider fakeSemanticDecisionProvider() {
            return new ScriptedSemanticDecisionProvider();
        }
    }

    static final class ScriptedSemanticDecisionProvider implements SemanticDecisionProvider {

        private volatile Map<String, Double> probabilities = Map.of(
                "topic_confidentiality_obligations", 0.94,
                "topic_term", 0.31,
                "topic_limitation_of_liability", 0.05,
                "topic_indemnification", 0.02,
                "topic_governing_law", 0.11);
        private volatile Map<String, String> choices = Map.of();
        private volatile RuntimeException failure;
        private volatile DecisionExecutionRequest lastRequest;

        void script(Map<String, Double> probabilities) {
            this.failure = null;
            this.probabilities = probabilities;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        DecisionExecutionRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            if (failure != null) {
                throw failure;
            }
            this.lastRequest = request;
            var answers = new LinkedHashMap<String, com.rivell.workbench.intelligence.SemanticAnswer>();
            for (var entry : request.pack().questions().entrySet()) {
                var question = entry.getValue();
                if (question instanceof com.rivell.workbench.intelligence.ChoiceQuestion choice) {
                    var selected = scriptedChoice(entry.getKey(), choice, request);
                    var probabilitiesMap = new LinkedHashMap<String, Double>();
                    for (var option : choice.criteria().keySet()) {
                        probabilitiesMap.put(option, option.equals(selected) ? 1.0 : 0.0);
                    }
                    answers.put(entry.getKey(), new com.rivell.workbench.intelligence.ChoiceAnswer(
                            selected, 1.0, probabilitiesMap));
                } else {
                    answers.put(entry.getKey(), new com.rivell.workbench.intelligence.YesNoAnswer(
                            probabilities.getOrDefault(entry.getKey(), 0.05)));
                }
            }
            return new ProviderExecutionResult(
                    answers, "fake-resolved-v2", new TokenUsage(180, 6));
        }

        private String scriptedChoice(
                String name, com.rivell.workbench.intelligence.ChoiceQuestion question,
                DecisionExecutionRequest request) {
            var scripted = choices.get(name);
            if (scripted != null) {
                return scripted;
            }
            if (name.equals("artifact_class") && request.state().payload() instanceof java.util.Map<?, ?> payload
                    && payload.get("declaredType") instanceof String declared) {
                return declared;
            }
            return question.criteria().keySet().iterator().next();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    ScriptedSemanticDecisionProvider fakeProvider;

    @org.springframework.beans.factory.annotation.Autowired
    KnowledgeIngestionService ingestionService;

    @org.springframework.beans.factory.annotation.Autowired
    ExtractionUnitConceptClassifier classifier;

    @org.springframework.beans.factory.annotation.Autowired
    KnowledgeQueryService queryService;

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
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), Set.of("ADMIN")));
        fakeProvider.script(Map.of(
                "topic_confidentiality_obligations", 0.94,
                "topic_term", 0.31,
                "topic_limitation_of_liability", 0.05,
                "topic_indemnification", 0.02,
                "topic_governing_law", 0.11));
    }

    @Test
    void classifiesUnitWithMinimalStateAndProvenance() throws Exception {
        var artifact = ingest("fixtures/clause-library.docx", "library.docx");
        var unit = firstTextualUnit(artifact);

        var classification = classifier.classify(artifact.id(), unit.id());

        assertThat(classification.unitId()).isEqualTo(unit.id());
        assertThat(classification.artifactId()).isEqualTo(artifact.id());
        assertThat(classification.proposedConcepts())
                .containsExactly(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS);
        assertThat(classification.noneMatched()).isFalse();
        assertThat(classification.conceptProbabilities().get(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS))
                .isEqualTo(0.94);

        assertThat(classification.taxonomyVersion()).isPositive();
        assertThat(classification.executions()).singleElement()
                .satisfies(execution -> assertThat(execution.specId()).isEqualTo("section-topics"));

        var request = fakeProvider.lastRequest();
        @SuppressWarnings("unchecked")
        var payload = (Map<String, Object>) request.state().payload();
        assertThat(payload).containsKey("text");
        assertThat(payload.get("text")).isEqualTo(unit.contentText());
        assertThat(payload).doesNotContainKeys("artifactId", "organizationId", "manifest",
                "kind", "title");
    }

    @Test
    void reportsNoProposalWhenNoConceptClearsTheThreshold() throws Exception {
        var artifact = ingest("fixtures/clause-library.docx", "library2.docx");
        var unit = firstTextualUnit(artifact);
        fakeProvider.script(Map.of(
                "topic_confidentiality_obligations", 0.40,
                "topic_term", 0.41,
                "topic_limitation_of_liability", 0.10,
                "topic_indemnification", 0.05,
                "topic_governing_law", 0.04));

        var classification = classifier.classify(artifact.id(), unit.id());

        assertThat(classification.proposedConcepts()).isEmpty();
        assertThat(classification.noneMatched()).isTrue();
        assertThat(classification.conceptProbabilities())
                .containsKeys(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS, CoreConcepts.TERM,
                        CoreConcepts.LIMITATION_OF_LIABILITY, CoreConcepts.INDEMNIFICATION,
                        CoreConcepts.GOVERNING_LAW);
    }

    @Test
    void rejectsClassificationAcrossOrganizations() throws Exception {
        var artifact = ingest("fixtures/clause-library.docx", "isolation.docx");
        var unit = firstTextualUnit(artifact);
        var ownerIdentity = identityProvider.current;

        identityProvider.use(new UserIdentity(
                ownerIdentity.userId(), java.util.UUID.randomUUID(), Set.of("ADMIN")));
        try {
            assertThatThrownBy(() -> classifier.classify(artifact.id(), unit.id()))
                    .isInstanceOf(KnowledgeUnitNotFoundException.class);
        } finally {
            identityProvider.use(ownerIdentity);
        }
    }

    @Test
    void translatesProviderFailureIntoControlledUnavailableState() throws Exception {
        var artifact = ingest("fixtures/clause-library.docx", "failure.docx");
        var unit = firstTextualUnit(artifact);
        fakeProvider.failWith(new com.rivell.workbench.intelligence.ProviderUnavailableException(
                "provider down"));

        assertThatThrownBy(() -> classifier.classify(artifact.id(), unit.id()))
                .isInstanceOf(ConceptClassificationUnavailableException.class);
    }

    @Test
    void rejectsUnitsWithoutText() throws Exception {
        var artifact = ingest("fixtures/knowledge-playbook.docx", "empty.docx");

        assertThatThrownBy(() -> classifier.classify(artifact.id(), tableUnitId(artifact)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no text to classify");
    }

    private java.util.UUID tableUnitId(KnowledgeArtifact artifact) {
        return queryService.getUnits(artifact.id()).stream()
                .filter(unit -> unit.kind() == ExtractionUnitKind.TABLE)
                .findFirst().orElseThrow()
                .id();
    }

    private KnowledgeArtifact ingest(String fixture, String filename) {
        try (var stream = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(stream).isNotNull();
            return ingestionService.ingest(
                    filename, "application/octet-stream", ArtifactType.CLAUSE_LIBRARY, null, stream);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ExtractionUnit firstTextualUnit(KnowledgeArtifact artifact) {
        return unitsOf(artifact).stream()
                .filter(unit -> unit.contentText() != null && !unit.contentText().isBlank())
                .findFirst().orElseThrow();
    }

    private List<ExtractionUnit> unitsOf(KnowledgeArtifact artifact) {
        return queryService.getUnits(artifact.id());
    }
}
