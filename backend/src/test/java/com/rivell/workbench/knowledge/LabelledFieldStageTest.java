package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The labelled-field stage is deterministic and model-free: a label the source states
 * explicitly becomes a typed stance or an authority rule, and a label-shaped statement the
 * stage cannot map is reported instead of silently dropped.
 */
class LabelledFieldStageTest {

    @Test
    void explicitLineLabelsBecomeTypedStances() {
        var text = String.join("\n",
                "Preferred position: Net forty-five (45) days from a valid tax invoice.",
                "Fallback position: Net thirty (30) days from a valid tax invoice.",
                "Walk-away: Payment in advance, or a deposit above twenty per cent.",
                "Approver: Head of Finance.");

        var parsed = LabelledFieldStage.parse("Payment terms", text, Map.of());

        assertThat(parsed.preferred())
                .isEqualTo("Net forty-five (45) days from a valid tax invoice.");
        assertThat(parsed.fallbacks())
                .containsExactly("Net thirty (30) days from a valid tax invoice.");
        assertThat(parsed.prohibited())
                .isEqualTo("Payment in advance, or a deposit above twenty per cent.");
        assertThat(parsed.entryRules()).singleElement().satisfies(rule -> {
            assertThat(rule.approver()).isEqualTo("Head of Finance");
            assertThat(rule.source()).isEqualTo(AuthorityLevel.ENTRY);
            assertThat(rule.stances()).isEmpty();
            assertThat(rule.quote()).contains("Approver: Head of Finance");
        });
        assertThat(parsed.unmapped()).isEmpty();
    }

    @Test
    void numberedFallbacksAndStanceRowCellsAreBothRead() {
        var text = String.join("\n",
                "Fallback 1: Cap at 100% of fees.",
                "Fallback 2: Cap at 150% of fees.");

        assertThat(LabelledFieldStage.parse("Liability", text, Map.of()).fallbacks())
                .containsExactly("Cap at 100% of fees.", "Cap at 150% of fees.");

        var columns = new java.util.LinkedHashMap<String, String>();
        columns.put("Position", "Fallback");
        columns.put("Trigger", "Counterparty insists on a higher cap");
        columns.put("Guidance", "Accept a liability cap at two times annual fees.");
        columns.put("Approval", "General counsel approval");

        var row = LabelledFieldStage.parse("Liability", "Position Fallback", columns);

        assertThat(row.fallbacks()).containsExactly("Accept a liability cap at two times annual fees.");
        assertThat(row.entryRules()).singleElement().satisfies(rule -> {
            assertThat(rule.approver()).isEqualTo("General counsel");
            assertThat(rule.stances()).containsExactly(PositionRole.FALLBACK);
            assertThat(rule.condition()).isEqualTo("Counterparty insists on a higher cap");
        });
    }

    @Test
    void spreadsheetStanceColumnsAndSignOffColumnAreRead() {
        var columns = new java.util.LinkedHashMap<String, String>();
        columns.put("Position", "H-01");
        columns.put("Topic", "Limitation of liability");
        columns.put("Our stance", "Cap liability at the fees paid in the preceding 12 months.");
        columns.put("Can live with", "Cap at the greater of the fees paid or CAD 250,000.");
        columns.put("Never", "Unlimited liability.");
        columns.put("Who signs off", "General Counsel");

        var parsed = LabelledFieldStage.parse("Limitation of liability", "", columns);

        assertThat(parsed.preferred()).isEqualTo("Cap liability at the fees paid in the preceding 12 months.");
        assertThat(parsed.fallbacks()).containsExactly("Cap at the greater of the fees paid or CAD 250,000.");
        assertThat(parsed.prohibited()).isEqualTo("Unlimited liability.");
        // a sign-off column in a sheet with a Never column is the walk-away authority
        assertThat(parsed.entryRules()).singleElement().satisfies(rule -> {
            assertThat(rule.approver()).isEqualTo("General Counsel");
            assertThat(rule.stances()).containsExactly(PositionRole.PROHIBITED);
        });
    }

    @Test
    void anImperativeEscalationLineIsARuleOnlyWhenItNamesAnApprover() {
        var withApprover = LabelledFieldStage.parse("Liability", String.join("\n",
                "Preferred: Cap at the fees paid in the preceding 12 months.",
                "Escalate to general counsel before agreeing to any cap above the preferred position."),
                Map.of());

        assertThat(withApprover.escalationTrigger())
                .isEqualTo("Escalate to general counsel before agreeing to any cap above the preferred position");
        assertThat(withApprover.entryRules()).singleElement().satisfies(rule -> {
            assertThat(rule.approver()).isEqualTo("general counsel");
            assertThat(rule.condition()).isEqualTo("agreeing to any cap above the preferred position");
        });

        var withoutApprover = LabelledFieldStage.parse("Term", String.join("\n",
                "Preferred: Three years.",
                "Escalate shorter periods for source code or customer data."), Map.of());

        assertThat(withoutApprover.escalationTrigger())
                .isEqualTo("Escalate shorter periods for source code or customer data");
        assertThat(withoutApprover.entryRules()).isEmpty();
    }

    @Test
    void aDocumentApprovalMatrixYieldsScopedRules() {
        var text = "Approval matrix: the Commercial Manager approves fallbacks. "
                + "The Head of Finance approves payment terms and set-off. "
                + "The General Counsel approves any matter that reaches a walk-away.";

        var rules = LabelledFieldStage.documentRules(text, Map.of());

        assertThat(rules).hasSize(3);
        assertThat(rules.get(0).rule().approver()).isEqualTo("Commercial Manager");
        assertThat(rules.get(0).rule().stances()).containsExactly(PositionRole.FALLBACK);
        assertThat(rules.get(0).rule().source()).isEqualTo(AuthorityLevel.MATRIX);
        assertThat(rules.get(1).rule().approver()).isEqualTo("Head of Finance");
        assertThat(rules.get(1).scope()).isEqualTo("payment terms and set-off");
        assertThat(rules.get(2).rule().stances()).containsExactly(PositionRole.PROHIBITED);
    }

    @Test
    void anApprovalTableRowYieldsATableRule() {
        var columns = new java.util.LinkedHashMap<String, String>();
        columns.put("Issue", "Wisconsin law and Milwaukee forum, or any arbitration or jury waiver");
        columns.put("Route to", "Commercial Legal Director; GC if any added dispute restriction");
        columns.put("Decision needed", "Before acceptance");

        var rules = LabelledFieldStage.documentRules("", columns);

        assertThat(rules).singleElement().satisfies(scoped -> {
            assertThat(scoped.rule().approver())
                    .isEqualTo("Commercial Legal Director; GC if any added dispute restriction");
            assertThat(scoped.rule().source()).isEqualTo(AuthorityLevel.TABLE);
            assertThat(scoped.rule().condition()).contains("Wisconsin law and Milwaukee forum");
            assertThat(scoped.rule().quote()).contains("Route to");
        });
    }

    @Test
    void anApprovalPathSentenceWithoutAStanceWordAppliesToTheApproverForThatEntry() {
        var rules = LabelledFieldStage.documentRules(
                "The lawyer handling the NDA may apply the fallbacks above and record the reason in the matter file.",
                Map.of());

        assertThat(rules).singleElement().satisfies(scoped -> {
            assertThat(scoped.rule().approver()).isEqualTo("The lawyer handling the NDA");
            assertThat(scoped.rule().stances()).containsExactly(PositionRole.FALLBACK);
            assertThat(scoped.rule().condition()).isNull();
        });
    }

    @Test
    void labelsAbsentLeavesEverythingEmptySoCompilationIsUnchanged() {
        var text = String.join("\n",
                "The supplier requires confidentiality of shared business information while "
                        + "limiting exposure, and governing law questions are handled case by case.",
                "Owner: Nadia Petrov, General Counsel.",
                "Version: 2.1. Last reviewed: 15 January 2026.");

        var parsed = LabelledFieldStage.parse("General guidance", text, Map.of());

        assertThat(parsed.preferred()).isNull();
        assertThat(parsed.fallbacks()).isEmpty();
        assertThat(parsed.prohibited()).isNull();
        assertThat(parsed.escalationTrigger()).isNull();
        assertThat(parsed.entryRules()).isEmpty();
        assertThat(parsed.unmapped()).isEmpty();
    }

    @Test
    void unmappedWalkAwayOrApproverWordsAreReported() {
        var parsed = LabelledFieldStage.parse("Liability", String.join("\n",
                "Preferred: Cap at the fees paid in the preceding 12 months.",
                "Deal-breaker: the supplier must never exclude the IP indemnity.",
                "Final say: the board."), Map.of());

        assertThat(parsed.preferred()).isNotNull();
        assertThat(parsed.unmapped()).hasSize(2);
        assertThat(parsed.unmapped().toString())
                .contains("Deal-breaker: the supplier must never exclude the IP indemnity.")
                .contains("Final say: the board.");
    }

    @Test
    void aLabelWithNoValueIsReportedRatherThanTreatedAsAbsent() {
        var parsed = LabelledFieldStage.parse("Liability", String.join("\n",
                "Preferred: Cap at the fees paid in the preceding 12 months.",
                "Approver:"), Map.of());

        assertThat(parsed.entryRules()).isEmpty();
        assertThat(parsed.unmapped()).containsExactly("Approver:");
    }

    @Test
    void aGenericReferenceToAnApproverIsNotAnApprovalRule() {
        var parsed = LabelledFieldStage.parse("Limitation of liability", String.join("\n",
                "Preferred: Cap at the fees paid.",
                "Walk-away: Unlimited liability.",
                "Escalate to the named approver at a walk-away."),
                Map.of());

        assertThat(parsed.escalationTrigger())
                .isEqualTo("Escalate to the named approver at a walk-away");
        assertThat(parsed.entryRules()).isEmpty();
    }

    @Test
    void aLabelOnItsOwnLineTakesTheNextLineAsItsValue() {
        var parsed = LabelledFieldStage.parse("Payment terms", String.join("\n",
                "Preferred:",
                "Net forty-five days from a valid tax invoice.",
                "Fallback:",
                "Net thirty days.",
                "Approver:"), Map.of());

        assertThat(parsed.preferred()).isEqualTo("Net forty-five days from a valid tax invoice.");
        assertThat(parsed.fallbacks()).containsExactly("Net thirty days.");
        // a blank approver is still a gap: nobody names who approves
        assertThat(parsed.unmapped()).containsExactly("Approver:");
    }

    @Test
    void aSpreadsheetApprovalMatrixColumnIsReadAsDocumentRules() {
        var columns = new java.util.LinkedHashMap<String, String>();
        columns.put("Owner", "Aoife Brennan, Head of Commercial Legal");
        columns.put("Approval matrix",
                "Privacy Office owns DPA positions. General Counsel approves any walk-away breach.");
        columns.put("Escalation",
                "Any deviation from a walk-away requires General Counsel written approval before signature.");

        assertThat(LabelledFieldStage.isAuthoritySection("Guide", "", columns)).isTrue();
        var rules = LabelledFieldStage.documentRules("", columns);
        assertThat(rules).anySatisfy(scoped -> {
            assertThat(scoped.rule().approver()).isEqualTo("General Counsel");
            assertThat(scoped.rule().stances()).containsExactly(PositionRole.PROHIBITED);
            assertThat(scoped.rule().source()).isEqualTo(AuthorityLevel.MATRIX);
        });
        assertThat(rules).anySatisfy(scoped ->
                assertThat(scoped.rule().condition()).contains("deviation from a walk-away"));
    }

    @Test
    void aPositionTableWithStanceColumnsIsNotAnApprovalTable() {
        var columns = new java.util.LinkedHashMap<String, String>();
        columns.put("Issue", "Liability cap");
        columns.put("Preferred", "Twelve months of fees.");
        columns.put("Fallback", "Eighteen months for large deals.");
        columns.put("Approver", "General Counsel");

        assertThat(LabelledFieldStage.isAuthoritySection("Limitation of liability", "", columns))
                .isFalse();
        assertThat(LabelledFieldStage.documentRules("", columns)).isEmpty();
    }

    @Test
    void aSectionMerelyTitledEscalationIsNotAnAuthoritySource() {
        assertThat(LabelledFieldStage.isAuthoritySection(
                "Escalation triggers", "Carve-outs beyond data breaches require escalation.", Map.of()))
                .isFalse();
        assertThat(LabelledFieldStage.isAuthoritySection("Escalation", "", Map.of())).isFalse();
        assertThat(LabelledFieldStage.isAuthoritySection(
                "Approval table", "", Map.of())).isTrue();
    }

    @Test
    void anApprovalStatedInThePassiveVoiceIsStillARule() {
        var rules = LabelledFieldStage.documentRules(
                "Fallbacks must be approved by the Commercial Manager before use.", Map.of());

        assertThat(rules).singleElement().satisfies(scoped -> {
            assertThat(scoped.rule().approver()).isEqualTo("Commercial Manager");
            assertThat(scoped.rule().stances()).containsExactly(PositionRole.FALLBACK);
        });
    }

    @Test
    void aClauseThatOnlyMentionsTheVerbDoesNotBecomeTheApprover() {
        var rules = LabelledFieldStage.documentRules(
                "The customer may accept a fallback that the Commercial Manager approves.", Map.of());

        assertThat(rules).isEmpty();
    }

    @Test
    void aConditionDropsTheBoundaryWordSoThePanelDoesNotSayIfIf() {
        var rules = LabelledFieldStage.documentRules(
                "The Commercial Legal Director may accept that forum if the rest of the agreement "
                        + "is mutual and the forum clause does not add a jury waiver.",
                Map.of());

        assertThat(rules).singleElement().satisfies(scoped ->
                assertThat(scoped.rule().condition())
                        .isEqualTo("the rest of the agreement is mutual and the forum clause "
                                + "does not add a jury waiver"));
    }

    @Test
    void aBlankPreferredLabelIsNotReportedAsAnUnmappedAuthorityWord() {
        var parsed = LabelledFieldStage.parse("Payment terms", String.join("\n",
                "Preferred:",
                "Fallback: Net thirty days."), Map.of());

        assertThat(parsed.fallbacks()).containsExactly("Net thirty days.");
        assertThat(parsed.unmapped()).isEmpty();
    }

    @Test
    void ownershipRowsJointApproversAndThresholdsBecomeTypedRules() {
        var ownership = LabelledFieldStage.documentRules(
                "Approval matrix: Legal owns the commercial positions. "
                        + "Privacy Office owns DPA positions.", Map.of());
        assertThat(ownership).hasSize(2);
        assertThat(ownership).anySatisfy(scoped -> {
            assertThat(scoped.rule().approver()).isEqualTo("Legal");
            assertThat(scoped.scope()).isEqualTo("commercial");
        });
        assertThat(ownership).anySatisfy(scoped ->
                assertThat(scoped.rule().approver()).isEqualTo("Privacy Office"));

        var joint = LabelledFieldStage.documentRules(
                "Approval matrix: Legal and Finance approve fallbacks.", Map.of());
        assertThat(joint).singleElement().satisfies(scoped -> {
            assertThat(scoped.rule().approver()).isEqualTo("Legal and Finance");
            assertThat(scoped.rule().stances()).containsExactly(PositionRole.FALLBACK);
        });

        var threshold = LabelledFieldStage.documentRules(
                "Approval matrix: General Counsel approves fallbacks above 10% of annual fees.",
                Map.of());
        assertThat(threshold).singleElement().satisfies(scoped -> {
            assertThat(scoped.rule().stances()).containsExactly(PositionRole.FALLBACK);
            assertThat(scoped.rule().condition()).isEqualTo("above 10% of annual fees");
        });
    }

    @Test
    void anEntrysOwnEscalationLabelOrApprovalAuthorityColumnIsNotADocumentAuthority() {
        assertThat(LabelledFieldStage.isAuthoritySection("Limitation of liability",
                "Preferred: Cap at the fees paid.\n"
                        + "Escalation: Any deviation requires General Counsel approval.", Map.of()))
                .isFalse();
        assertThat(LabelledFieldStage.isAuthoritySection("Limitation of liability",
                "Preferred: Cap at the fees paid.\nApproval authority: General Counsel.", Map.of()))
                .isFalse();

        var columns = new java.util.LinkedHashMap<String, String>();
        columns.put("Preferred", "Cap at the fees paid.");
        columns.put("Approval authority", "General Counsel");
        assertThat(LabelledFieldStage.isAuthoritySection("Limitation of liability", "", columns))
                .isFalse();
    }

    @Test
    void aStanceRowTriggerDropsItsLeadingIfAndAPassiveThresholdBecomesTheCondition() {
        var columns = new java.util.LinkedHashMap<String, String>();
        columns.put("Position", "Fallback");
        columns.put("Trigger", "If the counterparty insists on a higher cap");
        columns.put("Guidance", "Accept a cap at two times annual fees.");
        columns.put("Approval", "General Counsel");

        var row = LabelledFieldStage.parse("Liability", "", columns);
        assertThat(row.entryRules()).singleElement().satisfies(rule ->
                assertThat(rule.condition()).isEqualTo("the counterparty insists on a higher cap"));

        var passive = LabelledFieldStage.documentRules(
                "Fallbacks above 10% of annual fees must be approved by the CFO.", Map.of());
        assertThat(passive).singleElement().satisfies(scoped -> {
            assertThat(scoped.rule().approver()).isEqualTo("CFO");
            assertThat(scoped.rule().condition()).isEqualTo("above 10% of annual fees");
        });
    }

    @Test
    void aConditionalProseApprovalAndAnApprovalRequiredSentenceBecomeRules() {
        var conditional = LabelledFieldStage.documentRules(
                "If the fees exceed five million dollars, Legal must approve.", Map.of());
        assertThat(conditional).singleElement().satisfies(scoped -> {
            assertThat(scoped.rule().approver()).isEqualTo("Legal");
            assertThat(scoped.rule().condition()).isEqualTo("the fees exceed five million dollars");
        });

        var required = LabelledFieldStage.documentRules(
                "CFO approval is required for fallbacks above 10% of annual fees.", Map.of());
        assertThat(required).singleElement().satisfies(scoped -> {
            assertThat(scoped.rule().approver()).isEqualTo("CFO");
            assertThat(scoped.rule().stances()).containsExactly(PositionRole.FALLBACK);
            assertThat(scoped.rule().condition()).isEqualTo("above 10% of annual fees");
        });
    }

    @Test
    void aDocumentSectionIsRecognisedByItsOwnWordsOrItsTableColumns() {
        assertThat(LabelledFieldStage.isAuthoritySection(
                "How to use", "Approval matrix: the Commercial Manager approves fallbacks.", Map.of()))
                .isTrue();
        assertThat(LabelledFieldStage.isAuthoritySection(
                "Approval path", "The lawyer handling the NDA may apply the fallbacks above.", Map.of()))
                .isTrue();
        assertThat(LabelledFieldStage.isAuthoritySection(
                "Approval path", "",
                Map.of("Issue", "Routine term", "Route to", "Reviewing counsel",
                        "Decision needed", "Within stated fallback")))
                .isTrue();
        assertThat(LabelledFieldStage.isAuthoritySection(
                "Limitation of liability", "Preferred: Cap at the fees paid.", Map.of()))
                .isFalse();
        assertThat(LabelledFieldStage.isAuthoritySection(
                "Termination", "Either party may terminate for convenience.", Map.of()))
                .isFalse();
    }

    @Test
    void theSameStanceTextIsNotDuplicatedByItsColonLabelAndItsValue() {
        var columns = new java.util.LinkedHashMap<String, String>();
        columns.put("Preferred", "Cap at 100% of fees.");
        columns.put("Fallback", "Cap at 150% of fees.");

        var parsed = LabelledFieldStage.parse("Liability", "Preferred: Cap at 100% of fees.", columns);

        assertThat(parsed.preferred()).isEqualTo("Cap at 100% of fees.");
        assertThat(parsed.fallbacks()).containsExactly("Cap at 150% of fees.");
    }

    @Test
    void aRuleWithoutAnApproverIsNeverInvented() {
        var parsed = LabelledFieldStage.parse("Liability", String.join("\n",
                "Preferred: Cap at the fees paid.",
                "Fallback: Cap at 150% of fees.",
                "Walk-away: Unlimited liability."), Map.of());

        assertThat(parsed.entryRules()).isEmpty();
    }
}
