package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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
 * The derived record-level scope changed meaning with the wider taxonomy. Knowledge compiled
 * before that change stores the old reading (Supply as OTHER, an order form as SAAS); the
 * one-time re-derivation must move those rows to the current reading without touching the
 * snapshots reviews froze.
 */
@SpringBootTest
@Testcontainers
class SourceApplicabilityRederivationIT {

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
    SourceApplicabilityRederivationService rederivation;

    @Test
    void rederivesOldScopesOnceAndLeavesFrozenSnapshotsAlone() {
        assertThat(repository.dataMigrationCompleted(
                SourceApplicabilityRederivationService.MIGRATION_KEY))
                .as("the startup runner records its completion even when the database is empty")
                .isTrue();
        jdbc.sql("delete from knowledge_data_migration where migration_key = :key")
                .param("key", SourceApplicabilityRederivationService.MIGRATION_KEY)
                .update();

        var organizationId = UUID.randomUUID();
        var artifactId = UUID.randomUUID();
        insertKnowledgeArtifact(artifactId, organizationId);
        var payloadCandidate = insertCandidate(artifactId, organizationId, "PLAYBOOK_RULE",
                "PUBLISHED", "Limitation of liability",
                """
                {"Applies to":"MSA, Supply, Services."}
                """,
                "[\"MSA\",\"OTHER\"]");
        var headingCandidate = insertCandidate(artifactId, organizationId, "PLAYBOOK_RULE",
                "PENDING", "Payment terms", "{}", "[\"OTHER\"]", "HEADING_SECTION",
                "Applies to: Supply.\nPreferred position: Sixty days.");
        var unchangedCandidate = insertCandidate(artifactId, organizationId, "PLAYBOOK_RULE",
                "PENDING", "Notices", "{}", "[]", "HEADING_SECTION",
                "General terms only.");
        var staleScopeCandidate = insertCandidate(artifactId, organizationId, "PLAYBOOK_RULE",
                "PENDING", "Indemnity", "{}", "[\"MSA\"]", "HEADING_SECTION",
                "General terms only.");
        insertPublication(organizationId, payloadCandidate);

        var reviewId = UUID.randomUUID();
        insertFrozenReview(reviewId, organizationId, artifactId);
        var snapshotBefore = frozenSnapshot(reviewId);
        var applicableBefore = frozenApplicableKnowledge(reviewId);

        var payloadVersionBefore = rowVersion(payloadCandidate);
        var headingVersionBefore = rowVersion(headingCandidate);
        var unchangedVersionBefore = rowVersion(unchangedCandidate);
        var staleScopeVersionBefore = rowVersion(staleScopeCandidate);

        assertThat(publishedFor(organizationId, "SUPPLY")).isEmpty();
        assertThat(publishedFor(organizationId, "NDA")).isEmpty();
        assertThat(applicableFor(organizationId, "SUPPLY")).isEmpty();
        assertThat(applicableFor(organizationId, "NDA")).isEmpty();

        var first = rederivation.rederiveOnce();

        assertThat(first.examined()).isEqualTo(4);
        assertThat(first.updated()).isEqualTo(2);
        assertThat(sourceContractTypes(payloadCandidate))
                .containsExactlyInAnyOrder("MSA", "SUPPLY", "SERVICES");
        assertThat(sourceContractTypes(headingCandidate)).containsExactly("SUPPLY");
        assertThat(sourceContractTypes(unchangedCandidate)).isEmpty();
        assertThat(sourceContractTypes(staleScopeCandidate))
                .as("a source that states nothing keeps the non-empty scope it already had")
                .containsExactly("MSA");
        assertThat(rowVersion(payloadCandidate)).isNotEqualTo(payloadVersionBefore);
        assertThat(rowVersion(headingCandidate)).isNotEqualTo(headingVersionBefore);
        assertThat(rowVersion(unchangedCandidate))
                .as("a row whose source states nothing keeps its empty scope and is not rewritten")
                .isEqualTo(unchangedVersionBefore);
        assertThat(rowVersion(staleScopeCandidate))
                .as("a row whose source states nothing keeps its non-empty scope and is not rewritten")
                .isEqualTo(staleScopeVersionBefore);

        assertThat(publishedFor(organizationId, "SUPPLY"))
                .extracting(published -> published.candidate().id())
                .containsExactly(payloadCandidate);
        assertThat(publishedFor(organizationId, "NDA")).isEmpty();
        assertThat(applicableFor(organizationId, "SUPPLY"))
                .extracting(ApplicableKnowledge::artifactId)
                .containsExactly(artifactId);
        assertThat(applicableFor(organizationId, "NDA")).isEmpty();

        assertThat(frozenSnapshot(reviewId))
                .as("a review replays the snapshot it froze")
                .isEqualTo(snapshotBefore);
        assertThat(frozenApplicableKnowledge(reviewId))
                .as("the frozen playbook set is not recomputed")
                .isEqualTo(applicableBefore);

        var payloadVersionAfterFirstRun = rowVersion(payloadCandidate);
        var secondDerivation = rederivation.rederive();

        assertThat(secondDerivation.examined()).isEqualTo(4);
        assertThat(secondDerivation.updated()).isZero();
        assertThat(rowVersion(payloadCandidate)).isEqualTo(payloadVersionAfterFirstRun);

        var secondOnce = rederivation.rederiveOnce();

        assertThat(secondOnce.examined()).isZero();
        assertThat(secondOnce.updated()).isZero();
        assertThat(rowVersion(payloadCandidate)).isEqualTo(payloadVersionAfterFirstRun);
        assertThat(frozenSnapshot(reviewId)).isEqualTo(snapshotBefore);
    }

    private List<com.rivell.workbench.knowledge.PublishedCandidate> publishedFor(
            UUID organizationId, String contractType) {
        return repository.findPublishedCandidatesAsOf(PublishedKnowledgeQuery.unpublishedNever(
                organizationId, 1, Set.of(CandidateType.PLAYBOOK_RULE), null, null,
                contractType, null, null, null, null));
    }

    private List<ApplicableKnowledge> applicableFor(UUID organizationId, String contractType) {
        return repository.findApplicableKnowledge(new ApplicableKnowledgeQuery(
                organizationId, 1, contractType, null, null, null, null, LocalDate.now()));
    }

    private Set<String> sourceContractTypes(UUID candidateId) {
        return Set.copyOf(jdbc.sql("""
                        select value from knowledge_candidate c,
                             jsonb_array_elements_text(c.source_contract_types) as value
                        where c.id = :id
                        """)
                .param("id", candidateId)
                .query(String.class)
                .list());
    }

    private String rowVersion(UUID candidateId) {
        return jdbc.sql("select xmin::text from knowledge_candidate where id = :id")
                .param("id", candidateId)
                .query(String.class)
                .single();
    }

    private String frozenSnapshot(UUID reviewId) {
        return jdbc.sql("select snapshot::text from review_snapshot snapshot where review_id = :id")
                .param("id", reviewId)
                .query(String.class)
                .single();
    }

    private List<String> frozenApplicableKnowledge(UUID reviewId) {
        return jdbc.sql("""
                        select row_value::text from review_applicable_knowledge row_value
                        where review_id = :id order by ordinal
                        """)
                .param("id", reviewId)
                .query(String.class)
                .list();
    }

    private void insertKnowledgeArtifact(UUID artifactId, UUID organizationId) {
        jdbc.sql("""
                        insert into knowledge_artifact (
                          id, organization_id, artifact_type, filename, content_type, size_bytes,
                          sha256, storage_key, declared_contract_types, ingest_status,
                          parse_warnings, created_at)
                        values (:id, :organizationId, 'PLAYBOOK', 'legacy-playbook.xlsx',
                          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
                          1024, :sha256, 'legacy/playbook.xlsx', '[]'::jsonb, 'EXTRACTED',
                          '[]'::jsonb, now())
                        """)
                .param("id", artifactId)
                .param("organizationId", organizationId)
                .param("sha256", "a".repeat(64))
                .update();
    }

    private UUID insertCandidate(UUID artifactId, UUID organizationId, String candidateType,
            String reviewStatus, String rawLabel, String payloadJson, String sourceTypesJson) {
        return insertCandidate(artifactId, organizationId, candidateType, reviewStatus, rawLabel,
                payloadJson, sourceTypesJson, "SPREADSHEET_ROW", "Legacy record.");
    }

    private UUID insertCandidate(UUID artifactId, UUID organizationId, String candidateType,
            String reviewStatus, String rawLabel, String payloadJson, String sourceTypesJson,
            String unitKind, String unitContent) {
        var unitId = UUID.randomUUID();
        jdbc.sql("""
                        insert into knowledge_extraction_unit (
                          id, artifact_id, organization_id, ordinal, kind, content_text, provenance,
                          created_at)
                        values (:id, :artifactId, :organizationId, 1, :kind, :content,
                          '{"memberNodeIds":[]}'::jsonb, now())
                        """)
                .param("id", unitId)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .param("kind", unitKind)
                .param("content", unitContent)
                .update();
        var candidateId = UUID.randomUUID();
        jdbc.sql("""
                        insert into knowledge_candidate (
                          id, artifact_id, organization_id, extraction_unit_id, candidate_type,
                          review_status, raw_label, content_text, payload, declared_contract_types,
                          source_contract_types, conflict_signals, created_at, updated_at)
                        values (:id, :artifactId, :organizationId, :unitId, :candidateType,
                          :reviewStatus, :rawLabel, :contentText, :payload::jsonb, '[]'::jsonb,
                          :sourceTypes::jsonb, '[]'::jsonb, now(), now())
                        """)
                .param("id", candidateId)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .param("unitId", unitId)
                .param("candidateType", candidateType)
                .param("reviewStatus", reviewStatus)
                .param("rawLabel", rawLabel)
                .param("contentText", unitContent)
                .param("payload", payloadJson)
                .param("sourceTypes", sourceTypesJson)
                .update();
        return candidateId;
    }

    private void insertPublication(UUID organizationId, UUID candidateId) {
        var publicationId = UUID.randomUUID();
        jdbc.sql("""
                        insert into knowledge_publication
                          (id, organization_id, publication_number, note, created_at)
                        values (:id, :organizationId, 1, null, now())
                        """)
                .param("id", publicationId)
                .param("organizationId", organizationId)
                .update();
        jdbc.sql("""
                        insert into knowledge_publication_item
                          (id, publication_id, organization_id, candidate_id,
                           superseded_candidate_id, action, created_at)
                        values (:id, :publicationId, :organizationId, :candidateId,
                          null, 'ADD', now())
                        """)
                .param("id", UUID.randomUUID())
                .param("publicationId", publicationId)
                .param("organizationId", organizationId)
                .param("candidateId", candidateId)
                .update();
    }

    private void insertFrozenReview(UUID reviewId, UUID organizationId, UUID knowledgeArtifactId) {
        var contractArtifactId = UUID.randomUUID();
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        jdbc.sql("""
                        insert into source_artifact
                          (id, organization_id, filename, content_type, size_bytes, sha256,
                           storage_key, created_at)
                        values (:id, :organizationId, 'reviewed.docx', 'application/docx', 10,
                          repeat('b', 64), 'reviewed', now())
                        """)
                .param("id", contractArtifactId)
                .param("organizationId", organizationId)
                .update();
        jdbc.sql("""
                        insert into contract (id, organization_id, title, created_at)
                        values (:id, :organizationId, 'Frozen contract', now())
                        """)
                .param("id", contractId)
                .param("organizationId", organizationId)
                .update();
        jdbc.sql("""
                        insert into contract_version
                          (id, contract_id, version_number, source_artifact_id, organization_id,
                           role, parser_version, parse_status, parse_warnings, created_at)
                        values (:id, :contractId, 1, :artifactId, :organizationId, 'ORIGINAL',
                          'test-parser-1', 'OK', '[]'::jsonb, now())
                        """)
                .param("id", versionId)
                .param("contractId", contractId)
                .param("artifactId", contractArtifactId)
                .param("organizationId", organizationId)
                .update();
        jdbc.sql("""
                        insert into review
                          (id, organization_id, contract_id, contract_version_id,
                           contract_version_number, status, failure_reason, created_by,
                           created_at, updated_at, started_at, completed_at)
                        values (:id, :organizationId, :contractId, :versionId, 1, 'COMPLETED',
                          null, :createdBy, now(), now(), now(), now())
                        """)
                .param("id", reviewId)
                .param("organizationId", organizationId)
                .param("contractId", contractId)
                .param("versionId", versionId)
                .param("createdBy", UUID.randomUUID())
                .update();
        jdbc.sql("""
                        insert into review_snapshot
                          (review_id, organization_id, contract_id, contract_version_id,
                           contract_version_number, contract_semantics_id,
                           semantics_pipeline_version, semantics_digest, contract_type,
                           our_side_role, paper_source, jurisdiction, contract_pack_key,
                           pack_version, knowledge_pin_number, effective_on,
                           review_pipeline_version, embedding_index_version,
                           embedding_model_identity, decision_provider_id, decision_model_alias,
                           generative_provider_id, generative_model_alias, review_engine,
                           applicable_knowledge_frozen, created_at)
                        values (:reviewId, :organizationId, :contractId, :versionId, 1,
                          :semanticsId, 'test', :digest, 'OTHER', null, null, 'UNSPECIFIED',
                          null, null, 1, :effectiveOn, 'test', 'test', null, 'p', 'm', 'g', 'gm',
                          'V2', true, now())
                        """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .param("contractId", contractId)
                .param("versionId", versionId)
                .param("semanticsId", UUID.randomUUID())
                .param("digest", "c".repeat(64))
                .param("effectiveOn", LocalDate.now())
                .update();
        jdbc.sql("""
                        insert into review_applicable_knowledge
                          (review_id, organization_id, ordinal, artifact_id, artifact_type, name,
                           publication_number)
                        values (:reviewId, :organizationId, 0, :artifactId, 'PLAYBOOK',
                          'Legacy playbook', 1)
                        """)
                .param("reviewId", reviewId)
                .param("organizationId", organizationId)
                .param("artifactId", knowledgeArtifactId)
                .update();
    }
}
