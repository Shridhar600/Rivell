package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Word table shaping at the record level: a single-column header names a list, not an entry;
 * a headerless grid must not publish placeholder column names; and an uncertain callout lead
 * line must stay in the wording.
 */
class KnowledgeStructureTest {

    private final UUID artifactId = UUID.randomUUID();
    private final KnowledgeSourceNode root = root();
    private int ordinal;

    @Test
    void aSingleColumnHeaderRowIsNotAnEntry() {
        var table = node(KnowledgeNodeType.TABLE, null);
        var nodes = document(table,
                row(table, 0, "Term"),
                row(table, 1, "Confidential Information"),
                row(table, 2, "Affiliate"));

        var compiled = compile(nodes);

        assertThat(compiled.candidates()).extracting(KnowledgeCandidate::rawLabel)
                .containsExactly("Confidential Information", "Affiliate");
    }

    @Test
    void aOneColumnListWithoutAHeaderKeepsItsFirstItem() {
        var table = node(KnowledgeNodeType.TABLE, null);
        var nodes = document(table,
                row(table, 0, "Affiliate"),
                row(table, 1, "Confidential Information"),
                row(table, 2, "Representatives"));

        var compiled = compile(nodes);

        assertThat(compiled.candidates()).extracting(KnowledgeCandidate::rawLabel)
                .containsExactly("Affiliate", "Confidential Information", "Representatives");
    }

    @Test
    void aHeaderlessMultiColumnTableGetsNeutralNamesNotPlaceholderColumns() {
        var table = node(KnowledgeNodeType.TABLE, null);
        var nodes = document(table,
                row(table, 0,
                        "The Supplier shall defend the Customer against all third-party claims.",
                        "The Supplier's liability is capped at the fees paid."),
                row(table, 1,
                        "The Customer shall give prompt notice of any claim.",
                        "No cap applies to the indemnity."));

        var compiled = compile(nodes);

        assertThat(compiled.candidates()).hasSize(2);
        assertThat(compiled.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.payload().columns().keySet())
                    .noneMatch(name -> name.matches("Column \\d+"));
        });
        assertThat(compiled.candidates().get(0).contentText())
                .contains("The Supplier shall defend")
                .contains("The Supplier's liability is capped");
    }

    @Test
    void aBlankHeaderColumnDoesNotBecomeTheClauseWording() {
        var table = node(KnowledgeNodeType.TABLE, null);
        var nodes = document(table,
                row(table, 0, "", "Clause text", "Variant"),
                row(table, 1, "G-01", "The Supplier shall defend the Customer.", "Preferred"));

        var compiled = compile(ArtifactType.CLAUSE_LIBRARY, nodes);

        assertThat(compiled.candidates()).hasSize(1);
        assertThat(compiled.candidates().getFirst().effectiveContentText())
                .isEqualTo("The Supplier shall defend the Customer.");
    }

    @Test
    void aHeaderlessClauseLibraryGridKeepsEveryCellInItsWording() {
        var table = node(KnowledgeNodeType.TABLE, null);
        var nodes = document(table,
                row(table, 0, "G-01", "The Supplier shall defend the Customer.", "Preferred"),
                row(table, 1, "G-02", "The Supplier shall notify the Customer.", "Fallback 1"));

        var compiled = compile(ArtifactType.CLAUSE_LIBRARY, nodes);

        assertThat(compiled.candidates()).hasSize(2);
        assertThat(compiled.candidates()).allSatisfy(candidate ->
                assertThat(candidate.effectiveContentText())
                        .contains("The Supplier shall")
                        .contains("G-0")
                        .containsAnyOf("Preferred", "Fallback 1"));
    }

    @Test
    void aNumericMultiLineCalloutKeepsAOneLineTitle() {
        var table = node(KnowledgeNodeType.TABLE, null);
        var nodes = document(table, row(table, 0, "1.\n2."));

        var compiled = compile(nodes);

        assertThat(compiled.candidates()).hasSize(1);
        assertThat(compiled.candidates().getFirst().rawLabel())
                .isEqualTo("1.")
                .doesNotContain("\n");
    }

    @Test
    void anUncertainCalloutLeadLineStaysInTheWording() {
        var table = node(KnowledgeNodeType.TABLE, null);
        var nodes = document(table,
                row(table, 0,
                        "No live or identifiable\nfleet data may enter the evaluation under an NDA."));

        var compiled = compile(nodes);

        assertThat(compiled.candidates()).hasSize(1);
        assertThat(compiled.candidates().getFirst().contentText())
                .startsWith("No live or identifiable")
                .contains("fleet data may enter the evaluation");
    }

    @Test
    void aSpreadsheetClauseRowIsTitledByItsTitleColumnAndVariant() {
        var compiled = spreadsheet(ArtifactType.CLAUSE_LIBRARY,
                new String[] {"Clause ID", "Title", "Variant", "Clause text"},
                new String[] {"G-GOV-01", "Governing law and jurisdiction", "Preferred",
                        "This Agreement is governed by the laws of Massachusetts."});

        assertThat(compiled.candidates()).hasSize(1);
        assertThat(compiled.candidates().getFirst().rawLabel())
                .isEqualTo("Governing law and jurisdiction · Preferred");
    }

    @Test
    void aSpreadsheetClauseRowWithoutATitleColumnKeepsItsFirstValueAsTheTitle() {
        var compiled = spreadsheet(ArtifactType.CLAUSE_LIBRARY,
                new String[] {"Clause ID", "Variant", "Clause text"},
                new String[] {"G-01", "Preferred",
                        "The Supplier shall defend the Customer against third-party claims."});

        assertThat(compiled.candidates()).hasSize(1);
        assertThat(compiled.candidates().getFirst().rawLabel()).isEqualTo("G-01");
    }

    @Test
    void aWordClauseTableIsNotRetitledByATitleColumn() {
        var table = node(KnowledgeNodeType.TABLE, null);
        var nodes = document(table,
                row(table, 0, "Clause ID", "Title", "Variant", "Clause text"),
                row(table, 1, "G-01", "Governing law and jurisdiction", "Preferred",
                        "This Agreement is governed by the laws of Massachusetts."));

        var compiled = compile(ArtifactType.CLAUSE_LIBRARY, nodes);

        assertThat(compiled.candidates()).hasSize(1);
        assertThat(compiled.candidates().getFirst().rawLabel()).isEqualTo("G-01");
    }

    @Test
    void aTwoColumnWordTableWithATitleHeaderStaysAGrid() {
        var table = node(KnowledgeNodeType.TABLE, null);
        var nodes = document(table,
                row(table, 0, "Title", "Clause text"),
                row(table, 1, "Governing law", "This Agreement is governed by the laws of Massachusetts."));

        var compiled = compile(ArtifactType.CLAUSE_LIBRARY, nodes);

        assertThat(compiled.candidates()).hasSize(1);
        assertThat(compiled.candidates().getFirst().rawLabel()).isEqualTo("Governing law");
        assertThat(compiled.candidates().getFirst().payload().columns())
                .containsEntry("Clause text", "This Agreement is governed by the laws of Massachusetts.");
    }

    @Test
    void aLongSpreadsheetClauseTitleKeepsItsVariantWithinTheTitleBound() {
        var longTitle = "Governing law and jurisdiction " + "a".repeat(91);
        var compiled = spreadsheet(ArtifactType.CLAUSE_LIBRARY,
                new String[] {"Clause ID", "Title", "Variant", "Clause text"},
                new String[] {"G-GOV-01", longTitle, "Preferred",
                        "This Agreement is governed by the laws of Massachusetts."},
                new String[] {"G-GOV-02", longTitle, "Fallback 1",
                        "This Agreement is governed by the laws of Massachusetts."});

        assertThat(compiled.candidates()).hasSize(2);
        var titles = compiled.candidates().stream().map(KnowledgeCandidate::rawLabel).toList();
        assertThat(titles).allSatisfy(title -> {
            assertThat(title.length()).isLessThanOrEqualTo(KnowledgeTitles.MAX_LENGTH);
            assertThat(title).doesNotContain("\n").contains("…");
        });
        assertThat(titles.get(0)).endsWith(" · Preferred");
        assertThat(titles.get(1)).endsWith(" · Fallback 1");
    }

    private KnowledgeCompiler.CompiledKnowledge compile(List<KnowledgeSourceNode> nodes) {
        return compile(ArtifactType.PLAYBOOK, nodes);
    }

    private KnowledgeCompiler.CompiledKnowledge spreadsheet(ArtifactType type, String[]... rows) {
        var workbook = new KnowledgeSourceNode(UUID.randomUUID(), artifactId, null,
                KnowledgeNodeType.WORKBOOK, ordinal++, null, null, null,
                "xl/workbook.xml", 0, null, null, null, null, null, null, null, null, null, null);
        var sheetId = UUID.randomUUID();
        var nodes = new ArrayList<KnowledgeSourceNode>();
        nodes.add(workbook);
        nodes.add(new KnowledgeSourceNode(sheetId, artifactId, workbook.id(),
                KnowledgeNodeType.SHEET, ordinal++, null, null, null,
                "xl/worksheets/sheet1.xml", 0, null, null, null, null, null, null,
                "Clauses", null, null, null));
        for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
            var rowId = UUID.randomUUID();
            nodes.add(new KnowledgeSourceNode(rowId, artifactId, sheetId,
                    KnowledgeNodeType.SPREADSHEET_ROW, ordinal++, null, null, null,
                    "xl/worksheets/sheet1.xml", rowIndex, null, null, rowIndex, null,
                    null, null, "Clauses", null, null, null));
            for (int column = 0; column < rows[rowIndex].length; column++) {
                var text = rows[rowIndex][column];
                nodes.add(new KnowledgeSourceNode(UUID.randomUUID(), artifactId, rowId,
                        KnowledgeNodeType.SPREADSHEET_CELL, ordinal++,
                        text == null || text.isBlank() ? null : text,
                        null, null, "xl/worksheets/sheet1.xml", rowIndex, null, null,
                        rowIndex, column, null, null, "Clauses", column, null, null));
            }
        }
        return compile(type, nodes);
    }

    private KnowledgeCompiler.CompiledKnowledge compile(
            ArtifactType type, List<KnowledgeSourceNode> nodes) {
        var manifest = new ArtifactManifest(Set.of("NDA"), null, null, null, null, null, null, null, null);
        var artifact = new KnowledgeArtifact(UUID.randomUUID(), UUID.randomUUID(),
                type, "guide.docx", "application/octet-stream", 1, "sha", "key",
                manifest, IngestStatus.EXTRACTED, null, List.of(), null, Instant.now(), null);
        return KnowledgeCompiler.compile(artifact, nodes);
    }

    private List<KnowledgeSourceNode> document(
            KnowledgeSourceNode table, List<KnowledgeSourceNode>... rows) {
        var nodes = new ArrayList<KnowledgeSourceNode>();
        nodes.add(root);
        nodes.add(table);
        for (var row : rows) {
            nodes.addAll(row);
        }
        return nodes;
    }

    private List<KnowledgeSourceNode> row(KnowledgeSourceNode table, int rowIndex, String... cells) {
        var row = node(artifactId, table.id(), KnowledgeNodeType.TABLE_ROW, null, rowIndex, null);
        var nodes = new ArrayList<KnowledgeSourceNode>();
        nodes.add(row);
        for (int cell = 0; cell < cells.length; cell++) {
            nodes.add(node(artifactId, row.id(), KnowledgeNodeType.TABLE_CELL, cells[cell],
                    rowIndex, cell));
        }
        return nodes;
    }

    private KnowledgeSourceNode node(KnowledgeNodeType type, String text) {
        return node(artifactId, root.id(), type, text, null, null);
    }

    private KnowledgeSourceNode node(
            UUID artifactId, UUID parentId, KnowledgeNodeType type, String text,
            Integer rowIndex, Integer cellIndex) {
        return new KnowledgeSourceNode(UUID.randomUUID(), artifactId, parentId, type, ordinal++,
                text, null, null, "word/document.xml", ordinal, null, null, rowIndex,
                cellIndex, null, null, null, cellIndex, null, null);
    }

    private KnowledgeSourceNode root() {
        return new KnowledgeSourceNode(UUID.randomUUID(), artifactId, null,
                KnowledgeNodeType.BODY, 0, null, null, null, "word/document.xml", 0,
                null, null, null, null, null, null, null, null, null, null);
    }
}
