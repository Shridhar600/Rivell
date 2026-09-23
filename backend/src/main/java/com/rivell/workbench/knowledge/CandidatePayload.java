package com.rivell.workbench.knowledge;

import java.util.Map;
import java.util.Objects;

/**
 * Typed payload of a knowledge candidate. M2 captures source column values verbatim
 * (table/spreadsheet cells keyed by their header labels); semantic fields arrive with M4
 * and must never be invented early (Architecture Decision #7.12).
 */
public record CandidatePayload(Map<String, String> columns) {

    public static final CandidatePayload EMPTY = new CandidatePayload(Map.of());

    public CandidatePayload {
        columns = columns == null ? Map.of() : Map.copyOf(columns);
    }
}
