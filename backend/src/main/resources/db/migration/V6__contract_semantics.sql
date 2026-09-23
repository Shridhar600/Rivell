-- Contract semantics (M5): the typed semantic model of one contract version.
-- Raw + interpreted + human correction coexist; no probabilistic truth in constraints.

ALTER TABLE contract_version ADD CONSTRAINT uq_contract_version_id_org UNIQUE (id, organization_id);

CREATE TABLE contract_semantics (
    id UUID PRIMARY KEY,
    contract_version_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    enrichment_status TEXT NOT NULL CHECK (enrichment_status IN
        ('NOT_ENRICHED', 'ENRICHING', 'READY', 'FAILED')),
    pipeline_version TEXT NOT NULL,
    review_profile JSONB NOT NULL,
    quality JSONB NOT NULL DEFAULT '[]'::jsonb,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    observations JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, organization_id),
    UNIQUE (contract_version_id)
);

ALTER TABLE contract_semantics
    ADD CONSTRAINT fk_contract_semantics_version_org
    FOREIGN KEY (contract_version_id, organization_id)
    REFERENCES contract_version (id, organization_id);

CREATE TABLE contract_metadata_fact (
    id UUID PRIMARY KEY,
    semantics_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    field TEXT NOT NULL CHECK (field IN
        ('AGREEMENT_TITLE', 'CONTRACT_TYPE', 'EFFECTIVE_DATE', 'GOVERNING_LAW', 'TERM')),
    value TEXT NOT NULL,
    interpretation JSONB NOT NULL,
    corrected_value TEXT,
    corrected_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, organization_id),
    UNIQUE (semantics_id, field)
);

CREATE TABLE contract_party (
    id UUID PRIMARY KEY,
    semantics_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    raw_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    interpretations JSONB NOT NULL DEFAULT '[]'::jsonb,
    machine_roles JSONB NOT NULL DEFAULT '[]'::jsonb,
    correction_roles JSONB,
    correction_id UUID,
    corrected_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, organization_id),
    UNIQUE (semantics_id, normalized_name)
);

CREATE TABLE contract_defined_term (
    id UUID PRIMARY KEY,
    semantics_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    term TEXT NOT NULL,
    definition TEXT NOT NULL,
    defining_node_id UUID NOT NULL,
    source_node_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    reference_node_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    duplicate BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, organization_id),
    UNIQUE (semantics_id, term)
);

CREATE TABLE contract_reference (
    id UUID PRIMARY KEY,
    semantics_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    source_node_id UUID NOT NULL,
    reference_text TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('SECTION', 'SCHEDULE', 'EXHIBIT', 'ANNEX', 'EXTERNAL')),
    status TEXT NOT NULL CHECK (status IN ('RESOLVED', 'AMBIGUOUS', 'BROKEN', 'EXTERNAL')),
    target_node_id UUID,
    candidate_node_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, organization_id)
);

CREATE TABLE contract_clause_semantics (
    id UUID PRIMARY KEY,
    semantics_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    heading_node_id UUID NOT NULL,
    text_node_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    content_text TEXT NOT NULL,
    classification JSONB NOT NULL,
    facts JSONB NOT NULL DEFAULT '[]'::jsonb,
    interpretations JSONB NOT NULL DEFAULT '[]'::jsonb,
    correction JSONB,
    corrected_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, organization_id),
    UNIQUE (semantics_id, heading_node_id)
);

CREATE TABLE contract_relationship (
    id UUID PRIMARY KEY,
    semantics_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    source_region_node_id UUID NOT NULL,
    target_region_node_id UUID NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('DEPENDS_ON', 'MODIFIES', 'CARVES_OUT', 'SURVIVES')),
    detector TEXT NOT NULL CHECK (detector IN ('DETERMINISTIC', 'SEMANTIC')),
    basis TEXT NOT NULL,
    probability NUMERIC(4, 3) NOT NULL CHECK (probability >= 0 AND probability <= 1),
    execution_id UUID,
    verification TEXT NOT NULL CHECK (verification IN
        ('NOT_VERIFIED', 'VERIFIED', 'UNCERTAIN', 'DISPUTED')),
    verification_execution_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, organization_id),
    UNIQUE (semantics_id, source_region_node_id, target_region_node_id, type)
);

CREATE INDEX idx_contract_semantics_org ON contract_semantics (organization_id);
CREATE INDEX idx_contract_clause_version ON contract_clause_semantics (contract_version_id);
CREATE INDEX idx_contract_reference_source ON contract_reference (source_node_id);
CREATE INDEX idx_contract_relationship_source ON contract_relationship (source_region_node_id);
CREATE INDEX idx_contract_relationship_target ON contract_relationship (target_region_node_id);
