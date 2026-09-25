-- M7 finding evidence (G-M7-1): the exact frozen evidence items cited by a run's checks.
-- Items are snapshotted per run and plan check at analysis time, so Finding evidence
-- resolves from the original generation and never from latest knowledge. Bounded to
-- the items actually cited; retrieval traces stay M6-authoritative and internal.

CREATE TABLE review_evidence_item (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    review_id UUID NOT NULL,
    run_id UUID NOT NULL,
    plan_check_id UUID NOT NULL,
    evidence_item_id UUID NOT NULL,
    authority_class TEXT NOT NULL,
    source_clause_id UUID,
    publication_number BIGINT,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, organization_id),
    UNIQUE (run_id, plan_check_id, evidence_item_id)
);

ALTER TABLE review_evidence_item
    ADD CONSTRAINT fk_review_evidence_item_review_org
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id);
ALTER TABLE review_evidence_item
    ADD CONSTRAINT fk_review_evidence_item_run_review_org
    FOREIGN KEY (run_id, review_id, organization_id)
    REFERENCES review_analysis_run (id, review_id, organization_id);
ALTER TABLE review_evidence_item
    ADD CONSTRAINT fk_review_evidence_item_plan_check_org
    FOREIGN KEY (plan_check_id, organization_id)
    REFERENCES review_plan_check (id, organization_id);

CREATE INDEX idx_review_evidence_item_run_item
    ON review_evidence_item (run_id, evidence_item_id);
CREATE INDEX idx_review_evidence_item_review
    ON review_evidence_item (organization_id, review_id);
