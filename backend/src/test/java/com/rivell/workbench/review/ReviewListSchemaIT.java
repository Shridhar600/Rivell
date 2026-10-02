package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Schema-level guards for the M10B-B1 organization-wide list reads.
 *
 * <p>Two different things are asserted here, and the distinction matters.
 *
 * <p><b>Indexes (V21).</b> The list queries resume with a keyset predicate on
 * {@code (created_at, id)} and order by {@code created_at DESC, id DESC}. That is only
 * efficient while the index is declared in the same direction, so the exact column order
 * and ordering direction of the V21 indexes are asserted here rather than assumed. A
 * future edit that changes {@code id DESC} to the default ascending would silently turn
 * an ordered index resume into a scan-and-sort.
 *
 * <p><b>Tenant scoping.</b> {@code review} rows are bound to their contract version by a
 * composite foreign key that carries {@code organization_id}, which makes a
 * cross-organization review row structurally unrepresentable. That is the honest reason
 * the organization predicates in the list joins cannot be proven load-bearing by any
 * fixture: deleting them would leave this suite green, because there is no contaminating
 * row for them to wrongly include. The same limit is documented in
 * {@code ReviewDiagnosticsQueryIT}; the predicate-presence guarantee is asserted there by
 * SQL-shape assertions rather than here by a fixture.
 */
@SpringBootTest
@Testcontainers
class ReviewListSchemaIT {

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
    }

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void appliesTheHomeListIndexMigration() {
        assertThat(jdbcClient.sql("""
                select version from flyway_schema_history
                where version = '21' and success
                """).query(String.class).optional()).isPresent();
    }

    @Test
    void organizationWideListIndexesMatchTheQueryOrderingExactly() {
        var definition = indexDefinition("idx_review_org_created");
        assertThat(definition)
                .as("review list orders by created_at desc, id desc, so the keyset index "
                        + "must be declared in that direction or the resume degrades to a sort")
                .contains("organization_id")
                .contains("created_at DESC")
                .contains("id DESC");

        var contract = indexDefinition("idx_contract_org_created");
        assertThat(contract)
                .as("contract list orders by created_at desc, id desc")
                .contains("organization_id")
                .contains("created_at DESC")
                .contains("id DESC");
    }

    @Test
    void thePrefixOnlyContractIndexIsSuperseded() {
        assertThat(indexExists("idx_contract_organization"))
                .as("idx_contract_org_created is a superset, exactly as V20 removed "
                        + "idx_review_org_active; keeping both would double contract write cost")
                .isFalse();
    }

    @Test
    void reviewIsBoundToItsContractVersionIncludingOrganization() {
        var keys = jdbcClient.sql("""
                select conname, pg_get_constraintdef(oid) as definition
                from pg_constraint
                where contype = 'f'
                  and conrelid::regclass::text = 'review'
                """)
                .query((rs, rowNum) -> rs.getString("conname") + "|" + rs.getString("definition"))
                .list();

        assertThat(keys)
                .as("this is why the list join's organization predicates cannot be proven "
                        + "load-bearing by a fixture: a cross-organization review row is "
                        + "unrepresentable, so deleting a predicate changes no result")
                .anyMatch(k -> k.contains("fk_review_contract_version_identity_org")
                        && k.contains("FOREIGN KEY (contract_version_id, contract_id, "
                                + "contract_version_number, organization_id)"));
    }

    @Test
    void reviewIdentityIsUniqueAndKeyed() {
        assertThat(jdbcClient.sql("""
                select count(*) from pg_constraint
                where conrelid::regclass::text = 'review' and contype in ('p','u')
                  and pg_get_constraintdef(oid) like '%(id, organization_id)%'
                """).query(Integer.class).single()).isPositive();

        assertThat(jdbcClient.sql("""
                select count(*) from pg_index i
                join pg_class t on t.oid = i.indrelid
                where t.relname = 'review' and i.indisprimary
                """).query(Integer.class).single())
                .as("the keyset ordering is a strict total order only because id is the "
                        + "primary key; a nullable or non-unique id would allow duplicates")
                .isEqualTo(1);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void theOrganizationWideKeysetScanNeedsNoSortBecauseTheIndexOrdersCorrectly() {
        var organizationId = UUID.randomUUID();
        seedReviewRows(organizationId, 40);

        jdbcClient.sql("analyze review").update();

        // The property under test is that the index can satisfy the ORDER BY itself, so a
        // bounded page resumes as an ordered walk instead of sorting every matching row.
        // Sequential scans are disabled so the assertion measures index ordering rather
        // than the planner's cost choice on a 40-row table, where a sequential scan plus
        // sort is genuinely the cheaper plan. With sequential scans unavailable, a wrong
        // index direction forces a Sort node and this test fails.
        jdbcClient.sql("set local enable_seqscan = off").update();

        var rendered = String.join("\n", explainKeysetScan(organizationId)).toLowerCase();
        assertThat(rendered)
                .as("V21 declares (organization_id, created_at desc, id desc) to match "
                        + "ORDER BY created_at desc, id desc. If the direction were wrong the "
                        + "index could not supply this ordering and the plan would sort.")
                .doesNotContain("sort");
        assertThat(rendered)
                .as("the resume must read the new V21 index")
                .contains("idx_review_org_created");
    }

    private List<String> explainKeysetScan(UUID organizationId) {
        return jdbcClient.sql("""
                explain (format text)
                select r.id
                from review r
                join contract_version cv
                  on cv.id = r.contract_version_id
                 and cv.organization_id = r.organization_id
                join source_artifact sa
                  on sa.id = cv.source_artifact_id
                 and sa.organization_id = r.organization_id
                where r.organization_id = :organizationId
                  and (r.created_at, r.id) < (
                        cast(:afterCreatedAt as timestamptz), cast(:afterId as uuid))
                order by r.created_at desc, r.id desc
                limit 21
                """)
                .param("organizationId", organizationId)
                .param("afterCreatedAt", "2026-02-03T04:05:06.123456Z")
                .param("afterId", UUID.fromString(
                        "ffffffff-ffff-ffff-ffff-ffffffffffff").toString())
                .query(String.class)
                .list();
    }

    private void seedReviewRows(UUID organizationId, int count) {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var artifactId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256,
                   storage_key, created_at)
                values (:id, :organizationId, 'seed.docx', 'application/docx', 10,
                        repeat('a', 64), 'seed', now())
                """)
                .param("id", artifactId)
                .param("organizationId", organizationId)
                .update();
        jdbcClient.sql("""
                insert into contract (id, organization_id, title, created_at)
                values (:id, :organizationId, null, now())
                """)
                .param("id", contractId)
                .param("organizationId", organizationId)
                .update();
        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, organization_id,
                   role, parser_version, parse_status, parse_warnings, created_at)
                values (:id, :contractId, 1, :artifactId, :organizationId, 'ORIGINAL',
                        'test-parser-1', 'OK', '[]'::jsonb, now())
                """)
                .param("id", versionId)
                .param("contractId", contractId)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .update();

        for (var i = 0; i < count; i++) {
            jdbcClient.sql("""
                    insert into review
                      (id, organization_id, contract_id, contract_version_id,
                       contract_version_number, status, failure_reason, created_by,
                       client_request_id, created_at, updated_at, started_at, completed_at)
                    values (:id, :organizationId, :contractId, :versionId, 1,
                            'COMPLETED', null, :userId, null,
                            :createdAt, :createdAt, :createdAt, :createdAt)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("organizationId", organizationId)
                    .param("contractId", contractId)
                    .param("versionId", versionId)
                    .param("userId", UUID.randomUUID())
                    .param("createdAt", java.time.OffsetDateTime.parse(
                            "2026-02-03T04:05:06.123456Z"))
                    .update();
        }
    }

    private String indexDefinition(String name) {
        return jdbcClient.sql("select indexdef from pg_indexes where indexname = :name")
                .param("name", name)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new AssertionError("missing index: " + name));
    }

    private boolean indexExists(String name) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("select exists (select 1 from pg_indexes where indexname = :name)")
                .param("name", name)
                .query(Boolean.class)
                .single());
    }
}