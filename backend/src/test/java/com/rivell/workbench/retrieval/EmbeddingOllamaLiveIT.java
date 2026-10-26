package com.rivell.workbench.retrieval;

import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.CoreConcepts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.EmbeddingService.EmbeddingExecution;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Live embedding verification against the locally running Ollama
 * embeddinggemma (768-dim) through the REAL adapter chain:
 * EmbeddingService → provider registry → OpenAI-compatible adapter → Ollama →
 * vectors → pgvector storage/query → labeled M6 EvidenceRequest.
 *
 * Opt-in only (OLLAMA_EMBEDDING_LIVE=1); normal verification stays network-free.
 * Engineering observation, NOT a benchmark: tiny sample, local model, no claims
 * about retrieval quality beyond the labeled fixture expectations.
 */
@EnabledIfEnvironmentVariable(named = "OLLAMA_EMBEDDING_LIVE", matches = "1")
@SpringBootTest
@Testcontainers
class EmbeddingOllamaLiveIT {

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
        registry.add("rivell.retrieval.indexing-enabled", () -> "true");
        // live Ollama transport (no real secret; Ollama ignores auth headers)
        registry.add("rivell.intelligence.embeddings.provider", () -> "openai-compatible");
        registry.add("rivell.intelligence.embeddings.base-url",
                () -> "http://localhost:11434/v1");
        registry.add("rivell.intelligence.embeddings.api-key", () -> "ollama");
        registry.add("rivell.intelligence.embeddings.model", () -> "embeddinggemma");
        registry.add("rivell.intelligence.embeddings.dimensions", () -> "768");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIntelligenceConfiguration {

        @Bean
        @Primary
        ModelPolicy fakeModelPolicy() {
            return asset -> asset instanceof com.rivell.workbench.intelligence.ReasoningSpec
                    ? new ModelBinding("fake", "fake-gen-model")
                    : new ModelBinding("fake", "fake-model-latest");
        }

        @Bean
        @Primary
        SemanticDecisionProvider fakeSemanticDecisionProvider() {
            return new CorpusScriptedProvider();
        }

        @Bean
        @Primary
        com.rivell.workbench.intelligence.GenerativeReasoningProvider fakeGenerativeProvider() {
            return new com.rivell.workbench.contracts.ContractEnrichmentIT.M5FakeGenerativeProvider();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        EvidenceApiServiceIT.MutableIdentity mutableIdentityProvider() {
            return new EvidenceApiServiceIT.MutableIdentity();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CorpusConfiguration {

        @Bean
        M6EvidenceCorpus m6EvidenceCorpus(
                com.rivell.workbench.knowledge.KnowledgeIngestionService ingestionService,
                com.rivell.workbench.knowledge.CandidateReviewService reviewService,
                com.rivell.workbench.knowledge.KnowledgePublicationService publicationService,
                com.rivell.workbench.knowledge.KnowledgeQueryService queryService,
                com.rivell.workbench.contracts.ContractIngestionService contractIngestionService,
                com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService) {
            return new M6EvidenceCorpus(ingestionService, reviewService, publicationService,
                    queryService, contractIngestionService, enrichmentService);
        }
    }

    @Autowired
    EvidenceApiServiceIT.MutableIdentity identityProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    EvidenceService evidenceService;

    @Autowired
    com.rivell.workbench.intelligence.EmbeddingService embeddingService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN")));
    }

    @Test
    void liveEmbeddingChainEndToEndWithLabeledEvidenceRequest() {
        // 1. direct EmbeddingService → adapter → Ollama: identity, dims, ordering, batch
        var startedAt = Instant.now();
        EmbeddingExecution execution = embeddingService.embed(List.of(
                "Supplier's total aggregate liability shall not exceed three times the "
                        + "fees paid in the preceding twelve months.",
                "The cap on the supplier's liability is three times the fees paid in the "
                        + "last year."));
        var elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
        var identity = embeddingService.modelIdentity();
        System.out.println("LIVE-EMBEDDING model=" + execution.resolvedModel()
                + " identity=" + identity
                + " inputs=" + execution.vectors().size()
                + " dims=" + execution.vectors().get(0).length
                + " usage=" + execution.metadata().usage()
                + " latencyMs=" + elapsedMs
                + " (sample=" + execution.vectors().size() + " inputs, local Ollama)");

        assertThat(execution.resolvedModel()).contains("embeddinggemma");
        assertThat(execution.vectors()).hasSize(2);
        assertThat(execution.vectors().get(0)).hasSize(768);
        assertThat(execution.vectors().get(1)).hasSize(768);

        // 2. labeled M6 evidence request with REAL embeddings + scripted Jev judgments
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var clauseId = corpus.semantics().clauses().stream()
                .filter(clause -> clause.primaryConcept().isPresent()
                        && clause.primaryConcept().get().equals(CoreConcepts.LIMITATION_OF_LIABILITY))
                .findFirst().orElseThrow().id();
        var bundle = evidenceService.build(EvidenceRequest.forClauseReview(
                organizationId, com.rivell.workbench.taxonomy.CoreConcepts
                        .LIMITATION_OF_LIABILITY,
                corpus.contract().contractId(), 1, clauseId,
                M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT, M6EvidenceCorpus.msaProfile(),
                LocalDate.of(2026, 9, 24), KnowledgePin.LATEST));

        // the vector lane ran on real Ollama vectors and the paraphrase precedent
        // (vector-strong/lexical-weak) is discovered through fusion
        assertThat(bundle.degradationWarnings())
                .noneMatch(flag -> flag.startsWith("VECTOR_LANE"));
        var paraphraseIds = corpus.allCandidatesOf(
                M6EvidenceCorpus.Fixture.PRECEDENT_PARAPHRASE).stream()
                .map(candidate -> candidate.id().toString())
                .toList();
        assertThat(paraphraseIds).isNotEmpty();
        assertThat(bundle.precedentItems())
                .anySatisfy(item -> {
                    assertThat(item.sourceCandidateId().toString()).isIn(paraphraseIds);
                    assertThat(item.laneInfo().vectorRank()).isNotNull();
                });
        System.out.println("LIVE-EMBEDDING bundle: sufficiency=" + bundle.sufficiency()
                + " precedents=" + bundle.precedentItems().size()
                + " degradation=" + bundle.degradationWarnings());
    }
}
