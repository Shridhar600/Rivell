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
                NOW, NOW, null, 0, null);
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
                new ApprovedDraftingPayload(UUID.randomUUID(), "fallback wording", "FALLBACK", null),
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
                new ApprovedDraftingPayload(UUID.randomUUID(), "fallback wording", "FALLBACK", null),
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
                        .contains("Your playbook has no position on this topic"));
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

    private static RawReviewSignal deviation(String category, boolean secondaryTopic, Map<String, Object> quality) {
        return signal(SignalType.POLICY_DEVIATION, IssueFamily.POSITION_ACCEPTABLE_FALLBACK,
                new PositionDeviationPayload(UUID.randomUUID(), category, 0.9, Map.of(), secondaryTopic),
                List.of(UUID.randomUUID()), null, quality);
    }

    @Test
    void p1AFindingRaisedBySecondOpinionCategoryGetsTheSeverityOfTheNewCategoryAndKeepsItsTag() {
        var walk = assembler.assemble(run(), List.of(deviation(PolicyComparisonSpecs.WALK_AWAY, false,
                Map.of("levers", List.of("P1", "P2"), "categoryBeforeLevers", PolicyComparisonSpecs.WITHIN_FALLBACK,
                        "secondOpinionCategory", PolicyComparisonSpecs.WALK_AWAY))), NOW).get(0);
        var beyond = assembler.assemble(run(), List.of(deviation(PolicyComparisonSpecs.BEYOND_FALLBACK, false,
                Map.of("levers", List.of("P1"), "categoryBeforeLevers", PolicyComparisonSpecs.WITHIN_FALLBACK))),
                NOW).get(0);

        assertThat(walk.severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(walk.quality()).containsEntry("levers", List.of("P1", "P2"))
                .containsEntry("severityWithoutLevers", "LOW")
                .containsEntry("categoryBeforeLevers", PolicyComparisonSpecs.WITHIN_FALLBACK)
                .containsEntry("secondOpinionCategory", PolicyComparisonSpecs.WALK_AWAY);
        assertThat(beyond.severity()).isEqualTo(FindingSeverity.MEDIUM);
        assertThat(beyond.quality()).containsEntry("severityWithoutLevers", "LOW");
    }

    @Test
    void p3AFindingRaisedByTheConditionCheckIsMediumAndRestorableToLow() {
        var finding = assembler.assemble(run(), List.of(deviation(PolicyComparisonSpecs.BEYOND_FALLBACK, false,
                Map.of("levers", List.of("P3"), "categoryBeforeLevers", PolicyComparisonSpecs.WITHIN_FALLBACK))),
                NOW).get(0);

        assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
        assertThat(finding.quality()).containsEntry("severityWithoutLevers", "LOW");
    }

    @Test
    void aLeverRaisedBeyondFallbackFromAHigherJevCategoryRestoresToThatCategorysSeverity() {
        var finding = assembler.assemble(run(), List.of(deviation(PolicyComparisonSpecs.WALK_AWAY, false,
                Map.of("levers", List.of("P1"), "categoryBeforeLevers", PolicyComparisonSpecs.BEYOND_FALLBACK))),
                NOW).get(0);

        assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(finding.quality()).containsEntry("severityWithoutLevers", "MEDIUM");
    }

    @Test
    void aFindingNoLeverChangedCarriesNoLeverKeys() {
        var finding = assembler.assemble(run(), List.of(deviation(PolicyComparisonSpecs.WITHIN_FALLBACK, false,
                Map.of("route", "SECOND_OPINION"))), NOW).get(0);

        assertThat(finding.severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(finding.quality()).doesNotContainKeys("levers", "severityWithoutLevers");
    }

    @Test
    void p2ARoutedNeedsYourLookItemIsTaggedAndRestorableToTheLowWithinNote() {
        var look = signal(SignalType.DEVIATION_UNCERTAIN, IssueFamily.POSITION_UNCERTAIN,
                new DeviationUncertainPayload(DeviationUncertainPayload.SECOND_OPINION_DISAGREES),
                List.of(UUID.randomUUID()), null,
                Map.of("route", "NEEDS_YOUR_LOOK", "secondOpinionOutcome", "DISAGREED",
                        "secondOpinionCategory", PolicyComparisonSpecs.MEETS_PREFERRED,
                        "routeReason", "P2_WITHIN_SPLIT", "levers", List.of("P2"),
                        "categoryBeforeLevers", PolicyComparisonSpecs.WITHIN_FALLBACK));

        var finding = assembler.assemble(run(), List.of(look), NOW).get(0);

        assertThat(finding.severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(finding.quality()).containsEntry("levers", List.of("P2"))
                .containsEntry("routeReason", "P2_WITHIN_SPLIT")
                .containsEntry("severityWithoutLevers", "LOW");
    }

    @Test
    void aSecondOpinionDisagreementSaysWhatRivellIsUnsureAboutInPlainWords() {
        var finding = assembler.assemble(
                run(), List.of(disagreement(PolicyComparisonSpecs.WALK_AWAY)), NOW).get(0);

        assertThat(finding.observationText()).isEqualTo(
                "Rivell is unsure about this clause. Here is the clause and the closest rule.");
        assertThat(finding.observationText()).doesNotContain("two reviewers", "please take a look");
    }

    @Test
    void anUnclearComparisonNamesWhatIsMissingInsteadOfCompliance() {
        var finding = assembler.assemble(
                run(), List.of(deviation(PolicyComparisonSpecs.UNCLEAR, false, Map.of())), NOW).get(0);

        assertThat(finding.observationText()).isEqualTo(
                "The clause depends on something outside the contract, so Rivell cannot tell "
                        + "whether it meets your playbook.");
        assertThat(finding.observationText()).doesNotContain("compliance cannot be decided");
    }

    @Test
    void anUncoveredClauseDoesNotAskForAHumanLegalReview() {
        var uncovered = signal(SignalType.POLICY_UNAVAILABLE, IssueFamily.POLICY_UNAVAILABLE,
                new PolicyUnavailablePayload(PolicyUnavailablePayload.NOT_COVERED),
                List.of(UUID.randomUUID()), null);
        var spotted = signal(SignalType.POLICY_UNAVAILABLE, IssueFamily.POLICY_UNAVAILABLE,
                new PolicyUnavailablePayload(PolicyUnavailablePayload.SPOTTED),
                List.of(UUID.randomUUID()), null);

        var uncoveredFinding = assembler.assemble(run(), List.of(uncovered), NOW).get(0);
        var spottedFinding = assembler.assemble(run(), List.of(spotted), NOW).get(0);

        assertThat(List.of(uncoveredFinding.observationText(), spottedFinding.observationText()))
                .allSatisfy(text -> assertThat(text).doesNotContain("lawyer", "legal review"));
        assertThat(uncoveredFinding.observationText())
                .isEqualTo("No playbook position addresses this clause, and it reads as unusual "
                        + "for this kind of contract and against our side.");
        assertThat(spottedFinding.observationText())
                .isEqualTo("A whole-contract read named this clause as one our side must act on, "
                        + "but no playbook position check flagged it.");
    }

    @Test
    void anAmbiguousPolicyNamesTheRulesThatConflictInPlainWords() {
        var ambiguous = assembler.assemble(run(),
                List.of(signal(SignalType.POLICY_AMBIGUOUS, IssueFamily.POLICY_AMBIGUOUS,
                        new PolicyAmbiguousPayload(2), List.of(UUID.randomUUID()), null)), NOW).get(0);

        assertThat(ambiguous.observationText()).isEqualTo(
                "More than one rule in your playbook applies to LIMITATION_OF_LIABILITY, and they "
                        + "conflict. Rivell did not choose between them.");
        assertThat(ambiguous.observationText()).doesNotContain("ambiguity is preserved", "no rule was chosen");
    }

    @Test
    void anUncertainClassificationAndTheCarveOutCheckAreSaidWithoutBoundedLanguage() {
        var classification = assembler.assemble(run(),
                List.of(signal(SignalType.DEVIATION_UNCERTAIN, IssueFamily.POSITION_UNCERTAIN,
                        new DeviationUncertainPayload("classification_uncertain"),
                        List.of(UUID.randomUUID()), null)), NOW).get(0);
        var carveOut = assembler.assemble(run(),
                List.of(signal(SignalType.CROSS_CLAUSE_UNCERTAIN, IssueFamily.CARVE_OUT_INCONSISTENCY,
                        new DeviationUncertainPayload("carve_out_not_comparable"),
                        List.of(UUID.randomUUID()), null)), NOW).get(0);

        assertThat(classification.observationText())
                .isEqualTo("Rivell could not check this clause against your playbook. "
                        + "Please check it yourself.");
        assertThat(carveOut.observationText())
                .isEqualTo("Rivell could not check how the liability cap and its exceptions fit "
                        + "together. Please check both clauses yourself.");
        assertThat(classification.observationText() + carveOut.observationText())
                .doesNotContain("bounded", "Please take a look");
    }

    @Test
    void unknownFallbackConditionsAddALookNoteAndNeverChangeTheSeverity() {
        var finding = assembler.assemble(run(), List.of(deviation(PolicyComparisonSpecs.WITHIN_FALLBACK, false,
                Map.of("fallbackConditionsUnknown", List.of("the work is delivered from our UK studio")))),
                NOW).get(0);

        assertThat(finding.severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(finding.claims()).anySatisfy(claim -> assertThat(claim.text())
                .contains("could not be checked").contains("the work is delivered from our UK studio"));
        assertThat(finding.quality()).doesNotContainKey("levers");
    }

    private static final String LIBRARY_WORDING =
            "The total liability of Supplier shall not exceed two times the fees paid.";

    private static RawReviewSignal drafting(String verdict) {
        var evidenceId = UUID.randomUUID();
        return new RawReviewSignal(UUID.randomUUID(), ORGANIZATION_ID, REVIEW_ID, RUN_ID,
                UUID.randomUUID(), SignalType.APPROVED_DRAFTING_AVAILABLE,
                FindingSubjectKind.CLAUSE, HEADING_ID, null,
                CoreConcepts.LIMITATION_OF_LIABILITY, null, IssueFamily.POSITION_WALK_AWAY,
                new ApprovedDraftingPayload(evidenceId, LIBRARY_WORDING, "FALLBACK", verdict),
                List.of(evidenceId), null, null, null, null, null, null, null,
                verdict == null ? Map.of() : Map.of("wordingFitVerdict", verdict),
                "APPROVED_DRAFTING_AVAILABLE:" + UUID.randomUUID(), NOW);
    }

    private static RawReviewSignal generatedWording() {
        return signal(SignalType.OPEN_ENDED_ANALYSIS_RESULT, IssueFamily.POSITION_WALK_AWAY,
                new OpenEndedAnalysisPayload("Rivell's own wording.", "what changed", "VERIFIED"),
                List.of(UUID.randomUUID()), "VERIFIED");
    }

    @Test
    void aLibraryWordingThatDoesNotFitIsNotProposedAsApproved() {
        var library = drafting("DOES_NOT_FIT");
        var libraryId = ((ApprovedDraftingPayload) library.payload()).draftingEvidenceId();
        var finding = assembler.assemble(run(),
                List.of(walkAway(false), library, generatedWording()), NOW).get(0);

        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.CONSIDER_GENERATED_LANGUAGE);
        assertThat(finding.proposedLanguageSource()).isEqualTo(ProposedLanguageSource.GENERATIVE);
        assertThat(finding.proposedLanguage()).isEqualTo("Rivell's own wording.");
        assertThat(finding.quality()).containsEntry("wordingFitVerdict", "DOES_NOT_FIT");
        // the library wording stays a source the person can read and take
        assertThat(finding.evidenceItemIds()).contains(libraryId);
    }

    @Test
    void aLibraryWordingThatDoesNotFitAndNoMachineProposalEscalates() {
        var finding = assembler.assemble(run(),
                List.of(walkAway(false), drafting("DOES_NOT_FIT")), NOW).get(0);

        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
        assertThat(finding.proposedLanguageSource()).isEqualTo(ProposedLanguageSource.NONE);
        assertThat(finding.quality()).containsEntry("wordingFitVerdict", "DOES_NOT_FIT");
    }

    @Test
    void anUncheckedLibraryWordingIsStillProposedWithItsVerdict() {
        var finding = assembler.assemble(run(),
                List.of(walkAway(false), drafting("UNKNOWN")), NOW).get(0);

        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.USE_APPROVED_FALLBACK);
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.APPROVED_DRAFTING);
        assertThat(finding.proposedLanguage()).isEqualTo(LIBRARY_WORDING);
        assertThat(finding.quality()).containsEntry("wordingFitVerdict", "UNKNOWN");
        // not checked is never said as approved, in the basis or the recommendation
        assertThat(finding.claims()).noneSatisfy(claim -> assertThat(claim.text())
                .contains("Approved drafting is available"));
        assertThat(finding.claims()).anySatisfy(claim -> {
            assertThat(claim.claimKind()).isEqualTo(ClaimKind.EVIDENCE_BASIS);
            assertThat(claim.text()).contains("not checked against this position");
        });
        assertThat(finding.claims()).filteredOn(claim -> claim.claimKind() == ClaimKind.RECOMMENDATION)
                .allSatisfy(claim -> assertThat(claim.text()).doesNotContain("approved"));
    }

    @Test
    void aWordingThatDoesNotFitAndNoMachineProposalAsksForAPersonWithoutApprovalCopy() {
        var finding = assembler.assemble(run(),
                List.of(walkAway(false), drafting("DOES_NOT_FIT")), NOW).get(0);

        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
        // no escalation signal: the playbook did not say this needs approval
        assertThat(finding.claims()).filteredOn(claim -> claim.claimKind() == ClaimKind.RECOMMENDATION)
                .allSatisfy(claim -> assertThat(claim.text())
                        .contains("Check it yourself")
                        .doesNotContain("needs approval"));
    }

    @Test
    void aFittedLibraryWordingIsProposedAsApproved() {
        var finding = assembler.assemble(run(),
                List.of(walkAway(false), drafting("FITS")), NOW).get(0);

        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.USE_APPROVED_FALLBACK);
        assertThat(finding.quality()).containsEntry("wordingFitVerdict", "FITS");
    }
}
