package com.rivell.workbench.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * M10 / B-4: the review-analysis job id must be derived from the work, not random.
 *
 * <p>A random id meant a duplicated trigger - a retried event, a second listener, a double submit -
 * created a second job. The work was already idempotent at the domain boundary, so the duplicate was
 * waste and unattributable load rather than corruption, which is why it went unnoticed. The key is
 * also scoped by organisation, so one tenant's review can never collide with another's.
 */
class ReviewAnalysisTriggerTest {

    @Test
    void theSameReviewAlwaysMapsToTheSameJob() {
        var organization = UUID.randomUUID();
        var review = UUID.randomUUID();

        assertThat(ReviewAnalysisTrigger.jobIdFor(organization, review))
                .isEqualTo(ReviewAnalysisTrigger.jobIdFor(organization, review));
    }

    @Test
    void differentWorkMapsToDifferentJobs() {
        var organization = UUID.randomUUID();
        var review = UUID.randomUUID();

        assertThat(ReviewAnalysisTrigger.jobIdFor(organization, UUID.randomUUID()))
                .as("a different review must not share a job")
                .isNotEqualTo(ReviewAnalysisTrigger.jobIdFor(organization, review));
        assertThat(ReviewAnalysisTrigger.jobIdFor(UUID.randomUUID(), review))
                .as("the key must be scoped by organisation")
                .isNotEqualTo(ReviewAnalysisTrigger.jobIdFor(organization, review));
    }
}
