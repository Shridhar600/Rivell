package com.rivell.workbench.knowledge;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One typed approval rule (council §A.1): who approves, which stances it governs, an optional
 * condition, where the statement came from, and the source words it rests on. A rule always
 * names an approver: a bare trigger text is not a rule, so it can never make an approval.
 *
 * <p>An empty stance set means the source names the approver without naming a stance. Such a
 * rule governs the fallback and the walk-away of its entry, never the preferred position.
 */
public record AuthorityRule(
        String approver,
        Set<PositionRole> stances,
        String condition,
        AuthorityLevel source,
        String quote) {

    public AuthorityRule {
        if (approver == null || approver.isBlank()) {
            throw new IllegalArgumentException("An authority rule needs an approver");
        }
        approver = approver.strip();
        stances = stances == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(stances));
        condition = condition == null || condition.isBlank() ? null
                : condition.strip()
                        .replaceFirst("(?i)^(if|when|where|unless|for|on|after|once)\\s+", "")
                        .replaceFirst("(?i)\\s+(must be|shall be|is required|are required|required|must|shall)$", "")
                        .replaceAll("[.;:,]+$", "")
                        .strip();
        if (condition != null && condition.isBlank()) {
            condition = null;
        }
        if (source == null) {
            throw new IllegalArgumentException("An authority rule needs a source level");
        }
        quote = quote == null || quote.isBlank() ? null : quote.strip();
    }

    /** The same rule with only the given stance attached, for a per-stance effective value. */
    public AuthorityRule forStance(PositionRole stance) {
        return new AuthorityRule(approver, Set.of(stance), condition, source, quote);
    }
}
