package com.rivell.workbench.review;

import com.rivell.workbench.taxonomy.CoreConcepts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.contracts.SemanticsDigest;
import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.ContractVersionNotFoundException;
import com.rivell.workbench.contracts.EnrichmentFailedException;
import com.rivell.workbench.contracts.EnrichmentInProgressException;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.contracts.SemanticsNotFoundException;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.intelligence.ModelBindings;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.CorpusScriptedProvider;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceService;
import com.rivell.workbench.retrieval.EvidenceSufficiency;
import com.rivell.workbench.retrieval.KnowledgePin;
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
        ModelBindingCatalog fakeModelBindingCatalog() {
            return () -> new ModelBindings(
                    new ModelBinding("fake", "fake-decision-model"),
                    new ModelBinding("fake", "fake-gen-model"));
        }

        @Bean
        @Primary
        ModelPolicy fakeModelPolicy(ModelBindingCatalog modelBindingCatalog) {
            return asset -> asset instanceof com.rivell.workbench.intelligence.ReasoningSpec
                    ? modelBindingCatalog.current().generativeProvider()
                    : modelBindingCatalog.current().decisionProvider();
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
                KnowledgeQueryService queryService,
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
    ReviewPlanBuilder reviewPlanBuilder;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @Autowired
    KnowledgeQueryService knowledgeQueryService;

    @Autowired
    EvidenceService evidenceService;

    @Autowired
    RetrievalProperties retrievalProperties;

    @Autowired
    JdbcClient jdbcClient;

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
        var before = LocalDate.now(ZoneOffset.UTC);
        var operator = identityProvider.currentIdentity().userId();

        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        assertThat(review.status()).isEqualTo(ReviewStatus.ANALYSIS_QUEUED);
        assertThat(review.contractVersionNumber()).isEqualTo(1);
        assertThat(review.createdBy()).isEqualTo(operator);

        var persisted = reviewRepository.find(organizationId, review.id()).orElseThrow();
        assertThat(persisted.id()).isEqualTo(review.id());
        assertThat(persisted.contractId()).isEqualTo(corpus.contract().contractId());
        assertThat(persisted.contractVersionId()).isEqualTo(corpus.semantics().contractVersionId());
        assertThat(persisted.status()).isEqualTo(ReviewStatus.ANALYSIS_QUEUED);
        assertThat(persisted.createdBy()).isEqualTo(operator);

        var snapshot = reviewRepository.findSnapshot(organizationId, review.id()).orElseThrow();
        assertThat(snapshot.knowledgePinNumber()).isEqualTo(corpus.latestPublicationNumber());
        assertThat(snapshot.knowledgePinNumber()).isPositive();
        assertThat(snapshot.contractPackKey()).isEqualTo(ReviewPackRegistry.MSA_PACK);
        assertThat(snapshot.packVersion()).isEqualTo(ReviewPackRegistry.VERSION);
        assertThat(snapshot.contractType()).isEqualTo(ContractType.MSA);
        assertThat(snapshot.ourSideRole()).isEqualTo(PartyRole.CUSTOMER);
        assertThat(snapshot.paperSource()).isEqualTo(PaperSource.THIRD_PARTY);
        assertThat(snapshot.reviewPipelineVersion()).isEqualTo(ReviewPipeline.VERSION);
        assertThat(snapshot.effectiveOn())
                .isBetween(before, LocalDate.now(ZoneOffset.UTC));
        assertThat(snapshot.embeddingIndexVersion())
                .isEqualTo(retrievalProperties.indexVersion());
        assertThat(snapshot.embeddingModelIdentity()).isNull();
        assertThat(snapshot.contractSemanticsId()).isEqualTo(corpus.semantics().id());
        assertThat(snapshot.semanticsPipelineVersion())
                .isEqualTo(corpus.semantics().pipelineVersion());
        assertThat(snapshot.semanticsDigest())
                .isEqualTo(SemanticsDigest.of(corpus.semantics()));
        assertThat(snapshot.modelPolicy().decisionProviderId()).isEqualTo("fake");
        assertThat(snapshot.modelPolicy().decisionModelAlias()).isEqualTo("fake-decision-model");
        assertThat(snapshot.modelPolicy().generativeProviderId()).isEqualTo("fake");
        assertThat(snapshot.modelPolicy().generativeModelAlias()).isEqualTo("fake-gen-model");
        // P6: engine v1 is removed; every new review is frozen on v2
        assertThat(snapshot.reviewEngine()).isEqualTo(ReviewEngine.V2);

        var checks = reviewRepository.findPlanChecks(organizationId, review.id());
        assertThat(checks).extracting(ReviewPlanCheck::ordinal)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, checks.size()).boxed().toList());
        assertThat(checks).filteredOn(check -> check.family() == CheckFamily.CONCEPT_PRESENCE)
                .extracting(ReviewPlanCheck::concept, ReviewPlanCheck::expectation)
                .containsExactlyInAnyOrder(
                        tuple(CoreConcepts.LIMITATION_OF_LIABILITY, CheckExpectation.REQUIRED),
                        tuple(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS, CheckExpectation.REQUIRED),
                        tuple(CoreConcepts.TERM, CheckExpectation.EXPECTED),
                        tuple(CoreConcepts.INDEMNIFICATION, CheckExpectation.EXPECTED),
                        tuple(CoreConcepts.GOVERNING_LAW, CheckExpectation.OPTIONAL));

        var headingNodeIds = corpus.semantics().clauses().stream()
                .map(ClauseSemantics::headingNodeId).toList();
        var clauseChecks = checks.stream()
                .filter(check -> check.family() == CheckFamily.CLAUSE_REVIEW)
                .toList();
        assertThat(clauseChecks).isNotEmpty();
        assertThat(clauseChecks).allSatisfy(check -> {
            assertThat(check.mechanism()).isEqualTo(CheckMechanism.POLICY_COMPARISON);
            assertThat(headingNodeIds).contains(check.subjectHeadingNodeId());
        });

        // Policy comparison is planned for every clause; the check records the effective
        // primary, else the first topic, else General (a clause with no topic is only triaged).
        assertThat(clauseChecks).extracting(ReviewPlanCheck::subjectHeadingNodeId)
                .containsExactlyInAnyOrderElementsOf(headingNodeIds);
        assertThat(clauseChecks).allSatisfy(check -> {
            var clause = corpus.semantics().clauses().stream()
                    .filter(candidate -> candidate.headingNodeId()
                            .equals(check.subjectHeadingNodeId()))
                    .findFirst().orElseThrow();
            var expected = clause.effectivePrimaryConcept()
                    .orElseGet(() -> clause.secondaryConcepts().isEmpty()
                            ? ClauseTopics.NO_TOPIC : clause.secondaryConcepts().getFirst());
            assertThat(check.concept()).isEqualTo(expected);
        });

        var clausesByConcept = corpus.semantics().clauses().stream()
                .filter(clause -> clause.effectivePrimaryConcept().isPresent())
                .collect(java.util.stream.Collectors.groupingBy(
                        clause -> clause.effectivePrimaryConcept().orElseThrow(),
                        java.util.stream.Collectors.mapping(
                                ClauseSemantics::headingNodeId, java.util.stream.Collectors.toList())));
        var crossChecks = checks.stream()
                .filter(check -> check.family() == CheckFamily.CROSS_CLAUSE)
                .toList();
        assertThat(crossChecks).extracting(ReviewPlanCheck::mechanism)
                .contains(CheckMechanism.CONFIDENTIALITY_SURVIVAL,
                        CheckMechanism.CARVE_OUT_CONSISTENCY);
        assertThat(crossChecks).allSatisfy(check -> assertThat(
                clausesByConcept.getOrDefault(check.concept(), java.util.List.of()))
                .contains(check.subjectHeadingNodeId()));
        assertThat(crossChecks.stream()
                .filter(check -> check.mechanism() == CheckMechanism.CONFIDENTIALITY_SURVIVAL)
                .count()).isEqualTo(
                        clausesByConcept.getOrDefault(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS,
                                java.util.List.of()).size());
        assertThat(crossChecks.stream()
                .filter(check -> check.mechanism() == CheckMechanism.CARVE_OUT_CONSISTENCY)
                .count()).isEqualTo(
                        clausesByConcept.getOrDefault(CoreConcepts.LIMITATION_OF_LIABILITY,
                                java.util.List.of()).size());

        var rebuilt = reviewPlanBuilder.build(
                review.id(), organizationId, snapshot, corpus.semantics());
        assertThat(rebuilt).extracting(ReviewPlanCheck::ordinal, ReviewPlanCheck::checkKey)
                .containsExactlyElementsOf(checks.stream()
                        .map(check -> tuple(check.ordinal(), check.checkKey()))
                        .toList());
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

    /**
     * M10A integration review: the key identifies one operation, and the pack is part of that
     * operation. Replaying a key with a different pack used to return the original review, which
     * handed the caller a review frozen against a playbook it did not select, and skipped pack
     * validation entirely. The replay must be refused.
     */
    @Test
    void aReplayWithADifferentContractPackIsRefused() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var key = UUID.randomUUID();

        var first = reviewService.create(corpus.contract().contractId(), 1,
                ReviewPackRegistry.MSA_PACK, key);

        assertThatThrownBy(() -> reviewService.create(corpus.contract().contractId(), 1,
                ReviewPackRegistry.NDA_PACK, key))
                .as("a key replayed against a different pack must not return the original review")
                .isInstanceOf(IdempotencyKeyReusedException.class);

        // The refusal must not have created a second review, and the canonical one must be intact.
        assertThat(reviewRepository.findByClientRequestId(organizationId, key))
                .get()
                .extracting(Review::id)
                .isEqualTo(first.id());
        assertThat(reviewRepository.findSnapshot(organizationId, first.id()).orElseThrow()
                .contractPackKey()).isEqualTo(ReviewPackRegistry.MSA_PACK);

        // Replaying the original pack unchanged still returns the canonical review.
        assertThat(reviewService.create(corpus.contract().contractId(), 1,
                ReviewPackRegistry.MSA_PACK, key).id()).isEqualTo(first.id());
    }

    /**
     * M10 / DW-020: review creation had no client-supplied key, so a retried creation produced a
     * second review with a second frozen snapshot and a second full analysis. The retry must return
     * the original review, and the key must never resolve another organisation's review.
     */
    @Test
    void aRetriedCreationWithTheSameClientRequestIdReturnsTheOriginalReview() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var key = UUID.randomUUID();

        var first = reviewService.create(corpus.contract().contractId(), 1,
                ReviewPackRegistry.MSA_PACK, key);
        var second = reviewService.create(corpus.contract().contractId(), 1,
                ReviewPackRegistry.MSA_PACK, key);

        assertThat(second.id()).as("a retried creation must not produce a second review")
                .isEqualTo(first.id());
        assertThat(jdbcClient.sql("""
                select count(*) from review
                where organization_id = :organizationId and contract_id = :contractId
                """)
                .param("organizationId", organizationId)
                .param("contractId", corpus.contract().contractId())
                .query(Integer.class).single())
                .as("exactly one review row must exist for the contract")
                .isEqualTo(1);

        // Without a key, two creations are two independent reviews: the key is opt-in.
        var unkeyed = reviewService.create(corpus.contract().contractId(), 1,
                ReviewPackRegistry.MSA_PACK, null);
        assertThat(unkeyed.id()).isNotEqualTo(first.id());

        // The same key in another organisation must not resolve to this review.
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(),
                Set.of("ADMIN")));
        var foreignCorpus = corpusBuilder.buildMainCorpus(organizationId());
        var foreign = reviewService.create(foreignCorpus.contract().contractId(), 1,
                ReviewPackRegistry.MSA_PACK, key);
        assertThat(foreign.id())
                .as("a key must never resolve another organisation's review")
                .isNotEqualTo(first.id());
    }

    /**
     * M10 review finding: the idempotency pre-check cannot observe a concurrent identical request, so
     * two simultaneous submits both missed it. The unique index keeps one review, but the loser used
     * to surface a 500 - the worst possible answer to a client that asked for idempotent creation,
     * because it has no way to learn that the review it wanted now exists.
     */
    @Test
    void concurrentIdenticalCreationsConvergeOnOneReview() throws Exception {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var key = UUID.randomUUID();

        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Callable<Review>> tasks = List.of(
                () -> {
                    start.await();
                    return reviewService.create(corpus.contract().contractId(), 1,
                            ReviewPackRegistry.MSA_PACK, key);
                },
                () -> {
                    start.await();
                    return reviewService.create(corpus.contract().contractId(), 1,
                            ReviewPackRegistry.MSA_PACK, key);
                });
        var futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        var reviews = new java.util.ArrayList<Review>();
        for (var future : futures) {
            reviews.add(future.get());
        }
        executor.shutdown();

        assertThat(reviews.get(1).id())
                .as("a concurrent loser must receive the winner's review, not an error")
                .isEqualTo(reviews.get(0).id());
        assertThat(jdbcClient.sql("""
                select count(*) from review
                where organization_id = :organizationId and client_request_id = :key
                """)
                .param("organizationId", organizationId)
                .param("key", key)
                .query(Integer.class).single())
                .as("exactly one review may carry the key")
                .isEqualTo(1);
    }

    @Test
    void reviewCreationRejectsEnrichmentThatIsNotReady() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var versionId = corpus.semantics().contractVersionId();

        updateEnrichmentStatus(versionId, organizationId, "FAILED");
        assertThatThrownBy(() -> reviewService.create(corpus.contract().contractId(), 1, null))
                .isInstanceOf(EnrichmentFailedException.class);

        updateEnrichmentStatus(versionId, organizationId, "ENRICHING");
        assertThatThrownBy(() -> reviewService.create(corpus.contract().contractId(), 1, null))
                .isInstanceOf(EnrichmentInProgressException.class);

        updateEnrichmentStatus(versionId, organizationId, "READY");
        assertThat(reviewService.create(corpus.contract().contractId(), 1, null)).isNotNull();
    }

    @Test
    void reviewCreationRequiresExistingContractVersion() {
        assertThatThrownBy(() -> reviewService.create(UUID.randomUUID(), 1, null))
                .isInstanceOf(ContractVersionNotFoundException.class);

        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        assertThatThrownBy(() -> reviewService.create(corpus.contract().contractId(), 99, null))
                .isInstanceOf(ContractVersionNotFoundException.class);
    }

    @Test
    void reviewCreatedWithoutPublishedKnowledgePinsEmptyAndStaysPinned() throws Exception {
        var organizationId = organizationId();
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        var profile = new ReviewProfile(ContractType.NDA, PartyRole.RECEIVING_PARTY,
                PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US);
        var semantics = enrichmentService.enrich(ingested.contractId(), 1, profile);
        assertThat(knowledgeQueryService.maxPublicationNumber()).isZero();

        var review = reviewService.create(ingested.contractId(), 1, null);
        var snapshot = reviewRepository.findSnapshot(organizationId, review.id()).orElseThrow();
        assertThat(snapshot.knowledgePinNumber()).isZero();
        assertThat(snapshot.applicableKnowledge()).isEmpty();

        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_NDA,
                M6EvidenceCorpus.ndaManifest(), null);
        assertThat(knowledgeQueryService.maxPublicationNumber()).isEqualTo(1);
        assertThat(reviewRepository.findSnapshot(organizationId, review.id()).orElseThrow()
                .knowledgePinNumber()).isZero();

        var confidentiality = semantics.clauses().stream()
                .filter(clause -> clause.effectivePrimaryConcept()
                        .filter(concept -> concept.equals(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS)).isPresent())
                .findFirst().orElseThrow();
        var bundle = evidenceService.build(EvidenceRequest.forClauseReview(
                organizationId, CoreConcepts.CONFIDENTIALITY_OBLIGATIONS, ingested.contractId(), 1,
                confidentiality.id(), confidentiality.contentText(), profile,
                snapshot.effectiveOn(), KnowledgePin.none()));
        assertThat(bundle.policyItems()).isEmpty();
        assertThat(bundle.sufficiency()).isEqualTo(EvidenceSufficiency.POLICY_MISSING);
    }

    @Test
    void snapshotPinsSemanticsGenerationAndStableSubjects() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var snapshot = reviewRepository.findSnapshot(organizationId, review.id()).orElseThrow();
        var checks = reviewRepository.findPlanChecks(organizationId, review.id());

        enrichmentService.enrich(corpus.contract().contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.NDA_PACK));
        var refreshed = enrichmentService.getSemantics(corpus.contract().contractId(), 1);

        assertThat(SemanticsDigest.of(refreshed)).isNotEqualTo(snapshot.semanticsDigest());
        var headingNodeIds = refreshed.clauses().stream()
                .map(ClauseSemantics::headingNodeId).toList();
        var subjects = checks.stream()
                .map(ReviewPlanCheck::subjectHeadingNodeId)
                .filter(Objects::nonNull)
                .toList();
        assertThat(headingNodeIds).containsAll(subjects);
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

    private void updateEnrichmentStatus(UUID versionId, UUID organizationId, String status) {
        jdbcClient.sql("""
                update contract_semantics set enrichment_status = :status,
                    enrichment_failure_reason = :reason
                where contract_version_id = :versionId and organization_id = :organizationId
                """)
                .param("status", status)
                // M10: FAILED is schema-coupled to a structured reason, exactly as the service does.
                .param("reason", "FAILED".equals(status) ? "INTERNAL" : null)
                .param("versionId", versionId)
                .param("organizationId", organizationId)
                .update();
    }
}
