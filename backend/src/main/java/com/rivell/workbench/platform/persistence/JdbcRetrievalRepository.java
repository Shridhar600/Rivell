package com.rivell.workbench.platform.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.retrieval.KnowledgePin;
import com.rivell.workbench.retrieval.RetrievalRepository;
import com.rivell.workbench.retrieval.RetrievalTrace;

@Repository
class JdbcRetrievalRepository implements RetrievalRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    JdbcRetrievalRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void upsertIndexRow(EvidenceIndexRow row) {
        jdbcClient.sql("""
                        insert into retrieval_evidence_index
                        (id, organization_id, candidate_id, artifact_id, candidate_type,
                         legal_concept_key, content_hash, index_version, lexical_tsvector,
                         lexical_config, publication_number, superseded_at_publication,
                         contract_pack_key, declared_contract_types, party_perspective,
                         paper_source, jurisdiction, effective_from, effective_to,
                         indexed_at, updated_at)
                        values (:id, :organizationId, :candidateId, :artifactId, :candidateType,
                         :legalConceptKey, :contentHash, :indexVersion,
                         to_tsvector(:lexicalConfig, coalesce(:lexicalText, '')),
                         :lexicalConfig, :publicationNumber, :supersededAtPublication,
                         :contractPackKey, cast(:declaredContractTypes as jsonb),
                         :partyPerspective, :paperSource, :jurisdiction,
                         :effectiveFrom, :effectiveTo, :indexedAt, :updatedAt)
                        on conflict (candidate_id, content_hash, index_version) do nothing
                        """)
                .param("id", row.id())
                .param("organizationId", row.organizationId())
                .param("candidateId", row.candidateId())
                .param("artifactId", row.artifactId())
                .param("candidateType", row.candidateType())
                .param("legalConceptKey", row.legalConceptKey())
                .param("contentHash", row.contentHash())
                .param("indexVersion", row.indexVersion())
                .param("lexicalConfig", row.lexicalConfig())
                .param("lexicalText", row.lexicalText())
                .param("publicationNumber", row.publicationNumber())
                .param("supersededAtPublication", row.supersededAtPublication())
                .param("contractPackKey", row.contractPackKey())
                .param("declaredContractTypes", json(row.declaredContractTypes()))
                .param("partyPerspective", row.partyPerspective())
                .param("paperSource", row.paperSource())
                .param("jurisdiction", row.jurisdiction())
                .param("effectiveFrom", dateOrNull(row.effectiveFrom()))
                .param("effectiveTo", dateOrNull(row.effectiveTo()))
                .param("indexedAt", Timestamp.from(Instant.now()))
                .param("updatedAt", Timestamp.from(Instant.now()))
                .update();
    }

    @Override
    public void persistEmbedding(UUID candidateId, String contentHash, String indexVersion,
            String embeddingModel, int embeddingDims, String vectorLiteral) {
        jdbcClient.sql("""
                        update retrieval_evidence_index
                        set embedding = cast(:embedding as vector),
                            embedding_model = :embeddingModel,
                            embedding_dims = :embeddingDims,
                            embedding_index_ready = true,
                            updated_at = :updatedAt
                        where candidate_id = :candidateId
                          and content_hash = :contentHash
                          and index_version = :indexVersion
                        """)
                .param("candidateId", candidateId)
                .param("contentHash", contentHash)
                .param("indexVersion", indexVersion)
                .param("embeddingModel", embeddingModel)
                .param("embeddingDims", embeddingDims)
                .param("embedding", vectorLiteral)
                .param("updatedAt", Timestamp.from(Instant.now()))
                .update();
    }

    @Override
    public void markSuperseded(UUID organizationId, UUID candidateId, long supersededAtPublication) {
        jdbcClient.sql("""
                        update retrieval_evidence_index
                        set superseded_at_publication = :supersededAt,
                            updated_at = :updatedAt
                        where organization_id = :organizationId
                          and candidate_id = :candidateId
                          and superseded_at_publication is null
                        """)
                .param("supersededAt", supersededAtPublication)
                .param("updatedAt", Timestamp.from(Instant.now()))
                .param("organizationId", organizationId)
                .param("candidateId", candidateId)
                .update();
    }

    @Override
    public Optional<EvidenceIndexRow> findIndexRow(UUID organizationId, UUID candidateId,
            String contentHash, String indexVersion) {
        return jdbcClient.sql("""
                        select * from retrieval_evidence_index
                        where organization_id = :organizationId
                          and candidate_id = :candidateId
                          and content_hash = :contentHash
                          and index_version = :indexVersion
                        """)
                .param("organizationId", organizationId)
                .param("candidateId", candidateId)
                .param("contentHash", contentHash)
                .param("indexVersion", indexVersion)
                .query((rs, ignored) -> mapRow(rs))
                .optional();
    }

    @Override
    public void insertTrace(RetrievalTrace trace) {
        jdbcClient.sql("""
                        insert into retrieval_trace
                        (id, organization_id, contract_id, version_number, clause_id,
                         purpose, request, stages, selected_evidence_ids, rejected_evidence,
                         degradation, stage_timings, model_versions, knowledge_pin, created_at)
                        values (:id, :organizationId, :contractId, :versionNumber, :clauseId,
                         :purpose, cast(:request as jsonb), cast(:stages as jsonb),
                         cast(:selectedEvidenceIds as jsonb), cast(:rejectedEvidence as jsonb),
                         cast(:degradation as jsonb), cast(:stageTimings as jsonb),
                         cast(:modelVersions as jsonb), cast(:knowledgePin as jsonb), :createdAt)
                        """)
                .param("id", trace.id())
                .param("organizationId", trace.organizationId())
                .param("contractId", trace.contractId())
                .param("versionNumber", trace.versionNumber())
                .param("clauseId", trace.clauseId())
                .param("purpose", trace.purpose())
                .param("request", json(trace.request()))
                .param("stages", json(trace.stages()))
                .param("selectedEvidenceIds", json(trace.selectedEvidenceIds()))
                .param("rejectedEvidence", json(trace.rejectedEvidence()))
                .param("degradation", json(trace.degradation()))
                .param("stageTimings", json(trace.stageTimings()))
                .param("modelVersions", json(trace.modelVersions()))
                .param("knowledgePin", json(Map.of(
                        "publicationNumber", trace.knowledgePin().publicationNumber())))
                .param("createdAt", Timestamp.from(trace.createdAt()))
                .update();
    }

    @Override
    public Optional<RetrievalTrace> findTrace(UUID organizationId, UUID traceId) {
        return jdbcClient.sql("""
                        select * from retrieval_trace
                        where organization_id = :organizationId and id = :traceId
                        """)
                .param("organizationId", organizationId)
                .param("traceId", traceId)
                .query((rs, ignored) -> mapTrace(rs))
                .optional();
    }

    @Override
    public List<EvidenceIndexRow> findHybridCandidates(HybridQuery query) {
        var conditions = new ArrayList<String>();
        var params = new LinkedHashMap<String, Object>();
        conditions.add("organization_id = :organizationId");
        params.put("organizationId", query.organizationId());
        conditions.add("candidate_type = :candidateType");
        params.put("candidateType", query.candidateType());
        conditions.add("publication_number <= :asOf");
        conditions.add("(superseded_at_publication is null or superseded_at_publication > :asOf)");
        params.put("asOf", query.asOfPublicationNumber());
        if (query.legalConceptKey() != null) {
            conditions.add("legal_concept_key = :legalConceptKey");
            params.put("legalConceptKey", query.legalConceptKey());
        }
        if (query.contractPackKey() != null) {
            conditions.add("(contract_pack_key is null or regexp_replace(lower(contract_pack_key), '[^a-z0-9]', '', 'g')"
                    + " = regexp_replace(lower(:contractPackKey), '[^a-z0-9]', '', 'g'))");
            params.put("contractPackKey", query.contractPackKey());
        }
        if (query.contractType() != null) {
            conditions.add("(declared_contract_types = '[]'::jsonb"
                    + " or exists (select 1 from jsonb_array_elements_text(declared_contract_types)"
                    + " as dt where regexp_replace(lower(dt), '[^a-z0-9]', '', 'g') = regexp_replace(lower(:contractType), '[^a-z0-9]', '', 'g')))");
            params.put("contractType", query.contractType());
        }
        if (query.partyPerspective() != null) {
            conditions.add("(party_perspective is null or regexp_replace(lower(party_perspective), '[^a-z0-9]', '', 'g')"
                    + " = regexp_replace(lower(:partyPerspective), '[^a-z0-9]', '', 'g'))");
            params.put("partyPerspective", query.partyPerspective());
        }
        if (query.paperSource() != null) {
            conditions.add("(paper_source is null or regexp_replace(lower(paper_source), '[^a-z0-9]', '', 'g')"
                    + " = regexp_replace(lower(:paperSource), '[^a-z0-9]', '', 'g'))");
            params.put("paperSource", query.paperSource());
        }
        if (query.jurisdiction() != null) {
            conditions.add("(jurisdiction is null or regexp_replace(lower(jurisdiction), '[^a-z0-9]', '', 'g')"
                    + " = regexp_replace(lower(:jurisdiction), '[^a-z0-9]', '', 'g'))");
            params.put("jurisdiction", query.jurisdiction());
        }
        if (query.effectiveOn() != null) {
            conditions.add("(effective_from is null or effective_from <= :effectiveOn)");
            conditions.add("(effective_to is null or effective_to >= :effectiveOn)");
            params.put("effectiveOn", java.sql.Date.valueOf(query.effectiveOn()));
        }
        var sql = "select * from retrieval_evidence_index where "
                + String.join(" and ", conditions)
                + " order by publication_number, candidate_id limit :limit";
        var statement = jdbcClient.sql(sql);
        for (var entry : params.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        return statement
                .param("limit", query.limit())
                .query((rs, ignored) -> mapRow(rs))
                .list();
    }

    @Override
    public List<CandidateHit> findLexicalCandidates(
            UUID organizationId, List<UUID> candidateIds, String lexicalQuery, int limit) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        var rows = jdbcClient.sql("""
                        select candidate_id,
                               ts_rank_cd(lexical_tsvector,
                                          websearch_to_tsquery(lexical_config, :lexicalQuery))
                                   as score
                        from retrieval_evidence_index
                        where organization_id = :organizationId
                          and candidate_id = any(:candidateIds)
                          and lexical_tsvector
                              @@ websearch_to_tsquery(lexical_config, :lexicalQuery)
                        order by score desc, candidate_id
                        limit :limit
                        """)
                .param("organizationId", organizationId)
                .param("candidateIds", candidateIds.toArray(UUID[]::new))
                .param("lexicalQuery", lexicalQuery)
                .param("limit", limit)
                .query((rs, ignored) -> Map.entry(
                        rs.getObject("candidate_id", UUID.class), rs.getDouble("score")))
                .list();
        return ranked(rows);
    }

    @Override
    public List<CandidateHit> findVectorCandidates(
            UUID organizationId, List<UUID> candidateIds, String embeddingModel,
            int embeddingDims, String queryVectorLiteral, int limit) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        var distance = "embedding::vector(%d) <=> cast(:queryVector as vector(%d))"
                .formatted(embeddingDims, embeddingDims);
        var rows = jdbcClient.sql("""
                        select candidate_id, %s as distance
                        from retrieval_evidence_index
                        where organization_id = :organizationId
                          and candidate_id = any(:candidateIds)
                          and embedding_model = :embeddingModel
                          and embedding_index_ready
                        order by %s
                        limit :limit
                        """.formatted(distance, distance))
                .param("organizationId", organizationId)
                .param("candidateIds", candidateIds.toArray(UUID[]::new))
                .param("embeddingModel", embeddingModel)
                .param("queryVector", queryVectorLiteral)
                .param("limit", limit)
                .query((rs, ignored) -> Map.entry(
                        rs.getObject("candidate_id", UUID.class), rs.getDouble("score")))
                .list();
        return ranked(rows);
    }

    private static List<CandidateHit> ranked(List<Map.Entry<UUID, Double>> rows) {
        var hits = new ArrayList<CandidateHit>();
        for (int i = 0; i < rows.size(); i++) {
            hits.add(new CandidateHit(rows.get(i).getKey(), rows.get(i).getValue(), i + 1));
        }
        return List.copyOf(hits);
    }

    private EvidenceIndexRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EvidenceIndexRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("candidate_id", UUID.class),
                rs.getObject("artifact_id", UUID.class),
                rs.getString("candidate_type"),
                rs.getString("legal_concept_key"),
                rs.getString("content_hash"),
                rs.getString("index_version"),
                rs.getString("lexical_config"),
                null,
                rs.getString("embedding_model"),
                (Integer) rs.getObject("embedding_dims"),
                rs.getBoolean("embedding_index_ready"),
                rs.getLong("publication_number"),
                rs.getObject("superseded_at_publication") == null
                        ? null : rs.getLong("superseded_at_publication"),
                rs.getString("contract_pack_key"),
                stringsFromJson(rs.getString("declared_contract_types")),
                rs.getString("party_perspective"),
                rs.getString("paper_source"),
                rs.getString("jurisdiction"),
                localDate(rs.getDate("effective_from")),
                localDate(rs.getDate("effective_to")));
    }

    private RetrievalTrace mapTrace(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            var pinNode = objectMapper.readTree(rs.getString("knowledge_pin"));
            Long pinPublication = pinNode.path("publicationNumber").isNumber()
                    ? pinNode.path("publicationNumber").asLong() : null;
            return new RetrievalTrace(
                    rs.getObject("id", UUID.class),
                    rs.getObject("organization_id", UUID.class),
                    rs.getObject("contract_id", UUID.class),
                    rs.getInt("version_number"),
                    rs.getObject("clause_id", UUID.class),
                    rs.getString("purpose"),
                    mapFromJson(rs.getString("request")),
                    stageMapFromJson(rs.getString("stages")),
                    uuidsFromJson(rs.getString("selected_evidence_ids")),
                    rejectedFromJson(rs.getString("rejected_evidence")),
                    stringsFromJson(rs.getString("degradation")),
                    longMapFromJson(rs.getString("stage_timings")),
                    stringMapFromJson(rs.getString("model_versions")),
                    new KnowledgePin(pinPublication),
                    rs.getObject("created_at", Timestamp.class).toInstant());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapFromJson(String json) {
        try {
            return json == null ? Map.of()
                    : objectMapper.convertValue(objectMapper.readTree(json), Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, RetrievalTrace.StageSummary> stageMapFromJson(String json) {
        try {
            if (json == null) {
                return Map.of();
            }
            var stages = new LinkedHashMap<String, RetrievalTrace.StageSummary>();
            objectMapper.readTree(json).fields().forEachRemaining(field -> {
                var stage = field.getValue();
                var ids = new ArrayList<String>();
                stage.path("candidateIds").forEach(id -> ids.add(id.asText()));
                stages.put(field.getKey(), new RetrievalTrace.StageSummary(
                        field.getKey(),
                        stage.path("candidateCount").asInt(0),
                        List.copyOf(ids),
                        List.of()));
            });
            return stages;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<UUID> uuidsFromJson(String json) {
        try {
            if (json == null) {
                return List.of();
            }
            var ids = new ArrayList<UUID>();
            for (JsonNode node : objectMapper.readTree(json)) {
                ids.add(UUID.fromString(node.asText()));
            }
            return List.copyOf(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<RetrievalTrace.RejectedItem> rejectedFromJson(String json) {
        try {
            if (json == null) {
                return List.of();
            }
            var rejected = new ArrayList<RetrievalTrace.RejectedItem>();
            for (JsonNode node : objectMapper.readTree(json)) {
                rejected.add(new RetrievalTrace.RejectedItem(
                        UUID.fromString(node.path("candidateId").asText()),
                        node.path("stage").asText(),
                        node.path("reason").asText()));
            }
            return List.copyOf(rejected);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> stringsFromJson(String json) {
        try {
            if (json == null) {
                return List.of();
            }
            var values = new ArrayList<String>();
            for (JsonNode node : objectMapper.readTree(json)) {
                values.add(node.asText());
            }
            return List.copyOf(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Long> longMapFromJson(String json) {
        try {
            if (json == null) {
                return Map.of();
            }
            var values = new LinkedHashMap<String, Long>();
            objectMapper.readTree(json).fields().forEachRemaining(
                    field -> values.put(field.getKey(), field.getValue().asLong()));
            return values;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> stringMapFromJson(String json) {
        try {
            if (json == null) {
                return Map.of();
            }
            var values = new LinkedHashMap<String, String>();
            objectMapper.readTree(json).fields().forEachRemaining(
                    field -> values.put(field.getKey(), field.getValue().asText()));
            return values;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDate localDate(java.sql.Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private static java.sql.Date dateOrNull(LocalDate date) {
        return date == null ? null : java.sql.Date.valueOf(date);
    }
}
