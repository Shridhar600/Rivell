package com.rivell.workbench.review.check;

import java.util.HashMap;
import java.util.Map;

import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceService;

/** Run-scoped evidence memoization: identical requests never re-run retrieval lanes. */
public final class ReviewEvidenceCache {

    private final EvidenceService evidenceService;
    private final Map<EvidenceRequest, EvidenceBundle> cache = new HashMap<>();

    public ReviewEvidenceCache(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    public EvidenceBundle build(EvidenceRequest request) {
        return cache.computeIfAbsent(request, evidenceService::buildForOrganization);
    }
}
