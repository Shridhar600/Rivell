package com.rivell.workbench.redline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.LiabilityReviewIT;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.ReviewAnalysisRun;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewService;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class RedlineSchemaIT {

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
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    ApprovedChangeSetRepository changeSetRepository;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void executionRowsAreConstrainedAndOrganizationScoped() throws Exception {
        var seeded = finalizedSet();
        var executionId = insertExecution(seeded);

        assertThat(count("redline_execution", executionId)).isEqualTo(1);

        assertThatThrownBy(() -> insertExecution(seeded))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("redline_execution_change_set_id_key");

        var change = seeded.change();
        insertExecutionChange(executionId, seeded, change, 0);
        assertThatThrownBy(() -> insertExecutionChange(executionId, seeded, change, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertExecutionChange(executionId, seeded, change, 1))
                .isInstanceOf(DataIntegrityViolationException.class);

        var artifactId = UUID.randomUUID();
        insertArtifact(artifactId, executionId, seeded.organizationId());
        assertThatThrownBy(() -> insertArtifact(UUID.randomUUID(), executionId,
                seeded.organizationId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertExecutionWithStatus(seeded, "COMPLETED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertExecutionWithStatus(
                seeded, "FAILED", "REDLINE_GENERATED", "TARGET_NOT_FOUND"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertExecutionWithStatus(seeded, "FAILED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertExecutionWithStatus(
                seeded, "COMPLETED", "NO_CHANGES", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void crossOrganizationReferencesAreStructurallyImpossible() throws Exception {
        var seeded = finalizedSet();
        var foreignOrganization = UUID.randomUUID();
        var executionId = insertExecution(seeded);

        assertThatThrownBy(() -> insertExecutionChange(executionId, seeded, seeded.change(), 0,
                foreignOrganization))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertArtifact(UUID.randomUUID(), executionId,
                foreignOrganization))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private record Seeded(UUID organizationId, UUID reviewId, UUID changeSetId,
            UUID contractId, UUID contractVersionId, int contractVersionNumber,
            ApprovedChange change) {
    }

    private Seeded finalizedSet() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        ReviewAnalysisRun run = analysisService.analyze(organizationId, review.id());
        List<Finding> findings = findingRepository.findFindings(organizationId, run.id());
        var finding = findings.stream()
                .filter(f -> f.proposedAction() == ProposedAction.USE_APPROVED_FALLBACK)
                .findFirst().orElseThrow();
        mockMvc.perform(put("/api/reviews/{id}/findings/{findingId}/decision",
                        review.id(), finding.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 0, \"action\": \"ACCEPT\"}"))
                .andExpect(status().isOk());
        var result = mockMvc.perform(post("/api/reviews/{id}/finalize", review.id()))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        var set = changeSetRepository.findByReview(organizationId, review.id()).orElseThrow();
        assertThat(set.id().toString()).isEqualTo(data.get("id").asText());
        return new Seeded(organizationId, review.id(), set.id(), set.contractId(),
                set.contractVersionId(), set.contractVersionNumber(), set.changes().get(0));
    }

    private UUID insertExecution(Seeded seeded) {
        return insertExecutionWithStatus(seeded, "COMPLETED", "REDLINE_GENERATED", null);
    }

    private UUID insertExecutionWithStatus(
            Seeded seeded, String status, String outcome, String failureCode) {
        var id = UUID.randomUUID();
        jdbcClient.sql("""
                insert into redline_execution
                  (id, organization_id, review_id, change_set_id, contract_id,
                   contract_version_id, contract_version_number, change_set_version,
                   status, outcome, failure_code, failure_detail, renderer_version,
                   placement_strategy_version, source_sha256, change_count, requested_by,
                   started_at, completed_at)
                values (:id, :organizationId, :reviewId, :changeSetId, :contractId,
                   :contractVersionId, :contractVersionNumber, 1,
                   :status, :outcome, :failureCode, null, 'test-renderer',
                   'test-placement', repeat('a', 64), 1, :requestedBy,
                   :startedAt, :completedAt)
                """)
                .param("id", id)
                .param("organizationId", seeded.organizationId())
                .param("reviewId", seeded.reviewId())
                .param("changeSetId", seeded.changeSetId())
                .param("contractId", seeded.contractId())
                .param("contractVersionId", seeded.contractVersionId())
                .param("contractVersionNumber", seeded.contractVersionNumber())
                .param("status", status)
                .param("outcome", outcome)
                .param("failureCode", failureCode)
                .param("requestedBy", UUID.randomUUID())
                .param("startedAt", timestamp())
                .param("completedAt", timestamp())
                .update();
        return id;
    }

    private void insertExecutionChange(
            UUID executionId, Seeded seeded, ApprovedChange change, int ordinal) {
        insertExecutionChange(executionId, seeded, change, ordinal, seeded.organizationId());
    }

    private void insertExecutionChange(UUID executionId, Seeded seeded,
            ApprovedChange change, int ordinal, UUID organizationId) {
        jdbcClient.sql("""
                insert into redline_execution_change
                  (id, organization_id, execution_id, approved_change_id, ordinal,
                   action_type, target_kind, status, verification, failure_code,
                   placement, resolved_target, paragraphs_deleted, paragraphs_inserted,
                   revision_id_start, revision_id_end)
                values (:id, :organizationId, :executionId, :approvedChangeId, :ordinal,
                   :actionType, :targetKind, 'APPLIED', 'VERIFIED', null,
                   null, 'bodyIndex=1', 1, 1, 2, 3)
                """)
                .param("id", UUID.randomUUID())
                .param("organizationId", organizationId)
                .param("executionId", executionId)
                .param("approvedChangeId", change.id())
                .param("ordinal", ordinal)
                .param("actionType", change.actionType().name())
                .param("targetKind", change.targetKind().name())
                .update();
    }

    private void insertArtifact(UUID artifactId, UUID executionId, UUID organizationId) {
        jdbcClient.sql("""
                insert into redline_artifact
                  (id, organization_id, execution_id, filename, content_type,
                   size_bytes, sha256, storage_key, created_at)
                values (:id, :organizationId, :executionId, 'contract-v1-redline.docx',
                   'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                   100, repeat('b', 64), :storageKey, :createdAt)
                """)
                .param("id", artifactId)
                .param("organizationId", organizationId)
                .param("executionId", executionId)
                .param("storageKey", organizationId + "/redline/" + artifactId + ".docx")
                .param("createdAt", timestamp())
                .update();
    }

    private int count(String table, UUID id) {
        return jdbcClient.sql("select count(*) from " + table + " where id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private static OffsetDateTime timestamp() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
