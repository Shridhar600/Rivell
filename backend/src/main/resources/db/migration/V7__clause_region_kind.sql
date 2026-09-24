-- Semantic region kind (M5 amendment): DocumentNode ≠ automatically Clause (AD #5.3).
-- Signature blocks are kept in the semantics but are never legal clauses.

ALTER TABLE contract_clause_semantics
    ADD COLUMN region_kind TEXT NOT NULL DEFAULT 'CLAUSE'
    CHECK (region_kind IN ('CLAUSE', 'SIGNATURE_BLOCK', 'SCHEDULE'));
