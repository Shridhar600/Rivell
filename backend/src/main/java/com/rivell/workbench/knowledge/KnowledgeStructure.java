package com.rivell.workbench.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        private final KnowledgeDocumentTitle.DocumentTitle documentTitle;

        ProseRegion(KnowledgeSourceNode heading, List<KnowledgeSourceNode> paragraphs) {
            this(heading, paragraphs, null);
        }

        /** The region a document's title opens: it is named by the document title, not its masthead. */
        ProseRegion(KnowledgeSourceNode heading, List<KnowledgeSourceNode> paragraphs,
                KnowledgeDocumentTitle.DocumentTitle documentTitle) {
            this.heading = heading;
            this.paragraphs = paragraphs;
            this.documentTitle = documentTitle;
        }

        private boolean ownsDocumentTitle() {
            if (heading == null || heading.nodeType() != KnowledgeNodeType.TITLE
                    || documentTitle == null) {
                return false;
            }
            return heading.id().equals(documentTitle.nodeId())
                    || paragraphs.stream().anyMatch(node -> node.id().equals(documentTitle.nodeId()));
        }

        String headingText() {
            if (ownsDocumentTitle()) {
                return documentTitle.text();
            }
            return heading == null ? null : heading.text();
        }

        /** A document title is not a section heading: the tables after it are not under it. */
        boolean isTitleRegion() {
            return ownsDocumentTitle();
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
                    joinedParagraphText(), memberIds, null, null);
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
                    : unit.contentText();
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
                    sectionTitle, new CandidatePayload(fields).render(), sourceNodeIds, null, null);
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
                    sectionTitle, new CandidatePayload(columns).render(), sourceNodeIds, null, null);
        }

        @Override
        public List<KnowledgeCandidate> toCandidates(
                KnowledgeArtifact artifact, ExtractionUnit unit,
                ApplicabilityScope scope, Instant now) {
            return List.of(structuredCandidate(artifact, unit, scope, now,
                    recordLabel, columns));
        }
    }

    /**
     * One row of a table with a single column: a callout box or a list item. It is prose, not a
     * grid, so it has no column names. A lead line that reads as a label ("DO NOT TRADE THIS
     * BOUNDARY FOR SPEED") titles it and the rest is its wording.
     */
    static final class CalloutRegion implements Region {
        private final String label;
        private final String text;
        private final String cellText;
        private final List<UUID> sourceNodeIds;

        CalloutRegion(String cellText, List<UUID> sourceNodeIds) {
            var lines = cellText.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
            var labelled = lines.size() > 1
                    && KnowledgeTableShaping.isCertainLabel(lines.getFirst());
            this.label = labelled ? lines.getFirst() : null;
            this.text = labelled ? String.join("\n", lines.subList(1, lines.size())) : cellText.strip();
            this.cellText = cellText.strip();
            this.sourceNodeIds = List.copyOf(sourceNodeIds);
        }

        private String title() {
            return KnowledgeTitles.bounded(label != null ? label : text);
        }

        /** A callout whose lead line names nothing still needs a non-blank, one-line name. */
        private String titleOrText() {
            var title = title();
            if (title != null && !title.isBlank()) {
                return title;
            }
            return text.lines().map(String::strip).filter(line -> !line.isEmpty())
                    .findFirst().orElse(text);
        }

        @Override
        public ExtractionUnit toUnit(UUID artifactId, int ordinal) {
            return new ExtractionUnit(UUID.randomUUID(), artifactId, ordinal, ExtractionUnitKind.TABLE,
                    titleOrText(), cellText, sourceNodeIds, null, null);
        }

        @Override
        public List<KnowledgeCandidate> toCandidates(
                KnowledgeArtifact artifact, ExtractionUnit unit,
                ApplicabilityScope scope, Instant now) {
            return List.of(candidate(artifact, unit, scope, now, titleOrText(), text));
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
                    ExtractionUnitKind.SPREADSHEET_ROW, sheetName, text, sourceNodeIds, null, null);
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
                displayTitle(rawLabel), contentText, CandidatePayload.EMPTY, null, List.of(), null,
                null, null,
                SemanticFields.EMPTY, null, List.of(),
                null, null, null, scope, List.of(), null, null,
                null, null, now, now, null);
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
                displayTitle(rawLabel),
                candidateType(artifact.artifactType()) == CandidateType.CLAUSE_VARIANT
                        ? payload.renderedWording() : payload.render(),
                payload, null, List.of(), null,
                null, null,
                SemanticFields.EMPTY, null, List.of(),
                null, null, null, scope, List.of(), null, null,
                null, null, now, now, null);
    }

    /** A record with no words of its own still needs a non-blank name; its source text is it. */
    private static String displayTitle(String rawLabel) {
        var title = KnowledgeTitles.bounded(rawLabel);
        if (title != null && !title.isBlank()) {
            return title;
        }
        return rawLabel == null ? "" : rawLabel.strip();
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
        var documentTitle = KnowledgeDocumentTitle.of(nodes);
        for (KnowledgeSourceNode node : childrenOf(root, nodes)) {
            switch (node.nodeType()) {
                case TITLE -> {
                    open = new ProseRegion(node, new ArrayList<>(), documentTitle);
                    regions.add(open);
                }
                case HEADING -> {
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
        var sectionTitle = open == null || open.isTitleRegion() ? null : open.headingText();
        var populated = rows.stream().filter(row -> !row.isEmpty()).toList();
        if (!populated.isEmpty() && populated.stream().allMatch(row -> row.nonBlankCells().size() == 1)) {
            // A one-column list often carries a header ("Term"). It names the list, not an entry.
            var start = populated.size() > 1 && isSingleColumnHeader(populated.getFirst()) ? 1 : 0;
            for (var row : populated.subList(start, populated.size())) {
                var provenance = new ArrayList<UUID>();
                provenance.add(table.id());
                provenance.add(row.rowNodeId());
                row.cells().forEach(cell -> provenance.add(cell.nodeId()));
                regions.add(new CalloutRegion(row.nonBlankCells().getFirst().text(), provenance));
            }
            return;
        }
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
            var rowLabel = KnowledgeTableShaping.firstValue(row.columns());
            // The unit title is the model's section path; the row's own label is display only.
            regions.add(new GridRecordRegion(ExtractionUnitKind.TABLE, sectionTitle,
                    rowLabel, row.columns(), provenance));
        }
    }

    /** Known one-column list names; anything else is a value, so no source row is dropped. */
    private static final Set<String> LIST_HEADERS = Set.of(
            "term", "defined term", "definition", "definitions", "item", "items", "name",
            "clause", "topic", "concept", "key", "category");

    /** A known one-line list name at the top of a one-column list names the list, not a value. */
    private static boolean isSingleColumnHeader(KnowledgeTableShaping.Row row) {
        var text = row.nonBlankCells().getFirst().text().strip();
        if (text.lines().count() != 1) {
            return false;
        }
        var normalized = RecordLabelRoles.normalize(text);
        return LIST_HEADERS.contains(normalized)
                || normalized.startsWith("definition ")
                || normalized.startsWith("defined term ");
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
