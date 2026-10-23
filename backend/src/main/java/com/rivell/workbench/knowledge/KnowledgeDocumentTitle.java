package com.rivell.workbench.knowledge;

import java.util.Comparator;
import java.util.List;

/**
 * The title a Word document gives itself, read from its leading nodes. Deterministic.
 * A document that opens with a masthead line ("ACME LEGAL | INTERNAL GUIDANCE") and then
 * a short line of its own is titled by that line; a masthead is a page header, not a name.
 */
final class KnowledgeDocumentTitle {

    private static final int MAX_WORDS = 12;

    private KnowledgeDocumentTitle() {
    }

    /** The document title, or null when the document names none or is a workbook. */
    static String of(List<KnowledgeSourceNode> nodes) {
        var root = nodes.stream().filter(node -> node.parentId() == null).findFirst().orElse(null);
        if (root == null || root.nodeType() == KnowledgeNodeType.WORKBOOK) {
            return null;
        }
        var leading = nodes.stream()
                .filter(node -> root.id().equals(node.parentId()))
                .sorted(Comparator.comparingInt(KnowledgeSourceNode::ordinal))
                .toList();
        for (int index = 0; index < leading.size(); index++) {
            var node = leading.get(index);
            if (node.nodeType() == KnowledgeNodeType.HEADING) {
                return null;
            }
            if (node.nodeType() != KnowledgeNodeType.TITLE || blank(node.text())) {
                continue;
            }
            var title = node.text().strip();
            var next = index + 1 < leading.size() ? leading.get(index + 1) : null;
            if (title.contains("|") && next != null && next.nodeType() == KnowledgeNodeType.PARAGRAPH
                    && ownTitle(next.text())) {
                return KnowledgeTitles.bounded(next.text());
            }
            return KnowledgeTitles.bounded(title);
        }
        return null;
    }

    private static boolean ownTitle(String text) {
        return !blank(text)
                && KnowledgeTableShaping.isLabelLike(text)
                && text.strip().split("\\s+").length <= MAX_WORDS;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
