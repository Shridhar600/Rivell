package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Proves the review list does no work proportional to its row count.
 *
 * <p>This milestone exists partly to remove the 1 + N contract requests + N summary
 * requests pattern that a naive widening of this list would produce. Counting statements
 * on the server side is the only way to prove the absence of that, because a caller cannot
 * see how many round trips the server made.
 *
 * <p>Evidence comes from PostgreSQL's own statement log rather than from the shape of a
 * repository call, following the mechanism already established in
 * {@code ReviewDiagnosticsQueryIT}. The marker is inlined into statement text on purpose:
 * PostgreSQL logs statements, not bind values, so a parameterised marker would never
 * appear in the log and the window would fence the wrong region.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class ReviewListQueryCountIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String MARKER = "M10B1_LIST_WINDOW";

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell")
            // The test reads the SQL the database received, so the absence of per-row
            // hydration is evidenced from the server side rather than inferred.
            .withCommand("-c", "log_statement=all");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService reviewAnalysisService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void aPageIssuesTheSameWorkRegardlessOfRowCount() throws Exception {
        createReviews(2);
        var small = statementCount(
                statementsIn(() -> mockMvc.perform(get("/api/reviews")).andReturn()));

        createReviews(18);
        var large = statementCount(
                statementsIn(() -> mockMvc.perform(get("/api/reviews")).andReturn()));

        assertThat(large)
                .as("statement count must not grow with row count: a per-row contract or "
                        + "summary lookup would make this grow by the eighteen added reviews")
                .isEqualTo(small);
        assertThat(small)
                .as("one page query plus the fixed batched aggregates, so a small page is "
                        + "never materially cheaper than a full one")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void progressCountsComeFromSqlAggregationRatherThanHydratedRows() throws Exception {
        createReviews(6);
        var window = statementsIn(() -> mockMvc.perform(get("/api/reviews")).andReturn())
                .toLowerCase();

        assertThat(countOccurrences(window, "from review_plan_check"))
                .as("planned checks are counted once for the whole page")
                .isEqualTo(1);
        assertThat(countOccurrences(window, "from review_check_execution"))
                .as("execution statuses are counted once for the whole page")
                .isEqualTo(1);
        assertThat(countOccurrences(window, "from raw_review_signal"))
                .as("signals are counted once for the whole page")
                .isEqualTo(1);
        assertThat(countOccurrences(window, "from finding"))
                .as("findings are counted once for the whole page")
                .isEqualTo(1);
        assertThat(countOccurrences(window, "from review_analysis_run"))
                .as("the counted and latest generations are resolved in one statement, which "
                        + "contains two distinct-on passes")
                .isEqualTo(2);
        assertThat(countOccurrences(window, "select r.id, r.contract_id"))
                .as("one page query, and it is the only place the contract version is joined")
                .isEqualTo(1);
    }

    @Test
    void noStatementIsIssuedPerRow() throws Exception {
        createReviews(12);
        var window = statementsIn(() -> mockMvc.perform(get("/api/reviews")).andReturn())
                .toLowerCase();

        assertThat(countOccurrences(window, "join contract_version cv"))
                .as("the contract version is joined exactly once, for the page; a per-row "
                        + "contract read would repeat this join per review")
                .isEqualTo(1);
        assertThat(countOccurrences(window, "from review_analysis_run"))
                .as("runs are resolved once for the whole page, not once per review")
                .isEqualTo(2);
        assertThat(window)
                .as("ids are bound as an array rather than expanded into a literal in-list, "
                        + "so the statement text does not grow with the page")
                .doesNotContain("in ('");
    }

    private final java.util.concurrent.atomic.AtomicInteger fence =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Everything the server logged for one call, fenced by a marker unique to that call.
     *
     * <p>The tag must be unique per invocation. With a fixed tag, {@code indexOf} returns the
     * first window for every call, so two different calls would be handed identical text and
     * comparing them would prove nothing. The container is shared across the whole class.
     */
    private String statementsIn(CheckedCall call) throws Exception {
        var tag = MARKER + "_" + fence.incrementAndGet();
        mark(tag + "_open");
        call.run();
        mark(tag + "_close");
        var log = postgres.getLogs();
        var open = log.indexOf(tag + "_open");
        var close = log.indexOf(tag + "_close", open);
        assertThat(open).as("opening marker must appear in the server log").isNotNegative();
        assertThat(close).as("closing marker must appear after it").isGreaterThan(open);
        return log.substring(open, close);
    }

    /**
     * Statements the server actually executed inside a window. Log lines are counted rather
     * than compared as text, because every line carries a timestamp and millisecond
     * precision, so two identical requests never produce byte-identical text.
     */
    private int statementCount(String window) {
        return countOccurrences(window, "execute <unnamed>")
                + countOccurrences(window, "LOG:  statement:");
    }

    /**
     * Fences the window. The tag is inlined rather than bound because PostgreSQL logs the
     * statement text, not its bind values.
     */
    private void mark(String tag) {
        jdbcClient.sql("select '" + MARKER + "_" + tag + "' as m10b1_fence")
                .query(String.class)
                .single();
    }

    /** A call that may throw, so the test methods can declare it once. */
    @FunctionalInterface
    private interface CheckedCall {
        void run() throws Exception;
    }

    /**
     * Ingests {@code count} contracts and creates one review on each. Both halves matter:
     * without a review per contract the list returns an empty page, the aggregates are
     * never issued, and a statement count would compare two empty windows and pass
     * without proving anything.
     */
    private void createReviews(int count) throws Exception {
        for (var i = 0; i < count; i++) {
            var bytes = Files.readAllBytes(
                    Path.of("src/test/resources/fixtures/simple-contract.docx"));
            var filename = "q" + i + ".docx";
            var ingested = contractIngestionService.ingest(filename, DOCX_MEDIA_TYPE,
                    new java.io.ByteArrayInputStream(bytes));
            enrichmentService.enrich(ingested.contractId(), 1, new ReviewProfile(
                    ContractType.MSA, PartyRole.RECEIVING_PARTY,
                    PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US, "MSA_PACK"));
            var review = reviewService.create(ingested.contractId(), 1, "MSA_PACK", null);
            // A generation must exist, or the counted-run aggregates are skipped as empty
            // and the test would assert nothing about them.
            reviewAnalysisService.analyze(review.organizationId(), review.id());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        var count = 0;
        var index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}