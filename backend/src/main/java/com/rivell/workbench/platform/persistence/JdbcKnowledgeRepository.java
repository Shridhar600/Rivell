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
import java.util.HashMap;
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
import com.rivell.workbench.knowledge.ArtifactTypeAssessment;
import com.rivell.workbench.knowledge.CandidatePayload;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.ConflictSignal;
import com.rivell.workbench.knowledge.ConflictType;
import com.rivell.workbench.knowledge.FieldInterpretation;
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
import com.rivell.workbench.knowledge.PublishedKnowledgeQuery;
import com.rivell.workbench.knowledge.ReviewCategory;
import com.rivell.workbench.knowledge.ReviewReason;
import com.rivell.workbench.knowledge.ReviewStatus;
import com.rivell.workbench.knowledge.SemanticFields;
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
                         artifact_assessment, created_at)
                        values (:id, :organizationId, :artifactType, :filename, :contentType,
                                :sizeBytes, :sha256, :storageKey, :declaredContractTypes::jsonb,
                                :contractPackKey, :partyPerspective, :paperSource, :jurisdiction,
                                :language, :effectiveFrom, :effectiveTo, :manifestNote,
                                :ingestStatus, :parserVersion, :parseWarnings::jsonb,
                                :artifactAssessment::jsonb, :createdAt)
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
                .param("artifactAssessment", writeArtifactAssessment(artifact.assessment()))
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
    @Transactional
    public void updateArtifactAssessment(UUID artifactId, ArtifactTypeAssessment assessment) {
        jdbcClient.sql("""
                        update knowledge_artifact
                        set artifact_assessment = :assessment::jsonb
                        where id = :id
                        """)
                .param("assessment", writeArtifactAssessment(assessment))
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
    public List<KnowledgeArtifact> listArtifacts(UUID organizationId, Integer limit) {
        var sql = new StringBuilder("""
                select * from knowledge_artifact
                where organization_id = :organizationId
                """);
        sql.append(" order by created_at desc, id desc");
        if (limit != null) {
            sql.append(" limit :limit");
        }
        var client = jdbcClient.sql(sql.toString())
                .param("organizationId", organizationId);
        if (limit != null) {
            client = client.param("limit", limit);
        }
        return client.query(this::mapArtifact).list();
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
                             semantic_fields, edited_semantic_fields, interpretations,
                             edited_content_text, edited_payload, superseding_candidate_id,
                             contract_pack_key, declared_contract_types, party_perspective,
                             paper_source, jurisdiction, effective_from, effective_to,
                             conflict_signals, review_category, review_reasons,
                             review_note, reviewed_at, created_at, updated_at)
                            values (:id, :artifactId,
                                    (select organization_id from knowledge_artifact where id = :artifactId),
                                    :extractionUnitId, :candidateType, :reviewStatus, :rawLabel,
                                    :contentText, :payload::jsonb, :legalConceptKey,
                                    :semanticFields::jsonb, :editedSemanticFields::jsonb,
                                    :interpretations::jsonb,
                                    :editedContentText, :editedPayload::jsonb, :supersedingCandidateId,
                                    :contractPackKey, :declaredContractTypes::jsonb,
                                    :partyPerspective, :paperSource, :jurisdiction, :effectiveFrom,
                                    :effectiveTo, :conflictSignals::jsonb, :reviewCategory,
                                    :reviewReasons::jsonb, :reviewNote, :reviewedAt,
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
                    .param("semanticFields", writeSemanticFields(candidate.semanticFields()))
                    .param("editedSemanticFields", candidate.editedSemanticFields() == null
                            ? null : writeSemanticFields(candidate.editedSemanticFields()))
                    .param("interpretations", writeInterpretations(candidate.interpretations()))
                    .param("reviewCategory", candidate.reviewCategory() == null
                            ? null : candidate.reviewCategory().name())
                    .param("reviewReasons", writeReviewReasons(candidate.reviewReasons()))
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
    public boolean updateConceptCorrection(UUID candidateId, UUID organizationId,
            KnowledgeCandidate updated, ReviewStatus expectedStatus,
            UUID correctedBy, java.time.Instant updatedAt) {
        return jdbcClient.sql("""
                        update knowledge_candidate
                        set concept_correction = :conceptCorrection,
                            concept_corrected_by = :correctedBy,
                            concept_corrected_at = :correctedAt,
                            review_status = :reviewStatus,
                            conflict_signals = :conflictSignals::jsonb,
                            review_category = :reviewCategory,
                            review_reasons = :reviewReasons::jsonb,
                            updated_at = :updatedAt
                        where id = :id and organization_id = :organizationId
                          and review_status = :expectedStatus
                        """)
                .param("conceptCorrection", updated.conceptCorrection())
                .param("correctedBy", correctedBy)
                .param("correctedAt", toTimestamp(updatedAt))
                .param("reviewStatus", updated.reviewStatus().name())
                .param("conflictSignals", writeConflictSignals(updated.conflictSignals()))
                .param("reviewCategory", updated.reviewCategory() == null
                        ? null : updated.reviewCategory().name())
                .param("reviewReasons", writeReviewReasons(updated.reviewReasons()))
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("id", candidateId)
                .param("organizationId", organizationId)
                .param("expectedStatus", expectedStatus.name())
                .update() == 1;
    }

    @Override
    @Transactional
    public void updateReviewState(
            UUID candidateId, UUID organizationId, ReviewStatus expectedStatus,
            ReviewStatus reviewStatus, String editedContentText,
            CandidatePayload editedPayload, SemanticFields editedSemanticFields,
            ApplicabilityScope scope, UUID supersedingCandidateId, String reviewNote,
            Instant reviewedAt, Instant updatedAt,
            List<ConflictSignal> conflictSignals, ReviewCategory reviewCategory,
            Set<ReviewReason> reviewReasons) {
        jdbcClient.sql("""
                        update knowledge_candidate
                        set review_status = :reviewStatus, edited_content_text = :editedContentText,
                            edited_payload = :editedPayload::jsonb,
                            edited_semantic_fields = :editedSemanticFields::jsonb,
                            superseding_candidate_id = :supersedingCandidateId,
                            contract_pack_key = :contractPackKey,
                            declared_contract_types = :declaredContractTypes::jsonb,
                            party_perspective = :partyPerspective, paper_source = :paperSource,
                            jurisdiction = :jurisdiction, effective_from = :effectiveFrom,
                            effective_to = :effectiveTo,
                            conflict_signals = :conflictSignals::jsonb,
                            review_category = :reviewCategory,
                            review_reasons = :reviewReasons::jsonb,
                            review_note = :reviewNote,
                            reviewed_at = :reviewedAt, updated_at = :updatedAt
                        where id = :id and organization_id = :organizationId
                          and review_status = :expectedStatus
                        """)
                .param("reviewStatus", reviewStatus.name())
                .param("editedContentText", editedContentText)
                .param("editedPayload", writePayload(editedPayload))
                .param("editedSemanticFields", writeNullableSemanticFields(editedSemanticFields))
                .param("contractPackKey", scope.contractPackKey())
                .param("declaredContractTypes", writeStringSet(scope.declaredContractTypes()))
                .param("partyPerspective", scope.partyPerspective())
                .param("paperSource", scope.paperSource())
                .param("jurisdiction", scope.jurisdiction())
                .param("effectiveFrom", scope.effectiveFrom())
                .param("effectiveTo", scope.effectiveTo())
                .param("supersedingCandidateId", supersedingCandidateId)
                .param("reviewNote", reviewNote)
                .param("reviewedAt", toTimestamp(reviewedAt))
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("conflictSignals", writeConflictSignals(conflictSignals))
                .param("reviewCategory", reviewCategory == null ? null : reviewCategory.name())
                .param("reviewReasons", writeReviewReasons(reviewReasons))
                .param("id", candidateId)
                .param("organizationId", organizationId)
                .param("expectedStatus", expectedStatus.name())
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
    public List<com.rivell.workbench.knowledge.PublishedCandidate> findPublishedCandidatesAsOf(
            PublishedKnowledgeQuery query) {
        var conditions = new ArrayList<String>();
        var params = new HashMap<String, Object>();
        conditions.add("c.organization_id = :organizationId");
        params.put("organizationId", query.organizationId());
        conditions.add("c.review_status = 'PUBLISHED'");
        if (!query.candidateTypes().isEmpty()) {
            conditions.add("c.candidate_type = any(:candidateTypes)");
            params.put("candidateTypes",
                    query.candidateTypes().stream().map(Enum::name).toArray(String[]::new));
        }
        if (query.legalConceptKey() != null) {
            conditions.add("c.effective_concept_key = :legalConceptKey");
            params.put("legalConceptKey", query.legalConceptKey());
        }
        if (query.contractPackKey() != null) {
            conditions.add("(c.contract_pack_key is null or regexp_replace(lower(c.contract_pack_key), '[^a-z0-9]', '', 'g')"
                    + " = regexp_replace(lower(:contractPackKey), '[^a-z0-9]', '', 'g'))");
            params.put("contractPackKey", query.contractPackKey());
        }
        if (query.contractType() != null) {
            conditions.add("(c.declared_contract_types = '[]'::jsonb"
                    + " or exists (select 1 from jsonb_array_elements_text(c.declared_contract_types)"
                    + " as dt where regexp_replace(lower(dt), '[^a-z0-9]', '', 'g') = regexp_replace(lower(:contractType), '[^a-z0-9]', '', 'g')))");
            params.put("contractType", query.contractType());
        }
        if (query.partyPerspective() != null) {
            conditions.add("(c.party_perspective is null or regexp_replace(lower(c.party_perspective), '[^a-z0-9]', '', 'g')"
                    + " = regexp_replace(lower(:partyPerspective), '[^a-z0-9]', '', 'g'))");
            params.put("partyPerspective", query.partyPerspective());
        }
        if (query.paperSource() != null) {
            conditions.add("(c.paper_source is null or regexp_replace(lower(c.paper_source), '[^a-z0-9]', '', 'g')"
                    + " = regexp_replace(lower(:paperSource), '[^a-z0-9]', '', 'g'))");
            params.put("paperSource", query.paperSource());
        }
        if (query.jurisdiction() != null) {
            conditions.add("(c.jurisdiction is null or regexp_replace(lower(c.jurisdiction), '[^a-z0-9]', '', 'g')"
                    + " = regexp_replace(lower(:jurisdiction), '[^a-z0-9]', '', 'g'))");
            params.put("jurisdiction", query.jurisdiction());
        }
        if (query.effectiveOn() != null) {
            conditions.add("(c.effective_from is null or c.effective_from <= :effectiveOn)");
            conditions.add("(c.effective_to is null or c.effective_to >= :effectiveOn)");
            params.put("effectiveOn", java.sql.Date.valueOf(query.effectiveOn()));
        }
        conditions.add("""
                not exists (
                    select 1 from knowledge_publication_item sup_item
                    join knowledge_publication sp
                         on sp.id = sup_item.publication_id
                        and sp.organization_id = c.organization_id
                    where sup_item.organization_id = c.organization_id
                      and sup_item.action = 'SUPERSEDE'
                      and sup_item.superseded_candidate_id = c.id
                      and sp.publication_number <= :asOf)
                """);
        params.put("asOf", query.asOfPublicationNumber());
        // a candidate has at most one ADD publication (DB-enforced), so this join
        // cannot fan out; it both constrains pin visibility and exposes the
        // publication identity for provenance
        var from = " from knowledge_candidate c"
                + " join knowledge_publication_item add_item"
                + " on add_item.candidate_id = c.id"
                + " and add_item.action = 'ADD'"
                + " and add_item.organization_id = c.organization_id"
                + " join knowledge_publication pp"
                + " on pp.id = add_item.publication_id"
                + " and pp.organization_id = c.organization_id"
                + " and pp.publication_number <= :asOf ";
        var sql = "select c.*, pp.id as publication_id, pp.publication_number as publication_number"
                + from + " where "
                + String.join(" and ", conditions)
                + " order by c.created_at desc, c.id desc limit :limit";
        var statement = jdbcClient.sql(sql);
        for (var entry : params.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        return statement
                .param("limit", query.limit() + 1)
                .query((rs, rowNum) -> new com.rivell.workbench.knowledge.PublishedCandidate(
                        mapCandidate(rs, rowNum),
                        rs.getObject("publication_id", UUID.class),
                        rs.getLong("publication_number")))
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
    public List<UUID> organizationsWithPublishedKnowledge() {
        return jdbcClient.sql("""
                        select distinct organization_id from knowledge_publication
                        order by organization_id
                        """)
                .query((rs, rowNum) -> rs.getObject("organization_id", UUID.class))
                .list();
    }

    @Override
    public long maxPublicationNumber(UUID organizationId) {
        var max = jdbcClient.sql("""
                        select coalesce(max(publication_number), 0)
                        from knowledge_publication
                        where organization_id = :organizationId
                        """)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> rs.getLong(1))
                .optional()
                .orElse(0L);
        return max;
    }

    @Override
    public boolean publicationNumberExists(UUID organizationId, long publicationNumber) {
        return jdbcClient.sql("""
                        select 1 from knowledge_publication
                        where organization_id = :organizationId
                          and publication_number = :publicationNumber
                        """)
                .param("organizationId", organizationId)
                .param("publicationNumber", publicationNumber)
                .query((rs, rowNum) -> true)
                .optional()
                .isPresent();
    }

    @Override
    public List<KnowledgePublication> listPublications(UUID organizationId, Integer limit) {
        var sql = new StringBuilder("""
                select * from knowledge_publication
                where organization_id = :organizationId
                """);
        sql.append(" order by publication_number desc");
        if (limit != null) {
            sql.append(" limit :limit");
        }
        var publicationClient = jdbcClient.sql(sql.toString())
                .param("organizationId", organizationId);
        if (limit != null) {
            publicationClient = publicationClient.param("limit", limit);
        }
        var publications = publicationClient
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
                readArtifactAssessment(rs.getString("artifact_assessment")),
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
                rs.getString("concept_correction"),
                uuid(rs, "concept_corrected_by"),
                instant(rs, "concept_corrected_at"),
                readSemanticFields(rs.getString("semantic_fields")),
                readSemanticFields(rs.getString("edited_semantic_fields")),
                readInterpretations(rs.getString("interpretations")),
                rs.getString("edited_content_text"),
                readPayload(rs.getString("edited_payload")),
                uuid(rs, "superseding_candidate_id"),
                scope,
                readConflictSignals(rs.getString("conflict_signals")),
                readReviewCategory(rs.getString("review_category")),
                readReviewReasons(rs.getString("review_reasons")),
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
            var columnOrder = new java.util.ArrayList<String>();
            var recorded = node.get("columnOrder");
            if (recorded != null && recorded.isArray()) {
                for (var element : recorded) {
                    if (element.isTextual()) {
                        columnOrder.add(element.asText());
                    }
                }
            }
            return new CandidatePayload(columns, columnOrder);
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
                var type = resolveConflictType(element.path("conflictType").asText(null));
                if (type == null) {
                    continue;
                }
                signals.add(new ConflictSignal(
                        type,
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

    private ConflictType resolveConflictType(String value) {
        if (value == null) {
            return ConflictType.UNKNOWN;
        }
        try {
            return ConflictType.valueOf(value);
        } catch (IllegalArgumentException e) {
            org.slf4j.LoggerFactory.getLogger(JdbcKnowledgeRepository.class)
                    .warn("Unknown stored conflict type {} — advisory signal kept as UNKNOWN", value);
            return ConflictType.UNKNOWN;
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

    private String writeNullableSemanticFields(SemanticFields fields) {
        return fields == null ? null : writeSemanticFields(fields);
    }

    private String writeSemanticFields(SemanticFields fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize semantic fields", e);
        }
    }

    private SemanticFields readSemanticFields(String json) {
        if (json == null || json.isBlank()) {
            return SemanticFields.EMPTY;
        }
        try {
            return objectMapper.readValue(json, SemanticFields.class);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read semantic fields", e);
        }
    }

    private String writeInterpretations(List<FieldInterpretation> interpretations) {
        try {
            return objectMapper.writeValueAsString(interpretations);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize interpretations", e);
        }
    }

    private List<FieldInterpretation> readInterpretations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, FieldInterpretation.class));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read interpretations", e);
        }
    }

    private String writeReviewReasons(java.util.Set<ReviewReason> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons.stream().map(Enum::name).toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize review reasons", e);
        }
    }

    private java.util.Set<ReviewReason> readReviewReasons(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Set.of();
        }
        try {
            @SuppressWarnings("unchecked")
            java.util.List<String> names = (java.util.List<String>) objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
            return names.stream().map(ReviewReason::valueOf)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read review reasons", e);
        }
    }

    private ReviewCategory readReviewCategory(String value) {
        return value == null ? null : ReviewCategory.valueOf(value);
    }

    private String writeArtifactAssessment(ArtifactTypeAssessment assessment) {
        if (assessment == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "assessedType", assessment.assessedType().name(),
                    "probability", assessment.probability(),
                    "state", assessment.state().name(),
                    "specId", assessment.specId(),
                    "specVersion", assessment.specVersion(),
                    "executionId", assessment.executionId().toString(),
                    "warnings", assessment.warnings()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize artifact assessment", e);
        }
    }

    private ArtifactTypeAssessment readArtifactAssessment(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(json);
            return new ArtifactTypeAssessment(
                    ArtifactType.valueOf(node.get("assessedType").asText()),
                    node.get("probability").asDouble(),
                    ArtifactTypeAssessment.AssessmentState.valueOf(node.get("state").asText()),
                    node.get("specId").asText(),
                    node.get("specVersion").asInt(),
                    UUID.fromString(node.get("executionId").asText()),
                    readList(node.get("warnings").toString()));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read artifact assessment", e);
        }
    }

    private record PublicationRow(UUID id, long number, String note, Instant createdAt) {
    }
}
