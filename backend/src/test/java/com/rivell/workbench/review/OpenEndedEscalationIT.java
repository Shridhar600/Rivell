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

/**
 * The open-ended drafting path generalised from liability to any position: a walk-away
 * with no approved clause-library wording produces a verified machine candidate, a
 * failed or unverifiable candidate never becomes proposed language and the finding
 * escalates to a human instead.
 */
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
    FindingRepository findingRepository;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    LiabilityReviewIT.ReviewGenerativeProvider generativeProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @Autowired
    ReviewRepository reviewRepository;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        generativeProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    /** Uncapped clause, policy published, deliberately no approved drafting. */
    private Review uncappedReviewOnlyPolicy() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/m7-msa-uncapped.docx"));
        var ingested = contractIngestionService.ingest("m7-msa-uncapped.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        return reviewService.create(ingested.contractId(), 1, null);
    }

    private static RawReviewSignal openEnded(java.util.List<RawReviewSignal> signals) {
        return signals.stream()
                .filter(signal -> signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT)
                .findFirst().orElseThrow();
    }

    @Test
    void walkAwayWithoutDraftingProducesVerifiedMachineProposal() throws Exception {
        var organizationId = organizationId();
        var review = uncappedReviewOnlyPolicy();
        generativeProvider.reset();

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(generativeProvider.calls()).isEqualTo(1);
        var signals = analysisRepository.findSignals(organizationId, run.id());
        var openEnded = openEnded(signals);
        assertThat(openEnded.issueFamily()).isEqualTo(IssueFamily.POSITION_WALK_AWAY);
        var payload = (OpenEndedAnalysisPayload) openEnded.payload();
        assertThat(payload.proposedLanguage()).contains("two times the fees");
        assertThat(payload.explanation()).isNotBlank();
        assertThat(payload.verificationOutcome()).isEqualTo("VERIFIED");
        assertThat(openEnded.generativeExecutionId()).isNotNull();
        assertThat(openEnded.resolvedModel()).isNotBlank();
        assertThat(openEnded.evidenceItemIds()).isNotEmpty();
        assertThat(openEnded.quality()).containsEntry("verificationOutcome", "VERIFIED");
        var loi = analysisRepository.findExecutions(organizationId, run.id()).stream()
                .filter(execution -> execution.detail().containsKey("positions"))
                .filter(execution -> execution.detail().get("positions").toString()
                        .contains("WALK_AWAY"))
                .findFirst().orElseThrow();
        assertThat(loi.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
        assertThat(loi.detail()).containsEntry("openEnded", true);
        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction())
                .isEqualTo(ProposedAction.CONSIDER_GENERATED_LANGUAGE);
    }

    @Test
    void generationFailureEscalatesToHumanWithoutFabrication() throws Exception {
        var organizationId = organizationId();
        var review = uncappedReviewOnlyPolicy();
        generativeProvider.failOpenEnded = true;

        var run = analysisService.analyze(organizationId, review.id());

        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).noneMatch(signal ->
                signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT);
        assertThat(signals).anyMatch(signal ->
                signal.signalType() == SignalType.POLICY_DEVIATION
                        && ((PositionDeviationPayload) signal.payload()).category()
                                .equals(PolicyComparisonSpecs.WALK_AWAY));
        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
        assertThat(finding.proposedLanguage()).isNull();
        assertThat(finding.quality()).containsEntry("openEndedOutcome", "UNAVAILABLE");
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void outOfCreditOnASecondaryCallDegradesTheCheckWithItsOwnReason() throws Exception {
        var organizationId = organizationId();
        var review = uncappedReviewOnlyPolicy();
        generativeProvider.openEndedOutOfCredit = true;

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(analysisRepository.findExecutions(organizationId, run.id()))
                .anySatisfy(execution -> assertThat(execution.detail())
                        .containsEntry("degradedReason", "provider_out_of_credit"));
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void unverifiedCandidateIsRetainedButMarkedUncertain() throws Exception {
        var organizationId = organizationId();
        var review = uncappedReviewOnlyPolicy();
        scriptedProvider.verificationSatisfies = false;

        var run = analysisService.analyze(organizationId, review.id());

        var openEnded = openEnded(analysisRepository.findSignals(organizationId, run.id()));
        var payload = (OpenEndedAnalysisPayload) openEnded.payload();
        assertThat(payload.proposedLanguage()).contains("two times the fees");
        assertThat(payload.verificationOutcome()).isEqualTo("UNCERTAIN");
        assertThat(openEnded.verificationOutcome()).isEqualTo("UNCERTAIN");
        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
    }

    @Test
    void approvedDraftingPresentSuppressesGenerationEntirely() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/m7-msa-uncapped.docx"));
        var ingested = contractIngestionService.ingest("m7-msa-uncapped.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        var review = reviewService.create(ingested.contractId(), 1, null);
        generativeProvider.reset();

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(generativeProvider.calls()).isZero();
        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).noneMatch(signal ->
                signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT);
        assertThat(signals).anyMatch(signal ->
                signal.signalType() == SignalType.POLICY_DEVIATION
                        && ((PositionDeviationPayload) signal.payload()).category()
                                .equals(PolicyComparisonSpecs.WALK_AWAY));
        assertThat(signals).anyMatch(signal ->
                signal.signalType() == SignalType.APPROVED_DRAFTING_AVAILABLE);
    }

    @Test
    void ambiguousVerificationDemotesCandidateToUncertain() throws Exception {
        var organizationId = organizationId();
        var review = uncappedReviewOnlyPolicy();
        scriptedProvider.verificationSatisfiesProbability = 0.5;

        var run = analysisService.analyze(organizationId, review.id());

        var openEnded = openEnded(analysisRepository.findSignals(organizationId, run.id()));
        assertThat(((OpenEndedAnalysisPayload) openEnded.payload()).verificationOutcome())
                .isEqualTo("UNCERTAIN");
        assertThat(openEnded.verificationOutcome()).isEqualTo("UNCERTAIN");
        assertThat(openEnded.quality()).containsKey("verificationSatisfiesProbability");
    }

    @Test
    void ambiguousRiskAnswerFailsClosedToDisputed() throws Exception {
        var organizationId = organizationId();
        var review = uncappedReviewOnlyPolicy();
        scriptedProvider.verificationRiskProbability = 0.5;

        var run = analysisService.analyze(organizationId, review.id());

        var openEnded = openEnded(analysisRepository.findSignals(organizationId, run.id()));
        assertThat(openEnded.verificationOutcome()).isEqualTo("DISPUTED");
    }

    @Test
    void oversizedExplanationIsRejectedWithoutFabrication() throws Exception {
        var organizationId = organizationId();
        var review = uncappedReviewOnlyPolicy();
        generativeProvider.openEndedExplanation = "x".repeat(2500);

        var run = analysisService.analyze(organizationId, review.id());

        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).noneMatch(signal ->
                signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT);
        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
    }

    @Test
    void whitespaceNearCopyCandidateIsRejected() throws Exception {
        var organizationId = organizationId();
        var review = uncappedReviewOnlyPolicy();
        generativeProvider.openEndedProposed =
                "The liability of Supplier under this Agreement is   uncapped and unlimited, "
                        + "however arising.";

        var run = analysisService.analyze(organizationId, review.id());

        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).noneMatch(signal ->
                signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT);
        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
    }
}
