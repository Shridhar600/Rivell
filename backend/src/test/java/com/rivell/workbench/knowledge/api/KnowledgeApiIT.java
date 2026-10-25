package com.rivell.workbench.knowledge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.knowledge.ArtifactType;
import com.rivell.workbench.knowledge.SemanticCompilationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

@SpringBootTest(properties = {
        "rivell.document-work.max-concurrent=1",
        "rivell.document-work.max-wait=50ms"})
@AutoConfigureMockMvc
@Testcontainers
class KnowledgeApiIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

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
        // tiny limit proves 413 behavior without large payloads (fixtures are < 3KB)
        registry.add("rivell.knowledge.upload.max-file-size", () -> "4KB");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        IdentityProvider mutableIdentityProvider() {
            return new MutableIdentityProvider();
        }
    }

    static final class MutableIdentityProvider implements IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Set.of("ADMIN"));

        void use(UserIdentity identity) {
            current = identity;
        }

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    @Autowired
    com.rivell.workbench.foundation.DocumentWorkLimiter documentWorkLimiter;

    @Autowired
    MutableIdentityProvider identityProvider;

    @MockitoSpyBean
    SemanticCompilationService semanticCompilationService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void uploadsDocxWithManifestAndReturnsArtifactDetail() throws Exception {
        var artifact = upload("knowledge-playbook.docx", ArtifactType.PLAYBOOK,
                "MSA_PACK", "Customer", "Third-Party", "US", null);

        assertThat(artifact.get("ingestStatus").asText()).isEqualTo("EXTRACTED");
        assertThat(artifact.get("artifactType").asText()).isEqualTo("PLAYBOOK");
        assertThat(artifact.get("contractPackKey").asText()).isEqualTo("MSA_PACK");
        assertThat(artifact.get("partyPerspective").asText()).isEqualTo("Customer");
        assertThat(artifact.get("jurisdiction").asText()).isEqualTo("US");
        assertThat(artifact.get("sha256").asText()).hasSize(64);

        mockMvc.perform(get("/api/knowledge/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].artifactType").value("PLAYBOOK"));

        mockMvc.perform(get("/api/knowledge/artifacts/{id}", artifact.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filename").value("knowledge-playbook.docx"));
    }

    @Test
    void listsApplicablePublishedPlaybooksByContractTypeAndScope() throws Exception {
        var msa = upload("knowledge-playbook.docx", ArtifactType.PLAYBOOK,
                "MSA_PACK", "Customer", "Third-Party", "US", "MSA");
        var msaPublication = publishFirstCandidate(msa.get("id").asText());
        var nda = upload("knowledge-playbook.docx", ArtifactType.PLAYBOOK,
                null, null, null, null, "NDA");
        var ndaPublication = publishFirstCandidate(nda.get("id").asText());
        var all = upload("knowledge-playbook.docx", ArtifactType.PLAYBOOK,
                null, null, null, null, null);
        var allPublication = publishFirstCandidate(all.get("id").asText());

        var mutual = mockMvc.perform(get("/api/knowledge/applicable")
                        .param("contractType", "MNDA"))
                .andExpect(status().isOk())
                .andReturn();
        var mutualData = objectMapper.readTree(mutual.getResponse().getContentAsString()).get("data");
        var mutualIds = new ArrayList<String>();
        mutualData.forEach(node -> mutualIds.add(node.get("artifactId").asText()));
        assertThat(mutualIds).containsExactlyInAnyOrder(
                nda.get("id").asText(), all.get("id").asText());
        var mutualNda = mutualData.get(0).get("artifactId").asText().equals(nda.get("id").asText())
                ? mutualData.get(0) : mutualData.get(1);
        assertThat(mutualNda.get("artifactType").asText()).isEqualTo("PLAYBOOK");
        assertThat(mutualNda.get("name").asText()).isEqualTo("Negotiation Playbook 2026");
        assertThat(mutualNda.get("publicationNumber").asLong()).isEqualTo(ndaPublication);

        var msaResult = mockMvc.perform(get("/api/knowledge/applicable")
                        .param("contractType", "MSA"))
                .andExpect(status().isOk())
                .andReturn();
        var msaData = objectMapper.readTree(msaResult.getResponse().getContentAsString()).get("data");
        var msaIds = new ArrayList<String>();
        msaData.forEach(node -> msaIds.add(node.get("artifactId").asText()));
        assertThat(msaIds).containsExactlyInAnyOrder(
                msa.get("id").asText(), all.get("id").asText());
        var msaVersions = new ArrayList<Long>();
        msaData.forEach(node -> msaVersions.add(node.get("publicationNumber").asLong()));
        assertThat(msaVersions).containsExactlyInAnyOrder(msaPublication, allPublication);

        // A declared jurisdiction narrows the set; the file that declared none stays a wildcard.
        var uk = mockMvc.perform(get("/api/knowledge/applicable")
                        .param("contractType", "MSA")
                        .param("jurisdiction", "UK"))
                .andExpect(status().isOk())
                .andReturn();
        var ukData = objectMapper.readTree(uk.getResponse().getContentAsString()).get("data");
        assertThat(ukData.size()).isEqualTo(1);
        assertThat(ukData.get(0).get("artifactId").asText()).isEqualTo(all.get("id").asText());
    }

    @Test
    void applicableKnowledgeDefaultsToTodayForEffectiveWindows() throws Exception {
        var expired = uploadWithPeriod("knowledge-playbook.docx", ArtifactType.PLAYBOOK,
                null, null, null, null, "MSA",
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31));
        publishFirstCandidate(expired.get("id").asText());

        mockMvc.perform(get("/api/knowledge/applicable").param("contractType", "MSA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(get("/api/knowledge/applicable")
                        .param("contractType", "MSA")
                        .param("effectiveOn", "2020-06-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].artifactId").value(expired.get("id").asText()));
    }

    @Test
    void applicableKnowledgeLeavesDraftsOutAndRequiresAReadPermission() throws Exception {
        var draft = upload("knowledge-playbook.docx", ArtifactType.PLAYBOOK,
                null, null, null, null, "MSA");

        mockMvc.perform(get("/api/knowledge/applicable").param("contractType", "MSA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of()));
        mockMvc.perform(get("/api/knowledge/applicable")
                        .param("contractType", "MSA"))
                .andExpect(status().isForbidden());
        assertThat(draft.get("ingestStatus").asText()).isEqualTo("EXTRACTED");
    }

    @Test
    void exposesStructureUnitsAndCandidatesForTheFullReviewFlow() throws Exception {
        var artifact = upload("knowledge-playbook.docx", ArtifactType.PLAYBOOK,
                "MSA_PACK", "Customer", "Third-Party", "US", null);
        var artifactId = artifact.get("id").asText();

        mockMvc.perform(get("/api/knowledge/artifacts/{id}/structure", artifactId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.nodeType == 'TABLE')]").exists())
                .andExpect(jsonPath("$.data[?(@.nodeType == 'TABLE_CELL')]").exists());

        mockMvc.perform(get("/api/knowledge/artifacts/{id}/units", artifactId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.kind == 'HEADING_SECTION')]").exists())
                .andExpect(jsonPath("$.data[?(@.kind == 'TABLE')]").exists());

        MvcResult candidates = mockMvc.perform(
                        get("/api/knowledge/artifacts/{id}/candidates", artifactId))
                .andExpect(status().isOk())
                .andReturn();
        var candidateArray = objectMapper.readTree(
                candidates.getResponse().getContentAsString()).get("data");
        assertThat(candidateArray.size()).isGreaterThanOrEqualTo(3);

        var ruleCandidates = new ArrayList<JsonNode>();
        candidateArray.forEach(node -> {
            if ("PLAYBOOK_RULE".equals(node.get("candidateType").asText())
                    && "Limitation of Liability".equals(node.get("rawLabel").asText())) {
                ruleCandidates.add(node);
            }
        });
        var sectionRule = ruleCandidates.get(0);
        var candidateId = sectionRule.get("id").asText();

        mockMvc.perform(get("/api/knowledge/candidates/{id}", candidateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.contractPackKey").value("MSA_PACK"));

        mockMvc.perform(post("/api/knowledge/candidates/{id}/approve", candidateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"policy verified\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewNote").value("policy verified"));

        var publication = mockMvc.perform(post("/api/knowledge/publications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"candidateIds":["%s"],"note":"initial publication"}
                                """.formatted(candidateId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicationNumber").value(1))
                .andExpect(jsonPath("$.data.items[0].action").value("ADD"))
                .andReturn().getResponse().getContentAsString();
        var publicationId = objectMapper.readTree(publication).get("data").get("id").asText();

        mockMvc.perform(get("/api/knowledge/publications/{id}", publicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].candidateId").value(candidateId));

        // a published candidate is immutable through the API
        mockMvc.perform(post("/api/knowledge/candidates/{id}/reject", candidateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/knowledge/publications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateIds\":[\"%s\"]}".formatted(candidateId)))
                .andExpect(status().isConflict());
    }

    @Test
    void spreadsheetUploadPreservesMergedRegionsAndRowCandidates() throws Exception {
        var artifact = upload("knowledge-matrix.xlsx", ArtifactType.PLAYBOOK,
                "MSA_PACK", "Customer", "Third-Party", "US", null);
        var artifactId = artifact.get("id").asText();

        mockMvc.perform(get("/api/knowledge/artifacts/{id}/structure", artifactId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.nodeType == 'WORKBOOK')]").exists())
                .andExpect(jsonPath("$.data[?(@.sheetName == 'Positions')]").exists())
                .andExpect(jsonPath(
                        "$.data[?(@.nodeType == 'SPREADSHEET_CELL' && @.mergedRegion)].columnIndex")
                        .exists());
        mockMvc.perform(get("/api/knowledge/artifacts/{id}/units", artifactId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.kind == 'SPREADSHEET_ROW')]").exists());
    }

    @Test
    void downloadsOriginalWithExactBytesAndDisposition() throws Exception {
        byte[] original;
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/knowledge-matrix.xlsx")) {
            assertThat(stream).isNotNull();
            original = stream.readAllBytes();
        }
        var file = new MockMultipartFile("file", "matrix-download.xlsx",
                XLSX_MEDIA_TYPE, original);
        var artifact = mockMvc.perform(multipart("/api/knowledge/artifacts")
                        .file(file)
                        .param("artifactType", "PLAYBOOK"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var artifactId = objectMapper.readTree(artifact).get("data").get("id").asText();

        MvcResult download = mockMvc.perform(
                        get("/api/knowledge/artifacts/{id}/original", artifactId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX_MEDIA_TYPE))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("matrix-download.xlsx")))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(original);
    }

    @Test
    void rejectsUnsupportedAndOversizedSources() throws Exception {
        var pdf = new MockMultipartFile("file", "notes.pdf",
                "application/pdf", "not supported".getBytes());
        mockMvc.perform(multipart("/api/knowledge/artifacts")
                        .file(pdf).param("artifactType", "PLAYBOOK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        var broken = new MockMultipartFile("file", "broken.xlsx",
                XLSX_MEDIA_TYPE, "not a workbook".getBytes());
        mockMvc.perform(multipart("/api/knowledge/artifacts")
                        .file(broken).param("artifactType", "PLAYBOOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingestStatus").value("FAILED"));

        var oversize = new MockMultipartFile("file", "big.xlsx",
                XLSX_MEDIA_TYPE, new byte[8192]);
        mockMvc.perform(multipart("/api/knowledge/artifacts")
                        .file(oversize).param("artifactType", "PLAYBOOK"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("413"));
    }

    @Test
    void crossOrganizationKnowledgeIsInvisible() throws Exception {
        var artifact = upload("clause-library.docx", ArtifactType.CLAUSE_LIBRARY,
                null, null, null, null, null);
        var artifactId = artifact.get("id").asText();
        var candidates = mockMvc.perform(
                        get("/api/knowledge/artifacts/{id}/candidates", artifactId))
                .andReturn().getResponse().getContentAsString();
        var candidateId = objectMapper.readTree(candidates).get("data").get(0).get("id").asText();

        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));

        mockMvc.perform(get("/api/knowledge/artifacts/{id}", artifactId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/knowledge/artifacts/{id}/candidates", artifactId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/knowledge/candidates/{id}", candidateId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/knowledge/artifacts/{id}/original", artifactId))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/knowledge/candidates/{id}/approve", candidateId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsWorkbookTextTheExtractedStructureDoesNotHold() throws Exception {
        var artifact = upload("coverage-workbook.xlsx", ArtifactType.PLAYBOOK,
                null, null, null, null, null);

        mockMvc.perform(get("/api/knowledge/artifacts/{id}/coverage", artifact.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checked").value(true))
                .andExpect(jsonPath("$.data.extraWords").value(0))
                .andExpect(jsonPath("$.data.gaps.length()").value(2))
                .andExpect(jsonPath("$.data.gaps[0].location").value("COMMENT"))
                .andExpect(jsonPath("$.data.gaps[1].excerpt")
                        .value("Escalate every walk-away to the General Counsel."));
    }

    private long publishFirstCandidate(String artifactId) throws Exception {
        var candidates = mockMvc.perform(
                        get("/api/knowledge/artifacts/{id}/candidates", artifactId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var candidateId = objectMapper.readTree(candidates).get("data").get(0).get("id").asText();
        mockMvc.perform(post("/api/knowledge/candidates/{id}/approve", candidateId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        var publication = mockMvc.perform(post("/api/knowledge/publications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateIds\":[\"%s\"]}".formatted(candidateId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(publication).get("data").get("publicationNumber").asLong();
    }

    @Test
    void clearingCounselApprovalToNotSpecifiedSticksAfterReload() throws Exception {
        var artifact = upload("knowledge-playbook.docx", ArtifactType.PLAYBOOK,
                "MSA_PACK", "Customer", "Third-Party", "US", null);
        var candidates = mockMvc.perform(get("/api/knowledge/artifacts/{id}/candidates",
                        artifact.get("id").asText()))
                .andExpect(status().isOk())
                .andReturn();
        var candidateArray = objectMapper.readTree(
                candidates.getResponse().getContentAsString()).get("data");
        var rule = java.util.stream.StreamSupport.stream(candidateArray.spliterator(), false)
                .filter(node -> "PLAYBOOK_RULE".equals(node.get("candidateType").asText()))
                .findFirst().orElseThrow();
        var candidateId = rule.get("id").asText();

        mockMvc.perform(post("/api/knowledge/candidates/{id}/edit", candidateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semanticFields":{"requiresApproval":true}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresApproval").value(true));

        // A correction that does not touch semantics must not clear the answer.
        mockMvc.perform(post("/api/knowledge/candidates/{id}/edit", candidateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentText":"Corrected wording only."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresApproval").value(true));

        mockMvc.perform(post("/api/knowledge/candidates/{id}/edit", candidateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semanticFields":{"requiresApproval":null}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresApproval").doesNotExist());

        var reloaded = mockMvc.perform(get("/api/knowledge/candidates/{id}", candidateId))
                .andExpect(status().isOk())
                .andReturn();
        var stored = objectMapper.readTree(reloaded.getResponse().getContentAsString()).get("data");
        assertThat(stored.path("requiresApproval").isNull())
                .as("a cleared approval is not specified, not the machine answer")
                .isTrue();
    }

    @Test
    void aBusyDocumentLimitRefusesTheUploadBeforeAnythingIsStored() throws Exception {
        var slotTaken = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = CompletableFuture.runAsync(() -> documentWorkLimiter.run(() -> {
            slotTaken.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(slotTaken.await(5, TimeUnit.SECONDS)).isTrue();

        var artifactsBefore = jdbcClient.sql("select count(*) from knowledge_artifact")
                .query(Long.class).single();
        byte[] bytes;
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/knowledge-playbook.docx")) {
            bytes = stream.readAllBytes();
        }
        try {
            mockMvc.perform(multipart("/api/knowledge/artifacts")
                            .file(new MockMultipartFile("file", "knowledge-playbook.docx",
                                    "application/vnd.openxmlformats-officedocument."
                                            + "wordprocessingml.document", bytes))
                            .param("artifactType", "PLAYBOOK"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.title").value("Service busy"));
        } finally {
            release.countDown();
        }
        holder.get(5, TimeUnit.SECONDS);

        assertThat(jdbcClient.sql("select count(*) from knowledge_artifact")
                .query(Long.class).single())
                .as("a refused upload creates no source").isEqualTo(artifactsBefore);
    }

    @Test
    void theModelCompileRunsOutsideTheDocumentSlot() throws Exception {
        // The slot bounds files held in memory while they are parsed. The model compile that
        // follows can take minutes; holding the slot through it would refuse every other upload
        // and redline for that time.
        var otherWorkDuringCompile = new ArrayList<String>();
        doAnswer(invocation -> {
            otherWorkDuringCompile.add(CompletableFuture
                    .supplyAsync(() -> documentWorkLimiter.run(() -> "ran"))
                    .handle((result, failure) -> failure == null ? result : "refused")
                    .get(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(semanticCompilationService).compile(any(), any(), any(), any());

        upload("knowledge-playbook.docx", ArtifactType.PLAYBOOK, null, null, null, null, null);

        assertThat(otherWorkDuringCompile)
                .as("another document job runs while the model compile is in progress")
                .containsExactly("ran");
    }

    private JsonNode upload(
            String fixture, ArtifactType artifactType, String contractPackKey,
            String partyPerspective, String paperSource, String jurisdiction,
            String declaredContractTypes) throws Exception {
        return uploadWithPeriod(fixture, artifactType, contractPackKey, partyPerspective,
                paperSource, jurisdiction, declaredContractTypes, null, null);
    }

    private JsonNode uploadWithPeriod(
            String fixture, ArtifactType artifactType, String contractPackKey,
            String partyPerspective, String paperSource, String jurisdiction,
            String declaredContractTypes, LocalDate effectiveFrom, LocalDate effectiveTo)
            throws Exception {
        byte[] bytes;
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/" + fixture)) {
            assertThat(stream).as("classpath resource %s", fixture).isNotNull();
            bytes = stream.readAllBytes();
        }
        var request = multipart("/api/knowledge/artifacts")
                .file(new MockMultipartFile("file", fixture, guessMediaType(fixture), bytes))
                .param("artifactType", artifactType.name());
        if (contractPackKey != null) {
            request = request.param("contractPackKey", contractPackKey)
                    .param("partyPerspective", partyPerspective)
                    .param("paperSource", paperSource)
                    .param("jurisdiction", jurisdiction);
        }
        if (declaredContractTypes != null) {
            request = request.param("declaredContractTypes", declaredContractTypes);
        }
        if (effectiveFrom != null) {
            request = request.param("effectiveFrom", effectiveFrom.toString());
        }
        if (effectiveTo != null) {
            request = request.param("effectiveTo", effectiveTo.toString());
        }

        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private static String guessMediaType(String fixture) {
        return fixture.endsWith(".xlsx") ? XLSX_MEDIA_TYPE : DOCX_MEDIA_TYPE;
    }
}
