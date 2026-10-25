package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic parse of the labels a knowledge source states explicitly into typed per-stance
 * text and typed authority rules. This stage runs before any bounded decision: Jev answers only
 * what the text does not state. Labels are recognised at the start of a line or a table cell,
 * case-insensitively; an unknown label that still states a walk-away or an approver is reported
 * as unmapped rather than silently dropped (council §A.4).
 *
 * <p>The stage never guesses. A label it does not know produces no stance; a stated label with
 * no value is unmapped; a sentence creates an authority rule only when it names an approver, so a
 * trigger text alone can never make an approval (council §A.3).
 */
final class LabelledFieldStage {

    private static final int MAX_LABEL_LENGTH = 44;
    private static final int MAX_QUOTE_LENGTH = 400;
    private static final int MAX_APPROVER_LENGTH = 90;

    private static final Set<String> ROLE_WORDS = Set.of(
            "counsel", "director", "manager", "officer", "lead", "legal", "finance",
            "privacy", "security", "procurement", "commercial", "engineering", "marketing",
            "lawyer", "approver", "board", "committee", "sponsor", "owner", "head of", "gc",
            "general counsel", "cfo", "ceo", "coo");

    private static final Set<String> NONE_VALUES = Set.of(
            "none", "no", "n a", "not applicable", "not required", "no approval",
            "no approval required", "not acceptable", "not acceptable at any level",
            "no one", "nobody", "-");

    /**
     * A word that points at an approver without naming one. "Escalate to the named approver"
     * states a route, not an authority rule (council §A.3).
     */
    private static final Set<String> GENERIC_APPROVERS = Set.of(
            "approver", "named approver", "the named approver", "a named approver",
            "an approver", "the approver", "appropriate person", "appropriate approver",
            "authorised approver", "authorized approver");

    private static final Set<String> BOUNDARY_WORDS = Set.of(
            "before", "if", "when", "where", "unless", "on", "for", "after", "once");

    private enum Field {
        PREFERRED, FALLBACK, PROHIBITED, ESCALATION, APPROVER
    }

    private static final Map<String, Field> LABELS = new LinkedHashMap<>();

    static {
        label("preferred", Field.PREFERRED);
        label("preferred position", Field.PREFERRED);
        label("preferred position 1", Field.PREFERRED);
        label("our position", Field.PREFERRED);
        label("our stance", Field.PREFERRED);
        label("standard", Field.PREFERRED);
        label("standard position", Field.PREFERRED);
        label("first choice", Field.PREFERRED);
        label("preferred stance", Field.PREFERRED);

        label("fallback", Field.FALLBACK);
        label("fallback position", Field.FALLBACK);
        label("fallback stance", Field.FALLBACK);
        label("acceptable fallback", Field.FALLBACK);
        label("acceptable position", Field.FALLBACK);
        label("can live with", Field.FALLBACK);
        label("second choice", Field.FALLBACK);

        label("prohibited", Field.PROHIBITED);
        label("prohibited position", Field.PROHIBITED);
        label("walk away", Field.PROHIBITED);
        label("walkaway", Field.PROHIBITED);
        label("walk away position", Field.PROHIBITED);
        label("walk away red line", Field.PROHIBITED);
        label("walk away or red line", Field.PROHIBITED);
        label("red line walk away", Field.PROHIBITED);
        label("red line", Field.PROHIBITED);
        label("never", Field.PROHIBITED);
        label("never accept", Field.PROHIBITED);
        label("do not accept", Field.PROHIBITED);
        label("not acceptable", Field.PROHIBITED);

        label("escalate", Field.ESCALATION);
        label("escalate if", Field.ESCALATION);
        label("escalation", Field.ESCALATION);
        label("escalation trigger", Field.ESCALATION);
        label("escalation condition", Field.ESCALATION);

        label("approver", Field.APPROVER);
        label("approvers", Field.APPROVER);
        label("approved by", Field.APPROVER);
        label("approval", Field.APPROVER);
        label("approval authority", Field.APPROVER);
        label("who signs off", Field.APPROVER);
        label("sign off", Field.APPROVER);
        label("signs off", Field.APPROVER);
        label("sign off by", Field.APPROVER);
    }

    record Result(
            String preferred,
            List<String> fallbacks,
            String prohibited,
            String escalationTrigger,
            List<AuthorityRule> entryRules,
            List<String> unmapped) {

        static Result empty() {
            return new Result(null, List.of(), null, null, List.of(), List.of());
        }

        Set<PositionRole> statedRoles() {
            var roles = new LinkedHashSet<PositionRole>();
            if (preferred != null) {
                roles.add(PositionRole.PREFERRED);
            }
            if (!fallbacks.isEmpty()) {
                roles.add(PositionRole.FALLBACK);
            }
            if (prohibited != null) {
                roles.add(PositionRole.PROHIBITED);
            }
            if (escalationTrigger != null) {
                roles.add(PositionRole.ESCALATION);
            }
            return roles;
        }
    }

    private LabelledFieldStage() {
    }

    /**
     * Reads one record. {@code columns} are the record's own label/value fields (a spreadsheet
     * row or a table row) and {@code text} its prose, in source order.
     */
    static Result parse(String title, String text, Map<String, String> columns) {
        var builder = new Builder();
        if (columns != null && !columns.isEmpty()) {
            readColumns(columns, builder);
        }
        if (text != null && !text.isBlank()) {
            readLines(text, builder);
        }
        builder.scanStatedValuesForRules(columns);
        return builder.build();
    }

    /**
     * The authority rules a document-level section or an approval-table row states, each with the
     * words that say which entries it governs. An approval table row is recognised by its own
     * headers; the value of the approver column is the rule's approver.
     */
    static List<AuthoritySourceRule> documentRules(String text, Map<String, String> columns) {
        var rules = new ArrayList<AuthoritySourceRule>();
        if (columns != null && !columns.isEmpty() && isApprovalTable(columns)) {
            var row = approvalTableRule(columns);
            if (row != null) {
                rules.add(row);
            }
            return dedupe(rules);
        }
        if (columns != null) {
            for (var entry : columns.entrySet()) {
                var normalized = RecordLabelRoles.normalize(entry.getKey());
                if (!isAuthorityCue(normalized) && !normalized.equals("escalation")) {
                    continue;
                }
                for (var sentence : sentences(entry.getValue())) {
                    rules.addAll(rulesIn(sentence, AuthorityLevel.MATRIX));
                }
            }
        }
        if (text != null && !text.isBlank()) {
            for (var line : text.split("\\R")) {
                var stripped = stripDocumentCue(line);
                if (stripped.isBlank()) {
                    continue;
                }
                for (var sentence : sentences(stripped)) {
                    rules.addAll(rulesIn(sentence, AuthorityLevel.MATRIX));
                }
            }
        }
        return dedupe(rules);
    }

    /**
     * Whether a unit is an authority source rather than a position: the document's approval
     * matrix or approval path, or an approval table row. A matrix or table unit is read for its
     * rules and is never published as a position.
     */
    static boolean isAuthoritySection(String title, String text, Map<String, String> columns) {
        if (columns != null && !columns.isEmpty()) {
            if (isApprovalTable(columns)) {
                return true;
            }
            // a spreadsheet key/value block carries the matrix as a column label ("Approval matrix");
            // an entry's own "Approval authority" or "Escalation" column is not a document cue
            for (var label : columns.keySet()) {
                if (fieldOf(label) == null && isAuthorityCue(RecordLabelRoles.normalize(label))) {
                    return true;
                }
            }
        }
        var normalizedTitle = RecordLabelRoles.normalize(title);
        if ((normalizedTitle.contains("approval") || normalizedTitle.contains("escalation"))
                && (normalizedTitle.contains("matrix") || normalizedTitle.contains("path")
                        || normalizedTitle.contains("table")
                        || normalizedTitle.contains("policy"))) {
            return true;
        }
        if (normalizedTitle.contains("authority")
                && (normalizedTitle.contains("approval") || normalizedTitle.contains("matrix")
                        || normalizedTitle.contains("path"))) {
            return true;
        }
        if (text != null) {
            for (var line : text.split("\\R")) {
                var stripped = line.strip();
                var separator = labelSeparator(stripped);
                if (separator > 0 && fieldOf(stripped.substring(0, separator).strip()) != null) {
                    // an entry's own "Approval authority: General Counsel" is a label, not a
                    // document-level control line
                    continue;
                }
                var normalized = RecordLabelRoles.normalize(stripped);
                if (normalized.startsWith("the ")
                        || normalized.startsWith("our ")
                        || normalized.startsWith("this ")) {
                    normalized = normalized.substring(normalized.indexOf(' ') + 1);
                }
                if (isAuthorityCue(normalized)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A document-level approval control line or column label, not an entry's own label. */
    private static boolean isAuthorityCue(String normalized) {
        return normalized.startsWith("approval matrix")
                || normalized.startsWith("approval path")
                || normalized.startsWith("approval authority")
                || normalized.startsWith("approval table")
                || normalized.startsWith("escalation policy")
                || normalized.startsWith("escalation matrix");
    }

    private static final class Builder {
        private String preferred;
        private final List<String> fallbacks = new ArrayList<>();
        private String prohibited;
        private String escalationTrigger;
        private final List<AuthorityRule> rules = new ArrayList<>();
        private final List<String> unmapped = new ArrayList<>();
        private Field pending;
        private String pendingQuote;

        void stance(Field field, String value, String quote) {
            var text = cleanValue(value);
            if (text == null) {
                if (field == Field.APPROVER) {
                    unmapped(quote);
                } else {
                    startPending(field, quote);
                }
                return;
            }
            switch (field) {
                case PREFERRED -> {
                    if (preferred == null) {
                        preferred = text;
                    }
                }
                case FALLBACK -> addFallback(text);
                case PROHIBITED -> {
                    if (prohibited == null) {
                        prohibited = text;
                    }
                }
                case ESCALATION -> {
                    if (escalationTrigger == null) {
                        escalationTrigger = text;
                    }
                }
                case APPROVER -> approver(text, Set.of(), null, quote);
            }
            pending = null;
        }

        void appendPending(String continuation) {
            if (pending == null) {
                return;
            }
            var field = pending;
            var label = pendingQuote;
            pending = null;
            stance(field, continuation,
                    label == null ? continuation : label + " " + continuation);
        }

        void addFallback(String text) {
            if (!fallbacks.contains(text)) {
                fallbacks.add(text);
            }
        }

        void approver(String value, Set<PositionRole> stances, String condition, String quote) {
            var approver = validApprover(value);
            if (approver == null) {
                if (!isNone(value)) {
                    unmapped(quote);
                }
                return;
            }
            var rule = new AuthorityRule(approver, stances, condition,
                    AuthorityLevel.ENTRY, quote);
            if (!rules.contains(rule)) {
                rules.add(rule);
            }
        }

        void ruleFromText(String text, AuthorityLevel level, PositionRole stanceHint) {
            for (var scoped : rulesIn(text, level)) {
                var rule = scoped.rule();
                if (rule.stances().isEmpty() && stanceHint != null) {
                    rule = new AuthorityRule(rule.approver(), Set.of(stanceHint),
                            rule.condition(), rule.source(), rule.quote());
                }
                if (!rules.contains(rule)) {
                    rules.add(rule);
                }
            }
        }

        void escalation(String value, String quote) {
            var text = cleanShortValue(value);
            if (text == null) {
                startPending(Field.ESCALATION, quote);
                return;
            }
            if (escalationTrigger == null) {
                escalationTrigger = text;
            }
            ruleFromText(text, AuthorityLevel.ENTRY, null);
        }

        void scanStatedValuesForRules(Map<String, String> columns) {
            if (columns == null) {
                return;
            }
            for (var entry : columns.entrySet()) {
                var value = entry.getValue();
                if (value == null || value.isBlank()) {
                    continue;
                }
                if (fieldOf(entry.getKey()) != null) {
                    continue;
                }
                ruleFromText(value, AuthorityLevel.ENTRY, null);
            }
        }

        void unmapped(String quote) {
            if (quote == null || quote.isBlank()) {
                return;
            }
            var text = quote.strip();
            if (!unmapped.contains(text)) {
                unmapped.add(bounded(text));
            }
        }

        void startPending(Field field, String quote) {
            pending = field;
            pendingQuote = quote;
        }

        Result build() {
            return new Result(preferred, List.copyOf(fallbacks), prohibited, escalationTrigger,
                    List.copyOf(rules), List.copyOf(unmapped));
        }
    }

    private static void readColumns(Map<String, String> columns, Builder builder) {
        var stanceRow = stanceRow(columns);
        if (stanceRow != null) {
            readStanceRow(columns, stanceRow, builder);
            return;
        }
        for (var entry : columns.entrySet()) {
            var field = fieldOf(entry.getKey());
            if (field == null) {
                continue;
            }
            var value = entry.getValue();
            var quote = entry.getKey() + ": " + (value == null ? "" : value);
            if (field == Field.ESCALATION) {
                builder.escalation(value, quote);
            } else {
                builder.stance(field, value, quote);
            }
        }
        if (builder.prohibited != null) {
            promoteSignOffToWalkAway(builder);
        }
    }

    /**
     * A sheet whose sign-off column sits beside a "Never" column names the walk-away authority;
     * without a walk-away column the same column names the entry's approver, not a stance.
     */
    private static void promoteSignOffToWalkAway(Builder builder) {
        var promoted = new ArrayList<AuthorityRule>();
        for (var rule : builder.rules) {
            promoted.add(rule.stances().isEmpty()
                    ? new AuthorityRule(rule.approver(), Set.of(PositionRole.PROHIBITED),
                            rule.condition(), rule.source(), rule.quote())
                    : rule);
        }
        builder.rules.clear();
        builder.rules.addAll(promoted);
    }

    /** The label a stance-row table uses to name the row's stance ("Position", "Variant"). */
    private static String stanceRow(Map<String, String> columns) {
        for (var entry : columns.entrySet()) {
            var label = RecordLabelRoles.normalize(entry.getKey());
            if (!label.equals("position") && !label.equals("stance") && !label.equals("variant")
                    && !label.equals("level") && !label.equals("role") && !label.equals("row")) {
                continue;
            }
            if (fieldOf(entry.getValue()) != null && fieldOf(entry.getValue()) != Field.APPROVER) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static void readStanceRow(
            Map<String, String> columns, String stanceLabel, Builder builder) {
        var rowStance = fieldOf(columns.get(stanceLabel));
        if (rowStance == null) {
            return;
        }
        String approverValue = null;
        String approverQuote = null;
        String trigger = null;
        var text = longestOtherValue(columns, stanceLabel);
        for (var entry : columns.entrySet()) {
            if (entry.getKey().equals(stanceLabel)) {
                continue;
            }
            var field = fieldOf(entry.getKey());
            if (field == Field.APPROVER) {
                approverValue = entry.getValue();
                approverQuote = entry.getKey() + ": " + entry.getValue();
            }
            if (isTriggerLabel(entry.getKey())) {
                trigger = cleanValue(entry.getValue());
            }
        }
        var quote = stanceLabel + ": " + (columns.get(stanceLabel) == null ? "" : columns.get(stanceLabel));
        switch (rowStance) {
            case PREFERRED -> builder.stance(Field.PREFERRED, text, quote);
            case FALLBACK -> builder.stance(Field.FALLBACK, text, quote);
            case PROHIBITED -> builder.stance(Field.PROHIBITED, text, quote);
            case ESCALATION -> {
                if (builder.escalationTrigger == null) {
                    builder.escalationTrigger = trigger != null ? trigger : text;
                }
                builder.ruleFromText(text == null ? "" : text, AuthorityLevel.ENTRY, null);
            }
            default -> {
            }
        }
        if (approverValue != null) {
            builder.approver(approverValue,
                    rowStance == Field.ESCALATION ? Set.of() : Set.of(stanceOf(rowStance)),
                    trigger, approverQuote);
        }
    }

    private static String longestOtherValue(Map<String, String> columns, String excludedLabel) {
        String longest = null;
        for (var entry : columns.entrySet()) {
            if (entry.getKey().equals(excludedLabel)) {
                continue;
            }
            var field = fieldOf(entry.getKey());
            if (field == Field.APPROVER || field == Field.ESCALATION || isTriggerLabel(entry.getKey())) {
                continue;
            }
            var value = cleanValue(entry.getValue());
            if (value != null && (longest == null || value.length() > longest.length())) {
                longest = value;
            }
        }
        return longest;
    }

    private static boolean isTriggerLabel(String label) {
        var normalized = RecordLabelRoles.normalize(label);
        return normalized.equals("trigger") || normalized.equals("condition")
                || normalized.equals("when") || normalized.equals("scenario")
                || normalized.equals("situation");
    }

    private static void readLines(String text, Builder builder) {
        for (var rawLine : text.split("\\R")) {
            var line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (isTableLine(line)) {
                readTableLine(line, builder);
                continue;
            }
            if (readLabelLine(line, builder)) {
                continue;
            }
            if (readImperativeLine(line, builder)) {
                continue;
            }
            if (builder.pending != null) {
                builder.appendPending(line);
                continue;
            }
            builder.ruleFromText(line, AuthorityLevel.ENTRY, null);
        }
    }

    private static boolean isTableLine(String line) {
        return line.startsWith("|") && line.endsWith("|") && line.length() > 2;
    }

    private static void readTableLine(String line, Builder builder) {
        var cells = new ArrayList<String>();
        for (var cell : line.substring(1, line.length() - 1).split("\\|", -1)) {
            cells.add(cell.strip());
        }
        if (cells.size() >= 2) {
            var label = cells.getFirst();
            var value = String.join(" ", cells.subList(1, cells.size())).strip();
            if (readLabel(label, value, line, builder)) {
                return;
            }
            if (looksLikeAuthorityLabel(label)) {
                builder.unmapped(line);
                return;
            }
        }
        for (var cell : cells) {
            builder.ruleFromText(cell, AuthorityLevel.ENTRY, null);
        }
    }

    private static boolean readLabelLine(String line, Builder builder) {
        var separator = labelSeparator(line);
        if (separator < 0) {
            return false;
        }
        var label = line.substring(0, separator).strip();
        var value = line.substring(separator + 1).strip();
        if (label.length() > MAX_LABEL_LENGTH || label.indexOf('.') >= 0
                || label.indexOf(',') >= 0) {
            return false;
        }
        if (readLabel(label, value, line, builder)) {
            return true;
        }
        if (looksLikeAuthorityLabel(label)) {
            builder.unmapped(line);
            return true;
        }
        return false;
    }

    private static boolean readLabel(
            String rawLabel, String value, String quote, Builder builder) {
        var field = fieldOf(rawLabel);
        if (field == null) {
            return false;
        }
        if (field == Field.ESCALATION) {
            builder.escalation(value, quote);
        } else {
            builder.stance(field, value, quote);
        }
        return true;
    }

    private static int labelSeparator(String line) {
        var colon = line.indexOf(':');
        if (colon > 0) {
            return colon;
        }
        var pipe = line.indexOf('|');
        if (pipe > 0) {
            return pipe;
        }
        return -1;
    }

    /** An imperative escalation sentence is a trigger; it is a rule only when it names a role. */
    private static boolean readImperativeLine(String line, Builder builder) {
        var normalized = RecordLabelRoles.normalize(line);
        if (normalized.equals("escalate") || normalized.startsWith("escalate ")) {
            var text = stripTrailingPeriod(line);
            builder.escalation(text, line);
            return true;
        }
        return false;
    }

    private static String stripDocumentCue(String line) {
        var stripped = line.strip();
        var normalized = RecordLabelRoles.normalize(stripped);
        if (normalized.startsWith("the ") || normalized.startsWith("our ")
                || normalized.startsWith("this ")) {
            normalized = normalized.substring(normalized.indexOf(' ') + 1);
        }
        for (var cue : List.of("approval matrix", "approval path", "approval authority",
                "approval table", "escalation policy", "escalation matrix")) {
            if (normalized.startsWith(cue)) {
                var separator = labelSeparator(stripped);
                return separator < 0 || separator >= stripped.length() - 1
                        ? "" : stripped.substring(separator + 1).strip();
            }
        }
        if (normalized.startsWith("escalation ")) {
            var separator = labelSeparator(stripped);
            if (separator >= 0) {
                return stripped.substring(separator + 1).strip();
            }
        }
        return stripped;
    }

    private static List<String> sentences(String text) {
        var sentences = new ArrayList<String>();
        var start = 0;
        for (int index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character != '.' && character != ';') {
                continue;
            }
            var next = index + 1;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                next++;
            }
            if (character == ';' || next == text.length()
                    || Character.isUpperCase(text.charAt(next))
                    || Character.isDigit(text.charAt(next))) {
                var sentence = text.substring(start, index).strip();
                if (!sentence.isEmpty()) {
                    sentences.add(sentence);
                }
                start = next;
                index = next - 1;
            }
        }
        var tail = text.substring(start).strip();
        if (!tail.isEmpty()) {
            sentences.add(tail);
        }
        return sentences;
    }

    /**
     * The deterministic authority patterns. Each returns a rule only when it names an approver;
     * the object or condition travels as the scope hint for the document matcher.
     */
    private static List<AuthoritySourceRule> rulesIn(String text, AuthorityLevel level) {
        var rules = new ArrayList<AuthoritySourceRule>();
        for (var sentence : sentences(text)) {
            var prepared = stripTrailingPeriod(sentence.replace('\u2019', '\''));
            ruleFromApprovalVerb(prepared, level, rules);
            ruleFromPassiveApproval(prepared, level, rules);
            ruleFromApprovalRequired(prepared, level, rules);
            ruleFromOwnership(prepared, level, rules);
            ruleFromRequiresApproval(prepared, level, rules);
            ruleFromSignOff(prepared, level, rules);
            ruleFromMayAccept(prepared, level, rules);
            ruleFromEscalateTo(prepared, level, rules);
        }
        return rules;
    }

    /** "CFO approval is required for fallbacks above 10%": the role comes first. */
    private static void ruleFromApprovalRequired(
            String sentence, AuthorityLevel level, List<AuthoritySourceRule> rules) {
        var matcher = java.util.regex.Pattern
                .compile("(?i)^(.+?)\\s+(?:written\\s+)?(?:approval|authorisation|authorization)"
                        + "\\s+(?:is|are)\\s+required\\b(.*)$")
                .matcher(sentence);
        if (!matcher.find()) {
            return;
        }
        var approver = validApprover(matcher.group(1));
        if (approver == null) {
            return;
        }
        var object = stripLeadingArticle(matcher.group(2).strip()
                .replaceFirst("(?i)^(for|before|on)\\s+", "").strip());
        var condition = conditionAfterThreshold(object);
        rules.add(new AuthoritySourceRule(new AuthorityRule(
                approver, stancesOf(object), condition, level, bounded(sentence)),
                scopeOf(object)));
    }

    /** "Legal owns the commercial positions": an ownership row names the default approver. */
    private static void ruleFromOwnership(
            String sentence, AuthorityLevel level, List<AuthoritySourceRule> rules) {
        var matcher = java.util.regex.Pattern
                .compile("(?i)^(.+?)\\s+owns?\\s+(?:the\\s+)?(.+?)\\s+positions?\\b(.*)$")
                .matcher(sentence);
        if (!matcher.find()) {
            return;
        }
        var approver = validApprover(matcher.group(1));
        if (approver == null) {
            return;
        }
        var object = (matcher.group(2) + " " + matcher.group(3)).strip();
        rules.add(new AuthoritySourceRule(new AuthorityRule(
                approver, stancesOf(object), null, level, bounded(sentence)),
                scopeOf(matcher.group(2))));
    }

    /** "Fallbacks must be approved by the Commercial Manager": the role follows the verb. */
    private static void ruleFromPassiveApproval(
            String sentence, AuthorityLevel level, List<AuthoritySourceRule> rules) {
        var matcher = java.util.regex.Pattern
                .compile("(?i)\\b(?:approved|authorised|authorized)\\s+by\\b").matcher(sentence);
        if (!matcher.find() || matcher.start() == 0) {
            return;
        }
        var condition = sentence.substring(0, matcher.start()).strip()
                .replaceFirst("(?i)\\b(must|shall|is|are|needs? to be|has to be)\\s*$", "")
                .strip();
        var rest = sentence.substring(matcher.end()).strip();
        var boundary = earliestBoundary(rest);
        var approverPart = boundary < 0 ? rest : rest.substring(0, boundary).strip();
        var approver = validApprover(approverPart);
        if (approver == null) {
            return;
        }
        var suffix = boundary < 0 ? "" : rest.substring(boundary).strip();
        var conditionText = conditionAfterThreshold(condition);
        if (conditionText == null) {
            conditionText = conditionAfterThreshold(suffix);
        }
        if (conditionText == null && readsAsCondition(condition)) {
            conditionText = cleanShortValue(condition);
        }
        rules.add(new AuthoritySourceRule(new AuthorityRule(
                approver, stancesOf(condition + " " + rest), conditionText, level,
                bounded(sentence)), scopeOf(condition)));
    }

    private static void ruleFromApprovalVerb(
            String sentence, AuthorityLevel level, List<AuthoritySourceRule> rules) {
        var matcher = java.util.regex.Pattern.compile("(?i)\\bapprove[sd]?\\b").matcher(sentence);
        if (!matcher.find() || matcher.start() == 0) {
            return;
        }
        var index = matcher.start();
        var subject = sentence.substring(0, index).strip();
        String condition = null;
        var comma = subject.lastIndexOf(',');
        if (comma > 0) {
            var prefix = subject.substring(0, comma).strip().toLowerCase(Locale.ROOT);
            if (prefix.startsWith("if ") || prefix.startsWith("when ")
                    || prefix.startsWith("where ") || prefix.startsWith("once ")
                    || prefix.startsWith("unless ")) {
                condition = subject.substring(0, comma).strip()
                        .replaceFirst("(?i)^(if|when|where|once|unless)\\s+", "").strip();
                subject = subject.substring(comma + 1).strip();
            }
        }
        // a clause that only contains the verb ("... that the Commercial Manager approves")
        // is not the sentence's authority statement
        var normalizedSubject = " " + subject.toLowerCase(Locale.ROOT) + " ";
        if (normalizedSubject.contains(" that ") || normalizedSubject.contains(" which ")
                || normalizedSubject.contains(" who ")
                || subject.toLowerCase(Locale.ROOT).startsWith("if ")
                || subject.toLowerCase(Locale.ROOT).startsWith("when ")
                || subject.toLowerCase(Locale.ROOT).startsWith("where ")
                || subject.split("\\s+").length > 12) {
            return;
        }
        var rest = sentence.substring(matcher.end()).strip();
        var approver = validApprover(subject.replaceFirst(
                "(?i)(\\s+(must|shall|may|can|will))$", ""));
        if (approver == null) {
            return;
        }
        var object = stripLeadingArticle(rest);
        // a threshold is a condition to show, never a value to evaluate (council §E)
        if (condition == null) {
            condition = conditionAfterThreshold(object);
        }
        rules.add(new AuthoritySourceRule(new AuthorityRule(
                approver, stancesOf(object), condition, level, bounded(sentence)),
                scopeOf(object)));
    }

    /** The threshold phrase of an approval object ("above 10% of annual fees"), or null. */
    private static String conditionAfterThreshold(String object) {
        var lower = " " + object.toLowerCase(Locale.ROOT) + " ";
        for (var cue : List.of(" above ", " over ", " below ", " under ", " beyond ",
                " exceeding ", " greater than ", " less than ")) {
            var index = lower.indexOf(cue);
            if (index >= 0) {
                return cleanShortValue(object.substring(index));
            }
        }
        return null;
    }

    private static void ruleFromRequiresApproval(
            String sentence, AuthorityLevel level, List<AuthoritySourceRule> rules) {
        var matcher = java.util.regex.Pattern.compile("(?i)\\brequires?\\b").matcher(sentence);
        if (!matcher.find()) {
            return;
        }
        var requires = matcher.start();
        var lower = sentence.toLowerCase(Locale.ROOT);
        var approval = lower.indexOf("approval", matcher.end());
        if (approval < 0) {
            return;
        }
        var condition = sentence.substring(0, requires).strip();
        var approverPart = sentence.substring(matcher.end(), approval).strip();
        approverPart = approverPart.replaceFirst("(?i)^(the|a|an)\\s+", "")
                .replaceFirst("(?i)\\s+(prior|written|written prior|prior written)$", "")
                .replaceFirst("(?i)[\']s$", "")
                .replaceFirst("(?i)s[\']$", "")
                .strip();
        var approver = validApprover(approverPart);
        if (approver == null) {
            return;
        }
        var conditionText = readsAsCondition(condition) ? stripLeadingArticle(condition) : null;
        var stanceSource = condition + " " + sentence.substring(approval);
        rules.add(new AuthoritySourceRule(new AuthorityRule(
                approver, stancesOf(stanceSource), conditionText, level, bounded(sentence)),
                scopeOf(condition)));
    }

    private static void ruleFromSignOff(
            String sentence, AuthorityLevel level, List<AuthoritySourceRule> rules) {
        var lower = sentence.toLowerCase(Locale.ROOT);
        var index = firstWord(lower, "signs off");
        if (index < 0) {
            index = firstWord(lower, "sign off");
        }
        if (index <= 0) {
            return;
        }
        var subject = sentence.substring(0, index).strip();
        var rest = sentence.substring(index).replaceFirst("(?i)^signs?\\s+off\\s*", "").strip();
        var approver = validApprover(subject);
        if (approver == null) {
            return;
        }
        rules.add(new AuthoritySourceRule(new AuthorityRule(
                approver, stancesOf(rest), null, level, bounded(sentence)), scopeOf(rest)));
    }

    private static void ruleFromMayAccept(
            String sentence, AuthorityLevel level, List<AuthoritySourceRule> rules) {
        var lower = sentence.toLowerCase(Locale.ROOT);
        for (var verb : List.of(" may accept ", " may apply ", " may approve ",
                " may agree ", " may waive ", " may vary ", " can accept ", " can approve ")) {
            var index = lower.indexOf(verb);
            if (index <= 0) {
                continue;
            }
            var subject = sentence.substring(0, index).strip();
            var rest = sentence.substring(index + verb.length()).strip();
            var approver = validApprover(subject);
            if (approver == null) {
                return;
            }
            var condition = conditionAfter(rest);
            var object = condition == null ? rest : rest.substring(0, rest.length() - condition.length() - 1).strip();
            rules.add(new AuthoritySourceRule(new AuthorityRule(
                    approver, stancesOf(object + " " + rest),
                    condition, level, bounded(sentence)), scopeOf(object)));
            return;
        }
    }

    private static void ruleFromEscalateTo(
            String sentence, AuthorityLevel level, List<AuthoritySourceRule> rules) {
        var lower = sentence.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("escalate to ")) {
            return;
        }
        var rest = sentence.substring("escalate to ".length()).strip();
        var boundary = earliestBoundary(rest);
        var approverPart = boundary < 0 ? rest : rest.substring(0, boundary).strip();
        var condition = boundary < 0 ? null
                : rest.substring(boundary).strip()
                        .replaceFirst("(?i)^(before|if|when|where|unless|on|for|after|once)\\s+", "")
                        .strip();
        var approver = validApprover(approverPart);
        if (approver == null) {
            return;
        }
        rules.add(new AuthoritySourceRule(new AuthorityRule(
                approver, stancesOf(sentence), condition == null ? null : condition,
                level, bounded(sentence)), scopeOf(condition)));
    }

    private static int earliestBoundary(String text) {
        var lower = " " + text.toLowerCase(Locale.ROOT) + " ";
        var earliest = -1;
        for (var word : BOUNDARY_WORDS) {
            var index = lower.indexOf(" " + word + " ");
            if (index >= 0 && (earliest < 0 || index < earliest)) {
                earliest = index;
            }
        }
        return earliest < 0 ? -1 : earliest;
    }

    private static String conditionAfter(String text) {
        var lower = " " + text.toLowerCase(Locale.ROOT) + " ";
        for (var word : List.of(" if ", " where ", " provided ", " unless ")) {
            var index = lower.indexOf(word);
            if (index >= 0) {
                return cleanShortValue(text.substring(index + word.length() - 1));
            }
        }
        return null;
    }

    private static boolean readsAsCondition(String text) {
        var lower = " " + text.toLowerCase(Locale.ROOT) + " ";
        for (var cue : List.of(" if ", " when ", " where ", " unless ", " any ", " deviation ",
                " beyond ", " above ", " over ", " breach ", " reach ")) {
            if (lower.contains(cue)) {
                return true;
            }
        }
        return false;
    }

    private static int firstWord(String lower, String word) {
        var index = lower.indexOf(word);
        while (index >= 0) {
            var before = index == 0 || !Character.isLetter(lower.charAt(index - 1));
            var afterIndex = index + word.length();
            var after = afterIndex >= lower.length() || !Character.isLetter(lower.charAt(afterIndex));
            if (before && after) {
                return index;
            }
            index = lower.indexOf(word, index + 1);
        }
        return -1;
    }

    private static Set<PositionRole> stancesOf(String text) {
        var normalized = RecordLabelRoles.normalize(text);
        var stances = new LinkedHashSet<PositionRole>();
        if (normalized.contains("fallback")) {
            stances.add(PositionRole.FALLBACK);
        }
        if (normalized.contains("walk away") || normalized.contains("walkaway")
                || normalized.contains("red line") || normalized.contains("prohibit")
                || normalized.contains("never accept") || normalized.contains("deviation")) {
            stances.add(PositionRole.PROHIBITED);
        }
        return stances;
    }

    private static String scopeOf(String text) {
        var cleaned = cleanValue(text);
        return cleaned == null ? null : cleaned;
    }

    private static String stripLeadingArticle(String text) {
        return text.replaceFirst("(?i)^(the|a|an)\\s+", "").strip();
    }

    private static String stripTrailingPeriod(String text) {
        var stripped = text.strip();
        while (stripped.endsWith(".")) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        return stripped;
    }

    private static Field fieldOf(String rawLabel) {
        if (rawLabel == null) {
            return null;
        }
        var normalized = RecordLabelRoles.normalize(rawLabel);
        if (normalized.isEmpty()) {
            return null;
        }
        var numeric = normalized.replaceFirst(" \\d+$", "");
        var withoutPosition = numeric.replaceFirst(" position$", "");
        for (var candidate : List.of(normalized, numeric, withoutPosition)) {
            var field = LABELS.get(candidate);
            if (field != null) {
                return field;
            }
        }
        return null;
    }

    private static PositionRole stanceOf(Field field) {
        return switch (field) {
            case PREFERRED -> PositionRole.PREFERRED;
            case FALLBACK -> PositionRole.FALLBACK;
            case PROHIBITED -> PositionRole.PROHIBITED;
            case ESCALATION -> PositionRole.ESCALATION;
            case APPROVER -> PositionRole.UNSPECIFIED;
        };
    }

    /** A label-shaped statement about a walk-away or an approver that the stage cannot map. */
    private static boolean looksLikeAuthorityLabel(String label) {
        var normalized = RecordLabelRoles.normalize(label);
        if (normalized.isEmpty()) {
            return false;
        }
        for (var cue : List.of("walk", "approv", "escalat", "red line", "prohibit",
                "sign off", "never", "no go", "deal breaker", "non negotiable", "final say",
                "must not", "cannot accept")) {
            if (normalized.contains(cue)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNone(String value) {
        if (value == null) {
            return false;
        }
        return NONE_VALUES.contains(RecordLabelRoles.normalize(value));
    }

    private static String validApprover(String value) {
        var cleaned = cleanShortValue(value);
        if (cleaned == null || isNone(cleaned)) {
            return null;
        }
        var lowered = cleaned.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("not ") || lowered.startsWith("no ") || lowered.endsWith(" approval")) {
            cleaned = cleaned.replaceFirst("(?i)\\s+approval$", "").strip();
            lowered = cleaned.toLowerCase(Locale.ROOT);
        }
        if (isNone(cleaned) || lowered.startsWith("not ") || lowered.startsWith("no ")) {
            return null;
        }
        if (GENERIC_APPROVERS.contains(RecordLabelRoles.normalize(cleaned))
                || RecordLabelRoles.normalize(cleaned).contains("named approver")) {
            return null;
        }
        if (cleaned.length() > MAX_APPROVER_LENGTH) {
            return null;
        }
        var matched = false;
        for (var role : ROLE_WORDS) {
            if (lowered.equals(role) || lowered.contains(role + " ")
                    || lowered.contains(" " + role)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            return null;
        }
        // "the Commercial Manager" reads as "Commercial Manager"; a long role phrase keeps
        // its article ("The lawyer handling the NDA").
        var withoutArticle = cleaned.replaceFirst("(?i)^(the|a|an)\\s+", "");
        return withoutArticle.split("\\s+").length <= 3 ? withoutArticle : cleaned;
    }

    private static String cleanValue(String value) {
        if (value == null) {
            return null;
        }
        var cleaned = value.strip().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** A short role or trigger value reads without the sentence's final stop. */
    private static String cleanShortValue(String value) {
        var cleaned = cleanValue(value);
        if (cleaned == null) {
            return null;
        }
        while (cleaned.endsWith(".") || cleaned.endsWith(";") || cleaned.endsWith(",")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).strip();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String bounded(String text) {
        var cleaned = text.strip();
        return cleaned.length() <= MAX_QUOTE_LENGTH ? cleaned
                : cleaned.substring(0, MAX_QUOTE_LENGTH);
    }

    private static boolean isApprovalTable(Map<String, String> columns) {
        boolean route = false;
        boolean decision = false;
        boolean issue = false;
        for (var label : columns.keySet()) {
            var normalized = RecordLabelRoles.normalize(label);
            // a position table with its own stance columns is never an approval table
            var field = fieldOf(label);
            if (field == Field.PREFERRED || field == Field.FALLBACK || field == Field.PROHIBITED) {
                return false;
            }
            if (normalized.equals("route to") || normalized.equals("who signs off")
                    || normalized.equals("who approves") || normalized.equals("approved by")
                    || normalized.equals("sign off") || normalized.equals("signs off")
                    || normalized.equals("approver") || normalized.equals("approval authority")) {
                route = true;
            }
            if (normalized.equals("decision needed") || normalized.equals("decision")) {
                decision = true;
            }
            if (normalized.equals("issue") || normalized.equals("matter")
                    || normalized.equals("situation") || normalized.equals("scenario")
                    || normalized.equals("topic")) {
                issue = true;
            }
        }
        return issue && (route || decision);
    }

    private static AuthoritySourceRule approvalTableRule(Map<String, String> columns) {
        String approverValue = null;
        String issueValue = null;
        String decisionValue = null;
        var quote = new StringBuilder();
        for (var entry : columns.entrySet()) {
            var normalized = RecordLabelRoles.normalize(entry.getKey());
            if (normalized.equals("route to") || normalized.equals("who signs off")
                    || normalized.equals("who approves") || normalized.equals("approved by")
                    || normalized.equals("sign off") || normalized.equals("signs off")
                    || normalized.equals("approver") || normalized.equals("approval authority")) {
                approverValue = entry.getValue();
            } else if (normalized.equals("issue") || normalized.equals("matter")
                    || normalized.equals("situation") || normalized.equals("scenario")
                    || normalized.equals("topic")) {
                issueValue = entry.getValue();
            } else if (normalized.equals("decision needed") || normalized.equals("decision")) {
                decisionValue = entry.getValue();
            }
            if (quote.length() > 0) {
                quote.append(" | ");
            }
            quote.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        var approver = validApprover(approverValue);
        if (approver == null) {
            return null;
        }
        var stances = stancesOf((decisionValue == null ? "" : decisionValue) + " "
                + (issueValue == null ? "" : issueValue));
        var condition = cleanValue(decisionValue);
        if (condition != null && condition.toLowerCase(Locale.ROOT).contains("within stated fallback")) {
            stances = Set.of(PositionRole.FALLBACK);
        }
        if (condition != null && condition.toLowerCase(Locale.ROOT).startsWith("before ")) {
            condition = condition.substring("before ".length()).strip();
        }
        return new AuthoritySourceRule(new AuthorityRule(
                approver, stances, cleanValue(issueValue), AuthorityLevel.TABLE,
                bounded(quote.toString())), cleanValue(issueValue));
    }

    private static List<AuthoritySourceRule> dedupe(List<AuthoritySourceRule> rules) {
        var seen = new LinkedHashSet<String>();
        var deduped = new ArrayList<AuthoritySourceRule>();
        for (var scoped : rules) {
            var key = scoped.rule().approver() + "|" + scoped.rule().stances() + "|"
                    + scoped.rule().condition() + "|" + scoped.scope();
            if (seen.add(key)) {
                deduped.add(scoped);
            }
        }
        return List.copyOf(deduped);
    }

    private static void label(String normalized, Field field) {
        LABELS.put(normalized, field);
    }
}
