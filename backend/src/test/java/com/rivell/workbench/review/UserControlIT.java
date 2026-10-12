package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import org.springframework.test.web.servlet.ResultActions;
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
import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.ChangeLanguageSource;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.workflow.DecisionAction;
import com.rivell.workbench.workflow.DecisionCommand;
import com.rivell.workbench.workflow.FindingNotActionableException;
import com.rivell.workbench.workflow.HumanDecisionService;
import com.rivell.workbench.workflow.HumanReviewService;
import com.rivell.workbench.workflow.ReviewFinalizationService;

/**
 * P2b, human control over misses (design 2026-10-12 §9): the user sees what Rivell did with every
 * clause, and can add a finding Rivell missed; the added finding is decided, finalized and
 * redlined like any other.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class UserControlIT {

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
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    LiabilityReviewIT.ReviewGenerativeProvider generativeProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    HumanDecisionService decisionService;

    @Autowired
    HumanReviewService humanReviewService;

    @Autowired
    ReviewFinalizationService finalizationService;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        generativeProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    /** pc-contract.docx against the PC playbook: the flow PolicyComparisonFlowIT pins. */
    private UUID reviewedContract() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/pc-contract.docx"));
        var ingested = contractIngestionService.ingest("pc-contract.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        return reviewService.create(ingested.contractId(), 1, null).id();
    }

    private JsonNode json(String url, Object... variables) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url, variables))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Map<String, String> marks(UUID reviewId) throws Exception {
        var marks = new LinkedHashMap<String, String>();
        json("/api/reviews/{id}/clause-marks", reviewId).path("data").path("clauses")
                .forEach(clause -> marks.put(clause.path("headingNodeId").asText(),
                        clause.path("mark").asText()));
        return marks;
    }

    @Test
    void everyPlannedClauseIsMarkedAndTheFlaggedOnesMatchTheFindings() throws Exception {
        var reviewId = reviewedContract();
        assertThat(json("/api/reviews/{id}/clause-marks", reviewId).path("data").path("clauses"))
                .as("no completed analysis, no marks").isEmpty();
        var run = analysisService.analyze(organizationId(), reviewId);

        var marks = marks(reviewId);

        var planned = reviewRepository.findPlanChecks(organizationId(), reviewId).stream()
                .map(ReviewPlanCheck::subjectHeadingNodeId)
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .collect(Collectors.toSet());
        assertThat(marks.keySet()).isEqualTo(planned);
        var flagged = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(finding -> finding.subjectKind() == FindingSubjectKind.CLAUSE)
                .map(finding -> finding.subjectHeadingNodeId().toString())
                .collect(Collectors.toSet());
        assertThat(flagged).isNotEmpty();
        assertThat(marks).allSatisfy((heading, mark) ->
                assertThat(mark.equals("FLAGGED")).as(heading).isEqualTo(flagged.contains(heading)));
        assertThat(List.copyOf(marks.values())).contains("CHECKED");
    }

    private ResultActions postFinding(UUID reviewId, Object headingNodeId, String severity, String note)
            throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("headingNodeId", headingNodeId);
        body.put("severity", severity);
        body.put("note", note);
        return mockMvc.perform(post("/api/reviews/{id}/findings", reviewId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private UUID added(ResultActions result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.andReturn().getResponse()
                .getContentAsString()).path("data").path("findingId").asText());
    }

    /** A clause Rivell did not flag: the place a user-added finding is about. */
    private UUID unflaggedClause(UUID reviewId) throws Exception {
        return marks(reviewId).entrySet().stream()
                .filter(entry -> !entry.getValue().equals("FLAGGED"))
                .map(entry -> UUID.fromString(entry.getKey()))
                .findFirst().orElseThrow();
    }

    /** Withdraws every open machine finding, so only what the test decides is left. */
    private void rejectOpenMachineFindings(UUID reviewId, UUID runId) {
        findingRepository.findFindings(organizationId(), runId).stream()
                .filter(finding -> finding.issueFamily() != IssueFamily.USER_ADDED)
                .filter(finding -> finding.reviewClassification() != FindingClassification.INFORMATIONAL)
                .forEach(finding -> decisionService.decide(reviewId, finding.id(),
                        new DecisionCommand(0, null, DecisionAction.REJECT, null, null,
                                "Not part of this test.")));
    }

    @Test
    void aFindingYouAddIsDecidedFinalizedAndRedlinedLikeAnyOther() throws Exception {
        var reviewId = reviewedContract();
        var run = analysisService.analyze(organizationId(), reviewId);
        var heading = unflaggedClause(reviewId);

        var findingId = added(postFinding(reviewId, heading, "HIGH",
                "  Auto-renewal has no notice window.  ").andExpect(status().isCreated()));

        var stored = findingRepository.findFinding(organizationId(), reviewId, run.id(), findingId)
                .orElseThrow();
        assertThat(stored.issueFamily()).isEqualTo(IssueFamily.USER_ADDED);
        assertThat(stored.addedBy()).isEqualTo(identityProvider.currentIdentity().userId());
        assertThat(stored.observationText()).isEqualTo("Auto-renewal has no notice window.");
        assertThat(stored.severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(stored.subjectHeadingNodeId()).isEqualTo(heading);
        assertThat(marks(reviewId)).containsEntry(heading.toString(), "FLAGGED");
        assertThat(json("/api/reviews/{id}/findings", reviewId).path("data").path("items"))
                .anySatisfy(item -> {
                    assertThat(item.path("id").asText()).isEqualTo(findingId.toString());
                    assertThat(item.path("addedBy").asText())
                            .isEqualTo(identityProvider.currentIdentity().userId().toString());
                });
        assertThat(json("/api/reviews/{id}/findings/{findingId}", reviewId, findingId)
                .path("data").path("observationText").asText())
                .isEqualTo("Auto-renewal has no notice window.");

        // undecided, it blocks finalization like any review-required finding
        assertThat(humanReviewService.state(organizationId(), reviewId).assessment().progress()
                .blockers()).anySatisfy(blocker ->
                        assertThat(blocker.findingIds()).contains(findingId));
        // Rivell proposed nothing here, so there is nothing to accept as-is
        assertThatThrownBy(() -> decisionService.decide(reviewId, findingId,
                new DecisionCommand(0, null, DecisionAction.ACCEPT, null, null, null)))
                .isInstanceOf(FindingNotActionableException.class);

        var wording = "This Agreement renews only on the written agreement of both parties.";
        decisionService.decide(reviewId, findingId, new DecisionCommand(0, null,
                DecisionAction.EDIT, ChangeActionType.REPLACE, wording, null));
        rejectOpenMachineFindings(reviewId, run.id());
        var changeSet = finalizationService.finalizeReview(reviewId);

        assertThat(changeSet.changes()).singleElement().satisfies(change -> {
            assertThat(change.findingId()).isEqualTo(findingId);
            assertThat(change.targetHeadingNodeId()).isEqualTo(heading);
            assertThat(change.actionType()).isEqualTo(ChangeActionType.REPLACE);
            assertThat(change.approvedLanguage()).isEqualTo(wording);
            assertThat(change.languageSource()).isEqualTo(ChangeLanguageSource.HUMAN_EDIT);
        });
    }

    @Test
    void youWithdrawAFindingYouAddedWithAReasonAndTheHistoryKeepsIt() throws Exception {
        var reviewId = reviewedContract();
        var run = analysisService.analyze(organizationId(), reviewId);
        var findingId = added(postFinding(reviewId, unflaggedClause(reviewId), null,
                "Check the notice address.").andExpect(status().isCreated()));
        assertThat(findingRepository.findFinding(organizationId(), reviewId, run.id(), findingId)
                .orElseThrow().severity()).as("severity defaults to medium")
                .isEqualTo(FindingSeverity.MEDIUM);

        decisionService.decide(reviewId, findingId, new DecisionCommand(0, null,
                DecisionAction.REJECT, null, null, "The address is in the order form."));

        assertThat(humanReviewService.state(organizationId(), reviewId).assessment().progress()
                .blockers()).noneSatisfy(blocker ->
                        assertThat(blocker.findingIds()).contains(findingId));
        assertThat(humanReviewService.history(organizationId(), reviewId, findingId, 10))
                .singleElement().satisfies(decision -> {
                    assertThat(decision.action()).isEqualTo(DecisionAction.REJECT);
                    assertThat(decision.comment()).isEqualTo("The address is in the order form.");
                });
        assertThat(findingRepository.findFinding(organizationId(), reviewId, run.id(), findingId))
                .as("withdrawing keeps the record").isPresent();
    }

    @Test
    void addingAFindingIsRefusedWhereADecisionWouldBe() throws Exception {
        var reviewId = reviewedContract();
        postFinding(reviewId, UUID.randomUUID(), "LOW", "Too early.")
                .andExpect(status().isConflict());
        var run = analysisService.analyze(organizationId(), reviewId);
        var heading = unflaggedClause(reviewId);
        var before = findingRepository.countFindings(organizationId(), run.id());

        postFinding(reviewId, heading, "LOW", "   ").andExpect(status().isBadRequest());
        postFinding(reviewId, heading, "LOW", "x".repeat(2001)).andExpect(status().isBadRequest());
        postFinding(reviewId, null, "LOW", "No clause.").andExpect(status().isBadRequest());
        postFinding(reviewId, UUID.randomUUID(), "LOW", "Not a clause of this review.")
                .andExpect(status().isBadRequest());
        postFinding(UUID.randomUUID(), heading, "LOW", "Unknown review.")
                .andExpect(status().isNotFound());
        var admin = identityProvider.currentIdentity();
        identityProvider.use(new UserIdentity(UUID.randomUUID(), admin.organizationId(), Set.of()));
        postFinding(reviewId, heading, "LOW", "No rights.").andExpect(status().isForbidden());
        identityProvider.use(admin);
        assertThat(findingRepository.countFindings(organizationId(), run.id())).isEqualTo(before);

        rejectOpenMachineFindings(reviewId, run.id());
        finalizationService.finalizeReview(reviewId);
        postFinding(reviewId, heading, "LOW", "After the change set is frozen.")
                .andExpect(status().isConflict());
        assertThat(findingRepository.countFindings(organizationId(), run.id())).isEqualTo(before);
    }
}
