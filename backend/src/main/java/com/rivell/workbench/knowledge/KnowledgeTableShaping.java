package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic table shape classification (design §5 item 1). A KEY_VALUE table (at most
 * two populated columns, first-column cells label-like in at least 80% of rows) is one
 * record with label → value fields. A GRID table (a header row of label-like cells
 * followed by text rows) yields one record per data row. No model, no heuristics beyond
 * the label-like test.
 */
final class KnowledgeTableShaping {

    private static final int MAX_LABEL_WORDS = 8;
    private static final double LABEL_LIKE_RATIO = 0.8;

    private KnowledgeTableShaping() {
    }

    record Cell(String text, UUID nodeId) {
    }

    record Row(UUID rowNodeId, List<Cell> cells) {

        Row {
            cells = List.copyOf(cells);
        }

        boolean isEmpty() {
            return cells.stream().allMatch(cell -> cell.text() == null || cell.text().isBlank());
        }

        List<Cell> nonBlankCells() {
            return cells.stream()
                    .filter(cell -> cell.text() != null && !cell.text().isBlank())
                    .toList();
        }
    }

    sealed interface Shape permits KeyValue, Grid {
    }

    /** One record for the whole table: fields in source order. */
    record KeyValue(Map<String, String> fields, List<UUID> provenance) implements Shape {
    }

    /** One data row: columns keyed by the header label in source order. */
    record GridRow(Map<String, String> columns, List<UUID> provenance) {
    }

    record Grid(List<GridRow> rows) implements Shape {
    }

    static Shape shape(List<Row> rows) {
        var nonEmpty = rows.stream().filter(row -> !row.isEmpty()).toList();
        if (nonEmpty.isEmpty()) {
            return new Grid(List.of());
        }
        var width = nonEmpty.stream().mapToInt(row -> row.nonBlankCells().size()).max().orElse(0);
        if (width <= 2 && firstColumnLabelLikeRatio(nonEmpty) >= LABEL_LIKE_RATIO) {
            return keyValue(nonEmpty);
        }
        return grid(nonEmpty);
    }

    /**
     * A label-like cell is short, carries no sentence-ending punctuation, and is not a
     * bare number. It distinguishes "Standard position" from a sentence of wording.
     */
    static boolean isLabelLike(String text) {
        if (text == null) {
            return false;
        }
        var stripped = text.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        if (stripped.matches("[-+]?\\d+(?:[.,]\\d+)?")) {
            return false;
        }
        if (stripped.matches(".*[.!?]$")) {
            return false;
        }
        return stripped.split("\\s+").length <= MAX_LABEL_WORDS;
    }

    static List<String> columnKeys(List<String> headerLabels, int width) {
        var keys = new ArrayList<String>(width);
        var taken = new LinkedHashSet<String>();
        for (int i = 0; i < width; i++) {
            var label = i < headerLabels.size() ? headerLabels.get(i) : null;
            var base = label == null || label.isBlank() ? "Column " + (i + 1) : label.strip();
            var key = base;
            for (int suffix = 2; taken.contains(key); suffix++) {
                key = base + " (" + suffix + ")";
            }
            taken.add(key);
            keys.add(key);
        }
        return List.copyOf(keys);
    }

    static String firstValue(Map<String, String> columns) {
        return columns.values().stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private static double firstColumnLabelLikeRatio(List<Row> rows) {
        long labelLike = rows.stream()
                .filter(row -> !row.cells().isEmpty() && isLabelLike(row.cells().get(0).text()))
                .count();
        return (double) labelLike / rows.size();
    }

    private static KeyValue keyValue(List<Row> rows) {
        var fields = new LinkedHashMap<String, String>();
        var provenance = new ArrayList<UUID>();
        for (var row : rows) {
            var cells = row.nonBlankCells();
            if (cells.isEmpty()) {
                continue;
            }
            var label = cells.get(0).text().strip();
            var value = cells.stream().skip(1)
                    .map(Cell::text)
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("");
            fields.put(uniqueKey(fields, label), value);
            provenance.add(row.rowNodeId());
            row.cells().forEach(cell -> provenance.add(cell.nodeId()));
        }
        return new KeyValue(Collections.unmodifiableMap(fields), List.copyOf(provenance));
    }

    private static Grid grid(List<Row> rows) {
        var first = rows.get(0);
        var header = isHeaderRow(first) ? first : null;
        var width = rows.stream().mapToInt(row -> row.cells().size()).max().orElse(0);
        var keys = header == null
                ? columnKeys(List.of(), width)
                : columnKeys(header.cells().stream().map(Cell::text).toList(), width);
        var dataRows = header == null ? rows : rows.subList(1, rows.size());
        var shaped = new ArrayList<GridRow>(dataRows.size());
        for (var row : dataRows) {
            if (row.isEmpty()) {
                continue;
            }
            var columns = new LinkedHashMap<String, String>();
            var cells = row.cells();
            for (int i = 0; i < cells.size(); i++) {
                var cell = cells.get(i);
                if (cell.text() == null || cell.text().isBlank()) {
                    continue;
                }
                columns.put(i < keys.size() ? keys.get(i) : "Column " + (i + 1), cell.text());
            }
            if (columns.isEmpty()) {
                continue;
            }
            var provenance = new ArrayList<UUID>();
            provenance.add(row.rowNodeId());
            cells.forEach(cell -> provenance.add(cell.nodeId()));
            shaped.add(new GridRow(Collections.unmodifiableMap(columns), List.copyOf(provenance)));
        }
        return new Grid(List.copyOf(shaped));
    }

    private static boolean isHeaderRow(Row row) {
        var cells = row.nonBlankCells();
        if (cells.size() < 2) {
            return false;
        }
        var labelLike = cells.stream().filter(cell -> isLabelLike(cell.text())).count();
        return (double) labelLike / cells.size() >= LABEL_LIKE_RATIO;
    }

    private static String uniqueKey(Map<String, String> taken, String base) {
        var key = base;
        for (int suffix = 2; taken.containsKey(key); suffix++) {
            key = base + " (" + suffix + ")";
        }
        return key;
    }
}
