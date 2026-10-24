package com.rivell.workbench.redline;

import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.CoreConcepts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.DelText;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.RunDel;
import org.docx4j.wml.RunIns;
import org.docx4j.wml.Text;
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
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.LiabilityReviewIT;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.Review;
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
class RedlineExecutionIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell");

    private static final String STORAGE_ROOT =
            "target/test-documents/" + UUID.randomUUID();

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
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    LiabilityReviewIT.ReviewGenerativeProvider generativeProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    ApprovedChangeSetRepository changeSetRepository;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    com.rivell.workbench.foundation.DocumentStorage documentStorage;

    @org.springframework.beans.factory.annotation.Value("${rivell.storage.local.root}")
    String storageRoot;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void approvedFallbackExecutesAsAVerifiedTrackedRedlineAndDownloads() throws Exception {
        var fixture = analyzedMainCorpus();
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        decide(fixture.review().id(), finding.id(),
                "{\"expectedVersion\": 0, \"action\": \"ACCEPT\"}");
        finalize(fixture.review().id());

        var view = redline(fixture.review().id());

        assertThat(view.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(view.get("outcome").asText()).isEqualTo("REDLINE_GENERATED");
        assertThat(view.get("failureCode").isNull()).isTrue();
        assertThat(view.get("downloadAvailable").asBoolean()).isTrue();
        assertThat(view.get("sourceSha256").asText()).hasSize(64);
        assertThat(view.get("changes")).hasSize(1);
        var change = view.get("changes").get(0);
        assertThat(change.get("actionType").asText()).isEqualTo("REPLACE");
        assertThat(change.get("status").asText()).isEqualTo("APPLIED");
        assertThat(change.get("verification").asText()).isEqualTo("VERIFIED");

        var download = mockMvc.perform(get("/api/reviews/{id}/redline/download",
                        fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", DOCX_MEDIA_TYPE))
                .andReturn();
        var bytes = download.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotEmpty();
        assertThat(view.get("artifactSizeBytes").asLong()).isEqualTo(bytes.length);
        assertThat(com.rivell.workbench.foundation.Hashes.sha256Hex(bytes))
                .isEqualTo(view.get("artifactSha256").asText());

        var wordPackage = WordprocessingMLPackage.load(new ByteArrayInputStream(bytes));
        var body = wordPackage.getMainDocumentPart().getJaxbElement().getBody();
        var deleted = new ArrayList<String>();
        var inserted = new ArrayList<String>();
        for (var raw : body.getContent()) {
            var element = org.docx4j.XmlUtils.unwrap(raw);
            if (element instanceof P paragraph) {
                if (isDeleted(paragraph)) {
                    deleted.add(deletedText(paragraph));
                }
                if (isInserted(paragraph)) {
                    inserted.add(insertedText(paragraph));
                }
            }
        }
        assertThat(deleted).hasSize(3);
        assertThat(deleted.get(0)).startsWith("6.1 Subject to Section 6.3");
        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0)).isNotBlank();

        var original = mockMvc.perform(get("/api/contracts/{id}/versions/1/original",
                        fixture.review().contractId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(com.rivell.workbench.foundation.Hashes.sha256Hex(original))
                .isNotEqualTo(view.get("artifactSha256").asText());
    }

    @Test
    void humanEditedLanguageReachesTheRedlineExactly() throws Exception {
        var fixture = analyzedMainCorpus();
        var clause = clauseFindings(fixture).get(0);
        var language = "Edited confidentiality language agreed by the reviewer.";
        decide(fixture.review().id(), clause.id(), """
                {"expectedVersion": 0, "action": "EDIT",
                 "approvedAction": "REPLACE",
                 "approvedLanguage": "%s"}
                """.formatted(language));
        rejectOtherActionable(fixture, clause.id());
        finalize(fixture.review().id());

        var view = redline(fixture.review().id());
        assertThat(view.get("status").asText()).isEqualTo("COMPLETED");

        var bytes = downloadBytes(fixture.review().id());
        var inserted = insertedTexts(bytes);
        assertThat(inserted).contains(language);
    }

    @Test
    void approvedDeletionExecutesAsATrackedDeletion() throws Exception {
        var fixture = analyzedMainCorpus();
        var clause = clauseFindings(fixture).get(0);
        decide(fixture.review().id(), clause.id(), """
                {"expectedVersion": 0, "action": "EDIT", "approvedAction": "DELETE"}
                """);
        rejectOtherActionable(fixture, clause.id());
        finalize(fixture.review().id());

        var view = redline(fixture.review().id());
        assertThat(view.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(view.get("changes")).hasSize(1);
        assertThat(view.get("changes").get(0).get("actionType").asText()).isEqualTo("DELETE");

        var bytes = downloadBytes(fixture.review().id());
        assertThat(deletedTexts(bytes)).isNotEmpty();
        assertThat(insertedTexts(bytes)).isEmpty();
    }

    @Test
    void missingProvisionInsertExecutesWithDeterministicPlacement() throws Exception {
        var fixture = analyzedNda();
        var absence = fixture.findings().stream()
                .filter(finding -> finding.subjectKind() == FindingSubjectKind.ABSENCE)
                .filter(finding -> com.rivell.workbench.taxonomy.CoreConcepts
                        .LIMITATION_OF_LIABILITY.equals(finding.absenceConcept()))
                .findFirst().orElseThrow();
        assertThat(absence.absenceConcept()).isNotNull();
        var language = "Liability is capped at fees paid in the prior 12 months.";
        decide(fixture.review().id(), absence.id(), """
                {"expectedVersion": 0, "action": "EDIT", "approvedLanguage": "%s"}
                """.formatted(language));
        rejectOtherActionable(fixture, absence.id());
        finalize(fixture.review().id());

        var view = redline(fixture.review().id());
        assertThat(view.get("status").asText()).isEqualTo("COMPLETED");
        var change = view.get("changes").get(0);
        assertThat(change.get("actionType").asText()).isEqualTo("INSERT");
        assertThat(change.get("targetKind").asText()).isEqualTo("ABSENCE");
        assertThat(change.get("placement").asText()).startsWith("AFTER_CLAUSE[");
        assertThat(change.get("resolvedTarget").asText()).isEqualTo("LIMITATION_OF_LIABILITY");

        var bytes = downloadBytes(fixture.review().id());
        assertThat(insertedTexts(bytes)).contains(language);
        assertThat(deletedTexts(bytes)).isEmpty();
    }

    @Test
    void multipleApprovedChangesExecuteIndependently() throws Exception {
        var fixture = analyzedSource("m9-multi-change-source.docx");
        var replaceTarget = findingByFamily(fixture, "POSITION_NEEDS_FALLBACK");
        var deleteTarget = findingByFamily(fixture, "SURVIVAL_COVERAGE_GAP");
        var absence = absenceFinding(fixture);
        decide(fixture.review().id(), replaceTarget.id(), """
                {"expectedVersion": 0, "action": "EDIT", "approvedAction": "REPLACE",
                 "approvedLanguage": "Replacement language for the liability clause."}
                """);
        decide(fixture.review().id(), deleteTarget.id(), """
                {"expectedVersion": 0, "action": "EDIT", "approvedAction": "DELETE"}
                """);
        decide(fixture.review().id(), absence.id(), """
                {"expectedVersion": 0, "action": "EDIT",
                 "approvedLanguage": "Inserted indemnification language."}
                """);
        for (var finding : fixture.findings()) {
            if (finding.reviewClassification()
                    == com.rivell.workbench.review.FindingClassification.INFORMATIONAL) {
                continue;
            }
            if (finding.id().equals(replaceTarget.id()) || finding.id().equals(deleteTarget.id())) {
                continue;
            }
            reject(fixture.review().id(), finding.id());
        }
        finalize(fixture.review().id());

        var view = redline(fixture.review().id());
        assertThat(view.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(view.get("changeCount").asInt()).isEqualTo(3);
        assertThat(view.get("changes")).hasSize(3);
        assertThat(view.get("changes"))
                .allSatisfy(change -> assertThat(change.get("verification").asText())
                        .isEqualTo("VERIFIED"));
        var actions = new ArrayList<String>();
        for (var change : view.get("changes")) {
            actions.add(change.get("actionType").asText());
        }
        assertThat(actions).containsExactlyInAnyOrder("REPLACE", "DELETE", "INSERT");

        var bytes = downloadBytes(fixture.review().id());
        assertThat(insertedTexts(bytes))
                .contains("Replacement language for the liability clause.")
                .contains("Inserted indemnification language.");
        assertThat(deletedTexts(bytes)).isNotEmpty();
    }

    @Test
    void executionIsIdempotent() throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());

        var first = redline(fixture.review().id());
        var second = redline(fixture.review().id());

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(countExecutions(fixture.review().id())).isEqualTo(1);
        assertThat(countArtifacts(fixture.review().id())).isEqualTo(1);
        assertThat(artifactFiles(fixture.review().id())).isEqualTo(1);
    }

    /**
     * M10: redline placement must be derived from the semantics frozen at finalization, not from
     * whatever the contract semantics say at execution time. A human concept correction after
     * finalization changes the effective concepts the INSERT placement strategy reads, so a
     * placement computed from post-freeze classification is not reproducible from the approved
     * review. Executing after that correction must fail closed with a typed reason and store no
     * artifact.
     */
    @Test
    void semanticsDriftAfterFinalizationFailsClosedBeforeAnyArtifactExists()
            throws Exception {
        var fixture = analyzedNdaWithAbsenceFindings();
        // An INSERT is the only change type that consumes the effective concepts, so this is the case
        // the drift guard exists for. Each absence Finding becomes an INSERT.
        resolveAllActionable(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        var changeSet = changeSetRepository.findByReview(organizationId(), reviewId).orElseThrow();
          assertThat(changeSet.changes())
                  .as("this test is only meaningful with an INSERT in the set")
                  .anyMatch(change -> change.actionType()
                          == com.rivell.workbench.redline.ChangeActionType.INSERT);

          // A human corrects a concept after the review froze: the placement inputs no longer match.
          // The helper picks a clause that is not already GOVERNING_LAW and not already corrected, so
          // the correction always changes the digest it is meant to drift.
          driftSemanticsOfPinnedVersion(reviewId);

          var drifted = redlineExpectingFailure(reviewId);
        assertThat(drifted.get("status").asText()).isEqualTo("FAILED");
        assertThat(drifted.get("failureCode").asText()).isEqualTo("APPROVED_SEMANTICS_DRIFT");
        assertThat(drifted.get("downloadAvailable").asBoolean()).isFalse();
        assertThat(countArtifacts(reviewId)).as("a drifted execution must store no artifact")
                .isZero();
        assertThat(artifactFiles(reviewId)).isZero();
    }

    @Test
    void aCorrectionUnrelatedToPlacementDoesNotBlockAReplaceOnlyRedline() throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        var contractId = changeSetRepository.findByReview(organizationId(), reviewId)
                .orElseThrow().contractId();

        // Correct an unrelated clause's concept, after the review froze.
        var clause = enrichmentService.getSemantics(contractId, 1).clauses().stream()
                .filter(c -> c.effectivePrimaryConcept().isPresent())
                .findFirst()
                .orElseThrow();
        enrichmentService.correctClause(contractId, 1, clause.id(),
                new com.rivell.workbench.contracts.FactCorrection("GOVERNING_LAW",
                        Map.of(), UUID.randomUUID()));

        // This change set is a REPLACE, which resolves from the document structure and the approved
        // language. A concept change cannot move it, so the execution must still succeed.
        var execution = redline(reviewId);
        assertThat(execution.get("outcome").asText()).isEqualTo("REDLINE_GENERATED");
        assertThat(execution.get("downloadAvailable").asBoolean()).isTrue();
    }

    @Test
    void artifactStorageFailureIsReportedWithTheStandardErrorEnvelope() throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        var organizationId = organizationId();

        // A real misconfiguration: a file where the organisation's redline directory must be.
        Files.createDirectories(Path.of(STORAGE_ROOT, organizationId.toString()));
        Files.createFile(Path.of(STORAGE_ROOT, organizationId.toString(), "redline"));

        var result = mockMvc.perform(post("/api/reviews/{id}/redline", reviewId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("503"))
                .andExpect(jsonPath("$.title").value("Document storage unavailable"))
                .andReturn();
        assertThat(result.getResponse().getContentType()).contains("application/json");

        // No execution row and no artifact may be recorded for a store that never happened.
        assertThat(countExecutions(reviewId)).isZero();
        assertThat(countArtifacts(reviewId)).isZero();
    }

    @Test
    void semanticsDriftAfterAVerifiedExecutionKeepsTheCanonicalArtifact()
            throws Exception {
        var fixture = analyzedNdaWithAbsenceFindings();
        resolveAllActionable(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        var contractId = changeSetRepository.findByReview(organizationId(), reviewId)
                .orElseThrow().contractId();

        var first = redline(reviewId);
        assertThat(first.get("outcome").asText()).isEqualTo("REDLINE_GENERATED");
        var artifactSha = first.get("artifactSha256").asText();

        var clauseSemanticsId = enrichmentService.getSemantics(contractId, 1).clauses().stream()
                .filter(c -> c.effectivePrimaryConcept().isPresent())
                .findFirst()
                .orElseThrow()
                .id();
        enrichmentService.correctClause(contractId, 1, clauseSemanticsId,
                new com.rivell.workbench.contracts.FactCorrection(
                        "GOVERNING_LAW", Map.of(), UUID.randomUUID()));

        var second = redline(reviewId);
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(second.get("artifactSha256").asText()).isEqualTo(artifactSha);
        assertThat(countArtifacts(reviewId)).isEqualTo(1);
        assertThat(downloadBytes(reviewId)).isNotEmpty();
    }

    @Test
    void concurrentTriggersRaceToOneCanonicalExecutionWithoutOrphanArtifacts()
            throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        var organizationId = organizationId();

        var executor = Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<Callable<MvcResult>> tasks = List.of(
                    () -> {
                        start.await();
                        return mockMvc.perform(post("/api/reviews/{id}/redline", reviewId))
                                .andReturn();
                    },
                    () -> {
                        start.await();
                        return mockMvc.perform(post("/api/reviews/{id}/redline", reviewId))
                                .andReturn();
                    });
            var futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            var responses = new ArrayList<String>();
            for (var future : futures) {
                var result = future.get();
                responses.add(result.getResponse().getStatus() + " "
                        + result.getResponse().getContentAsString());
            }
            assertThat(responses).allMatch(response -> response.startsWith("200 "));
        } finally {
            executor.shutdown();
        }

        assertThat(countExecutions(reviewId)).isEqualTo(1);
        assertThat(countArtifacts(reviewId)).isEqualTo(1);
        assertThat(artifactFiles(reviewId, organizationId)).isEqualTo(1);
    }

    @Test
    void zeroChangeSetsCompleteWithoutAnArtifact() throws Exception {
        var fixture = analyzedNda();
        for (var finding : fixture.findings()) {
            if (finding.reviewClassification()
                    == com.rivell.workbench.review.FindingClassification.INFORMATIONAL) {
                continue;
            }
            reject(fixture.review().id(), finding.id());
        }
        finalize(fixture.review().id());

        var view = redline(fixture.review().id());
        assertThat(view.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(view.get("outcome").asText()).isEqualTo("NO_CHANGES");
        assertThat(view.get("changeCount").asInt()).isZero();
        assertThat(view.get("downloadAvailable").asBoolean()).isFalse();
        assertThat(view.get("artifactSha256").isNull()).isTrue();

        mockMvc.perform(get("/api/reviews/{id}/redline/download", fixture.review().id()))
                .andExpect(status().isConflict());
        assertThat(countExecutions(fixture.review().id())).isEqualTo(1);
    }

    @Test
    void preexistingRevisionsFailClosedWithAPersistedReport() throws Exception {
        var fixture = analyzedSource("m9-preexisting-revisions.docx");
        resolveAllActionable(fixture);
        finalize(fixture.review().id());

        mockMvc.perform(post("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));

        var view = readRedline(fixture.review().id());
        assertThat(view.get("status").asText()).isEqualTo("FAILED");
        assertThat(view.get("failureCode").asText())
                .isEqualTo("PREEXISTING_REVISIONS_UNSUPPORTED");
        assertThat(view.get("downloadAvailable").asBoolean()).isFalse();
        assertThat(view.get("changes")).isNotEmpty();
        assertThat(view.get("changes")).allSatisfy(change -> {
            assertThat(change.get("status").asText()).isEqualTo("NOT_APPLIED");
            assertThat(change.get("verification").isNull()).isTrue();
        });

        mockMvc.perform(post("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isConflict());
        assertThat(countExecutions(fixture.review().id())).isEqualTo(1);
    }

    @Test
    void executionUsesTheFrozenSourceAndNeverTouchesM8State() throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());
        var decisionsBefore = countRows("human_decision", fixture.review().id());
        var changesBefore = countRows("approved_change", fixture.review().id());
        var setBefore = readApprovedChangeSet(fixture.review().id());

        corpusBuilder.publishAll(identityProvider.currentIdentity().organizationId(),
                M6EvidenceCorpus.Fixture.POLICY_UK, M6EvidenceCorpus.msaManifest2026(), null);
        scriptedProvider.reset();
        generativeProvider.reset();

        var view = redline(fixture.review().id());

        assertThat(view.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(scriptedProvider.totalDecisions()).isZero();
        assertThat(generativeProvider.generations()).isZero();
        assertThat(countRows("human_decision", fixture.review().id()))
                .isEqualTo(decisionsBefore);
        assertThat(countRows("approved_change", fixture.review().id()))
                .isEqualTo(changesBefore);
        assertThat(readApprovedChangeSet(fixture.review().id())).isEqualTo(setBefore);
        assertThat(view.get("contractVersionNumber").asInt()).isEqualTo(1);
    }

    @Test
    void tamperedSourceBytesFailClosedWithAnIntegrityMismatch() throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());

        var storageKey = jdbcClient.sql("""
                select sa.storage_key from source_artifact sa
                join contract_version cv on cv.source_artifact_id = sa.id
                where cv.contract_id = :contractId and cv.version_number = 1
                """).param("contractId", fixture.review().contractId())
                .query(String.class).single();
        var key = new com.rivell.workbench.foundation.StorageKey(storageKey);
        documentStorage.delete(key);
        documentStorage.store(key, new ByteArrayInputStream("tampered bytes".getBytes()));

        mockMvc.perform(post("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString(
                                "no longer matches the file that was reviewed")))
                .andExpect(jsonPath("$.failureCode").value("SOURCE_INTEGRITY_MISMATCH"));
        mockMvc.perform(get("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isNotFound());
        assertThat(countExecutions(fixture.review().id())).isZero();
        assertThat(countArtifacts(fixture.review().id())).isZero();

        documentStorage.delete(key);
        documentStorage.store(key, new ByteArrayInputStream(
                Files.readAllBytes(Path.of("src/test/resources/fixtures/m5-msa-services.docx"))));
        assertThat(redline(fixture.review().id()).get("status").asText())
                .isEqualTo("COMPLETED");
    }

    @Test
    void frozenSourceVersionMismatchFailsClosed() throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());
        jdbcClient.sql("""
                update review_finalization set contract_version_id = :versionId
                where review_id = :reviewId
                """).param("versionId", UUID.randomUUID())
                .param("reviewId", fixture.review().id()).update();

        mockMvc.perform(post("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString(
                                "no longer matches the version these changes were approved on")))
                .andExpect(jsonPath("$.failureCode").value("SOURCE_VERSION_MISMATCH"));
        mockMvc.perform(get("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isNotFound());
        assertThat(countExecutions(fixture.review().id())).isZero();
    }

    @Test
    void crossOrganizationReadsAndDownloadsAreNotFound() throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());
        redline(fixture.review().id());

        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(),
                Set.of("ADMIN")));
        mockMvc.perform(get("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reviews/{id}/redline/download", fixture.review().id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadCarriesADeterministicFilenameAndDisposition() throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());
        redline(fixture.review().id());

        mockMvc.perform(get("/api/reviews/{id}/redline/download", fixture.review().id()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("-v1-redline.docx")));
    }

    @Test
    void readsBeforeExecutionAreNotFoundAndPermissionsAreEnforced() throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());

        mockMvc.perform(get("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isNotFound());

        identityProvider.use(new UserIdentity(UUID.randomUUID(),
                identityProvider.currentIdentity().organizationId(), Set.of("REVIEWER")));
        mockMvc.perform(post("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isForbidden());

        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(),
                Set.of("ADMIN")));
        mockMvc.perform(post("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void triggerWithoutAFinalizedChangeSetIsAConflict() throws Exception {
        var fixture = analyzedMainCorpus();

        mockMvc.perform(post("/api/reviews/{id}/redline", fixture.review().id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"));

        mockMvc.perform(post("/api/reviews/{id}/redline", UUID.randomUUID()))
                .andExpect(status().isConflict());
    }

    @Test
    void retryAppendsANewAttemptAndLeavesThePriorFailureIntact() throws Exception {
        var fixture = analyzedNdaWithAbsenceFindings();
        resolveAllActionable(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        driftSemanticsOfPinnedVersion(reviewId);

        var first = redlineExpectingFailure(reviewId);
        assertThat(first.get("attempt").asInt()).isEqualTo(1);
        var frozenSet = readApprovedChangeSet(reviewId).toString();

        var retried = retryExpectingFailure(reviewId);

        assertThat(retried.get("attempt").asInt()).isEqualTo(2);
        assertThat(retried.get("id").asText()).isNotEqualTo(first.get("id").asText());
        assertThat(retried.get("failureCode").asText()).isEqualTo("APPROVED_SEMANTICS_DRIFT");
        assertThat(countExecutions(reviewId)).isEqualTo(2);

        // The first attempt is preserved verbatim: a retry must never rewrite a recorded
        // failure into a success.
        var attempts = jdbcClient.sql("""
                select attempt_number, status, failure_code
                from redline_execution where review_id = :reviewId order by attempt_number
                """).param("reviewId", reviewId)
                .query((rs, n) -> rs.getInt("attempt_number") + "|" + rs.getString("status")
                        + "|" + rs.getString("failure_code")).list();
        assertThat(attempts).containsExactly(
                "1|FAILED|APPROVED_SEMANTICS_DRIFT", "2|FAILED|APPROVED_SEMANTICS_DRIFT");

        // The frozen change set is untouched by a retry.
        assertThat(readApprovedChangeSet(reviewId).toString()).isEqualTo(frozenSet);
        assertThat(countArtifacts(reviewId)).isZero();
        assertThat(artifactFiles(reviewId)).isZero();
        assertThat(readRedline(reviewId).get("attempt").asInt()).isEqualTo(2);
    }

    @Test
    void retryNeverPublishesAnArtifactAndDownloadStaysRefused() throws Exception {
        var fixture = analyzedNdaWithAbsenceFindings();
        resolveAllActionable(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        driftSemanticsOfPinnedVersion(reviewId);
        redlineExpectingFailure(reviewId);

        retryExpectingFailure(reviewId);

        assertThat(countArtifacts(reviewId))
                .as("a failed attempt stores no artifact, so there is nothing to download")
                .isZero();
        assertThat(artifactFiles(reviewId)).isZero();
        mockMvc.perform(get("/api/reviews/{id}/redline/download", reviewId))
                .andExpect(status().isConflict());
    }

    @Test
    void retryIsRefusedWhenTheLatestAttemptSucceeded() throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        var done = redline(reviewId);
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");

        mockMvc.perform(post("/api/reviews/{id}/redline/retry", reviewId))
                .andExpect(status().isConflict());

        assertThat(countExecutions(reviewId))
                .as("a completed redline is not retried, so no second artifact appears")
                .isEqualTo(1);
        assertThat(countArtifacts(reviewId)).isEqualTo(1);
    }

    @Test
    void retryIsRefusedWhenNoExecutionExistsToRetry() throws Exception {
        var fixture = analyzedMainCorpus();
        acceptFallback(fixture);
        finalize(fixture.review().id());

        mockMvc.perform(post("/api/reviews/{id}/redline/retry", fixture.review().id()))
                .andExpect(status().isConflict());

        assertThat(countExecutions(fixture.review().id())).isZero();
    }

    @Test
    void retryIsRefusedWithoutTheRedlinePermission() throws Exception {
        var fixture = analyzedNdaWithAbsenceFindings();
        resolveAllActionable(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        driftSemanticsOfPinnedVersion(reviewId);
        redlineExpectingFailure(reviewId);

        identityProvider.use(new com.rivell.workbench.organization.UserIdentity(
                UUID.randomUUID(), organizationId(), Set.of("REVIEWER")));

        mockMvc.perform(post("/api/reviews/{id}/redline/retry", reviewId))
                .andExpect(status().isForbidden());

        assertThat(countExecutions(reviewId)).isEqualTo(1);
    }

    @Test
    void retryIsTenantScoped() throws Exception {
        var fixture = analyzedNdaWithAbsenceFindings();
        resolveAllActionable(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        driftSemanticsOfPinnedVersion(reviewId);
        redlineExpectingFailure(reviewId);
        var before = countExecutions(reviewId);

        identityProvider.use(new com.rivell.workbench.organization.UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));

        // Same status the ordinary trigger gives when the change set is not visible, so a
        // review in another organization is indistinguishable from one that does not exist.
        mockMvc.perform(post("/api/reviews/{id}/redline/retry", reviewId))
                .andExpect(status().isConflict());
        assertThat(countExecutions(reviewId))
                .as("another tenant cannot append an attempt to this review")
                .isEqualTo(before);
    }

    @Test
    void theReviewsListReportsTheLatestAttemptRatherThanFailing() throws Exception {
        var fixture = analyzedNdaWithAbsenceFindings();
        resolveAllActionable(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        driftSemanticsOfPinnedVersion(reviewId);
        redlineExpectingFailure(reviewId);
        retryExpectingFailure(reviewId);

        var rows = json(mockMvc.perform(get("/api/reviews?limit=50"))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("items");

        var row = new java.util.ArrayList<JsonNode>();
        rows.forEach(item -> {
            if (item.get("id").asText().equals(reviewId.toString())) {
                row.add(item);
            }
        });
        assertThat(row).as("a review with two attempts still appears exactly once").hasSize(1);
        assertThat(row.get(0).get("redline").get("status").asText()).isEqualTo("FAILED");
    }

    /**
     * The one behaviour that justifies the endpoint: a retry that succeeds. Attempt 1 fails on
     * a semantics drift, the correction is reverted, and attempt 2 completes and verifies.
     * Without this, the failure path is heavily covered and the success path is not covered at
     * all, and the reviews-list projection's reason for reporting the newest attempt is
     * unproven.
     */
    @Test
    void retrySucceedsWhenTheCauseIsNoLongerPresentAndKeepsThePriorFailure() throws Exception {
        var fixture = analyzedNdaWithAbsenceFindings();
        resolveAllActionable(fixture);
        finalize(fixture.review().id());
        var reviewId = fixture.review().id();
        driftSemanticsOfPinnedVersion(reviewId);

        var first = redlineExpectingFailure(reviewId);
        assertThat(first.get("failureCode").asText()).isEqualTo("APPROVED_SEMANTICS_DRIFT");

        restoreSemantics(reviewId);

        var result = mockMvc.perform(post("/api/reviews/{id}/redline/retry", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        var retried = json(result).get("data");

        assertThat(retried.get("attempt").asInt()).isEqualTo(2);
        assertThat(retried.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(retried.get("outcome").asText()).isEqualTo("REDLINE_GENERATED");
        assertThat(retried.get("downloadAvailable").asBoolean()).isTrue();

        assertThat(countExecutions(reviewId))
                .as("the failed attempt is still on record beside the successful one")
                .isEqualTo(2);
        assertThat(countArtifacts(reviewId))
                .as("only the verified attempt stores an artifact")
                .isEqualTo(1);
        assertThat(artifactFiles(reviewId)).isEqualTo(1);

        // The recorded failure is untouched, not rewritten into the success.
        var attempts = jdbcClient.sql("""
                select attempt_number || '|' || status || '|' || coalesce(failure_code,'-')
                from redline_execution where review_id = :reviewId order by attempt_number
                """).param("reviewId", reviewId).query(String.class).list();
        assertThat(attempts).containsExactly(
                "1|FAILED|APPROVED_SEMANTICS_DRIFT", "2|COMPLETED|-");

        // Only a fully verified artifact is downloadable.
        var downloaded = downloadBytes(reviewId);
        assertThat(downloaded).isNotEmpty();
        assertThat(readRedline(reviewId).get("attempt").asInt()).isEqualTo(2);

        // And the list reports the newest attempt rather than the earlier failure.
        var rows = json(mockMvc.perform(get("/api/reviews?limit=50"))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("items");
        var row = new java.util.ArrayList<JsonNode>();
        rows.forEach(item -> {
            if (item.get("id").asText().equals(reviewId.toString())) {
                row.add(item);
            }
        });
        assertThat(row).hasSize(1);
        assertThat(row.get(0).get("redline").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(row.get(0).get("redline").get("downloadAvailable").asBoolean()).isTrue();
    }

    /** The concept the drifted clause had before this test corrected it. */
    private ConceptKey driftSemanticsOfPinnedVersion(UUID reviewId) {
        var changeSet = changeSetRepository.findByReview(organizationId(), reviewId).orElseThrow();
        var contractId = changeSet.contractId();
        var clause = enrichmentService.getSemantics(contractId, 1).clauses().stream()
                .filter(c -> c.effectivePrimaryConcept().isPresent())
                .filter(c -> !c.effectivePrimaryConcept().get().equals(CoreConcepts.GOVERNING_LAW))
                .filter(c -> !c.conceptCorrected())
                .min(java.util.Comparator.comparing(c -> c.id().toString()))
                .orElseThrow(() -> new AssertionError("no classified clause to correct"));
        var original = clause.effectivePrimaryConcept().get();
        enrichmentService.correctClause(contractId, 1, clause.id(),
                new com.rivell.workbench.contracts.FactCorrection(
                        "GOVERNING_LAW", Map.of(), UUID.randomUUID()));
        return original;
    }

    private void restoreSemantics(UUID reviewId) {
        var changeSet = changeSetRepository.findByReview(organizationId(), reviewId).orElseThrow();
        var contractId = changeSet.contractId();
        var clause = enrichmentService.getSemantics(contractId, 1).clauses().stream()
                .filter(c -> c.conceptCorrected())
                .filter(c -> c.primaryConcept().isPresent())
                .filter(c -> !c.primaryConcept().get().equals(CoreConcepts.GOVERNING_LAW))
                .min(java.util.Comparator.comparing(c -> c.id().toString()))
                .orElseThrow(() -> new AssertionError("no corrected clause to restore"));
        enrichmentService.correctClause(contractId, 1, clause.id(),
                new com.rivell.workbench.contracts.FactCorrection(
                        clause.primaryConcept().get().value(), Map.of(), UUID.randomUUID()));
    }

    private JsonNode retryExpectingFailure(UUID reviewId) throws Exception {
        mockMvc.perform(post("/api/reviews/{id}/redline/retry", reviewId))
                .andExpect(status().isConflict());
        return readRedline(reviewId);
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

    private Fixture analyzedNda() throws Exception {
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

    /**
     * A review whose Findings are absence Findings, so resolving them produces INSERTs - the only
     * change type whose placement consumes the effective concepts.
     */
    private Fixture analyzedNdaWithAbsenceFindings() throws Exception {
        var bytes = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.NDA, PartyRole.RECEIVING_PARTY,
                        PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(ingested.contractId(), 1, null);
        var run = analysisService.analyze(organizationId(), review.id());
        var findings = findingRepository.findFindings(organizationId(), run.id());
        assertThat(findings).as("this fixture must produce absence findings to insert for")
                .isNotEmpty()
                .allMatch(f -> f.subjectKind() == FindingSubjectKind.ABSENCE);
        return new Fixture(review, run, findings);
    }

    private Fixture analyzedSource(String fixtureName) throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(Path.of("src/test/resources/fixtures/" + fixtureName));
        var ingested = contractIngestionService.ingest(fixtureName,
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(ingested.contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId(), review.id());
        return new Fixture(review, run,
                findingRepository.findFindings(organizationId(), run.id()));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
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

    private List<Finding> clauseFindings(Fixture fixture) {
        return fixture.findings().stream()
                .filter(finding -> finding.subjectKind() == FindingSubjectKind.CLAUSE)
                .filter(finding -> finding.reviewClassification()
                        != com.rivell.workbench.review.FindingClassification.INFORMATIONAL)
                .toList();
    }

    private Finding absenceFinding(Fixture fixture) {
        return fixture.findings().stream()
                .filter(finding -> finding.subjectKind() == FindingSubjectKind.ABSENCE)
                .findFirst()
                .orElseThrow();
    }

    private void acceptFallback(Fixture fixture) throws Exception {
        var finding = finding(fixture, ProposedAction.USE_APPROVED_FALLBACK);
        decide(fixture.review().id(), finding.id(),
                "{\"expectedVersion\": 0, \"action\": \"ACCEPT\"}");
    }

    private void resolveAllActionable(Fixture fixture) throws Exception {
        for (var finding : fixture.findings()) {
            if (finding.reviewClassification()
                    == com.rivell.workbench.review.FindingClassification.INFORMATIONAL) {
                continue;
            }
            if (finding.subjectKind() == FindingSubjectKind.ABSENCE) {
                decide(fixture.review().id(), finding.id(), """
                        {"expectedVersion": 0, "action": "EDIT",
                         "approvedLanguage": "Deterministic inserted language."}
                        """);
            } else {
                decide(fixture.review().id(), finding.id(), """
                        {"expectedVersion": 0, "action": "EDIT",
                         "approvedAction": "REPLACE",
                         "approvedLanguage": "Deterministic replacement language."}
                        """);
            }
        }
    }

    private Finding findingByFamily(Fixture fixture, String issueFamily) {
        return fixture.findings().stream()
                .filter(finding -> issueFamily.equals(finding.issueFamily().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No finding family " + issueFamily
                        + " in " + fixture.findings().stream()
                                .map(f -> String.valueOf(f.issueFamily()) + "/"
                                        + f.subjectKind() + "/"
                                        + f.reviewClassification())
                                .toList()));
    }

    private void rejectOtherActionable(Fixture fixture, UUID decidedFindingId) throws Exception {
        for (var finding : fixture.findings()) {
            if (finding.reviewClassification()
                    == com.rivell.workbench.review.FindingClassification.INFORMATIONAL) {
                continue;
            }
            if (finding.id().equals(decidedFindingId)) {
                continue;
            }
            reject(fixture.review().id(), finding.id());
        }
    }

    private void reject(UUID reviewId, UUID findingId) throws Exception {
        decide(reviewId, findingId, """
                {"expectedVersion": 0, "action": "REJECT",
                 "comment": "Not required for this review."}
                """);
    }

    private JsonNode decide(UUID reviewId, UUID findingId, String body) throws Exception {
        var result = mockMvc.perform(put("/api/reviews/{id}/findings/{findingId}/decision",
                        reviewId, findingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return json(result);
    }

    private void finalize(UUID reviewId) throws Exception {
        mockMvc.perform(post("/api/reviews/{id}/finalize", reviewId))
                .andExpect(status().isOk());
    }

    private JsonNode redlineExpectingFailure(UUID reviewId) throws Exception {
        mockMvc.perform(post("/api/reviews/{id}/redline", reviewId))
                .andExpect(status().isConflict());
        var state = mockMvc.perform(get("/api/reviews/{id}/redline", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        return json(state).get("data");
    }

    private JsonNode redline(UUID reviewId) throws Exception {
        var result = mockMvc.perform(post("/api/reviews/{id}/redline", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data");
    }

    private JsonNode readRedline(UUID reviewId) throws Exception {
        var result = mockMvc.perform(get("/api/reviews/{id}/redline", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data");
    }

    private byte[] downloadBytes(UUID reviewId) throws Exception {
        return mockMvc.perform(get("/api/reviews/{id}/redline/download", reviewId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private JsonNode readApprovedChangeSet(UUID reviewId) throws Exception {
        var result = mockMvc.perform(get("/api/reviews/{id}/approved-change-set", reviewId))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data");
    }

    private int countExecutions(UUID reviewId) {
        return jdbcClient.sql("""
                select count(*) from redline_execution where review_id = :reviewId
                """).param("reviewId", reviewId).query(Integer.class).single();
    }

    private long artifactFiles(UUID reviewId) {
        return artifactFiles(reviewId, organizationId());
    }

    private long artifactFiles(UUID reviewId, UUID organizationId) {
        var directory = Path.of(storageRoot, organizationId.toString(), "redline");
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var files = Files.list(directory)) {
            return files.count();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int countArtifacts(UUID reviewId) {
        return jdbcClient.sql("""
                select count(*) from redline_artifact a
                join redline_execution e on e.id = a.execution_id
                where e.review_id = :reviewId
                """).param("reviewId", reviewId).query(Integer.class).single();
    }

    private int countRows(String table, UUID reviewId) {
        return jdbcClient.sql("select count(*) from " + table + " where review_id = :reviewId")
                .param("reviewId", reviewId).query(Integer.class).single();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static boolean isDeleted(P paragraph) {
        return paragraph.getPPr() != null && paragraph.getPPr().getRPr() != null
                && paragraph.getPPr().getRPr().getDel() != null;
    }

    private static boolean isInserted(P paragraph) {
        return paragraph.getPPr() != null && paragraph.getPPr().getRPr() != null
                && paragraph.getPPr().getRPr().getIns() != null;
    }

    private static List<String> deletedTexts(byte[] bytes) {
        var texts = new ArrayList<String>();
        for (var paragraph : paragraphs(bytes)) {
            if (isDeleted(paragraph)) {
                texts.add(collect(paragraph, true));
            }
        }
        return texts;
    }

    private static List<String> insertedTexts(byte[] bytes) {
        var texts = new ArrayList<String>();
        for (var paragraph : paragraphs(bytes)) {
            if (isInserted(paragraph)) {
                texts.add(collect(paragraph, false));
            }
        }
        return texts;
    }

    private static List<P> paragraphs(byte[] bytes) {
        try {
            var wordPackage = WordprocessingMLPackage.load(new ByteArrayInputStream(bytes));
            var paragraphs = new ArrayList<P>();
            for (var raw : wordPackage.getMainDocumentPart().getJaxbElement().getBody()
                    .getContent()) {
                var element = org.docx4j.XmlUtils.unwrap(raw);
                if (element instanceof P paragraph) {
                    paragraphs.add(paragraph);
                }
            }
            return paragraphs;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String deletedText(P paragraph) {
        return collect(paragraph, true);
    }

    private static String insertedText(P paragraph) {
        return collect(paragraph, false);
    }

    private static String collect(P paragraph, boolean deleted) {
        var text = new StringBuilder();
        for (var raw : paragraph.getContent()) {
            var element = org.docx4j.XmlUtils.unwrap(raw);
            if (element instanceof RunDel del && deleted) {
                for (var wrapped : del.getCustomXmlOrSmartTagOrSdt()) {
                    collectFrom(wrapped, text);
                }
            } else if (element instanceof RunIns ins && !deleted) {
                for (var wrapped : ins.getCustomXmlOrSmartTagOrSdt()) {
                    collectFrom(wrapped, text);
                }
            }
        }
        return text.toString();
    }

    private static void collectFrom(Object element, StringBuilder text) {
        var value = org.docx4j.XmlUtils.unwrap(element);
        if (value instanceof R run) {
            for (var child : run.getContent()) {
                var runChild = org.docx4j.XmlUtils.unwrap(child);
                if (runChild instanceof Text textElement) {
                    text.append(textElement.getValue());
                } else if (runChild instanceof DelText delText) {
                    text.append(delText.getValue());
                }
            }
        }
    }
}
