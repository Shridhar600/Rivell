package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SemanticFieldModelTest {

    private static final UUID EXECUTION = UUID.randomUUID();

    @Test
    void machineProposalIsRetainedAndEffectiveValuesPreferHumanCorrection() {
        var fields = new SemanticFields(Set.of(PositionRole.PREFERRED), null, null, null, Map.of());
        var interpretation = interpretation(PositionRole.PREFERRED.name(), "BOUNDED_CLASSIFICATION");

        assertThat(fields.positions()).containsExactly(PositionRole.PREFERRED);
        assertThat(fields.effectiveVariantRole()).isEqualTo(PositionRole.UNSPECIFIED);

        var corrected = new SemanticFields(
                Set.of(PositionRole.PREFERRED), null, null, PositionRole.FALLBACK, Map.of());

        assertThat(corrected.effectiveVariantRole()).isEqualTo(PositionRole.FALLBACK);
        assertThat(corrected.positions()).isEqualTo(fields.positions());
        assertThat(interpretation.proposedValue()).isEqualTo("PREFERRED");
    }

    @Test
    void effectiveVariantRoleFallsBackToUnspecifiedWhenAbsent() {
        var fields = new SemanticFields(Set.of(), null, null, PositionRole.PREFERRED, Map.of());

        assertThat(fields.effectiveVariantRole()).isEqualTo(PositionRole.PREFERRED);

        assertThat(new SemanticFields(Set.of(), null, null, null, Map.of()).effectiveVariantRole())
                .isEqualTo(PositionRole.UNSPECIFIED);
    }

    @Test
    void clearedApprovalIsNotSpecifiedButAnOlderEditWithoutTheMarkerStillFallsBack() {
        var machine = new SemanticFields(Set.of(PositionRole.PREFERRED), true, null, null, Map.of());
        var cleared = new SemanticFields(Set.of(), null, null, null, Map.of())
                .withRequiresApprovalCleared();
        var legacyEdit = new SemanticFields(
                Set.of(PositionRole.FALLBACK), null, null, null, Map.of());

        assertThat(candidate(machine, cleared).effectiveRequiresApproval()).isNull();
        assertThat(candidate(machine, legacyEdit).effectiveRequiresApproval()).isTrue();
        assertThat(candidate(machine, cleared).semanticFields().requiresApproval()).isTrue();
    }

    private static KnowledgeCandidate candidate(
            SemanticFields machine, SemanticFields edited) {
        var now = java.time.Instant.now();
        return new KnowledgeCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CandidateType.PLAYBOOK_RULE, ReviewStatus.PENDING, "G-LIAB-01", "wording",
                new CandidatePayload(Map.of("Position", "Preferred")),
                "LIMITATION_OF_LIABILITY", List.of("LIMITATION_OF_LIABILITY"),
                null, null, null, machine, edited, List.of(), null, null, null,
                new ApplicabilityScope(null, Set.of(), null, null, null, null, null),
                List.of(), null, null, null, null, now, now, null);
    }

    @Test
    void aStoredProposalThatWasNotAppliedIsNotACorrectedField() {
        var machine = new SemanticFields(Set.of(), null, null, null, Map.of());

        assertThat(candidate(machine, null).correctedFields()).isEmpty();
    }

    @Test
    void savedStructuredChangesAreReportedAsCorrectedFields() {
        var machine = new SemanticFields(
                Set.of(PositionRole.PREFERRED), false, null, null, Map.of());
        var edited = new SemanticFields(
                Set.of(PositionRole.PREFERRED, PositionRole.FALLBACK), null,
                "Any uncapped indemnity", null, Map.of()).withRequiresApprovalCleared();

        assertThat(candidate(machine, edited).correctedFields())
                .containsExactlyInAnyOrder("positions", "escalationTrigger", "requiresApproval");
    }

    @Test
    void anEditedValueThatMatchesTheMachineValueIsNotACorrection() {
        var machine = new SemanticFields(
                Set.of(PositionRole.PREFERRED), true, "Below twelve months", null, Map.of());
        var edited = new SemanticFields(
                Set.of(PositionRole.PREFERRED), true, "Below twelve months", null, Map.of());

        assertThat(candidate(machine, edited).correctedFields()).isEmpty();
    }

    @Test
    void interpretationRequiresSpecAndKind() {
        var interpretation = interpretation("position", "BOUNDED_CLASSIFICATION");

        assertThat(interpretation.specId()).isEqualTo("playbook-position");
        assertThat(interpretation.verification()).isEqualTo(VerificationState.NOT_VERIFIED);

        assertThatThrownBy(() -> new FieldInterpretation(
                "position", InterpretationKind.BOUNDED_CLASSIFICATION, "PREFERRED",
                null, 0, null, List.of(), VerificationState.VERIFIED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generativeFieldRequiresVerificationExecutionWhenVerified() {
        assertThatThrownBy(() -> new FieldInterpretation(
                "escalationTrigger", InterpretationKind.GENERATIVE_EXTRACTION,
                "carve-outs beyond data breaches", "escalation-trigger-extraction", 1,
                UUID.randomUUID(), List.of(), VerificationState.VERIFIED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FieldInterpretation interpretation(String value, String kind) {
        return new FieldInterpretation(
                "position", InterpretationKind.valueOf(kind), value,
                "playbook-position", 1, EXECUTION, List.of(),
                VerificationState.NOT_VERIFIED, null);
    }

    @Test
    void typedStancesPreferTheHumanCorrectionAndFallBackToTheMachineReading() {
        var machine = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of(),
                false, "Preferred machine text", List.of("Fallback machine text"),
                "Never accept machine text", List.of(), List.of(), false, List.of(), false);
        var corrected = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of(),
                false, "Preferred corrected text", List.of("Fallback corrected text"),
                "", List.of(), List.of(), false, List.of(), false);

        var untouched = candidate(machine, null);
        assertThat(untouched.effectivePreferredText()).isEqualTo("Preferred machine text");
        assertThat(untouched.effectiveFallbackTexts()).containsExactly("Fallback machine text");
        assertThat(untouched.effectiveProhibitedText()).isEqualTo("Never accept machine text");

        var edited = candidate(machine, corrected);
        assertThat(edited.effectivePreferredText()).isEqualTo("Preferred corrected text");
        assertThat(edited.effectiveFallbackTexts()).containsExactly("Fallback corrected text");
        // an empty correction is a deliberate clear, not a fallback to the machine value
        assertThat(edited.effectiveProhibitedText()).isNull();
    }

    @Test
    void aClearedRuleListIsNoRuleAndAnOlderEditStillFallsBackToTheMachineRules() {
        var rule = new AuthorityRule("General Counsel", Set.of(PositionRole.FALLBACK), null,
                AuthorityLevel.MATRIX, "the General Counsel approves fallbacks");
        var machine = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of(),
                false, null, List.of(), null, List.of(rule), List.of(), false, List.of(), false);
        var cleared = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of(),
                false, null, List.of(), null, List.of(), List.of(), true, List.of(), false);
        var legacyEdit = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of());

        assertThat(candidate(machine, cleared).effectiveAuthorityRules()).isEmpty();
        assertThat(candidate(machine, legacyEdit).effectiveAuthorityRules()).containsExactly(rule);
    }

    @Test
    void aRecordWithNoRoleIsNotAPosition() {
        var noRole = candidate(new SemanticFields(Set.of(), null, null, null, Map.of()), null);
        var escalationOnly = candidate(new SemanticFields(
                Set.of(PositionRole.ESCALATION), null, "Escalate to counsel", null, Map.of()), null);
        var statedPreferred = candidate(new SemanticFields(
                Set.of(PositionRole.PREFERRED), null, null, null, Map.of()), null);
        var roleTaggedValue = candidateWithColumns(
                new SemanticFields(Set.of(), null, null, null, Map.of()),
                Map.of("Preferred", "Cap at fees paid."));

        assertThat(noRole.carriesPosition()).isFalse();
        assertThat(escalationOnly.carriesPosition()).isFalse();
        assertThat(statedPreferred.carriesPosition()).isTrue();
        assertThat(roleTaggedValue.carriesPosition()).isTrue();
    }

    @Test
    void aClearedFallbackListStaysClearedButAnOlderEditFallsBackToTheMachineList() {
        var machine = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of(),
                false, null, List.of("Machine fallback"), null, List.of(), List.of(), false,
                List.of(), false);
        var cleared = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of(),
                false, null, List.of(), null, List.of(), List.of(), false, List.of(), true);
        var legacyEdit = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of());

        assertThat(candidate(machine, cleared).effectiveFallbackTexts()).isEmpty();
        assertThat(candidate(machine, cleared).fallbackTextsCleared()).isTrue();
        assertThat(candidate(machine, legacyEdit).effectiveFallbackTexts())
                .containsExactly("Machine fallback");
    }

    @Test
    void aHumanCorrectionClearsTheUnmappedNoticeAndTheMachineConflicts() {
        var rule = new AuthorityRule("General Counsel", Set.of(PositionRole.FALLBACK), null,
                AuthorityLevel.MATRIX, "the General Counsel approves fallbacks");
        var machine = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of(),
                false, null, List.of(), null, List.of(rule),
                List.of(new AuthorityConflict("Commercial Manager", AuthorityLevel.TABLE,
                        PositionRole.FALLBACK)),
                false, List.of("Deal-breaker: no retainer"), false);
        var corrected = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of(),
                false, "Corrected preferred", List.of(), null, List.of(), List.of(), true,
                List.of(), false);

        var edited = candidate(machine, corrected);
        assertThat(edited.effectiveUnmappedAuthority()).isEmpty();
        assertThat(edited.effectiveAuthorityConflicts()).isEmpty();
        assertThat(edited.effectiveAuthorityRules()).isEmpty();
    }

    @Test
    void aCorrectionThatDoesNotChangeATypedValueKeepsTheUnmappedNotice() {
        var machine = new SemanticFields(Set.of(PositionRole.FALLBACK), true, null, null, Map.of(),
                false, "Machine preferred", List.of(), null, List.of(), List.of(), false,
                List.of("Deal-breaker: no retainer"), false);
        // the form sends the complete set back: same preferred, an empty fallback list it also
        // marked cleared, and no rules. Only the approval boolean changed.
        var unrelatedEdit = new SemanticFields(Set.of(PositionRole.FALLBACK), false, null, null,
                Map.of(), false, "Machine preferred", List.of(), null, List.of(), List.of(),
                false, List.of(), true);
        // clearing the machine's preferred wording is a real correction
        var cleared = new SemanticFields(Set.of(PositionRole.FALLBACK), true, null, null, Map.of(),
                false, "", List.of(), null, List.of(), List.of(), false, List.of(), true);

        assertThat(candidate(machine, unrelatedEdit).effectiveUnmappedAuthority())
                .containsExactly("Deal-breaker: no retainer");
        assertThat(candidate(machine, cleared).effectiveUnmappedAuthority()).isEmpty();
    }

    private static KnowledgeCandidate candidateWithColumns(
            SemanticFields machine, Map<String, String> columns) {
        var now = java.time.Instant.now();
        return new KnowledgeCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CandidateType.PLAYBOOK_RULE, ReviewStatus.PENDING, "Payment terms", "wording",
                new CandidatePayload(columns),
                "PAYMENT_TERMS", List.of("PAYMENT_TERMS"),
                null, null, null, machine, null, List.of(), null, null, null,
                new ApplicabilityScope(null, Set.of(), null, null, null, null, null),
                List.of(), null, null, null, null, now, now, null);
    }
}
