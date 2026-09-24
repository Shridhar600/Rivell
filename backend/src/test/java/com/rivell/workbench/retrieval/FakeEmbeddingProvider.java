package com.rivell.workbench.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.rivell.workbench.intelligence.EmbeddingBatch;
import com.rivell.workbench.intelligence.EmbeddingProvider;
import com.rivell.workbench.intelligence.EmbeddingRequest;
import com.rivell.workbench.intelligence.TokenUsage;

/**
 * Deterministic test embedding provider with controlled geometry: fixture texts
 * map to a small orthonormal basis so vector lanes are deliberately strong, weak,
 * or tied per labeled scenario. Hash vectors (stable pseudo-random unit vectors)
 * fill everything else. This proves retrieval mechanics — never real embedding
 * quality.
 */
public final class FakeEmbeddingProvider implements EmbeddingProvider {

    public static final String PROVIDER_ID = "fake";
    public static final String MODEL = "fake-embedding-model";
    public static final int DIMENSIONS = 8;

    /** Registered anchor: exact input text → deterministic vector. */
    private final Map<String, float[]> anchors = new ConcurrentHashMap<>();

    public void anchor(String text, float[] vector) {
        anchors.put(text, vector.clone());
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public EmbeddingBatch embed(EmbeddingRequest request) {
        var vectors = new ArrayList<float[]>(request.inputs().size());
        for (var input : request.inputs()) {
            var anchor = anchors.get(input);
            vectors.add(anchor != null ? anchor.clone() : geometry(input));
        }
        return new EmbeddingBatch(vectors, MODEL, new TokenUsage(request.inputs().size(), 0));
    }

    /**
     * Fixture-anchored geometry: the MSA liability subject, the strongly relevant
     * precedent, the paraphrase precedent, and the policy-incompatible precedent all
     * map to the same direction e0; the exact-terminology lexical fixture maps to an
     * unrelated direction e1. Vector-weak and lexical-weak lanes are therefore
     * deliberately testable.
     */
    private float[] geometry(String text) {
        var lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("as measured at the time of the claim")) {
            return basis(1);
        }
        if (lower.contains("uncapped and unlimited")) {
            return basis(2);
        }
        if (lower.contains("fees paid in the preceding 12 months")
                || lower.contains("total aggregate liability")) {
            return basis(0);
        }
        if (lower.contains("capped at one year of the amounts invoiced")) {
            var scaled = basis(0);
            scaled[0] = 0.98f;
            return scaled;
        }
        return hashVector(text);
    }

    private float[] hashVector(String text) {
        var seed = text.hashCode();
        var vector = new float[DIMENSIONS];
        long state = 0x9E3779B97F4A7C15L ^ seed;
        for (int i = 0; i < DIMENSIONS; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            vector[i] = (float) ((state % 2000) / 1000.0 - 1.0);
        }
        return normalize(vector);
    }

    private static float[] normalize(float[] vector) {
        double sum = 0.0;
        for (var value : vector) {
            sum += value * value;
        }
        var norm = Math.sqrt(sum);
        if (norm == 0.0) {
            vector[0] = 1.0f;
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }

    /** Orthonormal basis vector e(index) over DIMENSIONS. */
    public static float[] basis(int index) {
        var vector = new float[DIMENSIONS];
        vector[index] = 1.0f;
        return vector;
    }

}
