package com.rivell.workbench.review.check;

import java.util.LinkedHashMap;
import java.util.Map;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.PolicyComparisonSpecs;

/**
 * The code-owned outcome of one judged position (design §6a item 4): a position flags
 * when the summed probability of the four issue-bearing options reaches the threshold,
 * and the category is the argmax of those four — never the provider's own selection.
 */
public final class PolicyComparisonOutcome {

    /** P(MEETS_PREFERRED) + flag mass at or above this line means the position addresses the clause. */
    private static final double ADDRESSED = 0.5;

    private PolicyComparisonOutcome() {
    }

    public record Judgement(
            String category, double flagProbability, boolean flagged,
            Map<String, Double> probabilities) {

        public Judgement {
            probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
        }
    }

    /**
     * The one "addresses" rule both the clause checks and the omissions lane use: a position
     * addresses a clause when its preferred mass plus its flag mass reaches the line.
     */
    public static boolean addresses(Map<String, Double> probabilities, double flagProbability) {
        return probabilities.getOrDefault(PolicyComparisonSpecs.MEETS_PREFERRED, 0.0)
                + flagProbability >= ADDRESSED;
    }

    public static Judgement judge(ChoiceAnswer answer, double flagThreshold) {
        var probabilities = answer.probabilities();
        var flagProbability = PolicyComparisonSpecs.FLAGGING.stream()
                .mapToDouble(option -> probabilities.getOrDefault(option, 0.0))
                .sum();
        String category = null;
        if (flagProbability >= flagThreshold) {
            category = PolicyComparisonSpecs.FLAGGING.stream()
                    .max(java.util.Comparator.comparingDouble(
                            option -> probabilities.getOrDefault(option, 0.0)))
                    .orElse(null);
        }
        return new Judgement(category, flagProbability, category != null,
                new LinkedHashMap<>(probabilities));
    }

    /** One issue family per category so different categories stay separate findings. */
    public static IssueFamily family(String category) {
        return switch (category) {
            case PolicyComparisonSpecs.WALK_AWAY -> IssueFamily.POSITION_WALK_AWAY;
            case PolicyComparisonSpecs.BEYOND_FALLBACK -> IssueFamily.POSITION_NEEDS_FALLBACK;
            case PolicyComparisonSpecs.WITHIN_FALLBACK -> IssueFamily.POSITION_ACCEPTABLE_FALLBACK;
            case PolicyComparisonSpecs.UNCLEAR -> IssueFamily.POSITION_AMBIGUOUS;
            default -> throw new IllegalArgumentException("Not a flagging category: " + category);
        };
    }
}
