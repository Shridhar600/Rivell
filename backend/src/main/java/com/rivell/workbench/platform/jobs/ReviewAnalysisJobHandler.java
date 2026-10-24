package com.rivell.workbench.platform.jobs;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.RunStatus;

/** JobRunr-resolvable bean entry point for asynchronous review analysis. */
@Component
public class ReviewAnalysisJobHandler {

    private final ReviewAnalysisService analysisService;

    ReviewAnalysisJobHandler(ReviewAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    public void handle(UUID organizationId, UUID reviewId) {
        var run = analysisService.analyze(organizationId, reviewId);
        if (run.status() == RunStatus.RUNNING) {
            // A fresh lease on a check means this attempt skipped work another worker (or a dead
            // worker's lease) still owns. Returning here would let JobRunr mark the job SUCCEEDED
            // and no retry would ever finish the run, so surface it as a retryable failure.
            throw new ReviewAnalysisIncompleteException(reviewId, run.id());
        }
    }
}
