package com.rivell.workbench.platform.jobs;

import java.util.UUID;

/**
 * The analysis call returned while its run was still RUNNING. A JobRunr retry will resume the run;
 * a fresh lease held by a worker that died (or a retry that arrived before the lease expired) means
 * "not finished yet", not "finished".
 */
public class ReviewAnalysisIncompleteException extends RuntimeException {

    public ReviewAnalysisIncompleteException(UUID reviewId, UUID runId) {
        super("Review analysis is incomplete: review " + reviewId + " run " + runId);
    }
}
