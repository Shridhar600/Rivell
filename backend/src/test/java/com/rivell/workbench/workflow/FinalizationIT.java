package com.rivell.workbench.workflow;

import com.rivell.workbench.taxonomy.CoreConcepts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.redline.ApprovedChangeSet;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.LiabilityReviewIT;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewAnalysisRun;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewService;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class FinalizationIT {

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
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    ReviewFinalizationService finalizationService;

    @Autowired
    HumanDecisionService decisionService;

    @Autowired
    HumanDecisionRepository decisionRepository;

    @Autowired
    ApprovedChangeSetRepository changeSetRepository;

    @Autowired
    LiabilityReviewIT.ReviewGenerativeProvider generativeProvider;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void finalizeFreezesTheExactApprovedChangeSet() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        var decision = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ACCEPT"}
                """).get("data");

        var state = json(mockMvc.perform(
                        get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readyToFinalize").value(true))
                .andExpect(jsonPath("$.data.blockers").isEmpty())
                .andExpect(jsonPath("$.data.status").value("READY_FOR_COMPLETION"))
                .andReturn()).get("data");
        assertThat(state.get("actionableFindings").asInt()).isEqualTo(1);
        assertThat(state.get("resolvedFindings").asInt()).isEqualTo(1);
        assertThat(state.get("accepted").asInt()).isEqualTo(1);

        var set = finalizeReview(fixture.review().id());
        assertThat(set.get("reviewId").asText())
                .isEqualTo(fixture.review().id().toString());
        assertThat(set.get("contractId").asText())
                .isEqualTo(fixture.review().contractId().toString());
        assertThat(set.get("contractVersionNumber").asInt()).isEqualTo(1);
        assertThat(set.get("runId").asText()).isEqualTo(fixture.run().id().toString());
        assertThat(set.get("changeSetVersion").asInt()).isEqualTo(1);
        assertThat(set.get("finalizedBy").asText())
                .isEqualTo(identityProvider.currentIdentity().userId().toString());
        assertThat(set.get("changes").size()).isEqualTo(1);

        var change = set.get("changes").get(0);
        assertThat(change.get("findingId").asText()).isEqualTo(finding.id().toString());
        assertThat(change.get("decisionId").asText())
                .isEqualTo(decision.get("id").asText());
        assertThat(change.get("actionType").asText()).isEqualTo("REPLACE");
        assertThat(change.get("targetKind").asText()).isEqualTo("CLAUSE");
        assertThat(change.get("targetHeadingNodeId").asText())
                .isEqualTo(finding.subjectHeadingNodeId().toString());
        assertThat(change.get("absenceConcept").isNull()).isTrue();
        assertThat(change.get("approvedLanguage").asText())
                .isEqualTo(finding.proposedLanguage());
        assertThat(change.get("languageSource").asText()).isEqualTo("APPROVED_DRAFTING");
        assertThat(change.get("approvedDraftingEvidenceId").asText())
                .isEqualTo(finding.approvedDraftingEvidenceId().toString());
        assertThat(change.get("ordinal").asInt()).isZero();

        var reread = json(mockMvc.perform(
                        get("/api/reviews/{id}/approved-change-set", fixture.review().id()))
                .andExpect(status().isOk())
                .andReturn()).get("data");
        assertThat(reread.get("id").asText()).isEqualTo(set.get("id").asText());
        assertThat(reread.get("changes").size()).isEqualTo(1);

        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.readyToFinalize").value(false))
                .andExpect(jsonPath("$.data.changeSetId").value(set.get("id").asText()));
    }

    @Test
    void missingProvisionEditFreezesInsertIntentWithoutFakeClauseTarget() throws Exception {
        var fixture = analyzedNdaReview();
        var absence = finding(fixture, ProposedAction.ADD_REQUIRED_PROVISION);
        assertThat(absence.subjectHeadingNodeId()).isNull();

        decide(fixture.review().id(), absence.id(),
                """
                {"expectedVersion": 0, "action": "EDIT",
                 "approvedLanguage": "Liability is capped at fees paid in the prior 12 months."}
                """);

        var set = finalizeReview(fixture.review().id());
        var inserts = changesOf(set, "INSERT");
        assertThat(inserts).hasSize(1);
        var insert = inserts.get(0);
        assertThat(insert.get("targetKind").asText()).isEqualTo("ABSENCE");
        assertThat(insert.get("targetHeadingNodeId").isNull()).isTrue();
        assertThat(insert.get("absenceConcept").asText()).isEqualTo("LIMITATION_OF_LIABILITY");
        assertThat(insert.get("approvedLanguage").asText())
                .isEqualTo("Liability is capped at fees paid in the prior 12 months.");
        assertThat(insert.get("languageSource").asText()).isEqualTo("HUMAN_EDIT");
        assertThat(insert.get("approvedDraftingEvidenceId").isNull()).isTrue();
    }

    @Test
    void rejectedFindingsProduceNoChangeAndInformationalFindingsDoNotBlock() throws Exception {
        var fixture = analyzedNdaReview();
        var informational = fixture.findings().stream()
                .filter(finding -> finding.reviewClassification()
                        == FindingClassification.INFORMATIONAL)
                .findFirst().orElseThrow();
        var blocking = fixture.findings().stream()
                .filter(finding -> finding.reviewClassification()
                        == FindingClassification.BLOCKING)
                .findFirst().orElseThrow();

        decide(fixture.review().id(), blocking.id(),
                """
                {"expectedVersion": 0, "action": "REJECT",
                 "comment": "Provision intentionally absent in this NDA."}
                """);

        var state = json(mockMvc.perform(
                        get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readyToFinalize").value(true))
                .andReturn()).get("data");
        assertThat(state.get("informationalFindings").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(state.get("informationalDecided").asInt()).isZero();
        assertThat(state.get("rejected").asInt()).isEqualTo(1);
        assertThat(informational.id()).isNotNull();

        var set = finalizeReview(fixture.review().id());
        assertThat(set.get("changes").size()).isZero();
    }

    @Test
    void escalationBlocksFinalizationUntilRevised() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ESCALATE",
                 "comment": "Needs GC review."}
                """);

        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readyToFinalize").value(false))
                .andExpect(jsonPath("$.data.blockers[?(@.code == 'ACTIVE_ESCALATION')]")
                        .exists());
        mockMvc.perform(post("/api/reviews/{id}/finalize", fixture.review().id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));

        decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 1, "action": "REJECT", "comment": "Resolved by GC."}
                """);
        var set = finalizeReview(fixture.review().id());
        assertThat(set.get("changes").size()).isZero();
    }

    @Test
    void keptClauseCreatesNoApprovedChange() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "REJECT", "rejectReason": "KEEP_AS_DRAFTED"}
                """);
        var set = finalizeReview(fixture.review().id());
        assertThat(set.get("changes").size())
                .as("kept wording is not a redline change")
                .isZero();
    }

    @Test
    void resolvedEscalationUnblocksFinalizationWithNoApprovedChange() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ESCALATE",
                 "comment": "Needs GC review."}
                """);
        var resolved = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 1, "action": "RESOLVE", "approvedBy": "Jane Doe"}
                """);
        assertThat(resolved.get("data").get("approvedBy").asText()).isEqualTo("Jane Doe");

        var set = finalizeReview(fixture.review().id());
        assertThat(set.get("changes").size())
                .as("a resolved escalation approves the clause as drafted and changes no wording")
                .isZero();
    }

    /**
     * M10: the product's hardest failure to diagnose is a review that stops. With the default
     * configuration there is no background worker, so an enqueued analysis is never picked up and the
     * review sits in ANALYSIS_QUEUED with nothing to indicate why. The diagnostics surface has to
     * say so plainly, and it has to say it without exposing contract text.
     */
    @Test
    void diagnosticsExplainAQueuedAnalysisThatNoWorkerHasPickedUp() throws Exception {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        // No analysis is driven: the review is created, its job is enqueued, and with the default
        // configuration nothing will ever run it.
        var result = mockMvc.perform(get("/api/reviews/{id}/diagnostics", review.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("ANALYSIS_QUEUED"))
                .andExpect(jsonPath("$.data.backgroundWorkerEnabled").value(false))
                .andExpect(jsonPath("$.data.analysis").doesNotExist())
                .andExpect(jsonPath("$.data.stalled[0]")
                        .value("The analysis has not started for this review."))
                .andReturn();

        var body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("diagnostics must not echo contract text")
                .doesNotContain("limitation of liability")
                .doesNotContain("Liability is capped");
    }

    @Test
    void diagnosticsReportACompletedRunWithItsChecksAndOutcomes() throws Exception {
        var fixture = analyzedMainCorpus();
        var reviewId = fixture.review().id();
        var actionable = fixture.findings().stream()
                .filter(f -> f.reviewClassification()
                        != FindingClassification.INFORMATIONAL)
                .toList();
        assertThat(actionable).as("the corpus must offer a decision to record").isNotEmpty();
        decide(reviewId, actionable.get(0).id(),
                "{\"expectedVersion\": 0, \"action\": \"REJECT\","
                        + " \"comment\": \"Counterparty position accepted.\"}");

        mockMvc.perform(get("/api/reviews/{id}/diagnostics", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.analysis.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.analysis.checks").isNotEmpty())
                .andExpect(jsonPath("$.data.findings.total").isNumber())
                .andExpect(jsonPath("$.data.analysis.environmentMatchesSnapshot").value(true))
                .andExpect(jsonPath("$.data.stalled").isEmpty())
                // The eval-label surface. This counts real decisions: the query filters on run_id, and
                // passing null there returned nothing, so the field read zero for every review.
                .andExpect(jsonPath("$.data.decisions.rejected").value(1))
                .andExpect(jsonPath("$.data.decisions.accepted").value(0));
    }

    /**
     * M10: a run whose checks all degraded produced a COMPLETED review with zero Findings, which
     * was then finalizable into a zero-change ApprovedChangeSet and a NO_CHANGES redline. That is
     * the worst possible failure for a legal tool: a clean-looking artifact from an analysis that
     * never actually ran. A degraded analysis must be reported as a typed blocker.
     */
    @Test
    void aDegradedAnalysisBlocksFinalizationInsteadOfReadingAsClean() throws Exception {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        // A human corrects a concept after the review froze: the review's analysis environment no
        // longer matches the live semantics, so every check degrades and produces no signal. The
        // target is a clause that actually carries a concept, and the replacement is a different
        // one, so the effective classification genuinely changes.
        var clause = enrichmentService.getSemantics(corpus.contract().contractId(), 1)
                .clauses().stream()
                .filter(c -> c.effectivePrimaryConcept().isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no classified clause to correct"));
        var replacement = clause.effectivePrimaryConcept().orElseThrow()
                .equals(com.rivell.workbench.taxonomy.CoreConcepts.GOVERNING_LAW)
                        ? "LIMITATION_OF_LIABILITY" : "GOVERNING_LAW";
        enrichmentService.correctClause(corpus.contract().contractId(), 1, clause.id(),
                new com.rivell.workbench.contracts.FactCorrection(
                        replacement, java.util.Map.of(), UUID.randomUUID()));

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(analysisRepository.findExecutions(organizationId, run.id()))
                .isNotEmpty()
                .allSatisfy(execution -> assertThat(execution.status())
                        .isEqualTo(com.rivell.workbench.review.CheckExecutionStatus.DEGRADED));
        assertThat(findingRepository.findFindings(organizationId, run.id())).isEmpty();

        mockMvc.perform(get("/api/reviews/{id}/human-review", review.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readyToFinalize").value(false))
                .andExpect(jsonPath("$.data.blockers[?(@.code == 'ANALYSIS_DEGRADED')]")
                        .exists());

        mockMvc.perform(post("/api/reviews/{id}/finalize", review.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));
    }

    @Test
    void unresolvedFindingsBlockFinalization() throws Exception {
        var fixture = analyzedMainCorpus();

        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readyToFinalize").value(false))
                .andExpect(jsonPath(
                        "$.data.blockers[?(@.code == 'UNRESOLVED_REVIEW_REQUIRED_FINDINGS')]")
                        .exists());
        mockMvc.perform(post("/api/reviews/{id}/finalize", fixture.review().id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));
    }

    @Test
    void conflictingChangesOnOneClauseBlockFinalization() throws Exception {
        var fixture = analyzedCarveOutConflict();
        var sameClause = fixture.findings().stream()
                .filter(finding -> finding.subjectHeadingNodeId() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        Finding::subjectHeadingNodeId, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()))
                .values().stream()
                .filter(group -> group.size() >= 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No two findings share a clause in the carve-out corpus: "
                                + fixture.findings().stream()
                                        .map(f -> f.issueFamily() + "@" + f.subjectHeadingNodeId())
                                        .toList()));
        for (var finding : fixture.findings()) {
            if (finding.reviewClassification() == FindingClassification.INFORMATIONAL
                    || sameClause.stream().anyMatch(member -> member.id().equals(finding.id()))) {
                continue;
            }
            decide(fixture.review().id(), finding.id(),
                    """
                    {"expectedVersion": 0, "action": "REJECT",
                     "comment": "Not material for this negotiation."}
                    """);
        }
        for (var finding : sameClause) {
            decide(fixture.review().id(), finding.id(),
                    """
                    {"expectedVersion": 0, "action": "EDIT", "approvedAction": "REPLACE",
                     "approvedLanguage": "Replacement for %s."}
                    """.formatted(finding.id()));
        }

        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readyToFinalize").value(false))
                .andExpect(jsonPath(
                        "$.data.blockers[?(@.code == 'APPROVED_CHANGE_CONFLICT')]")
                        .exists());
        mockMvc.perform(post("/api/reviews/{id}/finalize", fixture.review().id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));

        decide(fixture.review().id(), sameClause.get(1).id(),
                """
                {"expectedVersion": 1, "action": "REJECT",
                 "comment": "Consolidated into the other clause decision."}
                """);
        var set = finalizeReview(fixture.review().id());
        assertThat(changesOf(set, "REPLACE")).hasSize(1);
    }

    @Test
    void finalizeTwiceReturnsTheSameFrozenSet() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ACCEPT"}
                """);

        var first = finalizeReview(fixture.review().id());
        var second = finalizeReview(fixture.review().id());

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(second.get("changes").size()).isEqualTo(first.get("changes").size());
        assertThat(countFinalizations(fixture.review().id())).isEqualTo(1);
        assertThat(countChanges(fixture.review().id())).isEqualTo(1);
    }

    @Test
    void decisionAfterFinalizationIsRejected() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ACCEPT"}
                """);
        finalizeReview(fixture.review().id());

        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 1, "action": "REJECT",
                                 "comment": "Too late."}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));
    }

    @Test
    void concurrentFinalizeConvergesToOneSet() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ACCEPT"}
                """);

        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var ids = java.util.Collections.synchronizedList(new ArrayList<String>());
        var failures = java.util.Collections.synchronizedList(new ArrayList<Throwable>());
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        ApprovedChangeSet set = finalizationService
                                .finalizeReview(fixture.review().id());
                        ids.add(set.id().toString());
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(failures).isEmpty();
        assertThat(ids).hasSize(2);
        assertThat(Set.copyOf(ids)).hasSize(1);
        assertThat(countFinalizations(fixture.review().id())).isEqualTo(1);
    }

    @Test
    void decideAndFinalizeInterleaveNeverFreezesAStaleRevision() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        var first = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "REJECT", "comment": "Initial position."}
                """).get("data");

        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var decisionOutcome = java.util.Collections.synchronizedList(new ArrayList<String>());
        var finalizeOutcome = java.util.Collections.synchronizedList(new ArrayList<String>());
        try {
            var decideFuture = executor.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    decisionService.decide(fixture.review().id(), finding.id(),
                            new DecisionCommand(1, null, DecisionAction.ACCEPT,
                                    null, null, null));
                    decisionOutcome.add("ok");
                } catch (ReviewAlreadyFinalizedException e) {
                    decisionOutcome.add("finalized");
                } catch (DecisionConflictException e) {
                    decisionOutcome.add("conflict");
                } catch (Throwable t) {
                    decisionOutcome.add("error:" + t.getClass().getSimpleName());
                }
                return null;
            });
            var finalizeFuture = executor.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    finalizationService.finalizeReview(fixture.review().id());
                    finalizeOutcome.add("ok");
                } catch (Throwable t) {
                    finalizeOutcome.add("error:" + t.getClass().getSimpleName());
                }
                return null;
            });
            start.countDown();
            decideFuture.get(30, TimeUnit.SECONDS);
            finalizeFuture.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(decisionOutcome).hasSize(1);
        assertThat(finalizeOutcome).hasSize(1);
        var current = decisionRepository
                .findCurrent(fixture.review().organizationId(), fixture.review().id(),
                        finding.id())
                .orElseThrow();
        var set = changeSetRepository
                .findByReview(fixture.review().organizationId(), fixture.review().id());
        if (finalizeOutcome.contains("ok")) {
            assertThat(set).isPresent();
            if (decisionOutcome.contains("ok")) {
                assertThat(current.revision()).isEqualTo(2);
                assertThat(set.get().changes()).hasSize(1);
                assertThat(set.get().changes().get(0).decisionId())
                        .isEqualTo(current.id());
            } else {
                assertThat(decisionOutcome).contains("finalized");
                assertThat(current.revision()).isEqualTo(1);
                assertThat(current.id().toString())
                        .isEqualTo(first.get("id").asText());
                assertThat(set.get().changes())
                        .allSatisfy(change -> assertThat(change.decisionId())
                                .isEqualTo(current.id()));
            }
        } else {
            assertThat(decisionOutcome).contains("ok");
            assertThat(current.revision()).isEqualTo(2);
        }
    }

    @Test
    void acceptGeneratedLanguageFreezesGenerativeProvenance() throws Exception {
        generativeProvider.reset();
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/m7-msa-uncapped.docx"));
        var ingested = contractIngestionService.ingest("m7-msa-uncapped.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        var review = reviewService.create(ingested.contractId(), 1, null);
        var run = analysisService.analyze(organizationId(), review.id());
        var finding = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(candidate -> candidate.proposedAction()
                        == ProposedAction.CONSIDER_GENERATED_LANGUAGE)
                .findFirst().orElseThrow();
        assertThat(finding.proposedLanguageSource().name()).isEqualTo("GENERATIVE");

        var decision = decide(review.id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ACCEPT"}
                """).get("data");
        assertThat(decision.get("languageSource").asText()).isEqualTo("GENERATIVE");
        assertThat(decision.get("approvedLanguage").asText())
                .isEqualTo(finding.proposedLanguage());
        assertThat(decision.get("approvedDraftingEvidenceId").isNull()).isTrue();

        var set = finalizeReview(review.id());
        var change = set.get("changes").get(0);
        assertThat(change.get("actionType").asText()).isEqualTo("REPLACE");
        assertThat(change.get("languageSource").asText()).isEqualTo("GENERATIVE");
        assertThat(change.get("approvedLanguage").asText())
                .isEqualTo(finding.proposedLanguage());
        assertThat(change.get("approvedDraftingEvidenceId").isNull()).isTrue();
    }

    @Test
    void finalizationIsProtectedByPermissionAndTenant() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ACCEPT"}
                """);
        finalizeReview(fixture.review().id());

        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), identityProvider.currentIdentity().organizationId(),
                Set.of("REVIEWER")));
        mockMvc.perform(post("/api/reviews/{id}/finalize", fixture.review().id()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reviews/{id}/approved-change-set", fixture.review().id()))
                .andExpect(status().isForbidden());

        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        mockMvc.perform(post("/api/reviews/{id}/finalize", fixture.review().id()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reviews/{id}/approved-change-set", fixture.review().id()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void approvedChangeSetIsSufficientForDeterministicExecution() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        var decision = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "EDIT",
                 "approvedLanguage": "Final agreed cap language."}
                """).get("data");
        var set = finalizeReview(fixture.review().id());

        assertThat(set.get("contractVersionNumber").asInt())
                .isEqualTo(fixture.review().contractVersionNumber());
        for (var change : set.get("changes")) {
            var action = change.get("actionType").asText();
            assertThat(change.get("targetKind").asText()).isEqualTo("CLAUSE");
            assertThat(change.get("targetHeadingNodeId").asText()).isNotBlank();
            if ("DELETE".equals(action)) {
                assertThat(change.get("approvedLanguage").isNull()).isTrue();
            } else {
                assertThat(change.get("approvedLanguage").asText()).isNotBlank();
            }
            assertThat(change.get("decisionId").asText())
                    .isEqualTo(decision.get("id").asText());
            mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}/decisions",
                            fixture.review().id(), change.get("findingId").asText()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].id")
                            .value(change.get("decisionId").asText()))
                    .andExpect(jsonPath("$.data.items[0].approvedLanguage")
                            .value(change.get("approvedLanguage").asText()));
        }
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private record Fixture(Review review, ReviewAnalysisRun run, List<Finding> findings) {
    }

    private Fixture analyzedMainCorpus() {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId(), review.id());
        return new Fixture(review, run,
                findingRepository.findFindings(organizationId(), run.id()));
    }

    private Fixture analyzedNdaReview() throws Exception {
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.NDA, PartyRole.RECEIVING_PARTY,
                        PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(ingested.contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId(), review.id());
        return new Fixture(review, run,
                findingRepository.findFindings(organizationId(), run.id()));
    }

    private Fixture analyzedCarveOutConflict() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m7-msa-carveout-conflict.docx"));
        var ingested = contractIngestionService.ingest("m7-msa-carveout-conflict.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(ingested.contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId(), review.id());
        return new Fixture(review, run,
                findingRepository.findFindings(organizationId(), run.id()));
    }

    private Finding finding(Fixture fixture, ProposedAction action) {
        return fixture.findings().stream()
                .filter(finding -> finding.proposedAction() == action)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No finding " + action + " in "
                        + fixture.findings().stream()
                                .map(f -> f.proposedAction() + "/" + f.reviewClassification())
                                .toList()));
    }

    private String decisionPath(UUID reviewId, UUID findingId) {
        return "/api/reviews/" + reviewId + "/findings/" + findingId + "/decision";
    }

    private JsonNode decide(UUID reviewId, UUID findingId, String body) throws Exception {
        MvcResult result = mockMvc.perform(put(decisionPath(reviewId, findingId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode finalizeReview(UUID reviewId) throws Exception {
        var result = mockMvc.perform(post("/api/reviews/{id}/finalize", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data");
    }

    private static List<JsonNode> changesOf(JsonNode set, String actionType) {
        var matches = new ArrayList<JsonNode>();
        for (var change : set.get("changes")) {
            if (actionType.equals(change.get("actionType").asText())) {
                matches.add(change);
            }
        }
        return matches;
    }

    private int countFinalizations(UUID reviewId) {
        return jdbcClient.sql("""
                select count(*) from review_finalization where review_id = :reviewId
                """).param("reviewId", reviewId).query(Integer.class).single();
    }

    private int countChanges(UUID reviewId) {
        return jdbcClient.sql("""
                select count(*) from approved_change where review_id = :reviewId
                """).param("reviewId", reviewId).query(Integer.class).single();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
