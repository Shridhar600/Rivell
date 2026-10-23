package com.rivell.workbench.knowledge;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The title a Word document gives itself, read from its leading nodes. Deterministic.
 * A document that opens with a masthead line ("ACME LEGAL | INTERNAL GUIDANCE") and then
 * a short line of its own is titled by that line; a masthead is a page header, not a name.
 */
final class KnowledgeDocumentTitle {

    private static final int MAX_WORDS = 20;

    private KnowledgeDocumentTitle() {
    }

    /**
     * The document title and the node it came from, or null when the document names none or is
     * a workbook. The node id matters: only that node may use the title as a section name.
     */
    static DocumentTitle of(List<KnowledgeSourceNode> nodes) {
        var root = nodes.stream().filter(node -> node.parentId() == null).findFirst().orElse(null);
        if (root == null || root.nodeType() == KnowledgeNodeType.WORKBOOK) {
            return null;
        }
        var leading = nodes.stream()
                .filter(node -> root.id().equals(node.parentId()))
                .sorted(Comparator.comparingInt(KnowledgeSourceNode::ordinal))
                .toList();
        var sawMasthead = false;
        for (var node : leading) {
            var text = node.text() == null ? "" : node.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            switch (node.nodeType()) {
                case TITLE -> {
                    if (isMasthead(text)) {
                        sawMasthead = true;
                        continue;
                    }
                    var title = titled(node, text);
                    if (title != null) {
                        return title;
                    }
                }
                case PARAGRAPH -> {
                    if (isMasthead(text)) {
                        sawMasthead = true;
                        continue;
                    }
                    // A short leading line names the document even without a Title style. Only a
                    // masthead earns the wider title allowance, so an opening sentence stays prose.
                    var eligible = sawMasthead ? ownTitle(text) : KnowledgeTableShaping.isLabelLike(text);
                    return eligible ? titled(node, text) : null;
                }
                case HEADING -> {
                    // A document that opens with a heading has no separate title; only a heading
                    // after a masthead is the real title.
                    return sawMasthead && ownTitle(text) ? titled(node, text) : null;
                }
                default -> {
                    return null;
                }
            }
        }
        return null;
    }

    private static DocumentTitle titled(KnowledgeSourceNode node, String text) {
        var bounded = KnowledgeTitles.bounded(text);
        return bounded == null || bounded.isBlank() ? null : new DocumentTitle(node.id(), bounded);
    }

    private static boolean ownTitle(String text) {
        if (text == null || text.isBlank() || isMasthead(text)) {
            return false;
        }
        var stripped = text.strip();
        return stripped.chars().anyMatch(Character::isLetter)
                && stripped.split("\\s+").length <= MAX_WORDS;
    }

    /** A page header, not a name: all caps, or a run of parts separated by a pipe. */
    private static boolean isMasthead(String text) {
        var stripped = text.strip();
        if (stripped.contains("|")) {
            return true;
        }
        var letters = stripped.chars().filter(Character::isLetter).toArray();
        if (letters.length < 3) {
            return false;
        }
        var sawUpperCase = false;
        for (var letter : letters) {
            if (Character.isLowerCase(letter)) {
                return false;
            }
            if (Character.isUpperCase(letter)) {
                sawUpperCase = true;
            }
        }
        return sawUpperCase;
    }

    record DocumentTitle(UUID nodeId, String text) {
    }
}
