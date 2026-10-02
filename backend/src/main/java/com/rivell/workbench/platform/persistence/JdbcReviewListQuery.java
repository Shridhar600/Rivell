package com.rivell.workbench.platform.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.rivell.workbench.review.ReviewExecutionCounts;
import com.rivell.workbench.review.ReviewListQuery;
import com.rivell.workbench.review.ReviewListRow;
import com.rivell.workbench.review.ReviewListRunState;

/**
 * Organization-scoped review list projection for Home and the reviews list.
 *
 * <p>The page query reaches the contract version through the review's own
 * {@code contract_version_id}, never through a latest-version lookup, so a row's filename
 * is the version the review actually froze. That is the only form in which the pin is a
 * property of the query rather than a convention someone has to remember.
 *
 * <p>Everything after the page read is an aggregate grouped over the ids already on the
 * page, so a page costs the same number of statements whether it holds two rows or a
 * hundred. The id lists are bound as a {@code uuid[]} and matched with {@code = any(...)},
 * which is the idiom already used elsewhere in this persistence layer.
 */
@Repository
class JdbcReviewListQuery implements ReviewListQuery {

    private static final ReviewListRunState NO_RUNS = new ReviewListRunState(
            null, null, null, null, null, null);

    private final JdbcClient jdbcClient;

    JdbcReviewListQuery(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewListRow> findPage(
            UUID organizationId, UUID contractId,
            Instant afterCreatedAt, UUID afterId, int limit) {
        var sql = new StringBuilder("""
                select r.id, r.contract_id, r.contract_version_number, sa.filename,
                       r.status, r.failure_reason, r.created_at, r.started_at, r.completed_at
                from review r
                join contract_version cv
                  on cv.id = r.contract_version_id
                 and cv.organization_id = r.organization_id
                join source_artifact sa
                  on sa.id = cv.source_artifact_id
                 and sa.organization_id = r.organization_id
                where r.organization_id = :organizationId
                """);
        if (contractId != null) {
            sql.append("  and r.contract_id = :contractId\n");
        }
        if (afterCreatedAt != null && afterId != null) {
            // Keyset resume on the same total order as the query's ORDER BY. created_at is
            // bound as a timestamp at full microsecond precision: binding a truncated value
            // would exclude rows sharing that millisecond and silently drop them.
            sql.append("""
                      and (r.created_at, r.id) < (
                            cast(:afterCreatedAt as timestamptz), cast(:afterId as uuid))
                    """);
        }
        sql.append(" order by r.created_at desc, r.id desc\n limit :limit");

        var client = jdbcClient.sql(sql.toString())
                .param("organizationId", organizationId)
                // Read limit + 1, as every other bounded read in this layer does, so the
                // caller can detect that more rows exist without a second count query.
                .param("limit", limit + 1);
        if (contractId != null) {
            client = client.param("contractId", contractId);
        }
        if (afterCreatedAt != null && afterId != null) {
            client = client
                    .param("afterCreatedAt", OffsetDateTime.ofInstant(afterCreatedAt, ZoneOffset.UTC))
                    .param("afterId", afterId.toString());
        }
        return client.query((rs, rowNum) -> new ReviewListRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("contract_id", UUID.class),
                        rs.getInt("contract_version_number"),
                        rs.getString("filename"),
                        rs.getString("status"),
                        rs.getString("failure_reason"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        instant(rs, "started_at"),
                        instant(rs, "completed_at")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ReviewListRunState> findRunStates(
            UUID organizationId, Collection<UUID> reviewIds) {
        // Total by contract: a review that has never run still gets an entry, so a caller
        // cannot mistake "absent" for "unknown".
        var states = new HashMap<UUID, ReviewListRunState>();
        reviewIds.forEach(id -> states.put(id, NO_RUNS));
        if (states.isEmpty()) {
            return states;
        }

        var ids = reviewIds.toArray(UUID[]::new);
        jdbcClient.sql("""
                with latest as (
                    select distinct on (review_id) review_id, id, run_number, status
                    from review_analysis_run
                    where organization_id = :organizationId and review_id = any(:reviewIds)
                    order by review_id, run_number desc
                ), latest_completed as (
                    select distinct on (review_id) review_id, id, run_number, status
                    from review_analysis_run
                    where organization_id = :organizationId
                      and review_id = any(:reviewIds)
                      and status = 'COMPLETED'
                    order by review_id, run_number desc
                )
                select l.review_id,
                       c.id as completed_id, c.run_number as completed_number,
                       c.status as completed_status,
                       l.id as latest_id, l.run_number as latest_number, l.status as latest_status
                from latest l
                left join latest_completed c on c.review_id = l.review_id
                """)
                .param("organizationId", organizationId)
                .param("reviewIds", ids)
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("review_id", UUID.class),
                        new ReviewListRunState(
                                rs.getObject("completed_id", UUID.class),
                                nullableInt(rs, "completed_number"),
                                rs.getString("completed_status"),
                                rs.getObject("latest_id", UUID.class),
                                nullableInt(rs, "latest_number"),
                                rs.getString("latest_status"))))
                .list()
                .forEach(entry -> states.put(entry.getKey(), entry.getValue()));
        return states;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> countPlannedChecks(
            UUID organizationId, Collection<UUID> reviewIds) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        return countsByReview(organizationId, reviewIds, """
                select review_id, count(*) as total
                from review_plan_check
                where organization_id = :organizationId and review_id = any(:reviewIds)
                group by review_id
                """);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ReviewExecutionCounts> countExecutions(
            UUID organizationId, Collection<UUID> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        return jdbcClient.sql("""
                select run_id,
                       count(*) filter (where status = 'COMPLETED') as completed,
                       count(*) filter (where status = 'DEGRADED') as degraded,
                       count(*) filter (where status = 'FAILED') as failed
                from review_check_execution
                where organization_id = :organizationId and run_id = any(:runIds)
                group by run_id
                """)
                .param("organizationId", organizationId)
                .param("runIds", runIds.toArray(UUID[]::new))
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("run_id", UUID.class),
                        new ReviewExecutionCounts(
                                rs.getInt("completed"),
                                rs.getInt("degraded"),
                                rs.getInt("failed"))))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> countSignals(UUID organizationId, Collection<UUID> runIds) {
        return runCounts(organizationId, runIds, "raw_review_signal");
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> countFindings(UUID organizationId, Collection<UUID> runIds) {
        return runCounts(organizationId, runIds, "finding");
    }

    private Map<UUID, Integer> countsByReview(
            UUID organizationId, Collection<UUID> reviewIds, String sql) {
        return jdbcClient.sql(sql)
                .param("organizationId", organizationId)
                .param("reviewIds", reviewIds.toArray(UUID[]::new))
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("review_id", UUID.class), rs.getInt("total")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<UUID, Integer> runCounts(
            UUID organizationId, Collection<UUID> runIds, String table) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        return jdbcClient.sql("""
                select run_id, count(*) as total
                from %s
                where organization_id = :organizationId and run_id = any(:runIds)
                group by run_id
                """.formatted(table))
                .param("organizationId", organizationId)
                .param("runIds", runIds.toArray(UUID[]::new))
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("run_id", UUID.class), rs.getInt("total")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        var value = rs.getObject(column);
        return value == null ? null : rs.getInt(column);
    }
}