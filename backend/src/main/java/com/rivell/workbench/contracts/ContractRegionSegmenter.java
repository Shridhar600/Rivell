package com.rivell.workbench.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic contract segmentation (S1): every top-level HEADING starts a clause
 * region, except a body-less heading that only groups deeper sub-clause headings; top-level paragraphs before the first heading form the preamble region
 * (anchored on the BODY node); TITLE nodes carry document metadata only. Tables are
 * kept inside their preceding region (schedule support).
 */
public final class ContractRegionSegmenter {

    private static final int MAX_CONTAINER_WORDS = 12;
    /** "SCHEDULE 1", also after a list number the heading text carries ("XXVI SCHEDULE 1"). */
    private static final java.util.regex.Pattern SCHEDULE_HEADING = java.util.regex.Pattern.compile(
            "(?s)(?:(?:\\d+(?:\\.\\d+)*|[IVXLC]+|[A-Z])[.):]?\\s+)?"
                    + "(?:SCHEDULE|EXHIBIT|ANNEX|APPENDIX|ATTACHMENT)\\b.*");

    /**
     * A DocumentNode is not automatically a legal clause (AD #5.3): the segmenter
     * classifies each region structurally. Signature blocks and schedules are kept in
     * the semantics but are never presented as clauses.
     */
    public enum SemanticRegionKind {
        CLAUSE,
        SIGNATURE_BLOCK,
        SCHEDULE
    }

    public record Region(
            UUID headingNodeId,
            String headingText,
            String numberingLabel,
            List<UUID> textNodeIds,
            String contentText,
            SemanticRegionKind kind) {

        public boolean isPreamble() {
            return headingText == null;
        }
    }

    /**
     * Headings whose clause a tracked change cannot replace or delete: regions that are not
     * clauses (schedules, signature blocks) and clauses whose body holds anything other than
     * paragraphs (a table, a text box) or content a tracked change cannot delete (a footnote
     * reference, a field). The redline plan applies the same rule and fails closed; callers use
     * this to refuse such a change before it is approved.
     */
    public java.util.Set<UUID> untrackableHeadings(List<DocumentNode> nodes) {
        var byId = new java.util.HashMap<UUID, DocumentNode>();
        for (var node : nodes) {
            byId.put(node.id(), node);
        }
        var untrackable = new java.util.HashSet<UUID>();
        for (var region : segment(nodes)) {
            if (region.isPreamble()) {
                continue;
            }
            var paragraphsOnly = region.textNodeIds().stream().map(byId::get)
                    .allMatch(node -> node != null && node.nodeType() == DocumentNodeType.PARAGRAPH
                            && !node.untrackableContent());
            // a run-in heading loses its clause text to a replacement, so its content counts too
            var heading = byId.get(region.headingNodeId());
            var headingTrimmable = heading == null || heading.runInHeading() == null
                    || !heading.untrackableContent();
            if (region.kind() != SemanticRegionKind.CLAUSE || !paragraphsOnly || !headingTrimmable) {
                untrackable.add(region.headingNodeId());
            }
        }
        return untrackable;
    }

    public List<Region> segment(List<DocumentNode> nodes) {
        var bodyId = bodyIdOf(nodes);
        var byId = new java.util.HashMap<UUID, DocumentNode>();
        for (var node : nodes) {
            byId.put(node.id(), node);
        }
        var top = nodes.stream()
                .filter(node -> node.nodeType() != DocumentNodeType.BODY)
                .filter(node -> node.parentId() != null && node.parentId().equals(bodyId))
                .toList();
        if (top.isEmpty()) {
            top = nodes.stream()
                    .filter(node -> node.nodeType() != DocumentNodeType.BODY)
                    .toList();
        }

        var regions = new ArrayList<Region>();
        var preambleNodes = new ArrayList<UUID>();
        var preambleTexts = new ArrayList<String>();
        var currentHeading = (DocumentNode) null;
        var currentTexts = new ArrayList<UUID>();
        var currentText = new StringBuilder();

        for (var node : top) {
            switch (node.nodeType()) {
                case TITLE -> { /* metadata layer handles titles */ }
                case HEADING -> {
                    if (!isContainer(currentHeading, currentTexts, node)) {
                        closeCurrent(regions, currentHeading, currentTexts, currentText);
                    }
                    currentHeading = node;
                    currentTexts = new ArrayList<>();
                    currentText = new StringBuilder();
                    if (node.text() != null && !node.text().isBlank()) {
                        currentText.append(node.text().strip());
                    }
                }
                case PARAGRAPH -> {
                    if (currentHeading != null && !currentTexts.isEmpty() && isWitnessLine(node)) {
                        // the execution block after the last clause or schedule is its own
                        // region: the clause stays reviewable and a replacement never reaches
                        // the signatures
                        closeCurrent(regions, currentHeading, currentTexts, currentText);
                        currentHeading = node;
                        currentTexts = new ArrayList<>();
                        currentText = new StringBuilder(node.text().strip());
                        continue;
                    }
                    if (currentHeading != null) {
                        currentTexts.add(node.id());
                        if (currentText.length() > 0) {
                            currentText.append('\n');
                        }
                        if (node.text() != null && !node.text().isBlank()) {
                            currentText.append(node.text().strip());
                        }
                    } else {
                        preambleNodes.add(node.id());
                        preambleTexts.add(node.text() == null ? "" : node.text());
                    }
                }
                case TABLE, TEXT_BOX -> {
                    var tableText = node.nodeType() == DocumentNodeType.TEXT_BOX
                            ? (node.text() == null ? "" : node.text())
                            : tableText(node, nodes, byId);
                    if (currentHeading != null) {
                        currentTexts.add(node.id());
                        if (currentText.length() > 0) {
                            currentText.append('\n');
                        }
                        if (!tableText.isBlank()) {
                            currentText.append(tableText.strip());
                        }
                    } else {
                        preambleNodes.add(node.id());
                        preambleTexts.add(tableText);
                    }
                }
                case TABLE_ROW, TABLE_CELL -> { /* reached via their TABLE ancestor */ }
                default -> { /* unsupported structures produce parser warnings upstream */ }
            }
        }
        closeCurrent(regions, currentHeading, currentTexts, currentText);

        if (!preambleNodes.isEmpty()) {
            regions.add(0, new Region(
                    bodyId, null, null, List.copyOf(preambleNodes),
                    String.join("\n", preambleTexts.stream()
                            .filter(text -> text != null && !text.isBlank())
                            .map(String::strip)
                            .toList()),
                    null));
        }
        return regions;
    }

    private String tableText(
            DocumentNode table, List<DocumentNode> nodes, Map<UUID, DocumentNode> byId) {
        var text = new StringBuilder();
        for (var node : nodes) {
            if (node.nodeType() == DocumentNodeType.TABLE_CELL
                    && node.parentId() != null
                    && byId.containsKey(node.parentId())
                    && byId.get(node.parentId()).parentId() != null
                    && byId.get(node.parentId()).parentId().equals(table.id())) {
                if (node.text() != null && !node.text().isBlank()) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(node.text().strip());
                }
            }
        }
        return text.toString();
    }

    /**
     * A short heading with no body directly above a deeper heading ("5 Confidentiality"
     * over "5.1 Confidential Information") only groups its sub-clauses. It is not a
     * clause of its own: judging an empty region yields noise, not findings.
     */
    private static boolean isContainer(
            DocumentNode heading, List<UUID> textNodes, DocumentNode next) {
        return heading != null && textNodes.isEmpty()
                && heading.headingLevel() != null && next.headingLevel() != null
                && next.headingLevel() > heading.headingLevel()
                && heading.text() != null
                && heading.text().strip().split("\\s+").length <= MAX_CONTAINER_WORDS;
    }

    private void closeCurrent(
            List<Region> regions, DocumentNode heading, List<UUID> textNodes,
            StringBuilder text) {
        if (heading == null) {
            return;
        }
        regions.add(new Region(
                heading.id(), heading.text(), heading.numberingLabel(),
                List.copyOf(textNodes), text.toString(), regionKind(heading, text)));
    }

    private SemanticRegionKind regionKind(DocumentNode heading, StringBuilder text) {
        var headingText = heading.text() == null ? "" : heading.text().strip();
        var body = text.toString().toUpperCase(java.util.Locale.ROOT);
        if (SCHEDULE_HEADING.matcher(headingText.toUpperCase(java.util.Locale.ROOT)).matches()) {
            return SemanticRegionKind.SCHEDULE;
        }
        if (body.contains("IN WITNESS WHEREOF")) {
            return SemanticRegionKind.SIGNATURE_BLOCK;
        }
        if (headingText.matches(".*\\b(?:Inc|LLC|Ltd|GmbH|Corp|Corporation|Company|Limited)\\.?\\s*$")) {
            return SemanticRegionKind.SIGNATURE_BLOCK;
        }
        return SemanticRegionKind.CLAUSE;
    }

    private static boolean isWitnessLine(DocumentNode paragraph) {
        return paragraph.text() != null && paragraph.text().strip()
                .toUpperCase(java.util.Locale.ROOT).startsWith("IN WITNESS");
    }

    private UUID bodyIdOf(List<DocumentNode> nodes) {
        return nodes.stream()
                .filter(node -> node.nodeType() == DocumentNodeType.BODY)
                .map(DocumentNode::id)
                .findFirst()
                .orElse(nodes.isEmpty() ? UUID.randomUUID() : nodes.get(0).id());
    }
}
