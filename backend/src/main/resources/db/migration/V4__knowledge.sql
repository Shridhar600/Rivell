-- Knowledge module: legal knowledge sources, deterministic structure, extraction units,
-- typed candidates with review lifecycle, and versioned publications.
-- Tenant integrity follows the contracts pattern: child rows carry organization_id and
-- reference (id, organization_id) pairs, so cross-organization references are impossible.

CREATE TABLE knowledge_artifact (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    artifact_type TEXT NOT NULL CHECK (artifact_type IN (
        'PLAYBOOK', 'CLAUSE_LIBRARY', 'TEMPLATE', 'EXECUTED_PRECEDENT',
        'NEGOTIATION_HISTORY', 'EXTERNAL_REFERENCE')),
    filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64) NOT NULL,
    storage_key TEXT NOT NULL,
    declared_contract_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    contract_pack_key TEXT,
    party_perspective TEXT,
    paper_source TEXT,
    jurisdiction TEXT,
    language TEXT,
    effective_from DATE,
    effective_to DATE,
    manifest_note TEXT,
    ingest_status TEXT NOT NULL CHECK (ingest_status IN (
        'RECEIVED', 'EXTRACTING', 'EXTRACTED', 'FAILED')),
    parser_version TEXT,
    parse_warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_knowledge_artifact_effective_period
        CHECK (effective_from IS NULL OR effective_to IS NULL OR effective_to >= effective_from)
);

CREATE TABLE knowledge_source_node (
    id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    parent_id UUID REFERENCES knowledge_source_node (id),
    node_type TEXT NOT NULL CHECK (node_type IN (
        'BODY', 'TITLE', 'HEADING', 'PARAGRAPH', 'TABLE', 'TABLE_ROW', 'TABLE_CELL',
        'WORKBOOK', 'SHEET', 'SPREADSHEET_ROW', 'SPREADSHEET_CELL')),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    text TEXT,
    heading_level INTEGER CHECK (heading_level IS NULL OR heading_level >= 1),
    numbering_label TEXT,
    part_path TEXT NOT NULL,
    body_index INTEGER NOT NULL CHECK (body_index >= 0),
    paragraph_index INTEGER,
    table_index INTEGER,
    row_index INTEGER,
    cell_index INTEGER,
    run_start INTEGER,
    run_end INTEGER,
    sheet_name TEXT,
    column_index INTEGER CHECK (column_index IS NULL OR column_index >= 0),
    merged_region JSONB,
    raw_style_id TEXT
);

CREATE TABLE knowledge_extraction_unit (
    id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    kind TEXT NOT NULL CHECK (kind IN ('HEADING_SECTION', 'TABLE', 'SPREADSHEET_ROW')),
    title TEXT,
    content_text TEXT,
    provenance JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE knowledge_candidate (
    id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    extraction_unit_id UUID NOT NULL,
    candidate_type TEXT NOT NULL CHECK (candidate_type IN (
        'PLAYBOOK_RULE', 'CLAUSE_VARIANT', 'TEMPLATE_CLAUSE', 'PRECEDENT', 'REFERENCE')),
    review_status TEXT NOT NULL CHECK (review_status IN (
        'PENDING', 'APPROVED', 'EDITED', 'REJECTED', 'PUBLISHED')),
    raw_label TEXT NOT NULL,
    content_text TEXT,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    legal_concept_key TEXT,
    edited_content_text TEXT,
    edited_payload JSONB,
    superseding_candidate_id UUID REFERENCES knowledge_candidate (id),
    contract_pack_key TEXT,
    declared_contract_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    party_perspective TEXT,
    paper_source TEXT,
    jurisdiction TEXT,
    effective_from DATE,
    effective_to DATE,
    conflict_signals JSONB NOT NULL DEFAULT '[]'::jsonb,
    review_note TEXT,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_knowledge_candidate_effective_period
        CHECK (effective_from IS NULL OR effective_to IS NULL OR effective_to >= effective_from)
);

CREATE TABLE knowledge_publication (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    publication_number BIGINT NOT NULL CHECK (publication_number >= 1),
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, publication_number)
);

CREATE TABLE knowledge_publication_item (
    id UUID PRIMARY KEY,
    publication_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    superseded_candidate_id UUID,
    action TEXT NOT NULL CHECK (action IN ('ADD', 'SUPERSEDE')),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_publication_item_supersede_reference
        CHECK (action <> 'SUPERSEDE' OR superseded_candidate_id IS NOT NULL)
);

-- Tenant-scoped structural references (see V3 pattern).
ALTER TABLE knowledge_artifact
    ADD CONSTRAINT uq_knowledge_artifact_id_org UNIQUE (id, organization_id);
ALTER TABLE knowledge_source_node
    ADD CONSTRAINT uq_knowledge_node_id_artifact UNIQUE (id, artifact_id);
ALTER TABLE knowledge_extraction_unit
    ADD CONSTRAINT uq_knowledge_unit_id_artifact UNIQUE (id, artifact_id);
ALTER TABLE knowledge_candidate
    ADD CONSTRAINT uq_knowledge_candidate_id_artifact UNIQUE (id, artifact_id);
ALTER TABLE knowledge_candidate
    ADD CONSTRAINT uq_knowledge_candidate_id_org UNIQUE (id, organization_id);

ALTER TABLE knowledge_source_node
    ADD CONSTRAINT fk_knowledge_node_artifact_org
    FOREIGN KEY (artifact_id, organization_id)
    REFERENCES knowledge_artifact (id, organization_id);
ALTER TABLE knowledge_source_node
    ADD CONSTRAINT fk_knowledge_node_parent_same_artifact
    FOREIGN KEY (parent_id, artifact_id)
    REFERENCES knowledge_source_node (id, artifact_id);

ALTER TABLE knowledge_extraction_unit
    ADD CONSTRAINT fk_knowledge_unit_artifact_org
    FOREIGN KEY (artifact_id, organization_id)
    REFERENCES knowledge_artifact (id, organization_id);

ALTER TABLE knowledge_candidate
    ADD CONSTRAINT fk_knowledge_candidate_artifact_org
    FOREIGN KEY (artifact_id, organization_id)
    REFERENCES knowledge_artifact (id, organization_id);
ALTER TABLE knowledge_candidate
    ADD CONSTRAINT fk_knowledge_candidate_unit_same_artifact
    FOREIGN KEY (extraction_unit_id, artifact_id)
    REFERENCES knowledge_extraction_unit (id, artifact_id);
ALTER TABLE knowledge_candidate
    ADD CONSTRAINT fk_knowledge_candidate_superseding_same_org
    FOREIGN KEY (superseding_candidate_id, organization_id)
    REFERENCES knowledge_candidate (id, organization_id);

ALTER TABLE knowledge_publication
    ADD CONSTRAINT uq_knowledge_publication_id_org UNIQUE (id, organization_id);
ALTER TABLE knowledge_publication_item
    ADD CONSTRAINT fk_publication_item_publication_org
    FOREIGN KEY (publication_id, organization_id)
    REFERENCES knowledge_publication (id, organization_id);
ALTER TABLE knowledge_publication_item
    ADD CONSTRAINT fk_publication_item_candidate_org
    FOREIGN KEY (candidate_id, organization_id)
    REFERENCES knowledge_candidate (id, organization_id);
ALTER TABLE knowledge_publication_item
    ADD CONSTRAINT fk_publication_item_superseded_org
    FOREIGN KEY (superseded_candidate_id, organization_id)
    REFERENCES knowledge_candidate (id, organization_id);

-- Published candidates must never appear in two publications as ADD.
CREATE UNIQUE INDEX uq_publication_item_candidate_add
    ON knowledge_publication_item (candidate_id) WHERE action = 'ADD';

CREATE INDEX idx_knowledge_artifact_org ON knowledge_artifact (organization_id, created_at DESC);
CREATE INDEX idx_knowledge_node_artifact ON knowledge_source_node (artifact_id, ordinal);
CREATE INDEX idx_knowledge_node_parent ON knowledge_source_node (parent_id);
CREATE INDEX idx_knowledge_unit_artifact ON knowledge_extraction_unit (artifact_id, ordinal);
CREATE INDEX idx_knowledge_candidate_artifact ON knowledge_candidate (artifact_id, candidate_type);
CREATE INDEX idx_knowledge_candidate_org_status ON knowledge_candidate (organization_id, review_status);
CREATE INDEX idx_knowledge_candidate_superseding ON knowledge_candidate (superseding_candidate_id);
CREATE INDEX idx_knowledge_publication_org ON knowledge_publication (organization_id, publication_number DESC);
CREATE INDEX idx_knowledge_publication_item_publication ON knowledge_publication_item (publication_id);
