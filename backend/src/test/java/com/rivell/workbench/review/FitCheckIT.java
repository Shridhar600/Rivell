package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
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

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * Part 4 fit check at the seams the brief names: a search hit that does not govern is not
 * applied and stays visible as "not covered"; an unchecked hit is applied with its verdict
 * recorded; library wording is proposed as approved only when it fits, replaced by Rivell's own
 * proposal when it does not, and one clause's fit questions all travel in one call.
 */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class FitCheckIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    /** The clause the search finds a position for, with no topic to pair it by. */
    private static final String TERMINATION = "Termination";
    private static final String TERMINATION_POSITION = "terminate for convenience";
    /** The library-wording clause: no topic, and the aggregate-cap wording is published for it. */
    private static final String AGGREGATE_CAP = "Aggregate Cap";
    private static final String LIABILITY_POSITION = "fees paid in the preceding 12 months";

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
    ContractEnrichmentService enrichmentService;

    private List<KnowledgeCandidate> positions;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        generativeProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    /** pc-contract.docx against the PC playbook (the flow the search route tests pin). */
    private UUID reviewedContract() throws Exception {
        return reviewedContract("pc-contract.docx");
    }

    private UUID reviewedContract(String fixture) throws Exception {
        positions = corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                        M6EvidenceCorpus.msaManifest2026(), null).stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.PLAYBOOK_RULE)
                .toList();
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/" + fixture));
        var ingested = contractIngestionService.ingest(fixture,
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        return reviewService.create(ingested.contractId(), 1, null).id();
    }

    private UUID positionId(String rawLabel) {
        return positions.stream().filter(position -> rawLabel.equals(position.rawLabel()))
                .findFirst().orElseThrow().id();
    }

    @SuppressWarnings("unchecked")
    private ReviewCheckExecution searchCheck(UUID runId, UUID positionId) {
        return analysisRepository.findExecutions(organizationId(), runId).stream()
                .filter(execution -> "SEARCH".equals(execution.detail().get("route")))
                .filter(execution -> execution.detail().get("positions") instanceof List<?> judged
                        && ((List<Map<String, Object>>) judged).stream().anyMatch(entry ->
                                positionId.toString().equals(entry.get("positionCandidateId"))))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entryFor(ReviewCheckExecution check, UUID positionId) {
        return ((List<Map<String, Object>>) check.detail().get("positions")).stream()
                .filter(entry -> positionId.toString().equals(entry.get("positionCandidateId")))
                .findFirst().orElseThrow();
    }

    private List<RawReviewSignal> signalsCiting(UUID runId, UUID positionId) {
        return analysisRepository.findSignals(organizationId(), runId).stream()
                .filter(signal -> signal.payload() instanceof PositionDeviationPayload payload
                        && positionId.equals(payload.positionCandidateId()))
                .toList();
    }

    @Test
    void aSearchHitThatDoesNotGovernIsNotAppliedAndStaysVisible() throws Exception {
        scriptedProvider.topiclessHeadings.add(TERMINATION);
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(TERMINATION, PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.positionAnswers.put(TERMINATION_POSITION,
                PolicyComparisonSpecs.BEYOND_FALLBACK);
        scriptedProvider.ruleFitProbability = 0.05;

        var run = analysisService.analyze(organizationId(), reviewId);

        var termination = positionId("Termination");
        var check = searchCheck(run.id(), termination);
        assertThat(check.detail()).containsEntry("addressed", false);
        assertThat(check.detail()).doesNotContainKey("searchFailed");
        assertThat(entryFor(check, termination))
                .containsEntry("fitVerdict", "DOES_NOT_FIT")
                .containsEntry("fitReason", "DIFFERENT_SUBJECT")
                .containsEntry("fitOutcome", "not_covered");
        var found = (Integer) ((Map<?, ?>) check.detail().get("search")).get("found");
        assertThat(((Map<?, ?>) check.detail().get("search")).get("notCovered")).isEqualTo(found);
        // not applied: no finding rests on a rule that does not govern the clause
        assertThat(signalsCiting(run.id(), termination)).isEmpty();
        // the clause is still read: the playbook is silent here, so the triage decides
        assertThat(check.detail()).containsKey("uncoveredProbability");
    }

    @Test
    void anUncheckedSearchHitIsAppliedWithItsVerdictRecorded() throws Exception {
        scriptedProvider.topiclessHeadings.add(TERMINATION);
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(TERMINATION, PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.positionAnswers.put(TERMINATION_POSITION,
                PolicyComparisonSpecs.BEYOND_FALLBACK);
        scriptedProvider.failFitDecision = true;

        var run = analysisService.analyze(organizationId(), reviewId);

        var termination = positionId("Termination");
        var check = searchCheck(run.id(), termination);
        assertThat(check.detail()).containsEntry("addressed", true);
        assertThat(entryFor(check, termination))
                .containsEntry("fitVerdict", "UNKNOWN")
                .containsEntry("fitReason", "UNAVAILABLE")
                .doesNotContainKey("fitOutcome");
        assertThat(signalsCiting(run.id(), termination)).hasSize(1);
        var signal = signalsCiting(run.id(), termination).getFirst();
        var finding = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(candidate -> candidate.signalIds().contains(signal.id()))
                .findFirst().orElseThrow();
        assertThat(finding.quality()).containsEntry("fitVerdict", "UNKNOWN")
                .containsEntry("fitReason", "UNAVAILABLE");
    }

    @Test
    void allFitQuestionsForAClauseTravelInOneCall() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_LIBRARY,
                M6EvidenceCorpus.msaManifest2026(), null);
        scriptedProvider.topiclessHeadings.add(AGGREGATE_CAP);
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(AGGREGATE_CAP, PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.positionAnswers.put(LIABILITY_POSITION,
                PolicyComparisonSpecs.BEYOND_FALLBACK);

        var run = analysisService.analyze(organizationId(), reviewId);

        var liability = positionId("Limitation of Liability");
        var searched = searchCheck(run.id(), liability);
        // the library offer is on the same check as the search hit it serves
        var offer = analysisRepository.findSignals(organizationId(), run.id()).stream()
                .filter(signal -> signal.planCheckId().equals(searched.planCheckId()))
                .filter(signal -> signal.signalType() == SignalType.APPROVED_DRAFTING_AVAILABLE)
                .findFirst().orElseThrow();
        var found = (Integer) ((Map<?, ?>) searched.detail().get("search")).get("found");
        var aggregate = scriptedProvider.fitCallsMade().stream()
                .filter(call -> call.heading().contains(AGGREGATE_CAP))
                .toList();
        assertThat(aggregate).hasSize(1);
        // every search hit's question and the wording's two questions (meets, cures), in one call
        assertThat(aggregate.getFirst().questionKeys()).hasSize(found + 2);
        assertThat(offer).isNotNull();
    }

    @Test
    void aWordingThatMeetsThePositionButDoesNotCureTheGapIsOfferedUnchecked() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_LIBRARY,
                M6EvidenceCorpus.msaManifest2026(), null);
        scriptedProvider.topiclessHeadings.add(AGGREGATE_CAP);
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(AGGREGATE_CAP, PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.positionAnswers.put(LIABILITY_POSITION,
                PolicyComparisonSpecs.BEYOND_FALLBACK);
        scriptedProvider.wordingMeetsProbability = 0.95;
        scriptedProvider.wordingCuresProbability = 0.05;

        var run = analysisService.analyze(organizationId(), reviewId);

        var finding = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(candidate -> "SEARCH".equals(candidate.quality().get("foundBy")))
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK)
                .findFirst().orElseThrow();
        // council §E: a partial fit is offered unchecked, not approved and not silently replaced
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.USE_APPROVED_FALLBACK);
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.APPROVED_DRAFTING);
        assertThat(finding.quality()).containsEntry("wordingFitVerdict", "UNKNOWN")
                .containsEntry("wordingFitReasons",
                        List.of("MEETS_POSITION_YES", "CURES_GAP_NO"));
        assertThat(analysisRepository.findSignals(organizationId(), run.id()).stream()
                .filter(signal -> finding.signalIds().contains(signal.id()))
                .anyMatch(signal -> signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT))
                .isFalse();
        var searched = searchCheck(run.id(), positionId("Limitation of Liability"));
        assertThat(entryFor(searched, positionId("Limitation of Liability")))
                .containsEntry("wordingFitVerdict", "UNKNOWN")
                .containsEntry("wordingFitReasons",
                        List.of("MEETS_POSITION_YES", "CURES_GAP_NO"));
    }

    @Test
    void aRelatedPositionPaysForNoFitQuestionBecauseItsWordingIsNeverOffered() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_LIBRARY,
                M6EvidenceCorpus.msaManifest2026(), null);
        var reviewId = reviewedContract("pc-related-topic-contract.docx");
        scriptedProvider.positionAnswers.put("never acceptable",
                PolicyComparisonSpecs.WALK_AWAY);

        var run = analysisService.analyze(organizationId(), reviewId);

        var liability = positionId("Limitation of Liability");
        assertThat(signalsCiting(run.id(), liability)).singleElement()
                .satisfies(signal -> assertThat(
                        ((PositionDeviationPayload) signal.payload()).secondaryTopic()).isTrue());
        // the position governs only a topic the clause touches, so no drafting is offered and
        // its wording is never put to the fit check
        assertThat(scriptedProvider.fitCallsMade())
                .noneMatch(call -> call.heading().contains("Termination"));
    }

    @Test
    void aDroppedSearchHitDoesNotCountItsWordingQuestionAsAnOffer() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_LIBRARY,
                M6EvidenceCorpus.msaManifest2026(), null);
        scriptedProvider.topiclessHeadings.add(AGGREGATE_CAP);
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(AGGREGATE_CAP, PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.positionAnswers.put(LIABILITY_POSITION,
                PolicyComparisonSpecs.BEYOND_FALLBACK);
        scriptedProvider.ruleFitProbability = 0.05;

        var run = analysisService.analyze(organizationId(), reviewId);

        var liability = positionId("Limitation of Liability");
        var check = searchCheck(run.id(), liability);
        assertThat(entryFor(check, liability))
                .containsEntry("fitVerdict", "DOES_NOT_FIT")
                .containsEntry("fitOutcome", "not_covered")
                .doesNotContainKey("wordingFitVerdict");
        var found = (Integer) ((Map<?, ?>) check.detail().get("search")).get("found");
        assertThat(((Map<?, ?>) check.detail().get("search")).get("notCovered")).isEqualTo(found);
    }

    @Test
    void aLibraryWordingThatDoesNotFitIsReplacedByRivellsProposal() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_LIBRARY,
                M6EvidenceCorpus.msaManifest2026(), null);
        scriptedProvider.topiclessHeadings.add(AGGREGATE_CAP);
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(AGGREGATE_CAP, PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.positionAnswers.put(LIABILITY_POSITION,
                PolicyComparisonSpecs.BEYOND_FALLBACK);
        scriptedProvider.wordingFitProbability = 0.05;

        var run = analysisService.analyze(organizationId(), reviewId);

        var finding = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(candidate -> "SEARCH".equals(candidate.quality().get("foundBy")))
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.CONSIDER_GENERATED_LANGUAGE);
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.GENERATIVE);
        assertThat(finding.quality()).containsEntry("wordingFitVerdict", "DOES_NOT_FIT");
        var drafting = analysisRepository.findSignals(organizationId(), run.id()).stream()
                .filter(signal -> finding.signalIds().contains(signal.id()))
                .filter(signal -> signal.signalType() == SignalType.APPROVED_DRAFTING_AVAILABLE)
                .map(signal -> (ApprovedDraftingPayload) signal.payload())
                .filter(payload -> "DOES_NOT_FIT".equals(payload.fitVerdict()))
                .findFirst().orElseThrow();
        assertThat(drafting.fitVerdict()).isEqualTo("DOES_NOT_FIT");
        // the library wording stays a source the person can read and take
        assertThat(finding.evidenceItemIds()).contains(drafting.draftingEvidenceId());
    }

    @Test
    void anUncheckedLibraryWordingIsOfferedWithItsVerdict() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_LIBRARY,
                M6EvidenceCorpus.msaManifest2026(), null);
        scriptedProvider.topiclessHeadings.add(AGGREGATE_CAP);
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(AGGREGATE_CAP, PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.positionAnswers.put(LIABILITY_POSITION,
                PolicyComparisonSpecs.BEYOND_FALLBACK);
        scriptedProvider.failFitDecision = true;

        var run = analysisService.analyze(organizationId(), reviewId);

        var finding = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(candidate -> "SEARCH".equals(candidate.quality().get("foundBy")))
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.USE_APPROVED_FALLBACK);
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.APPROVED_DRAFTING);
        assertThat(finding.quality()).containsEntry("wordingFitVerdict", "UNKNOWN")
                .containsEntry("wordingFitReason", "MEETS_POSITION_UNKNOWN")
                .containsEntry("wordingFitReasons", List.of("MEETS_POSITION_UNKNOWN",
                        "CURES_GAP_UNKNOWN", "UNAVAILABLE"));
    }

    @Test
    void aTopicMatchedFindingWithoutLibraryWordingKeepsItsOldPath() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put("Limitation of Liability",
                PolicyComparisonSpecs.BEYOND_FALLBACK);

        var run = analysisService.analyze(organizationId(), reviewId);

        var finding = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK)
                .findFirst().orElseThrow();
        // no library variant, no search hit: Rivell's proposal and none of the fit keys
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.GENERATIVE);
        assertThat(finding.quality()).doesNotContainKeys("fitVerdict", "fitReason",
                "wordingFitVerdict", "wordingFitReason");
    }
}
