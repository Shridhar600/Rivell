package com.rivell.workbench.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
public record CandidatePayload(
        Map<String, String> columns, List<String> columnOrder,
        /**
         * Roles of labels the code synonym table does not know ("Can live with" -> FALLBACK), as
         * resolved once per artifact by the field-role decision. Code-known labels are not stored:
         * their role always comes from the current synonym table.
         */
        Map<String, String> fieldRoles) {

    public static final CandidatePayload EMPTY = new CandidatePayload(Map.of(), List.of());

    public CandidatePayload(Map<String, String> columns) {
        this(columns, columns == null ? List.of() : List.copyOf(columns.keySet()));
    }

    public CandidatePayload(Map<String, String> columns, List<String> columnOrder) {
        this(columns, columnOrder, Map.of());
    }

    public CandidatePayload {
        columns = columns == null || columns.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        columnOrder = columnOrder == null ? List.of() : List.copyOf(columnOrder);
        fieldRoles = fieldRoles == null || fieldRoles.isEmpty() ? Map.of() : Map.copyOf(fieldRoles);
    }

    public CandidatePayload withFieldRoles(Map<String, String> roles) {
        return new CandidatePayload(columns, columnOrder, roles);
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
     * <p>A correction replaces the values it names and leaves the others alone, so corrections
     * compose: correcting one cell and then another keeps both. Replacing the whole value set
     * would silently drop the earlier correction whenever a client submitted only the cell it
     * had just changed.
     *
     * <p>A correction does not reorder the source. The submitted map arrives from a client (and
     * from {@code jsonb}) in an arbitrary key order, so taking its order would let a correction
     * restate a spreadsheet row as "Fallback | Preferred". Columns the correction introduces are
     * appended after the known ones.
     */
    public CandidatePayload correctedBy(CandidatePayload correction) {
        var values = new LinkedHashMap<>(columns);
        values.putAll(correction.columns());
        var order = new ArrayList<String>(orderedKeys());
        for (var key : correction.columns().keySet()) {
            if (!order.contains(key)) {
                order.add(key);
            }
        }
        return new CandidatePayload(values, order, fieldRoles);
    }

    /**
     * Recovers the source order of a payload that was persisted before the order was recorded.
     *
     * <p>The machine wording is the reliable record of source order: extraction rendered it left
     * to right, so it can be replayed against the stored values to recover which value came
     * first. Recovery is applied only when the wording is exactly the rendering of those values -
     * same number of readable values, each matching an unconsumed one. If the stored wording is
     * unrelated to the columns, or a value itself contains the separator, nothing is recovered
     * and the payload keeps the order the database returned rather than claiming a source order it
     * cannot prove.
     *
     * <p>A human correction over the same columns no longer matches the machine wording, so its
     * order comes from {@code verifiedOrder}, the order already recovered for the machine
     * payload of the same row. Columns the correction introduced keep their own order at the end.
     */
    public CandidatePayload withRecoveredOrder(String machineWording, List<String> verifiedOrder) {
        if (!columnOrder.isEmpty() || columns.isEmpty()) {
            return this;
        }
        var matched = matchOrderTo(machineWording);
        if (matched != null) {
            return new CandidatePayload(columns, matched, fieldRoles);
        }
        if (verifiedOrder == null || verifiedOrder.isEmpty()
                || !columns.keySet().containsAll(verifiedOrder)) {
            return this;
        }
        return new CandidatePayload(columns, verifiedOrder, fieldRoles);
    }

    private List<String> matchOrderTo(String machineWording) {
        if (machineWording == null || machineWording.isBlank()) {
            return null;
        }
        var readable = new ArrayList<String>();
        for (var part : machineWording.split("\\|", -1)) {
            var value = part.strip();
            if (!value.isEmpty()) {
                readable.add(value);
            }
        }
        var unmatched = new LinkedHashMap<String, String>();
        for (var entry : columns.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                unmatched.put(entry.getKey(), entry.getValue());
            }
        }
        if (unmatched.size() != readable.size()) {
            return null;
        }
        var order = new ArrayList<String>(readable.size());
        for (var value : readable) {
            String match = null;
            for (var entry : unmatched.entrySet()) {
                if (value.equals(entry.getValue())) {
                    match = entry.getKey();
                    break;
                }
            }
            if (match == null) {
                return null;
            }
            unmatched.remove(match);
            order.add(match);
        }
        return order;
    }

    /**
     * The wording of a clause variant record: the value of its wording column ("Clause text",
     * "Approved language"), as it stands in the source. A record with no such column, or an empty
     * one, falls back to {@link #render()}.
     */
    public String renderedWording() {
        return wordingText().orElseGet(this::render);
    }

    /**
     * The value of the column that plays the wording role, when there is one. The code synonym
     * table is consulted first; a label the field-role decision resolved ("Provision" as
     * WORDING) counts the same, because the source said it and the model read it.
     */
    Optional<String> wordingText() {
        for (var key : orderedKeys()) {
            var value = columns.get(key);
            if (value != null && !value.isBlank()
                    && roleOf(key).filter(role -> role == RecordFieldRole.WORDING).isPresent()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private Optional<RecordFieldRole> roleOf(String label) {
        var known = RecordLabelRoles.known(label);
        if (known.isPresent()) {
            return known;
        }
        var resolved = fieldRoles.get(label);
        if (resolved == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(RecordFieldRole.valueOf(resolved));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
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
