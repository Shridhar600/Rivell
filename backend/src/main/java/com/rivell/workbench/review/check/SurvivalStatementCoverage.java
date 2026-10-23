package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ContractRegionSegmenter;

/**
 * DW-100: deterministic survival coverage written into the contract itself. A statement
 * covers a confidentiality clause when it uses a survival verb and either names that clause
 * by number, names confidentiality, or says that the obligations in this Agreement survive.
 * This is deliberately a small pattern set, not a model call: negated or conditional
 * survival ("shall not survive", "may survive"), "surviving" as in "surviving entity",
 * "survival" as a heading noun, and bare "the parties survive" cover nothing.
 */
final class SurvivalStatementCoverage {

    record Coverage(Set<UUID> coveredClauseIds, List<UUID> sourceClauseIds) {
    }

    private static final Pattern SURVIVAL_VERB = Pattern.compile(
            "\\bsurviv(?:e|es|ed)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_SURVIVAL = Pattern.compile(
            "\\b(?:not|never|no longer)\\s+(?:be\\s+)?(?:obligated\\s+to\\s+)?survive\\b"
                    + "|\\b(?:may|might|could|would)\\s+survive\\b"
                    + "|\\bsurvive\\s+only\\s+(?:if|to the extent)\\b"
                    + "|\\bonly\\s+survive\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_TRANSFER_EVENT = Pattern.compile(
            "\\bsurviv(?:e|es|ed)\\s+(?:the\\s+)?"
                    + "(?:assignment|merger|transfer|change of control|sale|consolidation)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TERMINATION_EVENT = Pattern.compile(
            "\\b(?:termination|expiry|expiration|end of (?:this|the) agreement"
                    + "|after (?:this|the) agreement ends)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFIDENTIALITY = Pattern.compile(
            "\\bconfidential(?:ity)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AGREEMENT_OBLIGATIONS = Pattern.compile(
            "\\bobligations?\\s+(?:in|under|of)\\s+this\\s+agreement\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SURVIVAL_SUBJECT = Pattern.compile(
            "\\b(?:obligations?|provisions?|terms|covenants?|clauses?|sections?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE = Pattern.compile(
            "\\b(?:sections?|clauses?|articles?)\\s+"
                    + "((?:\\d+(?:\\.\\d+)*|[IVXLCDM]+)(?:\\s*\\([^)]*\\))?"
                    + "(?:\\s*(?:,|and|or|to|through|-|–)\\s*"
                    + "(?:\\d+(?:\\.\\d+)*|[IVXLCDM]+)(?:\\s*\\([^)]*\\))?)*)"
                    + "(?![a-z0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE_ITEM = Pattern.compile(
            "\\s*(?:,|and|or)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE_RANGE = Pattern.compile(
            "\\s*(?:to|through|-|–)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)");
    private static final Pattern CLAUSE_NUMBER = Pattern.compile(
            "^\\s*(?:(?:clause|section|article)\\s+)?"
                    + "(\\d+(?:\\.\\d+)*|[IVXLCDM]+|[A-Z]-\\d+[A-Za-z]?)"
                    + "(?:\\s*[.):]\\s*|\\s+|$)",
            Pattern.CASE_INSENSITIVE);

    private SurvivalStatementCoverage() {
    }

    static Coverage coverage(List<ClauseSemantics> clauses, List<ClauseSemantics> candidates) {
        if (candidates.isEmpty()) {
            return new Coverage(Set.of(), List.of());
        }
        var numbers = candidateNumbers(clauses, candidates);
        var covered = new LinkedHashSet<UUID>();
        var sources = new LinkedHashSet<UUID>();
        for (var clause : clauses) {
            for (var statement : statements(clause.contentText())) {
                if (!isSurvivalStatement(statement)) {
                    continue;
                }
                var references = references(statement);
                var matched = new LinkedHashSet<UUID>();
                for (var candidate : candidates) {
                    var number = numbers.get(candidate.id());
                    if (number != null && references.stream()
                            .anyMatch(reference -> covers(reference, number))) {
                        matched.add(candidate.id());
                    }
                }
                if (matched.isEmpty() && namesConfidentiality(statement)) {
                    candidates.forEach(candidate -> matched.add(candidate.id()));
                }
                if (!matched.isEmpty()) {
                    covered.addAll(matched);
                    sources.add(clause.id());
                }
            }
        }
        return new Coverage(Set.copyOf(covered), List.copyOf(sources));
    }

    private static boolean isSurvivalStatement(String statement) {
        if (!SURVIVAL_VERB.matcher(statement).find() || NOT_SURVIVAL.matcher(statement).find()) {
            return false;
        }
        // "survive assignment" is a transfer rule, not a termination survival rule.
        return !OTHER_TRANSFER_EVENT.matcher(statement).find()
                || TERMINATION_EVENT.matcher(statement).find();
    }

    private static boolean namesConfidentiality(String statement) {
        return CONFIDENTIALITY.matcher(statement).find()
                || AGREEMENT_OBLIGATIONS.matcher(statement).find();
    }

    /**
     * The heading gives its body sentences context ("5. Survival of Confidentiality" over
     * "The obligations survive termination"), but only when the sentence has a survival-capable
     * subject. A bare "the parties survive" under a confidentiality heading still names nothing.
     */
    private static List<String> statements(String text) {
        var statements = new ArrayList<String>();
        if (text == null) {
            return statements;
        }
        var lines = text.split("\\R");
        var heading = lines.length > 1 ? lines[0].strip() : "";
        for (var index = lines.length > 1 ? 1 : 0; index < lines.length; index++) {
            for (var sentence : lines[index].split("(?<=[.;])\\s+")) {
                if (!sentence.isBlank()) {
                    var context = heading.isEmpty()
                            || !SURVIVAL_SUBJECT.matcher(sentence).find()
                                    ? "" : heading + " ";
                    statements.add(context + sentence.strip());
                }
            }
        }
        return statements;
    }

    private static List<Reference> references(String statement) {
        var references = new ArrayList<Reference>();
        var matcher = REFERENCE.matcher(statement);
        while (matcher.find()) {
            var group = PARENTHETICAL.matcher(matcher.group(1)).replaceAll(" ");
            for (var item : REFERENCE_ITEM.split(group)) {
                var range = REFERENCE_RANGE.split(item.strip());
                if (range.length == 2) {
                    var from = clauseNumber(range[0]);
                    var to = clauseNumber(range[1]);
                    if (from != null && to != null && from.compareTo(to) <= 0) {
                        references.add(new Reference(from, to));
                    }
                } else {
                    for (var token : range) {
                        var number = clauseNumber(token);
                        if (number != null) {
                            references.add(new Reference(number, null));
                        }
                    }
                }
            }
        }
        return references;
    }

    private static boolean covers(Reference reference, ClauseNumber candidate) {
        if (reference.to() == null) {
            return candidate.startsWith(reference.from());
        }
        if (reference.from().parts().size() == 1 && reference.to().parts().size() == 1) {
            return candidate.major() >= reference.from().major()
                    && candidate.major() <= reference.to().major();
        }
        return candidate.compareTo(reference.from()) >= 0
                && candidate.compareTo(reference.to()) <= 0;
    }

    /**
     * Clause numbers by clause id. When no clause heading carries a printed number, Word
     * auto-numbering is assumed and the clause's position among clause regions stands in
     * for it. A mixed document is left alone: a made-up ordinal must never collide with a
     * printed section number and hide a gap.
     */
    private static Map<UUID, ClauseNumber> candidateNumbers(
            List<ClauseSemantics> clauses, List<ClauseSemantics> candidates) {
        var candidateIds = candidates.stream().map(ClauseSemantics::id).toList();
        var clauseRegions = clauses.stream()
                .filter(clause -> clause.regionKind()
                        == ContractRegionSegmenter.SemanticRegionKind.CLAUSE)
                .toList();
        var anyPrintedNumber = clauseRegions.stream()
                .map(SurvivalStatementCoverage::clauseNumber)
                .anyMatch(number -> number != null);
        var numbers = new LinkedHashMap<UUID, ClauseNumber>();
        for (var index = 0; index < clauseRegions.size(); index++) {
            var clause = clauseRegions.get(index);
            var number = clauseNumber(clause);
            if (number == null && !anyPrintedNumber) {
                number = ClauseNumber.of(List.of(index + 1));
            }
            if (number != null && candidateIds.contains(clause.id())) {
                numbers.put(clause.id(), number);
            }
        }
        return numbers;
    }

    /** The leading clause label of a clause: "2. Confidentiality" is clause 2. */
    private static ClauseNumber clauseNumber(ClauseSemantics clause) {
        if (clause.contentText() == null) {
            return null;
        }
        var firstLine = clause.contentText().split("\\R", 2)[0];
        return clauseNumber(firstLine);
    }

    private static ClauseNumber clauseNumber(String label) {
        var matcher = CLAUSE_NUMBER.matcher(label.strip());
        if (!matcher.find()) {
            return null;
        }
        var token = matcher.group(1);
        if (token.matches("\\d+(?:\\.\\d+)*")) {
            var parts = new ArrayList<Integer>();
            for (var part : token.split("\\.")) {
                var value = integer(part);
                if (value == null) {
                    return null;
                }
                parts.add(value);
            }
            return ClauseNumber.of(parts);
        }
        if (token.matches("(?i)[A-Z]-\\d+[A-Za-z]?")) {
            var value = integer(token.replaceAll("\\D", ""));
            return value == null ? null : ClauseNumber.of(List.of(value));
        }
        var roman = roman(token);
        return roman == null ? null : ClauseNumber.of(List.of(roman));
    }

    private static Integer integer(String token) {
        if (token == null || token.isEmpty() || token.length() > 10) {
            return null;
        }
        try {
            var value = Long.parseLong(token);
            return value >= 0 && value <= Integer.MAX_VALUE ? (int) value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer roman(String token) {
        var upper = token.toUpperCase(Locale.ROOT);
        var total = 0;
        var previous = 0;
        for (var index = upper.length() - 1; index >= 0; index--) {
            var value = switch (upper.charAt(index)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> -1;
            };
            if (value < 0) {
                return null;
            }
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total > 0 ? total : null;
    }

    private record Reference(ClauseNumber from, ClauseNumber to) {
    }

    private record ClauseNumber(List<Integer> parts) implements Comparable<ClauseNumber> {

        static ClauseNumber of(List<Integer> parts) {
            return new ClauseNumber(List.copyOf(parts));
        }

        int major() {
            return parts.getFirst();
        }

        boolean startsWith(ClauseNumber prefix) {
            return parts.size() >= prefix.parts.size()
                    && parts.subList(0, prefix.parts.size()).equals(prefix.parts);
        }

        @Override
        public int compareTo(ClauseNumber other) {
            var size = Math.min(parts.size(), other.parts.size());
            for (var index = 0; index < size; index++) {
                var comparison = Integer.compare(parts.get(index), other.parts.get(index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(parts.size(), other.parts.size());
        }
    }
}
