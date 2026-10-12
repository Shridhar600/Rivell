package com.rivell.workbench.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SystemApiIT {

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
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void exposesSystemStatusSeparatelyFromDependencyHealth() throws Exception {
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.service").value("rivell-backend"))
                .andExpect(jsonPath("$.data.status").value("ready"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void malformedJsonBodiesAreBadRequestsNotStorageErrors() throws Exception {
        mockMvc.perform(post("/api/knowledge/publications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateIds\": [\"not-a-uuid]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void generatesTheStableFrontendOpenApiContract() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        var openApiJson = result.getResponse().getContentAsByteArray();
        var openApi = objectMapper.readTree(openApiJson);

        assertThat(Set.copyOf(openApi.path("paths").properties().stream()
                .map(entry -> entry.getKey()).toList()))
                .containsExactlyInAnyOrder(
                        "/api/system/status",
                        "/api/contracts",
                        "/api/contracts/{id}",
                        "/api/contracts/{id}/versions/{versionNumber}/structure",
                        "/api/contracts/{id}/versions/{versionNumber}/coverage",
                        "/api/contracts/{id}/versions/{versionNumber}/original",
                        "/api/contracts/{id}/versions/{versionNumber}/enrichment",
                        "/api/contracts/{id}/versions/{versionNumber}/semantics",
                        "/api/contracts/{id}/versions/{versionNumber}/semantics/parties/{partyId}/role",
                        "/api/contracts/{id}/versions/{versionNumber}/semantics/clauses/{clauseId}/concepts",
                        "/api/contracts/{id}/versions/{versionNumber}/semantics/clauses/{clauseId}/facts",
                        "/api/contracts/{contractId}/versions/{versionNumber}/reviews",
                        "/api/reviews",
                        "/api/reviews/packs",
                        "/api/reviews/{id}",
                        "/api/reviews/{id}/findings",
                        "/api/reviews/{id}/findings/{findingId}",
                        "/api/reviews/{id}/clause-marks",
                        "/api/reviews/{id}/findings/{findingId}/decision",
                        "/api/reviews/{id}/findings/{findingId}/decisions",
                        "/api/reviews/{id}/decisions",
                        "/api/reviews/{id}/human-review",
                        "/api/reviews/{id}/diagnostics",
                        "/api/reviews/{id}/finalize",
                        "/api/reviews/{id}/approved-change-set",
                        "/api/reviews/{id}/redline",
                        "/api/reviews/{id}/redline/retry",
                        "/api/reviews/{id}/redline/download",
                        "/api/reviews/{id}/progress",
                        "/api/knowledge/artifacts",
                        "/api/knowledge/artifacts/{id}",
                        "/api/knowledge/artifacts/{id}/structure",
                        "/api/knowledge/artifacts/{id}/coverage",
                        "/api/knowledge/artifacts/{id}/units",
                        "/api/knowledge/artifacts/{id}/candidates",
                        "/api/knowledge/artifacts/{id}/original",
                        "/api/knowledge/artifacts/{artifactId}/units/{unitId}/classification",
                        "/api/knowledge/artifacts/{artifactId}/units/{unitId}/position",
                        "/api/knowledge/candidates/bulk-approve",
                        "/api/knowledge/candidates/{id}",
                        "/api/knowledge/candidates/{id}/concept",
                        "/api/knowledge/candidates/{id}/approve",
                        "/api/knowledge/candidates/{id}/edit",
                        "/api/knowledge/candidates/{id}/reject",
                        "/api/knowledge/publications",
                        "/api/knowledge/publications/{id}",
                        "/api/retrieval/evidence",
                        "/api/retrieval/traces/{id}",
                        "/api/taxonomy/concepts");
        assertThat(openApi.at("/paths/~1api~1system~1status/get/responses/200/content/application~1json/schema/$ref")
                .asText()).endsWith("SystemStatusResponse");

        var output = Path.of("target/openapi/openapi.json");
        Files.createDirectories(output.getParent());
        Files.write(output, openApiJson);
        assertThat(output).isNotEmptyFile();
    }
}
