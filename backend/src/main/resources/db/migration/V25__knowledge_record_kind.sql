-- Knowledge record kind (design 2026-10-10 generalization, §5 item 3): what one compiled
-- record is, decided per record by knowledge-record-kind. Only POSITION records (and
-- WORDING records in a clause library) become candidates; every other kind stays an
-- extraction unit with its kind visible, so non-rule content is never silently dropped.
-- Nullable: records compiled before this column, and records whose kind decision could
-- not run, keep an honest unknown.

ALTER TABLE knowledge_extraction_unit
    ADD COLUMN record_kind TEXT
    CHECK (record_kind IS NULL OR record_kind IN (
        'POSITION', 'WORDING', 'ESCALATION', 'GUIDANCE', 'DOCUMENT_CONTROL', 'OTHER'));
