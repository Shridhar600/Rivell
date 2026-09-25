-- M7 findings (B17): the human-reviewable machine proposals assembled per run.
-- Machine review stops here; human decision states arrive in M8.

CREATE TABLE finding (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    review_id UUID NOT NULL,
    run_id UUID NOT NULL,
    consolidation_key TEXT NOT NULL,
    subject_kind TEXT NOT NULL CHECK (subject_kind IN ('CLAUSE', 'ABSENCE')),
    subject_heading_node_id UUID,
    absence_concept TEXT,
    concept TEXT NOT NULL,
    secondary_concept TEXT,
    issue_family TEXT NOT NULL CHECK (issue_family IN
        ('LIABILITY_CAP_DEVIATION', 'LIABILITY_CAP_UNCERTAIN', 'MISSING_REQUIRED_PROVISION',
         'EXPECTED_PROVISION_ABSENT', 'SURVIVAL_COVERAGE_GAP', 'CARVE_OUT_INCONSISTENCY',
         'POLICY_UNAVAILABLE', 'POLICY_AMBIGUOUS', 'OPEN_ENDED_LIABILITY')),
    observation_kind TEXT NOT NULL,
    observation_text TEXT NOT NULL,
    severity TEXT NOT NULL CHECK (severity IN ('HIGH', 'MEDIUM', 'LOW')),
    review_classification TEXT NOT NULL CHECK (review_classification IN
        ('BLOCKING', 'REVIEW_REQUIRED', 'INFORMATIONAL')),
    proposed_action TEXT NOT NULL CHECK (proposed_action IN
        ('USE_APPROVED_FALLBACK', 'ADD_REQUIRED_PROVISION', 'REVIEW_AMBIGUITY',
         'ESCALATE_TO_HUMAN', 'CONSIDER_GENERATED_LANGUAGE', 'NO_ACTION')),
    proposed_language TEXT,
    proposed_language_source TEXT NOT NULL CHECK (proposed_language_source IN
        ('APPROVED_DRAFTING', 'GENERATIVE', 'NONE')),
    approved_drafting_evidence_id UUID,
    quality JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((subject_kind = 'CLAUSE') = (subject_heading_node_id IS NOT NULL)),
    CHECK ((subject_kind = 'ABSENCE') = (absence_concept IS NOT NULL)),
    CHECK ((proposed_language_source = 'NONE') = (proposed_language IS NULL)),
    CHECK (proposed_language_source <> 'APPROVED_DRAFTING'
        OR approved_drafting_evidence_id IS NOT NULL),
    UNIQUE (id, organization_id),
    UNIQUE (run_id, consolidation_key)
);

ALTER TABLE finding
    ADD CONSTRAINT fk_finding_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);
ALTER TABLE finding
    ADD CONSTRAINT fk_finding_run_review_org
    FOREIGN KEY (run_id, review_id, organization_id)
    REFERENCES review_analysis_run (id, review_id, organization_id);

CREATE INDEX idx_finding_run ON finding (run_id);
CREATE INDEX idx_finding_review_severity ON finding (review_id, severity);

CREATE TABLE finding_concept (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    finding_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    concept TEXT NOT NULL,
    UNIQUE (finding_id, concept),
    UNIQUE (finding_id, ordinal)
);

ALTER TABLE finding_concept
    ADD CONSTRAINT fk_finding_concept_finding_org
    FOREIGN KEY (finding_id, organization_id) REFERENCES finding (id, organization_id);

CREATE TABLE finding_claim (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    finding_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    claim_kind TEXT NOT NULL CHECK (claim_kind IN
        ('OBSERVATION', 'POLICY_BASIS', 'EVIDENCE_BASIS', 'ABSENCE_BASIS',
         'RECOMMENDATION', 'GENERATIVE_EXPLANATION')),
    text TEXT NOT NULL,
    absence BOOLEAN NOT NULL DEFAULT false,
    evidence_item_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    CHECK (absence OR jsonb_array_length(evidence_item_ids) > 0),
    UNIQUE (finding_id, ordinal)
);

ALTER TABLE finding_claim
    ADD CONSTRAINT fk_finding_claim_finding_org
    FOREIGN KEY (finding_id, organization_id) REFERENCES finding (id, organization_id);

CREATE TABLE finding_evidence (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    finding_id UUID NOT NULL,
    evidence_item_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    UNIQUE (finding_id, evidence_item_id)
);

ALTER TABLE finding_evidence
    ADD CONSTRAINT fk_finding_evidence_finding_org
    FOREIGN KEY (finding_id, organization_id) REFERENCES finding (id, organization_id);

CREATE TABLE finding_signal (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    finding_id UUID NOT NULL,
    signal_id UUID NOT NULL,
    UNIQUE (finding_id, signal_id)
);

ALTER TABLE finding_signal
    ADD CONSTRAINT fk_finding_signal_finding_org
    FOREIGN KEY (finding_id, organization_id) REFERENCES finding (id, organization_id);
ALTER TABLE finding_signal
    ADD CONSTRAINT fk_finding_signal_signal_org
    FOREIGN KEY (signal_id, organization_id) REFERENCES raw_review_signal (id, organization_id);
