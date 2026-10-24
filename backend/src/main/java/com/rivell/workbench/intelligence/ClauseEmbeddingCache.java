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

    private final EmbeddingService embeddingService;
    private final Map<String, float[]> vectors = new HashMap<>();

    public ClauseEmbeddingCache(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * Embeds every text not already cached in one batch. A failure leaves the cache empty and
     * is not fatal: the first later use retries through the ordinary path and degrades honestly.
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
        } catch (ProviderOutOfCreditException e) {
            // a dry account fails every text the same way; the short circuit keeps it cheap
        } catch (SemanticDecisionException e) {
            // the batch failed: retry each text alone so one bad input only loses its own vector
            for (var text : missing) {
                try {
                    vectors.put(text, embeddingService.embed(List.of(text)).vectors().get(0));
                } catch (ProviderOutOfCreditException dry) {
                    return;
                } catch (SemanticDecisionException ignored) {
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
        var execution = embeddingService.embed(List.of(text));
        var vector = execution.vectors().get(0);
        vectors.put(text, vector);
        return vector;
    }
}
