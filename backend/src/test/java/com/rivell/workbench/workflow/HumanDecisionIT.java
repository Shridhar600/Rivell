package com.rivell.workbench.workflow;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.LiabilityReviewIT;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewService;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class HumanDecisionIT {

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
    HumanDecisionService decisionService;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void acceptApprovedFallbackApprovesExactMachineLanguage() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        var decision = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ACCEPT"}
                """).get("data");

        assertThat(decision.get("action").asText()).isEqualTo("ACCEPT");
        assertThat(decision.get("revision").asInt()).isEqualTo(1);
        assertThat(decision.get("approvedAction").asText()).isEqualTo("REPLACE");
        assertThat(decision.get("approvedLanguage").asText())
                .isEqualTo(finding.proposedLanguage());
        assertThat(decision.get("languageSource").asText()).isEqualTo("APPROVED_DRAFTING");
        assertThat(decision.get("approvedDraftingEvidenceId").asText())
                .isEqualTo(finding.approvedDraftingEvidenceId().toString());
        assertThat(decision.get("actorId").asText())
                .isEqualTo(identityProvider.currentIdentity().userId().toString());
        assertThat(decision.get("decidedAt").asText()).isNotBlank();
        assertThat(decision.get("supersedesDecisionId").isNull()).isTrue();

        mockMvc.perform(get("/api/reviews/{id}/decisions", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].findingId")
                        .value(finding.id().toString()))
                .andExpect(jsonPath("$.data.truncated").value(false));
    }

    @Test
    void editPreservesMachineProposalAndUsesHumanText() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        var machineLanguage = finding.proposedLanguage();

        var decision = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "EDIT",
                 "approvedLanguage": "Human-authored replacement clause text."}
                """).get("data");

        assertThat(decision.get("approvedAction").asText()).isEqualTo("REPLACE");
        assertThat(decision.get("approvedLanguage").asText())
                .isEqualTo("Human-authored replacement clause text.");
        assertThat(decision.get("languageSource").asText()).isEqualTo("HUMAN_EDIT");
        assertThat(decision.get("approvedDraftingEvidenceId").isNull()).isTrue();

        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                        fixture.review().id(), finding.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposedLanguage").value(machineLanguage))
                .andExpect(jsonPath("$.data.proposedLanguageSource")
                        .value("APPROVED_DRAFTING"));
    }

    @Test
    void rejectResolvesWithReasonAndProducesNoChange() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "action": "REJECT"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        var decision = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "REJECT",
                 "comment": "Market-standard wording for this counterparty."}
                """).get("data");

        assertThat(decision.get("action").asText()).isEqualTo("REJECT");
        assertThat(decision.get("approvedAction").isNull()).isTrue();
        assertThat(decision.get("approvedLanguage").isNull()).isTrue();
        assertThat(decision.get("comment").asText())
                .isEqualTo("Market-standard wording for this counterparty.");
    }

    @Test
    void keepAsDraftedRecordsItsReasonAndProducesNoChange() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        var decision = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "REJECT",
                 "rejectReason": "KEEP_AS_DRAFTED"}
                """).get("data");

        assertThat(decision.get("action").asText()).isEqualTo("REJECT");
        assertThat(decision.get("rejectReason").asText()).isEqualTo("KEEP_AS_DRAFTED");
        assertThat(decision.get("approvedBy").isNull()).isTrue();
        assertThat(decision.get("approvedAction").isNull()).isTrue();
        assertThat(decision.get("approvedLanguage").isNull()).isTrue();
        assertThat(decision.get("comment").isNull()).isTrue();

        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rejected").value(1))
                .andExpect(jsonPath("$.data.escalated").value(0));
    }

    @Test
    void dismissFindingRecordsItsReason() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        var decision = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "REJECT",
                 "rejectReason": "FINDING_WRONG", "comment": "The clause is standard."}
                """).get("data");

        assertThat(decision.get("action").asText()).isEqualTo("REJECT");
        assertThat(decision.get("rejectReason").asText()).isEqualTo("FINDING_WRONG");
        assertThat(decision.get("approvedBy").isNull()).isTrue();
        assertThat(decision.get("comment").asText()).isEqualTo("The clause is standard.");
    }

    @Test
    void oldRejectWithoutAReasonStillLoadsAndDisplaysAsRejected() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        var decision = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "REJECT", "comment": "Legacy reason."}
                """).get("data");

        assertThat(decision.get("action").asText()).isEqualTo("REJECT");
        assertThat(decision.get("rejectReason").isNull()).isTrue();
        assertThat(decision.get("approvedBy").isNull()).isTrue();

        mockMvc.perform(get("/api/reviews/{id}/decisions", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].action").value("REJECT"));
    }

    @Test
    void resolveEscalationRequiresAnApproverNameAndClearsTheBlocker() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ESCALATE",
                 "comment": "Needs GC review before accepting."}
                """);

        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blockers[0].code").value("ACTIVE_ESCALATION"))
                .andExpect(jsonPath("$.data.blockers[0].message")
                        .value("1 clause is waiting for approval."));

        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 1, "action": "RESOLVE"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 1, "action": "RESOLVE", "approvedBy": "%s"}
                                """.formatted("x".repeat(121))))
                .andExpect(status().isBadRequest());

        var resolved = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 1, "action": "RESOLVE",
                 "approvedBy": "Jane Doe", "comment": "Approved by the GC."}
                """).get("data");
        assertThat(resolved.get("action").asText()).isEqualTo("RESOLVE");
        assertThat(resolved.get("approvedBy").asText()).isEqualTo("Jane Doe");
        assertThat(resolved.get("comment").asText()).isEqualTo("Approved by the GC.");

        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.escalated").value(0))
                .andExpect(jsonPath("$.data.blockers[?(@.code == 'ACTIVE_ESCALATION')]")
                        .doesNotExist());
        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}/decisions",
                        fixture.review().id(), finding.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].approvedBy").value("Jane Doe"));
    }

    @Test
    void rejectReasonAndApproverAreRefusedOnTheWrongAction() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "action": "ESCALATE",
                                 "rejectReason": "KEEP_AS_DRAFTED", "comment": "Why not."}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "action": "REJECT",
                                 "approvedBy": "Jane Doe", "comment": "Why not."}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolveRequiresAnExistingEscalation() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "action": "RESOLVE",
                                 "approvedBy": "Jane Doe"}
                                """))
                .andExpect(status().isBadRequest());

        decide(fixture.review().id(), finding.id(), """
                {"expectedVersion": 0, "action": "ACCEPT"}
                """);
        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 1, "action": "RESOLVE",
                                 "approvedBy": "Jane Doe"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void escalateIsUnresolvedAndRevisable() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        var escalated = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ESCALATE",
                 "comment": "Needs GC review before accepting."}
                """).get("data");
        assertThat(escalated.get("action").asText()).isEqualTo("ESCALATE");

        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.escalated").value(1))
                .andExpect(jsonPath("$.data.readyToFinalize").value(false))
                .andExpect(jsonPath("$.data.blockers[?(@.code == 'ACTIVE_ESCALATION')]")
                        .exists());

        var revised = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 1, "action": "REJECT",
                 "comment": "Reviewed with GC; standard wording accepted."}
                """).get("data");
        assertThat(revised.get("revision").asInt()).isEqualTo(2);
        assertThat(revised.get("supersedesDecisionId").asText())
                .isEqualTo(escalated.get("id").asText());

        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.escalated").value(0))
                .andExpect(jsonPath("$.data.rejected").value(1))
                .andExpect(jsonPath("$.data.blockers[?(@.code == 'ACTIVE_ESCALATION')]")
                        .doesNotExist());
    }

    @Test
    void acceptOfNonExecutableRecommendationIsConflict() throws Exception {
        var fixture = analyzedSurvivalGapReview();
        var finding = finding(fixture, ProposedAction.ESCALATE_TO_HUMAN);

        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "action": "ACCEPT"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));
    }

    @Test
    void staleExpectedVersionIsAConflictAndCurrentStaysAuthoritative() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        var first = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "REJECT", "comment": "First reviewer."}
                """).get("data");

        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "action": "ACCEPT"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));

        mockMvc.perform(get("/api/reviews/{id}/decisions", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first.get("id").asText()))
                .andExpect(jsonPath("$.data.items[0].action").value("REJECT"));
    }

    @Test
    void clientRequestIdMakesRetriesIdempotent() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        var requestId = UUID.randomUUID();
        var body = """
                {"expectedVersion": 0, "clientRequestId": "%s", "action": "REJECT",
                 "comment": "Not acceptable to us."}
                """.formatted(requestId);

        var first = decide(fixture.review().id(), finding.id(), body).get("data");
        var replay = decide(fixture.review().id(), finding.id(), body).get("data");

        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(replay.get("revision").asInt()).isEqualTo(1);
        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}/decisions",
                        fixture.review().id(), finding.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void revisionHistoryIsAppendOnlyNewestFirst() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        var accepted = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "ACCEPT"}
                """).get("data");
        var edited = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 1, "action": "EDIT",
                 "approvedLanguage": "Second thoughts clause text."}
                """).get("data");
        var rejected = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 2, "action": "REJECT", "comment": "Withdrawn."}
                """).get("data");

        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}/decisions",
                        fixture.review().id(), finding.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].revision").value(3))
                .andExpect(jsonPath("$.data.items[0].action").value("REJECT"))
                .andExpect(jsonPath("$.data.items[0].supersedesDecisionId")
                        .value(edited.get("id").asText()))
                .andExpect(jsonPath("$.data.items[1].revision").value(2))
                .andExpect(jsonPath("$.data.items[2].revision").value(1))
                .andExpect(jsonPath("$.data.items[2].supersedesDecisionId").isEmpty())
                .andExpect(jsonPath("$.data.items[2].id").value(accepted.get("id").asText()))
                .andExpect(jsonPath("$.data.items[0].id").value(rejected.get("id").asText()));

        mockMvc.perform(get("/api/reviews/{id}/decisions", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].revision").value(3));
    }

    @Test
    void editValidationMatrix() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        var invalidBodies = List.of(
                """
                {"expectedVersion": 0, "action": "EDIT"}
                """,
                """
                {"expectedVersion": 0, "action": "EDIT", "approvedAction": "DELETE",
                 "approvedLanguage": "Should not be here."}
                """,
                """
                {"expectedVersion": 0, "action": "EDIT", "approvedAction": "INSERT",
                 "approvedLanguage": "Wrong action for a clause."}
                """,
                """
                {"expectedVersion": 0, "action": "ACCEPT", "approvedLanguage": "Not allowed."}
                """,
                """
                {"action": "ACCEPT"}
                """,
                """
                {"expectedVersion": 0}
                """,
                """
                {"expectedVersion": 0, "action": "REJECT", "comment": "   "}
                """);
        for (var body : invalidBodies) {
            mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("400"));
        }
    }

    @Test
    void editDeleteIsExecutableAndCarriesNoLanguage() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        var decision = decide(fixture.review().id(), finding.id(),
                """
                {"expectedVersion": 0, "action": "EDIT", "approvedAction": "DELETE"}
                """).get("data");

        assertThat(decision.get("approvedAction").asText()).isEqualTo("DELETE");
        assertThat(decision.get("approvedLanguage").isNull()).isTrue();
        assertThat(decision.get("languageSource").isNull()).isTrue();
        assertThat(decision.get("approvedDraftingEvidenceId").isNull()).isTrue();

        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edited").value(1))
                .andExpect(jsonPath("$.data.blockers[?(@.code == 'MISSING_APPROVED_LANGUAGE')]")
                        .doesNotExist());
    }

    @Test
    void concurrentDecisionsOnOneFindingSerializeToOneWinner() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        var start = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var conflicts = new AtomicInteger();
        var failures = new ArrayList<Throwable>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        decisionService.decide(fixture.review().id(), finding.id(),
                                new DecisionCommand(0, null, DecisionAction.REJECT,
                                        null, null, "Concurrent reviewer."));
                        successes.incrementAndGet();
                    } catch (DecisionConflictException e) {
                        conflicts.incrementAndGet();
                    } catch (Throwable t) {
                        synchronized (failures) {
                            failures.add(t);
                        }
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
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}/decisions",
                        fixture.review().id(), finding.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].revision").value(1));
    }

    @Test
    void missingProvisionEditInsertsHumanLanguageAndAcceptIsInvalid() throws Exception {
        var contractId = enrichmentReadyNda();
        var review = reviewService.create(contractId, 1, ReviewPackRegistry.MSA_PACK);
        analysisService.analyze(organizationId(), review.id());
        var absence = finding(review.id(), ProposedAction.ADD_REQUIRED_PROVISION);

        mockMvc.perform(put(decisionPath(review.id(), absence.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "action": "ACCEPT"}
                                """))
                .andExpect(status().isConflict());

        var decision = decide(review.id(), absence.id(),
                """
                {"expectedVersion": 0, "action": "EDIT",
                 "approvedLanguage": "Liability is capped at fees paid in the prior 12 months."}
                """).get("data");
        assertThat(decision.get("approvedAction").asText()).isEqualTo("INSERT");
        assertThat(decision.get("languageSource").asText()).isEqualTo("HUMAN_EDIT");
    }

    @Test
    void decisionListsAreBoundedAndHistoryTruncationIsTruthful() throws Exception {
        var contractId = enrichmentReadyNda();
        var review = reviewService.create(contractId, 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId(), review.id());
        var findings = findingRepository.findFindings(organizationId(), run.id());
        assertThat(findings.size()).isGreaterThanOrEqualTo(2);
        for (var finding : findings.subList(0, 2)) {
            decide(review.id(), finding.id(), """
                    {"expectedVersion": 0, "action": "REJECT", "comment": "Not applicable."}
                    """);
        }

        mockMvc.perform(get("/api/reviews/{id}/decisions", review.id())
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.truncated").value(true));
        mockMvc.perform(get("/api/reviews/{id}/decisions", review.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.truncated").value(false));
        mockMvc.perform(get("/api/reviews/{id}/decisions", review.id())
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());

        var finding = findings.get(0);
        decide(review.id(), finding.id(), """
                {"expectedVersion": 1, "action": "ESCALATE", "comment": "Revisit."}
                """);
        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}/decisions",
                        review.id(), finding.id()).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].revision").value(2))
                .andExpect(jsonPath("$.data.truncated").value(true));
    }

    @Test
    void decisionRequiresCompletedAnalysis() throws Exception {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        mockMvc.perform(put("/api/reviews/{id}/findings/{findingId}/decision",
                        review.id(), UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "action": "ACCEPT"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));
    }

    @Test
    void unknownFindingAndForeignReviewAreNotFound() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        mockMvc.perform(put(decisionPath(fixture.review().id(), UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "action": "ACCEPT"}
                                """))
                .andExpect(status().isNotFound());

        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "action": "ACCEPT"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reviews/{id}/decisions", fixture.review().id()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}/decisions",
                        fixture.review().id(), finding.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void readOnlyIdentityCannotReadOrDecide() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);

        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), identityProvider.currentIdentity().organizationId(),
                Set.of("REVIEWER")));

        mockMvc.perform(get("/api/reviews/{id}/human-review", fixture.review().id()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(decisionPath(fixture.review().id(), finding.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion": 0, "action": "ACCEPT"}
                                """))
                .andExpect(status().isForbidden());
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private record Fixture(Review review, List<Finding> findings) {
    }

    private Fixture analyzedMainCorpus() {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId(), review.id());
        return new Fixture(review, findingRepository.findFindings(organizationId(), run.id()));
    }

    private Finding finding(Fixture fixture, ProposedAction action) {
        return fixture.findings().stream()
                .filter(finding -> finding.proposedAction() == action)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No finding with proposed action " + action + " in "
                                + fixture.findings().stream()
                                        .map(Finding::proposedAction).toList()));
    }

    private Finding finding(UUID reviewId, ProposedAction action) {
        var run = analysisRepository.findLatestCompletedRun(organizationId(), reviewId)
                .orElseThrow();
        return findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(finding -> finding.proposedAction() == action)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No finding " + action));
    }

    private Fixture analyzedSurvivalGapReview() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/m7-msa-survival-gap.docx"));
        var ingested = contractIngestionService.ingest("m7-msa-survival-gap.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(ingested.contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId(), review.id());
        return new Fixture(review, findingRepository.findFindings(organizationId(), run.id()));
    }

    private UUID enrichmentReadyNda() throws Exception {
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.NDA, PartyRole.RECEIVING_PARTY,
                        PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        return ingested.contractId();
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
}
