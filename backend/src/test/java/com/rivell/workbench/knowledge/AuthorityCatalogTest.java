package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Council §A.1-A.2: the most specific typed rule wins for one stance, and a lower level that
 * names a different approver is kept as a visible conflict rather than hidden. A stance with
 * no rule behind it has no approval at all.
 */
class AuthorityCatalogTest {

    private static AuthorityRule rule(
            String approver, Set<PositionRole> stances, String condition,
            AuthorityLevel level) {
        return new AuthorityRule(approver, stances, condition, level, approver + " rule");
    }

    @Test
    void anEntryApproverBeatsTheMatrixEvenWhenTheMatrixNamesTheStance() {
        var catalog = AuthorityCatalog.of(List.of(
                new AuthoritySourceRule(rule("Commercial Manager",
                        Set.of(PositionRole.FALLBACK), null, AuthorityLevel.MATRIX), "fallbacks")));
        var entry = rule("General Counsel", Set.of(), null, AuthorityLevel.ENTRY);

        var effective = catalog.merge("Limitation of liability", "", List.of(entry));

        // council §A.1: an approver stated on the entry beats the approval matrix
        assertThat(effective.rule(PositionRole.FALLBACK).approver())
                .isEqualTo("General Counsel");
        assertThat(effective.rule(PositionRole.PROHIBITED).approver())
                .isEqualTo("General Counsel");
        assertThat(effective.conflicts())
                .filteredOn(conflict -> conflict.stance() == PositionRole.FALLBACK)
                .singleElement()
                .satisfies(conflict -> {
                    assertThat(conflict.approver()).isEqualTo("Commercial Manager");
                    assertThat(conflict.source()).isEqualTo(AuthorityLevel.MATRIX);
                });
    }

    @Test
    void aConditionBearingRuleBeatsANoConditionRuleAtTheSameLevel() {
        var catalog = AuthorityCatalog.of(List.of(
                new AuthoritySourceRule(rule("General Counsel",
                        Set.of(PositionRole.PROHIBITED), null, AuthorityLevel.MATRIX),
                        "walk-away breach"),
                new AuthoritySourceRule(rule("General Counsel",
                        Set.of(PositionRole.PROHIBITED),
                        "Any deviation from a walk-away", AuthorityLevel.MATRIX),
                        "deviation from a walk-away")));

        var effective = catalog.merge("Limitation of liability", "", List.of());

        assertThat(effective.rule(PositionRole.PROHIBITED).condition())
                .isEqualTo("Any deviation from a walk-away");
        // both same-approver conditions survive as separate rules (council §E)
        assertThat(effective.rules()).extracting(AuthorityRule::condition)
                .containsExactly("Any deviation from a walk-away", null);
    }

    @Test
    void anEntryRuleThatNamesTheStanceBeatsTheMatrix() {
        var catalog = AuthorityCatalog.of(List.of(
                new AuthoritySourceRule(rule("Commercial Legal Director",
                        Set.of(PositionRole.FALLBACK), "if the rest of the agreement is mutual",
                        AuthorityLevel.MATRIX), "fallbacks")));
        var entry = rule("Commercial Legal Director", Set.of(PositionRole.FALLBACK),
                "if the rest of the agreement is mutual", AuthorityLevel.ENTRY);

        var effective = catalog.merge("Governing law", "", List.of(entry));

        assertThat(effective.rule(PositionRole.FALLBACK).approver())
                .isEqualTo("Commercial Legal Director");
        assertThat(effective.rule(PositionRole.FALLBACK).condition())
                .isEqualTo("the rest of the agreement is mutual");
        assertThat(effective.conflicts()).isEmpty();
    }

    @Test
    void theMatrixBeatsAnApprovalTableRow() {
        var catalog = AuthorityCatalog.of(List.of(
                new AuthoritySourceRule(rule("The lawyer handling the NDA",
                        Set.of(PositionRole.FALLBACK), null, AuthorityLevel.MATRIX), "fallbacks"),
                new AuthoritySourceRule(rule("Reviewing Commercial Legal counsel",
                        Set.of(PositionRole.FALLBACK), null, AuthorityLevel.TABLE),
                        "Routine term, marking, or return mechanics")));

        var effective = catalog.merge("Term and survival", "", List.of());

        assertThat(effective.rule(PositionRole.FALLBACK).approver())
                .isEqualTo("The lawyer handling the NDA");
        assertThat(effective.conflicts())
                .filteredOn(conflict -> conflict.stance() == PositionRole.FALLBACK)
                .singleElement()
                .satisfies(conflict -> assertThat(conflict.source())
                        .isEqualTo(AuthorityLevel.TABLE));
    }

    @Test
    void aTableRowIsUsedWhenNothingHigherNamesTheStance() {
        var catalog = AuthorityCatalog.of(List.of(
                new AuthoritySourceRule(rule("Commercial Legal Director",
                        Set.of(), "Wisconsin law or Milwaukee forum", AuthorityLevel.TABLE),
                        "Wisconsin law and Milwaukee forum")));

        var effective = catalog.merge("Governing law and jurisdiction", "", List.of());

        assertThat(effective.rule(PositionRole.PROHIBITED).approver())
                .isEqualTo("Commercial Legal Director");
        assertThat(effective.rule(PositionRole.PROHIBITED).condition())
                .isEqualTo("Wisconsin law or Milwaukee forum");
        assertThat(effective.conflicts()).isEmpty();
    }

    @Test
    void aMatrixRuleScopedToOtherEntriesDoesNotApplyHere() {
        var catalog = AuthorityCatalog.of(List.of(
                new AuthoritySourceRule(rule("Head of Finance",
                        Set.of(PositionRole.FALLBACK), null, AuthorityLevel.MATRIX),
                        "payment terms and set-off")));

        assertThat(catalog.merge("Confidentiality", "", List.of()).rules()).isEmpty();
        var payment = catalog.merge("Payment terms", "", List.of());
        assertThat(payment.rule(PositionRole.FALLBACK).approver()).isEqualTo("Head of Finance");
        assertThat(payment.rule(PositionRole.PROHIBITED).approver()).isEqualTo("Head of Finance");
    }

    @Test
    void anOwnershipRuleAppliesWhereItsObjectNamesTheEntry() {
        var catalog = AuthorityCatalog.of(List.of(
                new AuthoritySourceRule(rule("Head of Finance", Set.of(), null,
                        AuthorityLevel.MATRIX), "payment and insurance")));

        assertThat(catalog.merge("Payment terms", "", List.of()).rule(PositionRole.FALLBACK).approver())
                .isEqualTo("Head of Finance");
        assertThat(catalog.merge("Confidentiality", "", List.of()).rules()).isEmpty();
    }

    @Test
    void noRuleMeansNoApproval() {
        var effective = AuthorityCatalog.of(List.of()).merge("Limitation of liability", "", List.of());

        assertThat(effective.rules()).isEmpty();
        assertThat(effective.rule(PositionRole.FALLBACK)).isNull();
        assertThat(effective.rule(PositionRole.PROHIBITED)).isNull();
        assertThat(effective.conflicts()).isEmpty();
    }

    @Test
    void aFallbackApproverAlsoCoversTheWalkAwayWhenNoWalkAwayRuleExists() {
        var entry = rule("The lawyer handling the NDA", Set.of(PositionRole.FALLBACK),
                null, AuthorityLevel.ENTRY);

        var effective = AuthorityCatalog.of(List.of()).merge("Term and survival", "", List.of(entry));

        assertThat(effective.rule(PositionRole.PROHIBITED).approver())
                .isEqualTo("The lawyer handling the NDA");
    }

    @Test
    void aWalkAwayRuleDoesNotForceApprovalOnTheFallback() {
        var entry = rule("General Counsel", Set.of(PositionRole.PROHIBITED),
                null, AuthorityLevel.ENTRY);

        var effective = AuthorityCatalog.of(List.of()).merge("Limitation of liability", "", List.of(entry));

        assertThat(effective.rule(PositionRole.FALLBACK)).isNull();
        assertThat(effective.rule(PositionRole.PROHIBITED).approver())
                .isEqualTo("General Counsel");
    }
}
