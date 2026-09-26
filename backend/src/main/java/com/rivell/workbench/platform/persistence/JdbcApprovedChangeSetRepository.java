package com.rivell.workbench.platform.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.redline.ApprovedChange;
import com.rivell.workbench.redline.ApprovedChangeSet;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.ChangeLanguageSource;
import com.rivell.workbench.redline.ChangeTargetKind;

@Repository
class JdbcApprovedChangeSetRepository implements ApprovedChangeSetRepository {

    private final JdbcClient jdbcClient;

    JdbcApprovedChangeSetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ApprovedChangeSet> findByReview(UUID organizationId, UUID reviewId) {
        var header = jdbcClient.sql("""
                select id, organization_id, review_id, run_id, contract_id,
                       contract_version_id, contract_version_number, change_set_version,
                       finalized_by, finalized_at
                from review_finalization
                where organization_id = :organizationId and review_id = :reviewId
                """)
                .param("organizationId", organizationId)
                .param("reviewId", reviewId)
                .query((rs, rowNum) -> new Header(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("review_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("contract_id", UUID.class),
                        rs.getObject("contract_version_id", UUID.class),
                        rs.getInt("contract_version_number"),
                        rs.getInt("change_set_version"),
                        rs.getObject("finalized_by", UUID.class),
                        rs.getTimestamp("finalized_at").toInstant()))
                .optional();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toSet(header.get(), changes(header.get().id(), organizationId)));
    }

    @Override
    @Transactional
    public boolean freeze(ApprovedChangeSet set) {
        var inserted = jdbcClient.sql("""
                insert into review_finalization
                  (id, organization_id, review_id, run_id, contract_id, contract_version_id,
                   contract_version_number, change_set_version, finalized_by, finalized_at)
                values (:id, :organizationId, :reviewId, :runId, :contractId,
                   :contractVersionId, :contractVersionNumber, :changeSetVersion,
                   :finalizedBy, :finalizedAt)
                on conflict (review_id) do nothing
                """)
                .param("id", set.id())
                .param("organizationId", set.organizationId())
                .param("reviewId", set.reviewId())
                .param("runId", set.runId())
                .param("contractId", set.contractId())
                .param("contractVersionId", set.contractVersionId())
                .param("contractVersionNumber", set.contractVersionNumber())
                .param("changeSetVersion", set.changeSetVersion())
                .param("finalizedBy", set.finalizedBy())
                .param("finalizedAt", timestamp(set.finalizedAt()))
                .update();
        if (inserted == 0) {
            return false;
        }
        for (var change : set.changes()) {
            insertChange(change);
        }
        return true;
    }

    private void insertChange(ApprovedChange change) {
        jdbcClient.sql("""
                insert into approved_change
                  (id, organization_id, change_set_id, review_id, ordinal, finding_id,
                   decision_id, subject_kind, action_type, target_heading_node_id,
                   absence_concept, approved_language, language_source,
                   approved_drafting_evidence_id)
                values (:id, :organizationId, :changeSetId, :reviewId, :ordinal, :findingId,
                   :decisionId, :subjectKind, :actionType, :targetHeadingNodeId,
                   :absenceConcept, :approvedLanguage, :languageSource,
                   :approvedDraftingEvidenceId)
                """)
                .param("id", change.id())
                .param("organizationId", change.organizationId())
                .param("changeSetId", change.changeSetId())
                .param("reviewId", change.reviewId())
                .param("ordinal", change.ordinal())
                .param("findingId", change.findingId())
                .param("decisionId", change.decisionId())
                .param("subjectKind", change.targetKind().name())
                .param("actionType", change.actionType().name())
                .param("targetHeadingNodeId", change.targetHeadingNodeId())
                .param("absenceConcept", change.absenceConcept() == null
                        ? null : change.absenceConcept().name())
                .param("approvedLanguage", change.approvedLanguage())
                .param("languageSource", change.languageSource().name())
                .param("approvedDraftingEvidenceId", change.approvedDraftingEvidenceId())
                .update();
    }

    private List<ApprovedChange> changes(UUID changeSetId, UUID organizationId) {
        return jdbcClient.sql("""
                select id, organization_id, change_set_id, review_id, ordinal, finding_id,
                       decision_id, subject_kind, action_type, target_heading_node_id,
                       absence_concept, approved_language, language_source,
                       approved_drafting_evidence_id
                from approved_change
                where change_set_id = :changeSetId and organization_id = :organizationId
                order by ordinal
                """)
                .param("changeSetId", changeSetId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new ApprovedChange(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("change_set_id", UUID.class),
                        rs.getObject("review_id", UUID.class),
                        rs.getInt("ordinal"),
                        rs.getObject("finding_id", UUID.class),
                        rs.getObject("decision_id", UUID.class),
                        ChangeActionType.valueOf(rs.getString("action_type")),
                        ChangeTargetKind.valueOf(rs.getString("subject_kind")),
                        rs.getObject("target_heading_node_id", UUID.class),
                        rs.getString("absence_concept") == null ? null
                                : LegalConcept.valueOf(rs.getString("absence_concept")),
                        rs.getString("approved_language"),
                        ChangeLanguageSource.valueOf(rs.getString("language_source")),
                        rs.getObject("approved_drafting_evidence_id", UUID.class)))
                .list();
    }

    private static ApprovedChangeSet toSet(Header header, List<ApprovedChange> changes) {
        return new ApprovedChangeSet(header.id(), header.organizationId(), header.reviewId(),
                header.runId(), header.contractId(), header.contractVersionId(),
                header.contractVersionNumber(), header.changeSetVersion(),
                header.finalizedBy(), header.finalizedAt(), new ArrayList<>(changes));
    }

    private record Header(
            UUID id, UUID organizationId, UUID reviewId, UUID runId, UUID contractId,
            UUID contractVersionId, int contractVersionNumber, int changeSetVersion,
            UUID finalizedBy, Instant finalizedAt) {
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
