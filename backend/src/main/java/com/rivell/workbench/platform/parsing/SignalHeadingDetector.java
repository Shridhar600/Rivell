package com.rivell.workbench.platform.parsing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.rivell.workbench.platform.parsing.HeadingDecision.Kind;

/**
 * Deterministic heading detection from layout signals.
 *
 * <p>Real contracts mark sections in many ways: Word heading styles, outline levels,
 * automatic list numbering, typed numbers ("7. Confidentiality", "ARTICLE I"), bold or
 * capitalised lines, and run-in headings ("5. Term. This Agreement…"). Strong signals
 * decide on their own. A bold or capitalised line with no number is only weak evidence
 * and is marked ambiguous. A numbered paragraph is a heading only when its text reads
 * like a title, so numbered body clauses ("1.1 The Receiving Party shall…") stay body.
 */
@Component
public final class SignalHeadingDetector implements HeadingDetector {

    private static final Pattern HEADING_STYLE =
            Pattern.compile("(?i)^heading\\s*([1-9])?$");
    private static final Pattern KEYWORD_LABEL = Pattern.compile(
            "(?i)^(article|section|clause|part|schedule|exhibit|annex|appendix)\\s+"
                    + "([0-9]{1,3}(?:\\.[0-9]{1,3}){0,4}|[ivxlc]{1,6}|[a-z])\\b[.:)]?\\s*[–—-]?\\s*");
    private static final Pattern NUMERIC_LABEL =
            Pattern.compile("^([0-9]{1,3}(?:\\.[0-9]{1,3}){0,4})(?:[.)]\\s*|\\s+)");
    private static final Pattern ROMAN_LABEL = Pattern.compile("^([IVXLC]{1,6})\\.\\s+");
    private static final Pattern HEADING_LIST_LABEL = Pattern.compile(
            "(?i)^(?:(?:article|section|clause|part)\\s+)?"
                    + "(?:[0-9]{1,3}(?:\\.[0-9]{1,3}){0,4}|[IVXLC]{1,6})[.)]?$");
    private static final Pattern MODAL = Pattern.compile(
            "(?i)\\b(shall|will|must|may|agrees?|means|includes?|is|are)\\b");
    private static final Pattern MONTH = Pattern.compile(
            "(?i)^(january|february|march|april|may|june|july|august|september|october"
                    + "|november|december)\\b");
    private static final Set<String> SIGNATURE_LABELS = Set.of(
            "by", "name", "title", "date", "signature", "its", "address", "email",
            "attention", "attn", "signed", "witness");

    private static final int MAX_TITLE_WORDS = 12;
    private static final int MAX_RUN_IN_WORDS = 10;
    private static final int MIN_BODY_WORDS = 12;

    @Override
    public List<HeadingDecision> classify(List<ParagraphFeatures> paragraphs) {
        var decisions = new ArrayList<HeadingDecision>(paragraphs.size());
        var titleSeen = false;
        var headingSeen = false;
        for (int i = 0; i < paragraphs.size(); i++) {
            var paragraph = paragraphs.get(i);
            var decision = decide(paragraph, nextNonBlank(paragraphs, i), !titleSeen && !headingSeen);
            titleSeen |= decision.kind() == Kind.TITLE;
            headingSeen |= decision.kind() == Kind.HEADING;
            decisions.add(decision);
        }
        return decisions;
    }

    private HeadingDecision decide(
            ParagraphFeatures p, ParagraphFeatures next, boolean beforeAnyStructure) {
        if (p.isBlank() || isTableOfContents(p)) {
            return HeadingDecision.body();
        }
        var text = p.text().strip();

        if ("Title".equals(p.styleId()) || "title".equalsIgnoreCase(p.styleName())) {
            return strong(Kind.TITLE, null, "STYLE_TITLE");
        }
        var styleLevel = headingStyleLevel(p);
        if (styleLevel != null) {
            return strong(Kind.HEADING, styleLevel, "STYLE_HEADING");
        }
        if (p.outlineLevel() != null && p.outlineLevel() >= 0 && p.outlineLevel() <= 8) {
            return strong(Kind.HEADING, p.outlineLevel() + 1, "OUTLINE_LEVEL");
        }

        var typed = typedLabel(text);
        var listLabel = p.numberingLabel();
        var listLabelIsHeading = listLabel != null && HEADING_LIST_LABEL.matcher(listLabel).matches();

        if (p.runInTitle() != null && isTitle(stripTrailing(p.runInTitle()), MAX_RUN_IN_WORDS)) {
            var level = listLabelIsHeading ? depth(listLabel) : typed != null ? typed.depth() : 1;
            return strong(Kind.HEADING, level, "RUN_IN");
        }
        if (listLabel != null) {
            if (listLabelIsHeading && isTitle(text, MAX_TITLE_WORDS)) {
                return strong(Kind.HEADING, depth(listLabel), "LIST_LABEL");
            }
            return HeadingDecision.body();
        }
        if (typed != null) {
            if (typed.remainder().isEmpty() ? typed.keyword() : isTitle(typed.remainder(), MAX_TITLE_WORDS)) {
                return strong(Kind.HEADING, typed.depth(), "TYPED_LABEL");
            }
            return HeadingDecision.body();
        }

        if ((p.allEmphasised() || p.allCaps()) && isTitle(text, MAX_TITLE_WORDS)) {
            if (beforeAnyStructure && (p.centred() || p.allCaps())) {
                return new HeadingDecision(Kind.TITLE, null, Set.of("LEADING_EMPHASIS"), true);
            }
            if (p.followedByTable() || next != null && isBodyLike(next)) {
                return new HeadingDecision(Kind.HEADING, 1, Set.of("EMPHASIS"), true);
            }
        }
        return HeadingDecision.body();
    }

    private static HeadingDecision strong(Kind kind, Integer level, String signal) {
        return new HeadingDecision(kind, level, Set.of(signal), false);
    }

    private static Integer headingStyleLevel(ParagraphFeatures p) {
        for (var candidate : new String[] {p.styleName(), p.styleId()}) {
            if (candidate == null) {
                continue;
            }
            Matcher m = HEADING_STYLE.matcher(candidate.strip());
            if (m.matches()) {
                return m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
            }
        }
        return null;
    }

    private static boolean isTableOfContents(ParagraphFeatures p) {
        var name = p.styleName() != null ? p.styleName() : p.styleId();
        return name != null && name.toLowerCase(Locale.ROOT).startsWith("toc");
    }

    /** Typed label at the start of the text, with what follows it. */
    private record TypedLabel(boolean keyword, int depth, String remainder) {
    }

    private static TypedLabel typedLabel(String text) {
        var keyword = KEYWORD_LABEL.matcher(text);
        if (keyword.find()) {
            var word = keyword.group(1).toLowerCase(Locale.ROOT);
            var number = keyword.group(2);
            var depth = word.equals("section") || word.equals("clause") ? depth(number) : 1;
            return new TypedLabel(true, depth, text.substring(keyword.end()).strip());
        }
        var numeric = NUMERIC_LABEL.matcher(text);
        if (numeric.find()) {
            var remainder = text.substring(numeric.end()).strip();
            if (MONTH.matcher(remainder).find()) {
                return null; // "1 October 2026" is a date, not a section
            }
            return new TypedLabel(false, depth(numeric.group(1)), remainder);
        }
        var roman = ROMAN_LABEL.matcher(text);
        if (roman.find()) {
            return new TypedLabel(false, 1, text.substring(roman.end()).strip());
        }
        return null;
    }

    private static int depth(String label) {
        var digits = label.replaceAll("[^0-9.]", "").replaceAll("^\\.+|\\.+$", "");
        return digits.isEmpty() ? 1 : digits.split("\\.").length;
    }

    /** Reads like a section title rather than a sentence. */
    static boolean isTitle(String text, int maxWords) {
        var s = text.strip();
        if (s.isEmpty() || s.length() > 120) {
            return false;
        }
        var first = s.codePointAt(0);
        if (Character.isLowerCase(first)) {
            return false;
        }
        var words = s.split("\\s+");
        if (words.length > maxWords) {
            return false;
        }
        var last = s.charAt(s.length() - 1);
        if (last == ',' || last == ';') {
            return false;
        }
        if (last == ':' && (words.length > 6 || SIGNATURE_LABELS.contains(
                words[0].replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT)))) {
            return false;
        }
        if (last == '.' && (words.length > 8 || s.substring(0, s.length() - 1).contains(". "))) {
            return false;
        }
        return !MODAL.matcher(s).find();
    }

    private static boolean isBodyLike(ParagraphFeatures next) {
        var words = next.text().strip().split("\\s+").length;
        return words >= MIN_BODY_WORDS || next.numberingLabel() != null
                || typedLabel(next.text().strip()) != null;
    }

    private static String stripTrailing(String title) {
        return title.strip().replaceAll("[.:–—-]+$", "").strip();
    }

    private static ParagraphFeatures nextNonBlank(List<ParagraphFeatures> paragraphs, int index) {
        for (int j = index + 1; j < paragraphs.size(); j++) {
            if (!paragraphs.get(j).isBlank()) {
                return paragraphs.get(j);
            }
        }
        return null;
    }
}
