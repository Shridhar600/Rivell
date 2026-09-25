package com.rivell.workbench.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ReasoningSpecTest {

    private static Map<String, String> fields() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("capBasis", "cap basis");
        fields.put("evidenceIds", "evidence ids");
        return fields;
    }

    @Test
    void allowedValuesOnUnknownFieldAreRejectedAtSpecAuthoring() {
        assertThatThrownBy(() -> new ReasoningSpec(
                "cap-spec", 1, "Extract cap facts.", 1, fields(),
                Map.of("cap_Basis", Set.of("ANNUAL_FEES"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cap_Basis");
    }

    @Test
    void listShapeOnUnknownFieldIsRejectedAtSpecAuthoring() {
        assertThatThrownBy(() -> new ReasoningSpec(
                "cap-spec", 1, "Extract cap facts.", 1, fields(),
                Map.of("capBasis", Set.of("ANNUAL_FEES")),
                Set.of("evidence_ids")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence_ids");
    }

    @Test
    void validSpecRetainsDeclarations() {
        var spec = new ReasoningSpec(
                "cap-spec", 1, "Extract cap facts.", 1, fields(),
                Map.of("capBasis", Set.of("ANNUAL_FEES")),
                Set.of("evidenceIds"));

        assertThat(spec.allowedValues()).containsOnlyKeys("capBasis");
        assertThat(spec.listFields()).containsExactly("evidenceIds");
    }
}
