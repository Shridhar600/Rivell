package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Table shaping must not turn a grid of sentences into labelled fields: a cell's first line
 * is only a label when it reads as one. Duplicate labels keep the role of their base label.
 */
class KnowledgeTableShapingTest {

    @Test
    void aTwoColumnGridOfMultiLineCellsStaysAGrid() {
        var rows = List.of(
                row(0,
                        "The Supplier shall\ndefend and indemnify the Customer against all third-party claims.",
                        "Fallback 1\nThe Supplier's liability is capped at the fees paid."),
                row(1,
                        "The Customer shall\ngive prompt notice of any claim.",
                        "Preferred\nNo cap applies to the indemnity."));

        var shape = KnowledgeTableShaping.shape(rows);

        assertThat(shape).isInstanceOf(KnowledgeTableShaping.Grid.class);
        var grid = (KnowledgeTableShaping.Grid) shape;
        assertThat(grid.rows()).hasSize(2);
        assertThat(grid.rows().get(0).columns().values())
                .anyMatch(value -> value.contains("The Supplier shall"));
        assertThat(grid.rows().get(1).columns().values())
                .anyMatch(value -> value.contains("The Customer shall"));
    }

    @Test
    void aMetadataTableOfCertainLabelsStaysOneRecord() {
        var rows = List.of(
                row(0, "OWNER\nDirector, Commercial Legal", "VERSION\n1.0"),
                row(1, "APPROVAL\nGeneral Counsel", "EFFECTIVE\n1 October 2026"));

        var shape = KnowledgeTableShaping.shape(rows);

        assertThat(shape).isInstanceOf(KnowledgeTableShaping.KeyValue.class);
        assertThat(((KnowledgeTableShaping.KeyValue) shape).fields())
                .containsEntry("OWNER", "Director, Commercial Legal")
                .containsEntry("APPROVAL", "General Counsel")
                .containsEntry("EFFECTIVE", "1 October 2026");
    }

    @Test
    void aTitleCaseFormTableStaysLabelledFields() {
        var rows = List.of(
                row(0, "Approved by\nGeneral Counsel", "Version\n1.0"),
                row(1, "Dated as of\n1 October 2026", "Owner\nLegal"));

        var shape = KnowledgeTableShaping.shape(rows);

        assertThat(shape).isInstanceOf(KnowledgeTableShaping.KeyValue.class);
        assertThat(((KnowledgeTableShaping.KeyValue) shape).fields())
                .containsEntry("Approved by", "General Counsel")
                .containsEntry("Dated as of", "1 October 2026")
                .containsEntry("Owner", "Legal");
    }

    @Test
    void aMultiLineCellThatCannotBeSplitStaysAGrid() {
        var rows = List.of(
                row(0, "Owner\nlegal", "Version\n1.0"),
                row(1, "Approver\ncounsel", "Date\n1 October 2026"));

        var shape = KnowledgeTableShaping.shape(rows);

        assertThat(shape).isInstanceOf(KnowledgeTableShaping.Grid.class);
        var grid = (KnowledgeTableShaping.Grid) shape;
        assertThat(grid.rows()).hasSize(2);
        assertThat(grid.rows()).allSatisfy(row -> assertThat(row.columns().keySet())
                .noneMatch(name -> name.contains("\n")));
    }

    @Test
    void duplicateLabelsKeepTheRoleOfTheirBaseLabel() {
        var rows = List.of(
                row(0, "Preferred\nFirst position", "Preferred\nSecond position"));

        var shape = KnowledgeTableShaping.shape(rows);

        assertThat(shape).isInstanceOf(KnowledgeTableShaping.KeyValue.class);
        assertThat(((KnowledgeTableShaping.KeyValue) shape).fields())
                .containsKeys("Preferred", "Preferred (2)");
        assertThat(RecordLabelRoles.known("Preferred (2)")).contains(RecordFieldRole.PREFERRED);
    }

    private static KnowledgeTableShaping.Row row(int index, String... cells) {
        var rowId = UUID.randomUUID();
        var shaped = new ArrayList<KnowledgeTableShaping.Cell>();
        for (int column = 0; column < cells.length; column++) {
            shaped.add(new KnowledgeTableShaping.Cell(cells[column], UUID.randomUUID(), column));
        }
        return new KnowledgeTableShaping.Row(rowId, index, shaped);
    }
}
