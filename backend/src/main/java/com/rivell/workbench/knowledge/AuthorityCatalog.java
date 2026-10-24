package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The document-level authority rules of one source, merged with an entry's own rules by the
 * precedence council §A.1 fixes: the most specific typed rule wins for one stance, a rule that
 * names the stance being more specific than one that names none. Lower-level rules that name a
 * different approver stay visible as conflicts (§A.2).
 */
final class AuthorityCatalog {

    /** Every stance a rule can name, in the order the compile reads them. */
    private static final List<PositionRole> ALL_STANCES = List.of(
            PositionRole.PREFERRED, PositionRole.FALLBACK, PositionRole.PROHIBITED);

    private static final Set<String> SCOPE_STOPWORDS = Set.of(
            "the", "and", "or", "any", "all", "for", "with", "that", "this", "from", "into",
            "under", "not", "its", "their", "our", "your", "per", "a", "an", "of", "to", "in",
            "on", "by", "as", "is", "be", "at");

    private final List<AuthoritySourceRule> documentRules;

    private AuthorityCatalog(List<AuthoritySourceRule> documentRules) {
        this.documentRules = documentRules;
    }

    record Effective(List<AuthorityRule> rules, List<AuthorityConflict> conflicts) {

        static Effective empty() {
            return new Effective(List.of(), List.of());
        }

        AuthorityRule rule(PositionRole stance) {
            return rules.stream()
                    .filter(rule -> rule.stances().contains(stance))
                    .findFirst()
                    .orElse(null);
        }
    }

    static AuthorityCatalog of(List<AuthoritySourceRule> rules) {
        var unique = new LinkedHashMap<String, AuthoritySourceRule>();
        if (rules != null) {
            for (var rule : rules) {
                unique.putIfAbsent(key(rule), rule);
            }
        }
        return new AuthorityCatalog(List.copyOf(unique.values()));
    }

    Effective merge(String entryTitle, String entryText, List<AuthorityRule> entryRules) {
        var applicable = new ArrayList<AuthorityRule>();
        if (entryRules != null) {
            applicable.addAll(entryRules);
        }
        for (var source : documentRules) {
            if (appliesTo(source, entryTitle, entryText)) {
                applicable.add(source.rule());
            }
        }
        var effective = new ArrayList<AuthorityRule>();
        var conflicts = new ArrayList<AuthorityConflict>();
        for (var stance : ALL_STANCES) {
            var candidates = applicable.stream()
                    .filter(rule -> covers(rule, stance))
                    .sorted((left, right) -> Integer.compare(
                            rank(right, stance), rank(left, stance)))
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            var winner = candidates.getFirst();
            effective.add(winner.forStance(stance));
            // every rule with its own condition survives (council §E); a different approver
            // is also a visible conflict, and both stay readable
            var seen = new LinkedHashSet<String>();
            seen.add(ruleKey(winner));
            for (var candidate : candidates) {
                if (seen.add(ruleKey(candidate))) {
                    effective.add(candidate.forStance(stance));
                }
            }
            candidates.stream()
                    .filter(rule -> !sameApprover(rule.approver(), winner.approver()))
                    .findFirst()
                    .ifPresent(conflict -> conflicts.add(new AuthorityConflict(
                            conflict.approver(), conflict.source(), stance)));
        }
        return new Effective(List.copyOf(effective), List.copyOf(conflicts));
    }

    private static String ruleKey(AuthorityRule rule) {
        return RecordLabelRoles.normalize(rule.approver()) + "|" + conditionKey(rule);
    }

    private static String conditionKey(AuthorityRule rule) {
        return rule.condition() == null ? "" : rule.condition().toLowerCase(Locale.ROOT);
    }

    /**
     * Council §A.1: an approver stated on the entry beats the document approval matrix, which
     * beats an approval table row. Within a level, a rule that names the stance is more specific
     * than one that does not, and a rule with its own condition is more specific still.
     */
    private static int rank(AuthorityRule rule, PositionRole stance) {
        var named = rule.stances().contains(stance);
        var level = switch (rule.source()) {
            case ENTRY -> named ? 6 : 5;
            case MATRIX -> named ? 4 : 3;
            case TABLE -> named ? 2 : 1;
        };
        return level * 2 + (rule.condition() != null ? 1 : 0);
    }

    /**
     * An empty stance set governs the fallback and the walk-away, never the preferred position.
     * A fallback approver can also approve anything beyond the fallback, so a fallback rule
     * covers the walk-away; a walk-away rule never forces approval on the fallback.
     */
    private static boolean covers(AuthorityRule rule, PositionRole stance) {
        if (stance == PositionRole.PREFERRED) {
            return rule.stances().contains(PositionRole.PREFERRED);
        }
        if (rule.stances().isEmpty()) {
            return true;
        }
        if (rule.stances().contains(stance)) {
            return true;
        }
        return stance == PositionRole.PROHIBITED
                && rule.stances().contains(PositionRole.FALLBACK);
    }

    private static boolean sameApprover(String left, String right) {
        return RecordLabelRoles.normalize(left).equals(RecordLabelRoles.normalize(right));
    }

    /**
     * A document rule applies to the entry its own words name; when it names no entry but names
     * a stance, it applies to every entry. A rule that names neither is not applied: no match
     * means no approval, never a guessed one.
     */
    private static boolean appliesTo(AuthoritySourceRule source, String entryTitle, String entryText) {
        var scope = source.scope();
        if (scope == null) {
            return true;
        }
        var titleWords = words(entryTitle);
        for (var word : words(scope)) {
            for (var titleWord : titleWords) {
                if (matches(word, titleWord)) {
                    return true;
                }
            }
        }
        return namesStance(scope);
    }

    private static boolean namesStance(String text) {
        var normalized = RecordLabelRoles.normalize(text);
        return normalized.contains("fallback") || normalized.contains("walk away")
                || normalized.contains("walkaway") || normalized.contains("red line")
                || normalized.contains("prohibit") || normalized.contains("never accept")
                || normalized.contains("deviation");
    }

    private static Set<String> words(String text) {
        var words = new LinkedHashSet<String>();
        var normalized = RecordLabelRoles.normalize(text);
        if (normalized.isEmpty()) {
            return words;
        }
        for (var word : normalized.split(" ")) {
            if (word.length() >= 3 && !SCOPE_STOPWORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static boolean matches(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        return left.length() >= 6 && right.length() >= 6
                && left.substring(0, 6).equals(right.substring(0, 6));
    }

    private static String key(AuthoritySourceRule source) {
        var rule = source.rule();
        return String.join("|", rule.approver().toLowerCase(Locale.ROOT),
                rule.stances().toString(), String.valueOf(rule.condition()),
                rule.source().name(), String.valueOf(source.scope()));
    }
}
