package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic segmentation of an XLSX sheet into records (design §5 item 1). A sheet
 * table starts at the first row with at least two label-like cells followed by data rows;
 * earlier rows are sheet context. A table that is at most two populated columns wide with
 * a label-like first column is one record; a wider table is a header row plus one record
 * per data row. Rows outside any table stay records so no sheet content is lost.
 */
final class KnowledgeSheetShaping {

    private KnowledgeSheetShaping() {
    }

    sealed interface SheetRecord permits KeyValueBlock, DataRow, LooseRow {
    }

    record KeyValueBlock(Map<String, String> fields, List<UUID> provenance) implements SheetRecord {
    }

    record DataRow(Map<String, String> columns, List<UUID> provenance, String rawLabel)
            implements SheetRecord {
    }

    record LooseRow(String rawLabel, String text, List<UUID> provenance) implements SheetRecord {
    }

    static List<SheetRecord> records(List<KnowledgeTableShaping.Row> rows) {
        var blocks = blocks(rows);
        var firstBlockStart = blocks.keySet().stream().mapToInt(Integer::intValue).min().orElse(-1);
        var records = new ArrayList<SheetRecord>();
        int index = 0;
        while (index < rows.size()) {
            var blockEnd = blocks.get(index);
            if (blockEnd != null) {
                records.addAll(shape(rows.subList(index, blockEnd)));
                index = blockEnd;
                continue;
            }
            var row = rows.get(index);
            if (!row.isEmpty() && (firstBlockStart < 0 || index > firstBlockStart)) {
                records.add(loose(row));
            }
            index++;
        }
        return records;
    }

    private static Map<Integer, Integer> blocks(List<KnowledgeTableShaping.Row> rows) {
        var blocks = new LinkedHashMap<Integer, Integer>();
        int index = 0;
        while (index < rows.size()) {
            if (!isBlockStart(rows, index)) {
                index++;
                continue;
            }
            var end = index + 1;
            while (end < rows.size()
                    && !rows.get(end).isEmpty()
                    && rows.get(end).rowIndex() == rows.get(end - 1).rowIndex() + 1) {
                end++;
            }
            blocks.put(index, end);
            index = end;
        }
        return blocks;
    }

    /**
     * A table starts at a row with at least two label-like cells whose immediately next
     * row continues the table. A gap in row indexes means the file never materialised that
     * row, so the next row is not a continuation - without this check an omitted blank row
     * silently merged two tables.
     */
    private static boolean isBlockStart(List<KnowledgeTableShaping.Row> rows, int index) {
        var row = rows.get(index);
        if (row.isEmpty()) {
            return false;
        }
        var labelLike = row.nonBlankCells().stream()
                .filter(cell -> KnowledgeTableShaping.isLabelLike(cell.text()))
                .count();
        if (labelLike < 2 || index + 1 >= rows.size()) {
            return false;
        }
        var next = rows.get(index + 1);
        return !next.isEmpty() && next.rowIndex() == row.rowIndex() + 1;
    }

    private static List<SheetRecord> shape(List<KnowledgeTableShaping.Row> blockRows) {
        var shape = KnowledgeTableShaping.shape(blockRows);
        if (shape instanceof KnowledgeTableShaping.KeyValue keyValue) {
            return List.of(new KeyValueBlock(keyValue.fields(), keyValue.provenance()));
        }
        var grid = (KnowledgeTableShaping.Grid) shape;
        var records = new ArrayList<SheetRecord>(grid.rows().size());
        for (var row : grid.rows()) {
            records.add(new DataRow(row.columns(), row.provenance(),
                    KnowledgeTableShaping.firstValue(row.columns())));
        }
        return records;
    }

    private static LooseRow loose(KnowledgeTableShaping.Row row) {
        var cells = row.nonBlankCells();
        var text = cells.stream()
                .map(KnowledgeTableShaping.Cell::text)
                .reduce((a, b) -> a + " | " + b)
                .orElse(null);
        var provenance = new ArrayList<UUID>();
        provenance.add(row.rowNodeId());
        row.cells().forEach(cell -> provenance.add(cell.nodeId()));
        return new LooseRow(cells.get(0).text(), text, List.copyOf(provenance));
    }
}
