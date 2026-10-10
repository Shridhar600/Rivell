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
}
