package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The title rule improved after sources were ingested. The one-time re-derivation must replace
 * a title the old rule read from a metadata line, name a workbook from its stored cover sheet,
 * and leave a source whose nodes name nothing exactly as it was.
 */
@SpringBootTest
@Testcontainers
class KnowledgeTitleRederivationIT {

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
    JdbcClient jdbc;

    @Autowired
    KnowledgeRepository repository;

    @Autowired
    KnowledgeTitleRederivationService rederivation;

    @Test
    void rederivesChangedTitlesOnceAndNeverClearsATitle() {
        assertThat(repository.dataMigrationCompleted(
                KnowledgeTitleRederivationService.MIGRATION_KEY))
                .as("the startup runner records its completion even when the database is empty")
                .isTrue();
        jdbc.sql("delete from knowledge_data_migration where migration_key = :key")
                .param("key", KnowledgeTitleRederivationService.MIGRATION_KEY)
                .update();

        var organizationId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        insertArtifact(documentId, organizationId, "legacy-playbook.docx",
                "Owner: Jane Doe");
        var documentRoot = insertNode(documentId, organizationId, null, "BODY", 0, null, null,
                null);
        insertNode(documentId, organizationId, documentRoot, "TITLE", 1,
                "COMMERCIAL PLAYBOOK", null, null);
        insertNode(documentId, organizationId, documentRoot, "PARAGRAPH", 2,
                "Owner: Jane Doe", null, null);

        var workbookId = UUID.randomUUID();
        insertArtifact(workbookId, organizationId, "named-playbook.xlsx", null);
        var workbookRoot = insertNode(workbookId, organizationId, null, "WORKBOOK", 0,
                null, null, null);
        var guide = insertNode(workbookId, organizationId, workbookRoot, "SHEET", 1,
                null, "Guide", null);
        var firstRow = insertNode(workbookId, organizationId, guide, "SPREADSHEET_ROW", 2,
                null, null, null);
        insertNode(workbookId, organizationId, firstRow, "SPREADSHEET_CELL", 3,
                "Commercial Playbook", null, 0);

        var unnamedWorkbookId = UUID.randomUUID();
        insertArtifact(unnamedWorkbookId, organizationId, "legacy-playbook.xlsx",
                "Old workbook title");
        var unnamedRoot = insertNode(unnamedWorkbookId, organizationId, null, "WORKBOOK", 0,
                null, null, null);
        insertNode(unnamedWorkbookId, organizationId, unnamedRoot, "SHEET", 1,
                null, "Data", null);

        var first = rederivation.rederiveOnce();

        assertThat(first.examined()).isEqualTo(3);
        assertThat(first.updated()).isEqualTo(2);
        assertThat(documentTitle(documentId)).isEqualTo("COMMERCIAL PLAYBOOK");
        assertThat(documentTitle(workbookId))
                .as("a workbook is named by its cover sheet")
                .isEqualTo("Commercial Playbook");
        assertThat(documentTitle(unnamedWorkbookId))
                .as("a source whose stored nodes name nothing keeps the title it had")
                .isEqualTo("Old workbook title");

        var second = rederivation.rederiveOnce();

        assertThat(second.examined()).isZero();
        assertThat(second.updated()).isZero();

        var idempotent = rederivation.rederive();

        assertThat(idempotent.examined()).isEqualTo(3);
        assertThat(idempotent.updated()).isZero();
        assertThat(documentTitle(documentId)).isEqualTo("COMMERCIAL PLAYBOOK");
    }

    private void insertArtifact(UUID artifactId, UUID organizationId, String filename,
            String documentTitle) {
        repository.insertArtifact(new KnowledgeArtifact(
                artifactId, organizationId, ArtifactType.PLAYBOOK, filename,
                filename.endsWith(".xlsx")
                        ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        : "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                1024, "a".repeat(64), "legacy/" + filename,
                new ArtifactManifest(Set.of(), null, null, null, null, null, null, null, null),
                IngestStatus.EXTRACTED, "test-parser-1", List.of(), null, Instant.now(), null));
        if (documentTitle != null) {
            repository.updateDocumentTitle(artifactId, documentTitle);
        }
    }

    private UUID insertNode(UUID artifactId, UUID organizationId, UUID parentId, String nodeType,
            int ordinal, String text, String sheetName, Integer columnIndex) {
        var nodeId = UUID.randomUUID();
        jdbc.sql("""
                        insert into knowledge_source_node (
                          id, artifact_id, organization_id, parent_id, node_type, ordinal, text,
                          part_path, body_index, sheet_name, column_index)
                        values (:id, :artifactId, :organizationId, :parentId, :nodeType, :ordinal,
                          :text, :partPath, 0, :sheetName, :columnIndex)
                        """)
                .param("id", nodeId)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .param("parentId", parentId)
                .param("nodeType", nodeType)
                .param("ordinal", ordinal)
                .param("text", text)
                .param("partPath", "test/document.xml")
                .param("sheetName", sheetName)
                .param("columnIndex", columnIndex)
                .update();
        return nodeId;
    }

    private String documentTitle(UUID artifactId) {
        return jdbc.sql("select document_title from knowledge_artifact where id = :id")
                .param("id", artifactId)
                .query(String.class)
                .single();
    }
}
