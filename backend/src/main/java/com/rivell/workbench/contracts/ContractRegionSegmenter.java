package com.rivell.workbench.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic contract segmentation (S1): every top-level HEADING starts a clause
 * region; top-level paragraphs before the first heading form the preamble region
 * (anchored on the BODY node); TITLE nodes carry document metadata only. Tables are
 * kept inside their preceding region (schedule support).
 */
public final class ContractRegionSegmenter {

    public record Region(
            UUID headingNodeId,
            String headingText,
            String numberingLabel,
            List<UUID> textNodeIds,
            String contentText) {

        public boolean isPreamble() {
            return headingText == null;
        }
    }

    public List<Region> segment(List<DocumentNode> nodes) {
        var bodyId = bodyIdOf(nodes);
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
                    closeCurrent(regions, currentHeading, currentTexts, currentText);
                    currentHeading = node;
                    currentTexts = new ArrayList<>();
                    currentText = new StringBuilder();
                    if (node.text() != null && !node.text().isBlank()) {
                        currentText.append(node.text().strip());
                    }
                }
                case PARAGRAPH -> {
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
                case TABLE, TABLE_ROW, TABLE_CELL -> {
                    if (currentHeading != null) {
                        currentTexts.add(node.id());
                    } else {
                        preambleNodes.add(node.id());
                    }
                }
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
                            .toList())));
        }
        return regions;
    }

    private void closeCurrent(
            List<Region> regions, DocumentNode heading, List<UUID> textNodes,
            StringBuilder text) {
        if (heading == null) {
            return;
        }
        regions.add(new Region(
                heading.id(), heading.text(), heading.numberingLabel(),
                List.copyOf(textNodes), text.toString()));
    }

    private UUID bodyIdOf(List<DocumentNode> nodes) {
        return nodes.stream()
                .filter(node -> node.nodeType() == DocumentNodeType.BODY)
                .map(DocumentNode::id)
                .findFirst()
                .orElse(nodes.isEmpty() ? UUID.randomUUID() : nodes.get(0).id());
    }
}
