package com.rivell.workbench.knowledge;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * Human-declared ingestion manifest (Architecture Decision #7.1). Values are stored
 * verbatim; the system never rewrites declared metadata. The artifact type lives on the
 * artifact itself; the manifest holds the remaining declared context.
 */
public record ArtifactManifest(
        Set<String> declaredContractTypes,
        String contractPackKey,
        String partyPerspective,
        String paperSource,
        String jurisdiction,
        String language,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String note) {

    public ArtifactManifest {
        declaredContractTypes = declaredContractTypes == null ? Set.of() : Set.copyOf(declaredContractTypes);
        contractPackKey = normalize(contractPackKey);
        partyPerspective = normalize(partyPerspective);
        paperSource = normalize(paperSource);
        jurisdiction = normalize(jurisdiction);
        language = normalize(language);
        note = normalize(note);
        if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException(
                    "Effective period must not end before it starts");
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
