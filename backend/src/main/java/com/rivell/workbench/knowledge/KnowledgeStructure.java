package com.rivell.workbench.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Segmentation of the deterministic source-node tree into knowledge records (design §5
 * item 1). A record is one playbook position or one clause wording variant. DOCX heading
 * sections own the tables that sit under them; a KEY_VALUE table is one record and a GRID
 * table yields one record per data row. XLSX sheets use the first row with two or more
 * label-like cells as their table start; earlier rows are context. Deterministic and
 * model-free.
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

    static final class ProseRegion implements Region {
        private final KnowledgeSourceNode heading;
        private final List<KnowledgeSourceNode> paragraphs;

        ProseRegion(KnowledgeSourceNode heading, List<KnowledgeSourceNode> paragraphs) {
            this.heading = heading;
            this.paragraphs = paragraphs;
        }

        String headingText() {
            return heading == null ? null : heading.text();
        }

        @Override
        public ExtractionUnit toUnit(UUID artifactId, int ordinal) {
            var memberIds = new ArrayList<UUID>();
            if (heading != null) {
                memberIds.add(heading.id());
            }
            paragraphs.forEach(node -> memberIds.add(node.id()));
            return new ExtractionUnit(UUID.randomUUID(), artifactId, ordinal,
                    ExtractionUnitKind.HEADING_SECTION, headingText(),
                    joinedParagraphText(), memberIds, null);
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

    /** One table or sheet block: label → value fields. */
    static final class FieldRecordRegion implements Region {
        private final ExtractionUnitKind unitKind;
        private final String sectionTitle;
        private final String recordLabel;
        private final Map<String, String> fields;
        private final List<UUID> sourceNodeIds;

        FieldRecordRegion(ExtractionUnitKind unitKind, String sectionTitle, String recordLabel,
                Map<String, String> fields, List<UUID> sourceNodeIds) {
            this.unitKind = unitKind;
            this.sectionTitle = sectionTitle;
            this.recordLabel = recordLabel;
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            this.sourceNodeIds = List.copyOf(sourceNodeIds);
        }

        @Override
        public ExtractionUnit toUnit(UUID artifactId, int ordinal) {
            return new ExtractionUnit(UUID.randomUUID(), artifactId, ordinal, unitKind,
                    sectionTitle, new CandidatePayload(fields).render(), sourceNodeIds, null);
        }

        @Override
        public List<KnowledgeCandidate> toCandidates(
                KnowledgeArtifact artifact, ExtractionUnit unit,
                ApplicabilityScope scope, Instant now) {
            return List.of(structuredCandidate(artifact, unit, scope, now,
                    recordLabel, fields));
        }
    }

    /** One data row of a GRID table or sheet: header label → cell value. */
    static final class GridRecordRegion implements Region {
        private final ExtractionUnitKind unitKind;
        private final String sectionTitle;
        private final String recordLabel;
        private final Map<String, String> columns;
        private final List<UUID> sourceNodeIds;

        GridRecordRegion(ExtractionUnitKind unitKind, String sectionTitle, String recordLabel,
                Map<String, String> columns, List<UUID> sourceNodeIds) {
            this.unitKind = unitKind;
            this.sectionTitle = sectionTitle;
            this.recordLabel = recordLabel;
            this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
            this.sourceNodeIds = List.copyOf(sourceNodeIds);
        }

        @Override
        public ExtractionUnit toUnit(UUID artifactId, int ordinal) {
            return new ExtractionUnit(UUID.randomUUID(), artifactId, ordinal, unitKind,
                    sectionTitle, new CandidatePayload(columns).render(), sourceNodeIds, null);
        }

        @Override
        public List<KnowledgeCandidate> toCandidates(
                KnowledgeArtifact artifact, ExtractionUnit unit,
                ApplicabilityScope scope, Instant now) {
            return List.of(structuredCandidate(artifact, unit, scope, now,
                    recordLabel, columns));
        }
    }

    /** A sheet row outside any table: kept as a record, never a candidate by default. */
    static final class RowTextRegion implements Region {
        private final String sheetName;
        private final String recordLabel;
        private final String text;
        private final List<UUID> sourceNodeIds;

        RowTextRegion(String sheetName, String recordLabel, String text, List<UUID> sourceNodeIds) {
            this.sheetName = sheetName;
            this.recordLabel = recordLabel;
            this.text = text;
            this.sourceNodeIds = List.copyOf(sourceNodeIds);
        }

        @Override
        public ExtractionUnit toUnit(UUID artifactId, int ordinal) {
            return new ExtractionUnit(UUID.randomUUID(), artifactId, ordinal,
                    ExtractionUnitKind.SPREADSHEET_ROW, sheetName, text, sourceNodeIds, null);
        }

        @Override
        public List<KnowledgeCandidate> toCandidates(
                KnowledgeArtifact artifact, ExtractionUnit unit,
                ApplicabilityScope scope, Instant now) {
            return List.of(candidate(artifact, unit, scope, now, recordLabel, text));
        }
    }

    private static KnowledgeCandidate candidate(
            KnowledgeArtifact artifact, ExtractionUnit unit, ApplicabilityScope scope,
            Instant now, String rawLabel, String contentText) {
        return new KnowledgeCandidate(
                UUID.randomUUID(), artifact.id(), artifact.organizationId(), unit.id(),
                candidateType(artifact.artifactType()), ReviewStatus.PENDING,
                rawLabel, contentText, CandidatePayload.EMPTY, null, List.of(), null,
                null, null,
                SemanticFields.EMPTY, null, List.of(),
                null, null, null, scope, List.of(), null, null,
                null, null, now, now);
    }

    private static KnowledgeCandidate structuredCandidate(
            KnowledgeArtifact artifact, ExtractionUnit unit, ApplicabilityScope scope,
            Instant now, String recordLabel, Map<String, String> columns) {
        var payload = new CandidatePayload(columns);
        var rawLabel = recordLabel != null && !recordLabel.isBlank()
                ? recordLabel
                : KnowledgeTableShaping.firstValue(columns);
        return new KnowledgeCandidate(
                UUID.randomUUID(), artifact.id(), artifact.organizationId(), unit.id(),
                candidateType(artifact.artifactType()), ReviewStatus.PENDING,
                rawLabel, payload.render(), payload, null, List.of(), null,
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

        ProseRegion open = null;
        for (KnowledgeSourceNode node : childrenOf(root, nodes)) {
            switch (node.nodeType()) {
                case TITLE, HEADING -> {
                    open = new ProseRegion(node, new ArrayList<>());
                    regions.add(open);
                }
                case PARAGRAPH -> {
                    if (open == null) {
                        open = new ProseRegion(null, new ArrayList<>());
                        regions.add(open);
                    }
                    open.paragraphs.add(node);
                }
                case TABLE -> addTableRegions(node, byId, open, regions);
                default -> {
                }
            }
        }
        return new Structure(regions);
    }

    record Structure(List<Region> regions) {
    }

    private static void addTableRegions(
            KnowledgeSourceNode table, Map<UUID, KnowledgeSourceNode> byId,
            ProseRegion open, List<Region> regions) {
        var rows = new ArrayList<KnowledgeTableShaping.Row>();
        for (var row : rowsOf(table, byId)) {
            var cells = cellsOf(row, byId).stream()
                    .map(cell -> new KnowledgeTableShaping.Cell(
                            cell.text(), cell.id(), cell.columnIndex()))
                    .toList();
            rows.add(new KnowledgeTableShaping.Row(row.id(),
                    row.rowIndex() == null ? -1 : row.rowIndex(), cells));
        }
        var sectionTitle = open == null ? null : open.headingText();
        var shape = KnowledgeTableShaping.shape(rows);
        if (shape instanceof KnowledgeTableShaping.KeyValue keyValue) {
            var provenance = new ArrayList<UUID>();
            provenance.add(table.id());
            provenance.addAll(keyValue.provenance());
            var label = sectionTitle != null && !sectionTitle.isBlank()
                    ? sectionTitle
                    : keyValue.fields().keySet().stream().findFirst().orElse("");
            regions.add(new FieldRecordRegion(ExtractionUnitKind.TABLE, sectionTitle, label,
                    keyValue.fields(), provenance));
            return;
        }
        var grid = (KnowledgeTableShaping.Grid) shape;
        for (var row : grid.rows()) {
            var provenance = new ArrayList<UUID>();
            provenance.add(table.id());
            provenance.addAll(row.provenance());
            regions.add(new GridRecordRegion(ExtractionUnitKind.TABLE, sectionTitle,
                    KnowledgeTableShaping.firstValue(row.columns()), row.columns(), provenance));
        }
    }

    private static void buildSpreadsheet(
            List<KnowledgeSourceNode> nodes, Map<UUID, KnowledgeSourceNode> byId,
            KnowledgeSourceNode workbook, List<Region> regions) {
        for (KnowledgeSourceNode sheet : nodes.stream()
                .filter(node -> workbook.id().equals(node.parentId()))
                .sorted(Comparator.comparingInt(KnowledgeSourceNode::ordinal))
                .toList()) {
            var rows = new ArrayList<KnowledgeTableShaping.Row>();
            for (var row : nodes.stream()
                    .filter(node -> sheet.id().equals(node.parentId())
                            && node.nodeType() == KnowledgeNodeType.SPREADSHEET_ROW)
                    .sorted(Comparator.comparingInt(KnowledgeSourceNode::rowIndex))
                    .toList()) {
                var cells = spreadsheetCells(row, byId).stream()
                        .map(cell -> new KnowledgeTableShaping.Cell(
                                cell.text(), cell.id(), cell.columnIndex()))
                        .toList();
                rows.add(new KnowledgeTableShaping.Row(row.id(),
                        row.rowIndex() == null ? -1 : row.rowIndex(), cells));
            }
            for (var record : KnowledgeSheetShaping.records(rows)) {
                addSheetRecord(sheet, record, regions);
            }
        }
    }

    private static void addSheetRecord(
            KnowledgeSourceNode sheet, KnowledgeSheetShaping.SheetRecord record,
            List<Region> regions) {
        var sheetName = sheet.sheetName();
        switch (record) {
            case KnowledgeSheetShaping.KeyValueBlock block -> {
                var provenance = new ArrayList<UUID>();
                provenance.add(sheet.id());
                provenance.addAll(block.provenance());
                var label = block.fields().keySet().stream().findFirst().orElse(sheetName);
                regions.add(new FieldRecordRegion(ExtractionUnitKind.SPREADSHEET_ROW, sheetName,
                        label, block.fields(), provenance));
            }
            case KnowledgeSheetShaping.DataRow row -> {
                var provenance = new ArrayList<UUID>();
                provenance.add(sheet.id());
                provenance.addAll(row.provenance());
                regions.add(new GridRecordRegion(ExtractionUnitKind.SPREADSHEET_ROW, sheetName,
                        row.rawLabel(), row.columns(), provenance));
            }
            case KnowledgeSheetShaping.LooseRow row -> {
                var provenance = new ArrayList<UUID>();
                provenance.add(sheet.id());
                provenance.addAll(row.provenance());
                regions.add(new RowTextRegion(sheetName, row.rawLabel(), row.text(), provenance));
            }
        }
    }

    private static List<KnowledgeSourceNode> rowsOf(
            KnowledgeSourceNode table, Map<UUID, KnowledgeSourceNode> byId) {
        return byId.values().stream()
                .filter(node -> table.id().equals(node.parentId())
                        && node.nodeType() == KnowledgeNodeType.TABLE_ROW)
                .sorted(Comparator.comparingInt(KnowledgeSourceNode::ordinal))
                .toList();
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
