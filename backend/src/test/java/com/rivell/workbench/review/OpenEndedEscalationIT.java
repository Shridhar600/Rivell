package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class OpenEndedEscalationIT {

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

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    /** Exotic clause, policy published, deliberately no approved drafting. */
    private Review exoticReviewOnlyPolicy() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/m7-msa-exotic.docx"));
        var ingested = contractIngestionService.ingest("m7-msa-exotic.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        return reviewService.create(ingested.contractId(), 1, null);
    }

    @Test
    void openEndedEscalationProducesVerifiedMachineProposal() throws Exception {
        var organizationId = organizationId();
        var review = exoticReviewOnlyPolicy();

        var run = analysisService.analyze(organizationId, review.id());

        var openEnded = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT)
                .findFirst().orElseThrow();
        assertThat(openEnded.issueFamily()).isEqualTo(IssueFamily.OPEN_ENDED_LIABILITY);
        var payload = (OpenEndedAnalysisPayload) openEnded.payload();
        assertThat(payload.proposedLanguage()).contains("two times the fees");
        assertThat(payload.explanation()).isNotBlank();
        assertThat(payload.verificationOutcome()).isEqualTo("VERIFIED");
        assertThat(openEnded.generativeExecutionId()).isNotNull();
        assertThat(openEnded.resolvedModel()).isNotBlank();
        assertThat(openEnded.evidenceItemIds()).isNotEmpty();
        assertThat(openEnded.quality()).containsEntry("verificationOutcome", "VERIFIED");
        var execution = analysisRepository.findExecutions(organizationId, run.id()).get(0);
        assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
        assertThat(execution.detail()).containsEntry("openEnded", true);
    }

    @Test
    void generativeFailurePreservesUncertaintyWithoutFabrication() throws Exception {
        var organizationId = organizationId();
        var review = exoticReviewOnlyPolicy();
        generativeProvider.failOpenEnded = true;

        var run = analysisService.analyze(organizationId, review.id());

        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).noneMatch(signal ->
                signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT);
        assertThat(signals).anyMatch(signal ->
                signal.signalType() == SignalType.DEVIATION_UNCERTAIN
                        && ((DeviationUncertainPayload) signal.payload()).reason()
                                .equals("open_ended_generation_unavailable"));
        var execution = analysisRepository.findExecutions(organizationId, run.id()).get(0);
        assertThat(execution.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
        assertThat(execution.detail().get("degradedReason").toString())
                .contains("open_ended_generation_unavailable");
        assertThat(reviewRepositoryStatus(organizationId, review.id()))
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void unverifiedCandidateIsRetainedButMarkedUncertain() throws Exception {
        var organizationId = organizationId();
        var review = exoticReviewOnlyPolicy();
        scriptedProvider.verificationSatisfies = false;

        var run = analysisService.analyze(organizationId, review.id());

        var openEnded = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT)
                .findFirst().orElseThrow();
        var payload = (OpenEndedAnalysisPayload) openEnded.payload();
        assertThat(payload.proposedLanguage()).contains("two times the fees");
        assertThat(payload.verificationOutcome()).isEqualTo("UNCERTAIN");
        assertThat(openEnded.verificationOutcome()).isEqualTo("UNCERTAIN");
    }

    @Autowired
    LiabilityReviewIT.ReviewGenerativeProvider generativeProvider;

    private ReviewStatus reviewRepositoryStatus(UUID organizationId, UUID reviewId) {
        return reviewRepository.find(organizationId, reviewId).orElseThrow().status();
    }

    @Autowired
    ReviewRepository reviewRepository;
}
