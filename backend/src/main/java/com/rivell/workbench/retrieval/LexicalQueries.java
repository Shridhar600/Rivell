package com.rivell.workbench.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexical query representation for the FTS lane: the OR-joined term form of the
 * reviewed clause text (legal terminology is first-class, AD #8.2).
 */
public final class LexicalQueries {

    private LexicalQueries() {
    }

    public static String orTerms(String clauseText) {
        var terms = new ArrayList<String>();
        for (var token : clauseText.split("\\s+")) {
            var cleaned = token.replaceAll("[^A-Za-z0-9]", "");
            if (!cleaned.isEmpty()) {
                terms.add(cleaned);
            }
        }
        return String.join(" OR ", terms);
    }
}
