package com.rivell.workbench.review.check;

import java.util.List;
import java.util.UUID;

import com.rivell.workbench.retrieval.EvidenceBundle;

/**
 * The contract's own definitions of terms the clause uses (P3), as the engine v2 judges read
 * them: the bundle's current-contract items with reason {@code DEFINED_TERM}, in bundle order.
 * The text is cut for the state; the evidence ids cite the definitions on findings.
 */
final class ClauseDefinitions {

    static final int MAX_DEFINITIONS = 8;
    static final int MAX_TEXT = 1000;
    private static final String DEFINED_TERM = "DEFINED_TERM";
    private static final String MEANS = " means ";

    private final List<String> texts;
    private final List<String> terms;
    private final List<UUID> evidenceIds;

    private ClauseDefinitions(List<String> texts, List<String> terms, List<UUID> evidenceIds) {
        this.texts = texts;
        this.terms = terms;
        this.evidenceIds = evidenceIds;
    }

    static ClauseDefinitions empty() {
        return new ClauseDefinitions(List.of(), List.of(), List.of());
    }

    static ClauseDefinitions of(EvidenceBundle bundle) {
        var items = bundle.currentContractItems().stream()
                .filter(item -> DEFINED_TERM.equals(item.retrievalReason()))
                .limit(MAX_DEFINITIONS)
                .toList();
        return new ClauseDefinitions(
                items.stream().map(item -> truncate(item.text())).toList(),
                items.stream().map(item -> term(item.text())).toList(),
                items.stream().map(item -> item.evidenceId()).toList());
    }

    /** The definition texts for the judging state, cut to {@link #MAX_TEXT} characters. */
    List<String> texts() {
        return texts;
    }

    /** The terms for the check detail: the text before the first " means ". */
    List<String> terms() {
        return terms;
    }

    /** The definition evidence ids, for citing them on findings. */
    List<UUID> evidenceIds() {
        return evidenceIds;
    }

    boolean isEmpty() {
        return texts.isEmpty();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    private static String term(String text) {
        var index = text.indexOf(MEANS);
        return index < 0 ? text : text.substring(0, index);
    }
}
