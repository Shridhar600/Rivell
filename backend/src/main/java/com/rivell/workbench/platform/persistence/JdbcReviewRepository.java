package com.rivell.workbench.platform.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.review.CheckExpectation;
import com.rivell.workbench.review.CheckFamily;
import com.rivell.workbench.review.CheckMechanism;
import com.rivell.workbench.review.ModelPolicyIdentity;
import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewPlanCheck;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewSnapshot;
import com.rivell.workbench.review.ReviewStatus;

@Repository
class JdbcReviewRepository implements ReviewRepository {

    private final JdbcClient jdbcClient;

    JdbcReviewRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public void create(Review review, ReviewSnapshot snapshot, List<ReviewPlanCheck> checks) {
        jdbcClient.sql("""
                insert into review
                  (id, organization_id, contract_id, contract_version_id,
                   contract_version_number, status, failure_reason, created_by,
                   created_at, updated_at, started_at, completed_at)
                values (:id, :organizationId, :contractId, :contractVersionId,
                   :contractVersionNumber, :status, :failureReason, :createdBy,
                   :createdAt, :updatedAt, :startedAt, :completedAt)
                """)
                .param("id", review.id())
                .param("organizationId", review.organizationId())
                .param("contractId", review.contractId())
                .param("contractVersionId", review.contractVersionId())
                .param("contractVersionNumber", review.contractVersionNumber())
                .param("status", review.status().name())
                .param("failureReason", review.failureReason())
                .param("createdBy", review.createdBy())
                .param("createdAt", timestamp(review.createdAt()))
                .param("updatedAt", timestamp(review.updatedAt()))
                .param("startedAt", review.startedAt() == null ? null : timestamp(review.startedAt()))
                .param("completedAt", review.completedAt() == null ? null : timestamp(review.completedAt()))
                .update();

        jdbcClient.sql("""
                insert into review_snapshot
                  (review_id, organization_id, contract_id, contract_version_id,
                   contract_version_number, contract_semantics_id,
                   semantics_pipeline_version, semantics_digest, contract_type,
                   our_side_role, paper_source, jurisdiction, contract_pack_key,
                   pack_version, knowledge_pin_number, effective_on,
                   review_pipeline_version, embedding_index_version,
                   embedding_model_identity, decision_provider_id, decision_model_alias,
                   generative_provider_id, generative_model_alias, created_at)
                values (:reviewId, :organizationId, :contractId, :contractVersionId,
                   :contractVersionNumber, :contractSemanticsId,
                   :semanticsPipelineVersion, :semanticsDigest, :contractType,
                   :ourSideRole, :paperSource, :jurisdiction, :contractPackKey,
                   :packVersion, :knowledgePinNumber, :effectiveOn,
                   :reviewPipelineVersion, :embeddingIndexVersion,
                   :embeddingModelIdentity, :decisionProviderId, :decisionModelAlias,
                   :generativeProviderId, :generativeModelAlias, :createdAt)
                """)
                .param("reviewId", snapshot.reviewId())
                .param("organizationId", snapshot.organizationId())
                .param("contractId", snapshot.contractId())
                .param("contractVersionId", snapshot.contractVersionId())
                .param("contractVersionNumber", snapshot.contractVersionNumber())
                .param("contractSemanticsId", snapshot.contractSemanticsId())
                .param("semanticsPipelineVersion", snapshot.semanticsPipelineVersion())
                .param("semanticsDigest", snapshot.semanticsDigest())
                .param("contractType", snapshot.contractType().name())
                .param("ourSideRole", snapshot.ourSideRole() == null ? null : snapshot.ourSideRole().name())
                .param("paperSource", snapshot.paperSource() == null ? null : snapshot.paperSource().name())
                .param("jurisdiction", snapshot.jurisdiction().name())
                .param("contractPackKey", snapshot.contractPackKey())
                .param("packVersion", snapshot.packVersion())
                .param("knowledgePinNumber", snapshot.knowledgePinNumber())
                .param("effectiveOn", snapshot.effectiveOn())
                .param("reviewPipelineVersion", snapshot.reviewPipelineVersion())
                .param("embeddingIndexVersion", snapshot.embeddingIndexVersion())
                .param("embeddingModelIdentity", snapshot.embeddingModelIdentity())
                .param("decisionProviderId", snapshot.modelPolicy().decisionProviderId())
                .param("decisionModelAlias", snapshot.modelPolicy().decisionModelAlias())
                .param("generativeProviderId", snapshot.modelPolicy().generativeProviderId())
                .param("generativeModelAlias", snapshot.modelPolicy().generativeModelAlias())
                .param("createdAt", timestamp(snapshot.createdAt()))
                .update();

        for (var check : checks) {
            jdbcClient.sql("""
                    insert into review_plan_check
                      (id, review_id, organization_id, ordinal, check_key, family,
                       expectation, mechanism, concept, secondary_concept,
                       subject_heading_node_id, created_at)
                    values (:id, :reviewId, :organizationId, :ordinal, :checkKey, :family,
                       :expectation, :mechanism, :concept, :secondaryConcept,
                       :subjectHeadingNodeId, :createdAt)
                    """)
                    .param("id", check.id())
                    .param("reviewId", check.reviewId())
                    .param("organizationId", check.organizationId())
                    .param("ordinal", check.ordinal())
                    .param("checkKey", check.checkKey())
                    .param("family", check.family().name())
                    .param("expectation", check.expectation() == null ? null : check.expectation().name())
                    .param("mechanism", check.mechanism().name())
                    .param("concept", check.concept().name())
                    .param("secondaryConcept", check.secondaryConcept() == null ? null : check.secondaryConcept().name())
                    .param("subjectHeadingNodeId", check.subjectHeadingNodeId())
                    .param("createdAt", timestamp(check.createdAt()))
                    .update();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Review> find(UUID organizationId, UUID reviewId) {
        return jdbcClient.sql("""
                select id, organization_id, contract_id, contract_version_id,
                       contract_version_number, status, failure_reason, created_by,
                       created_at, updated_at, started_at, completed_at
                from review
                where id = :reviewId and organization_id = :organizationId
                """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new Review(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("contract_id", UUID.class),
                        rs.getObject("contract_version_id", UUID.class),
                        rs.getInt("contract_version_number"),
                        ReviewStatus.valueOf(rs.getString("status")),
                        rs.getString("failure_reason"),
                        rs.getObject("created_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewSnapshot> findSnapshot(UUID organizationId, UUID reviewId) {
        return jdbcClient.sql("""
                select review_id, organization_id, contract_id, contract_version_id,
                       contract_version_number, contract_semantics_id,
                       semantics_pipeline_version, semantics_digest, contract_type,
                       our_side_role, paper_source, jurisdiction, contract_pack_key,
                       pack_version, knowledge_pin_number, effective_on,
                       review_pipeline_version, embedding_index_version,
                       embedding_model_identity, decision_provider_id, decision_model_alias,
                       generative_provider_id, generative_model_alias, created_at
                from review_snapshot
                where review_id = :reviewId and organization_id = :organizationId
                """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new ReviewSnapshot(
                        rs.getObject("review_id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("contract_id", UUID.class),
                        rs.getObject("contract_version_id", UUID.class),
                        rs.getInt("contract_version_number"),
                        ContractType.valueOf(rs.getString("contract_type")),
                        rs.getString("our_side_role") == null ? null : PartyRole.valueOf(rs.getString("our_side_role")),
                        rs.getString("paper_source") == null ? null : PaperSource.valueOf(rs.getString("paper_source")),
                        Jurisdiction.valueOf(rs.getString("jurisdiction")),
                        rs.getString("contract_pack_key"),
                        rs.getString("pack_version"),
                        rs.getObject("contract_semantics_id", UUID.class),
                        rs.getString("semantics_pipeline_version"),
                        rs.getString("semantics_digest"),
                        rs.getLong("knowledge_pin_number"),
                        rs.getObject("effective_on", java.time.LocalDate.class),
                        rs.getString("review_pipeline_version"),
                        rs.getString("embedding_index_version"),
                        rs.getString("embedding_model_identity"),
                        new ModelPolicyIdentity(
                                rs.getString("decision_provider_id"),
                                rs.getString("decision_model_alias"),
                                rs.getString("generative_provider_id"),
                                rs.getString("generative_model_alias")),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewPlanCheck> findPlanChecks(UUID organizationId, UUID reviewId) {
        return jdbcClient.sql("""
                select id, review_id, organization_id, ordinal, check_key, family,
                       expectation, mechanism, concept, secondary_concept,
                       subject_heading_node_id, created_at
                from review_plan_check
                where review_id = :reviewId and organization_id = :organizationId
                order by ordinal
                """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new ReviewPlanCheck(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("review_id", UUID.class),
                        rs.getInt("ordinal"),
                        rs.getString("check_key"),
                        CheckFamily.valueOf(rs.getString("family")),
                        rs.getString("expectation") == null ? null : CheckExpectation.valueOf(rs.getString("expectation")),
                        CheckMechanism.valueOf(rs.getString("mechanism")),
                        LegalConcept.valueOf(rs.getString("concept")),
                        rs.getString("secondary_concept") == null ? null : LegalConcept.valueOf(rs.getString("secondary_concept")),
                        rs.getObject("subject_heading_node_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static OffsetDateTime timestamp(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @Override
    @Transactional
    public boolean markAnalyzing(UUID organizationId, UUID reviewId, java.time.Instant at) {
        return jdbcClient.sql("""
                update review set status = 'ANALYZING', started_at = :at, updated_at = :at
                where id = :reviewId and organization_id = :organizationId
                  and status = 'ANALYSIS_QUEUED'
                """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .param("at", timestamp(at))
                .update() == 1;
    }

    @Override
    @Transactional
    public boolean markCompleted(UUID organizationId, UUID reviewId, java.time.Instant at) {
        return jdbcClient.sql("""
                update review set status = 'COMPLETED', completed_at = :at, updated_at = :at
                where id = :reviewId and organization_id = :organizationId
                  and status = 'ANALYZING'
                """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .param("at", timestamp(at))
                .update() == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(UUID organizationId, UUID reviewId, String reason,
            java.time.Instant at) {
        return jdbcClient.sql("""
                update review set status = 'FAILED', failure_reason = :reason,
                    completed_at = :at, updated_at = :at
                where id = :reviewId and organization_id = :organizationId
                  and status in ('ANALYSIS_QUEUED', 'ANALYZING')
                """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .param("reason", reason)
                .param("at", timestamp(at))
                .update() == 1;
    }
}
