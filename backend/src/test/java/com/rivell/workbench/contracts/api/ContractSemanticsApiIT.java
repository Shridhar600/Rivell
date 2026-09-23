package com.rivell.workbench.contracts.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ContractSemanticsApiIT {

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

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIntelligenceConfiguration {

        @Bean
        @Primary
        ModelPolicy fakeModelPolicy() {
            return asset -> new ModelBinding("fake", "fake-model-latest");
        }

        @Bean
        @Primary
        SemanticDecisionProvider fakeSemanticDecisionProvider() {
            return new ContractEnrichmentIT.CueScriptedProvider();
        }

        @Bean
        @Primary
        GenerativeReasoningProvider fakeGenerativeProvider() {
            return new ContractEnrichmentIT.M5FakeGenerativeProvider();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void enrichExposeAndCorrectSemanticContractOverHttp() throws Exception {
        var contractId = uploadFixture("m5-msa-services.docx");

        MvcResult result = mockMvc.perform(post(
                        "/api/contracts/{id}/versions/1/enrichment", contractId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractType": "MSA", "ourSideRole": "CUSTOMER",
                                 "paperSource": "COUNTERPARTY_PAPER", "jurisdiction": "US"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrichmentStatus").value("READY"))
                .andExpect(jsonPath("$.data.reviewProfile.contractType").value("MSA"))
                .andExpect(jsonPath("$.data.metadata").isArray())
                .andExpect(jsonPath("$.data.parties").isArray())
                .andExpect(jsonPath("$.data.quality").isArray())
                .andReturn();

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString());
        var data = body.get("data");
        assertThat(data.get("metadata").size()).isGreaterThanOrEqualTo(4);
        assertThat(data.get("parties").size()).isGreaterThanOrEqualTo(2);

        var semantics = mockMvc.perform(get(
                        "/api/contracts/{id}/versions/1/semantics", contractId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrichmentStatus").value("READY"))
                .andExpect(jsonPath("$.data.pipelineVersion").value(
                        "rivell-contract-enrichment-1"))
                .andExpect(jsonPath("$.data.observations.regionCount").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var reloaded = new com.fasterxml.jackson.databind.ObjectMapper().readTree(semantics);
        UUID liabilityClauseId = null;
        for (var clause : reloaded.get("data").get("clauses")) {
            if (clause.has("primaryConcept")
                    && clause.get("primaryConcept").asText().equals("LIMITATION_OF_LIABILITY")) {
                liabilityClauseId = UUID.fromString(clause.get("id").asText());
            }
        }
        assertThat(liabilityClauseId).isNotNull();
        var partyId = UUID.fromString(reloaded.get("data").get("parties").get(0).get("id")
                .asText());

        mockMvc.perform(post(
                        "/api/contracts/{id}/versions/1/semantics/parties/{partyId}/role",
                        contractId, partyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roles": ["CUSTOMER"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parties[0].corrected").value(true))
                .andExpect(jsonPath("$.data.parties[0].effectiveRoles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.parties[0].machineRoles[0]").isNotEmpty());

        mockMvc.perform(post(
                        "/api/contracts/{id}/versions/1/semantics/clauses/{clauseId}/concepts",
                        contractId, liabilityClauseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"primary": "NOT_A_CONCEPT"}"""))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(
                        "/api/contracts/{id}/versions/1/semantics/clauses/{clauseId}/facts",
                        contractId, liabilityClauseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"values": {"liabilityCapMultiple": "2"}}"""))
                .andExpect(status().isOk());

        var after = mockMvc.perform(get(
                        "/api/contracts/{id}/versions/1/semantics", contractId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var corrected = new com.fasterxml.jackson.databind.ObjectMapper().readTree(after);
        assertThat(corrected.get("data").get("clauses").toString())
                .contains("correctedPrimaryConcept");

        mockMvc.perform(get("/api/contracts/" + UUID.randomUUID() + "/versions/1/semantics"))
                .andExpect(status().isNotFound());
    }

    @Test
    void foreignOrganizationCannotTouchSemanticState() throws Exception {
        var contractId = uploadFixture("m5-nda-counterparty.docx");

        mockMvc.perform(post("/api/contracts/" + contractId + "/versions/1/enrichment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractType": "NDA"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrichmentStatus").value("READY"));

        identityService.switchTo(UUID.randomUUID());

        mockMvc.perform(get("/api/contracts/{id}/versions/1/semantics", contractId))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/contracts/{id}/versions/1/enrichment", contractId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractType": "NDA"}"""))
                .andExpect(status().isNotFound());

        identityService.reset();
    }

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentIT.MutableIdentity identityService;

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        com.rivell.workbench.contracts.ContractEnrichmentIT.MutableIdentity mutableIdentityProvider() {
            return new ContractEnrichmentIT.MutableIdentity();
        }
    }

    private UUID uploadFixture(String name) throws Exception {
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures", name));
        var upload = new MockMultipartFile("file", name, DOCX_MEDIA_TYPE, bytes);
        MvcResult uploaded = mockMvc.perform(multipart("/api/contracts").file(upload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(uploaded.getResponse().getContentAsString());
        return UUID.fromString(response.get("data").get("contractId").asText());
    }

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
}
