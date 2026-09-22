package com.rivell.workbench.knowledge;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

import com.rivell.workbench.foundation.text.Texts;

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
        contractPackKey = Texts.normalize(contractPackKey);
        partyPerspective = Texts.normalize(partyPerspective);
        paperSource = Texts.normalize(paperSource);
        jurisdiction = Texts.normalize(jurisdiction);
        language = Texts.normalize(language);
        note = Texts.normalize(note);
        if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException(
                    "Effective period must not end before it starts");
        }
    }

}
