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
        return new RawReviewSignal(UUID.randomUUID(), ORGANIZATION_ID, REVIEW_ID, RUN_ID,
                UUID.randomUUID(), type, FindingSubjectKind.CLAUSE, HEADING_ID, null,
                CoreConcepts.LIMITATION_OF_LIABILITY, null, family, payload, evidence,
                null, null, null, null, verificationOutcome, null, null, Map.of(),
                type.name() + ":" + UUID.randomUUID(), NOW);
    }

    @Test
    void supportOnlyGroupYieldsNoFinding() {
        var support = signal(SignalType.APPROVED_DRAFTING_AVAILABLE,
                IssueFamily.LIABILITY_CAP_DEVIATION,
                new ApprovedDraftingPayload(UUID.randomUUID(), "fallback wording", "FALLBACK"),
                List.of(UUID.randomUUID()), null);

        assertThat(assembler.assemble(run(), List.of(support), NOW)).isEmpty();
    }

    @Test
    void clauseSignalWithoutEvidenceFailsAssemblyLoudly() {
        var signal = signal(SignalType.DEVIATION_UNCERTAIN, IssueFamily.LIABILITY_CAP_UNCERTAIN,
                new DeviationUncertainPayload("policy_not_comparable"), List.of(), null);

        assertThatThrownBy(() -> assembler.assemble(run(), List.of(signal), NOW))
                .isInstanceOf(FindingAssemblyException.class)
                .hasMessageContaining("without evidence");
    }

    @Test
    void draftingOnAnUncertainIssueDoesNotProposeLanguage() {
        var uncertain = signal(SignalType.DEVIATION_UNCERTAIN,
                IssueFamily.LIABILITY_CAP_UNCERTAIN,
                new DeviationUncertainPayload("classification_uncertain"),
                List.of(UUID.randomUUID()), null);
        var drafting = signal(SignalType.APPROVED_DRAFTING_AVAILABLE,
                IssueFamily.LIABILITY_CAP_UNCERTAIN,
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
                IssueFamily.OPEN_ENDED_LIABILITY,
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
}
