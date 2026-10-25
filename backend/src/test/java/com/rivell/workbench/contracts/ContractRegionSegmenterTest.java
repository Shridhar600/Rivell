package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.platform.parsing.DocxParserProperties;
import com.rivell.workbench.platform.parsing.DocxStructuralParser;

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
    void tableCellsKeepDocumentOrder() {
        var table = new DocumentNode(UUID.randomUUID(), VERSION, BODY_ID,
                DocumentNodeType.TABLE, 2, null, null, null,
                SourceSpan.ofTable("word/document.xml", 2, 0), null);
        var rowA = new DocumentNode(UUID.randomUUID(), VERSION, table.id(),
                DocumentNodeType.TABLE_ROW, 0, null, null, null,
                SourceSpan.ofRow("word/document.xml", 2, 0, 0), null);
        var rowB = new DocumentNode(UUID.randomUUID(), VERSION, table.id(),
                DocumentNodeType.TABLE_ROW, 1, null, null, null,
                SourceSpan.ofRow("word/document.xml", 2, 0, 1), null);
        var cellA1 = tableCell(rowA, "A1", 3, 0);
        var cellA2 = tableCell(rowA, "A2", 12, 1);
        var cellB1 = tableCell(rowB, "B1", 7, 0);
        var cellB2 = tableCell(rowB, "B2", 1, 1);
        var nodes = List.of(BODY_NODE, heading("Schedule A — Services", "8", 1),
                table, rowA, cellA1, cellA2, rowB, cellB1, cellB2);

        var byHash = new java.util.HashMap<UUID, DocumentNode>();
        for (var node : nodes) {
            byHash.put(node.id(), node);
        }
        var hashOrder = byHash.values().stream()
                .filter(node -> node.nodeType() == DocumentNodeType.TABLE_CELL)
                .map(DocumentNode::text)
                .toList();
        assertThat(hashOrder).containsExactlyInAnyOrder("A1", "A2", "B1", "B2");
        assertThat(hashOrder)
                .as("precondition: the old HashMap walk scrambles the cells, otherwise this "
                        + "test cannot catch the defect")
                .isNotEqualTo(List.of("A1", "A2", "B1", "B2"));

        var regions = new ContractRegionSegmenter().segment(nodes);

        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).contentText()).contains("A1\nA2\nB1\nB2");
    }

    private static DocumentNode tableCell(DocumentNode row, String text, long highBits, int ordinal) {
        return new DocumentNode(new UUID(highBits, 0), VERSION, row.id(),
                DocumentNodeType.TABLE_CELL, ordinal, text, null, null,
                SourceSpan.ofCell("word/document.xml", 2, 0, row.ordinal(), ordinal), null);
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
    void aClauseWithAFootnoteReferenceOrATextBoxCannotBeReplaced() {
        var term = heading("2. Term", "2", 0);
        var footnoted = new DocumentNode(UUID.randomUUID(), VERSION, BODY_ID,
                DocumentNodeType.PARAGRAPH, 1, "This Agreement lasts three years.", null, null,
                SourceSpan.ofParagraph("word/document.xml", 1, 1), null, null, true);
        var keyTerms = heading("3. Key Terms", "3", 2);
        var box = new DocumentNode(UUID.randomUUID(), VERSION, BODY_ID,
                DocumentNodeType.TEXT_BOX, 3, "Liability cap: two months of fees.", null, null,
                new SourceSpan("word/document.xml", 2, null, null, null, null, null, null), null);
        var fees = heading("4. Fees", "4", 4);
        var nodes = List.of(BODY_NODE, term, footnoted, keyTerms, box, fees,
                paragraph("Fees are due monthly.", 5));

        assertThat(new ContractRegionSegmenter().untrackableHeadings(nodes))
                .containsExactlyInAnyOrder(term.id(), keyTerms.id());
        assertThat(new ContractRegionSegmenter().segment(nodes).get(1).contentText())
                .as("the box text is part of its clause for review")
                .contains("Liability cap: two months of fees.");
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
    void theExecutionBlockAfterTheLastScheduleIsItsOwnRegion() {
        var schedule = heading("XXVI SCHEDULE 1 — GOODS, PRICE AND DELIVERY", null, 1);
        var witness = paragraph("IN WITNESS WHEREOF the parties have executed this Agreement.", 3);
        var nodes = List.of(
                BODY_NODE,
                heading("XXV WORK HEALTH AND SAFETY", null, 0),
                schedule,
                paragraph("The Price escalates each year by the greater of 5% or CPI.", 2),
                witness,
                paragraph("Signed for Fenwick Engineering Pty Ltd", 4));

        var regions = new ContractRegionSegmenter().segment(nodes);

        assertThat(regions).extracting(ContractRegionSegmenter.Region::kind).containsExactly(
                ContractRegionSegmenter.SemanticRegionKind.CLAUSE,
                ContractRegionSegmenter.SemanticRegionKind.SCHEDULE,
                ContractRegionSegmenter.SemanticRegionKind.SIGNATURE_BLOCK);
        assertThat(regions.get(1).contentText())
                .as("the schedule keeps its content and loses the signatures")
                .contains("greater of 5% or CPI").doesNotContain("IN WITNESS");
        assertThat(regions.get(2).headingNodeId()).isEqualTo(witness.id());
    }

    /**
     * The SOW file holds its milestone and rate tables after the signature block. They must stay
     * in the text the review reads, in row and cell order; folded into the signature block they
     * were dropped, and a whole-contract read said the milestone schedule was missing.
     */
    @Test
    void tablesAfterTheExecutionBlockStayReviewableContent() throws Exception {
        var parser = new DocxStructuralParser(new DocxParserProperties(200L * 1024 * 1024));
        ParseResult parsed;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("fixtures/h-sow-under-msa.docx")) {
            parsed = parser.parse(in, VERSION);
        }
        var regions = new ContractRegionSegmenter().segment(parsed.nodes());

        var reviewable = regions.stream()
                .filter(region -> region.kind() != ContractRegionSegmenter.SemanticRegionKind.SIGNATURE_BLOCK)
                .map(ContractRegionSegmenter.Region::contentText)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(reviewable).containsSubsequence(
                "M1", "Brand strategy and positioning", "30 April 2025", "CAD 60,000",
                "M2", "Visual identity system", "31 May 2025", "CAD 75,000",
                "M3", "Campaign creative and media plan", "30 June 2025", "CAD 110,000",
                "Creative director", "CAD 2,400");

        var signature = regions.stream()
                .filter(region -> region.kind()
                        == ContractRegionSegmenter.SemanticRegionKind.SIGNATURE_BLOCK)
                .findFirst().orElseThrow();
        assertThat(signature.contentText()).doesNotContain("CAD 60,000");
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
