-- Review foundation (M7): machine-analysis aggregate, frozen input snapshot, typed plan.
-- The snapshot pins the semantic generation it planned against (semantics id, pipeline
-- version, content digest) and stable canonical heading-node subjects, so re-enrichment
-- cannot silently change what a review analyzes; mismatches fail closed at analysis time.
-- Contract/version copies are structurally bound to the exact version identity.

ALTER TABLE contract_version
    ADD CONSTRAINT uq_contract_version_identity_org
    UNIQUE (id, contract_id, version_number, organization_id);

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
    CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CHECK (status <> 'ANALYSIS_QUEUED' OR started_at IS NULL),
    UNIQUE (id, organization_id)
);

ALTER TABLE review
    ADD CONSTRAINT uq_review_identity_org
    UNIQUE (id, contract_id, contract_version_id, contract_version_number, organization_id);

ALTER TABLE review
    ADD CONSTRAINT fk_review_contract_version_identity_org
    FOREIGN KEY (contract_version_id, contract_id, contract_version_number, organization_id)
    REFERENCES contract_version (id, contract_id, version_number, organization_id);

CREATE INDEX idx_review_org_contract ON review (organization_id, contract_id, created_at DESC, id);
CREATE INDEX idx_review_org_status ON review (organization_id, status);

CREATE TABLE review_snapshot (
    review_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    contract_version_number INTEGER NOT NULL CHECK (contract_version_number >= 1),
    contract_semantics_id UUID NOT NULL,
    semantics_pipeline_version TEXT NOT NULL,
    semantics_digest CHAR(64) NOT NULL,
    contract_type TEXT NOT NULL CHECK (contract_type IN ('NDA', 'MSA', 'OTHER')),
    our_side_role TEXT,
    paper_source TEXT,
    jurisdiction TEXT NOT NULL CHECK (jurisdiction IN ('US', 'UK', 'UNSPECIFIED')),
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
    ADD CONSTRAINT fk_review_snapshot_review_identity_org
    FOREIGN KEY (review_id, contract_id, contract_version_id, contract_version_number,
                 organization_id)
    REFERENCES review (id, contract_id, contract_version_id, contract_version_number,
                       organization_id);

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
    subject_heading_node_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((family = 'CONCEPT_PRESENCE') = (expectation IS NOT NULL)),
    CHECK (family <> 'CONCEPT_PRESENCE'
        OR (subject_heading_node_id IS NULL AND secondary_concept IS NULL)),
    CHECK (family <> 'CLAUSE_REVIEW'
        OR (subject_heading_node_id IS NOT NULL AND secondary_concept IS NULL)),
    CHECK (family <> 'CROSS_CLAUSE'
        OR (secondary_concept IS NOT NULL AND subject_heading_node_id IS NOT NULL)),
    UNIQUE (id, organization_id),
    UNIQUE (review_id, check_key),
    UNIQUE (review_id, ordinal)
);

ALTER TABLE review_plan_check
    ADD CONSTRAINT fk_review_plan_check_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);

CREATE INDEX idx_review_plan_check_review ON review_plan_check (review_id, ordinal);
