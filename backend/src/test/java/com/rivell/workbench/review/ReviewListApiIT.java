package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Behaviour of the review list at the HTTP boundary (GAP-A).
 *
 * <p>Covers the two things the milestone exists to change: {@code contractId} becomes
 * optional so Home can read the organization without fanning out over contracts, and each
 * row now carries enough truth to render on its own.
 *
 * <p>The list assembles its progress numbers with its own SQL aggregates while the
 * summary endpoint counts in Java. Those are two implementations of the same five numbers,
 * so {@link #listRowAgreesWithTheSummaryAndProgressEndpoints} pins them together over a
 * completed run, and {@link #anInFlightReviewReportsTheSameLatestRunStatusAsItsSummary}
 * pins the generation resolution when no generation has completed. The multi-generation
 * case, where a newer generation exists above the counted one, is covered at repository
 * level in {@code JdbcReviewListQueryIT} rather than here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class ReviewListApiIT {

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
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    @Test
    void contractIdIsOptionalAndAnAbsentFilterReturnsTheWholeOrganization() throws Exception {
        var first = createContract("first.docx");
        var second = createContract("second.docx");
        createReview(first);
        createReview(second);

        mockMvc.perform(get("/api/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void contractScopedListingIsUnchanged() throws Exception {
        var first = createContract("first.docx");
        var second = createContract("second.docx");
        createReview(first);
        createReview(first);
        createReview(second);

        var items = json(mockMvc.perform(get("/api/reviews")
                        .param("contractId", first.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn()).get("data").get("items");

        assertThat(items).allSatisfy(item -> assertThat(item.get("contractId").asText())
                .isEqualTo(first.toString()));
    }

    @Test
    void everyRowCarriesTheOriginalFieldsPlusItsPinnedContractIdentity() throws Exception {
        var contractId = createContract("pinned.docx");
        var reviewId = createReview(contractId);

        var item = json(mockMvc.perform(get("/api/reviews"))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("items").get(0);

        assertThat(item.get("id").asText()).isEqualTo(reviewId.toString());
        assertThat(item.get("contractId").asText()).isEqualTo(contractId.toString());
        assertThat(item.get("contractVersionNumber").asInt()).isEqualTo(1);
        assertThat(item.get("contractFilename").asText()).isEqualTo("pinned.docx");
        assertThat(item.get("status").asText()).isNotBlank();
        assertThat(item.get("createdAt").asText()).isNotBlank();
    }

    @Test
    void aRowIsRenderableWithoutAnyFollowUpRequest() throws Exception {
        createContract("solo.docx");
        createReview(createContract("another.docx"));

        var item = json(mockMvc.perform(get("/api/reviews"))
                .andReturn()).get("data").get("items").get(0);

        // Everything a Home row needs, present from this one response.
        assertThat(item.has("contractFilename")).isTrue();
        assertThat(item.has("plannedChecks")).isTrue();
        assertThat(item.has("completedChecks")).isTrue();
        assertThat(item.has("degradedChecks")).isTrue();
        assertThat(item.has("failedChecks")).isTrue();
        assertThat(item.has("findings")).isTrue();
        assertThat(item.has("signals")).isTrue();
    }

    @Test
    void listRowAgreesWithTheSummaryAndProgressEndpoints() throws Exception {
        var contractId = createContract("agreement.docx");
        var reviewId = createReview(contractId);
        reviewAnalysisService.analyze(organizationId(), reviewId);

        var item = json(mockMvc.perform(get("/api/reviews").param("limit", "50"))
                .andReturn()).get("data").get("items").get(0);
        var summary = json(mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andReturn()).get("data");
        var progress = json(mockMvc.perform(get("/api/reviews/{id}/progress", reviewId))
                .andReturn()).get("data");

        assertThat(item.get("id").asText()).isEqualTo(reviewId.toString());
        assertThat(item.get("status").asText()).isEqualTo(summary.get("status").asText());
        assertThat(item.get("plannedChecks").asInt())
                .isEqualTo(summary.get("plannedChecks").asInt());
        assertThat(item.get("completedChecks").asInt())
                .isEqualTo(summary.get("completedChecks").asInt());
        assertThat(item.get("degradedChecks").asInt())
                .isEqualTo(summary.get("degradedChecks").asInt());
        assertThat(item.get("failedChecks").asInt())
                .isEqualTo(summary.get("failedChecks").asInt());
        assertThat(item.get("findings").asInt()).isEqualTo(summary.get("findings").asInt());
        assertThat(item.get("signals").asInt()).isEqualTo(summary.get("signals").asInt());
        assertThat(item.get("startedAt").asText()).isEqualTo(summary.get("startedAt").asText());
        assertThat(item.get("completedAt").asText())
                .isEqualTo(summary.get("completedAt").asText());
        assertThat(item.get("plannedChecks").asInt())
                .isEqualTo(progress.get("plannedChecks").asInt());
        assertThat(item.get("completedChecks").asInt())
                .isEqualTo(progress.get("completedChecks").asInt());
    }

    @Test
    void anInFlightReviewReportsTheSameLatestRunStatusAsItsSummary() throws Exception {
        var contractId = createContract("inflight.docx");
        var reviewId = createReview(contractId);

        // A queued review has a run that is neither completed nor absent. The list must
        // surface it exactly as the summary does, or an in-flight review would look idle
        // in the list and running in its detail.
        var runId = seedRunningRun(reviewId);

        var item = json(mockMvc.perform(get("/api/reviews"))
                .andReturn()).get("data").get("items").get(0);
        var summary = json(mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andReturn()).get("data");

        assertThat(summary.get("latestRunStatus").asText()).isEqualTo("RUNNING");
        assertThat(summary.get("activeRun").isNull())
                .as("no generation has completed, so there is no active run to report")
                .isTrue();
        assertThat(item.get("latestRunStatus").asText())
                .as("with no completed run the latest run is the counted one and must still "
                        + "be reported, matching the summary endpoint exactly")
                .isEqualTo(summary.get("latestRunStatus").asText());
        assertThat(item.get("activeRunStatus").isNull())
                .as("and the list mirrors the summary's absent active run rather than "
                        + "inventing one from the running generation")
                .isTrue();
        assertThat(item.get("activeRunNumber").isNull()).isTrue();
        assertThat(runId).isNotNull();
    }

    @Test
    void aFailedRowCarriesThePublicFailureCode() throws Exception {
        var contractId = createContract("failed.docx");
        var reviewId = createReview(contractId);
        jdbcClient.sql("""
                update review
                set status = 'FAILED', failure_reason = 'decision_contract_error:boom',
                    completed_at = now()
                where id = :id
                """).param("id", reviewId).update();

        var item = json(mockMvc.perform(get("/api/reviews"))
                .andReturn()).get("data").get("items").get(0);

        assertThat(item.get("status").asText()).isEqualTo("FAILED");
        assertThat(item.get("failure").get("code").asText()).isEqualTo("DECISION_CONTRACT_ERROR");
        assertThat(item.get("failure").get("message").asText()).doesNotContain("boom");
    }

    @Test
    void orderingIsNewestFirstAndDeterministic() throws Exception {
        createReview(createContract("a.docx"));
        createReview(createContract("b.docx"));
        createReview(createContract("c.docx"));

        var first = listedIds();
        var second = listedIds();

        assertThat(first).hasSize(3);
        assertThat(second)
                .as("two identical calls must produce an identical order")
                .isEqualTo(first);

        var timestamps = json(mockMvc.perform(get("/api/reviews"))
                .andReturn()).get("data").get("items");
        for (var i = 1; i < timestamps.size(); i++) {
            assertThat(timestamps.get(i - 1).get("createdAt").asText())
                    .isGreaterThanOrEqualTo(timestamps.get(i).get("createdAt").asText());
        }
    }

    @Test
    void cursorWalksTheWholeOrganizationWithoutDuplicatingOrSkipping() throws Exception {
        var expected = new ArrayList<UUID>();
        for (var i = 0; i < 5; i++) {
            expected.add(createReview(createContract("c" + i + ".docx")));
        }

        var walked = new ArrayList<UUID>();
        String cursor = null;
        for (var guard = 0; guard < 10; guard++) {
            var request = MockMvcRequestBuilders.get("/api/reviews").param("limit", "2");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            var data = json(mockMvc.perform(request).andExpect(status().isOk())
                    .andReturn()).get("data");
            data.get("items").forEach(item -> walked.add(UUID.fromString(item.get("id").asText())));
            cursor = data.get("nextCursor").isNull() ? null : data.get("nextCursor").asText();
            if (cursor == null) {
                break;
            }
        }

        assertThat(walked)
                .as("every review exactly once across the whole cursor walk")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(walked).doesNotHaveDuplicates();
    }

    @Test
    void nextCursorIsAbsentOnTheLastPage() throws Exception {
        createReview(createContract("only.docx"));

        var data = json(mockMvc.perform(get("/api/reviews").param("limit", "20"))
                .andReturn()).get("data");

        assertThat(data.get("items").size()).isEqualTo(1);
        assertThat(data.get("truncated").asBoolean()).isFalse();
        assertThat(data.get("nextCursor").isNull() || data.get("nextCursor").isMissingNode())
                .isTrue();
    }

    @Test
    void limitIsEnforcedAndBounded() throws Exception {
        for (var i = 0; i < 3; i++) {
            createReview(createContract("l" + i + ".docx"));
        }

        mockMvc.perform(get("/api/reviews").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.truncated").value(true));

        mockMvc.perform(get("/api/reviews").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        mockMvc.perform(get("/api/reviews").param("limit", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void anOversizedLimitIsClampedToTheCapRatherThanHonoured() throws Exception {
        for (var i = 0; i < 105; i++) {
            createReview(createContract("cap" + i + ".docx"));
        }

        mockMvc.perform(get("/api/reviews").param("limit", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(100))
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty());
    }

    @Test
    void anEmptyOrganizationReturnsAnEmptyPage() throws Exception {
        var data = json(mockMvc.perform(get("/api/reviews"))
                .andExpect(status().isOk())
                .andReturn()).get("data");

        assertThat(data.get("items").size()).isZero();
        assertThat(data.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void anotherOrganizationsReviewsAreNeverVisible() throws Exception {
        createReview(createContract("mine.docx"));

        var otherOrganization = UUID.randomUUID();
        var otherContract = seedContractFor(otherOrganization, "theirs.docx");
        var otherReview = seedReviewFor(otherOrganization, otherContract, "theirs.docx");
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), organizationId(), Set.of("ADMIN")));

        var data = json(mockMvc.perform(get("/api/reviews").param("limit", "50"))
                .andExpect(status().isOk())
                .andReturn()).get("data");

        var ids = new ArrayList<String>();
        data.get("items").forEach(item -> ids.add(item.get("id").asText()));
        assertThat(ids).doesNotContain(otherReview.toString());
        assertThat(data.get("items").get(0).get("contractFilename").asText())
                .as("only this organization's own review is listed")
                .isEqualTo("mine.docx");
    }

    @Test
    void anotherOrganizationsContractIdCannotWidenTheResult() throws Exception {
        var otherOrganization = UUID.randomUUID();
        var otherContract = seedContractFor(otherOrganization, "theirs.docx");
        seedReviewFor(otherOrganization, otherContract, "theirs.docx");

        // A cross-organization contract id is not found, exactly as before this change:
        // the guard is preserved rather than relaxed.
        mockMvc.perform(get("/api/reviews")
                        .param("contractId", otherContract.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
    }

    @Test
    void anUnknownContractStillReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/reviews")
                        .param("contractId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
    }

    @Test
    void aMalformedCursorIsRejected() throws Exception {
        mockMvc.perform(get("/api/reviews").param("cursor", "not-a-real-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void listRequiresReadPermission() throws Exception {
        var contractId = createContract("forbidden.docx");
        createReview(contractId);
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), organizationId(), Set.of("VIEWER")));

        mockMvc.perform(get("/api/reviews"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403"));
        mockMvc.perform(get("/api/reviews").param("contractId", contractId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403"));
    }

    private List<String> listedIds() throws Exception {
        var items = json(mockMvc.perform(get("/api/reviews"))
                .andReturn()).get("data").get("items");
        var ids = new ArrayList<String>();
        items.forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private UUID createContract(String filename) throws Exception {
        var bytes = Files.readAllBytes(Path.of("src/test/resources/fixtures/simple-contract.docx"));
        var file = new MockMultipartFile(
                "file", filename, "application/vnd.openxmlformats-officedocument"
                        + ".wordprocessingml.document", bytes);
        var result = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/contracts").file(file))
                .andExpect(status().isOk())
                .andReturn();
        var contractId = UUID.fromString(json(result).get("data").get("contractId").asText());
        enrichmentService.enrich(contractId, 1, fixtureProfile());
        return contractId;
    }

    private com.rivell.workbench.contracts.ReviewProfile fixtureProfile() {
        return new com.rivell.workbench.contracts.ReviewProfile(
                com.rivell.workbench.contracts.ContractType.MSA,
                com.rivell.workbench.contracts.PartyRole.RECEIVING_PARTY,
                com.rivell.workbench.contracts.PaperSource.COUNTERPARTY_PAPER,
                com.rivell.workbench.contracts.Jurisdiction.US,
                ReviewPackRegistry.MSA_PACK);
    }

    private UUID createReview(UUID contractId) throws Exception {
        var result = mockMvc.perform(MockMvcRequestBuilders.post(CREATE_PATH, contractId, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractPackKey\":\"" + ReviewPackRegistry.MSA_PACK + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(json(result).get("data").get("id").asText());
    }

    private UUID seedContractFor(UUID organizationId, String filename) throws Exception {
        var bytes = Files.readAllBytes(Path.of("src/test/resources/fixtures/simple-contract.docx"));
        var artifactId = UUID.randomUUID();
        var contractId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256,
                   storage_key, created_at)
                values (:id, :organizationId, :filename,
                        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                        :size, repeat('b', 64), :key, now())
                """)
                .param("id", artifactId)
                .param("organizationId", organizationId)
                .param("filename", filename)
                .param("size", bytes.length)
                .param("key", organizationId + "/" + artifactId + ".docx")
                .update();
        jdbcClient.sql("""
                insert into contract (id, organization_id, title, created_at)
                values (:id, :organizationId, null, now())
                """)
                .param("id", contractId)
                .param("organizationId", organizationId)
                .update();
        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, organization_id,
                   role, parser_version, parse_status, parse_warnings, created_at)
                values (:id, :contractId, 1, :artifactId, :organizationId, 'ORIGINAL',
                        'rivell-docx-parser-1', 'OK', '[]'::jsonb, now())
                """)
                .param("id", UUID.randomUUID())
                .param("contractId", contractId)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .update();
        return contractId;
    }

    private UUID seedReviewFor(UUID organizationId, UUID contractId, String filename)
            throws Exception {
        var versionId = jdbcClient.sql("""
                select id from contract_version where contract_id = :contractId
                """).param("contractId", contractId).query(UUID.class).single();
        var reviewId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into review
                  (id, organization_id, contract_id, contract_version_id,
                   contract_version_number, status, failure_reason, created_by,
                   client_request_id, created_at, updated_at, started_at, completed_at)
                values (:id, :organizationId, :contractId, :versionId, 1, 'ANALYSIS_QUEUED',
                        null, :userId, null, now(), now(), null, null)
                """)
                .param("id", reviewId)
                .param("organizationId", organizationId)
                .param("contractId", contractId)
                .param("versionId", versionId)
                .param("userId", UUID.randomUUID())
                .update();
        return reviewId;
    }

    /** Adds a RUNNING generation to a queued review, mirroring a real in-flight analysis. */
    private UUID seedRunningRun(UUID reviewId) {
        var runId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into review_analysis_run
                  (id, organization_id, review_id, run_number, status, trigger,
                   pipeline_version, started_at, completed_at, failure_reason)
                values (:id, :organizationId, :reviewId, 1, 'RUNNING', 'INITIAL',
                        'rivell-review-pipeline-1', now(), null, null)
                """)
                .param("id", runId)
                .param("organizationId", organizationId())
                .param("reviewId", reviewId)
                .update();
        jdbcClient.sql("""
                update review set status = 'ANALYZING', started_at = now() where id = :id
                """).param("id", reviewId).update();
        return runId;
    }

    @Autowired
    com.rivell.workbench.review.ReviewAnalysisService reviewAnalysisService;

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}