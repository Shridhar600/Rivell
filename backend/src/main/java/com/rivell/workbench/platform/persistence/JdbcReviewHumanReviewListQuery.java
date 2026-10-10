package com.rivell.workbench.platform.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.ChangeLanguageSource;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingSeverity;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.ProposedLanguageSource;
import com.rivell.workbench.review.ReviewHumanReviewListInput;
import com.rivell.workbench.review.ReviewHumanReviewListQuery;
import com.rivell.workbench.review.ReviewHumanReviewSummary;
import com.rivell.workbench.workflow.DecisionAction;
import com.rivell.workbench.workflow.HumanDecision;
import com.rivell.workbench.workflow.HumanReviewResolver;

/**
 * Batched human-review state for a page of reviews.
 *
 * <p>Counts and readiness come from {@link HumanReviewResolver}, the same resolver the
 * single-review human-review read uses. Reusing it is the point: a list row cannot report
 * different counts or a different readiness from the review's own detail endpoint, because
 * there is only one implementation of those rules. Re-deriving them in SQL would create a
 * second, silently divergent copy of the domain.
 *
 * <p>Two statements cover any number of reviews: one reads each counted generation's
 * findings together with their current decisions, and one reads the frozen structure those
 * decisions target. Structure is resolved through the review's own pinned contract version,
 * so an approved change is validated against the document the reviewer actually read.
 */
@Repository
class JdbcReviewHumanReviewListQuery implements ReviewHumanReviewListQuery {

    private final JdbcClient jdbcClient;

    JdbcReviewHumanReviewListQuery(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ReviewHumanReviewSummary> findProgress(
            UUID organizationId, Collection<ReviewHumanReviewListInput> inputs) {
        if (inputs.isEmpty()) {
            return Map.of();
        }
        var byReview = new LinkedHashMap<UUID, ReviewHumanReviewListInput>();
        var runIds = new ArrayList<UUID>();
        for (var input : inputs) {
            byReview.put(input.reviewId(), input);
            if (input.countedRunId() != null) {
                runIds.add(input.countedRunId());
            }
        }
        var runIdArray = runIds.toArray(UUID[]::new);
        var resolved = runIdArray.length == 0
                ? Map.<UUID, ResolvedRun>of()
                : resolvedRuns(organizationId, runIdArray);
        var headings = headingNodeIds(organizationId,
                byReview.keySet().toArray(UUID[]::new));

        var summaries = new HashMap<UUID, ReviewHumanReviewSummary>();
        for (var input : byReview.values()) {
            var run = input.countedRunId() == null
                    ? null : resolved.get(input.countedRunId());
            var progress = HumanReviewResolver.resolve(
                    run == null ? List.<Finding>of() : run.findings(),
                    run == null ? Map.of() : run.decisions(),
                    input.analysisComplete(),
                    // A generation that completed without a single trustworthy check has
                    // nothing to decide on. Before any run completes there are no executions
                    // at all, so this must not fire for a review that has not run yet.
                    input.analysisComplete()
                            && input.plannedChecks() > 0
                            && input.completedChecks() == 0,
                    input.finalized(),
                    headings.getOrDefault(input.reviewId(), Set.of())).progress();
            summaries.put(input.reviewId(), new ReviewHumanReviewSummary(
                    progress.status().name(),
                    progress.actionableFindings(),
                    progress.resolvedFindings(),
                    progress.remainingFindings(),
                    progress.escalated(),
                    progress.readyToFinalize(),
                    progress.blockers().stream()
                            .map(blocker -> blocker.code().name())
                            .toList()));
        }
        return Map.copyOf(summaries);
    }

    /** A counted generation's findings with the current effective decision for each. */
    private record ResolvedRun(List<Finding> findings, Map<UUID, HumanDecision> decisions) {
    }

    /**
     * Findings of the given generations, each paired with its current decision.
     *
     * <p>Revisions are append-only, so the current decision is the highest revision, which
     * is how a superseded escalation stops blocking. Scoped to the generation being counted,
     * so decisions from an abandoned generation cannot decide a live one.
     */
    private Map<UUID, ResolvedRun> resolvedRuns(UUID organizationId, UUID[] runIds) {
        var rows = jdbcClient.sql("""
                with current_decision as (
                    select distinct on (d.finding_id)
                           d.id, d.organization_id, d.review_id, d.finding_id, d.run_id,
                           d.revision, d.action, d.approved_action, d.approved_language,
                           d.language_source, d.approved_drafting_evidence_id, d.comment,
                           d.actor_id, d.decided_at, d.client_request_id,
                           d.supersedes_decision_id
                    from human_decision d
                    where d.organization_id = :organizationId
                      and d.run_id = any(:runIds)
                    order by d.finding_id, d.revision desc
                )
                select f.id, f.organization_id, f.review_id, f.run_id,
                       f.consolidation_key, f.subject_kind, f.subject_heading_node_id,
                       f.absence_concept, f.concept, f.secondary_concept, f.issue_family,
                       f.observation_kind, f.observation_text, f.severity,
                       f.review_classification, f.proposed_action, f.proposed_language,
                       f.proposed_language_source, f.approved_drafting_evidence_id,
                       f.created_at,
                       cd.id as d_id, cd.organization_id as d_organization_id,
                       cd.review_id as d_review_id, cd.finding_id as d_finding_id,
                       cd.run_id as d_run_id, cd.revision as d_revision,
                       cd.action as d_action, cd.approved_action as d_approved_action,
                       cd.approved_language as d_approved_language,
                       cd.language_source as d_language_source,
                       cd.approved_drafting_evidence_id as d_evidence_id,
                       cd.comment as d_comment, cd.actor_id as d_actor_id,
                       cd.decided_at as d_decided_at,
                       cd.client_request_id as d_client_request_id,
                       cd.supersedes_decision_id as d_supersedes_id
                from finding f
                left join current_decision cd on cd.finding_id = f.id
                where f.organization_id = :organizationId
                  and f.run_id = any(:runIds)
                """)
                .param("organizationId", organizationId)
                .param("runIds", runIds)
                .query(JdbcReviewHumanReviewListQuery::resolvedRow)
                .list();

        var findings = new LinkedHashMap<UUID, List<Finding>>();
        var decisions = new LinkedHashMap<UUID, Map<UUID, HumanDecision>>();
        for (var row : rows) {
            findings.computeIfAbsent(row.finding().runId(),
                    key -> new ArrayList<>()).add(row.finding());
            if (row.decision() != null) {
                decisions.computeIfAbsent(row.finding().runId(),
                        key -> new HashMap<>()).put(row.finding().id(), row.decision());
            }
        }
        var resolved = new HashMap<UUID, ResolvedRun>();
        findings.forEach((runId, runFindings) -> resolved.put(runId,
                new ResolvedRun(List.copyOf(runFindings),
                        Map.copyOf(decisions.getOrDefault(runId, Map.of())))));
        return resolved;
    }

    private record ResolvedRow(Finding finding, HumanDecision decision) {
    }

    private static ResolvedRow resolvedRow(ResultSet rs, int rowNum) throws SQLException {
        var decisionId = rs.getObject("d_id", UUID.class);
        return new ResolvedRow(finding(rs),
                decisionId == null ? null : new HumanDecision(
                        decisionId,
                        rs.getObject("d_organization_id", UUID.class),
                        rs.getObject("d_review_id", UUID.class),
                        rs.getObject("d_finding_id", UUID.class),
                        rs.getObject("d_run_id", UUID.class),
                        rs.getInt("d_revision"),
                        DecisionAction.valueOf(rs.getString("d_action")),
                        enumOrNull(rs.getString("d_approved_action"),
                                ChangeActionType.class),
                        rs.getString("d_approved_language"),
                        enumOrNull(rs.getString("d_language_source"),
                                ChangeLanguageSource.class),
                        rs.getObject("d_evidence_id", UUID.class),
                        rs.getString("d_comment"),
                        rs.getObject("d_actor_id", UUID.class),
                        rs.getObject("d_decided_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("d_client_request_id", UUID.class),
                        rs.getObject("d_supersedes_id", UUID.class)));
    }

    private static Finding finding(ResultSet rs) throws SQLException {
        return new Finding(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("review_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("consolidation_key"),
                FindingSubjectKind.valueOf(rs.getString("subject_kind")),
                rs.getObject("subject_heading_node_id", UUID.class),
                conceptOrNull(rs.getString("absence_concept")),
                ConceptKey.of(rs.getString("concept")),
                conceptOrNull(rs.getString("secondary_concept")),
                IssueFamily.valueOf(rs.getString("issue_family")),
                rs.getString("observation_kind"),
                rs.getString("observation_text"),
                FindingSeverity.valueOf(rs.getString("severity")),
                FindingClassification.valueOf(rs.getString("review_classification")),
                ProposedAction.valueOf(rs.getString("proposed_action")),
                rs.getString("proposed_language"),
                ProposedLanguageSource.valueOf(
                        rs.getString("proposed_language_source")),
                rs.getObject("approved_drafting_evidence_id", UUID.class),
                Map.of(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                List.of(), List.of(), List.of(), List.of());
    }

    private static <E extends Enum<E>> E enumOrNull(String value, Class<E> type) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static ConceptKey conceptOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ConceptKey.of(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Frozen heading nodes of each review's pinned contract version.
     *
     * <p>Read through {@code review.contract_version_id}, which is the version an approved
     * change may legitimately target. Resolving the contract's latest version instead would
     * let a later re-enrichment invalidate a decision made against the document the
     * reviewer actually read.
     */
    private Map<UUID, Set<UUID>> headingNodeIds(UUID organizationId, UUID[] reviewIds) {
        var rows = jdbcClient.sql("""
                select r.id as review_id, n.id as heading_node_id
                from review r
                left join document_node n
                  on n.contract_version_id = r.contract_version_id
                 and n.node_type = 'HEADING'
                where r.organization_id = :organizationId
                  and r.id = any(:reviewIds)
                """)
                .param("organizationId", organizationId)
                .param("reviewIds", reviewIds)
                .query((rs, rowNum) -> new HeadingRow(
                        rs.getObject("review_id", UUID.class),
                        rs.getObject("heading_node_id", UUID.class)))
                .list();

        // A version with no HEADING nodes yields one row with a null heading id; the review
        // still needs its (empty) entry.
        var headings = new HashMap<UUID, Set<UUID>>();
        for (var row : rows) {
            var ids = headings.computeIfAbsent(row.reviewId(), key -> new HashSet<UUID>());
            if (row.headingNodeId() != null) {
                ids.add(row.headingNodeId());
            }
        }
        return headings;
    }

    private record HeadingRow(UUID reviewId, UUID headingNodeId) {
    }
}