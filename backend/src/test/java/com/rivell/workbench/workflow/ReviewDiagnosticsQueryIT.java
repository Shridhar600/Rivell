package com.rivell.workbench.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.review.ClaimKind;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClaim;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.ProposedLanguageSource;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.LiabilityReviewIT;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewService;

/**
 * M10A.1 / DW-046: diagnostics computes four counts, and it must do so with an aggregate.
 *
 * <p>Correctness alone cannot hold this line. The hydrating shape and the aggregate shape return
 * identical numbers, so a test that only checks the counts would keep passing after a regression to
 * the expensive path — which loaded every finding of the run plus every concept and claim child,
 * then discarded the lot, on the endpoint an operator opens precisely when a run is large and
 * misbehaving.
 *
 * <p>So this test also reads the SQL the database actually received. The container logs every
 * statement, and the window is fenced by marker statements either side of the request, which makes
 * the evidence independent of which repository method issued a query. No mocking is involved, and
 * none is available: this suite has no mock library.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class ReviewDiagnosticsQueryIT {

    private static final String MARKER = "M10A1_DIAG_WINDOW";

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell")
            // The test reads the SQL the database received, so correctness of the aggregate is
            // evidenced from the server side rather than from the shape of a repository call.
            .withCommand("-c", "log_statement=all");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired M6EvidenceCorpus corpusBuilder;
    @Autowired ReviewService reviewService;
    @Autowired ReviewAnalysisService analysisService;
    @Autowired FindingRepository findingRepository;
    @Autowired LiabilityReviewIT.MutableIdentity identityProvider;
    @Autowired JdbcClient jdbcClient;

    private UUID organizationId() {
        var id = UUID.randomUUID();
        identityProvider.use(new UserIdentity(UUID.randomUUID(), id, Set.of("ADMIN")));
        return id;
    }

    /**
     * Fences the window. The tag is inlined into the statement text on purpose: PostgreSQL logs the
     * statement, not its bind values, so a parameterised marker would never appear in the log and
     * the window would silently fence the wrong region.
     */
    private void mark(String tag) {
        jdbcClient.sql("select '" + MARKER + "_" + tag + "' as m10a1_fence")
                .query(String.class)
                .single();
    }

    /** Everything the server logged between the two markers. */
    private String window(String openTag, String closeTag) {
        var log = postgres.getLogs();
        var open = log.indexOf(MARKER + "_" + openTag);
        var close = log.indexOf(MARKER + "_" + closeTag, open);
        assertThat(open).as("opening marker must appear in the server log").isNotNegative();
        assertThat(close).as("closing marker must appear in the server log")
                .isNotNegative().isGreaterThan(open);
        return log.substring(open, close);
    }

    @Test
    void diagnosticsCountsFindingsWithAnAggregateAndNeverHydratesTheGraph() throws Exception {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId, review.id());

        // A realistic multi-finding run covering every classification, each with concept children.
        // The cost DW-046 removes scales with the number of findings and their children, so a
        // single-finding fixture would make the query assertions vacuous.
        findingRepository.replaceFindings(organizationId, review.id(), run.id(),
                multiFindingRun(organizationId, review.id(), run.id()));

        List<Finding> hydrated = findingRepository.findFindings(organizationId, run.id());
        assertThat(hydrated)
                .as("the aggregate can only be proven on a multi-finding run")
                .hasSizeGreaterThanOrEqualTo(6);
        long blocking = hydrated.stream()
                .filter(f -> f.reviewClassification() == FindingClassification.BLOCKING).count();
        long reviewRequired = hydrated.stream()
                .filter(f -> f.reviewClassification()
                        == FindingClassification.REVIEW_REQUIRED).count();
        long informational = hydrated.stream()
                .filter(f -> f.reviewClassification() == FindingClassification.INFORMATIONAL)
                .count();
        assertThat(List.of(blocking, reviewRequired, informational))
                .as("every classification must be represented or the grouping is not proven")
                .doesNotContain(0L);

        mark("OPEN");
        mockMvc.perform(get("/api/reviews/{id}/diagnostics", review.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings.total").value(hydrated.size()))
                .andExpect(jsonPath("$.data.findings.blocking").value((int) blocking))
                .andExpect(jsonPath("$.data.findings.reviewRequired").value((int) reviewRequired))
                .andExpect(jsonPath("$.data.findings.informational").value((int) informational));
        mark("CLOSE");

        var sql = window("OPEN", "CLOSE");
        var lower = sql.toLowerCase();

        // The counts come from one grouped aggregate over the finding table.
        assertThat(countOccurrences(lower, "from finding") + countOccurrences(lower, "from\\nfinding"))
                .as("the finding table must be read exactly once, by the count aggregate")
                .isEqualTo(1);
        assertThat(lower)
                .as("the finding count must be aggregated in the database")
                .contains("count(*)")
                .contains("group by review_classification");

        // No concept or claim child graph is touched.
        assertThat(lower)
                .as("diagnostics must not read finding children; the counts never needed them")
                .doesNotContain("finding_concept")
                .doesNotContain("finding_claim");

        // The latest completed run is fetched once and shared by the finding counts and the
        // decision counters, rather than looked up independently by each.
        // Two run reads are correct: the latest run of any status drives the analysis block, and the
        // latest COMPLETED run drives the counts. What must not happen is the completed lookup being
        // issued twice, which is what it was before the finding counts and the decision counters
        // each asked for it.
        assertThat(countOccurrences(lower, "from review_analysis_run"))
                .as("exactly two run reads: the latest run, and the latest completed run once")
                .isEqualTo(2);
        assertThat(countOccurrences(lower, "status = 'completed'"))
                .as("the latest completed run must be looked up exactly once and shared")
                .isEqualTo(1);

        // Decision counters are aggregated, not counted from a bounded decision list. The bounded list
        // read limit + 1 rows and counted all of them, so it could report 1001 for one action, which
        // is not a real count. The aggregate has neither a bound nor a sentinel, and it selects only
        // the action column, so the full decision projection is the discriminator.
        assertThat(lower)
                .as("decision counters must be aggregated")
                .contains("group by action")
                .doesNotContain("supersedes_decision_id");

        // Nothing is read once per finding.
        assertThat(countOccurrences(lower, "where finding_id ="))
                .as("no per-finding read may be issued")
                .isZero();
    }

    private static int countOccurrences(String haystack, String needle) {
        var n = needle.replace("\\n", "\n");
        var count = 0;
        var index = haystack.indexOf(n);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(n, index + n.length());
        }
        return count;
    }

    /**
     * A run of nine findings spread across all three classifications, each carrying concept and
     * claim children so that a regression to the hydrating path has something to hydrate.
     */
    private List<Finding> multiFindingRun(UUID organizationId, UUID reviewId, UUID runId) {
        var classifications = List.of(
                FindingClassification.BLOCKING, FindingClassification.BLOCKING,
                FindingClassification.REVIEW_REQUIRED, FindingClassification.REVIEW_REQUIRED,
                FindingClassification.REVIEW_REQUIRED,
                FindingClassification.INFORMATIONAL, FindingClassification.INFORMATIONAL,
                FindingClassification.INFORMATIONAL, FindingClassification.INFORMATIONAL);
        var concepts = List.of(LegalConcept.CONFIDENTIALITY, LegalConcept.LIMITATION_OF_LIABILITY,
                LegalConcept.INDEMNIFICATION, LegalConcept.GOVERNING_LAW,
                LegalConcept.TERM_TERMINATION, LegalConcept.CONFIDENTIALITY,
                LegalConcept.LIMITATION_OF_LIABILITY, LegalConcept.INDEMNIFICATION,
                LegalConcept.GOVERNING_LAW);
        var now = java.time.Instant.now();
        var findings = new java.util.ArrayList<Finding>();
        for (int i = 0; i < classifications.size(); i++) {
            var concept = concepts.get(i);
            findings.add(new Finding(
                    UUID.randomUUID(), organizationId, reviewId, runId,
                    "m10a1-finding-" + i,
                    FindingSubjectKind.ABSENCE,
                    null, concept, concept, null,
                    IssueFamily.EXPECTED_PROVISION_ABSENT,
                    "observation-" + i, "observation text " + i,
                    com.rivell.workbench.review.FindingSeverity.MEDIUM,
                    classifications.get(i),
                    ProposedAction.USE_APPROVED_FALLBACK,
                    null, ProposedLanguageSource.NONE,
                    null, java.util.Map.of("fixture", "m10a1"), now,
                    List.of(concept),
                    List.of(new FindingClaim(UUID.randomUUID(), 0,
                            ClaimKind.OBSERVATION, "claim " + i, true, List.of())),
                    List.of(), List.of()));
        }
        return findings;
    }
}
