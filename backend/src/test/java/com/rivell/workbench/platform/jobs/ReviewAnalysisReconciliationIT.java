package com.rivell.workbench.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.navigation.AmountRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.review.LiabilityReviewIT;
import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewService;

/**
 * Final review C1: a committed review can lose its after-commit enqueue, so a recurring reconciler
 * re-enqueues reviews that stay queued. The analysis job id is derived from the review, so a second
 * pass collapses onto the existing job instead of running the review twice.
 */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class ReviewAnalysisReconciliationIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    ReviewAnalysisReconciler reconciler;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    StorageProvider storageProvider;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void reconciliationIsRegisteredAsARecurringJob() {
        assertThat(storageProvider.getRecurringJobs())
                .extracting(org.jobrunr.jobs.RecurringJob::getId)
                .contains(ReviewAnalysisReconciliationTrigger.JOB_ID);
    }

    @Test
    void aStaleQueuedReviewIsEnqueuedOnceWhileFreshAndRunningReviewsAreIgnored() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var stale = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var fresh = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var analyzing = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        // The failure being recovered: the review committed and the enqueue write was lost.
        for (var review : List.of(stale, fresh, analyzing)) {
            storageProvider.deletePermanently(jobId(review));
            assertThat(enqueuedJobs(review)).isEmpty();
        }

        backdate(stale, analyzing);
        assertThat(reviewRepository.markAnalyzing(organizationId, analyzing.id(), Instant.now()))
                .isTrue();

        assertThat(reconciler.reconcile())
                .as("only the stale queued review is re-enqueued")
                .isEqualTo(1);
        assertThat(enqueuedJobs(stale)).hasSize(1);
        assertThat(enqueuedJobs(fresh)).isEmpty();
        assertThat(enqueuedJobs(analyzing)).isEmpty();

        // A further pass sees a live job and leaves it alone: one review, one analysis job.
        assertThat(reconciler.reconcile()).isZero();
        assertThat(enqueuedJobs(stale))
                .as("re-enqueueing the same review stays one job")
                .hasSize(1);

        // A job that failed for good keeps its id in storage, which would block every later
        // enqueue. The reconciler replaces it instead of leaving the review queued forever.
        storageProvider.save(storageProvider.getJobById(jobId(stale))
                .failed("retries exhausted", new RuntimeException("boom")));
        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(enqueuedJobs(stale)).hasSize(1);
    }

    private void backdate(Review... reviews) {
        for (var review : reviews) {
            jdbcClient.sql("""
                    update review set created_at = created_at - interval '10 minutes'
                    where id = :reviewId
                    """)
                    .param("reviewId", review.id())
                    .update();
        }
    }

    private static UUID jobId(Review review) {
        return ReviewAnalysisTrigger.jobIdFor(review.organizationId(), review.id());
    }

    private List<Job> enqueuedJobs(Review review) {
        var jobId = jobId(review);
        return storageProvider.getJobList(StateName.ENQUEUED,
                        new AmountRequest("updatedAt:ASC", 100))
                .stream()
                .filter(job -> job.getId().equals(jobId))
                .toList();
    }
}
