package com.redstar.workbench.contracts.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("redstar")
            .withUsername("redstar")
            .withPassword("redstar");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("redstar.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void uploadsDocxAndReturnsSummary() throws Exception {
        var result = uploadFixture("fixtures/simple-contract.docx", "simple-contract.docx");

        mockMvc.perform(get("/api/contracts/{id}", result.contractId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(result.contractId().toString()))
                .andExpect(jsonPath("$.title").value("Mutual Non-Disclosure Agreement"))
                .andExpect(jsonPath("$.latestVersion.versionNumber").value(1))
                .andExpect(jsonPath("$.latestVersion.parseStatus").value("OK"))
                .andExpect(jsonPath("$.latestVersion.sha256").value(result.sha256()));
    }

    @Test
    void returnsCanonicalStructureForUploadedContract() throws Exception {
        var result = uploadFixture("fixtures/simple-contract.docx", "nda.docx");

        mockMvc.perform(get("/api/contracts/{id}/versions/{n}/structure",
                        result.contractId(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.parseStatus").value("OK"))
                .andExpect(jsonPath("$.parserVersion").value("redstar-docx-parser-1"))
                .andExpect(jsonPath("$.nodeCount").value(7))
                .andExpect(jsonPath("$.nodes[0].nodeType").value("BODY"))
                .andExpect(jsonPath("$.nodes[1].nodeType").value("TITLE"))
                .andExpect(jsonPath("$.nodes[1].text").value("Mutual Non-Disclosure Agreement"))
                .andExpect(jsonPath("$.nodes[3].nodeType").value("HEADING"))
                .andExpect(jsonPath("$.nodes[3].headingLevel").value(1))
                .andExpect(jsonPath("$.nodes[1].sourceSpan.bodyIndex").value(0));
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
    void downloadsExactOriginalBytes() throws Exception {
        byte[] original;
        try (var stream = getClass().getClassLoader().getResourceAsStream("fixtures/simple-contract.docx")) {
            assertThat(stream).isNotNull();
            original = stream.readAllBytes();
        }
        var result = uploadFixture("fixtures/simple-contract.docx", "original.docx");

        MvcResult download = mockMvc.perform(get(
                        "/api/contracts/{id}/versions/{n}/original", result.contractId(), 1))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andReturn();

        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(original);
    }

    @Test
    void rejectsNonDocxUpload() throws Exception {
        var file = new MockMultipartFile(
                "file", "contract.pdf", "application/pdf", "not a docx".getBytes());

        mockMvc.perform(multipart("/api/contracts").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForUnknownContract() throws Exception {
        mockMvc.perform(get("/api/contracts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/contracts/{id}/versions/{n}/structure", UUID.randomUUID(), 1))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/contracts/{id}/versions/{n}/original", UUID.randomUUID(), 1))
                .andExpect(status().isNotFound());
    }

    private UploadResult uploadFixture(String fixture, String filename) throws Exception {
        byte[] bytes;
        try (var stream = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(stream).as("classpath resource %s", fixture).isNotNull();
            bytes = stream.readAllBytes();
        }
        var file = new MockMultipartFile(
                "file", filename,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                bytes);

        MvcResult result = mockMvc.perform(multipart("/api/contracts").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractId").isString())
                .andExpect(jsonPath("$.parseStatus").value("OK"))
                .andReturn();

        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new UploadResult(
                UUID.fromString(json.get("contractId").asText()),
                json.get("sha256").asText());
    }

    private record UploadResult(UUID contractId, String sha256) {
    }
}
