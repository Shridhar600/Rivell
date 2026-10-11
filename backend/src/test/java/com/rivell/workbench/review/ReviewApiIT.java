package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class ReviewApiIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String CREATE_PATH =
            "/api/contracts/{contractId}/versions/{versionNumber}/reviews";

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
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    @Test
    void createReturnsEnvelopeAndReadEndpointsExposeTheActiveRun() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());

        var created = createReview(corpus.contract().contractId(),
                ReviewPackRegistry.MSA_PACK);
        assertThat(created.get("status").asText()).isEqualTo("ANALYSIS_QUEUED");
        assertThat(created.get("contractVersionNumber").asInt()).isEqualTo(1);
        assertThat(created.get("snapshot").get("profile").get("contractPackKey").asText())
                .isEqualTo("MSA_PACK");
        assertThat(created.get("snapshot").get("packVersion").asText())
                .isEqualTo(ReviewPackRegistry.VERSION);
        assertThat(created.get("snapshot").get("knowledgePinNumber").asLong()).isPositive();
        assertThat(created.get("snapshot").get("effectiveOn").asText()).isNotBlank();
        assertThat(created.get("failure").isNull()).isTrue();
        assertThat(created.get("activeRun").isNull()).isTrue();
        assertThat(created.get("analysisWarnings").isEmpty()).isTrue();

        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organizationId(), reviewId);

        var summary = json(mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.id").value(reviewId.toString()))
                .andExpect(jsonPath("$.data.contractId")
                        .value(corpus.contract().contractId().toString()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.failure").value(nullValue()))
                .andExpect(jsonPath("$.data.activeRun.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.activeRun.runNumber").value(1))
                .andExpect(jsonPath("$.data.latestRunStatus").value(nullValue()))
                .andExpect(jsonPath("$.data.degradedChecks").value(1))
                .andExpect(jsonPath("$.data.failedChecks").value(0))
                .andExpect(jsonPath("$.data.findings").value(1))
                .andExpect(jsonPath("$.data.signals").value(3))
                .andExpect(jsonPath("$.data.analysisWarnings[0].reasonCode")
                        .value("SURVIVAL_COVERAGE_PARTIAL"))
                .andExpect(jsonPath("$.data.analysisWarningsTruncated").value(false))
                .andReturn()).get("data");
        assertThat(summary.get("completedChecks").asInt()
                + summary.get("degradedChecks").asInt())
                .isEqualTo(summary.get("plannedChecks").asInt())
                .isPositive();
        assertThat(summary.get("startedAt").isNull()).isFalse();
        assertThat(summary.get("completedAt").isNull()).isFalse();

        mockMvc.perform(get("/api/reviews/{id}/progress", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.latestRunStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.pendingChecks").value(0))
                .andExpect(jsonPath("$.data.runningChecks").value(0))
                .andExpect(jsonPath("$.data.skippedChecks").value(0))
                .andExpect(jsonPath("$.data.findings").value(1))
                .andExpect(jsonPath("$.data.signals").value(3))
                .andExpect(jsonPath("$.data.plannedChecks")
                        .value(greaterThan(0)))
                .andExpect(jsonPath("$.data.analysisWarnings[0].reasonCode")
                        .value("SURVIVAL_COVERAGE_PARTIAL"));
    }

    @Test
    void unanalyzedReviewHasNoActiveRunAndNoFindings() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var created = createReview(corpus.contract().contractId(), null);
        var reviewId = UUID.fromString(created.get("id").asText());

        mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANALYSIS_QUEUED"))
                .andExpect(jsonPath("$.data.activeRun").value(nullValue()))
                .andExpect(jsonPath("$.data.latestRunStatus").value(nullValue()))
                .andExpect(jsonPath("$.data.findings").value(0));

        mockMvc.perform(get("/api/reviews/{id}/progress", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("ANALYSIS_QUEUED"))
                .andExpect(jsonPath("$.data.latestRunStatus").value(nullValue()))
                .andExpect(jsonPath("$.data.pendingChecks").value(greaterThan(0)))
                .andExpect(jsonPath("$.data.findings").value(0));

        mockMvc.perform(get("/api/reviews/{id}/findings", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.truncated").value(false));

        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        reviewId, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
    }

    @Test
    void reviewListIsBoundedNewestFirstAndTruncated() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var contractId = corpus.contract().contractId();
        var first = createReview(contractId, null);
        var second = createReview(contractId, null);
        var third = createReview(contractId, null);

        var limited = mockMvc.perform(get("/api/reviews")
                        .param("contractId", contractId.toString())
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn();
        var items = json(limited).get("data").get("items");
        assertThat(items.get(0).get("id").asText()).isEqualTo(third.get("id").asText());
        assertThat(items.get(1).get("id").asText()).isEqualTo(second.get("id").asText());
        assertThat(items.get(0).get("id").asText()).isNotEqualTo(first.get("id").asText());

        mockMvc.perform(get("/api/reviews")
                        .param("contractId", contractId.toString())
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.items.length()").value(3));

        mockMvc.perform(get("/api/reviews")
                        .param("contractId", contractId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.items.length()").value(3));

        mockMvc.perform(get("/api/reviews")
                        .param("contractId", contractId.toString())
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void findingsListFiltersAndTruncates() throws Exception {
        var contractId = enrichmentReadyNda();
        var created = createReview(contractId, ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organizationId(), reviewId);

        mockMvc.perform(get("/api/reviews/{id}/findings", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.data.items[0].subjectKind").value("ABSENCE"))
                .andExpect(jsonPath("$.data.truncated").value(false));

        mockMvc.perform(get("/api/reviews/{id}/findings", reviewId)
                        .param("severity", "LOW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].concept").value("INDEMNIFICATION"))
                .andExpect(jsonPath("$.data.items[0].reviewClassification")
                        .value("INFORMATIONAL"));

        mockMvc.perform(get("/api/reviews/{id}/findings", reviewId)
                        .param("classification", "BLOCKING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].concept")
                        .value("LIMITATION_OF_LIABILITY"));

        mockMvc.perform(get("/api/reviews/{id}/findings", reviewId)
                        .param("concept", "LIMITATION_OF_LIABILITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].issueFamily")
                        .value("MISSING_REQUIRED_PROVISION"));

        mockMvc.perform(get("/api/reviews/{id}/findings", reviewId)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.data.truncated").value(true));
    }

    @Test
    void clauseFindingDetailExposesClaimsEvidenceAndProvenance() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var created = createReview(corpus.contract().contractId(),
                ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organizationId(), reviewId);
        var findingId = listFindings(reviewId).get(0).get("id").asText();

        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        reviewId, findingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjectKind").value("CLAUSE"))
                .andExpect(jsonPath("$.data.subjectHeadingNodeId").isString())
                .andExpect(jsonPath("$.data.absenceConcept").value(nullValue()))
                .andExpect(jsonPath("$.data.concept").value("LIMITATION_OF_LIABILITY"))
                .andExpect(jsonPath("$.data.issueFamily").value("POSITION_NEEDS_FALLBACK"))
                .andExpect(jsonPath("$.data.observationKind")
                        .value("POSITION_BEYOND_FALLBACK"))
                .andExpect(jsonPath("$.data.observationText").isNotEmpty())
                .andExpect(jsonPath("$.data.severity").value("MEDIUM"))
                .andExpect(jsonPath("$.data.reviewClassification")
                        .value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.proposedAction")
                        .value("USE_APPROVED_FALLBACK"))
                .andExpect(jsonPath("$.data.proposedLanguage")
                        .value(containsString("two times the fees")))
                .andExpect(jsonPath("$.data.proposedLanguageSource")
                        .value("APPROVED_DRAFTING"))
                .andExpect(jsonPath("$.data.approvedDraftingEvidenceId").isString())
                .andExpect(jsonPath("$.data.claims[0].claimKind")
                        .value("OBSERVATION"))
                .andExpect(jsonPath("$.data.claims[0].absence").value(false))
                .andExpect(jsonPath("$.data.claims[0].evidenceItemIds").isNotEmpty())
                .andExpect(jsonPath("$.data.claims[?(@.claimKind == 'RECOMMENDATION')]")
                        .exists())
                .andExpect(jsonPath("$.data.evidenceItemIds").isNotEmpty())
                .andExpect(jsonPath("$.data.signalIds.length()").value(3))
                .andExpect(jsonPath("$.data.quality").exists())
                .andExpect(jsonPath("$.data.absenceBasis").value(nullValue()));
    }

    @Test
    void absenceFindingDetailExposesPlanCheckBasis() throws Exception {
        var contractId = enrichmentReadyNda();
        var created = createReview(contractId, ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organizationId(), reviewId);

        JsonNode missing = null;
        for (var item : listFindings(reviewId)) {
            if ("LIMITATION_OF_LIABILITY".equals(item.get("absenceConcept").asText())) {
                missing = item;
            }
        }
        assertThat(missing).isNotNull();

        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        reviewId, missing.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjectKind").value("ABSENCE"))
                .andExpect(jsonPath("$.data.subjectHeadingNodeId").value(nullValue()))
                .andExpect(jsonPath("$.data.absenceConcept")
                        .value("LIMITATION_OF_LIABILITY"))
                .andExpect(jsonPath("$.data.issueFamily")
                        .value("MISSING_REQUIRED_PROVISION"))
                .andExpect(jsonPath("$.data.proposedAction")
                        .value("ADD_REQUIRED_PROVISION"))
                .andExpect(jsonPath("$.data.proposedLanguageSource").value("NONE"))
                .andExpect(jsonPath("$.data.absenceBasis.planCheckId").isString())
                .andExpect(jsonPath("$.data.absenceBasis.checkKey")
                        .value("PRESENCE:LIMITATION_OF_LIABILITY"))
                .andExpect(jsonPath("$.data.claims[0].claimKind")
                        .value("ABSENCE_BASIS"))
                .andExpect(jsonPath("$.data.claims[0].absence").value(true))
                .andExpect(jsonPath("$.data.claims[0].evidenceItemIds").isEmpty());
    }

    @Test
    void crossOrganizationAccessIsNotFoundForEveryEndpoint() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var created = createReview(corpus.contract().contractId(),
                ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organizationId(), reviewId);
        var findingId = listFindings(reviewId).get(0).get("id").asText();

        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));

        mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
        mockMvc.perform(get("/api/reviews/{id}/progress", reviewId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
        mockMvc.perform(get("/api/reviews/{id}/findings", reviewId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        reviewId, findingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
        mockMvc.perform(get("/api/reviews")
                        .param("contractId", corpus.contract().contractId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
        mockMvc.perform(post(CREATE_PATH, corpus.contract().contractId(), 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
    }

    @Test
    void missingPermissionIsForbiddenForEveryEndpoint() throws Exception {
        var organization = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organization);
        var created = createReview(corpus.contract().contractId(),
                ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organization, reviewId);
        var findingId = listFindings(reviewId).get(0).get("id").asText();

        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), organization, Set.of("VIEWER")));

        mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403"));
        mockMvc.perform(get("/api/reviews/{id}/progress", reviewId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403"));
        mockMvc.perform(get("/api/reviews/{id}/findings", reviewId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403"));
        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        reviewId, findingId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403"));
        mockMvc.perform(get("/api/reviews")
                        .param("contractId", corpus.contract().contractId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403"));
        mockMvc.perform(post(CREATE_PATH, corpus.contract().contractId(), 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403"));
    }

    @Test
    void createRejectsUnknownAndConflictingPacksWith400() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());

        mockMvc.perform(post(CREATE_PATH, corpus.contract().contractId(), 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractPackKey\":\"UNKNOWN_PACK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.title").value("Invalid review configuration"));

        enrichmentService.enrich(corpus.contract().contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        mockMvc.perform(post(CREATE_PATH, corpus.contract().contractId(), 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractPackKey\":\"" + ReviewPackRegistry.NDA_PACK
                                + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void createRejectsEnrichmentThatIsNotReadyAndUnknownVersions() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var versionId = corpus.semantics().contractVersionId();

        updateEnrichmentStatus(versionId, "ENRICHING");
        mockMvc.perform(post(CREATE_PATH, corpus.contract().contractId(), 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));

        updateEnrichmentStatus(versionId, "FAILED");
        mockMvc.perform(post(CREATE_PATH, corpus.contract().contractId(), 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));

        updateEnrichmentStatus(versionId, "READY");
        mockMvc.perform(post(CREATE_PATH, UUID.randomUUID(), 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
        mockMvc.perform(post(CREATE_PATH, corpus.contract().contractId(), 99)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
    }

    @Test
    void analysisWarningsReportDegradedChecks() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var created = createReview(corpus.contract().contractId(),
                ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());

        enrichmentService.enrich(corpus.contract().contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.NDA_PACK));
        analysisService.analyze(organizationId(), reviewId);

        var summary = json(mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.analysisWarnings").isNotEmpty())
                .andExpect(jsonPath("$.data.analysisWarningsTruncated").value(false))
                .andExpect(jsonPath("$.data.analysisWarnings[0].status")
                        .value("DEGRADED"))
                .andExpect(jsonPath("$.data.analysisWarnings[0].checkKey").isString())
                .andExpect(jsonPath("$.data.analysisWarnings[0].reasonCode")
                        .value("SEMANTICS_CHANGED"))
                .andExpect(jsonPath("$.data.analysisWarnings[0].message")
                        .value(containsString("semantics changed")))
                .andReturn()).get("data");
        assertThat(summary.get("degradedChecks").asInt())
                .isEqualTo(summary.get("plannedChecks").asInt())
                .isPositive();

        mockMvc.perform(get("/api/reviews/{id}/progress", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisWarnings").isNotEmpty())
                .andExpect(jsonPath("$.data.analysisWarnings[0].reasonCode")
                        .value("SEMANTICS_CHANGED"));
    }

    @Test
    void summaryExposesLatestRunStatusWhenARecomputeFails() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var created = createReview(corpus.contract().contractId(),
                ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organizationId(), reviewId);

        jdbcClient.sql("update contract_semantics set pipeline_version = '' "
                        + "where contract_version_id = :id")
                .param("id", corpus.semantics().contractVersionId()).update();
        assertThatThrownBy(() -> analysisService.recompute(organizationId(), reviewId))
                .isInstanceOf(RuntimeException.class);

        mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.activeRun.runNumber").value(1))
                .andExpect(jsonPath("$.data.activeRun.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.latestRunStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.findings").value(1));

        mockMvc.perform(get("/api/reviews/{id}/progress", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.latestRunStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.findings").value(1));
    }


    @Test
    void findingDetailResolvesExactFrozenEvidenceWithClaimLinkage() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var created = createReview(corpus.contract().contractId(),
                ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organizationId(), reviewId);

        var findingId = listFindings(reviewId).get(0).get("id").asText();
        var response = mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        reviewId, findingId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var detail = objectMapper.readTree(response).get("data");

        var evidenceItems = detail.get("evidenceItems");
        assertThat(evidenceItems.size()).isPositive();
        System.out.println("M7-EVIDENCE-OBSERVATION items=" + evidenceItems.size()
                + " claims=" + detail.get("claims").size()
                + " responseBytes=" + response.length());
        var evidenceIds = new java.util.HashSet<String>();
        evidenceItems.forEach(item -> {
            evidenceIds.add(item.get("evidenceId").asText());
            assertThat(item.get("authorityClass").asText()).isNotBlank();
            assertThat(item.get("text").asText()).isNotBlank();
            assertThat(item.get("legalConcept").asText()).isNotBlank();
        });
        detail.get("evidenceItemIds").forEach(id -> {
            assertThat(evidenceIds).contains(id.asText());
        });
        for (var claim : detail.get("claims")) {
            for (var id : claim.get("evidenceItemIds")) {
                assertThat(evidenceIds).contains(id.asText());
            }
        }
        assertThat(evidenceItems).anySatisfy(item -> {
            assertThat(item.get("authorityClass").asText()).isEqualTo("POLICY");
            assertThat(item.get("publicationNumber").isNull()).isFalse();
            assertThat(item.get("policyFacts").isNull()).isFalse();
            assertThat(item.get("policyFacts").get("positions").size()).isPositive();
        });
    }

    @Test
    void findingEvidenceStaysFrozenAfterNewerKnowledgePublication() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var created = createReview(corpus.contract().contractId(),
                ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organizationId(), reviewId);
        var findingId = listFindings(reviewId).get(0).get("id").asText();

        var before = json(mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        reviewId, findingId))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("evidenceItems");
        assertThat(before.size()).isPositive();

        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.LIBRARY_UK,
                M6EvidenceCorpus.msaManifest2026(), null);

        var after = json(mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        reviewId, findingId))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("evidenceItems");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void crossClauseFindingEvidenceExposesBothClauseSources() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m7-msa-carveout-conflict.docx"));
        var ingested = contractIngestionService.ingest("m7-msa-carveout-conflict.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(ingested.contractId(), 1, null);
        analysisService.analyze(organizationId(), review.id());

        var findings = listFindings(review.id());
        var carveFinding = findings.get(0);
        var detail = json(mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        review.id(), carveFinding.get("id").asText()))
                .andExpect(status().isOk())
                .andReturn()).get("data");
        var subjectClauseId = detail.get("subjectHeadingNodeId").asText();
        var sourceClauseIds = new java.util.HashSet<String>();
        detail.get("evidenceItems").forEach(item -> {
            if (!item.get("sourceClauseId").isNull()) {
                sourceClauseIds.add(item.get("sourceClauseId").asText());
            }
        });
        assertThat(sourceClauseIds).isNotEmpty();
        var semantics = enrichmentService.getSemantics(ingested.contractId(), 1);
        var subjectClause = semantics.clauses().stream()
                .filter(clause -> clause.headingNodeId().toString().equals(subjectClauseId))
                .findFirst().orElseThrow();
        assertThat(sourceClauseIds).contains(subjectClause.id().toString());
    }

    @Test
    void missingPersistedEvidenceFailsLoudlyAndSafely() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var created = createReview(corpus.contract().contractId(),
                ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organizationId(), reviewId);
        var findingId = listFindings(reviewId).get(0).get("id").asText();
        jdbcClient.sql("delete from review_evidence_item where organization_id = :org")
                .param("org", organizationId()).update();

        var raw = mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        reviewId, findingId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("500"))
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("Exception");
        assertThat(raw).doesNotContain(".java");
        assertThat(raw).doesNotContain("ReviewEvidenceIntegrity");
    }

    @Test
    void packCatalogListsCurrentlySelectablePacks() throws Exception {
        var catalog = json(mockMvc.perform(get("/api/reviews/packs"))
                .andExpect(status().isOk())
                .andReturn()).get("data");

        var packs = catalog.get("packs");
        assertThat(packs.size()).isEqualTo(2);
        var keys = new java.util.HashSet<String>();
        packs.forEach(pack -> {
            keys.add(pack.get("key").asText());
            assertThat(pack.get("displayName").asText()).isNotBlank();
            assertThat(pack.get("summary").asText()).isNotBlank();
            assertThat(pack.get("version").asText())
                    .isEqualTo(ReviewPackRegistry.VERSION);
        });
        assertThat(keys).containsExactlyInAnyOrder(
                ReviewPackRegistry.MSA_PACK, ReviewPackRegistry.NDA_PACK);
        assertThat(packs.get(0).get("key").asText()).isEqualTo(ReviewPackRegistry.MSA_PACK);
        assertThat(packs.get(1).get("key").asText()).isEqualTo(ReviewPackRegistry.NDA_PACK);
    }

    @Test
    void progressExposesSecondaryOnlyCoverage() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m7-msa-secondary-conf.docx"));
        var ingested = contractIngestionService.ingest("m7-msa-secondary-conf.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(ingested.contractId(), 1, null);
        analysisService.analyze(organizationId(), review.id());

        var progress = json(mockMvc.perform(get("/api/reviews/{id}/progress", review.id()))
                .andExpect(status().isOk())
                .andReturn()).get("data");

        assertThat(progress.get("coverage")).anySatisfy(entry -> {
            assertThat(entry.get("concept").asText()).isEqualTo("CONFIDENTIALITY_OBLIGATIONS");
            assertThat(entry.get("expectation").asText()).isEqualTo("REQUIRED");
            assertThat(entry.get("coverage").asText())
                    .isEqualTo("PRESENT_SECONDARY_ONLY");
        });
        assertThat(progress.get("coverage")).anySatisfy(entry -> {
            assertThat(entry.get("concept").asText()).isEqualTo("TERM");
            assertThat(entry.get("coverage").asText()).isEqualTo("PRESENT_PRIMARY");
        });
    }

    @Test
    void failedReviewExposesSanitizedFailureAndKeepsInternalDiagnostics() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var created = createReview(corpus.contract().contractId(),
                ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        jdbcClient.sql("""
                update contract_semantics set pipeline_version = ''
                where contract_version_id = :id
                """).param("id", corpus.contract().contractVersionId()).update();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> analysisService.analyze(organizationId(), reviewId))
                .isInstanceOf(RuntimeException.class);

        var raw = mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failure.code").value("ANALYSIS_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.data.failure.message").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("Exception");
        assertThat(raw).doesNotContain(".java");
        assertThat(raw).doesNotContain("SECRET");

        var internal = reviewRepository.find(organizationId(), reviewId)
                .orElseThrow().failureReason();
        assertThat(internal).contains("@");
    }

    private JsonNode createReview(UUID contractId, String packKey) throws Exception {
        var body = packKey == null
                ? "{}"
                : "{\"contractPackKey\":\"" + packKey + "\"}";
        var result = mockMvc.perform(post(CREATE_PATH, contractId, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andReturn();
        return json(result).get("data");
    }

    private JsonNode listFindings(UUID reviewId) throws Exception {
        var result = mockMvc.perform(get("/api/reviews/{id}/findings", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data").get("items");
    }

    private UUID enrichmentReadyNda() throws Exception {
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.NDA, PartyRole.RECEIVING_PARTY,
                        PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        return ingested.contractId();
    }

    private void updateEnrichmentStatus(UUID versionId, String status) {
        jdbcClient.sql("""
                update contract_semantics set enrichment_status = :status,
                    enrichment_failure_reason = :reason
                where contract_version_id = :versionId
                """)
                .param("reason", "FAILED".equals(status) ? "INTERNAL" : null)
                .param("status", status)
                .param("versionId", versionId)
                .update();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
