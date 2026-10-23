package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The title a Word document gives itself. A masthead is not a name, a leading title-style
 * paragraph is, and only the node the title came from may use it as a section name.
 */
class KnowledgeDocumentTitleTest {

    private static final String MASTHEAD = "ACME LEGAL | INTERNAL GUIDANCE";

    private final UUID artifactId = UUID.randomUUID();
    private final KnowledgeSourceNode root = root(artifactId);
    private int ordinal;

    @Test
    void anAllCapsTitleStyleMastheadIsSkippedForTheNextParagraph() {
        var nodes = document(
                node(KnowledgeNodeType.TITLE, "ACME LEGAL"),
                node(KnowledgeNodeType.PARAGRAPH, "Mutual NDA Negotiation Guide"),
                node(KnowledgeNodeType.HEADING, "Purpose"));

        var title = KnowledgeDocumentTitle.of(nodes);

        assertThat(title).isNotNull();
        assertThat(title.text()).isEqualTo("Mutual NDA Negotiation Guide");
    }

    @Test
    void anAllCapsParagraphMastheadIsSkippedForTheTitleBelowIt() {
        var nodes = document(
                node(KnowledgeNodeType.PARAGRAPH, "ACME LEGAL"),
                node(KnowledgeNodeType.PARAGRAPH, "Mutual NDA Negotiation Guide"));

        var title = KnowledgeDocumentTitle.of(nodes);

        assertThat(title).isNotNull();
        assertThat(title.text()).isEqualTo("Mutual NDA Negotiation Guide");
    }

    @Test
    void aLeadingParagraphNamesTheDocumentWhenThereIsNoTitleNode() {
        var nodes = document(
                node(KnowledgeNodeType.PARAGRAPH, "Mutual NDA Negotiation Guide"),
                node(KnowledgeNodeType.HEADING, "1. Purpose"));

        var title = KnowledgeDocumentTitle.of(nodes);

        assertThat(title).isNotNull();
        assertThat(title.text()).isEqualTo("Mutual NDA Negotiation Guide");
    }

    @Test
    void aHeadingAfterAPipeMastheadNamesTheDocument() {
        var nodes = document(
                node(KnowledgeNodeType.TITLE, MASTHEAD),
                node(KnowledgeNodeType.HEADING, "Mutual NDA Negotiation Guide"),
                node(KnowledgeNodeType.PARAGRAPH, "This guide sets our positions."));

        var title = KnowledgeDocumentTitle.of(nodes);

        assertThat(title).isNotNull();
        assertThat(title.text()).isEqualTo("Mutual NDA Negotiation Guide");
    }

    @Test
    void aThirteenWordTitleEndingInAPeriodIsAcceptedAfterAMasthead() {
        var titleText = "Mutual NDA Negotiation Guide for Channel and Integration Partners in Europe and Asia.";
        var nodes = document(
                node(KnowledgeNodeType.TITLE, MASTHEAD),
                node(KnowledgeNodeType.PARAGRAPH, titleText));

        var title = KnowledgeDocumentTitle.of(nodes);

        assertThat(title).isNotNull();
        assertThat(title.text()).isEqualTo(titleText);
    }

    @Test
    void aBlankLeadingParagraphDoesNotMakeTheFirstHeadingTheTitle() {
        var nodes = document(
                node(KnowledgeNodeType.PARAGRAPH, "   "),
                node(KnowledgeNodeType.HEADING, "1. Purpose"));

        assertThat(KnowledgeDocumentTitle.of(nodes)).isNull();
    }

    @Test
    void aLeadingProseSentenceWithoutAMastheadIsNotADocumentTitle() {
        var nodes = document(
                node(KnowledgeNodeType.PARAGRAPH,
                        "This guide sets out our standard negotiating positions for mutual NDAs."),
                node(KnowledgeNodeType.HEADING, "1. Purpose"));

        assertThat(KnowledgeDocumentTitle.of(nodes)).isNull();
    }

    @Test
    void onlyTheNodeTheTitleCameFromIsNamedByIt() {
        var firstTitle = node(KnowledgeNodeType.TITLE, "Mutual NDA Negotiation Guide");
        var paragraph = node(KnowledgeNodeType.PARAGRAPH, "This guide sets our positions.");
        var secondTitle = node(KnowledgeNodeType.TITLE, "Schedule 1");
        var table = node(KnowledgeNodeType.TABLE, null);
        var nodes = new ArrayList<>(List.of(root, firstTitle, paragraph, secondTitle, table));
        nodes.addAll(row(table, 0, "Term", "12 months"));
        nodes.addAll(row(table, 1, "Notice", "30 days"));

        var title = KnowledgeDocumentTitle.of(nodes);

        assertThat(title).isNotNull();
        assertThat(title.nodeId()).isEqualTo(firstTitle.id());
        var compiled = KnowledgeCompiler.compile(artifact(), nodes);
        assertThat(compiled.candidates()).extracting(KnowledgeCandidate::rawLabel)
                .contains("Mutual NDA Negotiation Guide", "Schedule 1");
    }

    private List<KnowledgeSourceNode> document(KnowledgeSourceNode... children) {
        var nodes = new ArrayList<KnowledgeSourceNode>();
        nodes.add(root);
        nodes.addAll(List.of(children));
        return nodes;
    }

    private KnowledgeSourceNode node(KnowledgeNodeType type, String text) {
        return node(artifactId, root.id(), type, text, null, null);
    }

    private List<KnowledgeSourceNode> row(
            KnowledgeSourceNode table, int rowIndex, String... cells) {
        var row = node(artifactId, table.id(), KnowledgeNodeType.TABLE_ROW, null, rowIndex, null);
        var rowNodes = new ArrayList<KnowledgeSourceNode>();
        rowNodes.add(row);
        for (int cell = 0; cell < cells.length; cell++) {
            rowNodes.add(node(artifactId, row.id(), KnowledgeNodeType.TABLE_CELL, cells[cell],
                    rowIndex, cell));
        }
        return rowNodes;
    }

    private KnowledgeSourceNode node(
            UUID artifactId, UUID parentId, KnowledgeNodeType type, String text,
            Integer rowIndex, Integer cellIndex) {
        return new KnowledgeSourceNode(UUID.randomUUID(), artifactId, parentId, type, ordinal++,
                text, null, null, "word/document.xml", ordinal, null, null, rowIndex,
                cellIndex, null, null, null, cellIndex, null, null);
    }

    private static KnowledgeSourceNode root(UUID artifactId) {
        return new KnowledgeSourceNode(UUID.randomUUID(), artifactId, null,
                KnowledgeNodeType.BODY, 0, null, null, null, "word/document.xml", 0,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static KnowledgeArtifact artifact() {
        var manifest = new ArtifactManifest(Set.of("NDA"), null, null, null, null, null, null, null, null);
        return new KnowledgeArtifact(UUID.randomUUID(), UUID.randomUUID(), ArtifactType.PLAYBOOK,
                "guide.docx", "application/octet-stream", 1, "sha", "key", manifest,
                IngestStatus.EXTRACTED, null, List.of(), null, Instant.now(), null);
    }
}
