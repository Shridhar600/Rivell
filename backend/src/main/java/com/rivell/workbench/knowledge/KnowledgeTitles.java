package com.rivell.workbench.knowledge;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The title of an entry in review. A title is the heading the source gives, else its first
 * line, else its first sentence, and never a whole paragraph: a record whose only text is a
 * long passage would otherwise carry that passage as its name.
 */
final class KnowledgeTitles {

    static final int MAX_LENGTH = 120;

    /** A sentence boundary must leave at least this much text to be a useful title. */
    private static final int MIN_SENTENCE_LENGTH = 15;

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s)");
    private static final Pattern NUMBER_ONLY = Pattern.compile("[\\d\\s.,;:()\\-–—/]*");
    private static final Set<String> ABBREVIATIONS = Set.of(
            "e.g.", "i.e.", "etc.", "sec.", "no.", "nos.", "u.s.", "u.k.", "mr.", "mrs.",
            "ms.", "dr.", "ltd.", "inc.", "llc.", "plc.", "v.", "vs.", "al.", "fig.",
            "art.", "para.", "cl.", "p.", "pp.", "ch.");

    private KnowledgeTitles() {
    }

    /**
     * Blank text is returned unchanged so the caller's own blank check still applies. A
     * passage that carries only numbering or bare numbers names nothing, so it returns null.
     */
    static String bounded(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        var line = text.lines()
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .filter(part -> !NUMBER_ONLY.matcher(part).matches())
                .findFirst()
                .orElse(null);
        if (line == null) {
            return null;
        }
        if (line.length() <= MAX_LENGTH) {
            return line;
        }
        var sentenceEnd = sentenceEnd(line);
        if (sentenceEnd > 0) {
            return line.substring(0, sentenceEnd).stripTrailing();
        }
        var cut = line.lastIndexOf(' ', MAX_LENGTH - 1);
        if (cut < MIN_SENTENCE_LENGTH) {
            cut = MAX_LENGTH - 1;
        }
        return line.substring(0, cut).stripTrailing() + "…";
    }

    /**
     * The end index of the first real sentence boundary, or -1. A dot after a known
     * abbreviation is not a boundary, and a boundary too early to name anything is skipped.
     */
    private static int sentenceEnd(String line) {
        var matcher = SENTENCE_END.matcher(line);
        while (matcher.find()) {
            if (matcher.end() < MIN_SENTENCE_LENGTH) {
                continue;
            }
            if (ABBREVIATIONS.contains(lastToken(line.substring(0, matcher.end())))) {
                continue;
            }
            if (matcher.end() > MAX_LENGTH) {
                return -1;
            }
            return matcher.end();
        }
        return -1;
    }

    private static String lastToken(String prefix) {
        var trimmed = prefix.stripTrailing();
        var space = trimmed.lastIndexOf(' ');
        return (space < 0 ? trimmed : trimmed.substring(space + 1)).toLowerCase(java.util.Locale.ROOT);
    }
}
