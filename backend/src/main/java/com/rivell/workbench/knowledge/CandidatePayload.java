package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed payload of a knowledge candidate. M2 captures source column values verbatim
 * (table/spreadsheet cells keyed by their header labels); semantic fields arrive with M4
 * and must never be invented early (Architecture Decision #7.12).
 *
 * <p>Two properties matter and are easy to lose:
 * <ul>
 *   <li><b>Column order is source meaning.</b> A spreadsheet row reads left to right, so the
 *       rendered wording of a structured candidate depends on the order of its values.</li>
 *   <li><b>A JSON object does not keep key order.</b> The payload is persisted as PostgreSQL
 *       {@code jsonb}, which normalizes object keys (by length, then bytes) and therefore
 *       returns {@code Fallback} before {@code Preferred}. The source order is carried
 *       alongside as an ordered array, which {@code jsonb} does preserve.</li>
 * </ul>
 */
public record CandidatePayload(Map<String, String> columns, List<String> columnOrder) {

    public static final CandidatePayload EMPTY = new CandidatePayload(Map.of(), List.of());

    public CandidatePayload(Map<String, String> columns) {
        this(columns, columns == null ? List.of() : List.copyOf(columns.keySet()));
    }

    public CandidatePayload {
        columns = columns == null || columns.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        columnOrder = columnOrder == null ? List.of() : List.copyOf(columnOrder);
    }

    /**
     * The authoritative value order: the recorded source order first, then any column a later
     * correction added, in the order the correction supplied it. A value absent from the record
     * is skipped rather than rendered out of position.
     */
    public List<String> orderedKeys() {
        if (columnOrder.isEmpty()) {
            return List.copyOf(columns.keySet());
        }
        var ordered = new ArrayList<String>(columns.size());
        for (var key : columnOrder) {
            if (columns.containsKey(key)) {
                ordered.add(key);
            }
        }
        if (ordered.size() < columns.size()) {
            for (var key : columns.keySet()) {
                if (!ordered.contains(key)) {
                    ordered.add(key);
                }
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * The human's corrected values, carried in this payload's authoritative column order.
     *
     * <p>A correction replaces values; it does not reorder the source. The submitted map arrives
     * from a client (and from {@code jsonb}) in an arbitrary key order, so taking its order would
     * let a correction silently restate a spreadsheet row as "Fallback | Preferred". Columns the
     * correction introduces are appended after the known ones.
     */
    public CandidatePayload correctedBy(CandidatePayload corrected) {
        var order = new ArrayList<String>(orderedKeys());
        for (var key : corrected.columns().keySet()) {
            if (!order.contains(key)) {
                order.add(key);
            }
        }
        return new CandidatePayload(corrected.columns(), order);
    }

    /**
     * The single canonical wording rendering of structured source values: the non-blank cell
     * values in authoritative column order, separated by {@code " | "}. Extraction renders
     * machine wording this way and a human column correction is rendered the same way, so the
     * only difference between machine and corrected wording is the value the human changed.
     */
    public String render() {
        var rendered = new StringBuilder();
        for (var key : orderedKeys()) {
            var value = columns.get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (rendered.length() > 0) {
                rendered.append(" | ");
            }
            rendered.append(value);
        }
        return rendered.isEmpty() ? null : rendered.toString();
    }
}