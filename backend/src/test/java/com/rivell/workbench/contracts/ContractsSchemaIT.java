package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
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
    JdbcClient jdbcClient;

    @Test
    void appliesContractsMigrations() {
        assertThat(jdbcClient.sql("select count(*) from flyway_schema_history where version in ('2','3')")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(tableExists("source_artifact")).isTrue();
        assertThat(tableExists("contract")).isTrue();
        assertThat(tableExists("contract_version")).isTrue();
        assertThat(tableExists("document_node")).isTrue();
    }

    @Test
    void theReviewSnapshotCheckAcceptsEveryDeclaredContractType() {
        var definition = jdbcClient.sql("""
                select pg_get_constraintdef(oid)
                from pg_constraint
                where conrelid = 'review_snapshot'::regclass
                  and conname = 'review_snapshot_contract_type_check'
                """).query(String.class).single();

        for (var type : ContractType.values()) {
            assertThat(definition).as(type.name()).contains("'" + type.name() + "'");
        }
    }

    @Test
    void insertsAndReloadsContractStructure() {
        var organizationId = UUID.randomUUID();
        var artifactId = UUID.randomUUID();
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var bodyNodeId = UUID.randomUUID();
        var childNodeId = UUID.randomUUID();
        var now = OffsetDateTime.now();

        insertArtifact(artifactId, organizationId, now);
        insertContractRow(contractId, organizationId, "Mutual NDA", now);
        insertVersionRow(versionId, contractId, artifactId, organizationId, 1, now);
        insertNode(bodyNodeId, versionId, null, "BODY", 0);
        insertNode(childNodeId, versionId, bodyNodeId, "TITLE", 0);

        var title = jdbcClient.sql("""
                select n.text from document_node n
                where n.contract_version_id = :versionId and n.node_type = 'TITLE'
                """)
                .param("versionId", versionId)
                .query(String.class).single();
        assertThat(title).isEqualTo("Mutual Non-Disclosure Agreement");

        var versionNumber = jdbcClient.sql("select version_number from contract_version where id = :id")
                .param("id", versionId)
                .query(Integer.class).single();
        assertThat(versionNumber).isEqualTo(1);
    }

    @Test
    void rejectsParentFromAnotherContractVersion() {
        var organizationId = UUID.randomUUID();
        var now = OffsetDateTime.now();
        insertArtifact(UUID.randomUUID(), organizationId, now);
        var contractId = UUID.randomUUID();
        insertContractRow(contractId, organizationId, null, now);
        var artifactId = UUID.randomUUID();
        insertArtifact(artifactId, organizationId, now);
        var versionA = UUID.randomUUID();
        var versionB = UUID.randomUUID();
        insertVersionRow(versionA, contractId, artifactId, organizationId, 1, now);
        insertVersionRow(versionB, contractId, artifactId, organizationId, 2, now);

        var parentNode = UUID.randomUUID();
        insertNode(parentNode, versionA, null, "BODY", 0);

        // a child that declares version B but points at a parent from version A is impossible
        assertThatThrownBy(() -> insertNode(UUID.randomUUID(), versionB, parentNode, "TITLE", 0))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rejectsVersionReferencingArtifactOfAnotherOrganization() {
        var orgA = UUID.randomUUID();
        var orgB = UUID.randomUUID();
        var now = OffsetDateTime.now();
        var artifactA = UUID.randomUUID();
        var contractA = UUID.randomUUID();
        insertArtifact(artifactA, orgA, now);
        insertContractRow(contractA, orgA, null, now);

        assertThatThrownBy(() -> insertVersionRow(
                UUID.randomUUID(), contractA, artifactA, orgB, 1, now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rejectsContractVersionOfAnotherOrganization() {
        var orgA = UUID.randomUUID();
        var orgB = UUID.randomUUID();
        var now = OffsetDateTime.now();
        var contractA = UUID.randomUUID();
        var artifactB = UUID.randomUUID();
        insertArtifact(artifactB, orgB, now);
        insertContractRow(contractA, orgA, null, now);

        assertThatThrownBy(() -> insertVersionRow(
                UUID.randomUUID(), contractA, artifactB, orgA, 1, now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateVersionNumbersPerContract() {
        var contractId = UUID.randomUUID();
        var artifactId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var now = OffsetDateTime.now();

        insertArtifact(artifactId, organizationId, now);
        insertContractRow(contractId, organizationId, null, now);

        insertVersionRow(UUID.randomUUID(), contractId, artifactId, organizationId, 1, now);
        assertThatThrownBy(() -> insertVersionRow(
                UUID.randomUUID(), contractId, artifactId, organizationId, 1, now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void insertArtifact(UUID id, UUID organizationId, OffsetDateTime now) {
        jdbcClient.sql("""
                insert into source_artifact
                  (id, organization_id, filename, content_type, size_bytes, sha256, storage_key, created_at)
                values (:id, :orgId, 'nda.docx', 'application/octet-stream', 42, :sha256, 'k', :createdAt)
                """)
                .param("id", id)
                .param("orgId", organizationId)
                .param("sha256", "a".repeat(64))
                .param("createdAt", now)
                .update();
    }

    private void insertContractRow(
            UUID id, UUID organizationId, String title, OffsetDateTime now) {
        jdbcClient.sql("""
                insert into contract (id, organization_id, title, created_at)
                values (:id, :orgId, :title, :createdAt)
                """)
                .param("id", id)
                .param("orgId", organizationId)
                .param("title", title)
                .param("createdAt", now)
                .update();
    }

    private void insertVersionRow(
            UUID id, UUID contractId, UUID artifactId, UUID organizationId, int versionNumber,
            OffsetDateTime now) {
        jdbcClient.sql("""
                insert into contract_version
                  (id, contract_id, version_number, source_artifact_id, organization_id, role,
                   parser_version, parse_status, parse_warnings, created_at)
                values (:id, :contractId, :versionNumber, :artifactId, :organizationId, 'ORIGINAL',
                        'p', 'OK', cast('[]' as jsonb), :createdAt)
                """)
                .param("id", id)
                .param("contractId", contractId)
                .param("versionNumber", versionNumber)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .param("createdAt", now)
                .update();
    }

    private void insertNode(UUID id, UUID versionId, UUID parentId, String nodeType, int ordinal) {
        jdbcClient.sql("""
                insert into document_node
                  (id, contract_version_id, parent_id, node_type, ordinal, text, heading_level,
                   numbering_label, part_path, body_index)
                values (:id, :versionId, :parentId, :nodeType, :ordinal, :text, null, null,
                        'word/document.xml', 0)
                """)
                .param("id", id)
                .param("versionId", versionId)
                .param("parentId", parentId)
                .param("nodeType", nodeType)
                .param("ordinal", ordinal)
                .param("text", "TITLE".equals(nodeType) ? "Mutual Non-Disclosure Agreement" : null)
                .update();
    }

    private boolean tableExists(String name) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("select exists (select 1 from information_schema.tables where table_name = :name)")
                .param("name", name)
                .query(Boolean.class).single());
    }
}
