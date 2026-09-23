-- Knowledge semantics (M4): machine-interpreted semantic fields with provenance,
-- classified concepts, and deterministic review-risk categorization.
-- Machine proposals and human corrections are stored side by side; interpreted fields
-- never overwrite raw source content.

ALTER TABLE knowledge_artifact
    ADD COLUMN artifact_assessment JSONB;

ALTER TABLE knowledge_candidate
    ADD COLUMN semantic_fields JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE knowledge_candidate
    ADD COLUMN edited_semantic_fields JSONB;
ALTER TABLE knowledge_candidate
    ADD COLUMN interpretations JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE knowledge_candidate
    ADD COLUMN review_category TEXT CHECK (review_category IN ('ROUTINE', 'ELEVATED', 'HIGH'));
ALTER TABLE knowledge_candidate
    ADD COLUMN review_reasons JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_knowledge_candidate_review_category
    ON knowledge_candidate (organization_id, review_category);
