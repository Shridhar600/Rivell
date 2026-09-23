package com.rivell.workbench.contracts;

import java.util.List;

/** Set of quality reasons; NONE when nothing is wrong. */
public record QualityReasons(List<ReviewQualityNote> notes) {

    public static final QualityReasons NONE = new QualityReasons(List.of(ReviewQualityNote.NONE));

    public QualityReasons {
        notes = notes == null || notes.isEmpty()
                ? List.of(ReviewQualityNote.NONE)
                : List.copyOf(notes);
    }

    public static QualityReasons of(List<ReviewQualityNote> notes) {
        return new QualityReasons(notes);
    }
}
