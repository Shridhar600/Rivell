-- Retrieval evidence index + trace (M6, Architecture Decision #8).
-- Indexed evidence is derived, rebuildable data: every row traces back to a stable
-- published candidate identity + content hash. Source truth remains the published
-- artifact; index rows are never the source of authority.
-- The embedding column is intentionally dimensionless: vectors from different
-- embedding models coexist but are only ever compared within one model identity
-- (per-model partial HNSW indexes are application-managed DDL, created when an
-- embedding provider is configured, since the real dimension is unknown at
-- migration time).

CREATE TABLE retrieval_evidence_index (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    artifact_id UUID NOT NULL,
    candidate_type TEXT NOT NULL,
    legal_concept_key TEXT,
    content_hash CHAR(64) NOT NULL,
    index_version TEXT NOT NULL,
    lexical_tsvector tsvector,
    lexical_config TEXT NOT NULL DEFAULT 'english',
    embedding vector,
    embedding_model TEXT,
    embedding_dims INTEGER CHECK (embedding_dims IS NULL OR embedding_dims > 0),
    embedding_index_ready BOOLEAN NOT NULL DEFAULT FALSE,
    -- snapshot visibility: the publication that made the candidate searchable, and
    -- the publication that superseded it (null while still current). A pinned
    -- retrieval sees a row iff publication_number <= pin AND
    -- (superseded_at_publication IS NULL OR superseded_at_publication > pin).
    publication_number BIGINT NOT NULL CHECK (publication_number >= 1),
    superseded_at_publication BIGINT,
    -- scope metadata denormalized from the published candidate at index time
    contract_pack_key TEXT,
    declared_contract_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    party_perspective TEXT,
    paper_source TEXT,
    jurisdiction TEXT,
    effective_from DATE,
    effective_to DATE,
    indexed_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_retrieval_index_candidate UNIQUE (candidate_id, content_hash, index_version)
);

CREATE INDEX idx_retrieval_index_org ON retrieval_evidence_index (organization_id, candidate_id);
CREATE INDEX idx_retrieval_index_lexical ON retrieval_evidence_index
    USING GIN (lexical_tsvector);
CREATE INDEX idx_retrieval_index_embedding_model ON retrieval_evidence_index
    (organization_id, embedding_model)
    WHERE embedding_index_ready;

CREATE TABLE retrieval_trace (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    version_number INTEGER NOT NULL CHECK (version_number >= 1),
    clause_id UUID NOT NULL,
    purpose TEXT NOT NULL,
    request JSONB NOT NULL,
    stages JSONB NOT NULL DEFAULT '{}'::jsonb,
    selected_evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    rejected_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    degradation JSONB NOT NULL DEFAULT '[]'::jsonb,
    stage_timings JSONB NOT NULL DEFAULT '{}'::jsonb,
    model_versions JSONB NOT NULL DEFAULT '{}'::jsonb,
    knowledge_pin JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_retrieval_trace_org ON retrieval_trace (organization_id, created_at);
CREATE INDEX idx_retrieval_trace_contract ON retrieval_trace
    (organization_id, contract_id, version_number, clause_id);

-- Bounded published-knowledge access for retrieval (DW-010): replaces broad
-- published-candidate hydration with a targeted, indexed lookup.
CREATE INDEX idx_knowledge_candidate_retrieval ON knowledge_candidate
    (organization_id, review_status, legal_concept_key);
