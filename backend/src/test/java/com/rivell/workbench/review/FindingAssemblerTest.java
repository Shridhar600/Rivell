package com.rivell.workbench.review;

import com.rivell.workbench.taxonomy.CoreConcepts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;


class FindingAssemblerTest {

    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID HEADING_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private final FindingAssembler assembler = new FindingAssembler();

    private static ReviewAnalysisRun run() {
        return new ReviewAnalysisRun(UUID.randomUUID(), ORGANIZATION_ID, REVIEW_ID, 1,
                RunStatus.COMPLETED, RunTrigger.INITIAL, "rivell-review-pipeline-1",
                NOW, NOW, null);
    }

    private static RawReviewSignal signal(
            SignalType type, IssueFamily family, SignalPayload payload,
            List<UUID> evidence, String verificationOutcome) {
        return signal(type, family, payload, evidence, verificationOutcome, Map.of());
    }

    private static RawReviewSignal signal(
            SignalType type, IssueFamily family, SignalPayload payload,
            List<UUID> evidence, String verificationOutcome, Map<String, Object> quality) {
        return new RawReviewSignal(UUID.randomUUID(), ORGANIZATION_ID, REVIEW_ID, RUN_ID,
                UUID.randomUUID(), type, FindingSubjectKind.CLAUSE, HEADING_ID, null,
                CoreConcepts.LIMITATION_OF_LIABILITY, null, family, payload, evidence,
                null, null, null, null, verificationOutcome, null, null, quality,
                type.name() + ":" + UUID.randomUUID(), NOW);
    }

    @Test
    void supportOnlyGroupYieldsNoFinding() {
        var support = signal(SignalType.APPROVED_DRAFTING_AVAILABLE,
                IssueFamily.POSITION_WALK_AWAY,
                new ApprovedDraftingPayload(UUID.randomUUID(), "fallback wording", "FALLBACK"),
                List.of(UUID.randomUUID()), null);

        assertThat(assembler.assemble(run(), List.of(support), NOW)).isEmpty();
    }

    @Test
    void clauseSignalWithoutEvidenceFailsAssemblyLoudly() {
        var signal = signal(SignalType.DEVIATION_UNCERTAIN, IssueFamily.POSITION_UNCERTAIN,
                new DeviationUncertainPayload("bounded_decision_unavailable"), List.of(), null);

        assertThatThrownBy(() -> assembler.assemble(run(), List.of(signal), NOW))
                .isInstanceOf(FindingAssemblyException.class)
                .hasMessageContaining("without evidence");
    }

    @Test
    void draftingOnAnUncertainIssueDoesNotProposeLanguage() {
        var uncertain = signal(SignalType.DEVIATION_UNCERTAIN,
                IssueFamily.POSITION_UNCERTAIN,
                new DeviationUncertainPayload("classification_uncertain"),
                List.of(UUID.randomUUID()), null);
        var drafting = signal(SignalType.APPROVED_DRAFTING_AVAILABLE,
                IssueFamily.POSITION_UNCERTAIN,
                new ApprovedDraftingPayload(UUID.randomUUID(), "fallback wording", "FALLBACK"),
                List.of(UUID.randomUUID()), null);

        var finding = assembler.assemble(run(), List.of(uncertain, drafting), NOW).get(0);

        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.NONE);
    }

    @Test
    void unverifiedCandidateHasNoGenerativeExplanationClaimAndSequentialOrdinals() {
        var openEnded = signal(SignalType.OPEN_ENDED_ANALYSIS_RESULT,
                IssueFamily.POSITION_NEEDS_FALLBACK,
                new OpenEndedAnalysisPayload("candidate", "model explanation", "UNCERTAIN"),
                List.of(UUID.randomUUID()), "UNCERTAIN");

        var finding = assembler.assemble(run(), List.of(openEnded), NOW).get(0);

        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
        assertThat(finding.claims()).extracting(FindingClaim::claimKind)
                .doesNotContain(ClaimKind.GENERATIVE_EXPLANATION);
        for (var index = 0; index < finding.claims().size(); index++) {
            assertThat(finding.claims().get(index).ordinal()).isEqualTo(index);
        }
    }

    private static RawReviewSignal walkAway(boolean secondaryTopic) {
        return signal(SignalType.POLICY_DEVIATION, IssueFamily.POSITION_WALK_AWAY,
                new PositionDeviationPayload(UUID.randomUUID(), PolicyComparisonSpecs.WALK_AWAY,
                        0.9, Map.of(PolicyComparisonSpecs.WALK_AWAY, 0.9), secondaryTopic),
                List.of(UUID.randomUUID()), null);
    }

    @Test
    void aPositionOnATopicTheClauseOnlyTouchesIsANoteAndNeverMasksItsOwnTopic() {
        var note = assembler.assemble(run(), List.of(walkAway(true)), NOW).get(0);
        assertThat(note.severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(note.reviewClassification()).isEqualTo(FindingClassification.INFORMATIONAL);

        // same clause, same family: the position on the clause's own topic decides
        var both = assembler.assemble(run(), List.of(walkAway(true), walkAway(false)), NOW);
        assertThat(both).hasSize(1);
        assertThat(both.get(0).severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(both.get(0).reviewClassification()).isEqualTo(FindingClassification.BLOCKING);
    }

    @Test
    void missingPolicyClaimDoesNotAssertAResolvedPolicy() {
        var unavailable = signal(SignalType.POLICY_UNAVAILABLE, IssueFamily.POLICY_UNAVAILABLE,
                new PolicyUnavailablePayload("POLICY_MISSING"),
                List.of(UUID.randomUUID()), null);

        var finding = assembler.assemble(run(), List.of(unavailable), NOW).get(0);

        assertThat(finding.claims())
                .filteredOn(claim -> claim.claimKind() == ClaimKind.POLICY_BASIS)
                .allSatisfy(claim -> assertThat(claim.text())
                        .contains("No published policy applies"));
    }

    private static RawReviewSignal disagreement(String secondOpinionCategory) {
        return signal(SignalType.DEVIATION_UNCERTAIN, IssueFamily.POSITION_UNCERTAIN,
                new DeviationUncertainPayload(DeviationUncertainPayload.SECOND_OPINION_DISAGREES),
                List.of(UUID.randomUUID()), null,
                Map.of("route", "NEEDS_YOUR_LOOK",
                        "secondOpinionOutcome", "DISAGREED",
                        "secondOpinionCategory", secondOpinionCategory));
    }

    @Test
    void aDisagreementNamingAWalkAwayIsMediumAndStaysNeedsYourLook() {
        var finding = assembler.assemble(
                run(), List.of(disagreement(PolicyComparisonSpecs.WALK_AWAY)), NOW).get(0);

        assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
        assertThat(finding.quality()).containsEntry("route", "NEEDS_YOUR_LOOK");
        assertThat(finding.reviewClassification()).isEqualTo(FindingClassification.REVIEW_REQUIRED);
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
    }

    @Test
    void aDisagreementNamingBeyondFallbackIsMedium() {
        var finding = assembler.assemble(
                run(), List.of(disagreement(PolicyComparisonSpecs.BEYOND_FALLBACK)), NOW).get(0);

        assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
    }

    @Test
    void aDisagreementOnAnyOtherSecondOpinionCategoryStaysLow() {
        for (var category : List.of(PolicyComparisonSpecs.WITHIN_FALLBACK,
                PolicyComparisonSpecs.MEETS_PREFERRED, PolicyComparisonSpecs.NOT_ADDRESSED)) {
            var finding = assembler.assemble(run(), List.of(disagreement(category)), NOW).get(0);
            assertThat(finding.severity()).as(category).isEqualTo(FindingSeverity.LOW);
        }
    }

    @Test
    void anUnavailableSecondOpinionStaysLow() {
        var unavailable = signal(SignalType.DEVIATION_UNCERTAIN, IssueFamily.POSITION_UNCERTAIN,
                new DeviationUncertainPayload(
                        DeviationUncertainPayload.SECOND_OPINION_UNAVAILABLE),
                List.of(UUID.randomUUID()), null,
                Map.of("route", "NEEDS_YOUR_LOOK",
                        "secondOpinionOutcome", "UNAVAILABLE",
                        "uncertaintyReason", DeviationUncertainPayload.SECOND_OPINION_UNAVAILABLE));

        var finding = assembler.assemble(run(), List.of(unavailable), NOW).get(0);

        assertThat(finding.severity()).isEqualTo(FindingSeverity.LOW);
    }
}
