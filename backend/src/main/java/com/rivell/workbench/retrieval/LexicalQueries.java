package com.rivell.workbench.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexical query representation for the FTS lane: the OR-joined term form of the
 * reviewed clause text (legal terminology is first-class, AD #8.2).
 */
public final class LexicalQueries {

    private static final int MAX_TERMS = 32;

    private LexicalQueries() {
    }

    /** OR-joined term form: deduped, single characters dropped, bounded term count. */
    public static String orTerms(String clauseText) {
        var terms = new java.util.LinkedHashSet<String>();
        for (var token : clauseText.split("\\s+")) {
            var cleaned = token.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
            if (cleaned.length() > 1) {
                terms.add(cleaned);
            }
            if (terms.size() >= MAX_TERMS) {
                break;
            }
        }
        return String.join(" OR ", terms);
    }
}
