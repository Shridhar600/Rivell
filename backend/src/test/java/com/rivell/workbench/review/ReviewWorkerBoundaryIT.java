package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * G-M7-4 regression: the production boundary is the JobRunr worker invoking the
 * registered handler reflectively, not {@link ReviewAnalysisService#analyze}. This
 * IT enables the real background job server over a real database, creates a review
 * through the API, and requires the worker to drive ANALYSIS_QUEUED to COMPLETED.
 * It fails on a handler class that is not publicly accessible to JobRunr.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class ReviewWorkerBoundaryIT {

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
        registry.add("jobrunr.background-job-server.enabled", () -> "true");
        registry.add("jobrunr.background-job-server.poll-interval-in-seconds", () -> "5");
        registry.add("jobrunr.background-job-server.worker-count", () -> "1");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    org.jobrunr.storage.StorageProvider storageProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
    }

    @Test
    void workerExecutesQueuedReviewThroughTheRealJobBoundary() throws Exception {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);

        var result = mockMvc.perform(post(
                        "/api/contracts/{contractId}/versions/{versionNumber}/reviews",
                        corpus.contract().contractId(), 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractPackKey\":\"MSA_PACK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANALYSIS_QUEUED"))
                .andReturn();
        var reviewId = UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asText());

        var enqueued = storageProvider.getJobList(
                org.jobrunr.jobs.states.StateName.ENQUEUED,
                new org.jobrunr.storage.navigation.AmountRequest("updatedAt:DESC", 100));
        assertThat(enqueued).anyMatch(job ->
                job.getJobSignature().contains("ReviewAnalysisJobHandler"));

        var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        ReviewStatus status = ReviewStatus.ANALYSIS_QUEUED;
        while (System.nanoTime() < deadline) {
            status = reviewRepository.find(organizationId, reviewId).orElseThrow().status();
            if (status == ReviewStatus.COMPLETED) {
                break;
            }
            if (status == ReviewStatus.FAILED) {
                break;
            }
            Thread.sleep(250);
        }
        assertThat(status)
                .as("worker must drive the queued review to COMPLETED")
                .isEqualTo(ReviewStatus.COMPLETED);
        assertThat(storageProvider.getJobList(
                org.jobrunr.jobs.states.StateName.FAILED,
                new org.jobrunr.storage.navigation.AmountRequest("updatedAt:DESC", 100)))
                .noneMatch(job -> job.getJobSignature().contains("ReviewAnalysisJobHandler"));

        var run = analysisRepository.findLatestCompletedRun(organizationId, reviewId)
                .orElseThrow();
        assertThat(findingRepository.findFindings(organizationId, run.id())).hasSize(1);
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }
}
