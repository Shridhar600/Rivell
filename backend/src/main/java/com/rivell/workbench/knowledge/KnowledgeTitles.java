package com.rivell.workbench.knowledge;

import java.util.regex.Pattern;

/**
 * The title of an entry in review. A title is the heading the source gives, else its first
 * line, else its first sentence, and never a whole paragraph: a record whose only text is a
 * long passage would otherwise carry that passage as its name.
 */
final class KnowledgeTitles {

    static final int MAX_LENGTH = 120;

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s)");

    private KnowledgeTitles() {
    }

    /** Blank text is returned unchanged so the caller's own blank check still applies. */
    static String bounded(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        var line = text.lines().map(String::strip).filter(part -> !part.isEmpty()).findFirst().orElseThrow();
        if (line.length() <= MAX_LENGTH) {
            return line;
        }
        var sentence = SENTENCE_END.matcher(line);
        if (sentence.find() && sentence.end() <= MAX_LENGTH) {
            return line.substring(0, sentence.end());
        }
        var cut = line.lastIndexOf(' ', MAX_LENGTH - 1);
        return line.substring(0, cut > 0 ? cut : MAX_LENGTH - 1).stripTrailing() + "…";
    }
}
