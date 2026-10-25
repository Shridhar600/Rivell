package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.knowledge.ApplicabilityScope;
import com.rivell.workbench.knowledge.AuthorityConflict;
import com.rivell.workbench.knowledge.AuthorityLevel;
import com.rivell.workbench.knowledge.AuthorityRule;
import com.rivell.workbench.knowledge.CandidatePayload;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.PositionRole;
import com.rivell.workbench.knowledge.ReviewStatus;
import com.rivell.workbench.knowledge.SemanticFields;
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
        // the catalog's covering expansion is applied when a corrected rule is read, so the
        // compile view and the review decision see the same stances
        var rules = correctedRules(new AuthorityRule("General Counsel", Set.of(), null,
                AuthorityLevel.ENTRY, "the entry names General Counsel"));
        var facts = facts(rules, List.of(), null);

        assertThat(rules).anySatisfy(rule -> {
            assertThat(rule.approver()).isEqualTo("General Counsel");
            assertThat(rule.stances()).contains(PositionRole.PROHIBITED);
        });
        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WITHIN_FALLBACK)
                .rule().approver()).isEqualTo("General Counsel");
        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WALK_AWAY)
                .rule().approver()).isEqualTo("General Counsel");
    }

    @Test
    void aFallbackOnlyRuleCoversTheWalkAway() {
        var rules = correctedRules(rule("The lawyer handling the NDA",
                PositionRole.FALLBACK, null, AuthorityLevel.ENTRY));
        var facts = facts(rules, List.of(), null);

        assertThat(rules).anySatisfy(rule -> {
            assertThat(rule.approver()).isEqualTo("The lawyer handling the NDA");
            assertThat(rule.stances()).contains(PositionRole.PROHIBITED);
        });
        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WITHIN_FALLBACK)
                .rule().approver()).isEqualTo("The lawyer handling the NDA");
        assertThat(EscalationAuthority.decide(facts, PolicyComparisonSpecs.WALK_AWAY)
                .rule().approver()).isEqualTo("The lawyer handling the NDA");
    }

    /** The rules a corrected candidate is read with, the same list review receives. */
    private static List<AuthorityRule> correctedRules(AuthorityRule... corrected) {
        var machine = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of());
        var edited = new SemanticFields(Set.of(PositionRole.FALLBACK), null, null, null, Map.of(),
                false, null, List.of(), null, List.of(corrected), List.of(), false, List.of(), false);
        var now = Instant.parse("2026-10-25T10:00:00Z");
        var candidate = new KnowledgeCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CandidateType.PLAYBOOK_RULE, ReviewStatus.PENDING, "G-LIAB-01", "wording",
                new CandidatePayload(Map.of("Position", "Fallback")),
                "LIMITATION_OF_LIABILITY", List.of("LIMITATION_OF_LIABILITY"),
                null, null, null, machine, edited, List.of(), null, null, null,
                new ApplicabilityScope(null, Set.of(), null, null, null, null, null),
                List.of(), null, null, null, null, now, now, null);
        return candidate.effectiveAuthorityRules();
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
