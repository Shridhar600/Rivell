package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
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
import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceService;
import com.rivell.workbench.retrieval.KnowledgePin;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * Engine v2 P3: the judges read the definitions of the terms a clause uses. The comparison state
 * carries them for the topic and SEARCH comparisons and the second opinions, findings cite them,
 * the check detail and diagnostics name the terms — and never with the property off.
 * The current-contract lane gathers them for the clause's heading node too, and not for a term
 * the clause itself defines.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class ClauseDefinitionsIT {

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
        registry.add("rivell.review.definitions", () -> "true");
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    /** Jev flags the clause without conviction: flag mass 0.68, inside the doubt band. */
    static final Map<String, Double> UNSURE_FLAG = Map.of(
            PolicyComparisonSpecs.MEETS_PREFERRED, 0.30,
            PolicyComparisonSpecs.WITHIN_FALLBACK, 0.02,
            PolicyComparisonSpecs.BEYOND_FALLBACK, 0.62,
            PolicyComparisonSpecs.WALK_AWAY, 0.02,
            PolicyComparisonSpecs.UNCLEAR, 0.02,
            PolicyComparisonSpecs.NOT_ADDRESSED, 0.02);
    static final String LIABILITY = "Limitation of Liability";
    static final String FEES_DEFINITION =
            "Fees means the fees paid by Customer under an Order Form";

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

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
    LiabilityReviewIT.ReviewGenerativeProvider generativeProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    EvidenceService evidenceService;

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

    /**
     * pc-contract.docx's content (see generate_policy_comparison_fixtures.py, contract_body) plus
     * a Definitions clause before section 1, the limited-to-Fees liability text, a run-in heading
     * clause that uses Fees and a clause that defines and uses Order Term.
     */
    static byte[] definitionsContract() throws Exception {
        var pkg = org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
        var body = pkg.getMainDocumentPart();
        body.addStyledParagraphOfText("Title", "Master Services Agreement");
        body.addParagraphOfText("This Master Services Agreement is entered into as of "
                + "January 1, 2026 by and between Northwind Customer Group LLC (\"Customer\") "
                + "and Pallas Cloud Services GmbH (\"Supplier\").");
        body.addStyledParagraphOfText("Heading1", "Definitions");
        body.addParagraphOfText("“Fees” means the fees paid by Customer under an Order Form; "
                + "and “Order Form” means an order signed by both Parties.");
        body.addStyledParagraphOfText("Heading1", "1. Limitation of Liability");
        body.addParagraphOfText("The liability of Supplier under this Agreement is limited to "
                + "the Fees, however arising.");
        body.addStyledParagraphOfText("Heading1", "2. Aggregate Cap");
        body.addParagraphOfText("Supplier's total liability shall not exceed two times the "
                + "Fees paid in the preceding 12 months.");
        body.addStyledParagraphOfText("Heading1", "3. Confidentiality");
        body.addParagraphOfText("Each Party shall protect the other Party's confidential "
                + "information with reasonable care. The obligations in this Section survive "
                + "termination for three (3) years.");
        body.addStyledParagraphOfText("Heading1", "4. Termination");
        body.addParagraphOfText("Either Party may terminate this Agreement for convenience "
                + "upon thirty (30) days' prior written notice to the other Party.");
        body.addStyledParagraphOfText("Heading1", "5. Governing Law");
        body.addParagraphOfText(
                "This Agreement is governed by the laws of the State of Delaware.");
        body.addStyledParagraphOfText("Heading1",
                "6. Service Credits. Service credits are capped at the Fees for the month.");
        body.addStyledParagraphOfText("Heading1", "7. Order Forms");
        body.addParagraphOfText("“Order Term” means the term stated in an Order Form.");
        body.addParagraphOfText("The Order Term begins on the effective date of the Order Form.");
        var out = new java.io.ByteArrayOutputStream();
        pkg.save(out);
        return out.toByteArray();
    }

    /** The in-test contract against the PC playbook (the flow SecondOpinionIT boots). */
    private UUID reviewedContract() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                M6EvidenceCorpus.msaManifest2026(), null);
        var ingested = contractIngestionService.ingest("definitions-contract.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(definitionsContract()));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        return reviewService.create(ingested.contractId(), 1, null).id();
    }

    private void jevUnsureOnLiability() {
        scriptedProvider.policyComparisonProbabilities = UNSURE_FLAG;
        scriptedProvider.probabilityHeadings.add(LIABILITY);
    }

    /** The one comparison call the liability check made (the order probe is off). */
    private LiabilityReviewIT.ReviewScriptedProvider.ComparisonCall liabilityCall() {
        return scriptedProvider.policyComparisonCalls().stream()
                .filter(call -> call.heading().contains(LIABILITY))
                .findFirst().orElseThrow();
    }

    private ClauseSemantics clauseNamed(UUID reviewId, String contentPrefix) {
        var review = reviewRepository.find(organizationId(), reviewId).orElseThrow();
        return enrichmentService.getSemantics(review.contractId(), review.contractVersionNumber())
                .clauses().stream()
                .filter(clause -> clause.contentText().startsWith(contentPrefix))
                .findFirst().orElseThrow();
    }

    private UUID liabilityHeadingNodeId(UUID reviewId) {
        return clauseNamed(reviewId, "1. Limitation of Liability").headingNodeId();
    }

    private ReviewCheckExecution liabilityCheck(UUID reviewId, UUID runId) {
        var headingNodeId = liabilityHeadingNodeId(reviewId);
        var planCheck = reviewRepository.findPlanChecks(organizationId(), reviewId).stream()
                .filter(check -> headingNodeId.equals(check.subjectHeadingNodeId()))
                .findFirst().orElseThrow();
        return analysisRepository.findExecutions(organizationId(), runId).stream()
                .filter(execution -> planCheck.id().equals(execution.planCheckId()))
                .findFirst().orElseThrow();
    }

    private EvidenceBundle bundleForClause(UUID reviewId, String contentPrefix) {
        var review = reviewRepository.find(organizationId(), reviewId).orElseThrow();
        var clause = clauseNamed(reviewId, contentPrefix);
        return evidenceService.build(EvidenceRequest.forClauseReview(organizationId(),
                ClauseTopics.subjectConcept(ClauseTopics.effectiveTopics(clause)),
                review.contractId(), review.contractVersionNumber(), clause.id(),
                clause.contentText(),
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US),
                LocalDate.of(2026, 1, 1), KnowledgePin.none()));
    }

    private JsonNode json(String url, Object... variables) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url, variables))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void aV2ComparisonCarriesTheClauseDefinitions() throws Exception {
        var reviewId = reviewedContract();

        var run = analysisService.analyze(organizationId(), reviewId);

        var call = liabilityCall();
        assertThat(call.definitions()).containsExactly(FEES_DEFINITION);
        var state = objectMapper.readTree(objectMapper.writeValueAsString(call.state()));
        assertThat(state.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(state.path("definitions")).hasSize(1);
        assertThat(state.path("definitions").get(0).asText()).isEqualTo(FEES_DEFINITION);

        assertThat(liabilityCheck(reviewId, run.id()).detail().get("definitionTerms"))
                .isEqualTo(List.of("Fees"));

        var headingNodeId = liabilityHeadingNodeId(reviewId).toString();
        JsonNode diagnostics = null;
        for (var check : json("/api/reviews/{id}/diagnostics", reviewId)
                .at("/data/analysis/checks")) {
            if (headingNodeId.equals(check.path("subjectHeadingNodeId").asText())) {
                diagnostics = check;
            }
        }
        assertThat(diagnostics).isNotNull();
        assertThat(diagnostics.path("definitionTerms").get(0).asText()).isEqualTo("Fees");
    }

    @Test
    void aFlaggedFindingCitesTheDefinitionsTheJudgeRead() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.policyComparisonProbabilities = Map.of(
                PolicyComparisonSpecs.MEETS_PREFERRED, 0.02,
                PolicyComparisonSpecs.WITHIN_FALLBACK, 0.02,
                PolicyComparisonSpecs.BEYOND_FALLBACK, 0.92,
                PolicyComparisonSpecs.WALK_AWAY, 0.02,
                PolicyComparisonSpecs.UNCLEAR, 0.01,
                PolicyComparisonSpecs.NOT_ADDRESSED, 0.01);
        scriptedProvider.probabilityHeadings.add(LIABILITY);

        var run = analysisService.analyze(organizationId(), reviewId);

        var cited = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(finding -> finding.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK)
                .flatMap(finding -> finding.evidenceItemIds().stream())
                .toList();
        assertThat(cited).isNotEmpty();
        assertThat(analysisRepository.findEvidenceItems(organizationId(), run.id(), cited))
                .anySatisfy(item -> {
                    assertThat(item.sourceType()).isEqualTo("CURRENT_CONTRACT_DEFINED_TERM");
                    assertThat(item.text()).startsWith("Fees means");
                });
    }

    @Test
    void aSearchFoundFindingCitesTheDefinitionsOfItsOwnSearchBundle() throws Exception {
        // the aggregate-cap clause gets no topic, so only the position search can pair a
        // rule with it; the search bundle's current-contract lane must carry the defined term, and
        // the finding must cite it. A change that stops running that lane for the search route
        // fails here.
        scriptedProvider.topiclessHeadings.add("Aggregate Cap");
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put("Aggregate Cap", PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.positionAnswers.put("two times the fees paid",
                PolicyComparisonSpecs.BEYOND_FALLBACK);

        var run = analysisService.analyze(organizationId(), reviewId);

        var cited = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(finding -> "SEARCH".equals(finding.quality().get("foundBy")))
                .flatMap(finding -> finding.evidenceItemIds().stream())
                .toList();
        assertThat(cited).as("a search-found finding must cite its search bundle's evidence")
                .isNotEmpty();
        assertThat(analysisRepository.findEvidenceItems(organizationId(), run.id(), cited))
                .anySatisfy(item -> {
                    assertThat(item.sourceType()).isEqualTo("CURRENT_CONTRACT_DEFINED_TERM");
                    assertThat(item.text()).startsWith("Fees means");
                });
    }

    @Test
    void theSecondOpinionSeesTheDefinitions() throws Exception {
        var reviewId = reviewedContract();
        jevUnsureOnLiability();

        analysisService.analyze(organizationId(), reviewId);

        assertThat(generativeProvider.secondOpinionDefinitions).isNotEmpty()
                .allSatisfy(definitions -> assertThat(definitions)
                        .containsExactly(FEES_DEFINITION));
    }

    @Test
    void aSecondOpinionQuotingOnlyADefinitionGoesToTheUser() throws Exception {
        var reviewId = reviewedContract();
        jevUnsureOnLiability();
        generativeProvider.secondOpinionQuote = "the fees paid by Customer under an Order Form";

        var run = analysisService.analyze(organizationId(), reviewId);

        @SuppressWarnings("unchecked")
        var judged = (List<Map<String, Object>>) liabilityCheck(reviewId, run.id())
                .detail().get("positions");
        assertThat(judged).filteredOn(entry -> "ADJUDICATE".equals(entry.get("route")))
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.get("secondOpinionOutcome"))
                        .isEqualTo("INVALID"));
        var look = findingRepository.findFindings(organizationId(), run.id()).stream()
                .filter(finding -> finding.issueFamily() == IssueFamily.POSITION_UNCERTAIN)
                .toList();
        assertThat(look).hasSize(1);
        assertThat(look.getFirst().severity()).isEqualTo(FindingSeverity.LOW);
    }

    @Test
    void aClauseWrittenInItsHeadingGetsTheDefinitionsItUses() throws Exception {
        var reviewId = reviewedContract();

        var bundle = bundleForClause(reviewId, "6. Service Credits");

        assertThat(bundle.currentContractItems()).anySatisfy(item -> {
            assertThat(item.sourceType()).isEqualTo("CURRENT_CONTRACT_DEFINED_TERM");
            assertThat(item.text()).isEqualTo(FEES_DEFINITION);
        });
    }

    @Test
    void aClauseThatDefinesATermDoesNotGetItsOwnDefinitionAgain() throws Exception {
        var reviewId = reviewedContract();

        var bundle = bundleForClause(reviewId, "7. Order Forms");

        assertThat(bundle.currentContractItems())
                .noneSatisfy(item -> assertThat(item.text()).startsWith("Order Term means"));
    }
}
