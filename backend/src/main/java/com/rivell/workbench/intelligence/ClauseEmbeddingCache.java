package com.rivell.workbench.intelligence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Run-scoped embedding cache. Every distinct clause text is embedded once and both retrieval
 * lanes read the same vector, so results are identical to the per-call path while the review
 * makes one batch request instead of one request per lane and clause.
 *
 * <p>Like {@code ReviewEvidenceCache} this is a plain {@link HashMap}: review checks run one at
 * a time, and the cache must stay scoped to one run, never application-wide.
 */
public final class ClauseEmbeddingCache implements EmbeddingLookup {

    /**
     * After a rejected batch, only this many texts are retried alone. A provider that rejects the
     * batch for size is likely to reject many inputs too, and the callers already tolerate a
     * degraded lane; the rest either stay uncached or lose their own slot.
     */
    public static final int MAX_FALLBACK_TEXTS = 8;

    private final EmbeddingService embeddingService;
    private final Map<String, float[]> vectors = new HashMap<>();
    /**
     * Set when the prefetch failed for a reason that is not about one input. Replayed to every
     * later caller instead of one fresh provider call per clause: an endpoint that timed out or is
     * down would otherwise spend the review's budget once per clause.
     */
    private SemanticDecisionException laneFailure;

    public ClauseEmbeddingCache(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * Embeds every text not already cached in one batch. Only a rejected batch (the provider
     * refused the request itself) retries at most {@link #MAX_FALLBACK_TEXTS} inputs alone; a
     * timeout or outage stops here and is replayed to every later caller, because one more call per
     * text would spend the review's budget on an endpoint that is not answering. The callers
     * already tolerate a failed lane and degrade their own.
     */
    public void prefetch(List<String> texts) {
        if (!embeddingService.isConfigured()) {
            return;
        }
        var missing = new LinkedHashSet<String>();
        for (var text : texts) {
            if (text != null && !vectors.containsKey(text)) {
                missing.add(text);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        try {
            var execution = embeddingService.embed(new ArrayList<>(missing));
            var textsInOrder = List.copyOf(missing);
            for (int index = 0; index < textsInOrder.size(); index++) {
                vectors.put(textsInOrder.get(index), execution.vectors().get(index));
            }
        } catch (SemanticDecisionException e) {
            if (ProviderFailures.isOutOfCredit(e)) {
                // a dry account fails every text the same way; the short circuit keeps it cheap
                laneFailure = e;
                return;
            }
            if (!(e instanceof ProviderRejectedException)) {
                // a timeout or outage is not about one input: retrying each text would spend the
                // review's budget on the same failing endpoint, so the lane fails for this run
                laneFailure = e;
                return;
            }
            // the batch was rejected: retry a bounded number of texts alone so one bad input only
            // loses its own vector and the rest stay cold for their own later use
            var attempted = 0;
            for (var text : missing) {
                if (attempted == MAX_FALLBACK_TEXTS) {
                    return;
                }
                attempted++;
                try {
                    vectors.put(text, embeddingService.embed(List.of(text)).vectors().get(0));
                } catch (SemanticDecisionException ignored) {
                    if (ProviderFailures.isOutOfCredit(ignored)) {
                        laneFailure = ignored;
                        return;
                    }
                    // this text degrades on its own when a caller asks for it
                }
            }
        }
    }

    @Override
    public float[] vector(String text) {
        var cached = vectors.get(text);
        if (cached != null) {
            return cached;
        }
        if (laneFailure != null) {
            throw laneFailure;
        }
        var execution = embeddingService.embed(List.of(text));
        var vector = execution.vectors().get(0);
        vectors.put(text, vector);
        return vector;
    }
}
