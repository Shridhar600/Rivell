package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic table shape classification (design §5 item 1). A KEY_VALUE table is one
 * record with label → value fields: a first column of known field labels, or two populated
 * columns with label-like first cells and no header row. A GRID table (a header row of
 * label-like cells followed by text rows) yields one record per data row. No model.
 */
final class KnowledgeTableShaping {

    private static final int MAX_LABEL_WORDS = 8;
    private static final double LABEL_LIKE_RATIO = 0.8;
    private static final double ROLE_KEYED_RATIO = 0.5;

    private KnowledgeTableShaping() {
    }

    record Cell(String text, UUID nodeId, Integer columnIndex) {
    }

    record Row(UUID rowNodeId, int rowIndex, List<Cell> cells) {

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
        var width = nonEmpty.stream().mapToInt(KnowledgeTableShaping::populatedWidth).max().orElse(0);
        if (firstColumnLabelLikeRatio(nonEmpty) < LABEL_LIKE_RATIO) {
            return grid(nonEmpty);
        }
        var roleRatio = firstColumnRoleRatio(nonEmpty);
        if (roleRatio >= LABEL_LIKE_RATIO) {
            return keyValue(nonEmpty);
        }
        // Two columns of "subject | stance" under a header row ("Clause | Our position") are
        // a grid of positions: a short header over sentence values. Short label-like values
        // ("Version | 2.2") stay one key-value record even when the first row looks like a header.
        var subjectGrid = isHeaderRow(nonEmpty.get(0)) && roleRatio < ROLE_KEYED_RATIO
                && sentenceValueRatio(nonEmpty.subList(1, nonEmpty.size())) >= ROLE_KEYED_RATIO;
        return width <= 2 && !subjectGrid ? keyValue(nonEmpty) : grid(nonEmpty);
    }

    private static double sentenceValueRatio(List<Row> rows) {
        if (rows.isEmpty()) {
            return 0.0;
        }
        long sentences = rows.stream()
                .map(Row::nonBlankCells)
                .filter(cells -> cells.size() > 1 && !isLabelLike(cells.getLast().text()))
                .count();
        return (double) sentences / rows.size();
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
                .filter(row -> {
                    var first = firstColumnCell(row);
                    return first != null && isLabelLike(first.text());
                })
                .count();
        return (double) labelLike / rows.size();
    }

    /**
     * A three-column table is a record, not a grid, when its first column carries known
     * field labels ("Standard position", "Fallback 1"): those are the table's fields with
     * an extra value column. A first column of subjects ("Clause", "Liability cap") keeps
     * the table a grid with a header row.
     */
    private static double firstColumnRoleRatio(List<Row> rows) {
        long known = rows.stream()
                .filter(row -> {
                    var first = firstColumnCell(row);
                    return first != null && RecordLabelRoles.known(first.text()).isPresent();
                })
                .count();
        return (double) known / rows.size();
    }

    /**
     * The cell that occupies column 0. A sparse XLSX row may omit it entirely; such a row
     * has no first-column label and must not count as one.
     */
    private static Cell firstColumnCell(Row row) {
        for (int index = 0; index < row.cells().size(); index++) {
            if (columnOf(row.cells().get(index), index) == 0) {
                return row.cells().get(index);
            }
        }
        return null;
    }

    private static int populatedWidth(Row row) {
        var width = 0;
        for (int index = 0; index < row.cells().size(); index++) {
            var cell = row.cells().get(index);
            if (cell.text() == null || cell.text().isBlank()) {
                continue;
            }
            width = Math.max(width, columnOf(cell, index) + 1);
        }
        return width;
    }

    private static int columnOf(Cell cell, int position) {
        return cell.columnIndex() != null ? cell.columnIndex() : position;
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
        var width = rows.stream().mapToInt(KnowledgeTableShaping::populatedWidth).max().orElse(0);
        if (header != null && rows.size() > 2) {
            var groups = ColumnGroups.of(header, rows.get(1), width);
            if (groups != null) {
                return groupedGrid(rows.subList(2, rows.size()), groups);
            }
        }
        var keys = columnKeys(headerLabels(header, width), width);
        var dataRows = header == null ? rows : rows.subList(1, rows.size());
        var shaped = new ArrayList<GridRow>(dataRows.size());
        for (var row : dataRows) {
            if (row.isEmpty()) {
                continue;
            }
            var columns = new LinkedHashMap<String, String>();
            var cells = row.cells();
            for (int index = 0; index < cells.size(); index++) {
                var cell = cells.get(index);
                if (cell.text() == null || cell.text().isBlank()) {
                    continue;
                }
                var column = columnOf(cell, index);
                columns.put(column < keys.size() ? keys.get(column) : "Column " + (column + 1),
                        cell.text());
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

    /**
     * A two-row header: a sparser top row groups the label row beneath it ("NDA | MSA | DPA"
     * over "Preferred | Fallback | Walk-away" repeated). A group label covers its column and
     * the blank top cells to its right, as a merged cell does. Groups that repeat another
     * group's labels are alternatives (one per contract type, say); the rest are shared.
     */
    private record ColumnGroups(List<String> labels, List<String> groupOf, Set<String> alternatives) {

        static ColumnGroups of(Row top, Row labelRow, int width) {
            if (!isHeaderRow(labelRow)
                    || labelRow.nonBlankCells().size() <= top.nonBlankCells().size()) {
                return null;
            }
            var labels = headerLabels(labelRow, width);
            var tops = headerLabels(top, width);
            var groupOf = new ArrayList<String>(width);
            String current = null;
            for (int column = 0; column < width; column++) {
                var group = tops.get(column);
                if (group != null && !group.isBlank()) {
                    current = group.strip();
                }
                groupOf.add(current);
            }
            var labelsByGroup = new LinkedHashMap<String, Set<String>>();
            for (int column = 0; column < width; column++) {
                var label = labels.get(column);
                var group = groupOf.get(column);
                if (group != null && label != null && !label.isBlank()
                        && !RecordLabelRoles.normalize(label).equals(RecordLabelRoles.normalize(group))) {
                    labelsByGroup.computeIfAbsent(group, ignored -> new LinkedHashSet<>())
                            .add(RecordLabelRoles.normalize(label));
                }
            }
            var alternatives = new LinkedHashSet<String>();
            for (var a : labelsByGroup.entrySet()) {
                for (var b : labelsByGroup.entrySet()) {
                    if (a != b && !Collections.disjoint(a.getValue(), b.getValue())) {
                        alternatives.add(a.getKey());
                    }
                }
            }
            return new ColumnGroups(labels, groupOf, Set.copyOf(alternatives));
        }

        String key(int column) {
            var label = column < labels.size() ? labels.get(column) : null;
            return label == null || label.isBlank() ? "Column " + (column + 1) : label.strip();
        }

        String group(int column) {
            return column < groupOf.size() ? groupOf.get(column) : null;
        }
    }

    /**
     * One record per data row and alternative group that has a value in it, carrying the shared
     * columns, that group's columns and the group label as its "Applies to" field. A row with
     * values in no alternative group is one record of its shared columns.
     */
    private static Grid groupedGrid(List<Row> dataRows, ColumnGroups groups) {
        var shaped = new ArrayList<GridRow>();
        for (var row : dataRows) {
            if (row.isEmpty()) {
                continue;
            }
            var filled = new ArrayList<Integer>();
            var cells = row.cells();
            for (int index = 0; index < cells.size(); index++) {
                if (cells.get(index).text() != null && !cells.get(index).text().isBlank()) {
                    filled.add(index);
                }
            }
            var present = new LinkedHashSet<String>();
            for (var index : filled) {
                var group = groups.group(columnOf(cells.get(index), index));
                if (groups.alternatives().contains(group)) {
                    present.add(group);
                }
            }
            var targets = present.isEmpty() ? java.util.Collections.<String>singletonList(null)
                    : List.copyOf(present);
            for (var target : targets) {
                var columns = new LinkedHashMap<String, String>();
                var provenance = new ArrayList<UUID>();
                provenance.add(row.rowNodeId());
                var appliesToAdded = false;
                for (var index : filled) {
                    var cell = cells.get(index);
                    var column = columnOf(cell, index);
                    var group = groups.group(column);
                    var alternative = groups.alternatives().contains(group);
                    if (alternative && !group.equals(target)) {
                        continue;
                    }
                    if (alternative && !appliesToAdded) {
                        columns.put(uniqueKey(columns, "Applies to"), group);
                        appliesToAdded = true;
                    }
                    columns.put(uniqueKey(columns, groups.key(column)), cell.text());
                    provenance.add(cell.nodeId());
                }
                shaped.add(new GridRow(Collections.unmodifiableMap(columns), List.copyOf(provenance)));
            }
        }
        return new Grid(List.copyOf(shaped));
    }

    /**
     * Header labels placed at their real column so a header with a blank column still
     * resolves every later cell to the column it came from.
     */
    private static List<String> headerLabels(Row header, int width) {
        var labels = new ArrayList<String>(width);
        for (int column = 0; column < width; column++) {
            labels.add(null);
        }
        if (header != null) {
            var cells = header.cells();
            for (int index = 0; index < cells.size(); index++) {
                var column = columnOf(cells.get(index), index);
                if (column < width) {
                    labels.set(column, cells.get(index).text());
                }
            }
        }
        return labels;
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
