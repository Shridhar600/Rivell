-- M9 deterministic redline execution: one canonical execution per frozen approved-change
-- set, per-change execution/verification report, and the verified artifact. M7/M8 rows
-- are never mutated by M9.

CREATE TABLE redline_execution (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    review_id UUID NOT NULL,
    change_set_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    contract_version_number INTEGER NOT NULL CHECK (contract_version_number >= 1),
    change_set_version INTEGER NOT NULL CHECK (change_set_version >= 1),
    status TEXT NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
    outcome TEXT CHECK (outcome IN ('REDLINE_GENERATED', 'NO_CHANGES')),
    failure_code TEXT,
    failure_detail TEXT,
    renderer_version TEXT NOT NULL,
    placement_strategy_version TEXT NOT NULL,
    source_sha256 CHAR(64),
    change_count INTEGER NOT NULL CHECK (change_count >= 0),
    requested_by UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'COMPLETED') = (outcome IS NOT NULL)),
    CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CHECK (status <> 'COMPLETED' OR outcome <> 'NO_CHANGES' OR change_count = 0),
    UNIQUE (id, organization_id),
    UNIQUE (change_set_id)
);

ALTER TABLE redline_execution
    ADD CONSTRAINT fk_redline_execution_set_org
    FOREIGN KEY (change_set_id, organization_id)
    REFERENCES review_finalization (id, organization_id);
ALTER TABLE redline_execution
    ADD CONSTRAINT fk_redline_execution_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);

CREATE INDEX idx_redline_execution_review
    ON redline_execution (organization_id, review_id);

CREATE TABLE redline_execution_change (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    approved_change_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    action_type TEXT NOT NULL CHECK (action_type IN ('REPLACE', 'INSERT', 'DELETE')),
    target_kind TEXT NOT NULL CHECK (target_kind IN ('CLAUSE', 'ABSENCE')),
    status TEXT NOT NULL CHECK (status IN ('APPLIED', 'FAILED', 'NOT_APPLIED')),
    verification TEXT CHECK (verification IN ('VERIFIED', 'FAILED')),
    failure_code TEXT,
    placement TEXT,
    resolved_target TEXT,
    paragraphs_deleted INTEGER CHECK (paragraphs_deleted IS NULL OR paragraphs_deleted >= 0),
    paragraphs_inserted INTEGER CHECK (paragraphs_inserted IS NULL OR paragraphs_inserted >= 0),
    revision_id_start BIGINT,
    revision_id_end BIGINT,
    CHECK (status = 'APPLIED' OR verification IS NULL),
    CHECK (status <> 'FAILED' OR failure_code IS NOT NULL),
    UNIQUE (id, organization_id),
    UNIQUE (execution_id, approved_change_id),
    UNIQUE (execution_id, ordinal)
);

ALTER TABLE redline_execution_change
    ADD CONSTRAINT fk_redline_execution_change_execution_org
    FOREIGN KEY (execution_id, organization_id)
    REFERENCES redline_execution (id, organization_id);
ALTER TABLE redline_execution_change
    ADD CONSTRAINT fk_redline_execution_change_approved_org
    FOREIGN KEY (approved_change_id, organization_id)
    REFERENCES approved_change (id, organization_id);

CREATE TABLE redline_artifact (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64) NOT NULL,
    storage_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, organization_id),
    UNIQUE (execution_id)
);

ALTER TABLE redline_artifact
    ADD CONSTRAINT fk_redline_artifact_execution_org
    FOREIGN KEY (execution_id, organization_id)
    REFERENCES redline_execution (id, organization_id);
