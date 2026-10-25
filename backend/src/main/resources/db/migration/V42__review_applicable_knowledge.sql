-- A review freezes the published playbooks and clause libraries the analysis may use, with the
-- knowledge version that made each applicable. The marker separates "this review froze that none
-- applied" from a review created before the freeze existed: old reviews must not be recomputed.
ALTER TABLE review_snapshot
    ADD COLUMN applicable_knowledge_frozen BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE review_applicable_knowledge (
    review_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    artifact_id UUID NOT NULL,
    artifact_type TEXT NOT NULL CHECK (artifact_type IN ('PLAYBOOK', 'CLAUSE_LIBRARY')),
    name TEXT NOT NULL,
    publication_number BIGINT NOT NULL CHECK (publication_number >= 1),
    PRIMARY KEY (review_id, artifact_id),
    UNIQUE (review_id, ordinal),
    FOREIGN KEY (review_id, organization_id) REFERENCES review (id, organization_id),
    FOREIGN KEY (artifact_id, organization_id)
        REFERENCES knowledge_artifact (id, organization_id)
);

CREATE INDEX idx_review_applicable_knowledge_review
    ON review_applicable_knowledge (review_id, ordinal);
