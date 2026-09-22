package com.rivell.workbench.contracts.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ContractApiIT {

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

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
        // tiny limit proves 413 behavior without large payloads (fixtures are < 3KB)
        registry.add("rivell.contracts.upload.max-file-size", () -> "4KB");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void uploadsDocxAndReturnsSummaryWithParseOutcome() throws Exception {
        var result = uploadFixture("fixtures/simple-contract.docx", "simple-contract.docx");

        mockMvc.perform(get("/api/contracts/{id}", result.contractId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(result.contractId().toString()))
                .andExpect(jsonPath("$.title").value("Mutual Non-Disclosure Agreement"))
                .andExpect(jsonPath("$.latestVersion.versionNumber").value(1))
                .andExpect(jsonPath("$.latestVersion.parseStatus").value("OK"))
                .andExpect(jsonPath("$.latestVersion.parseWarnings").isEmpty())
                .andExpect(jsonPath("$.latestVersion.filename").value("simple-contract.docx"))
                .andExpect(jsonPath("$.latestVersion.sha256").value(result.sha256()))
                .andExpect(jsonPath("$.latestVersion.nodeCount").value(7));
    }

    @Test
    void returnsCanonicalStructureForUploadedContract() throws Exception {
        var result = uploadFixture("fixtures/simple-contract.docx", "nda.docx");

        mockMvc.perform(get("/api/contracts/{id}/versions/{n}/structure",
                        result.contractId(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.parseStatus").value("OK"))
                .andExpect(jsonPath("$.parseWarnings").isEmpty())
                .andExpect(jsonPath("$.nodeCount").value(7))
                .andExpect(jsonPath("$.nodes[0].nodeType").value("BODY"))
                .andExpect(jsonPath("$.nodes[1].nodeType").value("TITLE"))
                .andExpect(jsonPath("$.nodes[1].text").value("Mutual Non-Disclosure Agreement"))
                .andExpect(jsonPath("$.nodes[3].nodeType").value("HEADING"))
                .andExpect(jsonPath("$.nodes[3].headingLevel").value(1))
                .andExpect(jsonPath("$.nodes[1].sourceSpan.bodyIndex").value(0));
    }

    @Test
    void returnsInterleavedStructureInPhysicalDocumentOrderAfterReload() throws Exception {
        var result = uploadFixture("fixtures/interleaved-blocks.docx", "interleaved.docx");

        MvcResult structure = mockMvc.perform(get(
                        "/api/contracts/{id}/versions/{n}/structure", result.contractId(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parseStatus").value("OK"))
                .andExpect(jsonPath("$.parseWarnings[0]").value("UNSUPPORTED_NESTED_TABLE"))
                .andReturn();

        var nodes = objectMapper.readTree(structure.getResponse().getContentAsString())
                .get("nodes");
        // BODY is nodes[0]; its children follow in returned order
        var bodyId = nodes.get(0).get("id").asText();
        var bodyChildren = new java.util.ArrayList<String>();
        for (var node : nodes) {
            if (node.get("parentId") != null && node.get("parentId").asText().equals(bodyId)) {
                var text = node.get("text") == null || node.get("text").isNull()
                        ? ""
                        : node.get("text").asText();
                bodyChildren.add(node.get("nodeType").asText() + ":" + text);
            }
        }
        assertThat(bodyChildren).containsExactly(
                "HEADING:Interleaved Structure",
                "PARAGRAPH:First paragraph before any table.",
                "TABLE:",
                "PARAGRAPH:Paragraph between the two tables.",
                "TABLE:",
                "PARAGRAPH:Last paragraph after all tables.");
    }

    @Test
    void returnsTableStructureForTableFixture() throws Exception {
        var result = uploadFixture("fixtures/with-table.docx", "msa.docx");

        mockMvc.perform(get("/api/contracts/{id}/versions/{n}/structure",
                        result.contractId(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.nodeType == 'TABLE')]").exists())
                .andExpect(jsonPath("$.nodes[?(@.nodeType == 'TABLE_ROW')]").exists())
                .andExpect(jsonPath("$.nodes[?(@.nodeType == 'TABLE_CELL')]").exists());
    }

    @Test
    void exposesFilenameAndWarningsForLimitedParses() throws Exception {
        var result = uploadFixture("fixtures/with-omitted-parts.docx", "omitted-parts.docx");

        assertThat(result.parseStatus()).isEqualTo("OK");
        assertThat(result.parseWarnings()).contains(
                "HEADERS_OR_FOOTERS_NOT_REPRESENTED",
                "FOOTNOTES_NOT_REPRESENTED");

        mockMvc.perform(get("/api/contracts/{id}/versions/{n}/structure",
                        result.contractId(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parseStatus").value("OK"))
                .andExpect(jsonPath("$.parseWarnings.length()").value(2));
    }

    @Test
    void reportsFailedParseThroughPublicContract() throws Exception {
        var result = uploadFixture("fixtures/invalid-bytes.docx", "broken.docx");

        assertThat(result.parseStatus()).isEqualTo("FAILED");
        assertThat(result.parseWarnings()).isNotEmpty();

        mockMvc.perform(get("/api/contracts/{id}/versions/{n}/structure",
                        result.contractId(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parseStatus").value("FAILED"))
                .andExpect(jsonPath("$.parseWarnings.length()").value(1));
    }

    @Test
    void downloadsExactOriginalBytesWithAttachmentDisposition() throws Exception {
        byte[] original;
        try (var stream = getClass().getClassLoader().getResourceAsStream("fixtures/simple-contract.docx")) {
            assertThat(stream).isNotNull();
            original = stream.readAllBytes();
        }
        var result = uploadFixture("fixtures/simple-contract.docx", "original-with-path.docx");

        MvcResult download = mockMvc.perform(get(
                        "/api/contracts/{id}/versions/{n}/original", result.contractId(), 1))
                .andExpect(status().isOk())
                .andExpect(content().contentType(DOCX_MEDIA_TYPE))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("original-with-path.docx")))
                .andReturn();

        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(original);
    }

    @Test
    void rejectsUploadsBeyondConfiguredLimitWith413() throws Exception {
        var file = new MockMultipartFile(
                "file", "large.docx", DOCX_MEDIA_TYPE, new byte[8192]);

        mockMvc.perform(multipart("/api/contracts").file(file))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("413"))
                .andExpect(jsonPath("$.title").value("Payload too large"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void rejectsNonDocxUpload() throws Exception {
        var file = new MockMultipartFile(
                "file", "contract.pdf", "application/pdf", "not a docx".getBytes());

        mockMvc.perform(multipart("/api/contracts").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void returnsNotFoundForUnknownContract() throws Exception {
        mockMvc.perform(get("/api/contracts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));

        mockMvc.perform(get("/api/contracts/{id}/versions/{n}/structure", UUID.randomUUID(), 1))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/contracts/{id}/versions/{n}/original", UUID.randomUUID(), 1))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossOrganizationAccessReturnsNotFound() throws Exception {
        var result = uploadFixture("fixtures/simple-contract.docx", "isolation.docx");

        mockMvc.perform(get("/api/contracts/{id}", result.contractId()))
                .andExpect(status().isOk());

        // Simulate another organization's contract in the database
        var otherOrgContract = UUID.randomUUID();
        jdbcClient.sql("""
                insert into contract (id, organization_id, title, created_at)
                values (:id, :orgId, null, :createdAt)
                """)
                .param("id", otherOrgContract)
                .param("orgId", UUID.randomUUID())
                .param("createdAt", java.time.OffsetDateTime.now())
                .update();

        mockMvc.perform(get("/api/contracts/{id}", otherOrgContract))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/contracts/{id}/versions/{n}/structure", otherOrgContract, 1))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/contracts/{id}/versions/{n}/original", otherOrgContract, 1))
                .andExpect(status().isNotFound());
    }

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    private UploadResult uploadFixture(String fixture, String filename) throws Exception {
        byte[] bytes;
        try (var stream = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(stream).as("classpath resource %s", fixture).isNotNull();
            bytes = stream.readAllBytes();
        }
        var file = new MockMultipartFile("file", filename, DOCX_MEDIA_TYPE, bytes);

        MvcResult result = mockMvc.perform(multipart("/api/contracts").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractId").isString())
                .andReturn();

        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        var parseStatus = json.has("parseStatus") ? json.get("parseStatus").asText() : "OK";
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (json.has("parseWarnings") && json.get("parseWarnings").isArray()) {
            json.get("parseWarnings").forEach(w -> warnings.add(w.asText()));
        }
        return new UploadResult(
                UUID.fromString(json.get("contractId").asText()),
                json.get("sha256").asText(),
                parseStatus,
                java.util.Collections.unmodifiableList(warnings));
    }

    private record UploadResult(
            UUID contractId, String sha256, String parseStatus, java.util.List<String> parseWarnings) {
    }
}
