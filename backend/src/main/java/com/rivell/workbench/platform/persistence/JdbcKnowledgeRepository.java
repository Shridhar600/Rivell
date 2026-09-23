package com.rivell.workbench.platform.persistence;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.knowledge.ApplicabilityScope;
import com.rivell.workbench.knowledge.ArtifactManifest;
import com.rivell.workbench.knowledge.ArtifactType;
import com.rivell.workbench.knowledge.CandidatePayload;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.ConflictSignal;
import com.rivell.workbench.knowledge.ConflictType;
import com.rivell.workbench.knowledge.ExtractionUnit;
import com.rivell.workbench.knowledge.ExtractionUnitKind;
import com.rivell.workbench.knowledge.IngestStatus;
import com.rivell.workbench.knowledge.KnowledgeArtifact;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.KnowledgeNodeType;
import com.rivell.workbench.knowledge.KnowledgePublication;
import com.rivell.workbench.knowledge.KnowledgeRepository;
import com.rivell.workbench.knowledge.KnowledgeSource;
import com.rivell.workbench.knowledge.KnowledgeSourceNode;
import com.rivell.workbench.knowledge.MergedCellRegion;
import com.rivell.workbench.knowledge.PublicationAction;
import com.rivell.workbench.knowledge.PublicationItem;
import com.rivell.workbench.knowledge.ReviewStatus;
import java.util.LinkedHashMap;

@Repository
class JdbcKnowledgeRepository implements KnowledgeRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    JdbcKnowledgeRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void insertArtifact(KnowledgeArtifact artifact) {
        jdbcClient.sql("""
                        insert into knowledge_artifact
                        (id, organization_id, artifact_type, filename, content_type, size_bytes,
                         sha256, storage_key, declared_contract_types, contract_pack_key,
                         party_perspective, paper_source, jurisdiction, language, effective_from,
                         effective_to, manifest_note, ingest_status, parser_version, parse_warnings,
                         created_at)
                        values (:id, :organizationId, :artifactType, :filename, :contentType,
                                :sizeBytes, :sha256, :storageKey, :declaredContractTypes::jsonb,
                                :contractPackKey, :partyPerspective, :paperSource, :jurisdiction,
                                :language, :effectiveFrom, :effectiveTo, :manifestNote,
                                :ingestStatus, :parserVersion, :parseWarnings::jsonb, :createdAt)
                        """)
                .param("id", artifact.id())
                .param("organizationId", artifact.organizationId())
                .param("artifactType", artifact.artifactType().name())
                .param("filename", artifact.filename())
                .param("contentType", artifact.contentType())
                .param("sizeBytes", artifact.sizeBytes())
                .param("sha256", artifact.sha256())
                .param("storageKey", artifact.storageKey())
                .param("declaredContractTypes", writeStringSet(artifact.manifest().declaredContractTypes()))
                .param("contractPackKey", artifact.manifest().contractPackKey())
                .param("partyPerspective", artifact.manifest().partyPerspective())
                .param("paperSource", artifact.manifest().paperSource())
                .param("jurisdiction", artifact.manifest().jurisdiction())
                .param("language", artifact.manifest().language())
                .param("effectiveFrom", artifact.manifest().effectiveFrom())
                .param("effectiveTo", artifact.manifest().effectiveTo())
                .param("manifestNote", artifact.manifest().note())
                .param("ingestStatus", artifact.ingestStatus().name())
                .param("parserVersion", artifact.parserVersion())
                .param("parseWarnings", writeList(artifact.parseWarnings()))
                .param("createdAt", Timestamp.from(artifact.createdAt()))
                .update();
    }

    @Override
    @Transactional
    public void updateIngestOutcome(
            UUID artifactId, String parserVersion, IngestStatus status, List<String> warnings) {
        jdbcClient.sql("""
                        update knowledge_artifact
                        set parser_version = :parserVersion, ingest_status = :status,
                            parse_warnings = :warnings::jsonb
                        where id = :id
                        """)
                .param("parserVersion", parserVersion)
                .param("status", status.name())
                .param("warnings", writeList(warnings))
                .param("id", artifactId)
                .update();
    }

    @Override
    public Optional<KnowledgeArtifact> findArtifact(UUID artifactId, UUID organizationId) {
        return jdbcClient.sql("""
                        select * from knowledge_artifact
                        where id = :id and organization_id = :organizationId
                        """)
                .param("id", artifactId)
                .param("organizationId", organizationId)
                .query(this::mapArtifact)
                .optional();
    }

    @Override
    public List<KnowledgeArtifact> listArtifacts(UUID organizationId) {
        return jdbcClient.sql("""
                        select * from knowledge_artifact
                        where organization_id = :organizationId
                        order by created_at desc
                        """)
                .param("organizationId", organizationId)
                .query(this::mapArtifact)
                .list();
    }

    @Override
    public Optional<KnowledgeSource> findSource(UUID artifactId, UUID organizationId) {
        return jdbcClient.sql("""
                        select filename, content_type, sha256, storage_key
                        from knowledge_artifact
                        where id = :id and organization_id = :organizationId
                        """)
                .param("id", artifactId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new KnowledgeSource(
                        rs.getString("filename"),
                        rs.getString("content_type"),
                        rs.getString("sha256"),
                        rs.getString("storage_key")))
                .optional();
    }

    @Override
    @Transactional
    public void insertNodes(UUID artifactId, List<KnowledgeSourceNode> nodes) {
        for (KnowledgeSourceNode node : nodes) {
            jdbcClient.sql("""
                            insert into knowledge_source_node
                            (id, artifact_id, organization_id, parent_id, node_type, ordinal, text,
                             heading_level, numbering_label, part_path, body_index, paragraph_index,
                             table_index, row_index, cell_index, run_start, run_end, sheet_name,
                             column_index, merged_region, raw_style_id)
                            values (:id, :artifactId, (select organization_id from knowledge_artifact
                                             where id = :artifactId),
                                    :parentId, :nodeType, :ordinal, :text, :headingLevel,
                                    :numberingLabel, :partPath, :bodyIndex, :paragraphIndex,
                                    :tableIndex, :rowIndex, :cellIndex, :runStart, :runEnd,
                                    :sheetName, :columnIndex, :mergedRegion::jsonb, :rawStyleId)
                            """)
                    .param("id", node.id())
                    .param("artifactId", artifactId)
                    .param("parentId", node.parentId())
                    .param("nodeType", node.nodeType().name())
                    .param("ordinal", node.ordinal())
                    .param("text", node.text())
                    .param("headingLevel", node.headingLevel())
                    .param("numberingLabel", node.numberingLabel())
                    .param("partPath", node.partPath())
                    .param("bodyIndex", node.bodyIndex())
                    .param("paragraphIndex", node.paragraphIndex())
                    .param("tableIndex", node.tableIndex())
                    .param("rowIndex", node.rowIndex())
                    .param("cellIndex", node.cellIndex())
                    .param("runStart", node.runStart())
                    .param("runEnd", node.runEnd())
                    .param("sheetName", node.sheetName())
                    .param("columnIndex", node.columnIndex())
                    .param("mergedRegion", writeMergedRegion(node.mergedRegion()))
                    .param("rawStyleId", node.rawStyleId())
                    .update();
        }
    }

    @Override
    @Transactional
    public void insertUnits(UUID artifactId, List<ExtractionUnit> units) {
        for (ExtractionUnit unit : units) {
            jdbcClient.sql("""
                            insert into knowledge_extraction_unit
                            (id, artifact_id, organization_id, ordinal, kind, title, content_text,
                             provenance, created_at)
                            values (:id, :artifactId,
                                    (select organization_id from knowledge_artifact where id = :artifactId),
                                    :ordinal, :kind, :title, :contentText, :provenance::jsonb, now())
                            """)
                    .param("id", unit.id())
                    .param("artifactId", artifactId)
                    .param("ordinal", unit.ordinal())
                    .param("kind", unit.kind().name())
                    .param("title", unit.title())
                    .param("contentText", unit.contentText())
                    .param("provenance", writeUuidList(unit.sourceNodeIds()))
                    .update();
        }
    }

    @Override
    @Transactional
    public void insertCandidates(UUID artifactId, List<KnowledgeCandidate> candidates) {
        for (KnowledgeCandidate candidate : candidates) {
            jdbcClient.sql("""
                            insert into knowledge_candidate
                            (id, artifact_id, organization_id, extraction_unit_id, candidate_type,
                             review_status, raw_label, content_text, payload, legal_concept_key,
                             edited_content_text, edited_payload, superseding_candidate_id,
                             contract_pack_key, declared_contract_types, party_perspective,
                             paper_source, jurisdiction, effective_from, effective_to,
                             conflict_signals, review_note, reviewed_at, created_at, updated_at)
                            values (:id, :artifactId,
                                    (select organization_id from knowledge_artifact where id = :artifactId),
                                    :extractionUnitId, :candidateType, :reviewStatus, :rawLabel,
                                    :contentText, :payload::jsonb, :legalConceptKey,
                                    :editedContentText, :editedPayload::jsonb, :supersedingCandidateId,
                                    :contractPackKey, :declaredContractTypes::jsonb,
                                    :partyPerspective, :paperSource, :jurisdiction, :effectiveFrom,
                                    :effectiveTo, :conflictSignals::jsonb, :reviewNote, :reviewedAt,
                                    :createdAt, :updatedAt)
                            """)
                    .param("id", candidate.id())
                    .param("artifactId", artifactId)
                    .param("extractionUnitId", candidate.extractionUnitId())
                    .param("candidateType", candidate.candidateType().name())
                    .param("reviewStatus", candidate.reviewStatus().name())
                    .param("rawLabel", candidate.rawLabel())
                    .param("contentText", candidate.contentText())
                    .param("payload", writePayload(candidate.payload()))
                    .param("legalConceptKey", candidate.legalConceptKey())
                    .param("editedContentText", candidate.editedContentText())
                    .param("editedPayload", writePayload(candidate.editedPayload()))
                    .param("supersedingCandidateId", candidate.supersedingCandidateId())
                    .param("contractPackKey", candidate.scope().contractPackKey())
                    .param("declaredContractTypes", writeStringSet(candidate.scope().declaredContractTypes()))
                    .param("partyPerspective", candidate.scope().partyPerspective())
                    .param("paperSource", candidate.scope().paperSource())
                    .param("jurisdiction", candidate.scope().jurisdiction())
                    .param("effectiveFrom", candidate.scope().effectiveFrom())
                    .param("effectiveTo", candidate.scope().effectiveTo())
                    .param("conflictSignals", writeConflictSignals(candidate.conflictSignals()))
                    .param("reviewNote", candidate.reviewNote())
                    .param("reviewedAt", toTimestamp(candidate.reviewedAt()))
                    .param("createdAt", Timestamp.from(candidate.createdAt()))
                    .param("updatedAt", Timestamp.from(candidate.updatedAt()))
                    .update();
        }
    }

    @Override
    public List<KnowledgeSourceNode> loadStructure(UUID artifactId, UUID organizationId) {
        return jdbcClient.sql("""
                        with recursive tree as (
                            select n.*, array[n.ordinal] as sort_path
                            from knowledge_source_node n
                            where n.artifact_id = :artifactId and n.parent_id is null
                            union all
                            select n.*, t.sort_path || n.ordinal
                            from knowledge_source_node n
                            join tree t on n.parent_id = t.id
                        )
                        select n.* from tree n
                        order by sort_path
                        """)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .query(this::mapNode)
                .list();
    }

    @Override
    public List<ExtractionUnit> loadUnits(UUID artifactId, UUID organizationId) {
        return jdbcClient.sql("""
                        select u.* from knowledge_extraction_unit u
                        join knowledge_artifact a on a.id = u.artifact_id
                        where u.artifact_id = :artifactId and a.organization_id = :organizationId
                        order by u.ordinal
                        """)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .query(this::mapUnit)
                .list();
    }

    @Override
    public Optional<ExtractionUnit> findUnit(UUID unitId, UUID organizationId) {
        return jdbcClient.sql("""
                        select u.* from knowledge_extraction_unit u
                        join knowledge_artifact a on a.id = u.artifact_id
                        where u.id = :id and a.organization_id = :organizationId
                        """)
                .param("id", unitId)
                .param("organizationId", organizationId)
                .query(this::mapUnit)
                .optional();
    }

    @Override
    public List<KnowledgeCandidate> loadCandidates(UUID artifactId, UUID organizationId) {
        return jdbcClient.sql("""
                        select c.* from knowledge_candidate c
                        join knowledge_artifact a on a.id = c.artifact_id
                        where c.artifact_id = :artifactId and a.organization_id = :organizationId
                        order by c.created_at, c.id
                        """)
                .param("artifactId", artifactId)
                .param("organizationId", organizationId)
                .query(this::mapCandidate)
                .list();
    }

    @Override
    public Optional<KnowledgeCandidate> findCandidate(UUID candidateId, UUID organizationId) {
        return jdbcClient.sql("""
                        select * from knowledge_candidate
                        where id = :id and organization_id = :organizationId
                        """)
                .param("id", candidateId)
                .param("organizationId", organizationId)
                .query(this::mapCandidate)
                .optional();
    }

    @Override
    @Transactional
    public void updateReviewState(
            UUID candidateId, ReviewStatus reviewStatus, String editedContentText,
            CandidatePayload editedPayload, String reviewNote, Instant reviewedAt, Instant updatedAt) {
        jdbcClient.sql("""
                        update knowledge_candidate
                        set review_status = :reviewStatus, edited_content_text = :editedContentText,
                            edited_payload = :editedPayload::jsonb, review_note = :reviewNote,
                            reviewed_at = :reviewedAt, updated_at = :updatedAt
                        where id = :id
                        """)
                .param("reviewStatus", reviewStatus.name())
                .param("editedContentText", editedContentText)
                .param("editedPayload", writePayload(editedPayload))
                .param("reviewNote", reviewNote)
                .param("reviewedAt", toTimestamp(reviewedAt))
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("id", candidateId)
                .update();
    }

    @Override
    public List<KnowledgeCandidate> findPublishedCandidates(UUID organizationId) {
        return jdbcClient.sql("""
                        select * from knowledge_candidate
                        where organization_id = :organizationId and review_status = 'PUBLISHED'
                        order by created_at, id
                        """)
                .param("organizationId", organizationId)
                .query(this::mapCandidate)
                .list();
    }

    @Override
    @Transactional
    public void insertPublication(KnowledgePublication publication) {
        jdbcClient.sql("""
                        insert into knowledge_publication (id, organization_id, publication_number, note, created_at)
                        values (:id, :organizationId, :publicationNumber, :note, :createdAt)
                        """)
                .param("id", publication.id())
                .param("organizationId", publication.organizationId())
                .param("publicationNumber", publication.publicationNumber())
                .param("note", publication.note())
                .param("createdAt", Timestamp.from(publication.createdAt()))
                .update();
        for (PublicationItem item : publication.items()) {
            jdbcClient.sql("""
                            insert into knowledge_publication_item
                            (id, publication_id, organization_id, candidate_id,
                             superseded_candidate_id, action, created_at)
                            values (:id, :publicationId, :organizationId, :candidateId,
                                    :supersededCandidateId, :action, :createdAt)
                            """)
                    .param("id", item.id())
                    .param("publicationId", item.publicationId())
                    .param("organizationId", publication.organizationId())
                    .param("candidateId", item.candidateId())
                    .param("supersededCandidateId", item.supersededCandidateId())
                    .param("action", item.action().name())
                    .param("createdAt", Timestamp.from(publication.createdAt()))
                    .update();
        }
    }

    @Override
    public List<KnowledgePublication> listPublications(UUID organizationId) {
        var publications = jdbcClient.sql("""
                        select * from knowledge_publication
                        where organization_id = :organizationId
                        order by publication_number desc
                        """)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new PublicationRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getLong("publication_number"),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        var result = new ArrayList<KnowledgePublication>(publications.size());
        for (PublicationRow row : publications) {
            var items = jdbcClient.sql("""
                            select * from knowledge_publication_item
                            where publication_id = :publicationId and organization_id = :organizationId
                            order by created_at, id
                            """)
                    .param("publicationId", row.id())
                    .param("organizationId", organizationId)
                    .query(this::mapItem)
                    .list();
            result.add(new KnowledgePublication(row.id(), organizationId, row.number(),
                    row.note(), items, row.createdAt()));
        }
        return result;
    }

    @Override
    public Optional<KnowledgePublication> findPublication(UUID publicationId, UUID organizationId) {
        var row = jdbcClient.sql("""
                        select * from knowledge_publication
                        where id = :id and organization_id = :organizationId
                        """)
                .param("id", publicationId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new PublicationRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getLong("publication_number"),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        var publication = row.get();
        var items = loadPublicationItems(publication.id(), organizationId);
        return Optional.of(new KnowledgePublication(publication.id(), organizationId,
                publication.number(), publication.note(), items, publication.createdAt()));
    }

    @Override
    public List<PublicationItem> loadPublicationItems(UUID publicationId, UUID organizationId) {
        return jdbcClient.sql("""
                        select * from knowledge_publication_item
                        where publication_id = :publicationId and organization_id = :organizationId
                        order by created_at, id
                        """)
                .param("publicationId", publicationId)
                .param("organizationId", organizationId)
                .query(this::mapItem)
                .list();
    }

    @Override
    public long nextPublicationNumber(UUID organizationId) {
        var current = jdbcClient.sql("""
                        select coalesce(max(publication_number), 0)
                        from knowledge_publication where organization_id = :organizationId
                        """)
                .param("organizationId", organizationId)
                .query(Long.class).single();
        return current + 1;
    }

    private KnowledgeArtifact mapArtifact(ResultSet rs, int rowNum) throws SQLException {
        var manifest = new ArtifactManifest(
                readStringSet(rs.getString("declared_contract_types")),
                rs.getString("contract_pack_key"),
                rs.getString("party_perspective"),
                rs.getString("paper_source"),
                rs.getString("jurisdiction"),
                rs.getString("language"),
                readDate(rs, "effective_from"),
                readDate(rs, "effective_to"),
                rs.getString("manifest_note"));
        return new KnowledgeArtifact(
                uuid(rs, "id"),
                uuid(rs, "organization_id"),
                ArtifactType.valueOf(rs.getString("artifact_type")),
                rs.getString("filename"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("storage_key"),
                manifest,
                IngestStatus.valueOf(rs.getString("ingest_status")),
                rs.getString("parser_version"),
                readList(rs.getString("parse_warnings")),
                instant(rs, "created_at"));
    }

    private KnowledgeSourceNode mapNode(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeSourceNode(
                uuid(rs, "id"),
                uuid(rs, "artifact_id"),
                uuid(rs, "parent_id"),
                KnowledgeNodeType.valueOf(rs.getString("node_type")),
                rs.getInt("ordinal"),
                rs.getString("text"),
                (Integer) rs.getObject("heading_level"),
                rs.getString("numbering_label"),
                rs.getString("part_path"),
                rs.getInt("body_index"),
                (Integer) rs.getObject("paragraph_index"),
                (Integer) rs.getObject("table_index"),
                (Integer) rs.getObject("row_index"),
                (Integer) rs.getObject("cell_index"),
                (Integer) rs.getObject("run_start"),
                (Integer) rs.getObject("run_end"),
                rs.getString("sheet_name"),
                (Integer) rs.getObject("column_index"),
                readMergedRegion(rs.getString("merged_region")),
                rs.getString("raw_style_id"));
    }

    private ExtractionUnit mapUnit(ResultSet rs, int rowNum) throws SQLException {
        return new ExtractionUnit(
                uuid(rs, "id"),
                uuid(rs, "artifact_id"),
                rs.getInt("ordinal"),
                ExtractionUnitKind.valueOf(rs.getString("kind")),
                rs.getString("title"),
                rs.getString("content_text"),
                readStringSet(rs.getString("provenance")).stream().map(UUID::fromString).toList());
    }

    private KnowledgeCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        var scope = new ApplicabilityScope(
                rs.getString("contract_pack_key"),
                readStringSet(rs.getString("declared_contract_types")),
                rs.getString("party_perspective"),
                rs.getString("paper_source"),
                rs.getString("jurisdiction"),
                readDate(rs, "effective_from"),
                readDate(rs, "effective_to"));
        return new KnowledgeCandidate(
                uuid(rs, "id"),
                uuid(rs, "artifact_id"),
                uuid(rs, "organization_id"),
                uuid(rs, "extraction_unit_id"),
                CandidateType.valueOf(rs.getString("candidate_type")),
                ReviewStatus.valueOf(rs.getString("review_status")),
                rs.getString("raw_label"),
                rs.getString("content_text"),
                readPayload(rs.getString("payload")),
                rs.getString("legal_concept_key"),
                rs.getString("edited_content_text"),
                readPayload(rs.getString("edited_payload")),
                uuid(rs, "superseding_candidate_id"),
                scope,
                readConflictSignals(rs.getString("conflict_signals")),
                rs.getString("review_note"),
                instant(rs, "reviewed_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private PublicationItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new PublicationItem(
                uuid(rs, "id"),
                uuid(rs, "publication_id"),
                uuid(rs, "candidate_id"),
                uuid(rs, "superseded_candidate_id"),
                PublicationAction.valueOf(rs.getString("action")));
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        var value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static LocalDate readDate(ResultSet rs, String column) throws SQLException {
        var value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private String writeStringSet(Set<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : List.copyOf(values));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize string collection", e);
        }
    }

    private Set<String> readStringSet(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(Set.class, String.class));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read string collection", e);
        }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read string list", e);
        }
    }

private String writeUuidList(List<UUID> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values.stream().map(UUID::toString).toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize id list", e);
        }
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize string list", e);
        }
    }

    private String writePayload(CandidatePayload payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? CandidatePayload.EMPTY : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize candidate payload", e);
        }
    }

    private CandidatePayload readPayload(String json) {
        if (json == null || json.isBlank()) {
            return CandidatePayload.EMPTY;
        }
        try {
            var node = objectMapper.readTree(json);
            var columns = new LinkedHashMap<String, String>();
            var source = node.has("columns") && node.get("columns").isObject()
                    ? node.get("columns")
                    : node;
            var fields = source.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.getValue().isTextual()) {
                    columns.put(field.getKey(), field.getValue().asText());
                }
            }
            return new CandidatePayload(columns);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read candidate payload", e);
        }
    }

    private String writeConflictSignals(List<ConflictSignal> signals) {
        try {
            var array = objectMapper.createArrayNode();
            for (ConflictSignal signal : signals) {
                var node = array.addObject();
                node.put("conflictType", signal.conflictType().name());
                node.put("message", signal.message());
                if (signal.publishedCandidateId() != null) {
                    node.put("publishedCandidateId", signal.publishedCandidateId().toString());
                }
            }
            return objectMapper.writeValueAsString(array);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize conflict signals", e);
        }
    }

    private List<ConflictSignal> readConflictSignals(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            var array = objectMapper.readTree(json);
            var signals = new ArrayList<ConflictSignal>(array.size());
            for (var element : array) {
                signals.add(new ConflictSignal(
                        ConflictType.valueOf(element.get("conflictType").asText()),
                        element.get("message").asText(),
                        element.hasNonNull("publishedCandidateId")
                                ? UUID.fromString(element.get("publishedCandidateId").asText())
                                : null));
            }
            return signals;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read conflict signals", e);
        }
    }

    private String writeMergedRegion(MergedCellRegion region) {
        if (region == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(region);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize merged region", e);
        }
    }

    private MergedCellRegion readMergedRegion(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MergedCellRegion.class);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read merged region", e);
        }
    }

    private record PublicationRow(UUID id, long number, String note, Instant createdAt) {
    }
}
