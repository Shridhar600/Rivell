package com.rivell.workbench.platform.parsing;

import java.util.Set;

/**
 * What one paragraph is, structurally, and why.
 *
 * @param kind      title, section heading, or body
 * @param level     heading level (1 = top) for headings, else null
 * @param signals   the evidence that decided it, for debugging and evaluation
 * @param ambiguous true when only weak evidence decided it; a second-opinion detector may
 *                  revisit these and only these
 */
public record HeadingDecision(Kind kind, Integer level, Set<String> signals, boolean ambiguous) {

    public enum Kind { TITLE, HEADING, BODY }

    public static HeadingDecision body() {
        return new HeadingDecision(Kind.BODY, null, Set.of(), false);
    }
}
