-- P2b (design 2026-10-12 §9): a person corrects the record kind of a part Rivell did not take
-- as a position ("make this a position"). The machine's kind stays in record_kind; the
-- correction and its author sit beside it.
ALTER TABLE knowledge_extraction_unit
    ADD COLUMN record_kind_override TEXT
        CHECK (record_kind_override IS NULL OR record_kind_override IN ('POSITION', 'WORDING')),
    ADD COLUMN record_kind_overridden_by UUID,
    ADD COLUMN record_kind_overridden_at TIMESTAMPTZ,
    ADD CONSTRAINT knowledge_unit_override_has_author CHECK (
        (record_kind_override IS NULL) = (record_kind_overridden_by IS NULL)
        AND (record_kind_override IS NULL) = (record_kind_overridden_at IS NULL));
