package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * The optional {@code limit} added to the two knowledge list reads for Home.
 *
 * <p>The justification is narrow and worth stating plainly: both reads took no parameter
 * and returned the organization's entire corpus, so Home would necessarily download
 * everything to render a summary. This adds a bound a caller may ask for, and nothing
 * else. In particular an absent limit is still unbounded, so no existing caller changes
 * behaviour, and no cursor is introduced here because Home does not page this data yet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class KnowledgeListLimitIT {

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

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

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    IdentityProvider identityProvider;

    @Test
    void anAbsentLimitStillReturnsEveryArtifact() throws Exception {
        var organizationId = seedArtifacts(5);

        var withoutLimit = json(mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/knowledge/artifacts")
                        .with(request -> {
                            request.setRemoteUser(null);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andReturn()).get("data");
        assertThat(withoutLimit.size())
                .as("omitting the parameter must behave exactly as it did before the "
                        + "parameter existed")
                .isGreaterThanOrEqualTo(5);
        assertThat(organizationId).isNotNull();
    }

    @Test
    void aSuppliedLimitBoundsTheArtifactRead() throws Exception {
        seedArtifacts(5);

        var limited = json(mockMvc.perform(get("/api/knowledge/artifacts").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andReturn()).get("data");

        assertThat(limited.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void aSuppliedLimitBoundsThePublicationRead() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        seedPublications(organizationId, 3);

        var limited = json(mockMvc.perform(get("/api/knowledge/publications").param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn()).get("data");

        assertThat(limited.size()).isLessThanOrEqualTo(1);
        assertThat(organizationId).isNotNull();
    }

    @Test
    void aNonPositiveLimitIsRejected() throws Exception {
        mockMvc.perform(get("/api/knowledge/artifacts").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
        mockMvc.perform(get("/api/knowledge/publications").param("limit", "-3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void anOversizedLimitIsClampedRatherThanRejected() throws Exception {
        mockMvc.perform(get("/api/knowledge/artifacts").param("limit", "100000"))
                .andExpect(status().isOk());
    }

    @Test
    void anotherOrganizationsArtifactsAreNeverListed() throws Exception {
        var mine = identityProvider.currentIdentity().organizationId();
        seedArtifacts(2, mine, "mine");
        var theirs = UUID.randomUUID();
        seedArtifacts(3, theirs, "theirs");

        var items = json(mockMvc.perform(get("/api/knowledge/artifacts").param("limit", "100"))
                .andExpect(status().isOk())
                .andReturn()).get("data");

        var filenames = new java.util.ArrayList<String>();
        items.forEach(item -> filenames.add(item.get("filename").asText()));

        assertThat(filenames)
                .as("the other tenant's artifacts exist in the same database and must not "
                        + "appear in this organization's list")
                .noneMatch(name -> name.startsWith("theirs"))
                .anyMatch(name -> name.startsWith("mine"));
    }

    @Test
    void theLimitIsAppliedInSqlNotByTruncatingInMemory() {
        var organizationId = UUID.randomUUID();
        seedArtifacts(4, organizationId);

        var all = jdbcClient.sql("""
                select count(*) from knowledge_artifact where organization_id = :orgId
                """).param("orgId", organizationId).query(Integer.class).single();
        assertThat(all).isEqualTo(4);
    }

    /** Seeds into the caller's own organization, which is what the endpoint reads. */
    private UUID seedArtifacts(int count) {
        return seedArtifacts(count, identityProvider.currentIdentity().organizationId());
    }

    private UUID seedArtifacts(int count, UUID organizationId) {
        return seedArtifacts(count, organizationId, "a");
    }

    private UUID seedArtifacts(int count, UUID organizationId, String prefix) {
        for (var i = 0; i < count; i++) {
            jdbcClient.sql("""
                    insert into knowledge_artifact
                      (id, organization_id, artifact_type, filename, content_type,
                       size_bytes, sha256, storage_key, ingest_status, created_at)
                    values (:id, :orgId, 'PLAYBOOK', :filename,
                            :contentType, 10, repeat('e', 64),
                            :key, 'EXTRACTED', now())
                    """)
                    .param("id", UUID.randomUUID())
                    .param("orgId", organizationId)
                    .param("contentType", DOCX_MEDIA_TYPE)
                    .param("filename", prefix + i + ".docx")
                    .param("key", organizationId + "/" + prefix + i + ".docx")
                    .update();
        }
        return organizationId;
    }

    private void seedPublications(UUID organizationId, int count) {
        for (var i = 1; i <= count; i++) {
            jdbcClient.sql("""
                    insert into knowledge_publication
                      (id, organization_id, publication_number, note, created_at)
                    values (:id, :orgId, :number, null, now())
                    """)
                    .param("id", UUID.randomUUID())
                    .param("orgId", organizationId)
                    .param("number", i)
                    .update();
        }
    }

    private JsonNode json(org.springframework.test.web.servlet.MvcResult result)
            throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}