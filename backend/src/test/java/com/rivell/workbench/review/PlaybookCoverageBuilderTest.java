package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.knowledge.ApplicabilityScope;
import com.rivell.workbench.knowledge.ApplicableKnowledge;
import com.rivell.workbench.knowledge.ArtifactType;
import com.rivell.workbench.knowledge.CandidatePayload;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.knowledge.PublishedCandidate;
import com.rivell.workbench.knowledge.ReviewStatus;
import com.rivell.workbench.knowledge.SemanticFields;
import com.rivell.workbench.review.PlaybookCoverage.Entry;
import com.rivell.workbench.review.PlaybookCoverage.Reason;
import com.rivell.workbench.review.PlaybookCoverage.State;
import com.rivell.workbench.review.PlaybookCoverage.Verdict;
import com.rivell.workbench.review.check.OmissionCheckExecutor;
import com.rivell.workbench.taxonomy.CoreConcepts;

class PlaybookCoverageBuilderTest {

    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID PLAYBOOK_ID = UUID.randomUUID();
    private static final UUID PUBLICATION_ID = UUID.randomUUID();
    private static final UUID HEADING_ID = UUID.randomUUID();
    private static final UUID POLICY_CHECK_ID = UUID.randomUUID();
    private static final UUID ABSENCE_CHECK_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private final KnowledgeQueryService knowledge = mock(KnowledgeQueryService.class);
    private final PlaybookCoverageBuilder builder = new PlaybookCoverageBuilder(knowledge);

    @Test
    void reportsComparedSearchedNeverComparedAndOutOfScopePositions() {
        var compared = position("Limitation of liability", "L-1",
                "LIMITATION_OF_LIABILITY", Set.of());
        var searched = position("Termination for convenience", "T-11", "TERM", Set.of());
        var neverCompared = position("Audit rights", "A-4", "AUDIT", Set.of());
        var outOfScope = position("Data export", "D-2", "DATA_PROTECTION", Set.of("NDA"));
        when(knowledge.listPublishedPositionsForArtifacts(
                any(), anyLong(), anyCollection(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(compared, searched, neverCompared, outOfScope));

        var policyCheck = policyCheck();
        var absenceCheck = absenceCheck();
        var policyExecution = execution(policyCheck.id(), Map.of("positions",
                List.of(judged(compared.candidate().id(), "WITHIN_FALLBACK", true,
                        Map.of("WITHIN_FALLBACK", 0.9), 0.9, "PARTIAL"))));
        var absenceExecution = execution(absenceCheck.id(), Map.of(
                "searchedCandidates", List.of(searched.candidate().id().toString()),
                "addressedBySearch", List.of(),
                "searchDegradation", List.of()));

        var coverage = builder.build(snapshot(), List.of(policyCheck, absenceCheck),
                List.of(policyExecution, absenceExecution), List.of(), List.of());

        assertThat(coverage.entries()).extracting(Entry::state).containsExactly(
                State.COMPARED, State.NOT_FOUND, State.NOT_COMPARED, State.OUT_OF_SCOPE);
        var comparedEntry = entry(coverage, compared.candidate().id());
        assertThat(comparedEntry.verdict()).isEqualTo(Verdict.PARTLY_WITHIN_FALLBACK);
        assertThat(comparedEntry.clauseHeadingNodeId()).isEqualTo(HEADING_ID);
        assertThat(comparedEntry.code()).isEqualTo("L-1");
        assertThat(comparedEntry.topic()).isEqualTo("LIMITATION_OF_LIABILITY");
        var searchedEntry = entry(coverage, searched.candidate().id());
        assertThat(searchedEntry.verdict()).isNull();
        assertThat(searchedEntry.reason()).isNull();
        assertThat(entry(coverage, neverCompared.candidate().id()).reason())
                .isEqualTo(Reason.NOT_CHECKED);
        assertThat(entry(coverage, outOfScope.candidate().id()).reason()).isNull();
    }

    @Test
    void neverSaysNotFoundWithoutACompletedSearchForThatPosition() {
        var neverSearched = position("Audit rights", "A-4", "AUDIT", Set.of());
        var judgedNotAddressed = position("Notice", "N-2", "NOTICE", Set.of());
        var degradedSearch = position("Insurance", "I-3", "INSURANCE", Set.of());
        when(knowledge.listPublishedPositionsForArtifacts(
                any(), anyLong(), anyCollection(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(neverSearched, judgedNotAddressed, degradedSearch));

        var policyCheck = policyCheck();
        var absenceCheck = absenceCheck();
        var policyExecution = execution(policyCheck.id(), Map.of("positions",
                List.of(judged(judgedNotAddressed.candidate().id(), null, false,
                        Map.of("NOT_ADDRESSED", 0.9, "MEETS_PREFERRED", 0.1), 0.1, null))));
        var absenceExecution = execution(absenceCheck.id(), Map.of(
                "searchedCandidates", List.of(degradedSearch.candidate().id().toString()),
                "addressedBySearch", List.of()), CheckExecutionStatus.DEGRADED);

        var coverage = builder.build(snapshot(), List.of(policyCheck, absenceCheck),
                List.of(policyExecution, absenceExecution), List.of(), List.of());

        assertThat(coverage.entries()).extracting(Entry::state)
                .containsOnly(State.NOT_COMPARED);
        assertThat(entry(coverage, neverSearched.candidate().id()).reason())
                .isEqualTo(Reason.CHECK_DID_NOT_COMPLETE);
        assertThat(entry(coverage, judgedNotAddressed.candidate().id()).reason())
                .isEqualTo(Reason.CHECK_DID_NOT_COMPLETE);
        assertThat(entry(coverage, degradedSearch.candidate().id()).reason())
                .isEqualTo(Reason.CHECK_DID_NOT_COMPLETE);
    }

    @Test
    void aClauseMatchedToTheWalkAwayIsComparedOnlyWhenThatFindingWasRaised() {
        var matched = position("Termination for convenience", "T-11", "TERM", Set.of());
        when(knowledge.listPublishedPositionsForArtifacts(
                any(), anyLong(), anyCollection(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(matched));
        var absenceCheck = absenceCheck();
        var match = Map.of(
                "candidateId", matched.candidate().id().toString(),
                "headingNodeId", HEADING_ID.toString());
        // the search matched the walk-away on one clause and another clause addressed the position,
        // so the check emitted no finding for it
        var addressedElsewhere = execution(absenceCheck.id(), Map.of(
                "searchedCandidates", List.of(matched.candidate().id().toString()),
                "addressedBySearch", List.of(matched.candidate().id().toString()),
                "matchedWalkAwayBySearch", List.of(match),
                "searchDegradation", List.of()));

        var withoutFinding = builder.build(snapshot(), List.of(absenceCheck),
                List.of(addressedElsewhere), List.of(), List.of());

        var unmatchedEntry = entry(withoutFinding, matched.candidate().id());
        assertThat(unmatchedEntry.state()).isEqualTo(State.NOT_COMPARED);
        assertThat(unmatchedEntry.reason()).isEqualTo(Reason.FOUND_NOT_JUDGED);
        assertThat(unmatchedEntry.verdict()).isNull();

        var signal = walkAwaySignal(matched.candidate().id());
        var finding = new FindingAssembler().assemble(run(), List.of(signal), NOW).getFirst();
        var matchedOnly = execution(absenceCheck.id(), Map.of(
                "searchedCandidates", List.of(matched.candidate().id().toString()),
                "addressedBySearch", List.of(),
                "matchedWalkAwayBySearch", List.of(match),
                "searchDegradation", List.of()));
        var withFinding = builder.build(snapshot(), List.of(absenceCheck),
                List.of(matchedOnly), List.of(signal), List.of(finding));

        var entry = entry(withFinding, matched.candidate().id());
        assertThat(entry.state()).isEqualTo(State.COMPARED);
        assertThat(entry.verdict()).isEqualTo(Verdict.WALK_AWAY);
        assertThat(entry.clauseHeadingNodeId()).isEqualTo(HEADING_ID);
        assertThat(entry.findingId()).isEqualTo(finding.id());
    }

    private static ReviewAnalysisRun run() {
        return new ReviewAnalysisRun(RUN_ID, ORGANIZATION_ID, REVIEW_ID, 1,
                RunStatus.COMPLETED, RunTrigger.INITIAL, "review-pipeline-1",
                NOW, NOW, null, 0, null, null);
    }

    private static RawReviewSignal walkAwaySignal(UUID candidateId) {
        var omission = new OmissionPayload(candidateId, "Termination for convenience", 12,
                "No termination for convenience", "Term and termination");
        return new RawReviewSignal(UUID.randomUUID(), ORGANIZATION_ID, REVIEW_ID, RUN_ID,
                ABSENCE_CHECK_ID, SignalType.MISSING_REQUIRED_CONCEPT,
                FindingSubjectKind.ABSENCE, null, CoreConcepts.TERM, CoreConcepts.TERM, null,
                IssueFamily.MISSING_REQUIRED_PROVISION, omission, List.of(UUID.randomUUID()),
                WalkAwayAbsenceSpecs.WALK_AWAY_ABSENCE_SPEC,
                WalkAwayAbsenceSpecs.WALK_AWAY_ABSENCE_VERSION, null, null, null, null, null,
                Map.of("absenceProbability", 0.92, "route", "JEV"),
                SignalType.MISSING_REQUIRED_CONCEPT.name() + ":" + candidateId, NOW);
    }

    private static Entry entry(PlaybookCoverage coverage, UUID candidateId) {
        return coverage.entries().stream()
                .filter(entry -> entry.positionCandidateId().equals(candidateId))
                .findFirst()
                .orElseThrow();
    }

    private static PublishedCandidate position(
            String label, String code, String topic, Set<String> appliesTo) {
        var payload = new CandidatePayload(Map.of("Ref", code, "Preferred", "Position text"));
        var candidate = new KnowledgeCandidate(
                UUID.randomUUID(), PLAYBOOK_ID, ORGANIZATION_ID, UUID.randomUUID(),
                CandidateType.PLAYBOOK_RULE, ReviewStatus.PUBLISHED, label, "Position text",
                payload, topic, List.of(topic), null, null, null, SemanticFields.EMPTY, null,
                List.of(), null, null, null,
                new ApplicabilityScope(null, Set.of(), null, null, null, null, null, appliesTo),
                List.of(), null, Set.of(), null, null, NOW, NOW, null);
        return new PublishedCandidate(candidate, PUBLICATION_ID, 7L);
    }

    private static ReviewPlanCheck policyCheck() {
        return new ReviewPlanCheck(POLICY_CHECK_ID, ORGANIZATION_ID, REVIEW_ID, 0,
                "CLAUSE:POLICY_COMPARISON", CheckFamily.CLAUSE_REVIEW, null,
                CheckMechanism.POLICY_COMPARISON, CoreConcepts.TERM, null, HEADING_ID, NOW);
    }

    private static ReviewPlanCheck absenceCheck() {
        return new ReviewPlanCheck(ABSENCE_CHECK_ID, ORGANIZATION_ID, REVIEW_ID, 1,
                OmissionCheckExecutor.WALK_AWAY_ABSENCE_CHECK_KEY, CheckFamily.CONCEPT_PRESENCE,
                CheckExpectation.REQUIRED, CheckMechanism.OMISSION, ClauseTopics.NO_TOPIC, null,
                null, NOW);
    }

    private static ReviewCheckExecution execution(
            UUID planCheckId, Map<String, Object> detail) {
        return execution(planCheckId, detail, CheckExecutionStatus.COMPLETED);
    }

    private static ReviewCheckExecution execution(
            UUID planCheckId, Map<String, Object> detail, CheckExecutionStatus status) {
        return new ReviewCheckExecution(UUID.randomUUID(), ORGANIZATION_ID, REVIEW_ID, RUN_ID,
                planCheckId, status, null, detail, 0, NOW, NOW);
    }

    private static Map<String, Object> judged(
            UUID candidateId, String category, boolean flagged,
            Map<String, Double> probabilities, double flagProbability,
            String fallbackConditionsOutcome) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("positionCandidateId", candidateId.toString());
        entry.put("category", category == null ? "" : category);
        entry.put("flagProbability", flagProbability);
        entry.put("flagged", flagged);
        entry.put("probabilities", probabilities);
        entry.put("route", "ACCEPT");
        if (fallbackConditionsOutcome != null) {
            entry.put("fallbackConditionsOutcome", fallbackConditionsOutcome);
        }
        return entry;
    }

    private static ReviewSnapshot snapshot() {
        return new ReviewSnapshot(
                REVIEW_ID, ORGANIZATION_ID, UUID.randomUUID(), UUID.randomUUID(), 1,
                ContractType.MSA, PartyRole.CUSTOMER, PaperSource.OUR_PAPER,
                Jurisdiction.UNSPECIFIED, null, null, UUID.randomUUID(), "semantics-1",
                "digest", 7L, LocalDate.of(2026, 10, 26), "review-pipeline-1", "index-1",
                "embedding-1",
                new ModelPolicyIdentity("provider", "decision", "provider", "generative"),
                ReviewEngine.V2,
                List.of(new ApplicableKnowledge(PLAYBOOK_ID, ArtifactType.PLAYBOOK,
                        "Commercial playbook", 7L)),
                NOW);
    }
}
