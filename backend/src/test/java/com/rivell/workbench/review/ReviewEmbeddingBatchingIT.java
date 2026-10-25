package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.intelligence.EmbeddingBatch;
import com.rivell.workbench.intelligence.EmbeddingProvider;
import com.rivell.workbench.intelligence.EmbeddingRequest;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.FakeEmbeddingProvider;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/** B1: one review embeds its distinct clause texts in one batch, not one call per lane/clause. */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class ReviewEmbeddingBatchingIT {

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
        registry.add("rivell.intelligence.embeddings.provider", () -> "counting");
        registry.add("rivell.intelligence.embeddings.api-key", () -> "fake-key");
        registry.add("rivell.intelligence.embeddings.model", () -> "fake-embedding-model");
        registry.add("rivell.intelligence.embeddings.dimensions", () -> "8");
        registry.add("rivell.retrieval.indexing-enabled", () -> "true");
    }

    static final class CountingEmbeddings implements EmbeddingProvider {

        private final FakeEmbeddingProvider delegate = new FakeEmbeddingProvider();
        final List<List<String>> batches = new CopyOnWriteArrayList<>();
        private volatile boolean recording;

        @Override
        public String id() {
            return "counting";
        }

        @Override
        public EmbeddingBatch embed(EmbeddingRequest request) {
            if (recording) {
                batches.add(new ArrayList<>(request.inputs()));
            }
            return delegate.embed(request);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingEmbeddingConfiguration {

        @Bean
        @Primary
        CountingEmbeddings countingEmbeddings() {
            return new CountingEmbeddings();
        }
    }

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    CountingEmbeddings embeddings;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void aThreeOrFourClauseReviewMakesOneEmbeddingRequestAndReusesIt() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/pc-contract.docx"));
        var ingested = contractIngestionService.ingest("pc-contract.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        var review = reviewService.create(ingested.contractId(), 1, null);

        embeddings.batches.clear();
        embeddings.recording = true;
        analysisService.analyze(organizationId, review.id());

        assertThat(embeddings.batches).as("one batch for the review").hasSize(1);
        var inputs = embeddings.batches.get(0);
        assertThat(inputs).doesNotHaveDuplicates();
        assertThat(inputs.size()).isGreaterThanOrEqualTo(3);
    }
}
