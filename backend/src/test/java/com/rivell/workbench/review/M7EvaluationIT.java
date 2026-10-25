package com.rivell.workbench.review;

import com.rivell.workbench.taxonomy.CoreConcepts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
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

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.FactCorrection;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * B20 eval harness: runs the labeled corpus scenarios (spec
 * {@code 2026-09-25-m7-labeled-review-corpus.md}) through the real pipeline and
 * records engineering fixture metrics. This is an observation over synthetic
 * fixtures with scripted providers — NOT calibration and NOT a legal benchmark.
 * Each scenario asserts its labeled expectations (issue families, unexpected and
 * missing findings, evidence-link resolution, source/absence mapping, decision and
 * generation counts) and appends one metrics line.
 */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class M7EvaluationIT {

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

    private static final List<EvalLine> RESULTS = new CopyOnWriteArrayList<>();

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
    ContractEnrichmentService enrichmentService;

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
    void s1CleanPreferredAndS5OptionalAbsent() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-clean.docx",
                msaProfile(ReviewPackRegistry.MSA_PACK));
        resetProviders();

        var start = System.nanoTime();
        var run = analysisService.analyze(organizationId, review.id());
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        assertThat(generativeProvider.calls()).isZero();
        assertThat(scriptedProvider.policyComparisonDecisions()).isGreaterThanOrEqualTo(1);
        var optionalCheck = reviewRepository.findPlanChecks(organizationId, review.id()).stream()
                .filter(check -> "PRESENCE:GOVERNING_LAW".equals(check.checkKey()))
                .findFirst().orElseThrow();
        assertThat(optionalCheck.expectation()).isEqualTo(CheckExpectation.OPTIONAL);
        assertThat(analysisRepository.findExecution(
                organizationId, run.id(), optionalCheck.id()).orElseThrow().signalCount())
                .isZero();
        assertThat(analysisRepository.findSignals(organizationId, run.id())).noneMatch(signal ->
                signal.concept().equals(CoreConcepts.GOVERNING_LAW)
                        || signal.absenceConcept().equals(CoreConcepts.GOVERNING_LAW));
        assertThat(analysisRepository.findExecutions(organizationId, run.id()))
                .allSatisfy(execution -> assertThat(execution.status())
                        .isEqualTo(CheckExecutionStatus.COMPLETED));
        record("S1+S5 clean preferred, optional absent", organizationId, review.id(),
                Set.of(), 0, 0, elapsed);
    }

    @Test
    void s2AndS11BoundedDeviationConsolidatesSupportingSignals() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-fallback.docx", msaProfile(null));
        resetProviders();

        var start = System.nanoTime();
        var run = analysisService.analyze(organizationId, review.id());
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        var findings = findingRepository.findFindings(organizationId, run.id());
        var finding = findings.stream().filter(candidate -> candidate.issueFamily()
                == IssueFamily.POSITION_NEEDS_FALLBACK).findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.USE_APPROVED_FALLBACK);
        assertThat(finding.proposedLanguage()).contains("two times the fees");
        assertThat(finding.signalIds()).hasSize(3);
        assertThat(generativeProvider.calls()).isZero();
        record("S2+S11 beyond fallback + approved drafting + escalation, one finding",
                organizationId, review.id(),
                Set.of(IssueFamily.POSITION_NEEDS_FALLBACK), 1, 0, elapsed);
    }

    @Test
    void s3MissingRequiredAndExpectedAbsence() throws Exception {
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

        resetProviders();

        var start = System.nanoTime();
        var run = analysisService.analyze(organizationId, review.id());
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        var findings = findingRepository.findFindings(organizationId, run.id());
        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.subjectKind()).isEqualTo(FindingSubjectKind.ABSENCE);
            assertThat(finding.subjectHeadingNodeId()).isNull();
            assertThat(finding.absenceConcept()).isNotNull();
        });
        // P6/v2: the whole-contract read runs once and names nothing; no other generative call
        assertThat(generativeProvider.spottingCalls).hasValue(1);
        assertThat(generativeProvider.calls()).isEqualTo(1);
        record("S3 required + expected absence findings", organizationId, review.id(),
                Set.of(IssueFamily.MISSING_REQUIRED_PROVISION,
                        IssueFamily.EXPECTED_PROVISION_ABSENT),
                2, 0, elapsed);
    }

    @Test
    void s6NoPublishedPositionMakesNoCallAndNoFinding() throws Exception {
        var organizationId = organizationId();
        var review = enrichedReview("m7-msa-preferred.docx", msaProfile(null));

        resetProviders();

        var start = System.nanoTime();
        var run = analysisService.analyze(organizationId, review.id());
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        assertThat(scriptedProvider.policyComparisonDecisions()).isZero();
        // P6/v2: the whole-contract read runs once and names nothing; no other generative call
        assertThat(generativeProvider.spottingCalls).hasValue(1);
        assertThat(generativeProvider.calls()).isEqualTo(1);
        assertThat(findingRepository.findFindings(organizationId, run.id())).isEmpty();
        record("S6 no published position, no call and no inference",
                organizationId, review.id(), Set.of(), 0, 0, elapsed);
    }

    @Test
    void s7TwoApplicablePositionsAreEachJudgedInOneCall() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_AMBIGUOUS,
                M6EvidenceCorpus.policyManifest(java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2026, 12, 31)),
                null);
        var review = enrichedReview("m7-msa-preferred.docx", msaProfile(null));

        resetProviders();

        var start = System.nanoTime();
        var run = analysisService.analyze(organizationId, review.id());
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        assertThat(scriptedProvider.policyComparisonCalls())
                .anySatisfy(call -> assertThat(call.questionKeys()).hasSize(2));
        assertThat(generativeProvider.calls()).isZero();
        assertThat(findingRepository.findFindings(organizationId, run.id())).isEmpty();
        record("S7 two positions judged independently, no ambiguity finding",
                organizationId, review.id(), Set.of(), 0, 0, elapsed);
    }

    @Test
    void s8IncompatiblePrecedentNeverOverridesPolicy() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.buildMainCorpus(organizationId);
        var review = enrichedReview("m7-msa-uncapped.docx", msaProfile(null));

        resetProviders();

        var start = System.nanoTime();
        var run = analysisService.analyze(organizationId, review.id());
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        var signals = analysisRepository.findSignals(organizationId, run.id());
        var deviation = signals.stream()
                .filter(signal -> signal.signalType() == SignalType.POLICY_DEVIATION)
                .findFirst().orElseThrow();
        assertThat(((PositionDeviationPayload) deviation.payload()).category())
                .isEqualTo(PolicyComparisonSpecs.WALK_AWAY);
        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily()
                        == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(finding.reviewClassification()).isEqualTo(FindingClassification.BLOCKING);
        assertThat(finding.claims()).anyMatch(claim -> claim.claimKind() == ClaimKind.POLICY_BASIS);
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.USE_APPROVED_FALLBACK);
        // P6/v2: the whole-contract read runs once and names nothing; no other generative call
        assertThat(generativeProvider.spottingCalls).hasValue(1);
        assertThat(generativeProvider.calls()).isEqualTo(1);
        record("S8 walk-away grounded in policy, precedent not policy",
                organizationId, review.id(),
                Set.of(IssueFamily.POSITION_WALK_AWAY), 1, 0, elapsed);
    }

    @Test
    void s9SurvivalCoverageGapIsDeterministic() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-survival-gap.docx", msaProfile(
                ReviewPackRegistry.MSA_PACK));

        resetProviders();

        var start = System.nanoTime();
        var run = analysisService.analyze(organizationId, review.id());
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        var gap = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.issueFamily() == IssueFamily.SURVIVAL_COVERAGE_GAP)
                .findFirst().orElseThrow();
        assertThat(gap.signalType()).isEqualTo(SignalType.CROSS_CLAUSE_CONFLICT);
        assertThat(gap.jevExecutionId()).isNull();
        assertThat(generativeProvider.calls()).isZero();
        record("S9 survival coverage gap", organizationId, review.id(),
                Set.of(IssueFamily.POSITION_NEEDS_FALLBACK,
                        IssueFamily.SURVIVAL_COVERAGE_GAP),
                2, 0, elapsed);
    }

    @Test
    void s10TwoIssueFamiliesStaySeparateFindings() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-carveout-conflict.docx", msaProfile(
                ReviewPackRegistry.MSA_PACK));

        resetProviders();

        var start = System.nanoTime();
        var run = analysisService.analyze(organizationId, review.id());
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        assertThat(analysisRepository.findSignals(organizationId, run.id()))
                .as("the clause states its survival, so DW-100 leaves no gap")
                .noneMatch(signal -> signal.issueFamily() == IssueFamily.SURVIVAL_COVERAGE_GAP);
        var findings = findingRepository.findFindings(organizationId, run.id());
        assertThat(findings).extracting(Finding::issueFamily)
                .contains(IssueFamily.POSITION_NEEDS_FALLBACK,
                        IssueFamily.CARVE_OUT_INCONSISTENCY);
        assertThat(findings.stream().filter(finding ->
                finding.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK
                        || finding.issueFamily() == IssueFamily.CARVE_OUT_INCONSISTENCY))
                .hasSize(2);
        assertThat(generativeProvider.calls()).isZero();
        record("S10 same clause, two issue families, no overmerge",
                organizationId, review.id(),
                Set.of(IssueFamily.POSITION_NEEDS_FALLBACK,
                        IssueFamily.CARVE_OUT_INCONSISTENCY),
                2, 0, elapsed);
    }

    @Test
    void s12GenerativeEscalationProducesVerifiedProposal() throws Exception {
        var organizationId = organizationId();
        publishMainPolicy(organizationId);
        var review = enrichedReview("m7-msa-uncapped.docx", msaProfile(null));

        resetProviders();

        var start = System.nanoTime();
        var run = analysisService.analyze(organizationId, review.id());
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        assertThat(generativeProvider.calls()).isEqualTo(1);
        var openEnded = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT)
                .findFirst().orElseThrow();
        assertThat(openEnded.verificationOutcome()).isEqualTo("VERIFIED");
        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.CONSIDER_GENERATED_LANGUAGE);
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.GENERATIVE);
        record("S12 generative escalation verified", organizationId, review.id(),
                Set.of(IssueFamily.POSITION_WALK_AWAY), 1, 1, elapsed);
    }

    @Test
    void s13GenerativeFailureNeverFabricates() throws Exception {
        var organizationId = organizationId();
        publishMainPolicy(organizationId);
        var review = enrichedReview("m7-msa-uncapped.docx", msaProfile(null));
        resetProviders();
        generativeProvider.failOpenEnded = true;

        var start = System.nanoTime();
        var run = analysisService.analyze(organizationId, review.id());
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        assertThat(generativeProvider.calls()).isEqualTo(1);
        assertThat(analysisRepository.findSignals(organizationId, run.id()))
                .noneMatch(signal ->
                        signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT);
        assertThat(findingRepository.findFindings(organizationId, run.id()))
                .noneMatch(finding -> finding.proposedLanguage() != null);
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
        record("S13 generative failure escalates without fabrication",
                organizationId, review.id(),
                Set.of(IssueFamily.POSITION_WALK_AWAY), 1, 1, elapsed);
    }

    @Test
    void s14CorrectedClauseSemanticsDriveTheReview() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var contractId = corpus.contract().contractId();
        var liability = enrichmentService.getSemantics(contractId, 1).clauses().stream()
                .filter(clause -> clause.primaryConcept()
                        .map(concept -> concept.equals(CoreConcepts.LIMITATION_OF_LIABILITY))
                        .orElse(false))
                .findFirst().orElseThrow();

        var machineReview = reviewService.create(contractId, 1, ReviewPackRegistry.MSA_PACK);
        resetProviders();
        var machineStart = System.nanoTime();
        var machineRun = analysisService.analyze(organizationId, machineReview.id());
        var machineElapsed = (System.nanoTime() - machineStart) / 1_000_000;
        var machineDeviation = analysisRepository.findSignals(organizationId, machineRun.id())
                .stream().filter(signal -> signal.signalType() == SignalType.POLICY_DEVIATION)
                .findFirst().orElseThrow();
        assertThat(((PositionDeviationPayload) machineDeviation.payload()).category())
                .isEqualTo(PolicyComparisonSpecs.BEYOND_FALLBACK);
        record("S14 machine 3x cap deviates", organizationId, machineReview.id(),
                Set.of(IssueFamily.POSITION_NEEDS_FALLBACK), 1, 0, machineElapsed);

        enrichmentService.correctClause(contractId, 1, liability.id(),
                FactCorrection.ofPrimary(CoreConcepts.GOVERNING_LAW));
        var correctedReview = reviewService.create(contractId, 1, ReviewPackRegistry.MSA_PACK);
        resetProviders();
        var correctedStart = System.nanoTime();
        var correctedRun = analysisService.analyze(organizationId, correctedReview.id());
        var correctedElapsed = (System.nanoTime() - correctedStart) / 1_000_000;
        // DW-085/v2: no position is paired by the corrected topic any more, and the text search
        // must not bring the removed topic's positions back: the correction stands
        var correctedDeviations = analysisRepository.findSignals(organizationId, correctedRun.id())
                .stream().filter(signal -> signal.signalType() == SignalType.POLICY_DEVIATION)
                .toList();
        assertThat(correctedDeviations).isEmpty();
        record("S14 corrected concept removes the topic match; the search does not bring it back",
                organizationId, correctedReview.id(),
                Set.of(), 0, 0, correctedElapsed);

        enrichmentService.correctClause(contractId, 1, liability.id(),
                FactCorrection.ofPrimary(CoreConcepts.LIMITATION_OF_LIABILITY));
        var restoredReview = reviewService.create(contractId, 1, ReviewPackRegistry.MSA_PACK);
        resetProviders();
        var restoredStart = System.nanoTime();
        var restoredRun = analysisService.analyze(organizationId, restoredReview.id());
        var restoredElapsed = (System.nanoTime() - restoredStart) / 1_000_000;
        var restoredDeviation = analysisRepository.findSignals(organizationId, restoredRun.id())
                .stream().filter(signal -> signal.signalType() == SignalType.POLICY_DEVIATION)
                .findFirst().orElseThrow();
        assertThat(((PositionDeviationPayload) restoredDeviation.payload()).category())
                .isEqualTo(PolicyComparisonSpecs.BEYOND_FALLBACK);
        record("S14 restored concept deviates again", organizationId, restoredReview.id(),
                Set.of(IssueFamily.POSITION_NEEDS_FALLBACK), 1, 0, restoredElapsed);
    }

    @AfterAll
    static void printMetrics() {
        var totals = new int[7];
        var out = new StringBuilder();
        out.append("\n===== M7 EVAL HARNESS (engineering fixture observation; NOT calibration) =====\n")
                .append("scenario | findings | unexpected | missing | decisions | generations | verificationFailures | evidenceLinks | ms\n");
        for (var line : RESULTS.stream().sorted((a, b) -> a.scenario().compareTo(b.scenario()))
                .toList()) {
            out.append(line.format()).append('\n');
            totals[0] += line.findings();
            totals[1] += line.unexpected();
            totals[2] += line.missing();
            totals[3] += line.decisions();
            totals[4] += line.generations();
            totals[5] += line.verificationFailures();
        }
        out.append(String.format(
                "TOTAL scenarios=%d findings=%d unexpected=%d missing=%d decisions=%d generations=%d verificationFailures=%d%n",
                RESULTS.size(), totals[0], totals[1], totals[2], totals[3], totals[4], totals[5]))
                .append("evidence links: all cited ids resolved to run signals in every scenario\n")
                .append("===== end M7 eval harness =====\n");
        System.out.print(out);
    }

    private void record(
            String scenario, UUID organizationId, UUID reviewId, Set<IssueFamily> expected,
            int expectedFindings, int expectedGenerations, long elapsedMs) {
        var run = analysisRepository.findLatestCompletedRun(organizationId, reviewId)
                .orElseThrow();
        var signals = analysisRepository.findSignals(organizationId, run.id());
        var findings = findingRepository.findFindings(organizationId, run.id());
        var actual = findings.stream().map(Finding::issueFamily)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        var missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        var unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(expected);

        var cited = new LinkedHashSet<UUID>();
        for (var finding : findings) {
            cited.addAll(finding.evidenceItemIds());
            finding.claims().forEach(claim -> cited.addAll(claim.evidenceItemIds()));
        }
        var runEvidence = new LinkedHashSet<UUID>();
        signals.forEach(signal -> runEvidence.addAll(signal.evidenceItemIds()));
        var unresolved = new ArrayList<>(cited);
        unresolved.removeAll(runEvidence);

        var verificationFailures = signals.stream()
                .filter(signal -> signal.verificationOutcome() != null
                        && !"VERIFIED".equals(signal.verificationOutcome()))
                .count();

        assertThat(missing).as(scenario + " missing findings").isEmpty();
        assertThat(unexpected).as(scenario + " unexpected findings").isEmpty();
        assertThat(findings).as(scenario + " finding count").hasSize(expectedFindings);
        assertThat(unresolved).as(scenario + " evidence links resolve").isEmpty();

        RESULTS.add(new EvalLine(scenario, findings.size(), unexpected.size(), missing.size(),
                scriptedProvider.policyComparisonDecisions(), generativeProvider.calls(),
                (int) verificationFailures, cited.size(), elapsedMs));
    }

    private void resetProviders() {
        scriptedProvider.reset();
        generativeProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private Review enrichedReview(String fixture, ReviewProfile profile) throws Exception {
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures", fixture));
        var ingested = contractIngestionService.ingest(fixture,
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1, profile);
        return reviewService.create(ingested.contractId(), 1, null);
    }

    private static ReviewProfile msaProfile(String packKey) {
        return new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                PaperSource.THIRD_PARTY, Jurisdiction.US, packKey);
    }

    private void publishMainPolicy(UUID organizationId) {
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
    }

    private void publishMainPolicyAndLibrary(UUID organizationId) {
        publishMainPolicy(organizationId);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
    }

    private record EvalLine(
            String scenario, int findings, int unexpected, int missing, int decisions,
            int generations, int verificationFailures, int evidenceLinks, long elapsedMs) {

        String format() {
            return String.format(
                    "%s | %d | %d | %d | %d | %d | %d | %d | %d",
                    scenario, findings, unexpected, missing, decisions, generations,
                    verificationFailures, evidenceLinks, elapsedMs);
        }
    }
}
