package com.rivell.workbench.knowledge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
 * What the knowledge API tells a client about a Word guide and a clause library (two real user
 * files, fictional dev data): the title the document gives itself, a title a person corrects,
 * and positions in the order a reader expects.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class KnowledgeImportQualityApiIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");
    private static final String GUIDE = "import-quality-nda-guide.docx";
    private static final String LIBRARY = "import-quality-clause-library.xlsx";
    private static final String CALLOUT = "DO NOT TRADE THIS BOUNDARY FOR SPEED";

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
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));

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
    MutableIdentityProvider identityProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void aWordSourceAndAWorkbookReportTheirOwnTitles() throws Exception {
        var guide = upload(GUIDE, ArtifactType.PLAYBOOK);
        var library = upload(LIBRARY, ArtifactType.CLAUSE_LIBRARY);

        assertThat(guide.get("documentTitle").asText()).isEqualTo("Mutual NDA Negotiation Guide");
        assertThat(guide.get("filename").asText()).isEqualTo(GUIDE);
        assertThat(library.get("documentTitle").asText())
                .isEqualTo("KELTHERA THERAPEUTICS — APPROVED CLAUSE LIBRARY");

        var detail = json(get("/api/knowledge/artifacts/{id}", guide.get("id").asText()));
        assertThat(detail.get("documentTitle").asText()).isEqualTo("Mutual NDA Negotiation Guide");
        var listed = json(get("/api/knowledge/artifacts"));
        assertThat(listed.findValuesAsText("documentTitle")).contains("Mutual NDA Negotiation Guide");
    }

    @Test
    void aPersonCanCorrectAnEntryTitleAndTheMachineTitleStays() throws Exception {
        var artifactId = upload(GUIDE, ArtifactType.PLAYBOOK).get("id").asText();
        var callout = candidate(artifactId, CALLOUT);
        assertThat(callout.get("title").asText()).isEqualTo(CALLOUT);
        assertThat(callout.get("editedTitle").isNull()).isTrue();

        var edited = edit(callout.get("id").asText(), Map.of("title", "  Data boundary  ", "note", "short title"));

        assertThat(edited.get("reviewStatus").asText()).isEqualTo("EDITED");
        assertThat(edited.get("title").asText()).isEqualTo("Data boundary");
        assertThat(edited.get("editedTitle").asText()).isEqualTo("Data boundary");
        assertThat(edited.get("rawLabel").asText()).isEqualTo(CALLOUT);
        var reread = json(get("/api/knowledge/candidates/{id}", callout.get("id").asText()));
        assertThat(reread.get("title").asText()).isEqualTo("Data boundary");
    }

    @Test
    void aTitleCorrectionIsRefusedWhenItChangesNothingOrIsTooLong() throws Exception {
        var artifactId = upload(GUIDE, ArtifactType.PLAYBOOK).get("id").asText();
        var callout = candidate(artifactId, CALLOUT);

        mockMvc.perform(post("/api/knowledge/candidates/{id}/edit", callout.get("id").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("title", CALLOUT))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/knowledge/candidates/{id}/edit", callout.get("id").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("title", "x".repeat(201)))))
                .andExpect(status().isBadRequest());
        assertThat(json(get("/api/knowledge/candidates/{id}", callout.get("id").asText()))
                .get("reviewStatus").asText()).isEqualTo("PENDING");
    }

    @Test
    void aBlankTitleClearsTheCorrectionAndIsAChangeOnItsOwn() throws Exception {
        var artifactId = upload(GUIDE, ArtifactType.PLAYBOOK).get("id").asText();
        var callout = candidate(artifactId, CALLOUT);
        edit(callout.get("id").asText(), Map.of("title", "Data boundary"));

        var cleared = edit(callout.get("id").asText(), Map.of("title", ""));

        assertThat(cleared.get("title").asText()).isEqualTo(CALLOUT);
        assertThat(cleared.get("editedTitle").isNull()).isTrue();
        assertThat(cleared.get("rawLabel").asText()).isEqualTo(CALLOUT);
        assertThat(json(get("/api/knowledge/candidates/{id}", callout.get("id").asText()))
                .get("editedTitle").isNull()).isTrue();
    }

    @Test
    void aTitleWithALineBreakIsRefused() throws Exception {
        var artifactId = upload(GUIDE, ArtifactType.PLAYBOOK).get("id").asText();
        var callout = candidate(artifactId, CALLOUT);

        mockMvc.perform(post("/api/knowledge/candidates/{id}/edit", callout.get("id").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("title", "Data\nboundary"))))
                .andExpect(status().isBadRequest());
        assertThat(json(get("/api/knowledge/candidates/{id}", callout.get("id").asText()))
                .get("reviewStatus").asText()).isEqualTo("PENDING");
    }

    @Test
    void aCorrectedTitleSurvivesApprovalAndPublication() throws Exception {
        var artifactId = upload(GUIDE, ArtifactType.PLAYBOOK).get("id").asText();
        var callout = candidate(artifactId, CALLOUT);
        var candidateId = callout.get("id").asText();
        edit(candidateId, Map.of("title", "Data boundary"));
        json(post("/api/knowledge/candidates/{id}/approve", candidateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

        json(post("/api/knowledge/publications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("candidateIds", List.of(candidateId)))));

        var published = json(get("/api/knowledge/candidates/{id}", candidateId));
        assertThat(published.get("title").asText()).isEqualTo("Data boundary");
        assertThat(published.get("reviewStatus").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void positionsAreListedInTheOrderAReaderExpects() throws Exception {
        var artifactId = upload(GUIDE, ArtifactType.PLAYBOOK).get("id").asText();
        var callout = candidate(artifactId, CALLOUT);

        var edited = edit(callout.get("id").asText(), Map.of(
                "semanticFields", Map.of("positions", List.of("ESCALATION", "PREFERRED", "FALLBACK"))));

        assertThat(texts(edited.get("positions"))).containsExactly("PREFERRED", "FALLBACK", "ESCALATION");
    }

    private static List<String> texts(JsonNode array) {
        var values = new java.util.ArrayList<String>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private JsonNode candidate(String artifactId, String title) throws Exception {
        for (var candidate : json(get("/api/knowledge/artifacts/{id}/candidates", artifactId))) {
            if (title.equals(candidate.get("rawLabel").asText())) {
                return candidate;
            }
        }
        throw new AssertionError("no candidate titled " + title);
    }

    private JsonNode edit(String candidateId, Map<String, Object> body) throws Exception {
        return json(post("/api/knowledge/candidates/{id}/edit", candidateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)));
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        var result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode upload(String fixture, ArtifactType type) throws Exception {
        byte[] bytes;
        try (var stream = getClass().getClassLoader().getResourceAsStream("fixtures/" + fixture)) {
            assertThat(stream).as("classpath resource %s", fixture).isNotNull();
            bytes = stream.readAllBytes();
        }
        return json(multipart("/api/knowledge/artifacts")
                .file(new MockMultipartFile("file", fixture, "application/octet-stream", bytes))
                .param("artifactType", type.name()));
    }
}
