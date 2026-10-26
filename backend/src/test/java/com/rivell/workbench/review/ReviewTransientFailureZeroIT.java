package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * With {@code max-transient-failures=0} the first transient infrastructure failure is terminal:
 * the run and the review fail instead of JobRunr retrying the same run.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "rivell.review.max-transient-failures=0")
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class,
        ReviewResumeIT.FaultInjectionConfiguration.class})
class ReviewTransientFailureZeroIT {

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
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    ReviewResumeIT.CompleteExecutionFault fault;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        fault.reset();
    }

    @Test
    void theFirstTransientFailureFailsTheRunAndTheReview() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(corpus.contract().contractId(), 1, null);

        fault.failOnCall(1, new TransientDataAccessResourceException("db blip"));

        assertThatThrownBy(() -> analysisService.analyze(organizationId, review.id()))
                .isInstanceOf(TransientDataAccessResourceException.class);

        var failedRun = analysisRepository.findLatestRun(organizationId, review.id())
                .orElseThrow();
        assertThat(failedRun.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failedRun.transientFailures()).isEqualTo(1);
        assertThat(failedRun.failureReason()).contains("TransientDataAccessResourceException");
        var failedReview = reviewRepository.find(organizationId, review.id()).orElseThrow();
        assertThat(failedReview.status()).isEqualTo(ReviewStatus.FAILED);
        assertThat(failedReview.failureReason()).contains("TransientDataAccessResourceException");
    }
}
