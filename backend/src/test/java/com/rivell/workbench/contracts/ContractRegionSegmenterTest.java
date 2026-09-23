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
        return new DocumentNode(UUID.randomUUID(), VERSION, BODY_ID,
                DocumentNodeType.HEADING, ordinal, text, 1, label,
                SourceSpan.ofParagraph("word/document.xml", ordinal, ordinal), "Heading1");
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
