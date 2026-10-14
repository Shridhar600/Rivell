package com.rivell.workbench.knowledge;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * Conditions under which a knowledge candidate applies (Architecture Decision #7.13).
 * Defaults are inherited from the artifact manifest; review may narrow or edit them.
 * Conflict analysis must consider scope overlap, not the legal concept alone.
 *
 * <p>{@code sourceContractTypes} is what the record itself says it applies to ("Applies to:
 * MSA, SOW", a contract-type column group), as contract type codes; empty when the record
 * says nothing. It narrows within the manifest's declared types and is never a human edit.
 */
public record ApplicabilityScope(
        String contractPackKey,
        Set<String> declaredContractTypes,
        String partyPerspective,
        String paperSource,
        String jurisdiction,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Set<String> sourceContractTypes) {

    public ApplicabilityScope {
        declaredContractTypes = declaredContractTypes == null ? Set.of() : Set.copyOf(declaredContractTypes);
        sourceContractTypes = sourceContractTypes == null ? Set.of() : Set.copyOf(sourceContractTypes);
    }

    public ApplicabilityScope(
            String contractPackKey, Set<String> declaredContractTypes, String partyPerspective,
            String paperSource, String jurisdiction, LocalDate effectiveFrom, LocalDate effectiveTo) {
        this(contractPackKey, declaredContractTypes, partyPerspective, paperSource, jurisdiction,
                effectiveFrom, effectiveTo, Set.of());
    }

    public static ApplicabilityScope fromManifest(ArtifactManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return new ApplicabilityScope(manifest.contractPackKey(), manifest.declaredContractTypes(),
                manifest.partyPerspective(), manifest.paperSource(), manifest.jurisdiction(),
                manifest.effectiveFrom(), manifest.effectiveTo());
    }

    public ApplicabilityScope withSourceContractTypes(Set<String> types) {
        return new ApplicabilityScope(contractPackKey, declaredContractTypes, partyPerspective,
                paperSource, jurisdiction, effectiveFrom, effectiveTo, types);
    }
}
