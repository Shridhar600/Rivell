package com.rivell.workbench.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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

import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeRepository;
import com.rivell.workbench.knowledge.PublishedKnowledgeQuery;

/**
 * Rows that tie on every relevance dimension must still come back in the same order on
 * every run. Before the content tie-break the order was carried by random UUIDs, so a
 * fresh database could select or rank a different set of rows for the same content.
 */
@SpringBootTest
@Testcontainers
class RetrievalOrderingIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    /** Ids chosen so the old random-id tie-break and the content tie-break disagree. */
    private static UUID lowId(int value) {
        return new UUID(0L, value);
    }

    private static UUID highId(int value) {
        return new UUID(0L, 100L + value);
    }

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
    KnowledgeRepository knowledge;

    @Autowired
    RetrievalRepository retrieval;

    @Autowired
    RetrievalProperties properties;

    @Test
    void topicTiesAreBrokenByContentNotByCandidateId() {
        var organizationId = UUID.randomUUID();
        var artifactId = UUID.randomUUID();
        var alpha = lowId(1);
        var beta = highId(1);
        var createdAt = Instant.parse("2026-01-01T00:00:00Z");
        insertArtifact(artifactId, organizationId, createdAt);
        var unitA = insertUnit(artifactId, organizationId, 0, "Alpha position", createdAt);
        var unitB = insertUnit(artifactId, organizationId, 0, "Beta position", createdAt);
        insertCandidate(alpha, artifactId, organizationId, unitA, "Alpha position",
                "Alpha content", "NOTICES", "{NOTICES}", createdAt);
        insertCandidate(beta, artifactId, organizationId, unitB, "Beta position",
                "Beta content", "NOTICES", "{NOTICES}", createdAt);
        insertPublication(organizationId, createdAt, alpha, beta);

        var found = knowledge.findPublishedCandidatesAsOf(PublishedKnowledgeQuery.byTopics(
                organizationId, 1, Set.of(CandidateType.PLAYBOOK_RULE), Set.of("NOTICES"),
                "NOTICES", null, null, null, null, null, LocalDate.of(2026, 1, 1), 2));

        assertThat(found)
                .extracting(candidate -> candidate.candidate().rawLabel())
                .containsExactly("Alpha position", "Beta position");
    }

    @Test
    void newestFirstTiesAreBrokenByContentNotByCandidateId() {
        var organizationId = UUID.randomUUID();
        var artifactId = UUID.randomUUID();
        var alpha = lowId(2);
        var beta = highId(2);
        var createdAt = Instant.parse("2026-01-01T00:00:00Z");
        insertArtifact(artifactId, organizationId, createdAt);
        var unitA = insertUnit(artifactId, organizationId, 0, "Alpha position", createdAt);
        var unitB = insertUnit(artifactId, organizationId, 1, "Beta position", createdAt);
        insertCandidate(alpha, artifactId, organizationId, unitA, "Alpha position",
                "Alpha content", "NOTICES", "{NOTICES}", createdAt);
        insertCandidate(beta, artifactId, organizationId, unitB, "Beta position",
                "Beta content", "NOTICES", "{NOTICES}", createdAt);
        insertPublication(organizationId, createdAt, alpha, beta);

        var found = knowledge.findPublishedCandidatesAsOf(
                PublishedKnowledgeQuery.unpublishedNever(organizationId, 1,
                        Set.of(CandidateType.PLAYBOOK_RULE), null, null, null, null, null, null,
                        LocalDate.of(2026, 1, 1)));

        assertThat(found)
                .extracting(candidate -> candidate.candidate().rawLabel())
                .containsExactly("Alpha position", "Beta position");
    }

    @Test
    void keysetResumptionFollowsTheContentTieBreakWhenTimestampsAreIdentical() {
        var organizationId = UUID.randomUUID();
        var artifactId = UUID.randomUUID();
        // Content-first ALPHA has the higher id: an id-only predicate would skip BETA.
        var alpha = highId(3);
        var beta = lowId(3);
        var createdAt = Instant.parse("2026-01-01T00:00:00Z");
        insertArtifact(artifactId, organizationId, createdAt);
        var unitA = insertUnit(artifactId, organizationId, 0, "Alpha position", createdAt);
        var unitB = insertUnit(artifactId, organizationId, 1, "Beta position", createdAt);
        insertCandidate(alpha, artifactId, organizationId, unitA, "Alpha position",
                "Alpha content", "NOTICES", "{NOTICES}", createdAt);
        insertCandidate(beta, artifactId, organizationId, unitB, "Beta position",
                "Beta content", "NOTICES", "{NOTICES}", createdAt);
        insertPublication(organizationId, createdAt, alpha, beta);

        var query = PublishedKnowledgeQuery.unpublishedNever(organizationId, 1,
                Set.of(CandidateType.PLAYBOOK_RULE), null, null, null, null, null, null,
                LocalDate.of(2026, 1, 1));
        var all = knowledge.findPublishedCandidatesAsOf(query);
        var cursor = all.stream()
                .filter(candidate -> candidate.candidate().id().equals(alpha))
                .findFirst().orElseThrow();
        var rest = knowledge.findPublishedCandidatesAsOf(query.after(cursor));

        // Same timestamp, and ALPHA sorts before BETA by content: resumption must not skip BETA.
        assertThat(rest)
                .extracting(candidate -> candidate.candidate().rawLabel())
                .containsExactly("Beta position");
    }

    @Test
    void lexicalTiesAreBrokenByContentHashNotByCandidateId() {
        var organizationId = UUID.randomUUID();
        var low = lowId(4);
        var high = highId(4);
        insertIndexRow(organizationId, high, "a".repeat(64));
        insertIndexRow(organizationId, low, "b".repeat(64));

        var hits = retrieval.findLexicalCandidates(organizationId, List.of(low, high),
                "alpha", 10, properties.indexVersion());

        assertThat(hits)
                .extracting(RetrievalRepository.CandidateHit::candidateId)
                .containsExactly(high, low);
    }

    @Test
    void vectorTiesAreBrokenByContentHashNotByCandidateId() {
        var organizationId = UUID.randomUUID();
        var low = lowId(5);
        var high = highId(5);
        insertIndexRow(organizationId, high, "a".repeat(64));
        insertIndexRow(organizationId, low, "b".repeat(64));

        var hits = retrieval.findVectorCandidates(organizationId, List.of(low, high),
                "test-model", 2, VectorLiterals.of(new float[] {1f, 0f}), 10,
                properties.indexVersion());

        assertThat(hits)
                .extracting(RetrievalRepository.CandidateHit::candidateId)
                .containsExactly(high, low);
    }

    private void insertArtifact(UUID artifactId, UUID organizationId, Instant createdAt) {
        jdbc.sql("""
                        insert into knowledge_artifact (
                          id, organization_id, artifact_type, filename, content_type, size_bytes,
                          sha256, storage_key, declared_contract_types, ingest_status,
                          parse_warnings, created_at)
                        values (:id, :organizationId, 'PLAYBOOK', 'ordering-test.docx',
                          'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                          1024, :sha256, 'ordering/test.docx', '[]'::jsonb, 'EXTRACTED',
                          '[]'::jsonb, :createdAt)
                        """)
                .param("id", artifactId)
                .param("organizationId", organizationId)
                .param("sha256", "a".repeat(64))
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .update();
    }

    private UUID insertUnit(UUID artifactId, UUID organizationId, int ordinal, String title,
            Instant createdAt) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        insert into knowledge_extraction_unit (
                          id, artifact_id, organization_id, ordinal, kind, title, content_text,
                          provenance, created_at)
                        values (:id, :artifactId, :organizationId, :ordinal, 'HEADING_SECTION',
                          :title, :content, '{"memberNodeIds":[]}'::jsonb, :createdAt)
                        """)
                .param("id", id)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .param("ordinal", ordinal)
                .param("title", title)
                .param("content", title + " content")
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .update();
        return id;
    }

    private void insertCandidate(UUID id, UUID artifactId, UUID organizationId, UUID unitId,
            String rawLabel, String contentText, String concept, String topics,
            Instant createdAt) {
        jdbc.sql("""
                        insert into knowledge_candidate (
                          id, artifact_id, organization_id, extraction_unit_id, candidate_type,
                          review_status, raw_label, content_text, payload, legal_concept_key,
                          topic_keys, declared_contract_types, conflict_signals, created_at,
                          updated_at)
                        values (:id, :artifactId, :organizationId, :unitId, 'PLAYBOOK_RULE',
                          'PUBLISHED', :rawLabel, :contentText, '{}'::jsonb, :concept,
                          cast(:topics as text[]), '[]'::jsonb, '[]'::jsonb, :createdAt, :createdAt)
                        """)
                .param("id", id)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .param("unitId", unitId)
                .param("rawLabel", rawLabel)
                .param("contentText", contentText)
                .param("concept", concept)
                .param("topics", topics)
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .update();
    }

    private void insertPublication(UUID organizationId, Instant createdAt, UUID... candidateIds) {
        var publicationId = UUID.randomUUID();
        jdbc.sql("""
                        insert into knowledge_publication (
                          id, organization_id, publication_number, note, created_at)
                        values (:id, :organizationId, 1, null, :createdAt)
                        """)
                .param("id", publicationId)
                .param("organizationId", organizationId)
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .update();
        for (var candidateId : candidateIds) {
            jdbc.sql("""
                            insert into knowledge_publication_item (
                              id, publication_id, organization_id, candidate_id,
                              superseded_candidate_id, action, created_at)
                            values (:id, :publicationId, :organizationId, :candidateId, null,
                              'ADD', :createdAt)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("publicationId", publicationId)
                    .param("organizationId", organizationId)
                    .param("candidateId", candidateId)
                    .param("createdAt", java.sql.Timestamp.from(createdAt))
                    .update();
        }
    }

    private void insertIndexRow(UUID organizationId, UUID candidateId, String contentHash) {
        jdbc.sql("""
                        insert into retrieval_evidence_index (
                          id, organization_id, candidate_id, artifact_id, candidate_type,
                          legal_concept_key, content_hash, index_version, lexical_tsvector,
                          lexical_config, embedding, embedding_model, embedding_dims,
                          embedding_index_ready, publication_number, declared_contract_types,
                          indexed_at, updated_at)
                        values (:id, :organizationId, :candidateId, :artifactId, 'PLAYBOOK_RULE',
                          null, :contentHash, :indexVersion, to_tsvector('english', 'alpha'),
                          'english', cast('[1,0]' as vector), 'test-model', 2, true, 1,
                          '[]'::jsonb, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", organizationId)
                .param("candidateId", candidateId)
                .param("artifactId", UUID.randomUUID())
                .param("contentHash", contentHash)
                .param("indexVersion", properties.indexVersion())
                .update();
    }
}
