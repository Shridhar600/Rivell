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
}
