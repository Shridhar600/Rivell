package com.rivell.workbench.review.check;

/**
 * Whether the live analysis environment still matches the frozen snapshot.
 * Mismatches degrade affected checks instead of silently analyzing changed inputs.
 */
public record AnalysisEnvironment(
        boolean semanticsDigestMatches,
        boolean embeddingIndexMatches,
        boolean embeddingModelMatches) {

    public boolean fullyMatches() {
        return semanticsDigestMatches && embeddingIndexMatches && embeddingModelMatches;
    }

    public String mismatchReason() {
        if (!semanticsDigestMatches) {
            return "contract semantics changed after the snapshot was frozen";
        }
        if (!embeddingIndexMatches) {
            return "retrieval index version changed after the snapshot was frozen";
        }
        if (!embeddingModelMatches) {
            return "embedding model identity changed after the snapshot was frozen";
        }
        return null;
    }
}
