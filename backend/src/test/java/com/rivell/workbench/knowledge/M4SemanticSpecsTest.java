package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class M4SemanticSpecsTest {

    @Test
    void scopeNarrowingSpecVersionPinsTheWidenedJurisdictionCriteria() {
        var spec = M4SemanticSpecs.scopeNarrowing();

        assertThat(spec.id()).isEqualTo(M4SemanticSpecs.SCOPE_NARROWING_SPEC);
        assertThat(spec.version()).isEqualTo(2);
    }

    @Test
    void thePlaybookSpecAsksOnlyTheStancesTheTextLeavesOpen() {
        var full = M4SemanticSpecs.playbookPositions();
        // pinned literally: this version is what execution records name
        assertThat(full.version()).isEqualTo(2);
        assertThat(KnowledgeRecordSemantics.FIELD_ROLE_VERSION).isEqualTo(2);
        assertThat(full.pack().questions().keySet())
                .contains("role_preferred", "role_fallback", "requires_approval");

        var missing = M4SemanticSpecs.playbookPositions(
                java.util.Set.of(PositionRole.COMMENTARY), false);
        assertThat(missing.pack().questions().keySet()).containsExactly("role_commentary");
    }
}
