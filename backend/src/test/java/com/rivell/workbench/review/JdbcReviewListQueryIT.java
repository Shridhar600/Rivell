package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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
 * Repository-level contract of {@link ReviewListQuery} against real PostgreSQL.
 *
 * <p>Written before the implementation so the SQL is proven rather than assumed, and
 * deliberately separate from the API-level tests: everything asserted here is a property
 * of the queries themselves — pinned-version resolution, keyset resumption, tenant
 * scoping and aggregate keying — and would be far harder to isolate through HTTP.
 *
 * <p>Rows are inserted directly rather than through the ingestion and analysis services,
 * because the services can only ever mint contract version 1. The pinned-version cases
 * below are unreachable through them, and an unreachable fixture is how the pin would
 * silently stop being tested.
 */
@SpringBootTest
@Testcontainers
class JdbcReviewListQueryIT {

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

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-02-03T04:05:06.123456Z");

    @Autowired
    ReviewListQuery reviewListQuery;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void findPageReturnsEveryOrganizationReviewNewestFirst() {
        var organizationId = UUID.randomUUID();
        var older = seedReview(organizationId, "old.docx", T0, "COMPLETED");
        var newer = seedReview(organizationId, "new.docx", T0.plusHours(1), "COMPLETED");

        var page = reviewListQuery.findPage(organizationId, null, null, null, 10);

        assertThat(page).extracting(ReviewListRow::reviewId)
                .as("newest first, and every review of this organization is present")
                .containsExactly(newer, older);
    }

    @Test
    void findPageNarrowsToOneContractWhenContractIdIsGiven() {
        var organizationId = UUID.randomUUID();
        var firstContract = seedContract(organizationId);
        var secondContract = seedContract(organizationId);
        var first = seedReview(organizationId, "a.docx", firstContract, T0, "COMPLETED");
        var second = seedReview(organizationId, "b.docx", secondContract, T0, "COMPLETED");

        var page = reviewListQuery.findPage(organizationId, firstContract, null, null, 10);

        assertThat(page).extracting(ReviewListRow::reviewId).containsExactly(first);
        assertThat(page).extracting(ReviewListRow::reviewId).doesNotContain(second);
    }

    @Test
    void findPageNeverReturnsAnotherOrganizationsRows() {
        var mine = UUID.randomUUID();
        var theirs = UUID.randomUUID();
        seedReview(mine, "mine.docx", T0, "COMPLETED");
        seedReview(theirs, "theirs.docx", T0, "COMPLETED");

        assertThat(reviewListQuery.findPage(mine, null, null, null, 10))
                .extracting(ReviewListRow::contractFilename)
                .containsExactly("mine.docx");
    }

    @Test
    void aContractIdFromAnotherOrganizationCannotWidenTheResult() {
        var mine = UUID.randomUUID();
        var theirs = UUID.randomUUID();
        seedReview(mine, "mine.docx", T0, "COMPLETED");
        var theirContract = seedContract(theirs);

        assertThat(reviewListQuery.findPage(mine, theirContract, null, null, 10))
                .as("the organization predicate is unconditional, so another tenant's "
                        + "contract id narrows to nothing rather than leaking rows")
                .isEmpty();
    }

    @Test
    void keysetResumptionReturnsEveryRowExactlyOnceWhenTimestampsAreIdentical() {
        var organizationId = UUID.randomUUID();
        // Deterministic, ascending ids sharing one byte-identical timestamp. Ordering
        // within the tie must be id descending, and a resume must not drop or repeat any.
        var ids = List.of(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                UUID.fromString("00000000-0000-0000-0000-000000000004"));
        for (var id : ids) {
            insertReview(organizationId, id, seedContract(organizationId), T0, "COMPLETED");
        }

        var walked = new java.util.ArrayList<UUID>();
        java.time.Instant after = null;
        UUID afterId = null;
        for (var guard = 0; guard < 10; guard++) {
            var page = reviewListQuery.findPage(organizationId, null, after, afterId, 2);
            if (page.isEmpty()) {
                break;
            }
            // A page is read as limit + 1, so it carries the caller's two rows plus a
            // sentinel when more exist. The final page is short and has no sentinel.
            assertThat(page).hasSizeBetween(1, 3);
            var emitted = Math.min(2, page.size());
            for (var i = 0; i < emitted; i++) {
                walked.add(page.get(i).reviewId());
            }
            // Resume after the last row actually emitted, not the last row read. The
            // sentinel is a look-ahead and is never itself emitted, so positioning the
            // cursor on it would silently skip that review from the output.
            after = page.get(emitted - 1).createdAt();
            afterId = page.get(emitted - 1).reviewId();
        }

        assertThat(walked)
                .as("ties resolve strictly id-descending and every row appears once")
                .containsExactly(
                        UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        UUID.fromString("00000000-0000-0000-0000-000000000003"),
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void findPageReportsTheFilenameOfTheVersionTheReviewIsPinnedTo() {
        var organizationId = UUID.randomUUID();
        var contractId = UUID.randomUUID();
        insertContract(organizationId, contractId, "contract");
        insertVersion(organizationId, contractId, 1, "first.docx");
        insertVersion(organizationId, contractId, 2, "second.docx");

        var pinnedToFirst = seedReviewAtVersion(
                organizationId, contractId, 1, T0, "COMPLETED");
        var pinnedToSecond = seedReviewAtVersion(
                organizationId, contractId, 2, T0.plusMinutes(1), "COMPLETED");

        var page = reviewListQuery.findPage(organizationId, null, null, null, 10);
        var byReview = page.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ReviewListRow::reviewId, ReviewListRow::contractFilename));

        assertThat(byReview.get(pinnedToFirst))
                .as("the newest version is version 2; a row pinned to version 1 must still "
                        + "report version 1's filename")
                .isEqualTo("first.docx");
        assertThat(byReview.get(pinnedToSecond))
                .as("and the reverse, so a query that ignored the pin entirely cannot pass")
                .isEqualTo("second.docx");
    }

    @Test
    void findPageCarriesLifecycleAndFailureTruth() {
        var organizationId = UUID.randomUUID();
        var failed = seedReview(organizationId, "failed.docx", T0, "FAILED");
        insertFailureReason(failed, "decision_contract_error:boom");

        var row = reviewListQuery.findPage(organizationId, null, null, null, 10).get(0);

        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.failureReason()).isEqualTo("decision_contract_error:boom");
        assertThat(row.completedAt()).isNotNull();
    }

    @Test
    void findRunStatesPicksTheLatestCompletedGenerationElseTheLatest() {
        var organizationId = UUID.randomUUID();
        var reviewId = seedReview(organizationId, "a.docx", T0, "COMPLETED");

        assertThat(reviewListQuery.findRunStates(organizationId, List.of(reviewId)))
                .as("no runs yet: nothing to count")
                .containsEntry(reviewId,
                        new ReviewListRunState(null, null, null, null, null, null));

        var completed = seedRun(organizationId, reviewId, 1, "COMPLETED");
        assertThat(reviewListQuery.findRunStates(organizationId, List.of(reviewId)).get(reviewId))
                .isEqualTo(new ReviewListRunState(
                        completed, 1, "COMPLETED", completed, 1, "COMPLETED"));

        var running = seedRun(organizationId, reviewId, 2, "RUNNING");
        var state = reviewListQuery.findRunStates(organizationId, List.of(reviewId)).get(reviewId);
        assertThat(state).isEqualTo(new ReviewListRunState(
                completed, 1, "COMPLETED", running, 2, "RUNNING"));
        assertThat(state.countedRunId())
                .as("the counted generation stays the latest COMPLETED one")
                .isEqualTo(completed);
        assertThat(state.hasNewerRunThanCounted())
                .as("a newer generation is the recompute/degraded signal")
                .isTrue();
    }

    @Test
    void aggregatesAreKeyedPerIdAndRestrictedToTheGivenScope() {
        var organizationId = UUID.randomUUID();
        var reviewId = seedReview(organizationId, "a.docx", T0, "COMPLETED");
        var runId = seedRun(organizationId, reviewId, 1, "COMPLETED");
        // Three plan checks are seeded explicitly, then each execution adds its own, so
        // the execution statuses below are counted against four declared checks.
        seedPlanCheck(organizationId, reviewId);
        seedPlanCheck(organizationId, reviewId);
        seedPlanCheck(organizationId, reviewId);
        seedExecution(organizationId, reviewId, runId, "COMPLETED");
        seedExecution(organizationId, reviewId, runId, "DEGRADED");
        seedExecution(organizationId, reviewId, runId, "FAILED");
        seedSignal(organizationId, reviewId, runId);
        seedSignal(organizationId, reviewId, runId);
        seedFinding(organizationId, reviewId, runId);

        var reviewIds = List.of(reviewId);
        assertThat(reviewListQuery.countPlannedChecks(organizationId, reviewIds))
                .containsEntry(reviewId, 6);
        assertThat(reviewListQuery.countExecutions(organizationId, List.of(runId)))
                .containsEntry(runId, new ReviewExecutionCounts(1, 1, 1));
        assertThat(reviewListQuery.countSignals(organizationId, List.of(runId)))
                .containsEntry(runId, 2);
        assertThat(reviewListQuery.countFindings(organizationId, List.of(runId)))
                .containsEntry(runId, 1);
    }

    @Test
    void aggregatesOfAnotherOrganizationAreNeverReturned() {
        var mine = UUID.randomUUID();
        var theirs = UUID.randomUUID();
        var theirReview = seedReview(theirs, "theirs.docx", T0, "COMPLETED");
        var theirRun = seedRun(theirs, theirReview, 1, "COMPLETED");
        seedExecution(theirs, theirReview, theirRun, "COMPLETED");

        var myReview = seedReview(mine, "mine.docx", T0, "COMPLETED");

        assertThat(reviewListQuery.countPlannedChecks(mine, List.of(myReview)))
                .doesNotContainValue(9);
        assertThat(reviewListQuery.countExecutions(mine, List.of(theirRun)))
                .as("another tenant's run id yields no counts, not that tenant's counts")
                .doesNotContainKey(theirRun);
    }

    @Test
    void emptyInputsReturnEmptyResultsWithoutQuerying() {
        var organizationId = UUID.randomUUID();
        assertThat(reviewListQuery.findRunStates(organizationId, List.of())).isEmpty();
        assertThat(reviewListQuery.countPlannedChecks(organizationId, List.of())).isEmpty();
        assertThat(reviewListQuery.countExecutions(organizationId, List.of())).isEmpty();
        assertThat(reviewListQuery.countSignals(organizationId, List.of())).isEmpty();
        assertThat(reviewListQuery.countFindings(organizationId, List.of())).isEmpty();
    }

    @Test
    void findPageRespectsTheLimitAndReadsOneExtraRow() {
        var organizationId = UUID.randomUUID();
        for (var i = 0; i < 5; i++) {
            seedReview(organizationId, "r" + i + ".docx", T0.plusMinutes(i), "COMPLETED");
        }

        assertThat(reviewListQuery.findPage(organizationId, null, null, null, 3))
                .as("implementations read limit + 1 so the caller can detect truncation")
                .hasSize(4);
    }

    private UUID seedReview(UUID organizationId, String filename, OffsetDateTime createdAt,
            String status) {
        return seedReview(organizationId, filename, seedContract(organizationId, filename),
                createdAt, status);
    }

    private UUID seedReview(UUID organizationId, String filename, UUID contractId,
            OffsetDateTime createdAt, String status) {
        var reviewId = UUID.randomUUID();
        insertReview(organizationId, reviewId, contractId, createdAt, status);
        return reviewId;
    }

    private UUID seedReviewAtVersion(UUID organizationId, UUID contractId, int versionNumber,
            OffsetDateTime createdAt, String status) {
        var reviewId = UUID.randomUUID();
        var versionId = versionId(organizationId, contractId, versionNumber);
        insertReview(organizationId, reviewId, contractId, createdAt, status, versionId,
                versionNumber);
        return reviewId;
    }

    private void insertReview(UUID organizationId, UUID reviewId, UUID contractId,
            OffsetDateTime createdAt, String status) {
        insertReview(organizationId, reviewId, contractId, createdAt, status,
                versionId(organizationId, contractId, 1), 1);
    }

    private void insertReview(UUID organizationId, UUID reviewId, UUID contractId,
            OffsetDateTime createdAt, String status, UUID versionId, int versionNumber) {
        var failed = "FAILED".equals(status);
        var started = "ANALYSIS_QUEUED".equals(status) ? null : createdAt;
        var completed = "ANALYSIS_QUEUED".equals(status) || "ANALYZING".equals(status)
                ? null
                : createdAt;
        jdbcClient.sql("""
                insert into review
                  (id, organization_id, contract_id, contract_version_id,
                   contract_version_number, status, failure_reason, created_by,
                   client_request_id, created_at, updated_at, started_at, completed_at)
                values (:id, :organizationId, :contractId, :versionId, :versionNumber,
                        :status, :reason, :userId, null, :createdAt, :createdAt,
                        :startedAt, :completedAt)
                """)
                .param("id", reviewId)
                .param("organizationId", organizationId)
                .param("contractId", contractId)
                .param("versionId", versionId)
                .param("versionNumber", versionNumber)
                .param("status", status)
                .param("reason", failed ? "internal_reason" : null)
                .param("userId", UUID.randomUUID())
                .param("createdAt", createdAt)
                .param("startedAt", started)
                .param("completedAt", completed)
                .update();
    }

    private void insertFailureReason(UUID reviewId, String reason) {
        jdbcClient.sql("update review set failure_reason = :reason where id = :id")
                .param("reason", reason)
                .param("id", reviewId)
                .update();
    }

    private UUID seedContract(UUID organizationId) {
        return seedContract(organizationId, "contract.docx");
    }

    private UUID seedContract(UUID organizationId, String filename) {
        var contractId = UUID.randomUUID();
        insertContract(organizationId, contractId, "contract-" + contractId);
        insertVersion(organizationId, contractId, 1, filename);
        return contractId;
    }

    private void insertContract(UUID organizationId, UUID contractId, String title) {
        jdbcClient.sql("""
                insert into contract (id, organization_id, title, created_at)
                values (:id, :organizationId, :title, :createdAt)
                """)
                .param("id", contractId)
                .param("organizationId", organizationId)
                .param("title", title)
                .param("createdAt", T0)
                .update();
    }

    private UUID versionId(UUID organizationId, UUID contractId, int versionNumber) {
        return jdbcClient.sql("""
                select id from contract_version
                where contract_id = :contractId and version_number = :versionNumber
                """)
                .param("contractId", contractId)
                .param("versionNumber", versionNumber)
                .query(UUID.class)
                .single();
    }

    private void insertVersion(UUID organizationId, UUID contractId, int versionNumber,
            String filename) {
        var artifactId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256,
                   storage_key, created_at)
                values (:id, :organizationId, :filename, 'application/docx', 10,
                        repeat('a', 64), :filename, :createdAt)
                """)
                .param("id", artifactId)
                .param("organizationId", organizationId)
                .param("filename", filename)
                .param("createdAt", T0)
                .update();
        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, organization_id,
                   role, parser_version, parse_status, parse_warnings, created_at)
                values (:id, :contractId, :versionNumber, :artifactId, :organizationId,
                        'ORIGINAL', 'test-parser-1', 'OK', '[]'::jsonb, :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("contractId", contractId)
                .param("versionNumber", versionNumber)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .param("createdAt", T0)
                .update();
    }

    private UUID seedRun(UUID organizationId, UUID reviewId, int runNumber, String status) {
        var runId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into review_analysis_run
                  (id, organization_id, review_id, run_number, status, trigger,
                   pipeline_version, started_at, completed_at, failure_reason)
                values (:id, :organizationId, :reviewId, :runNumber, :status, 'INITIAL',
                        'test-pipeline-1', :startedAt, :completedAt, :reason)
                """)
                .param("id", runId)
                .param("organizationId", organizationId)
                .param("reviewId", reviewId)
                .param("runNumber", runNumber)
                .param("status", status)
                .param("startedAt", T0)
                .param("completedAt", "RUNNING".equals(status) ? null : T0.plusMinutes(1))
                .param("reason", "FAILED".equals(status) ? "run_failed" : null)
                .update();
        return runId;
    }

    private void seedExecution(UUID organizationId, UUID reviewId, UUID runId, String status) {
        // Each execution needs its own plan check: review_check_execution is
        // UNIQUE (run_id, plan_check_id), so three executions sharing one check cannot
        // exist, and review_plan_check is UNIQUE (review_id, check_key) and
        // UNIQUE (review_id, ordinal).
        var planCheckId = seedPlanCheck(organizationId, reviewId);
        jdbcClient.sql("""
                insert into review_check_execution
                  (id, organization_id, review_id, run_id, plan_check_id, status, detail,
                   signal_count, started_at, completed_at)
                values (:id, :organizationId, :reviewId, :runId, :planCheckId, :status,
                        '{}'::jsonb, 0, :startedAt, :completedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("organizationId", organizationId)
                .param("reviewId", reviewId)
                .param("runId", runId)
                .param("planCheckId", planCheckId)
                .param("status", status)
                .param("startedAt", T0)
                .param("completedAt", "RUNNING".equals(status) ? null : T0.plusMinutes(1))
                .update();
    }

    /** Adds one plan check with the next free ordinal and returns its id. */
    private UUID seedPlanCheck(UUID organizationId, UUID reviewId) {
        var next = jdbcClient.sql("""
                select coalesce(max(ordinal), -1) + 1 as next
                from review_plan_check where review_id = :reviewId
                """)
                .param("reviewId", reviewId)
                .query(Integer.class)
                .single();
        var planCheckId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into review_plan_check
                  (id, organization_id, review_id, ordinal, check_key, family, expectation,
                   mechanism, concept, created_at)
                values (:id, :organizationId, :reviewId, :ordinal, :checkKey,
                        'CONCEPT_PRESENCE', 'REQUIRED', 'PRESENCE', 'GOVERNING_LAW', :createdAt)
                """)
                .param("id", planCheckId)
                .param("organizationId", organizationId)
                .param("reviewId", reviewId)
                .param("ordinal", next)
                .param("checkKey", "check-" + next)
                .param("createdAt", T0)
                .update();
        return planCheckId;
    }

    private UUID planCheck(UUID organizationId, UUID reviewId) {
        return jdbcClient.sql("""
                select id from review_plan_check
                where organization_id = :organizationId and review_id = :reviewId
                order by ordinal limit 1
                """)
                .param("organizationId", organizationId)
                .param("reviewId", reviewId)
                .query(UUID.class)
                .single();
    }

    private void seedSignal(UUID organizationId, UUID reviewId, UUID runId) {
        jdbcClient.sql("""
                insert into raw_review_signal
                  (id, organization_id, review_id, run_id, plan_check_id, signal_type,
                   subject_kind, subject_heading_node_id, concept, issue_family, payload,
                   dedupe_key, created_at)
                values (:id, :organizationId, :reviewId, :runId, :planCheckId,
                        'MISSING_REQUIRED_CONCEPT', 'CLAUSE', :nodeId, 'GOVERNING_LAW',
                        'MISSING_REQUIRED_PROVISION', '{}'::jsonb, :dedupeKey, :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("organizationId", organizationId)
                .param("reviewId", reviewId)
                .param("runId", runId)
                .param("planCheckId", planCheck(organizationId, reviewId))
                .param("nodeId", UUID.randomUUID())
                .param("dedupeKey", UUID.randomUUID().toString())
                .param("createdAt", T0)
                .update();
    }

    private void seedFinding(UUID organizationId, UUID reviewId, UUID runId) {
        jdbcClient.sql("""
                insert into finding
                  (id, organization_id, review_id, run_id, consolidation_key, subject_kind,
                   subject_heading_node_id, concept, issue_family, observation_kind,
                   observation_text, severity, review_classification, proposed_action,
                   proposed_language_source, created_at)
                values (:id, :organizationId, :reviewId, :runId, :consolidationKey, 'CLAUSE',
                        :nodeId, 'GOVERNING_LAW', 'MISSING_REQUIRED_PROVISION', 'ABSENCE',
                        'No governing law clause found.', 'HIGH', 'BLOCKING',
                        'ADD_REQUIRED_PROVISION', 'NONE', :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("organizationId", organizationId)
                .param("reviewId", reviewId)
                .param("runId", runId)
                .param("consolidationKey", UUID.randomUUID().toString())
                .param("nodeId", UUID.randomUUID())
                .param("createdAt", T0)
                .update();
    }
}