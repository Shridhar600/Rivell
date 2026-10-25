package com.rivell.workbench.review.check;

import java.util.HashMap;
import java.util.Map;

import com.rivell.workbench.intelligence.ClauseEmbeddingCache;
import com.rivell.workbench.intelligence.EmbeddingLookup;
import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceService;

/**
 * Run-scoped evidence memoization: identical requests never re-run retrieval lanes, and every
 * clause text is embedded once for the precedent and position-search lanes.
 */
public final class ReviewEvidenceCache {

    private final EvidenceService evidenceService;
    private final EmbeddingLookup embeddings;
    private final Map<EvidenceRequest, EvidenceBundle> cache = new HashMap<>();

    public ReviewEvidenceCache(EvidenceService evidenceService) {
        this(evidenceService, null);
    }

    public ReviewEvidenceCache(EvidenceService evidenceService, ClauseEmbeddingCache embeddings) {
        this.evidenceService = evidenceService;
        this.embeddings = embeddings;
    }

    public EvidenceBundle build(EvidenceRequest request) {
        return cache.computeIfAbsent(request, this::buildUncached);
    }

    public EvidenceBundle buildForPolicyComparison(
            EvidenceRequest request, java.util.List<com.rivell.workbench.taxonomy.ConceptKey> topics,
            com.rivell.workbench.taxonomy.ConceptKey primaryTopic, int maxPositions) {
        return evidenceService.buildForPolicyComparison(
                request, topics, primaryTopic, maxPositions, embeddings);
    }

    public EvidenceBundle buildForPositionSearch(
            EvidenceRequest request, java.util.Set<java.util.UUID> alreadyJudged,
            java.util.Set<String> excludedTopics, int limit) {
        return evidenceService.buildForPositionSearch(
                request, alreadyJudged, excludedTopics, limit, embeddings);
    }

    public EvidenceBundle buildForUncoveredClause(EvidenceRequest request) {
        return evidenceService.buildForUncoveredClause(request, embeddings);
    }

    private EvidenceBundle buildUncached(EvidenceRequest request) {
        return evidenceService.buildForOrganization(request, embeddings);
    }
}
