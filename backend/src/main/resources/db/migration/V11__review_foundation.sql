-- Review foundation (M7): machine-analysis aggregate, frozen input snapshot, typed plan.
-- Snapshot values are copied (profile, pin, versions); referenced identities are immutable
-- by construction. Plan clause references are typed UUIDs to contract semantics (no
-- cross-module FK by design).

CREATE TABLE review (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    contract_version_number INTEGER NOT NULL CHECK (contract_version_number >= 1),
    status TEXT NOT NULL CHECK (status IN
        ('ANALYSIS_QUEUED', 'ANALYZING', 'COMPLETED', 'FAILED')),
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (id, organization_id)
);

ALTER TABLE review
    ADD CONSTRAINT fk_review_contract_version_org
    FOREIGN KEY (contract_version_id, organization_id)
    REFERENCES contract_version (id, organization_id);

CREATE INDEX idx_review_org_contract ON review (organization_id, contract_id, created_at DESC);
CREATE INDEX idx_review_org_status ON review (organization_id, status);

CREATE TABLE review_snapshot (
    review_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    contract_version_number INTEGER NOT NULL CHECK (contract_version_number >= 1),
    contract_type TEXT NOT NULL,
    our_side_role TEXT,
    paper_source TEXT,
    jurisdiction TEXT NOT NULL,
    contract_pack_key TEXT,
    pack_version TEXT,
    knowledge_pin_number BIGINT NOT NULL CHECK (knowledge_pin_number >= 0),
    effective_on DATE NOT NULL,
    review_pipeline_version TEXT NOT NULL,
    embedding_index_version TEXT NOT NULL,
    embedding_model_identity TEXT,
    decision_provider_id TEXT NOT NULL,
    decision_model_alias TEXT NOT NULL,
    generative_provider_id TEXT NOT NULL,
    generative_model_alias TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((contract_pack_key IS NULL) = (pack_version IS NULL)),
    UNIQUE (review_id, organization_id)
);

ALTER TABLE review_snapshot
    ADD CONSTRAINT fk_review_snapshot_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);

CREATE TABLE review_plan_check (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    check_key TEXT NOT NULL,
    family TEXT NOT NULL CHECK (family IN
        ('CONCEPT_PRESENCE', 'CLAUSE_REVIEW', 'CROSS_CLAUSE')),
    expectation TEXT CHECK (expectation IN ('REQUIRED', 'EXPECTED', 'OPTIONAL')),
    mechanism TEXT NOT NULL CHECK (mechanism IN
        ('PRESENCE', 'LIABILITY_CAP', 'CONFIDENTIALITY_SURVIVAL', 'CARVE_OUT_CONSISTENCY')),
    concept TEXT NOT NULL,
    secondary_concept TEXT,
    subject_clause_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((family = 'CONCEPT_PRESENCE') = (expectation IS NOT NULL)),
    UNIQUE (id, organization_id),
    UNIQUE (review_id, check_key)
);

ALTER TABLE review_plan_check
    ADD CONSTRAINT fk_review_plan_check_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);

CREATE INDEX idx_review_plan_check_review ON review_plan_check (review_id, ordinal);
