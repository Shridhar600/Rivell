package com.rivell.workbench.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Code-owned synonym table mapping field labels to {@link RecordFieldRole}. Matching is
 * case- and punctuation-insensitive; a label that no rule knows is resolved once per
 * artifact by the {@code knowledge-field-role} decision instead.
 */
final class RecordLabelRoles {

    private static final Map<String, RecordFieldRole> EXACT = new LinkedHashMap<>();
    private static final Map<String, RecordFieldRole> PREFIX = new LinkedHashMap<>();

    static {
        exact("standard position", RecordFieldRole.PREFERRED);
        exact("preferred", RecordFieldRole.PREFERRED);
        exact("preferred position", RecordFieldRole.PREFERRED);
        exact("preferred standard position", RecordFieldRole.PREFERRED);
        exact("our position", RecordFieldRole.PREFERRED);
        exact("standard", RecordFieldRole.PREFERRED);
        exact("red line", RecordFieldRole.PROHIBITED);
        exact("walkaway", RecordFieldRole.PROHIBITED);
        exact("never accept", RecordFieldRole.PROHIBITED);
        exact("ref", RecordFieldRole.REFERENCE);
        exact("reference", RecordFieldRole.REFERENCE);
        exact("applies to", RecordFieldRole.APPLICABILITY);
        exact("contract types", RecordFieldRole.APPLICABILITY);
        exact("contract type", RecordFieldRole.APPLICABILITY);
        exact("topic", RecordFieldRole.TOPIC);
        exact("clause topic", RecordFieldRole.TOPIC);
        exact("wording", RecordFieldRole.WORDING);
        exact("approved language", RecordFieldRole.WORDING);
        exact("approved wording", RecordFieldRole.WORDING);
        exact("clause text", RecordFieldRole.WORDING);
        exact("note", RecordFieldRole.COMMENTARY);
        exact("notes", RecordFieldRole.COMMENTARY);

        prefix("fallback", RecordFieldRole.FALLBACK);
        prefix("prohibited", RecordFieldRole.PROHIBITED);
        prefix("walk away", RecordFieldRole.PROHIBITED);
        prefix("approver", RecordFieldRole.ESCALATION);
        prefix("escalat", RecordFieldRole.ESCALATION);
        prefix("drafting note", RecordFieldRole.COMMENTARY);
        prefix("note on", RecordFieldRole.COMMENTARY);
        prefix("rationale", RecordFieldRole.COMMENTARY);
        prefix("when to use", RecordFieldRole.COMMENTARY);
        prefix("commentary", RecordFieldRole.COMMENTARY);
        prefix("applies", RecordFieldRole.APPLICABILITY);
        prefix("scope", RecordFieldRole.APPLICABILITY);
        prefix("subject", RecordFieldRole.TOPIC);
        prefix("language", RecordFieldRole.WORDING);
    }

    private RecordLabelRoles() {
    }

    static Optional<RecordFieldRole> known(String label) {
        var normalized = normalize(label);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        var exact = EXACT.get(normalized);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (var entry : PREFIX.entrySet()) {
            if (normalized.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    static String normalize(String label) {
        if (label == null) {
            return "";
        }
        return label.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    private static void exact(String label, RecordFieldRole role) {
        EXACT.put(label, role);
    }

    private static void prefix(String label, RecordFieldRole role) {
        PREFIX.put(label, role);
    }
}
