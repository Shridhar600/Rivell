package com.rivell.workbench.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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

    @Test
    void unorderedSourcesAreSortedSoIdenticalSpecsHaveIdenticalOrder() {
        var expectedFields = List.of("aa", "b", "listA", "listB");
        var expectedAllowedKeys = List.of("b", "listA");
        var expectedListFields = List.of("listA", "listB");

        var hashFields = new HashMap<String, String>();
        hashFields.put("listA", "listA description");
        hashFields.put("b", "b description");
        hashFields.put("listB", "listB description");
        hashFields.put("aa", "aa description");
        var hashAllowedValues = new HashMap<String, Set<String>>();
        hashAllowedValues.put("listA", Set.of("N", "M"));
        hashAllowedValues.put("b", Set.of("Z", "A"));
        var hashListFields = new java.util.HashSet<String>();
        hashListFields.add("listB");
        hashListFields.add("listA");

        var specs = List.of(
                new ReasoningSpec("ordered-spec", 1, "Extract fields.", 1,
                        Map.of("b", "b description", "aa", "aa description",
                                "listB", "listB description", "listA", "listA description"),
                        Map.of("listA", Set.of("N", "M"), "b", Set.of("Z", "A")),
                        Set.of("listB", "listA")),
                new ReasoningSpec("ordered-spec", 1, "Extract fields.", 1, hashFields,
                        hashAllowedValues, hashListFields),
                new ReasoningSpec("ordered-spec", 1, "Extract fields.", 1,
                        new TreeMap<>(hashFields),
                        new TreeMap<>(hashAllowedValues),
                        new java.util.TreeSet<>(hashListFields)));

        for (var spec : specs) {
            assertThat(spec.fields().keySet()).containsExactlyElementsOf(expectedFields);
            assertThat(spec.allowedValues().keySet())
                    .containsExactlyElementsOf(expectedAllowedKeys);
            assertThat(spec.allowedValues().get("b")).containsExactly("A", "Z");
            assertThat(spec.allowedValues().get("listA")).containsExactly("M", "N");
            assertThat(spec.listFields()).containsExactlyElementsOf(expectedListFields);
        }

        var first = specs.get(0);
        assertThatThrownBy(() -> first.fields().put("x", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.allowedValues().put("x", Set.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.listFields().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void orderedSourcesKeepTheCallersOrder() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("z", "z description");
        fields.put("a", "a description");
        fields.put("y", "y description");
        fields.put("x", "x description");
        var values = new LinkedHashSet<String>();
        values.add("Z");
        values.add("A");
        var allowedValues = new LinkedHashMap<String, Set<String>>();
        allowedValues.put("z", values);
        var listFields = new LinkedHashSet<String>();
        listFields.add("y");
        listFields.add("x");

        var spec = new ReasoningSpec("ordered-spec", 1, "Extract fields.", 1,
                fields, allowedValues, listFields);

        assertThat(spec.fields().keySet()).containsExactly("z", "a", "y", "x");
        assertThat(spec.allowedValues().keySet()).containsExactly("z");
        assertThat(spec.allowedValues().get("z")).containsExactly("Z", "A");
        assertThat(spec.listFields()).containsExactly("y", "x");
    }
}
