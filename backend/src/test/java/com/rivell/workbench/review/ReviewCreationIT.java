package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.ContractVersionNotFoundException;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.contracts.SemanticsNotFoundException;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.CorpusScriptedProvider;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.retrieval.RetrievalProperties;

@SpringBootTest
@Testcontainers
class ReviewCreationIT {

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
        com.rivell.workbench.intelligence.SemanticDecisionProvider fakeSemanticDecisionProvider() {
            return new CorpusScriptedProvider();
        }

        @Bean
        @Primary
        com.rivell.workbench.intelligence.GenerativeReasoningProvider fakeGenerativeProvider() {
            return new com.rivell.workbench.contracts.ContractEnrichmentIT.M5FakeGenerativeProvider();
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
                ContractIngestionService contractIngestionService,
                com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService) {
            return new M6EvidenceCorpus(ingestionService, reviewService, publicationService,
                    queryService, contractIngestionService, enrichmentService);
        }
    }

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
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));

        void use(UserIdentity identity) {
            current = identity;
        }

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    @Autowired
    MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @Autowired
    ModelBindingCatalog modelBindingCatalog;

    @Autowired
    RetrievalProperties retrievalProperties;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    @Test
    void creationFreezesSnapshotAndBuildsTypedPackPlan() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);

        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        assertThat(review.status()).isEqualTo(ReviewStatus.ANALYSIS_QUEUED);
        assertThat(review.contractVersionNumber()).isEqualTo(1);

        var persisted = reviewRepository.find(organizationId, review.id()).orElseThrow();
        assertThat(persisted.id()).isEqualTo(review.id());
        assertThat(persisted.contractId()).isEqualTo(corpus.contract().contractId());
        assertThat(persisted.contractVersionId()).isEqualTo(corpus.semantics().contractVersionId());
        assertThat(persisted.status()).isEqualTo(ReviewStatus.ANALYSIS_QUEUED);

        var snapshot = reviewRepository.findSnapshot(organizationId, review.id()).orElseThrow();
        assertThat(snapshot.knowledgePinNumber()).isEqualTo(corpus.latestPublicationNumber());
        assertThat(snapshot.knowledgePinNumber()).isPositive();
        assertThat(snapshot.contractPackKey()).isEqualTo(ReviewPackRegistry.MSA_PACK);
        assertThat(snapshot.packVersion()).isEqualTo(ReviewPackRegistry.VERSION);
        assertThat(snapshot.contractType()).isEqualTo(ContractType.MSA);
        assertThat(snapshot.ourSideRole()).isEqualTo(PartyRole.CUSTOMER);
        assertThat(snapshot.paperSource()).isEqualTo(PaperSource.THIRD_PARTY);
        assertThat(snapshot.reviewPipelineVersion()).isEqualTo(ReviewPipeline.VERSION);
        assertThat(snapshot.effectiveOn()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(snapshot.embeddingIndexVersion())
                .isEqualTo(retrievalProperties.indexVersion());
        assertThat(snapshot.embeddingModelIdentity()).isNull();
        var bindings = modelBindingCatalog.current();
        assertThat(snapshot.modelPolicy().decisionProviderId())
                .isEqualTo(bindings.decisionProvider().providerId());
        assertThat(snapshot.modelPolicy().generativeModelAlias())
                .isEqualTo(bindings.generativeProvider().modelAlias());

        var checks = reviewRepository.findPlanChecks(organizationId, review.id());
        assertThat(checks).extracting(ReviewPlanCheck::checkKey)
                .contains("PRESENCE:LIMITATION_OF_LIABILITY", "PRESENCE:CONFIDENTIALITY",
                        "PRESENCE:TERM_TERMINATION", "PRESENCE:INDEMNIFICATION",
                        "PRESENCE:GOVERNING_LAW");
        assertThat(checks).filteredOn(check -> check.family() == CheckFamily.CONCEPT_PRESENCE)
                .allSatisfy(check -> assertThat(check.expectation()).isNotNull());
        assertThat(checks).filteredOn(check -> check.family() == CheckFamily.CROSS_CLAUSE)
                .extracting(ReviewPlanCheck::checkKey)
                .containsExactlyInAnyOrder(
                        "CROSS:CONFIDENTIALITY_SURVIVAL:TERM_TERMINATION:CONFIDENTIALITY",
                        "CROSS:CARVE_OUT_CONSISTENCY:LIMITATION_OF_LIABILITY:INDEMNIFICATION");

        var clauseChecks = checks.stream()
                .filter(check -> check.family() == CheckFamily.CLAUSE_REVIEW)
                .toList();
        assertThat(clauseChecks).isNotEmpty();
        assertThat(clauseChecks).allSatisfy(check -> {
            assertThat(check.mechanism()).isEqualTo(CheckMechanism.LIABILITY_CAP);
            assertThat(check.concept()).isEqualTo(LegalConcept.LIMITATION_OF_LIABILITY);
            assertThat(corpus.semantics().clauses())
                    .extracting(ClauseSemantics::id)
                    .contains(check.subjectClauseId());
        });
    }

    @Test
    void packLessReviewRunsReducedPlanWithoutCompletenessChecks() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);

        var review = reviewService.create(corpus.contract().contractId(), 1, null);

        var snapshot = reviewRepository.findSnapshot(organizationId, review.id()).orElseThrow();
        assertThat(snapshot.contractPackKey()).isNull();
        assertThat(snapshot.packVersion()).isNull();

        var checks = reviewRepository.findPlanChecks(organizationId, review.id());
        assertThat(checks).isNotEmpty();
        assertThat(checks).allSatisfy(check ->
                assertThat(check.family()).isEqualTo(CheckFamily.CLAUSE_REVIEW));
    }

    @Test
    void explicitPackIsHonoredAndConflictsAreRejected() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);

        assertThatThrownBy(() -> reviewService.create(
                corpus.contract().contractId(), 1, "UNKNOWN_PACK"))
                .isInstanceOf(InvalidReviewConfigurationException.class);

        enrichmentService.enrich(corpus.contract().contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.NDA_PACK));

        var fromProfile = reviewService.create(corpus.contract().contractId(), 1, null);
        var snapshot = reviewRepository.findSnapshot(organizationId, fromProfile.id())
                .orElseThrow();
        assertThat(snapshot.contractPackKey()).isEqualTo(ReviewPackRegistry.NDA_PACK);

        assertThatThrownBy(() -> reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK))
                .isInstanceOf(InvalidReviewConfigurationException.class);
    }

    @Test
    void unenrichedContractCannotBeReviewed() throws Exception {
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));

        assertThatThrownBy(() -> reviewService.create(ingested.contractId(), 1, null))
                .isInstanceOf(SemanticsNotFoundException.class);
    }

    @Test
    void reviewsAreOrganizationScopedAndRequireCreatePermission() {
        var organizationId = organizationId();
        var operator = identityProvider.currentIdentity().userId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(corpus.contract().contractId(), 1, null);

        var otherOrganization = UUID.randomUUID();
        identityProvider.use(new UserIdentity(UUID.randomUUID(), otherOrganization, Set.of("ADMIN")));
        assertThat(reviewRepository.find(otherOrganization, review.id())).isEmpty();
        assertThat(reviewRepository.findSnapshot(otherOrganization, review.id())).isEmpty();
        assertThat(reviewRepository.findPlanChecks(otherOrganization, review.id())).isEmpty();
        assertThatThrownBy(() -> reviewService.create(corpus.contract().contractId(), 1, null))
                .isInstanceOf(ContractVersionNotFoundException.class);

        identityProvider.use(new UserIdentity(operator, organizationId, Set.of("VIEWER")));
        assertThatThrownBy(() -> reviewService.create(corpus.contract().contractId(), 1, null))
                .isInstanceOf(UnauthorizedReviewAccessException.class);

        identityProvider.use(new UserIdentity(operator, organizationId, Set.of("ADMIN")));
        assertThat(reviewRepository.find(organizationId, review.id())).isPresent();
    }
}
