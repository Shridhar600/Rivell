package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
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

import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.intelligence.ModelBindings;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.CorpusScriptedProvider;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

@SpringBootTest
@Testcontainers
class ReviewAnalysisIT {

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
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    org.jobrunr.storage.StorageProvider storageProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    @Test
    void analysisCompletesReviewAndEnqueuesTheJobAfterCommit() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        var enqueued = storageProvider.getJobList(
                org.jobrunr.jobs.states.StateName.ENQUEUED,
                new org.jobrunr.storage.navigation.AmountRequest("updatedAt:DESC", 100));
        assertThat(enqueued).anyMatch(job ->
                job.getJobSignature().contains("ReviewAnalysisJobHandler"));

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);

        var checks = reviewRepository.findPlanChecks(organizationId, review.id());
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        assertThat(executions).hasSize(checks.size());
        var checkById = checks.stream().collect(java.util.stream.Collectors.toMap(
                ReviewPlanCheck::id, check -> check));
        assertThat(executions).allSatisfy(execution -> {
            var family = checkById.get(execution.planCheckId()).family();
            if (family == CheckFamily.CROSS_CLAUSE) {
                assertThat(execution.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
                assertThat(execution.detail()).containsKey("degradedReason");
            } else {
                assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
                assertThat(execution.detail()).isNotEmpty();
            }
        });
        assertThat(analysisRepository.countSignals(organizationId, run.id())).isZero();

        var progress = analysisService.progress(organizationId, review.id());
        assertThat(progress.reviewStatus()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(progress.plannedChecks()).isEqualTo(checks.size());
        assertThat(progress.completedChecks()).isPositive();
        assertThat(progress.degradedChecks()).isPositive();
        assertThat(progress.pendingChecks()).isZero();
        assertThat(progress.runningChecks()).isZero();
    }

    @Test
    void missingRequiredAndExpectedConceptsProduceAbsenceSignals() throws Exception {
        var organizationId = organizationId();
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.NDA, PartyRole.RECEIVING_PARTY,
                        PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(ingested.contractId(), 1, null);

        var run = analysisService.analyze(organizationId, review.id());

        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).extracting(RawReviewSignal::signalType,
                        RawReviewSignal::issueFamily, RawReviewSignal::absenceConcept)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(SignalType.MISSING_REQUIRED_CONCEPT,
                                IssueFamily.MISSING_REQUIRED_PROVISION,
                                LegalConcept.LIMITATION_OF_LIABILITY),
                        org.assertj.core.groups.Tuple.tuple(SignalType.EXPECTED_CONCEPT_ABSENT,
                                IssueFamily.EXPECTED_PROVISION_ABSENT,
                                LegalConcept.INDEMNIFICATION));
        assertThat(signals).allSatisfy(signal -> {
            assertThat(signal.subjectKind()).isEqualTo(FindingSubjectKind.ABSENCE);
            assertThat(signal.subjectHeadingNodeId()).isNull();
            assertThat(signal.payload()).isInstanceOf(PresencePayload.class);
        });

        var progress = analysisService.progress(organizationId, review.id());
        assertThat(progress.signals()).isEqualTo(2);
        assertThat(progress.reviewStatus()).isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void retryResumesTheSameRunWithoutDuplicatingSignals() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        var first = analysisService.analyze(organizationId, review.id());
        var executionsAfterFirst = analysisRepository.findExecutions(organizationId, first.id());
        var signalsAfterFirst = analysisRepository.countSignals(organizationId, first.id());

        var second = analysisService.analyze(organizationId, review.id());
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(analysisRepository.findExecutions(organizationId, first.id()))
                .hasSize(executionsAfterFirst.size());
        assertThat(analysisRepository.countSignals(organizationId, first.id()))
                .isEqualTo(signalsAfterFirst);

        var interrupted = executionsAfterFirst.stream()
                .filter(execution -> execution.status() == CheckExecutionStatus.COMPLETED)
                .findFirst().orElseThrow();
        jdbcClient.sql("""
                update review_check_execution set status = 'RUNNING', completed_at = null
                where id = :id
                """).param("id", interrupted.id()).update();
        jdbcClient.sql("""
                update review_analysis_run set status = 'RUNNING', completed_at = null
                where id = :id
                """).param("id", first.id()).update();
        jdbcClient.sql("""
                update review set status = 'ANALYZING', completed_at = null
                where id = :id
                """).param("id", review.id()).update();

        var resumed = analysisService.analyze(organizationId, review.id());
        assertThat(resumed.id()).isEqualTo(first.id());
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(analysisRepository.findExecutions(organizationId, first.id()))
                .hasSize(executionsAfterFirst.size());
        assertThat(analysisRepository.countSignals(organizationId, first.id()))
                .isEqualTo(signalsAfterFirst);
    }

    @Test
    void semanticsChangeAfterCreationDegradesAllChecks() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        enrichmentService.enrich(corpus.contract().contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.NDA_PACK));

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        assertThat(executions).isNotEmpty();
        assertThat(executions).allSatisfy(execution -> {
            assertThat(execution.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
            assertThat(execution.detail().get("degradedReason").toString())
                    .contains("semantics changed");
        });
        assertThat(analysisRepository.countSignals(organizationId, run.id())).isZero();
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void analysisIsOrganizationScoped() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        var otherOrganization = UUID.randomUUID();
        assertThatThrownBy(() -> analysisService.analyze(otherOrganization, review.id()))
                .isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> analysisService.progress(otherOrganization, review.id()))
                .isInstanceOf(ReviewNotFoundException.class);
        assertThat(reviewRepository.find(otherOrganization, review.id())).isEmpty();
        assertThat(analysisRepository.findExecutions(otherOrganization, UUID.randomUUID()))
                .isEmpty();
        assertThat(analysisRepository.findSignals(otherOrganization, UUID.randomUUID()))
                .isEmpty();
    }
}
