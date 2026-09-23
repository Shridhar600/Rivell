package com.rivell.workbench.knowledge;

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
class KnowledgeSchemaIT {

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
    void appliesKnowledgeMigration() {
        assertThat(jdbcClient.sql("select count(*) from flyway_schema_history where version = '4'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(tableExists("knowledge_artifact")).isTrue();
        assertThat(tableExists("knowledge_source_node")).isTrue();
        assertThat(tableExists("knowledge_extraction_unit")).isTrue();
        assertThat(tableExists("knowledge_candidate")).isTrue();
        assertThat(tableExists("knowledge_publication")).isTrue();
        assertThat(tableExists("knowledge_publication_item")).isTrue();
    }

    @Test
    void insertsAndReloadsFullKnowledgeChain() {
        var org = UUID.randomUUID();
        var now = OffsetDateTime.now();

        var artifactId = UUID.randomUUID();
        insertArtifact(artifactId, org, now);

        var workbookId = UUID.randomUUID();
        var sheetId = UUID.randomUUID();
        var rowId = UUID.randomUUID();
        insertNode(workbookId, artifactId, null, "WORKBOOK", 0);
        insertNode(sheetId, artifactId, workbookId, "SHEET", 0);
        insertNode(rowId, artifactId, sheetId, "SPREADSHEET_ROW", 0);

        var unitId = UUID.randomUUID();
        insertUnit(unitId, artifactId, 0, "SPREADSHEET_ROW");

        var candidateId = UUID.randomUUID();
        insertCandidate(candidateId, artifactId, unitId, org, now);

        var nodeCount = jdbcClient.sql(
                        "select count(*) from knowledge_source_node where artifact_id = :id")
                .param("id", artifactId).query(Integer.class).single();
        assertThat(nodeCount).isEqualTo(3);

        var payload = jdbcClient.sql(
                        "select payload->'columns'->>'Preferred' from knowledge_candidate where id = :id")
                .param("id", candidateId).query(String.class).single();
        assertThat(payload).isEqualTo("1x annual fees");
    }

    @Test
    void rejectsCandidateOfAnotherOrganization() {
        var orgA = UUID.randomUUID();
        var orgB = UUID.randomUUID();
        var now = OffsetDateTime.now();
        var artifactA = UUID.randomUUID();
        var unitA = UUID.randomUUID();
        insertArtifact(artifactA, orgA, now);
        insertUnit(unitA, artifactA, 0, "HEADING_SECTION");

        assertThatThrownBy(() -> insertCandidate(
                UUID.randomUUID(), artifactA, unitA, orgB, now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCandidateReferencingUnitOfAnotherArtifact() {
        var org = UUID.randomUUID();
        var now = OffsetDateTime.now();
        var artifactA = UUID.randomUUID();
        var artifactB = UUID.randomUUID();
        insertArtifact(artifactA, org, now);
        insertArtifact(artifactB, org, now);
        var unitOnB = UUID.randomUUID();
        insertUnit(unitOnB, artifactB, 0, "HEADING_SECTION");

        assertThatThrownBy(() -> insertCandidate(
                UUID.randomUUID(), artifactA, unitOnB, org, now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNodeParentFromAnotherArtifact() {
        var org = UUID.randomUUID();
        var now = OffsetDateTime.now();
        var artifactA = UUID.randomUUID();
        var artifactB = UUID.randomUUID();
        insertArtifact(artifactA, org, now);
        insertArtifact(artifactB, org, now);

        insertNode(UUID.randomUUID(), artifactA, null, "BODY", 0);
        var parentId = jdbcClient.sql(
                        "select id from knowledge_source_node where artifact_id = :id")
                .param("id", artifactA).query(UUID.class).single();

        assertThatThrownBy(() -> insertNode(UUID.randomUUID(), artifactB, parentId, "SHEET", 0))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidArtifactTypeAndStatus() {
        var org = UUID.randomUUID();
        var now = OffsetDateTime.now();
        assertThatThrownBy(() -> insertArtifactWithType(org, "AI_POLICY", now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        insert into knowledge_artifact
                          (id, organization_id, artifact_type, filename, content_type, size_bytes,
                           sha256, storage_key, ingest_status, created_at)
                        values (:id, :orgId, 'PLAYBOOK', 'p.docx', 'application/octet-stream', 1,
                                :sha256, 'k', 'PUBLISHED', :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("orgId", org)
                .param("sha256", "a".repeat(64))
                .param("createdAt", now)
                .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEffectivePeriodEndingBeforeItStarts() {
        var org = UUID.randomUUID();
        var now = OffsetDateTime.now();
        assertThatThrownBy(() -> jdbcClient.sql("""
                        insert into knowledge_artifact
                          (id, organization_id, artifact_type, filename, content_type, size_bytes,
                           sha256, storage_key, effective_from, effective_to, created_at)
                        values (:id, :orgId, 'PLAYBOOK', 'p.docx', 'application/octet-stream', 1,
                                :sha256, 'k', '2026-10-01', '2026-01-01', :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("orgId", org)
                .param("sha256", "a".repeat(64))
                .param("createdAt", now)
                .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCandidatePublishedTwiceAsAdd() {
        var org = UUID.randomUUID();
        var now = OffsetDateTime.now();
        var artifactId = UUID.randomUUID();
        var unitId = UUID.randomUUID();
        insertArtifact(artifactId, org, now);
        insertUnit(unitId, artifactId, 0, "HEADING_SECTION");

        var candidateId = UUID.randomUUID();
        insertCandidate(candidateId, artifactId, unitId, org, now);

        var publicationA = UUID.randomUUID();
        var publicationB = UUID.randomUUID();
        insertPublication(publicationA, org, 1, now);
        insertPublication(publicationB, org, 2, now);

        insertPublicationItem(UUID.randomUUID(), publicationA, candidateId, org, now);
        assertThatThrownBy(() -> insertPublicationItem(
                UUID.randomUUID(), publicationB, candidateId, org, now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void insertArtifact(UUID id, UUID org, OffsetDateTime now) {
        jdbcClient.sql("""
                insert into knowledge_artifact
                  (id, organization_id, artifact_type, filename, content_type, size_bytes,
                   sha256, storage_key, ingest_status, created_at)
                values (:id, :orgId, 'PLAYBOOK', 'p.docx', 'application/octet-stream', 1,
                        :sha256, 'k', 'EXTRACTED', :createdAt)
                """)
                .param("id", id)
                .param("orgId", org)
                .param("sha256", "a".repeat(64))
                .param("createdAt", now)
                .update();
    }

    private void insertArtifactWithType(UUID org, String artifactType, OffsetDateTime now) {
        jdbcClient.sql("""
                insert into knowledge_artifact
                  (id, organization_id, artifact_type, filename, content_type, size_bytes,
                   sha256, storage_key, created_at)
                values (:id, :orgId, :artifactType, 'p.docx', 'application/octet-stream', 1,
                        :sha256, 'k', :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("orgId", org)
                .param("artifactType", artifactType)
                .param("sha256", "a".repeat(64))
                .param("createdAt", now)
                .update();
    }


    private void insertNode(UUID id, UUID artifactId, UUID parentId, String nodeType, int ordinal) {
        jdbcClient.sql("""
                insert into knowledge_source_node
                  (id, artifact_id, organization_id, parent_id, node_type, ordinal, part_path, body_index)
                values (:id, :artifactId,
                        (select organization_id from knowledge_artifact where id = :artifactId),
                        :parentId, :nodeType, :ordinal, 'xl/worksheets/sheet1.xml', 0)
                """)
                .param("id", id)
                .param("artifactId", artifactId)
                .param("parentId", parentId)
                .param("nodeType", nodeType)
                .param("ordinal", ordinal)
                .update();
    }

    private void insertUnit(UUID id, UUID artifactId, int ordinal, String kind) {
        jdbcClient.sql("""
                insert into knowledge_extraction_unit
                  (id, artifact_id, organization_id, ordinal, kind, provenance, created_at)
                values (:id, :artifactId,
                        (select organization_id from knowledge_artifact where id = :artifactId),
                        :ordinal, :kind,
                        cast('["00000000-0000-0000-0000-000000000009"]' as jsonb), :createdAt)
                """)
                .param("id", id)
                .param("artifactId", artifactId)
                .param("ordinal", ordinal)
                .param("kind", kind)
                .param("createdAt", OffsetDateTime.now())
                .update();
    }

    private void insertCandidate(UUID id, UUID artifactId, UUID unitId, UUID org, OffsetDateTime now) {
        jdbcClient.sql("""
                insert into knowledge_candidate
                  (id, artifact_id, organization_id, extraction_unit_id, candidate_type,
                   review_status, raw_label, content_text, payload, created_at, updated_at)
                values (:id, :artifactId, :orgId, :unitId, 'PLAYBOOK_RULE', 'PENDING',
                        'Limitation of Liability', 'cap at 1x fees',
                        cast('{"columns":{"Preferred":"1x annual fees"}}' as jsonb),
                        :createdAt, :createdAt)
                """)
                .param("id", id)
                .param("artifactId", artifactId)
                .param("orgId", org)
                .param("unitId", unitId)
                .param("createdAt", now)
                .update();
    }

    private void insertPublication(UUID id, UUID org, long number, OffsetDateTime now) {
        jdbcClient.sql("""
                insert into knowledge_publication (id, organization_id, publication_number, created_at)
                values (:id, :orgId, :number, :createdAt)
                """)
                .param("id", id)
                .param("orgId", org)
                .param("number", number)
                .param("createdAt", now)
                .update();
    }

    private void insertPublicationItem(UUID id, UUID publicationId, UUID candidateId, UUID org, OffsetDateTime now) {
        jdbcClient.sql("""
                insert into knowledge_publication_item
                  (id, publication_id, organization_id, candidate_id, action, created_at)
                values (:id, :publicationId, :orgId, :candidateId, 'ADD', :createdAt)
                """)
                .param("id", id)
                .param("publicationId", publicationId)
                .param("orgId", org)
                .param("candidateId", candidateId)
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
