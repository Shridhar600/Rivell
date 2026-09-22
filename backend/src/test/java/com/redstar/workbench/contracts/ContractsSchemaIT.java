package com.redstar.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

@SpringBootTest
@Testcontainers
class ContractsSchemaIT {

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
    }

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void appliesContractsMigration() {
        assertThat(jdbcClient.sql("select count(*) from flyway_schema_history where version = '2'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(tableExists("source_artifact")).isTrue();
        assertThat(tableExists("contract")).isTrue();
        assertThat(tableExists("contract_version")).isTrue();
        assertThat(tableExists("document_node")).isTrue();
    }

    @Test
    void insertsAndReloadsContractStructure() {
        var organizationId = UUID.randomUUID();
        var artifactId = UUID.randomUUID();
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var bodyNodeId = UUID.randomUUID();
        var childNodeId = UUID.randomUUID();
        var now = OffsetDateTime.of(2026, 9, 22, 12, 0, 0, 0, ZoneOffset.UTC);

        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256, storage_key, created_at)
                values (:id, :orgId, :filename, :contentType, :sizeBytes, :sha256, :storageKey, :createdAt)
                """)
                .param("id", artifactId)
                .param("orgId", organizationId)
                .param("filename", "nda.docx")
                .param("contentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .param("sizeBytes", 42L)
                .param("sha256", "a".repeat(64))
                .param("storageKey", "org/contracts/nda.docx")
                .param("createdAt", now)
                .update();

        jdbcClient.sql("""
                insert into contract (id, organization_id, title, created_at)
                values (:id, :orgId, :title, :createdAt)
                """)
                .param("id", contractId)
                .param("orgId", organizationId)
                .param("title", "Mutual NDA")
                .param("createdAt", now)
                .update();

        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, role, parser_version, parse_status, parse_warnings, created_at)
                values (:id, :contractId, 1, :artifactId, 'ORIGINAL', 'redstar-docx-parser-1', 'OK', cast(:warnings as jsonb), :createdAt)
                """)
                .param("id", versionId)
                .param("contractId", contractId)
                .param("artifactId", artifactId)
                .param("warnings", "[]")
                .param("createdAt", now)
                .update();

        jdbcClient.sql("""
                insert into document_node
                  (id, contract_version_id, parent_id, node_type, ordinal, text, heading_level,
                   part_path, body_index, paragraph_index, table_index, row_index, cell_index,
                   run_start, run_end, raw_style_id)
                values (:id, :versionId, null, 'BODY', 0, null, null,
                        'word/document.xml', 0, null, null, null, null, null, null, null)
                """)
                .param("id", bodyNodeId)
                .param("versionId", versionId)
                .update();

        jdbcClient.sql("""
                insert into document_node
                  (id, contract_version_id, parent_id, node_type, ordinal, text, heading_level,
                   part_path, body_index, paragraph_index, table_index, row_index, cell_index,
                   run_start, run_end, raw_style_id)
                values (:id, :versionId, :parentId, 'TITLE', 0, :text, null,
                        'word/document.xml', 0, 0, null, null, null, null, null, 'Title')
                """)
                .param("id", childNodeId)
                .param("versionId", versionId)
                .param("parentId", bodyNodeId)
                .param("text", "Mutual Non-Disclosure Agreement")
                .update();

        var sha = jdbcClient.sql("select sha256 from source_artifact where id = :id")
                .param("id", artifactId)
                .query(String.class).single();
        assertThat(sha).isEqualTo("a".repeat(64));

        var title = jdbcClient.sql("""
                select n.text from document_node n
                where n.contract_version_id = :versionId and n.node_type = 'TITLE'
                """)
                .param("versionId", versionId)
                .query(String.class).single();
        assertThat(title).isEqualTo("Mutual Non-Disclosure Agreement");

        var parentCount = jdbcClient.sql(
                        "select count(*) from document_node where contract_version_id = :versionId and parent_id = :parentId")
                .param("versionId", versionId)
                .param("parentId", bodyNodeId)
                .query(Integer.class).single();
        assertThat(parentCount).isEqualTo(1);

        var versionNumber = jdbcClient.sql("select version_number from contract_version where id = :id")
                .param("id", versionId)
                .query(Integer.class).single();
        assertThat(versionNumber).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateVersionNumbersPerContract() {
        var contractId = UUID.randomUUID();
        var artifactId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbcClient.sql("""
                insert into contract (id, organization_id, title, created_at)
                values (:id, :orgId, null, :createdAt)
                """)
                .param("id", contractId)
                .param("orgId", UUID.randomUUID())
                .param("createdAt", now)
                .update();

        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256, storage_key, created_at)
                values (:id, :orgId, 'a.docx', 'application/octet-stream', 1, :sha256, 'k', :createdAt)
                """)
                .param("id", artifactId)
                .param("orgId", UUID.randomUUID())
                .param("sha256", "b".repeat(64))
                .param("createdAt", now)
                .update();

        insertVersion(UUID.randomUUID(), contractId, artifactId, now);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> insertVersion(UUID.randomUUID(), contractId, artifactId, now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void insertVersion(UUID id, UUID contractId, UUID artifactId, OffsetDateTime now) {
        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, role, parser_version, parse_status, parse_warnings, created_at)
                values (:id, :contractId, 1, :artifactId, 'ORIGINAL', 'p', 'OK', cast('{}' as jsonb), :createdAt)
                """)
                .param("id", id)
                .param("contractId", contractId)
                .param("artifactId", artifactId)
                .param("createdAt", now)
                .update();
    }

    private boolean tableExists(String name) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("select exists (select 1 from information_schema.tables where table_name = :name)")
                .param("name", name)
                .query(Boolean.class).single());
    }
}
