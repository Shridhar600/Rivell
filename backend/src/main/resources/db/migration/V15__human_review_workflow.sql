-- M8 human review workflow: append-only human decisions, one finalization per review,
-- and the frozen approved-change set consumed by M9. M7 machine state is never mutated.

ALTER TABLE finding
    ADD CONSTRAINT uq_finding_id_review UNIQUE (id, review_id);
ALTER TABLE finding
    ADD CONSTRAINT uq_finding_id_run UNIQUE (id, run_id);

CREATE TABLE human_decision (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    review_id UUID NOT NULL,
    finding_id UUID NOT NULL,
    run_id UUID NOT NULL,
    revision INTEGER NOT NULL CHECK (revision >= 1),
    action TEXT NOT NULL CHECK (action IN ('ACCEPT', 'EDIT', 'REJECT', 'ESCALATE')),
    approved_action TEXT CHECK (approved_action IN ('REPLACE', 'INSERT', 'DELETE')),
    approved_language TEXT,
    language_source TEXT CHECK (language_source IN
        ('APPROVED_DRAFTING', 'GENERATIVE', 'HUMAN_EDIT')),
    approved_drafting_evidence_id UUID,
    comment TEXT,
    actor_id UUID NOT NULL,
    client_request_id UUID,
    decided_at TIMESTAMPTZ NOT NULL,
    supersedes_decision_id UUID,
    CHECK ((action IN ('ACCEPT', 'EDIT')) = (approved_action IS NOT NULL)),
    CHECK (action IN ('ACCEPT', 'EDIT')
        OR (approved_language IS NULL AND language_source IS NULL
            AND approved_drafting_evidence_id IS NULL)),
    CHECK (approved_action NOT IN ('REPLACE', 'INSERT')
        OR (approved_language IS NOT NULL AND language_source IS NOT NULL)),
    CHECK ((language_source IS NULL AND approved_drafting_evidence_id IS NULL)
        OR (language_source = 'APPROVED_DRAFTING'
            AND approved_drafting_evidence_id IS NOT NULL)
        OR (language_source IN ('GENERATIVE', 'HUMAN_EDIT')
            AND approved_drafting_evidence_id IS NULL)),
    CHECK (action NOT IN ('REJECT', 'ESCALATE')
        OR (comment IS NOT NULL AND length(comment) > 0)),
    UNIQUE (id, organization_id),
    UNIQUE (finding_id, revision)
);

CREATE UNIQUE INDEX uq_human_decision_client_request
    ON human_decision (finding_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

ALTER TABLE human_decision
    ADD CONSTRAINT fk_human_decision_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);
ALTER TABLE human_decision
    ADD CONSTRAINT fk_human_decision_run_review_org
    FOREIGN KEY (run_id, review_id, organization_id)
    REFERENCES review_analysis_run (id, review_id, organization_id);
ALTER TABLE human_decision
    ADD CONSTRAINT fk_human_decision_finding_org
    FOREIGN KEY (finding_id, organization_id) REFERENCES finding (id, organization_id);
ALTER TABLE human_decision
    ADD CONSTRAINT fk_human_decision_finding_review
    FOREIGN KEY (finding_id, review_id) REFERENCES finding (id, review_id);
ALTER TABLE human_decision
    ADD CONSTRAINT fk_human_decision_finding_run
    FOREIGN KEY (finding_id, run_id) REFERENCES finding (id, run_id);
ALTER TABLE human_decision
    ADD CONSTRAINT fk_human_decision_supersedes_org
    FOREIGN KEY (supersedes_decision_id, organization_id)
    REFERENCES human_decision (id, organization_id);

CREATE INDEX idx_human_decision_review_run
    ON human_decision (organization_id, review_id, run_id);

CREATE TABLE review_finalization (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    review_id UUID NOT NULL,
    run_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    contract_version_number INTEGER NOT NULL CHECK (contract_version_number >= 1),
    change_set_version INTEGER NOT NULL CHECK (change_set_version >= 1),
    finalized_by UUID NOT NULL,
    finalized_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, organization_id),
    UNIQUE (review_id)
);

ALTER TABLE review_finalization
    ADD CONSTRAINT fk_review_finalization_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);
ALTER TABLE review_finalization
    ADD CONSTRAINT fk_review_finalization_run_review_org
    FOREIGN KEY (run_id, review_id, organization_id)
    REFERENCES review_analysis_run (id, review_id, organization_id);

CREATE TABLE approved_change (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    change_set_id UUID NOT NULL,
    review_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    finding_id UUID NOT NULL,
    decision_id UUID NOT NULL,
    subject_kind TEXT NOT NULL CHECK (subject_kind IN ('CLAUSE', 'ABSENCE')),
    action_type TEXT NOT NULL CHECK (action_type IN ('REPLACE', 'INSERT', 'DELETE')),
    target_heading_node_id UUID,
    absence_concept TEXT,
    approved_language TEXT,
    language_source TEXT NOT NULL CHECK (language_source IN
        ('APPROVED_DRAFTING', 'GENERATIVE', 'HUMAN_EDIT', 'NONE')),
    approved_drafting_evidence_id UUID,
    CHECK ((subject_kind = 'CLAUSE') = (target_heading_node_id IS NOT NULL)),
    CHECK ((subject_kind = 'ABSENCE') = (absence_concept IS NOT NULL)),
    CHECK (action_type <> 'INSERT' OR subject_kind = 'ABSENCE'),
    CHECK (action_type = 'INSERT' OR subject_kind = 'CLAUSE'),
    CHECK ((action_type = 'DELETE') = (approved_language IS NULL)),
    CHECK ((action_type = 'DELETE') = (language_source = 'NONE')),
    CHECK (action_type = 'DELETE' OR approved_language IS NOT NULL),
    CHECK ((language_source = 'APPROVED_DRAFTING')
        = (approved_drafting_evidence_id IS NOT NULL)),
    UNIQUE (id, organization_id),
    UNIQUE (change_set_id, finding_id),
    UNIQUE (change_set_id, ordinal)
);

ALTER TABLE approved_change
    ADD CONSTRAINT fk_approved_change_set_org
    FOREIGN KEY (change_set_id, organization_id)
    REFERENCES review_finalization (id, organization_id);
ALTER TABLE approved_change
    ADD CONSTRAINT fk_approved_change_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);
ALTER TABLE approved_change
    ADD CONSTRAINT fk_approved_change_finding_org
    FOREIGN KEY (finding_id, organization_id) REFERENCES finding (id, organization_id);
ALTER TABLE approved_change
    ADD CONSTRAINT fk_approved_change_finding_review
    FOREIGN KEY (finding_id, review_id) REFERENCES finding (id, review_id);
ALTER TABLE approved_change
    ADD CONSTRAINT fk_approved_change_decision_org
    FOREIGN KEY (decision_id, organization_id)
    REFERENCES human_decision (id, organization_id);

CREATE UNIQUE INDEX uq_approved_change_clause_target
    ON approved_change (change_set_id, target_heading_node_id)
    WHERE target_heading_node_id IS NOT NULL;
CREATE UNIQUE INDEX uq_approved_change_absence_target
    ON approved_change (change_set_id, absence_concept)
    WHERE absence_concept IS NOT NULL;
