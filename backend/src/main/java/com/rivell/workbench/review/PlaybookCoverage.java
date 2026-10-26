package com.rivell.workbench.review;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What one analysis run did with every published playbook position that applies to its contract
 * type: judged with a verdict, searched and not found, not compared, or out of scope by
 * the position's own "Applies to". Computed once when the run finishes from the run's check
 * detail, then frozen on the run. It is never re-resolved from live knowledge at read time.
 * A run whose coverage could not be computed carries no list at all, which reads the same as a
 * run that predates the list: neither claims anything.
 *
 * <p>"Not found in this contract" means a search ran for the position and no clause addressed it.
 * It never means the provision does not exist. "Not compared" covers both "no check judged or
 * searched for it" and "a check did not complete"; it never implies absence.
 */
public record PlaybookCoverage(List<Entry> entries) {

    public PlaybookCoverage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public enum State {
        COMPARED,
        NOT_FOUND,
        NOT_COMPARED,
        OUT_OF_SCOPE
    }

    /** The product words for a judged position; {@code PARTLY_WITHIN_FALLBACK} is P3's report. */
    public enum Verdict {
        MEETS_PREFERRED,
        WITHIN_FALLBACK,
        PARTLY_WITHIN_FALLBACK,
        BEYOND_FALLBACK,
        WALK_AWAY,
        UNCLEAR
    }

    /** Why a position was not compared; only present for {@link State#NOT_COMPARED}. */
    public enum Reason {
        NOT_CHECKED,
        FOUND_NOT_JUDGED,
        CHECK_DID_NOT_COMPLETE
    }

    public record Entry(
            UUID positionCandidateId,
            String label,
            String code,
            String topic,
            State state,
            Verdict verdict,
            UUID clauseHeadingNodeId,
            UUID findingId,
            Reason reason) {

        public Entry {
            Objects.requireNonNull(positionCandidateId, "positionCandidateId");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(state, "state");
        }
    }
}
