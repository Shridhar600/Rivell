package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.knowledge.AuthorityConflict;
import com.rivell.workbench.knowledge.AuthorityLevel;
import com.rivell.workbench.knowledge.AuthorityRule;
import com.rivell.workbench.knowledge.PositionRole;
import com.rivell.workbench.review.PolicyComparisonSpecs;
import com.rivell.workbench.retrieval.EvidenceItem;

/**
 * Council §A.3: an approval exists only when a typed rule covers the finding's stance. A
 * trigger text alone never makes one, a within-fallback note escalates only when the rule says
 * the fallback needs it, and the rule's own condition is what the "Escalate if" line shows.
 */
class EscalationAuthorityTest {

    private static EvidenceItem.PolicyFacts facts(
            List<AuthorityRule> rules, List<AuthorityConflict> conflicts, String trigger) {
        return new EvidenceItem.PolicyFacts(Set.of(PositionRole.FALLBACK), null, trigger,
                "Cap at fees paid.", List.of("Cap at two times fees."), "Unlimited liability.",
                rules, conflicts);
    }

    private static AuthorityRule rule(
            String approver, PositionRole stance, String condition, AuthorityLevel level) {
        return new AuthorityRule(approver, Set.of(stance), condition, level, approver + " rule");
    }

    @Test
    void aTriggerTextAloneMakesNoApproval() {
        var facts = facts(List.of(), List.of(), "Escalate above net 90");

        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.BEYOND_FALLBACK))
                .isNull();
        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WALK_AWAY)).isNull();
        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WITHIN_FALLBACK))
                .isNull();
    }

    @Test
    void withinFallbackNeedsApprovalOnlyWhenTheRuleNamesTheFallback() {
        var walkAwayOnly = facts(List.of(rule("General Counsel",
                PositionRole.PROHIBITED, null, AuthorityLevel.ENTRY)), List.of(), null);
        var fallbackRule = facts(List.of(rule("Commercial Manager",
                PositionRole.FALLBACK, null, AuthorityLevel.MATRIX)), List.of(), null);

        assertThat(EscalationAuthority.decide(walkAwayOnly,
                PolicyComparisonSpecs.WITHIN_FALLBACK)).isNull();
        var decision = EscalationAuthority.decide(fallbackRule,
                PolicyComparisonSpecs.WITHIN_FALLBACK);
        assertThat(decision.rule().approver()).isEqualTo("Commercial Manager");
        assertThat(decision.payload(null, Boolean.TRUE).approver())
                .isEqualTo("Commercial Manager");
    }

    @Test
    void aConditionBecomesTheEscalateIfLine() {
        var facts = facts(List.of(rule("Commercial Legal Director", PositionRole.FALLBACK,
                "the rest of the agreement is mutual", AuthorityLevel.ENTRY)), List.of(), null);

        var payload = EscalationAuthority.decide(facts, PolicyComparisonSpecs.WITHIN_FALLBACK)
                .payload(facts.escalationTrigger(), Boolean.TRUE);

        assertThat(payload.trigger()).isEqualTo("the rest of the agreement is mutual");
        assertThat(payload.condition()).isEqualTo("the rest of the agreement is mutual");
        assertThat(payload.source()).isEqualTo("ENTRY");
        assertThat(payload.stance()).isEqualTo("FALLBACK");
    }

    @Test
    void aWalkAwayRuleEscalatesAWalkAwayAndIsNamedWithItsSource() {
        var facts = facts(List.of(rule("General Counsel", PositionRole.PROHIBITED, null,
                AuthorityLevel.MATRIX)), List.of(), "walk-away breach");

        var decision = EscalationAuthority.decide(facts, PolicyComparisonSpecs.WALK_AWAY);
        var payload = decision.payload(facts.escalationTrigger(), Boolean.TRUE);

        assertThat(payload.approver()).isEqualTo("General Counsel");
        assertThat(payload.source()).isEqualTo("MATRIX");
        assertThat(payload.trigger()).isEqualTo("walk-away breach");
        assertThat(payload.conflictingApprover()).isNull();
    }

    @Test
    void beyondFallbackPrefersTheFallbackRuleAndNeverThePreferredPosition() {
        var fallback = facts(List.of(
                rule("Commercial Manager", PositionRole.FALLBACK, null, AuthorityLevel.MATRIX),
                rule("General Counsel", PositionRole.PROHIBITED, null, AuthorityLevel.MATRIX)),
                List.of(), null);
        var walkAwayOnly = facts(List.of(rule("General Counsel",
                PositionRole.PROHIBITED, null, AuthorityLevel.MATRIX)), List.of(), null);

        var decision = EscalationAuthority.decide(fallback,
                PolicyComparisonSpecs.BEYOND_FALLBACK);
        assertThat(decision.rule().approver()).isEqualTo("Commercial Manager");
        assertThat(decision.stance()).isEqualTo(PositionRole.FALLBACK);

        var walkAway = EscalationAuthority.decide(walkAwayOnly,
                PolicyComparisonSpecs.BEYOND_FALLBACK);
        assertThat(walkAway.rule().approver()).isEqualTo("General Counsel");
        assertThat(walkAway.stance()).isEqualTo(PositionRole.PROHIBITED);
    }

    @Test
    void aConflictIsCarriedSoThePanelCanNameTheOtherLevel() {
        var conflict = new AuthorityConflict("Commercial Manager", AuthorityLevel.MATRIX,
                PositionRole.FALLBACK);
        var facts = facts(List.of(rule("Commercial Legal Director", PositionRole.FALLBACK,
                null, AuthorityLevel.ENTRY)), List.of(conflict), null);

        var payload = EscalationAuthority.decide(facts, PolicyComparisonSpecs.WITHIN_FALLBACK)
                .payload(null, Boolean.TRUE);

        assertThat(payload.approver()).isEqualTo("Commercial Legal Director");
        assertThat(payload.conflictingApprover()).isEqualTo("Commercial Manager");
        assertThat(payload.conflictingSource()).isEqualTo("MATRIX");
    }

    @Test
    void anEmptyStanceSetRuleCoversTheFallbackAndTheWalkAway() {
        var facts = facts(List.of(new AuthorityRule("General Counsel", Set.of(), null,
                AuthorityLevel.ENTRY, "the entry names General Counsel")), List.of(), null);

        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WITHIN_FALLBACK)
                .rule().approver()).isEqualTo("General Counsel");
        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WALK_AWAY)
                .rule().approver()).isEqualTo("General Counsel");
    }

    @Test
    void aFallbackOnlyRuleCoversTheWalkAway() {
        var facts = facts(List.of(rule("The lawyer handling the NDA",
                PositionRole.FALLBACK, null, AuthorityLevel.ENTRY)), List.of(), null);

        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WITHIN_FALLBACK)
                .rule().approver()).isEqualTo("The lawyer handling the NDA");
        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WALK_AWAY)
                .rule().approver()).isEqualTo("The lawyer handling the NDA");
    }

    @Test
    void aFallbackRuleNeverDisplacesTheExplicitWalkAwayRule() {
        var facts = facts(List.of(
                rule("Commercial Manager", PositionRole.FALLBACK, null, AuthorityLevel.MATRIX),
                rule("General Counsel", PositionRole.PROHIBITED, null, AuthorityLevel.ENTRY)),
                List.of(), null);

        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WALK_AWAY)
                .rule().approver()).isEqualTo("General Counsel");
        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WITHIN_FALLBACK)
                .rule().approver()).isEqualTo("Commercial Manager");
    }

    @Test
    void anAgreedPreferredPositionNeverEscalates() {
        var facts = facts(List.of(rule("General Counsel", PositionRole.FALLBACK, null,
                AuthorityLevel.MATRIX)), List.of(), null);

        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.MEETS_PREFERRED))
                .isNull();
    }
}
