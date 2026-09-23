package com.rivell.workbench.intelligence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutually-exclusive selection among named options. Criterion order is meaningful and preserved.
 */
public record ChoiceQuestion(String instructions, Map<String, String> criteria)
        implements SemanticQuestion {

    public ChoiceQuestion {
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("Instructions must not be blank");
        }
        if (criteria == null || criteria.isEmpty()) {
            throw new IllegalArgumentException("A choice question requires at least one criterion");
        }
        criteria = new LinkedHashMap<>(criteria);
    }
}
