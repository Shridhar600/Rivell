package com.rivell.workbench.retrieval;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, provenance-backed evidence bundle (AD #8.5). Retrieval returns this —
 * never a legal decision, never a Finding. Bundles are reproducible from the pinned
 * request inputs, knowledge publication, and index/model versions recorded in the
 * trace, as far as probabilistic provider behavior permits.
 */
public record EvidenceBundle(
        UUID id,
        EvidenceRequest request,
        List<EvidenceItem> policyItems,
        List<EvidenceItem> approvedDraftingItems,
        List<EvidenceItem> precedentItems,
        List<EvidenceItem> currentContractItems,
        List<EvidenceItem> externalReferenceItems,
        EvidenceSufficiency sufficiency,
        List<String> degradationWarnings,
        UUID traceId) {

    public EvidenceBundle {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(request, "request");
        policyItems = items(policyItems);
        approvedDraftingItems = items(approvedDraftingItems);
        precedentItems = items(precedentItems);
        currentContractItems = items(currentContractItems);
        externalReferenceItems = items(externalReferenceItems);
        Objects.requireNonNull(sufficiency, "sufficiency");
        degradationWarnings = degradationWarnings == null ? List.of() : List.copyOf(degradationWarnings);
    }

    private static List<EvidenceItem> items(List<EvidenceItem> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    public List<EvidenceItem> items() {
        var all = new java.util.ArrayList<EvidenceItem>();
        all.addAll(policyItems);
        all.addAll(approvedDraftingItems);
        all.addAll(precedentItems);
        all.addAll(currentContractItems);
        all.addAll(externalReferenceItems);
        return List.copyOf(all);
    }
}
