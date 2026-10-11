package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ContractRegionSegmenterTest {

    private static DocumentNode body() {
        return new DocumentNode(UUID.randomUUID(), VERSION, null,
                DocumentNodeType.BODY, 0, null, null, null,
                SourceSpan.ofBody("word/document.xml"), null);
    }

    private static DocumentNode paragraph(String text, int ordinal) {
        return new DocumentNode(UUID.randomUUID(), VERSION, BODY_ID,
                DocumentNodeType.PARAGRAPH, ordinal, text, null, null,
                SourceSpan.ofParagraph("word/document.xml", ordinal, ordinal), null);
    }

    private static DocumentNode heading(String text, String label, int ordinal) {
        return heading(text, label, ordinal, 1);
    }

    private static DocumentNode heading(String text, String label, int ordinal, int level) {
        return new DocumentNode(UUID.randomUUID(), VERSION, BODY_ID,
                DocumentNodeType.HEADING, ordinal, text, level, label,
                SourceSpan.ofParagraph("word/document.xml", ordinal, ordinal), "Heading" + level);
    }

    private static DocumentNode title(String text, int ordinal) {
        return new DocumentNode(UUID.randomUUID(), VERSION, BODY_ID,
                DocumentNodeType.TITLE, ordinal, text, null, null,
                SourceSpan.ofParagraph("word/document.xml", ordinal, ordinal), "Title");
    }

    private static final UUID VERSION = UUID.randomUUID();
    private static UUID BODY_ID;
    private static DocumentNode BODY_NODE;

    static {
        BODY_NODE = new DocumentNode(UUID.randomUUID(), VERSION, null,
                DocumentNodeType.BODY, 0, null, null, null,
                SourceSpan.ofBody("word/document.xml"), null);
        BODY_ID = BODY_NODE.id();
    }

    @Test
    void groupsSiblingsIntoHeadingRegionsAndPreamble() {
        var nodes = List.of(
                BODY_NODE,
                title("Master Services Agreement", 0),
                paragraph("This Agreement is entered into by and between "
                        + "Northwind LLC (\"Customer\") and Pallas GmbH (\"Supplier\") "
                        + "as of January 1, 2026.", 1),
                heading("Services", "2", 2),
                paragraph("2.1 Supplier shall provide the Services in accordance with Schedule A.", 3),
                heading("Limitation of Liability", "6", 4),
                paragraph("6.1 Supplier liability shall not exceed three times the Fees.", 5),
                heading("Survival", "7", 6),
                paragraph("7.1 Sections 5 and 6 survive termination.", 7));

        var regions = new ContractRegionSegmenter().segment(nodes);

        assertThat(regions).hasSize(4);
        var preamble = regions.get(0);
        assertThat(preamble.headingNodeId()).isEqualTo(BODY_ID);
        assertThat(preamble.isPreamble()).isTrue();
        assertThat(preamble.contentText()).contains("Northwind LLC");
        var services = regions.get(1);
        assertThat(services.headingNodeId()).isNotEqualTo(BODY_ID);
        assertThat(services.headingText()).isEqualTo("Services");
        assertThat(services.contentText()).startsWith("Services\n2.1 Supplier");
        assertThat(regions.get(2).headingText()).isEqualTo("Limitation of Liability");
        assertThat(regions.get(3).contentText()).contains("Sections 5 and 6 survive");
    }

    @Test
    void aBodylessHeadingOverSubClausesIsNotAClause() {
        var nodes = List.of(
                BODY_NODE,
                heading("Confidentiality", "5", 0, 1),
                heading("Confidential Information", "5.1", 1, 2),
                paragraph("Each party shall keep the other's information confidential.", 2),
                heading("Return of Information", "5.2", 3, 2),
                paragraph("On request each party shall return or destroy the information.", 4),
                heading("Term", "6", 5, 1),
                heading("Notices", "7", 6, 1),
                paragraph("Notices are given in writing.", 7));

        var regions = new ContractRegionSegmenter().segment(nodes);

        assertThat(regions).extracting(ContractRegionSegmenter.Region::headingText)
                .containsExactly("Confidential Information", "Return of Information", "Term",
                        "Notices");
    }

    @Test
    void tablesBelongToTheirPrecedingRegion() {
        var table = new DocumentNode(UUID.randomUUID(), VERSION, BODY_ID,
                DocumentNodeType.TABLE, 8, null, null, null,
                SourceSpan.ofTable("word/document.xml", 8, 0), null);
        var nodes = List.of(
                BODY_NODE,
                heading("Schedule A", "8", 7),
                table);

        var regions = new ContractRegionSegmenter().segment(nodes);

        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).headingText()).isEqualTo("Schedule A");
        assertThat(regions.get(0).textNodeIds()).contains(table.id());
    }

    @Test
    void aTrackedChangeCanReplaceOnlyParagraphClauses() {
        var fees = heading("4. Fees", "4", 1);
        var table = new DocumentNode(UUID.randomUUID(), VERSION, BODY_ID,
                DocumentNodeType.TABLE, 3, null, null, null,
                SourceSpan.ofTable("word/document.xml", 3, 0), null);
        var term = heading("5. Term", "5", 4);
        var schedule = heading("Schedule 1 Order Form", "S1", 6);
        var nodes = List.of(
                BODY_NODE,
                fees,
                paragraph("The fees are set out below.", 2),
                table,
                term,
                paragraph("This Agreement lasts three years.", 5),
                schedule,
                paragraph("Subscription: Enterprise.", 7));

        var untrackable = new ContractRegionSegmenter().untrackableHeadings(nodes);

        assertThat(untrackable).containsExactlyInAnyOrder(fees.id(), schedule.id());
    }

    @Test
    void signatureHeadingsAndSchedulesAreNotClauses() {
        var nodes = List.of(
                BODY_NODE,
                paragraph("IN WITNESS WHEREOF, the Parties have executed this Agreement.", 0),
                heading("2. Services", "2", 1),
                paragraph("2.1 Supplier shall provide the Services.", 2),
                heading("Schedule A — Services", "8", 3),
                heading("Northwind Customer Group LLC", "8", 4),
                paragraph("By: Dana Whitfield, VP Procurement", 5));

        var regions = new ContractRegionSegmenter().segment(nodes);

        var byHeading = regions.stream()
                .collect(java.util.stream.Collectors.toMap(
                        region -> region.headingText() == null ? "preamble" : region.headingText(),
                        region -> region,
                        (a, b) -> a));
        assertThat(byHeading.get("2. Services").kind())
                .isEqualTo(ContractRegionSegmenter.SemanticRegionKind.CLAUSE);
        assertThat(byHeading.get("Schedule A — Services").kind())
                .isEqualTo(ContractRegionSegmenter.SemanticRegionKind.SCHEDULE);
        assertThat(byHeading.get("Northwind Customer Group LLC").kind())
                .isEqualTo(ContractRegionSegmenter.SemanticRegionKind.SIGNATURE_BLOCK);
        assertThat(byHeading.get("preamble").kind()).isNull();
    }

    @Test
    void bodyNodeIdsAreKeptOutOfText() {
        var nodes = List.of(
                BODY_NODE,
                heading("Definitions", "1", 0),
                paragraph("1.1 \"Fees\" means the fees in Schedule A.", 1));

        var regions = new ContractRegionSegmenter().segment(nodes);

        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).textNodeIds()).doesNotContain(BODY_ID);
    }
}
