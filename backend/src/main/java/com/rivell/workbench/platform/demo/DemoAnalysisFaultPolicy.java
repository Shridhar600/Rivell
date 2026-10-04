package com.rivell.workbench.platform.demo;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo-only marker consulted by {@link DemoFaultingReviewRepository}. It exists so the
 * R10 scenario can drive a genuine review-level FAILED at a real service boundary instead
 * of writing a status row. The seeder marks and clears it around one synchronous analysis
 * call.
 */
final class DemoAnalysisFaultPolicy {

    private final Set<UUID> marked = ConcurrentHashMap.newKeySet();

    void failFor(UUID reviewId) {
        marked.add(reviewId);
    }

    void clear() {
        marked.clear();
    }

    boolean shouldFail(UUID reviewId) {
        return marked.contains(reviewId);
    }

    static final class InjectedAnalysisFailure extends RuntimeException {
        InjectedAnalysisFailure(UUID reviewId) {
            super("Demo-injected analysis failure for review " + reviewId);
        }
    }
}
