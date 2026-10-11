package com.rivell.workbench.review.check;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sentence-level difference between a clause and a proposed rewrite: the sentences of the
 * original the rewrite no longer contains word for word, and the sentences that are new.
 * Sub-clause labels ("A.", "(b)", "3.1") and punctuation do not count as changes.
 */
record ClauseTextDiff(List<String> removed, List<String> added) {

    private static final Pattern SENTENCE_END =
            Pattern.compile("(?<=[.;])\\s+(?=[A-Z(\\u201C\"'])");
    private static final Pattern LABEL =
            Pattern.compile("^\\s*(?:\\(?[0-9]+(?:\\.[0-9]+)*\\)?|\\(?[A-Za-z]\\)|[A-Z]\\.|[0-9]+\\.[A-Z]\\.)\\s+");
    private static final Pattern WORD = Pattern.compile("[A-Za-z]+");
    private static final int MIN_WORDS = 3;

    static ClauseTextDiff of(String original, String rewrite) {
        var before = sentences(original);
        var after = sentences(rewrite);
        var beforeKeys = keys(before);
        var afterKeys = keys(after);
        return new ClauseTextDiff(
                before.stream().filter(sentence -> !afterKeys.contains(key(sentence))).toList(),
                after.stream().filter(sentence -> !beforeKeys.contains(key(sentence))).toList());
    }

    static List<String> sentences(String text) {
        var sentences = new ArrayList<String>();
        if (text == null) {
            return sentences;
        }
        for (var line : text.split("\\n+")) {
            for (var sentence : SENTENCE_END.split(line)) {
                var stripped = LABEL.matcher(sentence.strip()).replaceFirst("");
                if (WORD.matcher(stripped).results().count() >= MIN_WORDS) {
                    sentences.add(stripped);
                }
            }
        }
        return sentences;
    }

    /** Lower-case words and numbers only, so quotes, spacing and punctuation do not count. */
    static String key(String sentence) {
        var words = new ArrayList<String>();
        var matcher = Pattern.compile("[a-z0-9]+").matcher(
                Normalizer.normalize(sentence, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return String.join(" ", words);
    }

    private static LinkedHashSet<String> keys(List<String> sentences) {
        var keys = new LinkedHashSet<String>();
        sentences.forEach(sentence -> keys.add(key(sentence)));
        return keys;
    }
}
