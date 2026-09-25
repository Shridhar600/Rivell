-- M7 analysis execution (B6): runs (generations), per-check execution state, raw signals.
-- Active generation = latest COMPLETED run; history is append-only (R2). Signals and
-- executions are run-scoped; retries resume a run and never duplicate active state.

CREATE TABLE review_analysis_run (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    review_id UUID NOT NULL,
    run_number INTEGER NOT NULL CHECK (run_number >= 1),
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    trigger TEXT NOT NULL CHECK (trigger IN ('INITIAL', 'RECOMPUTE')),
    pipeline_version TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    failure_reason TEXT,
    CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    UNIQUE (id, organization_id),
    UNIQUE (review_id, run_number)
);

ALTER TABLE review_analysis_run
    ADD CONSTRAINT fk_review_analysis_run_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);

CREATE UNIQUE INDEX uq_review_analysis_run_running
    ON review_analysis_run (review_id) WHERE status = 'RUNNING';
CREATE INDEX idx_review_analysis_run_review ON review_analysis_run (review_id, run_number DESC);

CREATE TABLE review_check_execution (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    review_id UUID NOT NULL,
    run_id UUID NOT NULL,
    plan_check_id UUID NOT NULL,
    status TEXT NOT NULL CHECK (status IN
        ('PENDING', 'RUNNING', 'COMPLETED', 'DEGRADED', 'FAILED', 'SKIPPED')),
    evidence_trace_id UUID,
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    signal_count INTEGER NOT NULL DEFAULT 0 CHECK (signal_count >= 0),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (id, organization_id),
    UNIQUE (run_id, plan_check_id)
);

ALTER TABLE review_check_execution
    ADD CONSTRAINT fk_review_check_execution_run_org
    FOREIGN KEY (run_id, organization_id)
    REFERENCES review_analysis_run (id, organization_id);
ALTER TABLE review_check_execution
    ADD CONSTRAINT fk_review_check_execution_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);

CREATE TABLE raw_review_signal (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    review_id UUID NOT NULL,
    run_id UUID NOT NULL,
    plan_check_id UUID NOT NULL,
    signal_type TEXT NOT NULL CHECK (signal_type IN
        ('POLICY_DEVIATION', 'MISSING_REQUIRED_CONCEPT', 'EXPECTED_CONCEPT_ABSENT',
         'CROSS_CLAUSE_CONFLICT', 'APPROVED_DRAFTING_AVAILABLE', 'ESCALATION_REQUIRED',
         'POLICY_UNAVAILABLE', 'POLICY_AMBIGUOUS', 'DEVIATION_UNCERTAIN',
         'OPEN_ENDED_ANALYSIS_RESULT')),
    subject_kind TEXT NOT NULL CHECK (subject_kind IN ('CLAUSE', 'ABSENCE')),
    subject_heading_node_id UUID,
    absence_concept TEXT,
    concept TEXT NOT NULL,
    secondary_concept TEXT,
    issue_family TEXT NOT NULL CHECK (issue_family IN
        ('LIABILITY_CAP_DEVIATION', 'LIABILITY_CAP_UNCERTAIN', 'MISSING_REQUIRED_PROVISION',
         'EXPECTED_PROVISION_ABSENT', 'SURVIVAL_COVERAGE_GAP', 'CARVE_OUT_INCONSISTENCY',
         'POLICY_UNAVAILABLE', 'POLICY_AMBIGUOUS', 'OPEN_ENDED_LIABILITY')),
    payload JSONB NOT NULL,
    evidence_item_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    decision_spec_id TEXT,
    decision_spec_version INTEGER,
    jev_execution_id UUID,
    generative_execution_id UUID,
    verification_outcome TEXT,
    resolved_model TEXT,
    usage JSONB,
    quality JSONB NOT NULL DEFAULT '{}'::jsonb,
    dedupe_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((subject_kind = 'CLAUSE') = (subject_heading_node_id IS NOT NULL)),
    CHECK ((subject_kind = 'ABSENCE') = (absence_concept IS NOT NULL)),
    UNIQUE (id, organization_id),
    UNIQUE (run_id, dedupe_key)
);

ALTER TABLE raw_review_signal
    ADD CONSTRAINT fk_raw_review_signal_run_org
    FOREIGN KEY (run_id, organization_id)
    REFERENCES review_analysis_run (id, organization_id);
ALTER TABLE raw_review_signal
    ADD CONSTRAINT fk_raw_review_signal_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);

CREATE INDEX idx_raw_review_signal_run ON raw_review_signal (run_id, created_at);
