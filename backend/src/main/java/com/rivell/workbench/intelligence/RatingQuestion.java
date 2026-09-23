package com.rivell.workbench.intelligence;

import java.util.List;

/**
 * Rating against an ordered rubric. Documented provider bound: at least two levels, up to ten.
 */
public record RatingQuestion(String instructions, List<String> levels) implements SemanticQuestion {

    private static final int MIN_LEVELS = 2;
    private static final int MAX_LEVELS = 10;

    public RatingQuestion {
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("Instructions must not be blank");
        }
        if (levels == null || levels.size() < MIN_LEVELS || levels.size() > MAX_LEVELS) {
            throw new IllegalArgumentException(
                    "A rating question requires between " + MIN_LEVELS + " and " + MAX_LEVELS + " levels");
        }
        levels = List.copyOf(levels);
    }
}
