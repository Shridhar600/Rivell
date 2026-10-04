package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * The summary's document metadata must describe the version the review pinned.
 *
 * <p>A review is created against one contract version and must keep reporting that version's
 * document forever. These tests prove the pin by making a later version exist and showing the
 * summary does not move to it, and by comparing {@code uploadedAt} against the pinned
 * version's own ingestion time rather than the review's creation time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class ReviewSummaryMetadataIT {

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
    JdbcClient jdbcClient;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewDocumentQuery documentQuery;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    ReviewService reviewService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    @Test
    void theSummaryReportsThePinnedVersionsUploadedDocument() throws Exception {
        var reviewId = reviewedContract();
        var summary = summary(reviewId);
        var document = summary.get("document");

        var pinnedFilename = jdbcClient.sql("""
                select sa.filename
                from review r
                join contract_version cv on cv.id = r.contract_version_id
                join source_artifact sa on sa.id = cv.source_artifact_id
                where r.id = :reviewId
                """).param("reviewId", reviewId).query(String.class).single();

        assertThat(document.get("contractFilename").asText())
                .as("the filename comes from the pinned version's own source artifact")
                .isEqualTo(pinnedFilename);
        assertThat(document.get("sizeBytes").asLong())
                .as("sizeBytes describes the uploaded source, not a derived structure")
                .isPositive();
    }

    @Test
    void uploadedAtIsThePinnedVersionsIngestionTimeNotTheReviewsCreationTime() throws Exception {
        var reviewId = reviewedContract();
        var document = summary(reviewId).get("document");

        var versionCreatedAt = jdbcClient.sql("""
                select cv.created_at
                from review r
                join contract_version cv on cv.id = r.contract_version_id
                where r.id = :reviewId
                """).param("reviewId", reviewId).query(Timestamp.class).single().toInstant();

        var reviewCreatedAt = jdbcClient.sql("select created_at from review where id = :id")
                .param("id", reviewId).query(Timestamp.class).single().toInstant();

        assertThat(Instant.parse(document.get("uploadedAt").asText()))
                .as("uploadedAt is when the pinned version was ingested")
                .isEqualTo(versionCreatedAt);
        assertThat(Instant.parse(document.get("uploadedAt").asText()))
                .as("and is not the review's creation time relabelled")
                .isNotEqualTo(reviewCreatedAt);
    }

    @Test
    void aLaterContractVersionDoesNotChangeWhatAnExistingReviewReports() throws Exception {
        var reviewId = reviewedContract();
        var before = summary(reviewId).get("document");

        addLaterVersion(reviewId, "Later Revision Of The Same Contract.docx");

        var after = summary(reviewId).get("document");
        assertThat(after.get("contractFilename").asText())
                .as("a review keeps reporting the version it froze, never the latest")
                .isEqualTo(before.get("contractFilename").asText())
                .isNotEqualTo("Later Revision Of The Same Contract.docx");
        assertThat(Instant.parse(after.get("uploadedAt").asText()))
                .isEqualTo(Instant.parse(before.get("uploadedAt").asText()));
        assertThat(after.get("sizeBytes").asLong())
                .isEqualTo(before.get("sizeBytes").asLong());

        assertThat(jdbcClient.sql("""
                select count(*) from contract_version cv
                join review r on r.contract_id = cv.contract_id
                where r.id = :reviewId
                """).param("reviewId", reviewId).query(Integer.class).single())
                .as("the later version really does exist, so the pin was tested")
                .isEqualTo(2);
    }

    @Test
    void aReviewWithNoRunProposesNothing() throws Exception {
        var created = createReview(corpusBuilder.buildMainCorpus(organizationId())
                .contract().contractId(), ReviewPackRegistry.MSA_PACK);
        var proposed = summary(UUID.fromString(created.get("id").asText()))
                .get("proposedChanges");

        assertThat(proposed.get("replace").asInt()).isZero();
        assertThat(proposed.get("insert").asInt()).isZero();
    }

    /**
     * Counts are asserted by what they mean to a reviewer, never by re-running the aggregate
     * the server already ran.
     *
     * <p>The distinction that matters: a replacement Rivell actually wrote is actionable now,
     * while an insertion is a gap it detected and cannot fill. ACCEPT is valid only for the
     * first, so the domain is the oracle — these tests use it to prove the counts never claim
     * a proposal the domain would refuse.
     */
    @Test
    void everyCountedReplacementIsOneTheDomainCanAccept() throws Exception {
        var reviewId = analyzedReview();
        var proposed = summary(reviewId).get("proposedChanges");

        var replaceable = new java.util.ArrayList<JsonNode>();
        var gaps = new java.util.ArrayList<JsonNode>();
        for (var finding : findings(reviewId)) {
            var detail = findingDetail(reviewId, finding.get("id").asText());
            var language = detail.get("proposedLanguage");
            if (language != null && !language.isNull()) {
                replaceable.add(finding);
            } else if (finding.get("proposedAction").asText().equals("ADD_REQUIRED_PROVISION")) {
                gaps.add(finding);
            }
        }

        assertThat(proposed.get("replace").asInt()).isEqualTo(replaceable.size());
        assertThat(proposed.get("insert").asInt()).isEqualTo(gaps.size());
        assertThat(proposed.get("delete").asInt())
                .as("a deletion is only ever a human choice at decision time")
                .isZero();

        for (var finding : replaceable) {
            assertThat(acceptStatus(reviewId, finding.get("id").asText()))
                    .as("a counted replacement must be acceptable, or the count overstates "
                            + "what Rivell can hand a reviewer")
                    .isEqualTo(200);
        }
        for (var finding : gaps) {
            assertThat(acceptStatus(reviewId, finding.get("id").asText()))
                    .as("a counted insertion carries no wording, so ACCEPT must be refused; "
                            + "if this ever changes, insert means drafted insertions and the "
                            + "contract has to say so")
                    .isEqualTo(409);
        }
    }

    /**
     * The same claims on a review whose Findings are absent provisions. This is the shape that
     * exposed the original defect, where an absence Finding with no proposed wording was being
     * reported as a drafted insertion.
     */
    @Test
    void anAbsenceFindingIsCountedAsAGapAndNotAsADraftedInsertion() throws Exception {
        var bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new java.io.ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.NDA, PartyRole.RECEIVING_PARTY,
                        PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(ingested.contractId(), 1, null);
        analysisService.analyze(organizationId(), review.id());

        var gapFindings = new java.util.ArrayList<JsonNode>();
        for (var finding : findings(review.id())) {
            if (finding.get("proposedAction").asText().equals("ADD_REQUIRED_PROVISION")) {
                gapFindings.add(finding);
            }
        }
        assertThat(gapFindings)
                .as("this fixture is only meaningful with a missing required provision")
                .isNotEmpty();

        var proposed = summary(review.id()).get("proposedChanges");
        assertThat(proposed.get("insert").asInt()).isEqualTo(gapFindings.size());
        assertThat(proposed.get("replace").asInt())
                .as("a Finding with no wording is not a replacement proposal either")
                .isZero();

        for (var finding : gapFindings) {
            var detail = findingDetail(review.id(), finding.get("id").asText());
            assertThat(detail.get("proposedLanguage").isNull())
                    .as("Rivell proposed no wording for this gap")
                    .isTrue();
            assertThat(acceptStatus(review.id(), finding.get("id").asText()))
                    .as("so ACCEPT is refused, and the count must not imply otherwise")
                    .isEqualTo(409);
        }
    }

    @Test
    void theDocumentQueryIsOrganizationScopedOnBothSides() {
        var reviewId = reviewedContractQuietly();
        var versionId = jdbcClient.sql(
                "select contract_version_id from review where id = :id")
                .param("id", reviewId).query(UUID.class).single();
        var foreignOrg = UUID.randomUUID();

        assertThat(documentQuery.find(organizationId(), versionId)).isPresent();
        assertThat(documentQuery.find(foreignOrg, versionId))
                .as("another organization cannot read this version's document")
                .isEmpty();
        assertThat(documentQuery.find(organizationId(), UUID.randomUUID()))
                .as("a version this organization does not own resolves to nothing")
                .isEmpty();
    }

    private List<JsonNode> findings(UUID reviewId) throws Exception {
        var result = mockMvc.perform(get("/api/reviews/{id}/findings?limit=200", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        var items = json(result).get("data").get("items");
        var list = new java.util.ArrayList<JsonNode>();
        items.forEach(list::add);
        return list;
    }

    private JsonNode findingDetail(UUID reviewId, String findingId) throws Exception {
        return json(mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        reviewId, findingId))
                .andExpect(status().isOk())
                .andReturn()).get("data");
    }

    private int acceptStatus(UUID reviewId, String findingId) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/reviews/{id}/findings/{findingId}/decision",
                        reviewId, findingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"action\":\"ACCEPT\"}"))
                .andReturn().getResponse().getStatus();
    }

    /**
     * Adds a second version of the review's contract directly, because ingestion has no
     * "new version of an existing contract" path yet. Only the contract side is written; the
     * review is untouched, which is exactly the situation the pin has to survive.
     */
    private void addLaterVersion(UUID reviewId, String filename) {
        var contractId = jdbcClient.sql("select contract_id from review where id = :id")
                .param("id", reviewId).query(UUID.class).single();
        var artifactId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256,
                   storage_key, created_at)
                values (:id, :organizationId, :filename, 'application/vnd.openxmlformats-
                  officedocument.wordprocessingml.document', 4242, :sha, :key, :createdAt)
                """)
                .param("id", artifactId)
                .param("organizationId", organizationId())
                .param("filename", filename)
                .param("sha", "c".repeat(64))
                .param("key", organizationId() + "/later.docx")
                .param("createdAt", timestamp(Instant.now().plusSeconds(3600)))
                .update();
        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, organization_id,
                   role, parser_version, parse_status, parse_warnings, created_at)
                values (:id, :contractId,
                   (select max(version_number) + 1 from contract_version
                     where contract_id = :contractId),
                   :artifactId, :organizationId, 'ORIGINAL', 'test-parser', 'OK', '[]'::jsonb,
                   :createdAt)
                """)
                .param("id", versionId)
                .param("contractId", contractId)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId())
                .param("createdAt", timestamp(Instant.now().plusSeconds(3600)))
                .update();
    }

    /** An analysed review, so the counts are compared against Findings that really exist. */
    private UUID analyzedReview() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var created = createReview(corpus.contract().contractId(), ReviewPackRegistry.MSA_PACK);
        var reviewId = UUID.fromString(created.get("id").asText());
        analysisService.analyze(organizationId(), reviewId);
        assertThat(summary(reviewId).get("findings").asInt())
                .as("this test is only meaningful with findings to count")
                .isPositive();
        return reviewId;
    }

    /** Creates a review and swallows the checked exception for non-throwing callers. */
    private UUID reviewedContractQuietly() {
        try {
            return reviewedContract();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID reviewedContract() throws Exception {
        var created = createReview(corpusBuilder.buildMainCorpus(organizationId())
                .contract().contractId(), ReviewPackRegistry.MSA_PACK);
        return UUID.fromString(created.get("id").asText());
    }

    private JsonNode createReview(UUID contractId, String packKey) throws Exception {
        var body = packKey == null
                ? "{}"
                : "{\"contractPackKey\":\"" + packKey + "\"}";
        var result = mockMvc.perform(post(CREATE_PATH, contractId, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data");
    }

    private JsonNode summary(UUID reviewId) throws Exception {
        return json(mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andExpect(status().isOk())
                .andReturn()).get("data");
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
