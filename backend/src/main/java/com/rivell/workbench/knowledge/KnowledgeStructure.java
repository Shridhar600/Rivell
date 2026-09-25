package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.HashMap;
import java.time.Instant;
import java.util.Comparator;

/**
 * Segmentation of the deterministic source-node tree into coherent regions
 * (Architecture Decision #7.2). DOCX: a top-level heading/title opens a section that
 * owns trailing paragraphs; tables are independent row sources. XLSX: each sheet's first
 * non-empty row is the header; every data row becomes a row region.
 */
final class KnowledgeStructure {

    private KnowledgeStructure() {
    }

    sealed interface Region {
        ExtractionUnit toUnit(UUID artifactId, int ordinal);

        List<KnowledgeCandidate> toCandidates(
                KnowledgeArtifact artifact, ExtractionUnit unit,
                ApplicabilityScope scope, Instant now);
    }

    static final class HeadingSection implements Region {
        private final KnowledgeSourceNode heading;
        private final List<KnowledgeSourceNode> paragraphs;

        HeadingSection(KnowledgeSourceNode heading, List<KnowledgeSourceNode> paragraphs) {
            this.heading = heading;
            this.paragraphs = paragraphs;
        }

        @Override
        public ExtractionUnit toUnit(UUID artifactId, int ordinal) {
            var memberIds = new ArrayList<UUID>();
            if (heading != null) {
                memberIds.add(heading.id());
            }
            paragraphs.forEach(node -> memberIds.add(node.id()));
            return new ExtractionUnit(UUID.randomUUID(), artifactId, ordinal,
                    ExtractionUnitKind.HEADING_SECTION, heading == null ? null : heading.text(),
                    joinedParagraphText(), memberIds);
        }

        @Override
        public List<KnowledgeCandidate> toCandidates(
                KnowledgeArtifact artifact, ExtractionUnit unit,
                ApplicabilityScope scope, Instant now) {
            if (unit.contentText() == null || unit.contentText().isBlank()) {
                return List.of();
            }
            var rawLabel = unit.title() != null && !unit.title().isBlank()
                    ? unit.title()
                    : unit.contentText().lines().findFirst().orElseThrow();
            return List.of(candidate(artifact, unit, scope, now, rawLabel, unit.contentText()));
        }

        private String joinedParagraphText() {
            return paragraphs.stream()
                    .map(KnowledgeSourceNode::text)
                    .filter(text -> text != null && !text.isBlank())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse(null);
        }
    }

    static final class TableRegion implements Region {
        private final KnowledgeSourceNode table;
        private final Map<Integer, List<KnowledgeSourceNode>> cellsByRow;
        private final List<String> headerLabels;

        TableRegion(KnowledgeSourceNode table, Map<Integer, List<KnowledgeSourceNode>> rowsByOrdinal,
                List<String> headerLabels) {
            this.table = table;
            this.cellsByRow = Collections.unmodifiableMap(rowsByOrdinal);
            this.headerLabels = headerLabels;
        }

        @Override
        public ExtractionUnit toUnit(UUID artifactId, int ordinal) {
            return new ExtractionUnit(UUID.randomUUID(), artifactId, ordinal,
                    ExtractionUnitKind.TABLE, null, null, List.of(table.id()));
        }

        @Override
        public List<KnowledgeCandidate> toCandidates(
                KnowledgeArtifact artifact, ExtractionUnit unit,
                ApplicabilityScope scope, Instant now) {
            var candidates = new ArrayList<KnowledgeCandidate>();
            boolean headerRow = true;
            for (var rowCells : cellsByRow.values()) {
                if (headerRow) {
                    // The first row supplies column labels; it is not knowledge itself.
                    headerRow = false;
                    continue;
                }
                if (rowCells.stream().allMatch(cell ->
                        cell.text() == null || cell.text().isBlank())) {
                    continue;
                }
                var columns = new LinkedHashMap<String, String>();
                for (int i = 0; i < rowCells.size(); i++) {
                    var cell = rowCells.get(i);
                    var header = i < headerLabels.size() && !headerLabels.get(i).isBlank()
                            ? headerLabels.get(i)
                            : "Column " + (i + 1);
                    columns.put(header, cell.text() == null ? "" : cell.text());
                }
                var rawLabel = rowCells.get(0).text() != null && !rowCells.get(0).text().isBlank()
                        ? rowCells.get(0).text()
                        : columns.values().stream().filter(value -> !value.isBlank())
                                .findFirst().orElse("");
                var contentText = rowCells.stream()
                        .map(KnowledgeSourceNode::text)
                        .filter(text -> text != null && !text.isBlank())
                        .reduce((a, b) -> a + " | " + b)
                        .orElse(null);
                candidates.add(new KnowledgeCandidate(
                        UUID.randomUUID(), artifact.id(), artifact.organizationId(), unit.id(),
                        candidateType(artifact.artifactType()), ReviewStatus.PENDING,
                        rawLabel, contentText, new CandidatePayload(columns), null, null,
                        null, null,
                        SemanticFields.EMPTY, null, List.of(),
                        null, null, null, scope, List.of(), null, null,
                        null, null, now, now));
            }
            return candidates;
        }
    }

    static final class RowRegion implements Region {
        private final KnowledgeSourceNode sheet;
        private final KnowledgeSourceNode row;
        private final List<KnowledgeSourceNode> cells;
        private final Map<Integer, String> headerLabels;

        RowRegion(KnowledgeSourceNode sheet, KnowledgeSourceNode row,
                List<KnowledgeSourceNode> cells, Map<Integer, String> headerLabels) {
            this.sheet = sheet;
            this.row = row;
            this.cells = cells;
            this.headerLabels = headerLabels;
        }

        @Override
        public ExtractionUnit toUnit(UUID artifactId, int ordinal) {
            var memberIds = new ArrayList<UUID>();
            memberIds.add(row.id());
            cells.forEach(cell -> memberIds.add(cell.id()));
            return new ExtractionUnit(UUID.randomUUID(), artifactId, ordinal,
                    ExtractionUnitKind.SPREADSHEET_ROW,
                    sheet.sheetName() + "!row" + row.rowIndex(),
                    rowContent(), memberIds);
        }

        @Override
        public List<KnowledgeCandidate> toCandidates(
                KnowledgeArtifact artifact, ExtractionUnit unit,
                ApplicabilityScope scope, Instant now) {
            var columns = new LinkedHashMap<String, String>();
            for (KnowledgeSourceNode cell : cells) {
                if (cell.text() == null || cell.text().isBlank()) {
                    continue;
                }
                var header = headerLabels.get(cell.columnIndex());
                columns.put(header != null && !header.isBlank() ? header
                        : "Column " + (cell.columnIndex() + 1), cell.text());
            }
            if (columns.isEmpty()) {
                return List.of();
            }
            var rawLabel = columns.values().iterator().next();
            var contentText = String.join(" | ", columns.values());
            return List.of(new KnowledgeCandidate(
                    UUID.randomUUID(), artifact.id(), artifact.organizationId(), unit.id(),
                    candidateType(artifact.artifactType()), ReviewStatus.PENDING,
                    rawLabel, contentText, new CandidatePayload(columns), null, null,
                    null, null,
                    SemanticFields.EMPTY, null, List.of(),
                    null, null, null, scope, List.of(), null, null,
                    null, null, now, now));
        }

        private String rowContent() {
            return cells.stream()
                    .map(KnowledgeSourceNode::text)
                    .filter(text -> text != null && !text.isBlank())
                    .reduce((a, b) -> a + " | " + b)
                    .orElse(null);
        }
    }

    private static KnowledgeCandidate candidate(
            KnowledgeArtifact artifact, ExtractionUnit unit, ApplicabilityScope scope,
            Instant now, String rawLabel, String contentText) {
        return new KnowledgeCandidate(
                UUID.randomUUID(), artifact.id(), artifact.organizationId(), unit.id(),
                candidateType(artifact.artifactType()), ReviewStatus.PENDING,
                rawLabel, contentText, CandidatePayload.EMPTY, null, null,
                null, null,
                SemanticFields.EMPTY, null, List.of(),
                null, null, null, scope, List.of(), null, null,
                null, null, now, now);
    }

    private static CandidateType candidateType(ArtifactType artifactType) {
        return switch (artifactType) {
            case PLAYBOOK -> CandidateType.PLAYBOOK_RULE;
            case CLAUSE_LIBRARY -> CandidateType.CLAUSE_VARIANT;
            case TEMPLATE -> CandidateType.TEMPLATE_CLAUSE;
            case EXECUTED_PRECEDENT -> CandidateType.PRECEDENT;
            case NEGOTIATION_HISTORY, EXTERNAL_REFERENCE -> CandidateType.REFERENCE;
        };
    }

    static Structure build(List<KnowledgeSourceNode> nodes) {
        var regions = new ArrayList<Region>();
        var byId = new HashMap<UUID, KnowledgeSourceNode>();
        for (KnowledgeSourceNode node : nodes) {
            byId.put(node.id(), node);
        }

        var root = nodes.stream()
                .filter(node -> node.parentId() == null)
                .findFirst()
                .orElse(null);
        if (root == null) {
            return new Structure(regions);
        }

        if (root.nodeType() == KnowledgeNodeType.WORKBOOK) {
            buildSpreadsheet(nodes, byId, root, regions);
            return new Structure(regions);
        }

        HeadingSection open = null;
        for (KnowledgeSourceNode node : childrenOf(root, nodes)) {
            switch (node.nodeType()) {
                case TITLE, HEADING -> {
                    open = new HeadingSection(node, new ArrayList<>());
                    regions.add(open);
                }
                case PARAGRAPH -> {
                    if (open == null) {
                        open = new HeadingSection(null, new ArrayList<>());
                        regions.add(open);
                    }
                    open.paragraphs.add(node);
                }
                case TABLE -> regions.add(buildTable(node, byId));
                default -> {
                }
            }
        }
        return new Structure(regions);
    }

    record Structure(List<Region> regions) {
    }

    private static Region buildTable(
            KnowledgeSourceNode table, Map<UUID, KnowledgeSourceNode> byId) {
        var rowsByOrdinal = new TreeMap<Integer, List<KnowledgeSourceNode>>();
        for (var row : byId.values().stream()
                .filter(node -> table.id().equals(node.parentId())
                        && node.nodeType() == KnowledgeNodeType.TABLE_ROW)
                .sorted(Comparator.comparingInt(KnowledgeSourceNode::ordinal))
                .toList()) {
            rowsByOrdinal.put(row.ordinal(), cellsOf(row, byId));
        }
        var headerLabels = new ArrayList<String>();
        if (!rowsByOrdinal.isEmpty()) {
            for (var cell : rowsByOrdinal.firstEntry().getValue()) {
                headerLabels.add(cell.text() == null ? "" : cell.text());
            }
        }
        return new TableRegion(table, rowsByOrdinal, headerLabels);
    }

    private static List<KnowledgeSourceNode> cellsOf(
            KnowledgeSourceNode row, Map<UUID, KnowledgeSourceNode> byId) {
        return byId.values().stream()
                .filter(node -> row.id().equals(node.parentId())
                        && (node.nodeType() == KnowledgeNodeType.SPREADSHEET_CELL
                                || node.nodeType() == KnowledgeNodeType.TABLE_CELL))
                .sorted(Comparator.comparingInt(KnowledgeSourceNode::ordinal))
                .toList();
    }

    private static void buildSpreadsheet(
            List<KnowledgeSourceNode> nodes, Map<UUID, KnowledgeSourceNode> byId,
            KnowledgeSourceNode workbook, List<Region> regions) {
        for (KnowledgeSourceNode sheet : nodes.stream()
                .filter(node -> workbook.id().equals(node.parentId()))
                .sorted(Comparator.comparingInt(KnowledgeSourceNode::ordinal))
                .toList()) {
            var rows = nodes.stream()
                    .filter(node -> sheet.id().equals(node.parentId())
                            && node.nodeType() == KnowledgeNodeType.SPREADSHEET_ROW)
                    .sorted(Comparator.comparingInt(KnowledgeSourceNode::rowIndex))
                    .toList();
            if (rows.isEmpty()) {
                continue;
            }
            var headerLabels = new HashMap<Integer, String>();
            for (KnowledgeSourceNode cell : spreadsheetCells(rows.get(0), byId)) {
                if (cell.text() != null && !cell.text().isBlank()) {
                    headerLabels.put(cell.columnIndex(), cell.text());
                }
            }
            for (int i = 1; i < rows.size(); i++) {
                var row = rows.get(i);
                var sheetRowCells = spreadsheetCells(row, byId);
                if (sheetRowCells.stream().noneMatch(cell ->
                        cell.text() != null && !cell.text().isBlank())) {
                    continue;
                }
                regions.add(new RowRegion(sheet, row, sheetRowCells, headerLabels));
            }
        }
    }

    private static List<KnowledgeSourceNode> spreadsheetCells(
            KnowledgeSourceNode row, Map<UUID, KnowledgeSourceNode> byId) {
        return byId.values().stream()
                .filter(node -> row.id().equals(node.parentId())
                        && node.nodeType() == KnowledgeNodeType.SPREADSHEET_CELL)
                .sorted(Comparator.comparingInt(KnowledgeSourceNode::columnIndex))
                .toList();
    }

    private static List<KnowledgeSourceNode> childrenOf(
            KnowledgeSourceNode parent, List<KnowledgeSourceNode> nodes) {
        return nodes.stream()
                .filter(node -> parent.id().equals(node.parentId()))
                .sorted(Comparator.comparingInt(KnowledgeSourceNode::ordinal))
                .toList();
    }
}
