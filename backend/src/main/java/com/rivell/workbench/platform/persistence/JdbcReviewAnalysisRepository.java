package com.rivell.workbench.platform.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.review.CheckExecutionStatus;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.PresencePayload;
import com.rivell.workbench.review.RawReviewSignal;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewAnalysisRun;
import com.rivell.workbench.review.ReviewCheckExecution;
import com.rivell.workbench.review.RunStatus;
import com.rivell.workbench.review.RunTrigger;
import com.rivell.workbench.review.SignalPayload;
import com.rivell.workbench.review.SignalType;

@Repository
class JdbcReviewAnalysisRepository implements ReviewAnalysisRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    JdbcReviewAnalysisRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ReviewAnalysisRun> findRunningRun(UUID organizationId, UUID reviewId) {
        return jdbcClient.sql("""
                select id, organization_id, review_id, run_number, status, trigger,
                       pipeline_version, started_at, completed_at, failure_reason
                from review_analysis_run
                where review_id = :reviewId and organization_id = :organizationId
                  and status = 'RUNNING'
                """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .query(JdbcReviewAnalysisRepository::mapRun)
                .optional();
    }

    @Override
    public Optional<ReviewAnalysisRun> findLatestRun(UUID organizationId, UUID reviewId) {
        return jdbcClient.sql("""
                select id, organization_id, review_id, run_number, status, trigger,
                       pipeline_version, started_at, completed_at, failure_reason
                from review_analysis_run
                where review_id = :reviewId and organization_id = :organizationId
                order by run_number desc
                limit 1
                """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .query(JdbcReviewAnalysisRepository::mapRun)
                .optional();
    }

    @Override
    public Optional<ReviewAnalysisRun> findLatestCompletedRun(
            UUID organizationId, UUID reviewId) {
        return jdbcClient.sql("""
                select id, organization_id, review_id, run_number, status, trigger,
                       pipeline_version, started_at, completed_at, failure_reason
                from review_analysis_run
                where review_id = :reviewId and organization_id = :organizationId
                  and status = 'COMPLETED'
                order by run_number desc
                limit 1
                """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .query(JdbcReviewAnalysisRepository::mapRun)
                .optional();
    }

    @Override
    public int maxRunNumber(UUID organizationId, UUID reviewId) {
        return jdbcClient.sql("""
                select coalesce(max(run_number), 0) from review_analysis_run
                where review_id = :reviewId and organization_id = :organizationId
                """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .query(Integer.class)
                .single();
    }

    /**
     * One RUNNING run per review is enforced by a partial unique index; the duplicate-key
     * race is resolved by returning false. The guard is safe because the method runs in
     * its own transaction (an aborted Postgres transaction is fully rolled back).
     */
    @Override
    @Transactional
    public boolean tryInsertRun(ReviewAnalysisRun run) {
        try {
            jdbcClient.sql("""
                    insert into review_analysis_run
                      (id, organization_id, review_id, run_number, status, trigger,
                       pipeline_version, started_at, completed_at, failure_reason)
                    values (:id, :organizationId, :reviewId, :runNumber, :status, :trigger,
                       :pipelineVersion, :startedAt, :completedAt, :failureReason)
                    """)
                    .param("id", run.id())
                    .param("organizationId", run.organizationId())
                    .param("reviewId", run.reviewId())
                    .param("runNumber", run.runNumber())
                    .param("status", run.status().name())
                    .param("trigger", run.trigger().name())
                    .param("pipelineVersion", run.pipelineVersion())
                    .param("startedAt", timestamp(run.startedAt()))
                    .param("completedAt", run.completedAt() == null ? null : timestamp(run.completedAt()))
                    .param("failureReason", run.failureReason())
                    .update();
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    @Transactional
    public boolean markRunCompleted(UUID organizationId, UUID runId, java.time.Instant completedAt) {
        return jdbcClient.sql("""
                update review_analysis_run
                set status = 'COMPLETED', completed_at = :completedAt
                where id = :runId and organization_id = :organizationId and status = 'RUNNING'
                """)
                .param("runId", runId)
                .param("organizationId", organizationId)
                .param("completedAt", timestamp(completedAt))
                .update() == 1;
    }

    @Override
    @Transactional
    public boolean markRunFailed(UUID organizationId, UUID runId, String reason,
            java.time.Instant completedAt) {
        return jdbcClient.sql("""
                update review_analysis_run
                set status = 'FAILED', failure_reason = :reason, completed_at = :completedAt
                where id = :runId and organization_id = :organizationId and status = 'RUNNING'
                """)
                .param("runId", runId)
                .param("organizationId", organizationId)
                .param("reason", reason)
                .param("completedAt", timestamp(completedAt))
                .update() == 1;
    }

    @Override
    public Optional<ReviewCheckExecution> findExecution(
            UUID organizationId, UUID runId, UUID planCheckId) {
        return jdbcClient.sql("""
                select id, organization_id, review_id, run_id, plan_check_id, status,
                       evidence_trace_id, detail, signal_count, started_at, completed_at
                from review_check_execution
                where run_id = :runId and plan_check_id = :planCheckId
                  and organization_id = :organizationId
                """)
                .param("runId", runId)
                .param("planCheckId", planCheckId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> mapExecution(rs))
                .optional();
    }

    @Override
    public List<ReviewCheckExecution> findExecutions(UUID organizationId, UUID runId) {
        return jdbcClient.sql("""
                select e.id, e.organization_id, e.review_id, e.run_id, e.plan_check_id,
                       e.status, e.evidence_trace_id, e.detail, e.signal_count,
                       e.started_at, e.completed_at
                from review_check_execution e
                join review_plan_check p
                  on p.id = e.plan_check_id and p.organization_id = e.organization_id
                where e.run_id = :runId and e.organization_id = :organizationId
                order by p.ordinal
                """)
                .param("runId", runId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> mapExecution(rs))
                .list();
    }

    @Override
    @Transactional
    public boolean insertExecutionIfAbsent(ReviewCheckExecution execution) {
        return jdbcClient.sql("""
                insert into review_check_execution
                  (id, organization_id, review_id, run_id, plan_check_id, status, detail,
                   signal_count)
                values (:id, :organizationId, :reviewId, :runId, :planCheckId, :status,
                   cast(:detail as jsonb), 0)
                on conflict (run_id, plan_check_id) do nothing
                """)
                .param("id", execution.id())
                .param("organizationId", execution.organizationId())
                .param("reviewId", execution.reviewId())
                .param("runId", execution.runId())
                .param("planCheckId", execution.planCheckId())
                .param("status", execution.status().name())
                .param("detail", json(execution.detail()))
                .update() == 1;
    }

    @Override
    @Transactional
    public Optional<UUID> claimExecution(UUID organizationId, UUID executionId) {
        var leaseId = UUID.randomUUID();
        var claimed = jdbcClient.sql("""
                update review_check_execution
                set status = 'RUNNING', started_at = now(), lease_id = :leaseId
                where id = :executionId and organization_id = :organizationId
                  and (status = 'PENDING'
                       or (status = 'RUNNING' and started_at < now() - interval '10 minutes'))
                  and exists (select 1 from review_analysis_run r
                              where r.id = review_check_execution.run_id
                                and r.status = 'RUNNING')
                """)
                .param("executionId", executionId)
                .param("organizationId", organizationId)
                .param("leaseId", leaseId)
                .update() == 1;
        return claimed ? Optional.of(leaseId) : Optional.empty();
    }

    @Override
    @Transactional
    public boolean completeExecution(
            UUID organizationId, UUID executionId, UUID leaseId,
            CheckExecutionStatus status, UUID evidenceTraceId, Map<String, Object> detail,
            int signalCount, java.time.Instant completedAt) {
        return jdbcClient.sql("""
                update review_check_execution
                set status = :status, evidence_trace_id = :evidenceTraceId,
                    detail = cast(:detail as jsonb), signal_count = :signalCount,
                    completed_at = :completedAt
                where id = :executionId and organization_id = :organizationId
                  and status = 'RUNNING' and lease_id = :leaseId
                  and exists (select 1 from review_analysis_run r
                              where r.id = review_check_execution.run_id
                                and r.status = 'RUNNING')
                """)
                .param("executionId", executionId)
                .param("organizationId", organizationId)
                .param("leaseId", leaseId)
                .param("status", status.name())
                .param("evidenceTraceId", evidenceTraceId)
                .param("detail", json(detail))
                .param("signalCount", signalCount)
                .param("completedAt", timestamp(completedAt))
                .update() == 1;
    }

    @Override
    @Transactional
    public void insertSignals(List<RawReviewSignal> signals) {
        for (var signal : signals) {
            jdbcClient.sql("""
                    insert into raw_review_signal
                      (id, organization_id, review_id, run_id, plan_check_id, signal_type,
                       subject_kind, subject_heading_node_id, absence_concept, concept,
                       secondary_concept, issue_family, payload, evidence_item_ids,
                       decision_spec_id, decision_spec_version, jev_execution_id,
                       generative_execution_id, verification_outcome, resolved_model, usage,
                       quality, dedupe_key, created_at)
                    values (:id, :organizationId, :reviewId, :runId, :planCheckId, :signalType,
                       :subjectKind, :subjectHeadingNodeId, :absenceConcept, :concept,
                       :secondaryConcept, :issueFamily, cast(:payload as jsonb),
                       cast(:evidenceItemIds as jsonb), :decisionSpecId, :decisionSpecVersion,
                       :jevExecutionId, :generativeExecutionId, :verificationOutcome,
                        :resolvedModel, cast(:usage as jsonb),
                       cast(:quality as jsonb), :dedupeKey, :createdAt)
                    """)
                    .param("id", signal.id())
                    .param("organizationId", signal.organizationId())
                    .param("reviewId", signal.reviewId())
                    .param("runId", signal.runId())
                    .param("planCheckId", signal.planCheckId())
                    .param("signalType", signal.signalType().name())
                    .param("subjectKind", signal.subjectKind().name())
                    .param("subjectHeadingNodeId", signal.subjectHeadingNodeId())
                    .param("absenceConcept", signal.absenceConcept() == null ? null : signal.absenceConcept().name())
                    .param("concept", signal.concept().name())
                    .param("secondaryConcept", signal.secondaryConcept() == null ? null : signal.secondaryConcept().name())
                    .param("issueFamily", signal.issueFamily().name())
                    .param("payload", json(signal.payload()))
                    .param("evidenceItemIds", json(signal.evidenceItemIds()))
                    .param("decisionSpecId", signal.decisionSpecId())
                    .param("decisionSpecVersion", signal.decisionSpecVersion())
                    .param("jevExecutionId", signal.jevExecutionId())
                    .param("generativeExecutionId", signal.generativeExecutionId())
                    .param("verificationOutcome", signal.verificationOutcome())
                    .param("resolvedModel", signal.resolvedModel())
                    .param("usage", signal.usage() == null ? null : json(signal.usage()))
                    .param("quality", json(signal.quality()))
                    .param("dedupeKey", signal.dedupeKey())
                    .param("createdAt", timestamp(signal.createdAt()))
                    .update();
        }
    }

    @Override
    @Transactional
    public int deleteSignals(UUID organizationId, UUID runId, UUID planCheckId) {
        return jdbcClient.sql("""
                delete from raw_review_signal
                where run_id = :runId and plan_check_id = :planCheckId
                  and organization_id = :organizationId
                """)
                .param("runId", runId)
                .param("planCheckId", planCheckId)
                .param("organizationId", organizationId)
                .update();
    }

    @Override
    public void replaceEvidenceItems(
            UUID organizationId, UUID reviewId, UUID runId, UUID planCheckId,
            List<com.rivell.workbench.retrieval.EvidenceItem> items) {
        jdbcClient.sql("""
                delete from review_evidence_item
                where run_id = :runId and plan_check_id = :planCheckId
                  and organization_id = :organizationId
                """)
                .param("runId", runId)
                .param("planCheckId", planCheckId)
                .param("organizationId", organizationId)
                .update();
        for (var item : items) {
            jdbcClient.sql("""
                    insert into review_evidence_item (
                        id, organization_id, review_id, run_id, plan_check_id,
                        evidence_item_id, authority_class, source_clause_id,
                        publication_number, payload, created_at)
                    values (
                        :id, :organizationId, :reviewId, :runId, :planCheckId,
                        :evidenceItemId, :authorityClass, :sourceClauseId,
                        :publicationNumber, cast(:payload as jsonb), :createdAt)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("organizationId", organizationId)
                    .param("reviewId", reviewId)
                    .param("runId", runId)
                    .param("planCheckId", planCheckId)
                    .param("evidenceItemId", item.evidenceId())
                    .param("authorityClass", item.authorityClass().name())
                    .param("sourceClauseId", item.sourceClauseId())
                    .param("publicationNumber", item.publicationNumber())
                    .param("payload", json(item))
                    .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
        }
    }

    @Override
    public List<com.rivell.workbench.retrieval.EvidenceItem> findEvidenceItems(
            UUID organizationId, UUID runId, java.util.Collection<UUID> evidenceItemIds) {
        if (evidenceItemIds == null || evidenceItemIds.isEmpty()) {
            return List.of();
        }
        var sql = new StringBuilder("""
                select payload from review_evidence_item
                where run_id = :runId and organization_id = :organizationId
                  and evidence_item_id in (""");
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("runId", runId);
        params.put("organizationId", organizationId);
        var index = 0;
        for (var evidenceItemId : evidenceItemIds) {
            var name = "evidence" + index++;
            if (index > 1) {
                sql.append(", ");
            }
            sql.append(":").append(name);
            params.put(name, evidenceItemId);
        }
        sql.append(")");
        var statement = jdbcClient.sql(sql.toString());
        for (var entry : params.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        var byId = new java.util.LinkedHashMap<UUID, com.rivell.workbench.retrieval.EvidenceItem>();
        statement.query((rs, rowNum) -> readEvidenceItem(rs.getString("payload")))
                .list()
                .forEach(item -> byId.putIfAbsent(item.evidenceId(), item));
        return List.copyOf(byId.values());
    }

    private com.rivell.workbench.retrieval.EvidenceItem readEvidenceItem(String payload) {
        try {
            return objectMapper.readValue(payload,
                    com.rivell.workbench.retrieval.EvidenceItem.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid persisted evidence item JSON", e);
        }
    }

    @Override
    public List<RawReviewSignal> findSignals(UUID organizationId, UUID runId) {
        return jdbcClient.sql("""
                select id, organization_id, review_id, run_id, plan_check_id, signal_type,
                       subject_kind, subject_heading_node_id, absence_concept, concept,
                       secondary_concept, issue_family, payload, evidence_item_ids,
                       decision_spec_id, decision_spec_version, jev_execution_id,
                       generative_execution_id, verification_outcome, resolved_model, usage,
                       quality, dedupe_key, created_at
                from raw_review_signal
                where run_id = :runId and organization_id = :organizationId
                order by dedupe_key
                """)
                .param("runId", runId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new RawReviewSignal(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("review_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("plan_check_id", UUID.class),
                        SignalType.valueOf(rs.getString("signal_type")),
                        FindingSubjectKind.valueOf(rs.getString("subject_kind")),
                        rs.getObject("subject_heading_node_id", UUID.class),
                        rs.getString("absence_concept") == null ? null : LegalConcept.valueOf(rs.getString("absence_concept")),
                        LegalConcept.valueOf(rs.getString("concept")),
                        rs.getString("secondary_concept") == null ? null : LegalConcept.valueOf(rs.getString("secondary_concept")),
                        IssueFamily.valueOf(rs.getString("issue_family")),
                        payload(rs.getString("payload"), rs.getString("signal_type")),
                        uuidList(rs.getString("evidence_item_ids")),
                        rs.getString("decision_spec_id"),
                        rs.getObject("decision_spec_version", Integer.class),
                        rs.getObject("jev_execution_id", UUID.class),
                        rs.getObject("generative_execution_id", UUID.class),
                        rs.getString("verification_outcome"),
                        rs.getString("resolved_model"),
                        usage(rs.getString("usage")),
                        map(rs.getString("quality")),
                        rs.getString("dedupe_key"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public int countSignals(UUID organizationId, UUID runId) {
        return jdbcClient.sql("""
                select count(*) from raw_review_signal
                where run_id = :runId and organization_id = :organizationId
                """)
                .param("runId", runId)
                .param("organizationId", organizationId)
                .query(Integer.class)
                .single();
    }

    @Override
    public int countSignals(UUID organizationId, UUID runId, UUID planCheckId) {
        return jdbcClient.sql("""
                select count(*) from raw_review_signal
                where run_id = :runId and plan_check_id = :planCheckId
                  and organization_id = :organizationId
                """)
                .param("runId", runId)
                .param("planCheckId", planCheckId)
                .param("organizationId", organizationId)
                .query(Integer.class)
                .single();
    }

    private static ReviewAnalysisRun mapRun(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ReviewAnalysisRun(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("review_id", UUID.class),
                rs.getInt("run_number"),
                RunStatus.valueOf(rs.getString("status")),
                RunTrigger.valueOf(rs.getString("trigger")),
                rs.getString("pipeline_version"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getString("failure_reason"));
    }

    private ReviewCheckExecution mapExecution(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReviewCheckExecution(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("review_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("plan_check_id", UUID.class),
                CheckExecutionStatus.valueOf(rs.getString("status")),
                rs.getObject("evidence_trace_id", UUID.class),
                map(rs.getString("detail")),
                rs.getInt("signal_count"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }

    private SignalPayload payload(String json, String signalType) {
        try {
            JsonNode node = objectMapper.readTree(json);
            var kind = node.path("kind").asText("");
            if ("PRESENCE".equals(kind)) {
                return objectMapper.treeToValue(node, PresencePayload.class);
            }
            if ("POLICY_UNAVAILABLE".equals(kind)) {
                return objectMapper.treeToValue(node,
                        com.rivell.workbench.review.PolicyUnavailablePayload.class);
            }
            if ("POLICY_AMBIGUOUS".equals(kind)) {
                return objectMapper.treeToValue(node,
                        com.rivell.workbench.review.PolicyAmbiguousPayload.class);
            }
            if ("LIABILITY_DEVIATION".equals(kind)) {
                return objectMapper.treeToValue(node,
                        com.rivell.workbench.review.LiabilityDeviationPayload.class);
            }
            if ("APPROVED_DRAFTING".equals(kind)) {
                return objectMapper.treeToValue(node,
                        com.rivell.workbench.review.ApprovedDraftingPayload.class);
            }
            if ("ESCALATION".equals(kind)) {
                return objectMapper.treeToValue(node,
                        com.rivell.workbench.review.EscalationPayload.class);
            }
            if ("DEVIATION_UNCERTAIN".equals(kind)) {
                return objectMapper.treeToValue(node,
                        com.rivell.workbench.review.DeviationUncertainPayload.class);
            }
            if ("CROSS_CLAUSE".equals(kind)) {
                return objectMapper.treeToValue(node,
                        com.rivell.workbench.review.CrossClauseConflictPayload.class);
            }
            if ("OPEN_ENDED".equals(kind)) {
                return objectMapper.treeToValue(node,
                        com.rivell.workbench.review.OpenEndedAnalysisPayload.class);
            }
            throw new IllegalStateException(
                    "Unsupported signal payload kind '" + kind + "' for " + signalType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid signal payload JSON", e);
        }
    }

    private com.rivell.workbench.intelligence.TokenUsage usage(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json,
                    com.rivell.workbench.intelligence.TokenUsage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid usage JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON map", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<UUID> uuidList(String json) {
        try {
            List<String> values = json == null
                    ? List.of()
                    : objectMapper.readValue(json, List.class);
            return values.stream().map(UUID::fromString).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid UUID list JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize JSON", e);
        }
    }

    private static OffsetDateTime timestamp(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
