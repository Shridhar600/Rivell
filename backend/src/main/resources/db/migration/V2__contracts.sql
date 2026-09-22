CREATE TABLE source_artifact (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64) NOT NULL,
    storage_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE contract (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    title TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE contract_version (
    id UUID PRIMARY KEY,
    contract_id UUID NOT NULL REFERENCES contract (id),
    version_number INTEGER NOT NULL CHECK (version_number >= 1),
    source_artifact_id UUID NOT NULL REFERENCES source_artifact (id),
    role TEXT NOT NULL,
    parser_version TEXT NOT NULL,
    parse_status TEXT NOT NULL,
    parse_warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (contract_id, version_number)
);

CREATE TABLE document_node (
    id UUID PRIMARY KEY,
    contract_version_id UUID NOT NULL REFERENCES contract_version (id),
    parent_id UUID REFERENCES document_node (id),
    node_type TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    text TEXT,
    heading_level INTEGER CHECK (heading_level IS NULL OR heading_level >= 1),
    part_path TEXT NOT NULL,
    body_index INTEGER NOT NULL CHECK (body_index >= 0),
    paragraph_index INTEGER,
    table_index INTEGER,
    row_index INTEGER,
    cell_index INTEGER,
    run_start INTEGER,
    run_end INTEGER,
    raw_style_id TEXT
);

CREATE INDEX idx_contract_organization ON contract (organization_id);
CREATE INDEX idx_contract_version_contract ON contract_version (contract_id);
CREATE INDEX idx_document_node_version ON document_node (contract_version_id, ordinal);
CREATE INDEX idx_document_node_parent ON document_node (parent_id);
