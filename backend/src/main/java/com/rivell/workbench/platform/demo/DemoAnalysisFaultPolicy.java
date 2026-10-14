package com.rivell.workbench.platform.demo;

import java.util.UUID;

/**
 * Demo-only marker consulted by {@link DemoFaultingReviewRepository}. It exists so the
 * R10 scenario can drive a genuine review-level FAILED at a real service boundary instead
 * of writing a status row. The marker is thread-scoped and the seeder sets it immediately
 * around one synchronous analysis call, so no other request can observe it.
 */
final class DemoAnalysisFaultPolicy {

    private final ThreadLocal<UUID> marked = new ThreadLocal<>();

    void failFor(UUID reviewId) {
        marked.set(reviewId);
    }

    void clear() {
        marked.remove();
    }

    boolean shouldFail(UUID reviewId) {
        return reviewId.equals(marked.get());
    }

    /**
     * A plain runtime failure, deliberately named and worded like an internal read failure
     * rather than a demo artefact: the analysis failure reason travels to the diagnostics
     * surface, and the demo seam must not advertise itself there.
     */
    static final class ReviewPlanUnavailableFailure extends RuntimeException {
        ReviewPlanUnavailableFailure() {
            super("The review plan could not be read.");
        }
    }
}
