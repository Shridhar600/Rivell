package com.rivell.workbench.platform.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.rivell.workbench.review.PinnedDocument;
import com.rivell.workbench.review.ReviewDocumentQuery;

/**
 * Reads the uploaded document behind a pinned contract version in one statement.
 *
 * <p>The version id is the review's own frozen {@code contract_version_id}, so the
 * organization predicate is what scopes the row rather than a contract lookup that could
 * land on a different version. Size comes from the version's own source artifact: that
 * artifact is immutable, and it is the thing the reviewer actually uploaded.
 *
 * <p>Both sides of the join are organization-scoped. {@code source_artifact} carries
 * {@code organization_id} directly and {@code contract_version} reaches it through the
 * version, so a row cannot be read across a tenant boundary even if a version id were
 * somehow supplied from another organization.
 */
@Repository
class JdbcReviewDocumentQuery implements ReviewDocumentQuery {

    private final JdbcClient jdbcClient;

    JdbcReviewDocumentQuery(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<PinnedDocument> find(UUID organizationId, UUID contractVersionId) {
        return jdbcClient.sql("""
                select sa.filename, cv.created_at, sa.size_bytes
                from contract_version cv
                join source_artifact sa
                  on sa.id = cv.source_artifact_id
                 and sa.organization_id = :organizationId
                where cv.id = :contractVersionId
                  and sa.organization_id = :organizationId
                """)
                .param("organizationId", organizationId)
                .param("contractVersionId", contractVersionId)
                .query((rs, rowNum) -> new PinnedDocument(
                        rs.getString("filename"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("size_bytes")))
                .optional();
    }
}