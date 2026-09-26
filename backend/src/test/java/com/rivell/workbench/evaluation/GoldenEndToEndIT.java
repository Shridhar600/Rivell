package com.rivell.workbench.evaluation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.DelText;
import org.docx4j.wml.P;
import org.docx4j.wml.RunDel;
import org.docx4j.wml.RunIns;
import org.docx4j.wml.Text;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
import com.rivell.workbench.contracts.ContractEnrichmentIT;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.GenerationRequest;
import com.rivell.workbench.intelligence.GenerationResult;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.intelligence.ModelBindings;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.review.LiabilityReviewIT;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewService;

/**
 * M10 golden end-to-end corpus.
 *
 * <p>Drives the complete product flow over the real HTTP API — DOCX upload, structure read,
 * semantic enrichment, knowledge upload, candidate approval, publication, review creation, machine
 * analysis, Findings read, human decisions, finalization, deterministic redline execution,
 * round-trip verification, artifact download — against real PostgreSQL with deterministic scripted
 * providers. No network, no live model.
 *
 * <p>Each case declares its expected checkpoints before the run. A per-case labeled result record is
 * written to {@code target/eval/} so later evaluation work has a stable artifact to read.
 *
 * <p>Coverage limits are declared in the M10 eval provider-strategy matrix, not guessed here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({GoldenEndToEndIT.GoldenProviderConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class GoldenEndToEndIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private static final String EVAL_OUTPUT = "target/eval/golden-end-to-end-results.json";

    private static final List<CaseResult> RESULTS = new CopyOnWriteArrayList<>();

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell");

    private static final String STORAGE_ROOT =
            "target/test-documents/golden/" + UUID.randomUUID();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rivell.storage.local.root", () -> STORAGE_ROOT);
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

    private UploadedContract current;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new com.rivell.workbench.organization.UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    // ---------------------------------------------------------------- cases

    /**
     * Case 1 — a counterparty MSA with an uncapped liability clause must produce a blocking
     * deviation Finding whose approved fallback is executed as a verified REPLACE redline.
     */
    @Test
    void msaUncappedLiabilityBecomesAVerifiedReplaceRedline() throws Exception {
        var declared = new Expected(
                "m5-msa-services.docx",
                "MSA",
                "LIMITATION_OF_LIABILITY",
                Set.of("LIABILITY_CAP_DEVIATION"),
                Set.of("USE_APPROVED_FALLBACK"),
                "REPLACE",
                "REDLINE_GENERATED");

        uploadContract("m5-msa-services.docx");
        publishMsaCorpus();
        var clauseCount = current.clauses();
        var enrichment = enrich("MSA", "CUSTOMER", "THIRD_PARTY", "US");
        var reviewId = createReview(ReviewPackRegistry.MSA_PACK);

        var findings = readFindings(reviewId);
        var deviation = findings.stream()
                .filter(f -> "LIMITATION_OF_LIABILITY".equals(f.get("concept").asText()))
                .filter(f -> "LIABILITY_CAP_DEVIATION".equals(f.get("issueFamily").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no liability deviation finding in "
                        + findings));

        assertThat(deviation.get("reviewClassification").asText())
                .isEqualTo("REVIEW_REQUIRED");
        assertThat(deviation.get("proposedAction").asText())
                .isEqualTo("USE_APPROVED_FALLBACK");
        assertThat(deviation.get("proposedLanguageSource").asText())
                .isEqualTo("APPROVED_DRAFTING");

        var approvedLanguage = findingDetail(reviewId, deviation.get("id").asText())
                .get("proposedLanguage").asText();
        assertThat(approvedLanguage).isNotBlank();

        decide(reviewId, deviation.get("id").asText(),
                "{\"expectedVersion\": 0, \"action\": \"ACCEPT\"}");
        resolveRemainingActionable(reviewId, findings, deviation.get("id").asText());

        var changeSet = finalize(reviewId);
        var execution = executeRedline(reviewId);

        assertThat(execution.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(execution.get("outcome").asText()).isEqualTo(declared.expectedRedlineOutcome());
        assertThat(execution.get("downloadAvailable").asBoolean()).isTrue();
        assertThat(execution.get("changeCount").asInt()).isPositive();
        assertThat(execution.get("changes")).isNotEmpty();
        for (var change : execution.get("changes")) {
            assertThat(change.get("status").asText()).isEqualTo("APPLIED");
            assertThat(change.get("verification").asText()).isEqualTo("VERIFIED");
        }

        var bytes = download(reviewId);
        assertThat(com.rivell.workbench.foundation.Hashes.sha256Hex(bytes))
                .isEqualTo(execution.get("artifactSha256").asText());
        assertThat(insertedText(bytes)).contains(approvedLanguage);
        assertThat(originalStillDownloadable(current.contractId())).isNotEmpty();
        assertThat(fixtureBytes("m5-msa-services.docx")).isNotEmpty();

        record(declared, "PASS", Map.of(
                "clauses", clauseCount,
                "enrichment", enrichment,
                "findings", findings.size(),
                "approvedChanges", changeSet.get("changes").size(),
                "artifactBytes", bytes.length));
    }

    /**
     * Case 2 — a missing required provision must produce an absence Finding that a human turns into
     * an INSERT, executed as a verified redline with deterministic placement.
     */
    @Test
    void missingRequiredProvisionBecomesAVerifiedInsertRedline() throws Exception {
        var declared = new Expected(
                "m5-nda-counterparty.docx",
                "NDA",
                "ABSENCE",
                Set.of("MISSING_REQUIRED_CONCEPT", "EXPECTED_CONCEPT_ABSENT",
                        "SURVIVAL_COVERAGE_GAP"),
                Set.of("ADD_REQUIRED_PROVISION"),
                "INSERT",
                "REDLINE_GENERATED");

        uploadContract("m5-nda-counterparty.docx");
        enrich("NDA", "RECEIVING_PARTY", "COUNTERPARTY_PAPER", "US",
                ReviewPackRegistry.MSA_PACK);
        var reviewId = createReview(null);

        var findings = readFindings(reviewId);
        var absence = findings.stream()
                .filter(f -> "ABSENCE".equals(f.get("subjectKind").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no absence finding in " + findings));

        assertThat(absence.get("proposedAction").asText())
                .isEqualTo("ADD_REQUIRED_PROVISION");

        var inserted = "Liability is capped at fees paid in the preceding 12 months.";
        decide(reviewId, absence.get("id").asText(), """
                {"expectedVersion": 0, "action": "EDIT",
                 "approvedLanguage": "%s"}
                """.formatted(inserted));
        resolveRemainingActionable(reviewId, findings, absence.get("id").asText());

        var changeSet = finalize(reviewId);
        var execution = executeRedline(reviewId);

        assertThat(execution.get("outcome").asText()).isEqualTo(declared.expectedRedlineOutcome());
        var applied = appliedChanges(execution, "INSERT");
        assertThat(applied).isNotEmpty();
        assertThat(applied.get(0).get("verification").asText()).isEqualTo("VERIFIED");
        assertThat(applied.get(0).get("placement")).isNotNull();
        assertThat(changeSet.get("changes")).isNotEmpty();

        var bytes = download(reviewId);
        assertThat(insertedText(bytes)).contains(inserted);

        record(declared, "PASS", Map.of(
                "findings", findings.size(),
                "approvedChanges", changeSet.get("changes").size(),
                "artifactBytes", bytes.length));
    }

    /**
     * Case 3 — a decision set that resolves to no approved change must finalize to a zero-change
     * ApprovedChangeSet, execute to NO_CHANGES, and refuse a download. This is the honest
     * "nothing to do" path: it must not fabricate an artifact.
     */
    @Test
    void zeroChangeFinalizationProducesNoChangesAndNoDownload() throws Exception {
        var declared = new Expected(
                "m7-msa-clean.docx",
                "MSA",
                "LIMITATION_OF_LIABILITY",
                Set.of(),
                Set.of(),
                "NONE",
                "NO_CHANGES");

        uploadContract("m7-msa-clean.docx");
        publishMsaCorpus();
        enrich("MSA", "CUSTOMER", "THIRD_PARTY", "US");
        var reviewId = createReview(ReviewPackRegistry.MSA_PACK);

        var findings = readFindings(reviewId);
        for (var finding : findings) {
            if ("INFORMATIONAL".equals(finding.get("reviewClassification").asText())) {
                continue;
            }
            decide(reviewId, finding.get("id").asText(), """
                    {"expectedVersion": 0, "action": "REJECT",
                     "comment": "Counterparty position accepted for this review."}
                    """);
        }

        var changeSet = finalize(reviewId);
        assertThat(changeSet.get("changes")).isEmpty();

        var execution = executeRedline(reviewId);
        assertThat(execution.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(execution.get("outcome").asText()).isEqualTo(declared.expectedRedlineOutcome());
        assertThat(execution.get("downloadAvailable").asBoolean()).isFalse();
        assertThat(execution.get("artifactSha256").isNull()).isTrue();
        assertThat(execution.get("changeCount").asInt()).isZero();

        mockMvc.perform(get("/api/reviews/{id}/redline/download", reviewId))
                .andExpect(status().isConflict());

        record(declared, "PASS", Map.of(
                "findings", findings.size(),
                "approvedChanges", 0,
                "artifactBytes", 0));
    }

    /**
     * Case 4 — a document Rivell cannot safely redline must fail closed with a typed reason and
     * produce no artifact, rather than a partial or unverified DOCX.
     */
    @Test
    void unsupportedSourceFailsClosedWithATypedReasonAndNoArtifact() throws Exception {
        var declared = new Expected(
                "m9-protected.docx",
                "MSA",
                "LIMITATION_OF_LIABILITY",
                Set.of(),
                Set.of(),
                "NONE",
                "PROTECTED_DOCUMENT_UNSUPPORTED");

        uploadContract("m9-protected.docx");
        publishMsaCorpus();
        enrich("MSA", "CUSTOMER", "THIRD_PARTY", "US");
        var reviewId = createReview(ReviewPackRegistry.MSA_PACK);

        var findings = readFindings(reviewId);
        var actionable = findings.stream()
                .filter(f -> !"INFORMATIONAL".equals(f.get("reviewClassification").asText()))
                .filter(f -> "CLAUSE".equals(f.get("subjectKind").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no actionable finding in " + findings));
        decide(reviewId, actionable.get("id").asText(), """
                {"expectedVersion": 0, "action": "EDIT", "approvedAction": "REPLACE",
                 "approvedLanguage": "Language that must never reach a protected document."}
                """);
        resolveRemainingActionable(reviewId, findings, actionable.get("id").asText());
        assertThat(finalize(reviewId).get("changes")).isNotEmpty();

        var execution = executeRedlineExpectingFailure(reviewId);
        assertThat(execution.get("status").asText()).isEqualTo("FAILED");
        assertThat(execution.get("failureCode").asText())
                .isEqualTo(declared.expectedRedlineOutcome());
        assertThat(execution.get("downloadAvailable").asBoolean()).isFalse();

        mockMvc.perform(get("/api/reviews/{id}/redline/download", reviewId))
                .andExpect(status().isConflict());

        record(declared, "PASS", Map.of(
                "findings", findings.size(),
                "approvedChanges", 1,
                "artifactBytes", 0));
    }

    // ---------------------------------------------------------------- flow steps

    private record UploadedContract(UUID contractId, int versionNumber, int clauses) {
    }

    private UploadedContract uploadContract(String fixture) throws Exception {
        var file = new MockMultipartFile("file", fixture, DOCX_MEDIA_TYPE, fixtureBytes(fixture));
        var result = mockMvc.perform(multipart("/api/contracts").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("OK"))
                .andReturn();
        var data = data(result);
        var contractId = UUID.fromString(data.get("contractId").asText());

        var structure = mockMvc.perform(get(
                        "/api/contracts/{id}/versions/{n}/structure",
                        contractId, data.get("versionNumber").asInt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus").value("OK"))
                .andReturn();
        var nodes = data(structure).get("nodes");
        var headings = 0;
        var paragraphs = 0;
        for (var node : nodes) {
            switch (node.get("nodeType").asText()) {
                case "HEADING" -> headings++;
                case "PARAGRAPH" -> paragraphs++;
                default -> { }
            }
        }
        assertThat(headings).as("canonical structure must expose clause headings").isPositive();
        assertThat(paragraphs).as("canonical structure must expose clause body paragraphs")
                .isPositive();
        var clauses = headings;
        current = new UploadedContract(contractId, data.get("versionNumber").asInt(), clauses);
        return current;
    }

    private String publishMsaCorpus() {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        return "POLICY+LIBRARY_PUBLISHED";
    }

    private String enrich(String contractType, String ourSideRole, String paperSource,
            String jurisdiction) throws Exception {
        enrich(contractType, ourSideRole, paperSource, jurisdiction, null);
        return "READY";
    }

    private String enrich(String contractType, String ourSideRole, String paperSource,
            String jurisdiction, String profilePackKey) throws Exception {
        var packField = profilePackKey == null
                ? ""
                : ", \"contractPackKey\": \"%s\"".formatted(profilePackKey);
        mockMvc.perform(post(
                        "/api/contracts/{id}/versions/{n}/enrichment",
                        current.contractId(), current.versionNumber())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"contractType": "%s", "ourSideRole": "%s",
                         "paperSource": "%s", "jurisdiction": "%s"%s}
                        """.formatted(contractType, ourSideRole, paperSource, jurisdiction,
                        packField)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrichmentStatus").value("READY"));
        return "READY";
    }

    private UUID createReview(String packKey) throws Exception {
        var body = packKey == null
                ? "{}"
                : "{\"contractPackKey\": \"%s\"}".formatted(packKey);
        var result = mockMvc.perform(post(
                        "/api/contracts/{id}/versions/{n}/reviews",
                        current.contractId(), current.versionNumber())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANALYSIS_QUEUED"))
                .andReturn();
        var reviewId = UUID.fromString(data(result).get("id").asText());
        analysisService.analyze(organizationId(), reviewId);
        mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        return reviewId;
    }

    private List<JsonNode> readFindings(UUID reviewId) throws Exception {
        var result = mockMvc.perform(get("/api/reviews/{id}/findings", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        var items = new ArrayList<JsonNode>();
        data(result).get("items").forEach(items::add);
        return items;
    }

    private JsonNode findingDetail(UUID reviewId, String findingId) throws Exception {
        var result = mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                reviewId, findingId))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    private void decide(UUID reviewId, String findingId, String body) throws Exception {
        mockMvc.perform(put("/api/reviews/{id}/findings/{findingId}/decision",
                        reviewId, findingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void resolveRemainingActionable(UUID reviewId, List<JsonNode> findings,
            String alreadyDecided) throws Exception {
        for (var finding : findings) {
            if ("INFORMATIONAL".equals(finding.get("reviewClassification").asText())) {
                continue;
            }
            if (finding.get("id").asText().equals(alreadyDecided)) {
                continue;
            }
            decide(reviewId, finding.get("id").asText(), """
                    {"expectedVersion": 0, "action": "REJECT",
                     "comment": "Counterparty position accepted for this review."}
                    """);
        }
    }

    private JsonNode finalize(UUID reviewId) throws Exception {
        var result = mockMvc.perform(post("/api/reviews/{id}/finalize", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    private JsonNode executeRedline(UUID reviewId) throws Exception {
        mockMvc.perform(post("/api/reviews/{id}/redline", reviewId))
                .andExpect(status().isOk());
        return redlineState(reviewId);
    }

    private JsonNode executeRedlineExpectingFailure(UUID reviewId) throws Exception {
        mockMvc.perform(post("/api/reviews/{id}/redline", reviewId))
                .andExpect(status().isConflict());
        return redlineState(reviewId);
    }

    private JsonNode redlineState(UUID reviewId) throws Exception {
        return data(mockMvc.perform(get("/api/reviews/{id}/redline", reviewId))
                .andExpect(status().isOk())
                .andReturn());
    }

    private byte[] download(UUID reviewId) throws Exception {
        return mockMvc.perform(get("/api/reviews/{id}/redline/download", reviewId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", DOCX_MEDIA_TYPE))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private byte[] originalStillDownloadable(UUID contractId) throws Exception {
        return mockMvc.perform(get(
                        "/api/contracts/{id}/versions/{n}/original", contractId, 1))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    // ---------------------------------------------------------------- helpers

    private List<JsonNode> appliedChanges(JsonNode execution, String actionType) {
        var applied = new ArrayList<JsonNode>();
        for (var change : execution.get("changes")) {
            if (actionType.equals(change.get("actionType").asText())
                    && "APPLIED".equals(change.get("status").asText())) {
                applied.add(change);
            }
        }
        return applied;
    }

    private String insertedText(byte[] docx) {
        return text(docx, true);
    }

    private String text(byte[] docx, boolean inserted) {
        var collected = new StringBuilder();
        try {
            var wordPackage = WordprocessingMLPackage.load(new ByteArrayInputStream(docx));
            for (var raw : wordPackage.getMainDocumentPart().getJaxbElement().getBody()
                    .getContent()) {
                var element = org.docx4j.XmlUtils.unwrap(raw);
                if (element instanceof P paragraph) {
                    collect(paragraph, inserted, collected);
                }
            }
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException(e));
        }
        return collected.toString();
    }

    private void collect(P paragraph, boolean inserted, StringBuilder collected) {
        for (var raw : paragraph.getContent()) {
            var element = org.docx4j.XmlUtils.unwrap(raw);
            if (element instanceof RunIns ins && inserted) {
                for (var wrapped : ins.getCustomXmlOrSmartTagOrSdt()) {
                    collectFrom(wrapped, collected);
                }
            } else if (element instanceof RunDel del && !inserted) {
                for (var wrapped : del.getCustomXmlOrSmartTagOrSdt()) {
                    collectFrom(wrapped, collected);
                }
            }
        }
    }

    private void collectFrom(Object element, StringBuilder collected) {
        var value = org.docx4j.XmlUtils.unwrap(element);
        if (value instanceof org.docx4j.wml.R run) {
            for (var child : run.getContent()) {
                var runChild = org.docx4j.XmlUtils.unwrap(child);
                if (runChild instanceof Text textElement) {
                    collected.append(textElement.getValue());
                } else if (runChild instanceof DelText delText) {
                    collected.append(delText.getValue());
                }
            }
        }
    }

    private byte[] fixtureBytes(String fixture) {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/" + fixture)) {
            if (stream == null) {
                throw new IllegalStateException("missing fixture " + fixture);
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private record Expected(String fixture, String contractType, String subjectConcept,
            Set<String> expectedIssueFamilies, Set<String> expectedProposedActions,
            String expectedChangeAction, String expectedRedlineOutcome) {
    }

    private record CaseResult(String fixture, String outcome, Map<String, Object> observations) {
    }

    private void record(Expected declared, String outcome, Map<String, Object> observations) {
        RESULTS.add(new CaseResult(declared.fixture(), outcome, observations));
    }

    @org.junit.jupiter.api.AfterAll
    static void writeLabeledResults() throws Exception {
        var mapper = new ObjectMapper();
        var rows = new ArrayList<Map<String, Object>>();
        for (var result : RESULTS) {
            rows.add(Map.of(
                    "fixture", result.fixture(),
                    "outcome", result.outcome(),
                    "observations", result.observations()));
        }
        var target = Path.of(EVAL_OUTPUT);
        Files.createDirectories(target.getParent());
        Files.writeString(target,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows), UTF_8);
    }

    /**
     * The M5 enrichment cue provider and the M7 review scripted provider are independent
     * implementations of the same port, and the full flow needs both. This configuration composes
     * them: review packs go to the review provider, everything else to the M5 cue provider. The
     * generative side dispatches on the spec's field shape.
     */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class GoldenProviderConfiguration {

        @Bean
        @Primary
        ModelBindingCatalog goldenModelBindingCatalog() {
            return () -> new ModelBindings(
                    new com.rivell.workbench.intelligence.ModelBinding("fake", "fake-decision"),
                    new com.rivell.workbench.intelligence.ModelBinding("fake", "fake-generative"));
        }

        @Bean
        @Primary
        ModelPolicy goldenModelPolicy(ModelBindingCatalog catalog) {
            return asset -> asset instanceof ReasoningSpec
                    ? catalog.current().generativeProvider()
                    : catalog.current().decisionProvider();
        }

        @Bean
        @Primary
        SemanticDecisionProvider goldenSemanticDecisionProvider() {
            var enrichment = new ContractEnrichmentIT.CueScriptedProvider();
            var review = new LiabilityReviewIT.ReviewScriptedProvider();
            return new SemanticDecisionProvider() {
                @Override
                public String id() {
                    return "fake";
                }

                @Override
                public ProviderExecutionResult execute(DecisionExecutionRequest request) {
                    if (isReviewPack(request.pack().questions().keySet())) {
                        return review.execute(request);
                    }
                    return enrichment.execute(request);
                }
            };
        }

        private static boolean isReviewPack(Set<String> questionNames) {
            return questionNames.contains(
                    com.rivell.workbench.review.M7DecisionSpecs.DEVIATION_TYPE)
                    || questionNames.contains(
                            com.rivell.workbench.review.M7DecisionSpecs.CONSISTENCY)
                    || questionNames.contains(
                            com.rivell.workbench.review.M7DecisionSpecs.SATISFIES_POLICY);
        }

        @Bean
        @Primary
        GenerativeReasoningProvider goldenGenerativeProvider() {
            var facts = new ContractEnrichmentIT.M5FakeGenerativeProvider();
            var review = new LiabilityReviewIT.ReviewGenerativeProvider();
            return new GenerativeReasoningProvider() {
                @Override
                public String id() {
                    return "fake";
                }

                @Override
                public GenerationResult generate(GenerationRequest request) {
                    if (isReviewSpec(request)) {
                        return review.generate(request);
                    }
                    return facts.generate(request);
                }

                private boolean isReviewSpec(GenerationRequest request) {
                    return request.spec().fields().containsKey("proposedLanguage")
                            || request.spec().fields().containsKey("escalationTrigger");
                }
            };
        }
    }


}
