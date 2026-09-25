package com.rivell.workbench.platform.jobs;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.rivell.workbench.review.ReviewAnalysisService;

/** JobRunr-resolvable bean entry point for asynchronous review analysis. */
@Component
class ReviewAnalysisJobHandler {

    private final ReviewAnalysisService analysisService;

    ReviewAnalysisJobHandler(ReviewAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    public void handle(UUID organizationId, UUID reviewId) {
        analysisService.analyze(organizationId, reviewId);
    }
}
