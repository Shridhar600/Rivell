package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.ProviderFailures;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceService;
import com.rivell.workbench.review.IssueSpottingSpecs;

/**
 * Runs the whole-contract spotting call once per analysis run (the first clause check that asks
 * runs it, the others wait) and answers which clauses it named. A failed call spots nothing:
 * spotting only adds recall to the position checks, it never replaces them.
 *
 * <p>The read's output is an untrusted list. Every issue it names is kept only when its own quote
 * is found in the contract: the quote decides the clause, never the number the read printed. A
 * line that holds several issues is not one finding; it is not used at all.
 */
final class ContractIssueSpotter {

    /** One issue the read named, with the clause quote it is grounded in. */
    record Spot(String severity, String reason, String quote, UUID executionId) {
    }

    /**
     * {@code unavailable}: the spotting call failed (or was interrupted), so the whole-contract
     * recall lane is missing and the check says so. A successful call that named nothing is a
     * real empty result. {@code dropped}: issue lines the read produced whose quote could not be
     * found in the contract, or that were not one issue in one line.
     */
    record Spots(Map<UUID, List<Spot>> spots, boolean unavailable, int dropped) {

        static Spots empty() {
            return new Spots(Map.of(), false, 0);
        }

        static Spots failed() {
            return new Spots(Map.of(), true, 0);
        }
    }

    /** The validated output of one read, keyed by the clause number the issue is attached to. */
    record Parsed(Map<Integer, List<Spot>> spots, int dropped) {
    }

    private static final int MAX_RUNS = 16;
    private static final int MAX_POSITIONS = 120;
    private static final int MAX_CLAUSE_TEXT = 3000;
    private static final Pattern ISSUE_LINE = Pattern.compile(
            "^\\s*(\\d+)\\s*\\|\\s*(HIGH|MEDIUM)\\s*\\|\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_SEPARATOR = Pattern.compile("\\|");
    private static final Pattern SEVERITY_TOKEN = Pattern.compile(
            "\\|\\s*(?:HIGH|MEDIUM)\\s*\\|", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISSUE_SHAPED = Pattern.compile(
            "(?i)^\\s*(?:(?:article|section|clause|part)\\s+)?"
                    + "\\(?\\d{1,3}(?:\\.\\d{1,3})*\\)?[.):]?\\s*\\|");
    /**
     * A clause label the read may repeat before its quote: "7.2" when the clause is 7, "7.",
     * "7)", "(a)", "a.", "i.", or a keyword label such as "Section 7". A bare number is
     * deliberately not a label, and a dotted number only counts when its integer part is the
     * clause's own number: "12.5 percent" in clause 3 is a quantity, not clause numbering.
     */
    private static final Pattern LEADING_LABEL = Pattern.compile(
            "^(?:(article|section|clause|part)\\s+"
                    + "(\\(?\\d{1,3}(?:\\.\\d{1,3})*\\)?[.):]?)\\s+"
                    + "|(\\([a-z]\\)|\\([ivxlc]{1,4}\\)|\\(\\d{1,3}\\)|"
                    + "\\d{1,3}(?:\\.\\d{1,3})+[.):]?|\\d{1,3}[.)]|"
                    + "[a-z][.)]|[ivxlc]{1,6}[.)])\\s+)");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:]+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LIST_LABEL = Pattern.compile("[a-z][.)]|[ivxlc]{1,6}[.)]");
    private static final Pattern LEADING_DIGITS = Pattern.compile("^([0-9]+).*$");

    private final GenerativeReasoningService generation;
    private final EvidenceService evidenceService;
    private final RunMemo<Spots> runs = new RunMemo<>(MAX_RUNS);

    ContractIssueSpotter(GenerativeReasoningService generation, EvidenceService evidenceService) {
        this.generation = generation;
        this.evidenceService = evidenceService;
    }

    Spots spots(ReviewCheckContext context, EvidenceRequest anyClauseRequest) {
        try {
            return runs.get(context.runId(), () -> spot(context, anyClauseRequest));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Spots.failed();
        } catch (ExecutionException e) {
            ProviderFailures.rethrowIfOutOfCredit(e.getCause());
            return Spots.failed();
        }
    }

    private Spots spot(ReviewCheckContext context, EvidenceRequest anyClauseRequest) {
        var clauses = new ArrayList<IssueSpottingSpecs.SpottingClause>();
        var byNumber = new LinkedHashMap<Integer, ClauseSemantics>();
        for (var clause : context.semantics().clauses()) {
            var number = clauses.size() + 1;
            var text = clause.contentText() == null ? "" : clause.contentText();
            clauses.add(new IssueSpottingSpecs.SpottingClause(number,
                    context.headingTexts().getOrDefault(clause.headingNodeId(), ""),
                    text.length() > MAX_CLAUSE_TEXT ? text.substring(0, MAX_CLAUSE_TEXT) : text));
            byNumber.put(number, clause);
        }
        var snapshot = context.snapshot();
        var state = new IssueSpottingSpecs.SpottingState(IssueSpottingSpecs.STATE_SCHEMA,
                snapshot.contractType().label(),
                snapshot.ourSideRole() == null ? "" : snapshot.ourSideRole().name(),
                evidenceService.applicablePositionTexts(anyClauseRequest, MAX_POSITIONS), clauses);
        try {
            var execution = generation.generateLong(IssueSpottingSpecs.spec(), state);
            var parsed = parse(execution.fields().get(IssueSpottingSpecs.ISSUES), clauses,
                    execution.executionId());
            var byHeading = new LinkedHashMap<UUID, List<Spot>>();
            parsed.spots().forEach((number, spots) -> {
                var clause = byNumber.get(number);
                if (clause != null) {
                    byHeading.put(clause.headingNodeId(), spots);
                }
            });
            return new Spots(Map.copyOf(byHeading), false, parsed.dropped());
        } catch (SemanticDecisionException e) {
            ProviderFailures.rethrowIfOutOfCredit(e);
            return Spots.failed();
        }
    }

    /**
     * Reads the read's lines into issues. A line is {@code number | HIGH or MEDIUM | quote |
     * reason}; the quote is matched against each clause's heading and text, and the issue is
     * attached to the clause that contains it. The printed number is only a tie-break: the quote
     * decides, so a misnumbered issue still reaches the clause it is about.
     */
    static Parsed parse(
            String output, List<IssueSpottingSpecs.SpottingClause> clauses, UUID executionId) {
        var spots = new LinkedHashMap<Integer, List<Spot>>();
        var dropped = 0;
        if (output == null || output.isBlank()) {
            return new Parsed(Map.of(), 0);
        }
        for (var line : output.split("\\R")) {
            var trimmed = line.trim();
            if (trimmed.isEmpty() || IssueSpottingSpecs.NONE.equalsIgnoreCase(trimmed)) {
                continue;
            }
            var matcher = ISSUE_LINE.matcher(trimmed);
            if (!matcher.matches()) {
                if (ISSUE_SHAPED.matcher(trimmed).find() || SEVERITY_TOKEN.matcher(trimmed).find()) {
                    // It names an issue but has no readable quote/reason. Count it: a model that
                    // returns the wrong shape must not look like a clean read.
                    dropped += Math.max(1, severityTokens(trimmed));
                }
                // Prose before or after the issue list is not an issue.
                continue;
            }
            int number;
            try {
                number = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                // A number the read printed that no clause check can hold is a malformed line,
                // not a reason to fail the clause check; drop it and count it like any other.
                dropped += Math.max(1, severityTokens(trimmed));
                continue;
            }
            var severity = matcher.group(2).toUpperCase(Locale.ROOT);
            var fields = FIELD_SEPARATOR.split(matcher.group(3), -1);
            if (fields.length != 2) {
                // Either a quote or a reason is missing, or the line holds several issues. One
                // finding must not carry several issues, so none of them is used. Count every
                // embedded issue so the check detail does not hide a lost read behind one line.
                dropped += Math.max(1, severityTokens(trimmed));
                continue;
            }
            var quote = unquote(fields[0].trim());
            var reason = fields[1].trim();
            if (quote.isEmpty() || reason.isEmpty()
                    || quote.chars().noneMatch(Character::isLetter)) {
                dropped++;
                continue;
            }
            var clause = clauseContaining(clauses, number, quote);
            if (clause == null) {
                dropped++;
                continue;
            }
            var found = spots.computeIfAbsent(clause.number(), ignored -> new ArrayList<>());
            var duplicate = found.stream().anyMatch(spot ->
                    normalizeForMatch(spot.quote()).equals(normalizeForMatch(quote))
                            && spot.reason().equalsIgnoreCase(reason));
            if (!duplicate) {
                found.add(new Spot(severity, reason, quote, executionId));
            }
        }
        var copy = new LinkedHashMap<Integer, List<Spot>>();
        spots.forEach((number, list) -> copy.put(number, List.copyOf(list)));
        return new Parsed(Map.copyOf(copy), dropped);
    }

    /** How many clauses a malformed line names, by its embedded "| HIGH |" / "| MEDIUM |" fields. */
    private static int severityTokens(String line) {
        var matcher = SEVERITY_TOKEN.matcher(line);
        var count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * The clause whose heading or text contains the quote. The read's own number wins when that
     * clause contains it, so a repeated quote stays where the read pointed; otherwise the first
     * clause that contains it is the one the issue is about.
     */
    private static IssueSpottingSpecs.SpottingClause clauseContaining(
            List<IssueSpottingSpecs.SpottingClause> clauses, int named, String quote) {
        var namedClause = clauses.stream().filter(clause -> clause.number() == named)
                .findFirst().orElse(null);
        if (namedClause != null && containsQuote(namedClause, quote)) {
            return namedClause;
        }
        return clauses.stream().filter(clause -> containsQuote(clause, quote))
                .findFirst().orElse(null);
    }

    private static boolean containsQuote(IssueSpottingSpecs.SpottingClause clause, String quote) {
        var needle = normalizeForMatch(quote);
        if (!hasWord(needle)) {
            return false;
        }
        var haystack = normalizeForMatch(clause.heading() + " " + clause.text());
        var unlabelledHaystack = withoutLeadingLabel(haystack, clause.number());
        // A quote may repeat the clause number ("7.2 The Supplier...") that the model saw, while
        // the clause text starts after the heading; a model may also add the sentence's final
        // period. Try both layers together, not only one at a time.
        var unlabelledQuote = withoutLeadingLabel(needle, clause.number());
        for (var candidate : List.of(needle, unlabelledQuote)) {
            if (haystack.contains(candidate) || unlabelledHaystack.contains(candidate)) {
                return true;
            }
            var withoutFinalPunctuation = TRAILING_PUNCTUATION.matcher(candidate).replaceAll("");
            if (hasWord(withoutFinalPunctuation)
                    && (haystack.contains(withoutFinalPunctuation)
                            || unlabelledHaystack.contains(withoutFinalPunctuation))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWord(String text) {
        return text != null && text.chars().anyMatch(Character::isLetter);
    }

    /** Quotes, spaces and dashes as a reader types them, never as a model renders them. */
    static String normalizeForMatch(String value) {
        if (value == null) {
            return "";
        }
        var normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '\u00A0', '\u2007', '\u202F', '\u200B', '\uFEFF' -> normalized.append(' ');
                case '\u2018', '\u2019', '\u201A', '\u201B', '\u2032', '`' ->
                        normalized.append('\'');
                case '\u201C', '\u201D', '\u201E', '\u201F', '\u2033' -> normalized.append('"');
                case '\u2013', '\u2014', '\u2015', '\u2212', '\u2010', '\u2011' ->
                        normalized.append('-');
                case '\u2026' -> normalized.append("...");
                case '\u00AD' -> {
                    // A soft hyphen is a line-break hint, not part of the words.
                }
                default -> normalized.append(character);
            }
        }
        return WHITESPACE.matcher(normalized.toString()).replaceAll(" ").trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Strips a label the read repeated before its quote. A numeric label is stripped only when
     * its integer part is the clause's own number, so a quantity ("12.5 percent") is never
     * mistaken for numbering; keyword and list labels are unambiguous.
     */
    private static String withoutLeadingLabel(String text, int clauseNumber) {
        var matcher = LEADING_LABEL.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        if (matcher.group(1) != null) {
            return text.substring(matcher.end());
        }
        var label = matcher.group(3);
        if (label.startsWith("(") || LIST_LABEL.matcher(label).matches()) {
            return text.substring(matcher.end());
        }
        var digits = LEADING_DIGITS.matcher(label).replaceAll("$1");
        if (!digits.equals(label) && Integer.parseInt(digits) == clauseNumber) {
            return text.substring(matcher.end());
        }
        return text;
    }

    private static String unquote(String value) {
        var text = value;
        while (text.length() >= 2) {
            var first = text.charAt(0);
            var last = text.charAt(text.length() - 1);
            var paired = (first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '\u201C' && last == '\u201D')
                    || (first == '\u2018' && last == '\u2019');
            if (!paired) {
                break;
            }
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }
}
