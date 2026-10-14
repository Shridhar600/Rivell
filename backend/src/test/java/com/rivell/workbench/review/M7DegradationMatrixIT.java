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
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * B22 failure/degradation matrix for rows not proven by the dedicated ITs: missing
 * approved drafting (machine language is generated instead), malformed generative
 * output (escalation, never fabrication), cross-clause provider failure, carve-out
 * NOT_COMPARABLE, and DISPUTED verification demotion. Every case must fail closed
 * (no fabricated state) and leave the review COMPLETED.
 */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class M7DegradationMatrixIT {

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
    ReviewRepository reviewRepository;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    LiabilityReviewIT.ReviewGenerativeProvider generativeProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new com.rivell.workbench.organization.UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        generativeProvider.reset();
    }

    @Test
    void draftingMissingStillProducesVerifiedMachineLanguage() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-uncapped.docx", null);
        scriptedProvider.reset();
        generativeProvider.reset();

        var run = analysisService.analyze(organizationId, review.id());

        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).extracting(RawReviewSignal::signalType)
                .contains(SignalType.POLICY_DEVIATION,
                        SignalType.OPEN_ENDED_ANALYSIS_RESULT)
                .doesNotContain(SignalType.APPROVED_DRAFTING_AVAILABLE);
        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction())
                .isEqualTo(ProposedAction.CONSIDER_GENERATED_LANGUAGE);
        assertThat(finding.proposedLanguage()).isNotBlank();
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.GENERATIVE);
        assertThat(generativeProvider.calls()).isEqualTo(1);
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void malformedGenerativeOutputEscalatesWithoutFabrication() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-uncapped.docx", null);
        generativeProvider.openEndedProposed = "";
        generativeProvider.openEndedExplanation = "";

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(analysisRepository.findSignals(organizationId, run.id()))
                .noneMatch(signal ->
                        signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT);
        assertThat(findingRepository.findFindings(organizationId, run.id()))
                .noneMatch(finding -> finding.proposedLanguage() != null);
        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void crossClauseProviderFailureDegradesToUncertainty() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-carveout-conflict.docx",
                ReviewPackRegistry.MSA_PACK);
        scriptedProvider.failConsistencyDecision = true;

        var run = analysisService.analyze(organizationId, review.id());

        var uncertain = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.signalType() == SignalType.CROSS_CLAUSE_UNCERTAIN)
                .findFirst().orElseThrow();
        assertThat(((DeviationUncertainPayload) uncertain.payload()).reason())
                .isEqualTo("cross_clause_decision_unavailable");
        var carveExecution = analysisRepository.findExecutions(organizationId, run.id()).stream()
                .filter(execution -> execution.detail().containsKey("degradedReason"))
                .findFirst().orElseThrow();
        assertThat(carveExecution.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
        assertThat(carveExecution.detail().get("degradedReason").toString())
                .contains("cross_clause_decision_unavailable");
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void carveOutNotComparableIsUncertaintyNotConflict() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-carveout-conflict.docx",
                ReviewPackRegistry.MSA_PACK);
        scriptedProvider.consistencyAnswerOverride = M7DecisionSpecs.NOT_COMPARABLE_CARVE;

        var run = analysisService.analyze(organizationId, review.id());

        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).noneMatch(signal ->
                signal.signalType() == SignalType.CROSS_CLAUSE_CONFLICT
                        && signal.issueFamily() == IssueFamily.CARVE_OUT_INCONSISTENCY);
        var uncertain = signals.stream()
                .filter(signal -> signal.signalType() == SignalType.CROSS_CLAUSE_UNCERTAIN)
                .findFirst().orElseThrow();
        assertThat(((DeviationUncertainPayload) uncertain.payload()).reason())
                .isEqualTo("carve_out_not_comparable");
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void carveOutClassificationUncertaintyReportsCauseAccurateReason() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-carveout-conflict.docx",
                ReviewPackRegistry.MSA_PACK);
        scriptedProvider.consistencyTopProbability = 0.5;

        var run = analysisService.analyze(organizationId, review.id());

        var uncertain = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.signalType() == SignalType.CROSS_CLAUSE_UNCERTAIN)
                .findFirst().orElseThrow();
        assertThat(((DeviationUncertainPayload) uncertain.payload()).reason())
                .isEqualTo("cross_clause_classification_uncertain");
        var carveExecution = analysisRepository.findExecutions(organizationId, run.id()).stream()
                .filter(execution -> execution.detail().containsKey("degradedReason"))
                .findFirst().orElseThrow();
        assertThat(carveExecution.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
        assertThat(carveExecution.detail().get("degradedReason"))
                .isEqualTo("cross_clause_classification_uncertain");
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void disputedVerificationDemotesTheAssembledFinding() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-uncapped.docx", null);
        scriptedProvider.verificationRiskProbability = 0.5;

        var run = analysisService.analyze(organizationId, review.id());

        var openEnded = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT)
                .findFirst().orElseThrow();
        assertThat(openEnded.verificationOutcome()).isEqualTo("DISPUTED");
        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily()
                        == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
        assertThat(finding.proposedLanguageSource()).isEqualTo(ProposedLanguageSource.NONE);
        assertThat(finding.claims()).noneMatch(claim ->
                claim.claimKind() == ClaimKind.GENERATIVE_EXPLANATION);
        assertThat(finding.quality()).containsEntry("verificationOutcome", "DISPUTED");
    }

    private void publishMainPolicyAndLibrary(UUID organizationId) {
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private Review enrichedReview(String fixture, String packKey) throws Exception {
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures", fixture));
        var ingested = contractIngestionService.ingest(fixture,
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US, packKey));
        return reviewService.create(ingested.contractId(), 1, null);
    }
}
