package com.rivell.workbench.knowledge;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The title a Word document gives itself, read from its leading nodes. Deterministic.
 * A document that opens with a masthead line ("ACME LEGAL | INTERNAL GUIDANCE") and then
 * a short line of its own is titled by that line; a masthead is a page header, not a name.
 * An all-caps line is also a title candidate: when only cover metadata or a numbered section
 * heading follows it, it is the document's own name (a playbook titled in capitals must not be
 * named by its "Owner:" line). A later, more specific all-caps title wins over a shorter
 * organisation masthead.
 */
final class KnowledgeDocumentTitle {

    private static final int MAX_WORDS = 20;
    private static final int MAX_TITLE_WORDS = 12;

    private static final Pattern METADATA_LABEL = Pattern.compile(
            "(?i)^\\s*(?:owner|document owner|version|date|effective(?: date)?|"
                    + "review(?:ed)?(?: date)?|last reviewed|last updated|updated|issued|"
                    + "issue date|document date|expiry date|author|prepared by|approved by|"
                    + "approver|approval(?: matrix)?|department|contacts?|status|classification|"
                    + "applies to|supersedes)\\s*:");

    /** A cover label on its own line; the value is the line below it, not a title. */
    private static final Pattern METADATA_LABEL_ONLY = Pattern.compile(
            "(?i)^\\s*(?:owner|document owner|version|date|effective|review|approval|"
                    + "approval matrix|applies to|author|prepared by|approved by|approver|"
                    + "department|contacts?|status|classification|supersedes)\\s*$");

    /** A section label ("1.", "4.2", "Article IV") is not a document title. */
    private static final Pattern NUMBERED_SECTION = Pattern.compile(
            "(?i)^\\s*(?:(?:article|section|clause|part)\\s+)?"
                    + "(?:\\d{1,3}(?:\\.\\d{1,3})*|\\(?[ivxlc]{1,6}\\)?)[.):]?\\s+");

    /** Cover legends are notices, not names. */
    private static final Pattern LEGEND = Pattern.compile(
            "(?i)\\b(?:confidential|draft|privileged|strictly private|internal use only|"
                    + "internal use|commercial in confidence|do not copy|do not distribute|"
                    + "not for distribution)\\b");

    /** A line that is only a legend, not a longer title that happens to contain the word. */
    private static final Set<String> STANDALONE_LEGENDS = Set.of(
            "confidential", "draft", "privileged", "strictly private", "internal use only",
            "internal use", "commercial in confidence", "do not copy", "do not distribute",
            "not for distribution", "confidential draft", "draft confidential",
            "this document is confidential", "this document is privileged");

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
        DocumentTitle allCapsCandidate = null;
        var sawPageHeader = false;
        var sawMetadataAfterAllCaps = false;
        var skipMetadataValue = false;
        for (var node : leading) {
            var text = node.text() == null ? "" : node.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (isMetadata(text)) {
                skipMetadataValue = isMetadataLabelOnly(text);
                if (allCapsCandidate != null) {
                    sawMetadataAfterAllCaps = true;
                }
                continue;
            }
            if (skipMetadataValue) {
                // The value under a bare cover label ("OWNER" / "Director, Commercial Legal").
                skipMetadataValue = false;
                continue;
            }
            if (isStandaloneLegend(text)) {
                continue;
            }
            switch (node.nodeType()) {
                case TITLE -> {
                    if (isPipeMasthead(text)) {
                        sawPageHeader = true;
                        continue;
                    }
                    if (isAllCaps(text)) {
                        allCapsCandidate = betterAllCapsCandidate(
                                allCapsCandidate, node, text);
                        continue;
                    }
                    return titled(node, text);
                }
                case PARAGRAPH -> {
                    if (isPipeMasthead(text)) {
                        sawPageHeader = true;
                        continue;
                    }
                    if (isAllCaps(text)) {
                        allCapsCandidate = betterAllCapsCandidate(
                                allCapsCandidate, node, text);
                        continue;
                    }
                    if (sawMetadataAfterAllCaps) {
                        // The all-caps line is the name; only a short label-like line below the
                        // cover block is a better one. A "Purpose: ..." sentence is not.
                        return KnowledgeTableShaping.isLabelLike(text)
                                ? titled(node, text) : allCapsCandidate;
                    }
                    // A short leading line names the document even without a Title style. Only a
                    // masthead earns the wider title allowance, so an opening sentence stays prose.
                    var eligible = sawPageHeader || allCapsCandidate != null
                            ? ownTitle(text) : KnowledgeTableShaping.isLabelLike(text);
                    return eligible ? titled(node, text) : allCapsCandidate;
                }
                case HEADING -> {
                    // A numbered section heading is part of the document, not its name. Word
                    // keeps the number in numberingLabel, not in the heading text.
                    if (hasNumberingLabel(node) || NUMBERED_SECTION.matcher(text).find()) {
                        return allCapsCandidate;
                    }
                    // A cover block after the all-caps line proves that line is the name, not a
                    // page header; the first section heading below it is not the document title.
                    if (sawMetadataAfterAllCaps) {
                        return allCapsCandidate;
                    }
                    if (sawPageHeader && allCapsCandidate != null) {
                        return allCapsCandidate;
                    }
                    // A document may open with its own heading and no separate title node.
                    return ownTitle(text) ? titled(node, text) : allCapsCandidate;
                }
                default -> {
                    return allCapsCandidate;
                }
            }
        }
        return allCapsCandidate;
    }

    /**
     * A later all-caps title replaces a shorter earlier one (an organisation masthead above the
     * real title). A notice or legend never becomes a candidate, and an all-caps candidate is
     * bounded like any other title.
     */
    private static DocumentTitle betterAllCapsCandidate(
            DocumentTitle current, KnowledgeSourceNode node, String text) {
        if (!ownAllCapsTitle(text) || isLegend(text)) {
            return current;
        }
        var next = titled(node, text);
        if (next == null) {
            return current;
        }
        if (current == null) {
            return next;
        }
        return wordCount(text) > wordCount(current.text()) ? next : current;
    }

    private static DocumentTitle titled(KnowledgeSourceNode node, String text) {
        var bounded = KnowledgeTitles.bounded(text);
        return bounded == null || bounded.isBlank() ? null : new DocumentTitle(node.id(), bounded);
    }

    private static boolean ownTitle(String text) {
        if (text == null || text.isBlank() || isPipeMasthead(text) || isMetadata(text)) {
            return false;
        }
        var stripped = text.strip();
        return stripped.chars().anyMatch(Character::isLetter)
                && wordCount(stripped) <= MAX_WORDS;
    }

    private static boolean ownAllCapsTitle(String text) {
        return isAllCaps(text) && wordCount(text) <= MAX_TITLE_WORDS;
    }

    private static int wordCount(String text) {
        return text.strip().isEmpty() ? 0 : text.strip().split("\\s+").length;
    }

    /** A cover-page label, not a name: "Owner:", "Version:", "Date:" and their siblings. */
    private static boolean isMetadata(String text) {
        return METADATA_LABEL.matcher(text).find() || METADATA_LABEL_ONLY.matcher(text).matches();
    }

    /** A cover label with its value on the next line ("OWNER" / "Director, Commercial Legal"). */
    private static boolean isMetadataLabelOnly(String text) {
        return METADATA_LABEL_ONLY.matcher(text).matches();
    }

    private static boolean hasNumberingLabel(KnowledgeSourceNode node) {
        return node.numberingLabel() != null && !node.numberingLabel().isBlank();
    }

    /** A page header, not a name: a run of parts separated by a pipe. */
    private static boolean isPipeMasthead(String text) {
        return text.strip().contains("|");
    }

    private static boolean isLegend(String text) {
        return LEGEND.matcher(text).find();
    }

    private static boolean isStandaloneLegend(String text) {
        var normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return STANDALONE_LEGENDS.contains(normalized);
    }

    private static boolean isAllCaps(String text) {
        var stripped = text.strip();
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
