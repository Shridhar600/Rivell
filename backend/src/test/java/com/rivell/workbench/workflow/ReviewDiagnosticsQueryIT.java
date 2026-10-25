package com.rivell.workbench.workflow;

import com.rivell.workbench.taxonomy.CoreConcepts;

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
import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.ChangeLanguageSource;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.review.ClaimKind;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClaim;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.ProposedLanguageSource;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewAnalysisRun;
import com.rivell.workbench.review.ReviewPipeline;
import com.rivell.workbench.review.RunTrigger;
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
    @Autowired ReviewAnalysisRepository analysisRepository;
    @Autowired HumanDecisionRepository decisionRepository;
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


    /**
     * M10A.1 corrective / DW-046: the decision counters are the eval-label surface, so they must
     * count the CURRENT effective decision for each finding, and only within one review, one run and
     * one organization.
     *
     * <p>The first version of this test created no human decisions at all. Its decision assertions
     * were about SQL shape only, so "the aggregate groups by action" passed whether or not the
     * aggregate returned anything correct. Correctness and shape are separate claims and both are
     * needed: the shape assertions cannot tell you the numbers are right, and the numbers cannot tell
     * you the expensive path did not come back.
     *
     * <p><b>What this can and cannot prove.</b> It proves the aggregate returns the right totals for
     * every row the schema can represent, across two organizations, two reviews of one organization,
     * and two runs of one review. It CANNOT prove the scope predicates are load-bearing, because
     * {@code human_decision}'s composite foreign keys tie every row to a real review, run, finding
     * and organization: a cross-organization, cross-review or wrong-run row is unrepresentable, so
     * no fixture can contain a contaminating one. Deleting a scope predicate from the query would
     * leave this test green. Predicate presence is guarded by the SQL-shape assertions in the
     * sibling test, and that is the honest limit of what is covered here.
     */
    @Test
    void decisionCountsUseTheLatestRevisionPerFindingWithinOneRunAndOrganization() throws Exception {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var firstRun = analysisService.analyze(organizationId, review.id());

        var findings = multiFindingRun(organizationId, review.id(), firstRun.id());
        findingRepository.replaceFindings(organizationId, review.id(), firstRun.id(), findings);
        assertThat(findings).hasSizeGreaterThanOrEqualTo(9);

        // Decision history on the first run: eight of the nine findings decided, three of them with
        // a superseded revision. The latest-revision totals differ from the all-row totals, so a
        // regression to "count every row" is detectable by value and not only by SQL shape.
        // Every figure below was read off a mutation of the production query, not counted by hand.
        // An earlier version of this comment hand-counted them and got two of eight wrong, so the
        // numbers are recorded with the mutation that produced each one.
        //
        //   correct (revision desc, distinct on):  accepted 1  edited 1  rejected 4  escalated 2
        //   MUT-E  revision asc:                  accepted 3  edited 2  rejected 1  escalated 2
        //   MUT-F  distinct on removed:            accepted 4  edited 2  rejected 4  escalated 2
        //
        // Both regressions are caught by value, and each differs from the correct result in at least
        // three of the four actions. `rejected` alone discriminates neither: it is 4 in both the
        // correct query and MUT-F, which is exactly why the assertions are per action and not a
        // total. (Totals: 8 decided findings, 12 decision rows.)
        seedDecisions(organizationId, review.id(), firstRun.id(), findings, 0, 8);
        assertThat(decisionRepository.findCurrentForRun(organizationId, review.id(), firstRun.id()))
                .as("the fixture must really hold superseded revisions, or the totals prove nothing")
                .hasSize(8);

        // A second review in the same organization, with its own run and its own decisions. Its rows
        // must not appear in the first review's counters.
        var secondReview = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var secondRun = analysisService.analyze(organizationId, secondReview.id());
        var secondFindings = multiFindingRun(organizationId, secondReview.id(), secondRun.id());
        findingRepository.replaceFindings(
                organizationId, secondReview.id(), secondRun.id(), secondFindings);
        seedDecisions(organizationId, secondReview.id(), secondRun.id(), secondFindings, 100, 8);

        // A second organization, with its own corpus, review, run and decisions.
        var otherOrganizationId = organizationId();
        var otherCorpus = corpusBuilder.buildMainCorpus(otherOrganizationId);
        var otherReview = reviewService.create(
                otherCorpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var otherRun = analysisService.analyze(otherOrganizationId, otherReview.id());
        var otherFindings = multiFindingRun(
                otherOrganizationId, otherReview.id(), otherRun.id());
        findingRepository.replaceFindings(
                otherOrganizationId, otherReview.id(), otherRun.id(), otherFindings);
        seedDecisions(
                otherOrganizationId, otherReview.id(), otherRun.id(), otherFindings, 200, 8);

        // The other organization's rows are now in the database. Return to the first organization
        // before asserting, because the identity the request runs under decides which organization
        // the endpoint can see at all.
        identityProvider.use(new UserIdentity(UUID.randomUUID(), organizationId, Set.of("ADMIN")));

        // Assert the first review's counters before a second run exists, so the two assertions
        // differ only by the run that was added between them.
        assertDecisionCounts(review.id(), 1, 1, 4, 2);

        // A second COMPLETED run for the SAME review, seeded through the production run repository
        // rather than by re-running the analysis pipeline. This is the real recompute-shaped state:
        // the earlier run and its decisions remain as audit history, and the counters must follow the
        // latest completed run. Invoking `recompute` instead would pull provider fakes, snapshot
        // freezing and the whole check pipeline into a test whose subject is a GROUP BY.
        var secondRunNumber = analysisRepository.maxRunNumber(organizationId, review.id()) + 1;
        var laterRun = ReviewAnalysisRun.running(UUID.randomUUID(), organizationId, review.id(),
                secondRunNumber, RunTrigger.RECOMPUTE, ReviewPipeline.VERSION,
                java.time.Instant.now());
        assertThat(analysisRepository.tryInsertRun(laterRun)).isTrue();
        assertThat(analysisRepository.markRunCompleted(
                organizationId, laterRun.id(), java.time.Instant.now())).isTrue();

        // Two decisions on the newer run only. The first run's eight decided findings must now be
        // excluded entirely, and this run's two must be counted.
        var laterFindings = multiFindingRun(organizationId, review.id(), laterRun.id());
        findingRepository.replaceFindings(organizationId, review.id(), laterRun.id(), laterFindings);
        // Deliberately a DIFFERENT shape from the first run's history. If both runs produced the
        // same totals, a query that read the wrong run would still pass, and this assertion would
        // not be testing run selection at all.
        seedDecisions(organizationId, review.id(), laterRun.id(), laterFindings, 300, 1);
        // The first run's findings stay in place as history, so this also exercises a latest
        // completed run that is not the run the decisions were first written against.
        assertThat(findingRepository.findFindings(organizationId, firstRun.id()))
                .as("the superseded run keeps its findings as history")
                .isNotEmpty();

        // The first run's 8 decided findings are excluded entirely: the counters follow the latest
        // COMPLETED run, and this run has exactly two decided findings of its own.
        assertDecisionCounts(review.id(), 1, 0, 1, 0);
    }

    /**
     * Also asserts the finding totals, because this fixture is the only one holding a second review
     * in the same organization and a second organization. Without that, a scope predicate removed
     * from the FINDING aggregate would go unnoticed: the finding assertions live in the sibling
     * test, which only ever builds one review in one organization.
     */
    private void assertDecisionCounts(
            UUID reviewId, int accepted, int edited, int rejected, int escalated)
            throws Exception {
        mockMvc.perform(get("/api/reviews/{id}/diagnostics", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings.total").value(9))
                .andExpect(jsonPath("$.data.decisions.accepted").value(accepted))
                .andExpect(jsonPath("$.data.decisions.edited").value(edited))
                .andExpect(jsonPath("$.data.decisions.rejected").value(rejected))
                .andExpect(jsonPath("$.data.decisions.escalated").value(escalated));
    }

    /**
     * Writes the decision history for one run, column-exactly.
     *
     * <p>Seeded through the repository rather than {@code HumanDecisionService.decide}, which
     * requires a COMPLETED review and would drag finalization into a test about a count query. That
     * is the same choice this file already makes for findings. It means the READ projection is what
     * is under test; the write path has its own tests.
     *
     * <p>The revision numbers and the supersedes chain are load-bearing: the aggregate selects
     * {@code distinct on (finding_id) ... order by finding_id, revision desc}, so a fixture with
     * sloppy revisions would look rigorous while proving nothing. Dropping {@code revision desc}
     * from the production query must therefore fail this test.
     */
    private void seedDecisions(
            UUID organizationId, UUID reviewId, UUID runId, List<Finding> findings, int tag,
            int decidedFindings) {
        var actor = UUID.randomUUID();
        var now = java.time.Instant.now();
        java.util.function.IntFunction<UUID> at = index -> findings.get(index).id();
        var rows = new java.util.ArrayList<HumanDecision>();

        // Index -> the revision chain written for it. f8 is deliberately absent: an undecided finding
        // must not be counted, and a counter that cannot show that is not proving much.
        rows.add(decision(organizationId, reviewId, runId, at.apply(0), 1,
                DecisionAction.ACCEPT, null, actor, now, tag));
        rows.add(decision(organizationId, reviewId, runId, at.apply(1), 1,
                DecisionAction.REJECT, "not the agreed position", actor, now, tag));
        rows.add(decision(organizationId, reviewId, runId, at.apply(2), 1,
                DecisionAction.ESCALATE, "needs the General Counsel", actor, now, tag));
        rows.add(decision(organizationId, reviewId, runId, at.apply(4), 1,
                DecisionAction.EDIT, null, actor, now, tag));
        rows.add(decision(organizationId, reviewId, runId, at.apply(7), 1,
                DecisionAction.ESCALATE, "outside the playbook", actor, now, tag));

        if (decidedFindings <= 2) {
            // The reduced shape used for the later run: one ACCEPT and one REJECT, nothing else.
            for (var row : rows.subList(0, 2)) {
                assertThat(decisionRepository.insert(row)).isTrue();
            }
            return;
        }

        // f3: an ACCEPT superseded by a REJECT, so the latest revision must win.
        var f3r1 = decision(organizationId, reviewId, runId, at.apply(3), 1,
                DecisionAction.ACCEPT, null, actor, now, tag);
        rows.add(f3r1);
        rows.add(decision(organizationId, reviewId, runId, at.apply(3), 2,
                DecisionAction.REJECT, "rejected on second review", actor,
                now.plusSeconds(1), tag, f3r1.id()));
        // f5: an EDIT superseded by a REJECT, so the ordering is proven in both directions.
        var f5r1 = decision(organizationId, reviewId, runId, at.apply(5), 1,
                DecisionAction.EDIT, null, actor, now, tag);
        rows.add(f5r1);
        rows.add(decision(organizationId, reviewId, runId, at.apply(5), 2,
                DecisionAction.REJECT, "rejected after drafting", actor,
                now.plusSeconds(1), tag, f5r1.id()));
        // f6: three revisions. The current one is the highest revision, not the last row inserted and
        // not the first, so both "highest revision" and "most recent" readings are distinguished.
        var f6r1 = decision(organizationId, reviewId, runId, at.apply(6), 1,
                DecisionAction.ACCEPT, null, actor, now, tag);
        rows.add(f6r1);
        var f6r2 = decision(organizationId, reviewId, runId, at.apply(6), 2,
                DecisionAction.ACCEPT, "accepted, then withdrawn", actor,
                now.plusSeconds(1), tag, f6r1.id());
        rows.add(f6r2);
        rows.add(decision(organizationId, reviewId, runId, at.apply(6), 3,
                DecisionAction.REJECT, "finally rejected", actor,
                now.plusSeconds(2), tag, f6r2.id()));

        for (var row : rows) {
            assertThat(decisionRepository.insert(row))
                    .as("decision fixture must be insertable: %s", row.action())
                    .isTrue();
        }
    }

    private static HumanDecision decision(
            UUID organizationId, UUID reviewId, UUID runId, UUID findingId, int revision,
            DecisionAction action, String comment, UUID actor, java.time.Instant at, int tag) {
        return decision(organizationId, reviewId, runId, findingId, revision, action, comment,
                actor, at, tag, null);
    }

    /**
     * Built through the domain constructor on purpose. {@code HumanDecision} refuses an ACCEPT or
     * EDIT without an approved action and refuses a REJECT or ESCALATE with one, so a fixture that
     * hand-wrote the columns would either fail here or, worse, quietly encode rows the write path
     * could never produce.
     */
    private static HumanDecision decision(
            UUID organizationId, UUID reviewId, UUID runId, UUID findingId, int revision,
            DecisionAction action, String comment, UUID actor, java.time.Instant at, int tag,
            UUID supersedes) {
        var executable = action == DecisionAction.ACCEPT || action == DecisionAction.EDIT;
        return new HumanDecision(
                UUID.randomUUID(), organizationId, reviewId, findingId, runId, revision, action,
                executable ? ChangeActionType.REPLACE : null,
                executable ? "approved language " + tag : null,
                executable ? ChangeLanguageSource.HUMAN_EDIT : null,
                null, comment, null, null, actor, at, null, supersedes);
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

        // The bounded-list regression cannot be caught by value: a fixture would need more than a
        // thousand decisions to make a bound visible, and the old bug reported the limit rather than
        // the truth. So it is caught by shape instead, on the aggregate statement alone — the wider
        // request window legitimately contains bounded reads elsewhere.
        var aggregate = statementContaining(sql, "select action, count(*)");
        assertThat(aggregate.toLowerCase())
                .as("the decision aggregate must be unbounded; a limit+1 bound is what reported "
                        + "1001 for a single action")
                .doesNotContain("limit");

        // Nothing is read once per finding.
        assertThat(countOccurrences(lower, "where finding_id ="))
                .as("no per-finding read may be issued")
                .isZero();
    }

    /**
     * The single statement that contains the given fragment, so a shape claim is scoped to it.
     *
     * <p>A logged statement is its text followed by a tab-only continuation line and then its
     * bind parameters, so the boundary is whichever of those comes first. Slicing to a
     * semicolon instead would swallow every later statement in the window, which makes an
     * unrelated bounded read elsewhere look like a bound on the statement under test.
     */
    private static String statementContaining(String sql, String fragment) {
        var start = sql.indexOf(fragment);
        assertThat(start).as("statement containing '%s' must be in the window", fragment)
                .isNotNegative();
        var continuation = sql.indexOf("\n\t\n", start);
        var parameters = sql.indexOf("detail:", start);
        var end = switch (Integer.compare(
                continuation < 0 ? Integer.MAX_VALUE : continuation,
                parameters < 0 ? Integer.MAX_VALUE : parameters)) {
            case -1, 0 -> continuation < 0 ? parameters : continuation;
            default -> parameters;
        };
        return end < 0 ? sql.substring(start) : sql.substring(start, end);
    }

/**
     * Why the organization-scope mutations survive, and what guards them instead.
     *
     * <p>Deleting {@code organization_id} from either aggregate leaves this suite green. That is not
     * a gap that happens to be unfound: no fixture can expose it, because {@code human_decision} and
     * {@code finding} carry composite foreign keys that tie every row to a real organization, review,
     * run and finding. A cross-organization row is unrepresentable, so there is nothing for a missing
     * predicate to wrongly include.
     *
     * <p>So the guarantee is asserted where it actually lives — in the schema. If a future migration
     * drops one of these keys, this fails, and the failure is the signal that value-level isolation
     * coverage has to be added at the same time, because from that point the query predicates would
     * be the only thing standing between two organizations' data.
     */
    @Test
    void theDecisionAndFindingTablesAreSchemaScopedByCompositeForeignKeys() {
        var keys = jdbcClient.sql("""
                select conrelid::regclass::text as table_name, conname,
                       pg_get_constraintdef(oid) as definition
                from pg_constraint
                where contype = 'f'
                  and conrelid::regclass::text in ('human_decision', 'finding')
                """)
                .query((rs, rowNum) -> rs.getString("table_name") + "|" + rs.getString("conname")
                        + "|" + rs.getString("definition"))
                .list();

        assertThat(keys)
                .as("the composite keys are the reason a cross-scope row cannot exist")
                .isNotEmpty();

        var decisionKeys = keys.stream().filter(k -> k.startsWith("human_decision|")).toList();
        assertThat(decisionKeys)
                .as("human_decision must be tied to its organization, review, run and finding")
                .anyMatch(k -> k.contains("FOREIGN KEY (review_id, organization_id)"))
                .anyMatch(k -> k.contains(
                        "FOREIGN KEY (run_id, review_id, organization_id)"))
                .anyMatch(k -> k.contains("FOREIGN KEY (finding_id, organization_id)"))
                .anyMatch(k -> k.contains("FOREIGN KEY (finding_id, review_id)"))
                .anyMatch(k -> k.contains("FOREIGN KEY (finding_id, run_id)"));

        // finding is tied the same way: to its review by (review_id, organization_id), and to its
        // run by the full (run_id, review_id, organization_id) triple. Those two keys are what make
        // a finding row of another organization, review or run unrepresentable.
        var findingKeys = keys.stream().filter(k -> k.startsWith("finding|")).toList();
        assertThat(findingKeys)
                .as("finding must be tied to its review and to its run, both organization-scoped")
                .anyMatch(k -> k.contains("fk_finding_review_org")
                        && k.contains("FOREIGN KEY (review_id, organization_id)"))
                .anyMatch(k -> k.contains("fk_finding_run_review_org")
                        && k.contains("FOREIGN KEY (run_id, review_id, organization_id)"));
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
        var concepts = List.of(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS, CoreConcepts.LIMITATION_OF_LIABILITY,
                CoreConcepts.INDEMNIFICATION, CoreConcepts.GOVERNING_LAW,
                CoreConcepts.TERM, CoreConcepts.CONFIDENTIALITY_OBLIGATIONS,
                CoreConcepts.LIMITATION_OF_LIABILITY, CoreConcepts.INDEMNIFICATION,
                CoreConcepts.GOVERNING_LAW);
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
                    List.of(), List.of(), null));
        }
        return findings;
    }
}
