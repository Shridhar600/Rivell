package com.rivell.workbench.review.check;

import com.rivell.workbench.knowledge.AuthorityConflict;
import com.rivell.workbench.knowledge.AuthorityRule;
import com.rivell.workbench.knowledge.PositionRole;
import com.rivell.workbench.review.EscalationPayload;
import com.rivell.workbench.review.PolicyComparisonSpecs;
import com.rivell.workbench.retrieval.EvidenceItem;

/**
 * Council §A.3: a finding escalates only when a typed rule applies. A walk-away or a clause
 * beyond the fallback escalates with the rule that covers that stance; a within-fallback note
 * needs approval only when a rule says the fallback itself needs it. A condition becomes the
 * "Escalate if" line. No rule means no approval, never an inferred one.
 */
final class EscalationAuthority {

    /**
     * The winning rule for one comparison category, the stance it was read for, and the
     * lower-level rule that names a different approver for that stance.
     */
    record Decision(AuthorityRule rule, PositionRole stance, AuthorityConflict conflict) {

        EscalationPayload payload(String fallbackTrigger, Boolean requiresApproval) {
            return new EscalationPayload(
                    rule.condition() != null ? rule.condition() : fallbackTrigger,
                    requiresApproval,
                    rule.approver(),
                    rule.condition(),
                    rule.source().name(),
                    conflict == null ? null : conflict.approver(),
                    conflict == null ? null : conflict.source().name(),
                    stance.name());
        }
    }

    private EscalationAuthority() {
    }

    static Decision decide(EvidenceItem.PolicyFacts facts, String category) {
        if (facts == null || category == null) {
            return null;
        }
        if (PolicyComparisonSpecs.WALK_AWAY.equals(category)) {
            // the compile fills the walk-away winner from a fallback rule too, because a
            // fallback approver can approve anything beyond the preferred position
            return decision(facts, rule(facts, PositionRole.PROHIBITED), PositionRole.PROHIBITED);
        }
        if (PolicyComparisonSpecs.BEYOND_FALLBACK.equals(category)
                || PolicyComparisonSpecs.UNCLEAR.equals(category)) {
            var fallback = rule(facts, PositionRole.FALLBACK);
            if (fallback != null) {
                return decision(facts, fallback, PositionRole.FALLBACK);
            }
            return decision(facts, rule(facts, PositionRole.PROHIBITED), PositionRole.PROHIBITED);
        }
        if (PolicyComparisonSpecs.WITHIN_FALLBACK.equals(category)) {
            // within the fallback needs approval only when a rule says the fallback needs it
            return decision(facts, rule(facts, PositionRole.FALLBACK), PositionRole.FALLBACK);
        }
        return null;
    }

    private static Decision decision(
            EvidenceItem.PolicyFacts facts, AuthorityRule rule, PositionRole stance) {
        if (rule == null) {
            return null;
        }
        var conflict = facts.authorityConflicts().stream()
                .filter(candidate -> candidate.stance() == stance)
                .findFirst().orElse(null);
        return new Decision(rule, stance, conflict);
    }

    /**
     * The highest rule that names the stance is the winner; a rule that only implies the stance
     * (a fallback approver covers a walk-away) is used only when no rule names it. This keeps a
     * matrix fallback rule from displacing the entry's own walk-away approver (council §A.1).
     */
    private static AuthorityRule rule(EvidenceItem.PolicyFacts facts, PositionRole stance) {
        return facts.authorityRules().stream()
                .filter(candidate -> candidate.stances().contains(stance))
                .findFirst()
                .orElseGet(() -> facts.authorityRules().stream()
                        .filter(candidate -> covers(candidate, stance))
                        .findFirst().orElse(null));
    }

    private static boolean covers(AuthorityRule rule, PositionRole stance) {
        if (stance == PositionRole.PREFERRED) {
            return rule.stances().contains(PositionRole.PREFERRED);
        }
        if (rule.stances().isEmpty() || rule.stances().contains(stance)) {
            return true;
        }
        return stance == PositionRole.PROHIBITED
                && rule.stances().contains(PositionRole.FALLBACK);
    }
}
