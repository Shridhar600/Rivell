package com.rivell.workbench.knowledge;

/**
 * One rule read from a document-level authority source (the approval matrix, the approval path
 * or an approval table row), with the words that say which entries it governs. The scope is a
 * compile-time matching hint, not part of the typed rule a finding shows.
 */
public record AuthoritySourceRule(AuthorityRule rule, String scope) {

    public AuthoritySourceRule {
        if (rule == null) {
            throw new IllegalArgumentException("A source rule needs a rule");
        }
        scope = scope == null || scope.isBlank() ? null : scope.strip();
    }
}
