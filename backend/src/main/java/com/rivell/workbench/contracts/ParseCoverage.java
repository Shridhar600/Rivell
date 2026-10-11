package com.rivell.workbench.contracts;

import java.util.List;
import java.util.Objects;

/**
 * How much of the uploaded file's text the parsed structure holds, measured against an
 * independent read of the raw file. Deterministic: same bytes and same nodes give the same
 * report. Source text is the accept-all view (tracked insertions in, deletions and field codes
 * out).
 *
 * @param exact false when the file differed too much for a word-level alignment and the
 *     check fell back to whole-paragraph matching
 * @param totals source and captured words per location
 * @param extraWords structure words with no source counterpart (should be zero)
 * @param gaps every run of source text the structure does not hold, in source order
 */
public record ParseCoverage(
        int checkVersion,
        boolean exact,
        List<LocationTotal> totals,
        int extraWords,
        List<Gap> gaps) {

    public static final int CHECK_VERSION = 1;

    public ParseCoverage {
        totals = totals == null ? List.of() : List.copyOf(totals);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }

    public int sourceWords() {
        return totals.stream().mapToInt(LocationTotal::sourceWords).sum();
    }

    public int capturedWords() {
        return totals.stream().mapToInt(LocationTotal::capturedWords).sum();
    }

    public enum Location {
        BODY, TABLE, TEXT_BOX, HEADER, FOOTER, FOOTNOTE, ENDNOTE, COMMENT
    }

    public record LocationTotal(Location location, int sourceWords, int capturedWords) {

        public LocationTotal {
            Objects.requireNonNull(location, "location");
        }
    }

    /**
     * One run of source text the structure does not hold.
     *
     * @param part package part, e.g. {@code word/document.xml}, {@code word/footer1.xml}
     * @param excerpt the start of the missing text, at most {@link #EXCERPT_CHARS} characters
     */
    public record Gap(String part, Location location, int words, String excerpt) {

        public static final int EXCERPT_CHARS = 160;

        public Gap {
            Objects.requireNonNull(part, "part");
            Objects.requireNonNull(location, "location");
        }
    }
}
