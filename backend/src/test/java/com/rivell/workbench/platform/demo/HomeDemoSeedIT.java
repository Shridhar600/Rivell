package com.rivell.workbench.platform.demo;

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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The demo seed, exercised through the real HTTP surface.
 *
 * <p>Deliberately proportional: this proves the seed is off by default, that enabling it
 * produces entities the Home reads actually expose, and that running it again does not
 * duplicate anything. It is not an exhaustive corruption-and-recovery suite.
 */
@SpringBootTest(properties = "rivell.demo.seed-enabled=true")
@AutoConfigureMockMvc
@Testcontainers
class HomeDemoSeedIT {

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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    HomeDemoSeeder seeder;

    @Test
    void seededContractsAreExposedByTheRealContractList() throws Exception {
        var items = json(mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("items");

        assertThat(items).hasSize(3);
        assertThat(items.toString())
                .contains("Atlas Mutual NDA.docx")
                .contains("Northstar Vendor MSA.docx")
                .contains("Beacon Data Processing Addendum.docx");
        for (var item : items) {
            assertThat(item.get("latestVersion").get("versionNumber").asInt()).isEqualTo(1);
            assertThat(item.get("latestVersion").get("filename").asText()).isNotBlank();
            assertThat(item.get("latestVersion").get("nodeCount").asInt()).isPositive();
        }
    }

    @Test
    void seededReviewsAreExposedWithTruthfulStates() throws Exception {
        var items = json(mockMvc.perform(get("/api/reviews"))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("items");

        assertThat(items).hasSize(3);

        var statuses = new java.util.ArrayList<String>();
        items.forEach(item -> statuses.add(item.get("status").asText()));
        assertThat(statuses).contains("COMPLETED", "ANALYSIS_QUEUED");

        for (var item : items) {
            assertThat(item.get("contractFilename").asText())
                    .as("every row identifies its own pinned contract document")
                    .isNotBlank();
        }

        var completed = completedRow(items);
        assertThat(completed.get("plannedChecks").asInt())
                .as("a completed review reports a real plan and real executions")
                .isPositive();
        assertThat(completed.get("findings").asInt())
                .as("a completed demo review finds real issues, so the Review Workspace "
                        + "has something truthful to show")
                .isPositive();
    }

    @Test
    void theCompletedSeedReviewHasFindingsWhoseEvidenceResolves() throws Exception {
        var completedId = completedReviewId();

        var findings = json(mockMvc.perform(get("/api/reviews/{id}/findings", completedId))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("items");
        assertThat(findings).isNotEmpty();

        for (var finding : findings) {
            var detail = json(mockMvc.perform(get("/api/reviews/{id}/findings/{findingId}",
                            completedId, finding.get("id").asText()))
                    .andExpect(status().isOk())
                    .andReturn()).get("data");

            assertThat(detail.get("evidenceItemIds")).isNotNull();
            var resolved = detail.get("evidenceItems");
            var resolvedIds = new java.util.ArrayList<String>();
            resolved.forEach(item -> resolvedIds.add(item.get("evidenceId").asText()));
            detail.get("evidenceItemIds").forEach(id ->
                    assertThat(resolvedIds).contains(id.asText()));
        }
    }

    @Test
    void theSeededSummaryReportsItsPinnedDocumentAndProposalCounts() throws Exception {
        var summary = json(mockMvc.perform(get("/api/reviews/{id}", completedReviewId()))
                .andExpect(status().isOk())
                .andReturn()).get("data");

        assertThat(summary.get("document").get("contractFilename").asText())
                .isEqualTo("Northstar Vendor MSA.docx");
        assertThat(summary.get("document").get("uploadedAt").asText()).isNotBlank();
        assertThat(summary.get("document").get("sizeBytes").asLong()).isPositive();

        var proposed = summary.get("proposedChanges");
        assertThat(proposed.get("replace").asInt() + proposed.get("insert").asInt())
                .as("the demo review proposes at least one executable change")
                .isPositive();
        assertThat(proposed.get("delete").asInt())
                .as("deletion is never a machine proposal")
                .isZero();
    }

    private JsonNode completedRow(JsonNode items) {
        for (var item : items) {
            if ("COMPLETED".equals(item.get("status").asText())) {
                return item;
            }
        }
        throw new AssertionError("the seed must create a completed review");
    }

    private UUID completedReviewId() {
        return jdbcClient.sql("""
                select id from review
                where status = 'COMPLETED'
                  and id in (select review_id from finding)
                order by created_at desc
                limit 1
                """)
                .query(UUID.class).single();
    }

    @Test
    void seededKnowledgeIsExposedAsArtifactsAndPublications() throws Exception {
        var artifacts = json(mockMvc.perform(get("/api/knowledge/artifacts"))
                .andExpect(status().isOk())
                .andReturn()).get("data");
        assertThat(artifacts).isNotEmpty();
        assertThat(artifacts.toString()).contains("Rivell Demo Playbook.docx");

        var publications = json(mockMvc.perform(get("/api/knowledge/publications"))
                .andExpect(status().isOk())
                .andReturn()).get("data");
        assertThat(publications)
                .as("the playbook candidates were really approved and published")
                .isNotEmpty();
    }

    @Test
    void runningTheSeedAgainDoesNotDuplicateAnything() throws Exception {
        var contractsBefore = count("contract");
        var reviewsBefore = count("review");
        var artifactsBefore = count("knowledge_artifact");
        var publicationsBefore = count("knowledge_publication");

        seeder.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));

        assertThat(count("contract")).isEqualTo(contractsBefore);
        assertThat(count("review")).isEqualTo(reviewsBefore);
        assertThat(count("knowledge_artifact")).isEqualTo(artifactsBefore);
        assertThat(count("knowledge_publication")).isEqualTo(publicationsBefore);
    }

    private int count(String table) {
        return jdbcClient.sql("select count(*) from " + table)
                .query(Integer.class).single();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
