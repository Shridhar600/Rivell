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
import java.util.stream.Collectors;

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
import com.rivell.workbench.taxonomy.CoreConcepts;

/**
 * Engine v2 omissions lane (spec §4, D4/D5/D6): the last check of a v2 plan reads the clause
 * checks' judgments, searches the contract for every applicable position no clause addressed,
 * and asks whether the position requires a provision. Required -> MEDIUM missing-provision
 * finding; unsure -> LOW needs-your-look item; not required -> nothing. A provider failure or
 * an incomplete run never invents an omission, and a v1 plan has no OMISSION check.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class OmissionLaneIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String LIMITATION_OF_LIABILITY = "Limitation of Liability";
    private static final String AGGREGATE_CAP = "Aggregate Cap";
    private static final String CONFIDENTIALITY = "Confidentiality";
    private static final String CAP_EXCEPTIONS = "Cap Exceptions";
    /** A fragment of the PC playbook's cap-exceptions position text. */
    private static final String CAP_EXCEPTIONS_POSITION = "fraud or willful misconduct";
    /** A fragment of the PC playbook's termination position text. */
    private static final String TERMINATION_POSITION = "terminate for convenience";
    private static final String MISSING_POSITION = "MISSING_POSITION";

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
        registry.add("rivell.review.engine", () -> "v2");
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewPlanBuilder planBuilder;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    ReviewRepository reviewRepository;

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
    JdbcClient jdbcClient;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private JsonNode json(String url, Object... variables) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url, variables))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** The diagnostics CheckView for one plan check key, over the HTTP surface. */
    private JsonNode diagnosticsCheck(UUID reviewId, String checkKey) throws Exception {
        for (var check : json("/api/reviews/{id}/diagnostics", reviewId)
                .at("/data/analysis/checks")) {
            if (checkKey.equals(check.path("key").asText())) {
                return check;
            }
        }
        throw new AssertionError("no diagnostics check " + checkKey);
    }

    private List<KnowledgeCandidate> positions;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private void publishPlaybook() {
        positions = corpusBuilder.publishAll(organizationId(),
                        M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                        M6EvidenceCorpus.msaManifest2026(), null).stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.PLAYBOOK_RULE)
                .toList();
    }

    /** One more review of pc-contract.docx against the already published PC playbook. */
    private UUID newReview() throws Exception {
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/pc-contract.docx"));
        var ingested = contractIngestionService.ingest("pc-contract.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        return reviewService.create(ingested.contractId(), 1, null).id();
    }

    private UUID reviewedContract() throws Exception {
        publishPlaybook();
        return newReview();
    }

    private UUID positionId(String label) {
        return positions.stream().filter(position -> label.equals(position.rawLabel()))
                .findFirst().orElseThrow().id();
    }

    private List<Finding> omissionFindings(UUID runId) {
        return findingRepository.findFindings(organizationId(), runId).stream()
                .filter(finding -> MISSING_POSITION.equals(finding.observationKind()))
                .toList();
    }

    private ReviewCheckExecution omissionExecution(UUID reviewId, UUID runId) {
        var omissionCheckIds = reviewRepository.findPlanChecks(organizationId(), reviewId).stream()
                .filter(check -> check.mechanism() == CheckMechanism.OMISSION)
                .map(ReviewPlanCheck::id)
                .collect(Collectors.toSet());
        return analysisRepository.findExecutions(organizationId(), runId).stream()
                .filter(execution -> omissionCheckIds.contains(execution.planCheckId()))
                .findFirst().orElseThrow();
    }

    private int clauseCount(UUID reviewId) {
        return (int) reviewRepository.findPlanChecks(organizationId(), reviewId).stream()
                .filter(check -> check.mechanism() == CheckMechanism.POLICY_COMPARISON)
                .count();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> presenceEntries(ReviewCheckExecution execution) {
        return (List<Map<String, Object>>) execution.detail().get("presence");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> flaggedEntries(ReviewCheckExecution execution) {
        return (List<Map<String, Object>>) execution.detail().get("flaggedBySearch");
    }

    @SuppressWarnings("unchecked")
    private static List<String> addressedBySearch(ReviewCheckExecution execution) {
        return (List<String>) execution.detail().get("addressedBySearch");
    }

    @SuppressWarnings("unchecked")
    private static List<String> searchDegradation(ReviewCheckExecution execution) {
        return (List<String>) execution.detail().get("searchDegradation");
    }

    @Test
    void aRequiredPositionNoClauseAddressesIsAMissingProvision() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.positionAnswers.put(CAP_EXCEPTIONS_POSITION,
                PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.presenceAnswers.put(CAP_EXCEPTIONS_POSITION, 0.92);
        var capExceptions = positionId(CAP_EXCEPTIONS);

        var run = analysisService.analyze(organizationId(), reviewId);

        var absence = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(finding -> finding.subjectKind() == FindingSubjectKind.ABSENCE)
                .toList();
        assertThat(absence).hasSize(1);
        var finding = absence.getFirst();
        assertThat(finding.issueFamily()).isEqualTo(IssueFamily.MISSING_REQUIRED_PROVISION);
        assertThat(finding.observationKind()).isEqualTo(MISSING_POSITION);
        assertThat(finding.observationText()).isEqualTo(
                "Missing: Cap Exceptions. Searched " + clauseCount(reviewId)
                        + " clauses; none addresses it.");
        assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
        assertThat(finding.reviewClassification()).isEqualTo(FindingClassification.REVIEW_REQUIRED);
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ADD_REQUIRED_PROVISION);
        assertThat(finding.quality())
                .containsEntry("searchedClauses", clauseCount(reviewId))
                .containsEntry("presenceProbability", 0.92)
                .containsEntry("route", "JEV");
        assertThat(((Number) finding.quality().get("comparedClauses")).intValue())
                .isBetween(1, 3);
        // the finding cites the position's evidence item
        assertThat(analysisRepository.findEvidenceItems(organizationId(), run.id(),
                finding.evidenceItemIds()))
                .anySatisfy(item -> assertThat(item.sourceCandidateId()).isEqualTo(capExceptions));

        var omission = omissionExecution(reviewId, run.id());
        assertThat(omission.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
        assertThat(omission.detail())
                .containsEntry("omissionCandidates", 1)
                .containsEntry("omissionsTruncated", false)
                .containsEntry("searchedClauses", clauseCount(reviewId));
        assertThat(presenceEntries(omission)).hasSize(1);
        assertThat(presenceEntries(omission).getFirst())
                .containsEntry("candidateId", capExceptions.toString())
                .containsEntry("probability", 0.92);
        assertThat(addressedBySearch(omission)).isEmpty();
        assertThat(flaggedEntries(omission)).isEmpty();
        assertThat(searchDegradation(omission)).contains("VECTOR_LANE_UNCONFIGURED");

        // the omission's position label reaches the findings API; firstReviewCategory stays null
        JsonNode summary = null;
        for (var item : json("/api/reviews/{id}/findings?limit=100", reviewId).at("/data/items")) {
            if (MISSING_POSITION.equals(item.path("observationKind").asText())) {
                summary = item;
            }
        }
        assertThat(summary).as("the omission finding must be listed").isNotNull();
        assertThat(summary.path("positionLabel").asText()).isEqualTo(CAP_EXCEPTIONS);
        assertThat(summary.has("firstReviewCategory")).isTrue();
        assertThat(summary.path("firstReviewCategory").isNull()).isTrue();
        var detail = json("/api/reviews/{id}/findings/{findingId}", reviewId,
                UUID.fromString(summary.path("id").asText())).at("/data");
        assertThat(detail.path("positionLabel").asText()).isEqualTo(CAP_EXCEPTIONS);
        assertThat(detail.path("firstReviewCategory").isNull()).isTrue();

        // the omission check's numbers reach diagnostics without any contract text
        var omissionView = diagnosticsCheck(reviewId, "OMISSION");
        assertThat(omissionView.path("omissionCandidates").asInt()).isEqualTo(1);
        assertThat(omissionView.path("searchedClauses").asInt()).isEqualTo(clauseCount(reviewId));
        assertThat(omissionView.path("addressedBySearch").asInt()).isZero();
        assertThat(omissionView.path("flaggedBySearch").asInt()).isZero();
        assertThat(omissionView.path("presenceProbabilities").get(capExceptions.toString()).asDouble())
                .isEqualTo(0.92);
    }

    @Test
    void anOmissionNeedsAPositionThatRequiresTheProvision() throws Exception {
        publishPlaybook();
        scriptedProvider.positionAnswers.put(CAP_EXCEPTIONS_POSITION,
                PolicyComparisonSpecs.NOT_ADDRESSED);
        var capExceptions = positionId(CAP_EXCEPTIONS);

        scriptedProvider.presenceAnswers.put(CAP_EXCEPTIONS_POSITION, 0.2);
        var reviewA = newReview();
        var runA = analysisService.analyze(organizationId(), reviewA);

        assertThat(omissionFindings(runA.id())).isEmpty();
        var presence = presenceEntries(omissionExecution(reviewA, runA.id()));
        assertThat(presence).hasSize(1);
        assertThat(presence.getFirst())
                .containsEntry("candidateId", capExceptions.toString())
                .containsEntry("probability", 0.2);

        // a second review in the same organization: the unsure answer becomes a LOW item
        scriptedProvider.presenceAnswers.put(CAP_EXCEPTIONS_POSITION, 0.6);
        var reviewB = newReview();
        var runB = analysisService.analyze(organizationId(), reviewB);

        var low = omissionFindings(runB.id());
        assertThat(low).hasSize(1);
        var finding = low.getFirst();
        assertThat(finding.severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(finding.reviewClassification()).isEqualTo(FindingClassification.REVIEW_REQUIRED);
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ADD_REQUIRED_PROVISION);
        assertThat(finding.observationText())
                .startsWith("Missing: Cap Exceptions. Searched ");
        assertThat(finding.quality())
                .containsEntry("route", "NEEDS_YOUR_LOOK")
                .containsEntry("uncertaintyReason", "omission_unsure")
                .containsEntry("presenceProbability", 0.6);
    }

    @Test
    void aPositionAClauseAddressedIsNeverAnOmission() throws Exception {
        var reviewId = reviewedContract();

        var run = analysisService.analyze(organizationId(), reviewId);

        var omission = omissionExecution(reviewId, run.id());
        assertThat(omission.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
        assertThat(omission.detail())
                .containsEntry("omissionCandidates", 0)
                .containsEntry("searchedClauses", clauseCount(reviewId));
        assertThat(addressedBySearch(omission)).isEmpty();
        assertThat(presenceEntries(omission)).isEmpty();
        assertThat(scriptedProvider.presenceCalls()).isZero();
        assertThat(omissionFindings(run.id())).isEmpty();
    }

    @Test
    void theOmissionNeverReJudgesAPairAClauseCheckJudged() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.positionAnswers.put(CAP_EXCEPTIONS_POSITION,
                PolicyComparisonSpecs.NOT_ADDRESSED);

        var run = analysisService.analyze(organizationId(), reviewId);

        var clauseChecks = reviewRepository.findPlanChecks(organizationId(), reviewId).stream()
                .filter(check -> check.mechanism() == CheckMechanism.POLICY_COMPARISON)
                .toList();
        var clauseCheckCalls = 0;
        for (var check : clauseChecks) {
            var execution = analysisRepository.findExecutions(organizationId(), run.id()).stream()
                    .filter(candidate -> candidate.planCheckId().equals(check.id()))
                    .findFirst().orElseThrow();
            if (execution.detail().containsKey("positions")) {
                clauseCheckCalls++;
            }
            if ("SEARCH".equals(execution.detail().get("route"))) {
                clauseCheckCalls++;
            }
        }
        var calls = scriptedProvider.policyComparisonCalls();
        var omissionCalls = calls.subList(clauseCheckCalls, calls.size());
        assertThat(omissionCalls).isNotEmpty();
        for (var call : omissionCalls) {
            // the clauses that already judged cap exceptions are never judged again
            assertThat(call.heading())
                    .doesNotContain(LIMITATION_OF_LIABILITY)
                    .doesNotContain(AGGREGATE_CAP);
            assertThat(call.questionKeys()).hasSize(1);
        }
        assertThat(calls).filteredOn(call -> call.heading().contains(LIMITATION_OF_LIABILITY))
                .hasSize(1);
        assertThat(calls).filteredOn(call -> call.heading().contains(AGGREGATE_CAP))
                .hasSize(1);
    }

    @Test
    void aPositionTheSearchFindsAddressedIsNeverAnOmission() throws Exception {
        var reviewId = reviewedContract();
        // the two liability clause checks leave both liability positions unaddressed; the
        // omission search then finds the confidentiality/termination clauses for cap exceptions
        scriptedProvider.headingAnswers.put(LIMITATION_OF_LIABILITY,
                PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.headingAnswers.put(AGGREGATE_CAP, PolicyComparisonSpecs.NOT_ADDRESSED);
        var capExceptions = positionId(CAP_EXCEPTIONS);

        var run = analysisService.analyze(organizationId(), reviewId);

        var omission = omissionExecution(reviewId, run.id());
        assertThat(omission.detail()).containsEntry("omissionCandidates", 2);
        assertThat(addressedBySearch(omission)).contains(capExceptions.toString());
        assertThat(flaggedEntries(omission)).isEmpty();
        // no presence question for the position the search addressed
        assertThat(presenceEntries(omission)).noneSatisfy(entry ->
                assertThat(entry).containsEntry("candidateId", capExceptions.toString()));
        assertThat(omissionFindings(run.id())).isEmpty();
        // diagnostics keep the two search outcomes apart: addressed, never flagged
        var omissionView = diagnosticsCheck(reviewId, "OMISSION");
        assertThat(omissionView.path("addressedBySearch").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(omissionView.path("flaggedBySearch").asInt()).isZero();
    }

    @Test
    void aSearchFoundPositionThatFlagsAClauseIsNeverAnOmission() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(LIMITATION_OF_LIABILITY,
                PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.headingAnswers.put(AGGREGATE_CAP, PolicyComparisonSpecs.NOT_ADDRESSED);
        // the omission's comparison of the confidentiality clause flags the position
        scriptedProvider.headingAnswers.put(CONFIDENTIALITY, PolicyComparisonSpecs.BEYOND_FALLBACK);
        var capExceptions = positionId(CAP_EXCEPTIONS);

        var run = analysisService.analyze(organizationId(), reviewId);

        var omission = omissionExecution(reviewId, run.id());
        var flagged = flaggedEntries(omission);
        assertThat(flagged).hasSize(1);
        assertThat(flagged.getFirst())
                .containsEntry("candidateId", capExceptions.toString())
                .containsEntry("category", PolicyComparisonSpecs.BEYOND_FALLBACK);
        // the pair the omission flagged addresses the position: it is not an omission
        assertThat(addressedBySearch(omission)).contains(capExceptions.toString());
        var headingId = UUID.fromString((String) flagged.getFirst().get("headingNodeId"));
        var confidentialityCheck = reviewRepository.findPlanChecks(organizationId(), reviewId)
                .stream()
                .filter(check -> check.mechanism() == CheckMechanism.POLICY_COMPARISON)
                .filter(check -> headingId.equals(check.subjectHeadingNodeId()))
                .findFirst().orElseThrow();
        assertThat(confidentialityCheck.concept())
                .isEqualTo(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS);
        assertThat(omissionFindings(run.id())).isEmpty();
    }

    @Test
    void aFailedPresenceQuestionDegradesTheCheckAndAddsNothing() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.positionAnswers.put(CAP_EXCEPTIONS_POSITION,
                PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.presenceAnswers.put(CAP_EXCEPTIONS_POSITION, 0.92);
        scriptedProvider.failPresence = true;

        var run = analysisService.analyze(organizationId(), reviewId);

        var omission = omissionExecution(reviewId, run.id());
        assertThat(omission.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
        assertThat(omission.detail())
                .containsEntry("degradedReason", "omission_provider_unavailable");
        assertThat(omission.signalCount()).isZero();
        assertThat(scriptedProvider.presenceCalls()).isEqualTo(1);
        assertThat(omissionFindings(run.id())).isEmpty();
    }

    @Test
    void aFailedOmissionComparisonDegradesTheCheckAndAddsNothing() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.positionAnswers.put(CAP_EXCEPTIONS_POSITION,
                PolicyComparisonSpecs.NOT_ADDRESSED);
        // only the omission's call on the confidentiality clause carries this pair
        scriptedProvider.failComparisonPairs.put(CONFIDENTIALITY, CAP_EXCEPTIONS_POSITION);

        var run = analysisService.analyze(organizationId(), reviewId);

        var omission = omissionExecution(reviewId, run.id());
        assertThat(omission.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
        assertThat(omission.detail())
                .containsEntry("degradedReason", "omission_provider_unavailable");
        assertThat(omission.signalCount()).isZero();
        assertThat(scriptedProvider.presenceCalls()).isZero();
        assertThat(omissionFindings(run.id())).isEmpty();
        // every clause check completed: the failure was the omission's own comparison
        var clauseCheckIds = reviewRepository.findPlanChecks(organizationId(), reviewId).stream()
                .filter(check -> check.mechanism() == CheckMechanism.POLICY_COMPARISON)
                .map(ReviewPlanCheck::id)
                .collect(Collectors.toSet());
        assertThat(analysisRepository.findExecutions(organizationId(), run.id()))
                .filteredOn(execution -> clauseCheckIds.contains(execution.planCheckId()))
                .allSatisfy(execution ->
                        assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED));
    }

    @Test
    void anIncompleteRunDegradesTheOmissionCheck() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.positionAnswers.put(CAP_EXCEPTIONS_POSITION,
                PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.presenceAnswers.put(CAP_EXCEPTIONS_POSITION, 0.92);
        scriptedProvider.failComparisonHeadings.add(AGGREGATE_CAP);

        var run = analysisService.analyze(organizationId(), reviewId);

        var omission = omissionExecution(reviewId, run.id());
        assertThat(omission.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
        assertThat(omission.detail())
                .containsEntry("degradedReason", "omission_inputs_incomplete");
        assertThat(omission.signalCount()).isZero();
        assertThat(scriptedProvider.presenceCalls()).isZero();
        assertThat(omissionFindings(run.id())).isEmpty();
    }

    @Test
    void twoMissingPositionsStayTwoFindings() throws Exception {
        var reviewId = reviewedContract();
        // both liability positions stay unaddressed; the presence question requires both
        scriptedProvider.positionAnswers.put("liability", PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.presenceAnswers.put("fees paid in the preceding 12 months", 0.92);
        scriptedProvider.presenceAnswers.put(CAP_EXCEPTIONS_POSITION, 0.92);
        var liability = positionId(LIMITATION_OF_LIABILITY);
        var capExceptions = positionId(CAP_EXCEPTIONS);

        var run = analysisService.analyze(organizationId(), reviewId);

        var missing = omissionFindings(run.id());
        assertThat(missing).hasSize(2);
        assertThat(missing).extracting(Finding::observationText)
                .anySatisfy(text -> assertThat(text).startsWith("Missing: Limitation of Liability."))
                .anySatisfy(text -> assertThat(text).startsWith("Missing: Cap Exceptions."));
        assertThat(missing).extracting(Finding::consolidationKey).doesNotHaveDuplicates();
        assertThat(missing).anySatisfy(finding -> assertThat(finding.consolidationKey())
                .endsWith(capExceptions.toString()));
        assertThat(missing).anySatisfy(finding -> assertThat(finding.consolidationKey())
                .endsWith(liability.toString()));
        assertThat(missing).allSatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
            assertThat(finding.quality()).containsEntry("route", "JEV");
        });
    }

    @Test
    void theOmissionCheckRunsLastAndOnlyInV2() throws Exception {
        var reviewId = reviewedContract();

        var plan = reviewRepository.findPlanChecks(organizationId(), reviewId);
        var omission = plan.getLast();
        assertThat(omission.mechanism()).isEqualTo(CheckMechanism.OMISSION);
        assertThat(omission.family()).isEqualTo(CheckFamily.CONCEPT_PRESENCE);
        assertThat(omission.expectation()).isEqualTo(CheckExpectation.REQUIRED);
        assertThat(omission.concept()).isEqualTo(ClauseTopics.NO_TOPIC);
        assertThat(omission.checkKey()).isEqualTo("OMISSION");
        assertThat(omission.subjectHeadingNodeId()).isNull();
        assertThat(omission.secondaryConcept()).isNull();
        assertThat(plan.stream().filter(check -> check.mechanism() == CheckMechanism.OMISSION))
                .hasSize(1);

        // the plan is frozen at create time: a v1 plan is asserted through the builder itself
        var snapshot = reviewRepository.findSnapshot(organizationId(), reviewId).orElseThrow();
        var semantics = enrichmentService.getSemantics(snapshot.contractId(),
                snapshot.contractVersionNumber());
        var v1Snapshot = new ReviewSnapshot(snapshot.reviewId(), snapshot.organizationId(),
                snapshot.contractId(), snapshot.contractVersionId(),
                snapshot.contractVersionNumber(), snapshot.contractType(), snapshot.ourSideRole(),
                snapshot.paperSource(), snapshot.jurisdiction(), snapshot.contractPackKey(),
                snapshot.packVersion(), snapshot.contractSemanticsId(),
                snapshot.semanticsPipelineVersion(), snapshot.semanticsDigest(),
                snapshot.knowledgePinNumber(), snapshot.effectiveOn(),
                snapshot.reviewPipelineVersion(), snapshot.embeddingIndexVersion(),
                snapshot.embeddingModelIdentity(), snapshot.modelPolicy(), ReviewEngine.V1,
                snapshot.createdAt());
        var v1Plan = planBuilder.build(reviewId, organizationId(), v1Snapshot, semantics);
        assertThat(v1Plan).noneMatch(check -> check.mechanism() == CheckMechanism.OMISSION);

        // a review running on v1 asks no presence question and adds no omission, even when a
        // required position is missing: the executor reads the frozen engine, not only the plan
        scriptedProvider.positionAnswers.put(CAP_EXCEPTIONS_POSITION,
                PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.presenceAnswers.put(CAP_EXCEPTIONS_POSITION, 0.92);
        jdbcClient.sql("update review_snapshot set review_engine = 'V1' where review_id = :id")
                .param("id", reviewId).update();
        var callsBefore = scriptedProvider.policyComparisonCalls().size();
        var run = analysisService.analyze(organizationId(), reviewId);
        assertThat(scriptedProvider.presenceCalls()).isZero();
        assertThat(omissionFindings(run.id())).isEmpty();
        assertThat(omissionExecution(reviewId, run.id()).detail())
                .containsEntry("omissionCandidates", 0);
        // only the clause checks compared: one call per clause that has positions
        var clauseCheckCalls = analysisRepository.findExecutions(organizationId(), run.id()).stream()
                .filter(execution -> execution.detail().containsKey("positions"))
                .count();
        assertThat(scriptedProvider.policyComparisonCalls().size() - callsBefore)
                .isEqualTo((int) clauseCheckCalls);
    }
}
