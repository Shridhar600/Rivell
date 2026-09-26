package com.rivell.workbench.platform.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.ChangeTargetKind;
import com.rivell.workbench.redline.RedlineArtifact;
import com.rivell.workbench.redline.RedlineChangeStatus;
import com.rivell.workbench.redline.RedlineExecution;
import com.rivell.workbench.redline.RedlineExecutionChange;
import com.rivell.workbench.redline.RedlineExecutionRepository;
import com.rivell.workbench.redline.RedlineExecutionStatus;
import com.rivell.workbench.redline.RedlineFailureCode;
import com.rivell.workbench.redline.RedlineOutcome;
import com.rivell.workbench.redline.RedlineVerificationStatus;

@Repository
class JdbcRedlineExecutionRepository implements RedlineExecutionRepository {

    private static final String EXECUTION_COLUMNS = """
            id, organization_id, review_id, change_set_id, contract_id,
            contract_version_id, contract_version_number, change_set_version,
            status, outcome, failure_code, failure_detail, renderer_version,
            placement_strategy_version, source_sha256, change_count, requested_by,
            started_at, completed_at
            """;

    private final JdbcClient jdbcClient;

    JdbcRedlineExecutionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<RedlineExecution> findByReview(UUID organizationId, UUID reviewId) {
        return jdbcClient.sql("select " + EXECUTION_COLUMNS + """
                from redline_execution
                where organization_id = :organizationId and review_id = :reviewId
                """)
                .param("organizationId", organizationId)
                .param("reviewId", reviewId)
                .query((rs, rowNum) -> header(rs))
                .optional()
                .map(this::withChildren);
    }

    @Override
    public Optional<RedlineExecution> findByChangeSet(UUID organizationId, UUID changeSetId) {
        return jdbcClient.sql("select " + EXECUTION_COLUMNS + """
                from redline_execution
                where organization_id = :organizationId and change_set_id = :changeSetId
                """)
                .param("organizationId", organizationId)
                .param("changeSetId", changeSetId)
                .query((rs, rowNum) -> header(rs))
                .optional()
                .map(this::withChildren);
    }

    @Override
    @Transactional
    public boolean insert(RedlineExecution execution) {
        var inserted = jdbcClient.sql("""
                insert into redline_execution
                  (id, organization_id, review_id, change_set_id, contract_id,
                   contract_version_id, contract_version_number, change_set_version,
                   status, outcome, failure_code, failure_detail, renderer_version,
                   placement_strategy_version, source_sha256, change_count, requested_by,
                   started_at, completed_at)
                values (:id, :organizationId, :reviewId, :changeSetId, :contractId,
                   :contractVersionId, :contractVersionNumber, :changeSetVersion,
                   :status, :outcome, :failureCode, :failureDetail, :rendererVersion,
                   :placementStrategyVersion, :sourceSha256, :changeCount, :requestedBy,
                   :startedAt, :completedAt)
                on conflict (change_set_id) do nothing
                """)
                .param("id", execution.id())
                .param("organizationId", execution.organizationId())
                .param("reviewId", execution.reviewId())
                .param("changeSetId", execution.changeSetId())
                .param("contractId", execution.contractId())
                .param("contractVersionId", execution.contractVersionId())
                .param("contractVersionNumber", execution.contractVersionNumber())
                .param("changeSetVersion", execution.changeSetVersion())
                .param("status", execution.status().name())
                .param("outcome", execution.outcome() == null ? null : execution.outcome().name())
                .param("failureCode",
                        execution.failureCode() == null ? null : execution.failureCode().name())
                .param("failureDetail", execution.failureDetail())
                .param("rendererVersion", execution.rendererVersion())
                .param("placementStrategyVersion", execution.placementStrategyVersion())
                .param("sourceSha256", execution.sourceSha256())
                .param("changeCount", execution.changeCount())
                .param("requestedBy", execution.requestedBy())
                .param("startedAt", timestamp(execution.startedAt()))
                .param("completedAt", timestamp(execution.completedAt()))
                .update();
        if (inserted == 0) {
            return false;
        }
        for (var change : execution.changes()) {
            insertChange(change);
        }
        if (execution.artifact() != null) {
            insertArtifact(execution.artifact());
        }
        return true;
    }

    private void insertChange(RedlineExecutionChange change) {
        jdbcClient.sql("""
                insert into redline_execution_change
                  (id, organization_id, execution_id, approved_change_id, ordinal,
                   action_type, target_kind, status, verification, failure_code,
                   placement, resolved_target, paragraphs_deleted, paragraphs_inserted,
                   revision_id_start, revision_id_end)
                values (:id, :organizationId, :executionId, :approvedChangeId, :ordinal,
                   :actionType, :targetKind, :status, :verification, :failureCode,
                   :placement, :resolvedTarget, :paragraphsDeleted, :paragraphsInserted,
                   :revisionIdStart, :revisionIdEnd)
                """)
                .param("id", change.id())
                .param("organizationId", change.organizationId())
                .param("executionId", change.executionId())
                .param("approvedChangeId", change.approvedChangeId())
                .param("ordinal", change.ordinal())
                .param("actionType", change.actionType().name())
                .param("targetKind", change.targetKind().name())
                .param("status", change.status().name())
                .param("verification",
                        change.verification() == null ? null : change.verification().name())
                .param("failureCode",
                        change.failureCode() == null ? null : change.failureCode().name())
                .param("placement", change.placement())
                .param("resolvedTarget", change.resolvedTarget())
                .param("paragraphsDeleted", change.paragraphsDeleted())
                .param("paragraphsInserted", change.paragraphsInserted())
                .param("revisionIdStart", change.revisionIdStart())
                .param("revisionIdEnd", change.revisionIdEnd())
                .update();
    }

    private void insertArtifact(RedlineArtifact artifact) {
        jdbcClient.sql("""
                insert into redline_artifact
                  (id, organization_id, execution_id, filename, content_type,
                   size_bytes, sha256, storage_key, created_at)
                values (:id, :organizationId, :executionId, :filename, :contentType,
                   :sizeBytes, :sha256, :storageKey, :createdAt)
                """)
                .param("id", artifact.id())
                .param("organizationId", artifact.organizationId())
                .param("executionId", artifact.executionId())
                .param("filename", artifact.filename())
                .param("contentType", artifact.contentType())
                .param("sizeBytes", artifact.sizeBytes())
                .param("sha256", artifact.sha256())
                .param("storageKey", artifact.storageKey())
                .param("createdAt", timestamp(artifact.createdAt()))
                .update();
    }

    private RedlineExecution withChildren(Header header) {
        return new RedlineExecution(header.id(), header.organizationId(), header.reviewId(),
                header.changeSetId(), header.contractId(), header.contractVersionId(),
                header.contractVersionNumber(), header.changeSetVersion(), header.status(),
                header.outcome(), header.failureCode(), header.failureDetail(),
                header.rendererVersion(), header.placementStrategyVersion(),
                header.sourceSha256(), header.changeCount(), header.requestedBy(),
                header.startedAt(), header.completedAt(),
                changes(header.id(), header.organizationId()),
                artifact(header.id(), header.organizationId()).orElse(null));
    }

    private List<RedlineExecutionChange> changes(UUID executionId, UUID organizationId) {
        return jdbcClient.sql("""
                select id, organization_id, execution_id, approved_change_id, ordinal,
                       action_type, target_kind, status, verification, failure_code,
                       placement, resolved_target, paragraphs_deleted, paragraphs_inserted,
                       revision_id_start, revision_id_end
                from redline_execution_change
                where execution_id = :executionId and organization_id = :organizationId
                order by ordinal
                """)
                .param("executionId", executionId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new RedlineExecutionChange(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("execution_id", UUID.class),
                        rs.getObject("approved_change_id", UUID.class),
                        rs.getInt("ordinal"),
                        ChangeActionType.valueOf(rs.getString("action_type")),
                        ChangeTargetKind.valueOf(rs.getString("target_kind")),
                        RedlineChangeStatus.valueOf(rs.getString("status")),
                        rs.getString("verification") == null ? null
                                : RedlineVerificationStatus.valueOf(rs.getString("verification")),
                        rs.getString("failure_code") == null ? null
                                : RedlineFailureCode.valueOf(rs.getString("failure_code")),
                        rs.getString("placement"),
                        rs.getString("resolved_target"),
                        rs.getObject("paragraphs_deleted", Integer.class),
                        rs.getObject("paragraphs_inserted", Integer.class),
                        rs.getObject("revision_id_start", Long.class),
                        rs.getObject("revision_id_end", Long.class)))
                .list();
    }

    private Optional<RedlineArtifact> artifact(UUID executionId, UUID organizationId) {
        return jdbcClient.sql("""
                select id, organization_id, execution_id, filename, content_type,
                       size_bytes, sha256, storage_key, created_at
                from redline_artifact
                where execution_id = :executionId and organization_id = :organizationId
                """)
                .param("executionId", executionId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new RedlineArtifact(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("execution_id", UUID.class),
                        rs.getString("filename"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("sha256"),
                        rs.getString("storage_key"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    private static Header header(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Header(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("review_id", UUID.class),
                rs.getObject("change_set_id", UUID.class),
                rs.getObject("contract_id", UUID.class),
                rs.getObject("contract_version_id", UUID.class),
                rs.getInt("contract_version_number"),
                rs.getInt("change_set_version"),
                RedlineExecutionStatus.valueOf(rs.getString("status")),
                rs.getString("outcome") == null ? null
                        : RedlineOutcome.valueOf(rs.getString("outcome")),
                rs.getString("failure_code") == null ? null
                        : RedlineFailureCode.valueOf(rs.getString("failure_code")),
                rs.getString("failure_detail"),
                rs.getString("renderer_version"),
                rs.getString("placement_strategy_version"),
                rs.getString("source_sha256"),
                rs.getInt("change_count"),
                rs.getObject("requested_by", UUID.class),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant());
    }

    private record Header(
            UUID id, UUID organizationId, UUID reviewId, UUID changeSetId, UUID contractId,
            UUID contractVersionId, int contractVersionNumber, int changeSetVersion,
            RedlineExecutionStatus status, RedlineOutcome outcome,
            RedlineFailureCode failureCode, String failureDetail, String rendererVersion,
            String placementStrategyVersion, String sourceSha256, int changeCount,
            UUID requestedBy, Instant startedAt, Instant completedAt) {
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
