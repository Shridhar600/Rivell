package com.rivell.workbench.knowledge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.knowledge.ArtifactType;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Reviewer finding 1: a structured correction whose values are all blank must not be able to make
 * a candidate's effective wording blank or null while valid machine wording exists.
 *
 * <p>Two failure modes are covered, at the API boundary a reviewer actually uses:
 * <ul>
 *   <li>the correction is accepted and silently dropped, so the reviewer believes the row was
 *       cleared while the machine wording stays published;</li>
 *   <li>published knowledge ends up with blank wording, which is invisible and cannot be indexed:
 *       {@code EvidenceIndexService} hashes {@code effectiveContentText()} before writing the index
 *       row, so a null wording throws inside the batch, is logged as a warning, and the candidate
 *       silently never becomes retrievable.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class KnowledgeBlankCorrectionApiIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MACHINE_ROW = "Liability cap | 1x annual fees | 2x annual fees";

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
    JdbcClient jdbc;

    @Autowired
    MutableIdentityProvider identityProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void anAllBlankColumnCorrectionIsRefusedAndKeepsTheMachineWording() throws Exception {
        var artifactId = uploadMatrix();
        var candidate = liabilityCapRow(artifactId);
        assertThat(candidate.get("contentText").asText()).isEqualTo(MACHINE_ROW);

        var blankColumns = new LinkedHashMap<String, String>();
        candidate.get("payloadColumns").fields().forEachRemaining(
                entry -> blankColumns.put(entry.getKey(), "  "));
        assertThat(blankColumns).as("the fixture row has columns to blank").isNotEmpty();

        mockMvc.perform(post("/api/knowledge/candidates/{id}/edit", candidate.get("id").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(java.util.Map.of(
                        "contentText", "",
                        "payloadColumns", blankColumns,
                        "note", "clear the whole row"))))
                .andExpect(status().isBadRequest());

        var afterRefusal = getCandidate(candidate.get("id").asText());
        assertThat(afterRefusal.get("reviewStatus").asText())
                .as("a refused correction must not consume the candidate's pending state")
                .isEqualTo("PENDING");
        assertThat(afterRefusal.get("editedContentText").isNull())
                .as("a refused correction must not record human wording")
                .isTrue();
        assertThat(afterRefusal.get("editedPayloadColumns").isEmpty())
                .as("a refused correction must not record a structured correction")
                .isTrue();
        assertThat(afterRefusal.get("contentText").asText())
                .as("the machine wording stays readable")
                .isEqualTo(MACHINE_ROW);
    }

    /**
     * The consequence that makes this more than a cosmetic rule. A PRECEDENT is an indexable
     * candidate type, and indexing hashes {@code effectiveContentText()} before writing the index
     * row; blank wording throws inside the batch, is swallowed as a warning, and the published
     * candidate silently never becomes retrievable. This proves the refused correction cannot
     * produce that state.
     */
    @Test
    void publishedKnowledgeStaysRetrievableAfterARefusedBlankCorrection() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var artifactId = upload("m4-executed-precedent-style.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ArtifactType.EXECUTED_PRECEDENT);
        var candidate = firstCandidate(artifactId);
        var candidateId = candidate.get("id").asText();

        mockMvc.perform(post("/api/knowledge/candidates/{id}/edit", candidateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(java.util.Map.of(
                        "payloadColumns", java.util.Map.of("Obligation", "   "),
                        "note", "blank the only column"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/knowledge/candidates/{id}/approve", candidateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/knowledge/publications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(java.util.Map.of(
                        "candidateIds", java.util.List.of(candidateId)))))
                .andExpect(status().isOk());

        assertThat(getCandidate(candidateId).get("reviewStatus").asText())
                .isEqualTo("PUBLISHED");

        var indexRows = jdbc.sql("""
                select count(*) from retrieval_evidence_index
                where organization_id = :organizationId
                  and candidate_id = :candidateId
                """)
                .param("organizationId", organizationId)
                .param("candidateId", UUID.fromString(candidateId))
                .query(Long.class)
                .single();
        assertThat(indexRows)
                .as("a candidate with blank wording would never reach the retrieval index")
                .isPositive();
    }

    private UUID upload(String fixture, String mediaType, ArtifactType artifactType)
            throws Exception {
        byte[] bytes;
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/" + fixture)) {
            assertThat(stream).as("fixture %s", fixture).isNotNull();
            bytes = stream.readAllBytes();
        }
        MvcResult result = mockMvc.perform(multipart("/api/knowledge/artifacts")
                .file(new MockMultipartFile("file", fixture, mediaType, bytes))
                .param("artifactType", artifactType.name()))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsString()).get("data").get("id").asText());
    }

    private UUID uploadMatrix() throws Exception {
        return upload("knowledge-matrix.xlsx", XLSX_MEDIA_TYPE, ArtifactType.PLAYBOOK);
    }

    private JsonNode firstCandidate(UUID artifactId) throws Exception {
        var candidates = listCandidates(artifactId);
        assertThat(candidates).as("the fixture yields candidates").isNotEmpty();
        return candidates.get(0);
    }

    private java.util.List<JsonNode> listCandidates(UUID artifactId) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/knowledge/artifacts/{id}/candidates", artifactId))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        var candidates = new java.util.ArrayList<JsonNode>();
        data.forEach(candidates::add);
        return candidates;
    }

    private JsonNode liabilityCapRow(UUID artifactId) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/knowledge/artifacts/{id}/candidates", artifactId))
                .andExpect(status().isOk())
                .andReturn();
        var candidates = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data");
        for (var candidate : candidates) {
            var preferred = candidate.get("payloadColumns").get("Preferred");
            if (preferred != null && "1x annual fees".equals(preferred.asText())) {
                return candidate;
            }
        }
        throw new AssertionError("no liability cap row in the fixture");
    }

    private JsonNode getCandidate(String candidateId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/knowledge/candidates/{id}", candidateId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }
}
