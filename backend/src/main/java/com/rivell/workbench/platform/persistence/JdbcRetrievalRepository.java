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
                         lexical_config, publication_number,
                         contract_pack_key, declared_contract_types, party_perspective,
                         paper_source, jurisdiction, effective_from, effective_to,
                         indexed_at, updated_at)
                        values (:id, :organizationId, :candidateId, :artifactId, :candidateType,
                         :legalConceptKey, :contentHash, :indexVersion,
                         to_tsvector(cast(:lexicalConfig as regconfig), coalesce(:lexicalText, '')),
                         :lexicalConfig, :publicationNumber,
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
    public void persistEmbedding(UUID organizationId, UUID candidateId, String contentHash,
            String indexVersion, String embeddingModel, int embeddingDims, String vectorLiteral) {
        jdbcClient.sql("""
                        update retrieval_evidence_index
                        set embedding = cast(:embedding as vector),
                            embedding_model = :embeddingModel,
                            embedding_dims = :embeddingDims,
                            embedding_index_ready = true,
                            updated_at = :updatedAt
                        where organization_id = :organizationId
                          and candidate_id = :candidateId
                          and content_hash = :contentHash
                          and index_version = :indexVersion
                        """)
                .param("organizationId", organizationId)
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
    public void createVectorIndexIfAbsent(String indexName, String embeddingModel, int dims) {
        var expression = "(embedding::vector(" + dims + ")) vector_cosine_ops";
        var predicate = "embedding_model = '" + embeddingModel.replace("'", "''") + "'";
        jdbcClient.sql("create index if not exists " + indexName
                + " on retrieval_evidence_index using hnsw (" + expression + ") where "
                + predicate)
                .update();
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
                .param("knowledgePin", json(pin(trace.knowledgePin())))
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
    public List<CandidateHit> findLexicalCandidates(
            UUID organizationId, List<UUID> candidateIds, String lexicalQuery, int limit,
            String indexVersion) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        var rows = jdbcClient.sql("""
                        select candidate_id,
                               ts_rank_cd(lexical_tsvector,
                                          websearch_to_tsquery(cast(lexical_config as regconfig), :lexicalQuery))
                                   as score
                        from retrieval_evidence_index
                        where organization_id = :organizationId
                          and candidate_id = any(:candidateIds)
                          and lexical_tsvector
                              @@ websearch_to_tsquery(cast(lexical_config as regconfig), :lexicalQuery)
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
            int embeddingDims, String queryVectorLiteral, int limit, String indexVersion) {
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
                          and index_version = :indexVersion
                          and embedding_model = :embeddingModel
                          and embedding_dims = :embeddingDims
                          and embedding_index_ready
                        order by %s, candidate_id
                        limit :limit
                        """.formatted(distance, distance))
                .param("organizationId", organizationId)
                .param("candidateIds", candidateIds.toArray(UUID[]::new))
                .param("embeddingModel", embeddingModel)
                .param("embeddingDims", embeddingDims)
                .param("queryVector", queryVectorLiteral)
                .param("indexVersion", indexVersion)
                .param("limit", limit)
                .query((rs, ignored) -> Map.entry(
                        rs.getObject("candidate_id", UUID.class), rs.getDouble("distance")))
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
                var ranks = new ArrayList<Integer>();
                stage.path("candidateRanks").forEach(rank -> ranks.add(rank.asInt()));
                stages.put(field.getKey(), new RetrievalTrace.StageSummary(
                        field.getKey(),
                        stage.path("candidateCount").asInt(0),
                        List.copyOf(ids),
                        List.copyOf(ranks)));
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

    private Map<String, Object> pin(KnowledgePin knowledgePin) {
        var values = new LinkedHashMap<String, Object>();
        values.put("pinned", knowledgePin.pinned());
        if (knowledgePin.pinned()) {
            values.put("publicationNumber", knowledgePin.publicationNumber());
        }
        return values;
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
