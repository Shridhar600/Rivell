package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * Engine v2 SEARCH route (spec §6): a clause no topic paired with a position is compared with
 * the positions a text search over the playbook finds, one round, judged by the same comparison.
 * Nothing relevant found falls back to the uncovered-clause triage; a failed search comparison
 * degrades the check and invents nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class PositionSearchRouteIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    /** The clause the topic call finds no topic in (pc-contract.docx, section 4). */
    private static final String TERMINATION = "Termination";
    /** A fragment of the PC playbook's termination position text. */
    private static final String TERMINATION_POSITION = "terminate for convenience";

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
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    com.rivell.workbench.retrieval.RetrievalRepository retrievalRepository;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private JsonNode json(String url, Object... variables) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url, variables))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<KnowledgeCandidate> positions;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        // the termination clause gets no topic, so no position is paired with it by topic
        scriptedProvider.topiclessHeadings.add(TERMINATION);
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    /** pc-contract.docx against the PC playbook (the flow PolicyComparisonFlowIT pins). */
    private UUID reviewedContract() throws Exception {
        positions = corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                        M6EvidenceCorpus.msaManifest2026(), null).stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.PLAYBOOK_RULE)
                .toList();
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/pc-contract.docx"));
        var ingested = contractIngestionService.ingest("pc-contract.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        return reviewService.create(ingested.contractId(), 1, null).id();
    }

    private UUID terminationPositionId() {
        return positions.stream().filter(position -> "Termination".equals(position.rawLabel()))
                .findFirst().orElseThrow().id();
    }

    /** The termination clause's check: the only one whose comparison was found by search. */
    @SuppressWarnings("unchecked")
    private ReviewCheckExecution terminationCheck(UUID runId) {
        var terminationId = terminationPositionId().toString();
        return analysisRepository.findExecutions(organizationId(), runId).stream()
                .filter(execution -> execution.detail().get("positions") instanceof List<?> judged
                        && ((List<Map<String, Object>>) judged).stream().anyMatch(entry ->
                                terminationId.equals(entry.get("positionCandidateId"))
                                        && "SEARCH".equals(entry.get("foundBy"))))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entryFor(ReviewCheckExecution check, UUID positionId) {
        return ((List<Map<String, Object>>) check.detail().get("positions")).stream()
                .filter(entry -> positionId.toString().equals(entry.get("positionCandidateId")))
                .findFirst().orElseThrow();
    }

    /** Signals that cite the position as the one the clause deviates from. */
    private List<RawReviewSignal> signalsCiting(UUID runId, UUID positionId) {
        return analysisRepository.findSignals(organizationId(), runId).stream()
                .filter(signal -> signal.payload() instanceof PositionDeviationPayload payload
                        && positionId.equals(payload.positionCandidateId()))
                .toList();
    }

    @Test
    void aClauseWithNoTopicIsComparedWithThePositionSearchFinds() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(TERMINATION, PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.positionAnswers.put(TERMINATION_POSITION,
                PolicyComparisonSpecs.BEYOND_FALLBACK);

        var run = analysisService.analyze(organizationId(), reviewId);

        var check = terminationCheck(run.id());
        assertThat(check.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
        assertThat(check.detail()).containsEntry("route", "SEARCH").containsEntry("addressed", true);
        assertThat(entryFor(check, terminationPositionId()))
                .containsEntry("foundBy", "SEARCH").containsEntry("flagged", true)
                // no embeddings in this test: only the full-text lane ranked it
                .containsEntry("lanes", Map.of("lexicalRank", 1, "fusedRank", 1));
        var deviation = signalsCiting(run.id(), terminationPositionId());
        assertThat(deviation).hasSize(1);
        assertThat(deviation.getFirst().issueFamily()).isEqualTo(IssueFamily.POSITION_NEEDS_FALLBACK);
        var finding = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK)
                .findFirst().orElseThrow();
        // the reviewer sees how the position was found
        assertThat(finding.quality()).containsEntry("foundBy", "SEARCH")
                .containsEntry("category", PolicyComparisonSpecs.BEYOND_FALLBACK);
        // a clause a searched position addresses is checked, not triaged
        assertThat(check.detail()).doesNotContainKey("uncoveredProbability");
        // no embeddings configured here: the search ran on full text and its trace says so
        var search = (Map<?, ?>) check.detail().get("search");
        var trace = retrievalRepository.findTrace(organizationId(),
                UUID.fromString((String) search.get("traceId"))).orElseThrow();
        assertThat(trace.degradation()).contains("VECTOR_LANE_UNCONFIGURED");
        assertThat(trace.stages()).containsKeys("structured", "lexical", "vector", "fusion");

        // diagnostics expose the search route's own numbers and the per-position foundBy
        JsonNode searched = null;
        for (var view : json("/api/reviews/{id}/diagnostics", reviewId)
                .at("/data/analysis/checks")) {
            if ("SEARCH".equals(view.path("route").asText())) {
                searched = view;
            }
        }
        assertThat(searched).as("a SEARCH check must reach diagnostics").isNotNull();
        assertThat(searched.path("addressed").asBoolean()).isTrue();
        assertThat(searched.path("searchFound").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(searched.path("searchFailed").asBoolean()).isFalse();
        JsonNode judgedView = null;
        for (var position : searched.path("judgedPositions")) {
            if (terminationPositionId().toString()
                    .equals(position.path("positionCandidateId").asText())
                    && position.path("positionKey").asText().startsWith("search_")) {
                judgedView = position;
            }
        }
        assertThat(judgedView).as("the searched termination position must be listed").isNotNull();
        assertThat(judgedView.path("foundBy").asText()).isEqualTo("SEARCH");
        assertThat(judgedView.has("foundBy")).isTrue();
    }

    @Test
    void aWalkAwayFoundBySearchIsSeriousEvenOnAnotherTopic() throws Exception {
        var reviewId = reviewedContract();
        // the confidentiality clause's own position does not address it; the search also finds
        // the liability position (another topic), which Jev judges a walk-away
        scriptedProvider.headingAnswers.put("Confidentiality", PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.positionAnswers.put("fees paid in the preceding 12 months",
                PolicyComparisonSpecs.WALK_AWAY);
        var liability = positions.stream()
                .filter(position -> "Limitation of Liability".equals(position.rawLabel()))
                .findFirst().orElseThrow().id();

        var run = analysisService.analyze(organizationId(), reviewId);

        // the clause that has a topic, yet was searched: the confidentiality clause
        var confidentiality = analysisRepository.findExecutions(organizationId(), run.id()).stream()
                .filter(execution -> "SEARCH".equals(execution.detail().get("route"))
                        && ((Integer) execution.detail().get("clauseTopicCount")) > 0)
                .findFirst().orElseThrow();
        var deviation = signalsCiting(run.id(), liability).stream()
                .filter(signal -> signal.planCheckId().equals(confidentiality.planCheckId()))
                .toList();
        assertThat(deviation).hasSize(1);
        assertThat(deviation.getFirst().quality()).containsEntry("foundBy", "SEARCH");
        // a position the text search found and Jev confirmed is not a secondary-topic fluke
        assertThat(((PositionDeviationPayload) deviation.getFirst().payload()).secondaryTopic())
                .isFalse();
        assertThat(findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(finding -> "SEARCH".equals(finding.quality().get("foundBy"))
                        && PolicyComparisonSpecs.WALK_AWAY.equals(finding.quality().get("category")))
                .toList())
                .isNotEmpty().allSatisfy(finding ->
                        assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH));
    }

    @Test
    void aFlagFoundBySearchProposesTheApprovedWording() throws Exception {
        // published before the review pins its knowledge; topics are read at enrichment
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_LIBRARY,
                M6EvidenceCorpus.msaManifest2026(), null);
        // the aggregate-cap clause gets no topic; the search finds the liability position
        scriptedProvider.topiclessHeadings.add("Aggregate Cap");
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put("Aggregate Cap", PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.positionAnswers.put("fees paid in the preceding 12 months",
                PolicyComparisonSpecs.BEYOND_FALLBACK);

        var run = analysisService.analyze(organizationId(), reviewId);

        var searched = analysisRepository.findExecutions(organizationId(), run.id()).stream()
                .filter(execution -> "SEARCH".equals(execution.detail().get("route"))
                        && Boolean.TRUE.equals(execution.detail().get("addressed")))
                .map(ReviewCheckExecution::planCheckId)
                .toList();
        var drafting = analysisRepository.findSignals(organizationId(), run.id()).stream()
                .filter(signal -> searched.contains(signal.planCheckId()))
                .filter(signal -> signal.signalType() == SignalType.APPROVED_DRAFTING_AVAILABLE)
                .toList();
        assertThat(drafting).hasSize(1);
        assertThat(((ApprovedDraftingPayload) drafting.getFirst().payload()).evidenceText())
                .contains("two times the fees");
    }

    @Test
    void aSearchThatFindsNothingRelevantFallsBackToTheTriage() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(TERMINATION, PolicyComparisonSpecs.NOT_ADDRESSED);

        var run = analysisService.analyze(organizationId(), reviewId);

        var check = terminationCheck(run.id());
        assertThat(check.detail()).containsEntry("addressed", false)
                .containsKey("uncoveredProbability");
        assertThat(scriptedProvider.uncoveredHeadings).anyMatch(heading -> heading.contains(TERMINATION));
        // positions the clause does not speak to leave no trace in the findings
        assertThat(signalsCiting(run.id(), terminationPositionId())).isEmpty();
        assertThat(entryFor(check, terminationPositionId())).containsEntry("flagged", false);
    }

    @Test
    void aFailedSearchComparisonDegradesTheCheck() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.failComparisonHeadings.add(TERMINATION);

        var run = analysisService.analyze(organizationId(), reviewId);

        var degraded = analysisRepository.findExecutions(organizationId(), run.id()).stream()
                .filter(execution -> "position_search_comparison_failed"
                        .equals(execution.detail().get("degradedReason")))
                .toList();
        assertThat(degraded).hasSize(1);
        assertThat(degraded.getFirst().status()).isEqualTo(CheckExecutionStatus.DEGRADED);
        assertThat(degraded.getFirst().signalCount()).isZero();
        // never an invented "not addressed": the triage did not run on a clause nobody judged
        assertThat(degraded.getFirst().detail()).doesNotContainKey("uncoveredProbability");
        assertThat(signalsCiting(run.id(), terminationPositionId())).isEmpty();
    }
}
