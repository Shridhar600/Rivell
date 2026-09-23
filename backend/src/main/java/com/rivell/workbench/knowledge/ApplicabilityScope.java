package com.rivell.workbench.knowledge;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * Conditions under which a knowledge candidate applies (Architecture Decision #7.13).
 * Defaults are inherited from the artifact manifest; review may narrow or edit them.
 * Conflict analysis must consider scope overlap, not the legal concept alone.
 */
public record ApplicabilityScope(
        String contractPackKey,
        Set<String> declaredContractTypes,
        String partyPerspective,
        String paperSource,
        String jurisdiction,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {

    public ApplicabilityScope {
        declaredContractTypes = declaredContractTypes == null ? Set.of() : Set.copyOf(declaredContractTypes);
    }

    public static ApplicabilityScope fromManifest(ArtifactManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return new ApplicabilityScope(manifest.contractPackKey(), manifest.declaredContractTypes(),
                manifest.partyPerspective(), manifest.paperSource(), manifest.jurisdiction(),
                manifest.effectiveFrom(), manifest.effectiveTo());
    }
}
