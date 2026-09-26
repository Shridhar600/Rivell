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
import com.rivell.workbench.redline.ChangeLanguageSource;
import com.rivell.workbench.workflow.DecisionAction;
import com.rivell.workbench.workflow.HumanDecision;
import com.rivell.workbench.workflow.HumanDecisionRepository;

@Repository
class JdbcHumanDecisionRepository implements HumanDecisionRepository {

    private final JdbcClient jdbcClient;

    JdbcHumanDecisionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<HumanDecision> findCurrent(
            UUID organizationId, UUID reviewId, UUID findingId) {
        return jdbcClient.sql(SELECT + """
                where organization_id = :organizationId and review_id = :reviewId
                  and finding_id = :findingId
                order by revision desc
                limit 1
                """)
                .param("organizationId", organizationId)
                .param("reviewId", reviewId)
                .param("findingId", findingId)
                .query(this::map)
                .optional();
    }

    @Override
    public List<HumanDecision> findCurrentForRun(
            UUID organizationId, UUID reviewId, UUID runId) {
        return jdbcClient.sql("""
                select distinct on (finding_id)
                       id, organization_id, review_id, finding_id, run_id, revision, action,
                       approved_action, approved_language, language_source,
                       approved_drafting_evidence_id, comment, actor_id, client_request_id,
                       decided_at, supersedes_decision_id
                from human_decision
                where organization_id = :organizationId and review_id = :reviewId
                  and run_id = :runId
                order by finding_id, revision desc
                """)
                .param("organizationId", organizationId)
                .param("reviewId", reviewId)
                .param("runId", runId)
                .query(this::map)
                .list();
    }

    @Override
    public List<HumanDecision> findHistory(
            UUID organizationId, UUID reviewId, UUID findingId, int limit) {
        return jdbcClient.sql(SELECT + """
                where organization_id = :organizationId and review_id = :reviewId
                  and finding_id = :findingId
                order by revision desc
                limit :limit
                """)
                .param("organizationId", organizationId)
                .param("reviewId", reviewId)
                .param("findingId", findingId)
                .param("limit", limit + 1)
                .query(this::map)
                .list();
    }

    @Override
    public Optional<HumanDecision> findByClientRequestId(
            UUID organizationId, UUID findingId, UUID clientRequestId) {
        return jdbcClient.sql(SELECT + """
                where organization_id = :organizationId and finding_id = :findingId
                  and client_request_id = :clientRequestId
                """)
                .param("organizationId", organizationId)
                .param("findingId", findingId)
                .param("clientRequestId", clientRequestId)
                .query(this::map)
                .optional();
    }

    @Override
    @Transactional
    public boolean insert(HumanDecision decision) {
        var inserted = jdbcClient.sql("""
                insert into human_decision
                  (id, organization_id, review_id, finding_id, run_id, revision, action,
                   approved_action, approved_language, language_source,
                   approved_drafting_evidence_id, comment, actor_id, client_request_id,
                   decided_at, supersedes_decision_id)
                values (:id, :organizationId, :reviewId, :findingId, :runId, :revision,
                   :action, :approvedAction, :approvedLanguage, :languageSource,
                   :approvedDraftingEvidenceId, :comment, :actorId, :clientRequestId,
                   :decidedAt, :supersedesDecisionId)
                """)
                .param("id", decision.id())
                .param("organizationId", decision.organizationId())
                .param("reviewId", decision.reviewId())
                .param("findingId", decision.findingId())
                .param("runId", decision.runId())
                .param("revision", decision.revision())
                .param("action", decision.action().name())
                .param("approvedAction", decision.approvedAction() == null
                        ? null : decision.approvedAction().name())
                .param("approvedLanguage", decision.approvedLanguage())
                .param("languageSource", decision.languageSource() == null
                        ? null : decision.languageSource().name())
                .param("approvedDraftingEvidenceId", decision.approvedDraftingEvidenceId())
                .param("comment", decision.comment())
                .param("actorId", decision.actorId())
                .param("clientRequestId", decision.clientRequestId())
                .param("decidedAt", timestamp(decision.decidedAt()))
                .param("supersedesDecisionId", decision.supersedesDecisionId())
                .update();
        return inserted == 1;
    }

    private static final String SELECT = """
            select id, organization_id, review_id, finding_id, run_id, revision, action,
                   approved_action, approved_language, language_source,
                   approved_drafting_evidence_id, comment, actor_id, client_request_id,
                   decided_at, supersedes_decision_id
            from human_decision
            """;

    private HumanDecision map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        var approvedAction = rs.getString("approved_action");
        var languageSource = rs.getString("language_source");
        return new HumanDecision(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("review_id", UUID.class),
                rs.getObject("finding_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("revision"),
                DecisionAction.valueOf(rs.getString("action")),
                approvedAction == null ? null : ChangeActionType.valueOf(approvedAction),
                rs.getString("approved_language"),
                languageSource == null ? null : ChangeLanguageSource.valueOf(languageSource),
                rs.getObject("approved_drafting_evidence_id", UUID.class),
                rs.getString("comment"),
                rs.getObject("actor_id", UUID.class),
                rs.getTimestamp("decided_at").toInstant(),
                rs.getObject("client_request_id", UUID.class),
                rs.getObject("supersedes_decision_id", UUID.class));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
