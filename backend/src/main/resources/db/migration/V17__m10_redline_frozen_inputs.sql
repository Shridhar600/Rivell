-- M10 correctness hardening. Additive only: no destructive change, no history rewrite.
--
-- (1) Frozen-input provenance for deterministic redline.
--     M8 finalization now records the review snapshot's semantics digest on the frozen
--     change set, and M9 records the digest it actually used. That makes the INSERT
--     placement strategy's inputs auditable and lets execution fail closed when the
--     contract semantics have drifted since the review froze.
--
-- (2) Tenant integrity for redline_execution.contract_id / contract_version_id.
--     V16 gave every other M9 table a composite organisation-scoped foreign key, but left
--     the contract reference on the newest table unconstrained, so a cross-organisation or
--     wrong-version contract reference was schema-possible. Closed the same way the rest
--     of the schema is closed.

-- ---------------------------------------------------------------- (1) frozen digest

ALTER TABLE review_finalization ADD COLUMN semantics_digest TEXT;

UPDATE review_finalization finalization
   SET semantics_digest = snapshot.semantics_digest
  FROM review_snapshot snapshot
 WHERE snapshot.review_id = finalization.review_id;

ALTER TABLE review_finalization
    ALTER COLUMN semantics_digest SET NOT NULL,
    ADD CONSTRAINT ck_review_finalization_semantics_digest
        CHECK (semantics_digest ~ '^[0-9a-f]{64}$');

ALTER TABLE redline_execution ADD COLUMN semantics_digest TEXT;

UPDATE redline_execution execution
   SET semantics_digest = finalization.semantics_digest
  FROM review_finalization finalization
 WHERE finalization.id = execution.change_set_id;

ALTER TABLE redline_execution
    ALTER COLUMN semantics_digest SET NOT NULL,
    ADD CONSTRAINT ck_redline_execution_semantics_digest
        CHECK (semantics_digest ~ '^[0-9a-f]{64}$');

-- ---------------------------------------------------------------- (2) redline tenant FK

-- The composite key M17 needs does not exist yet: V3 declared UNIQUE (id, organization_id)
-- on contract_version but not the contract-scoped variant the redline reference needs.
ALTER TABLE contract_version
    ADD CONSTRAINT uq_contract_version_id_contract_org
        UNIQUE (id, contract_id, organization_id);

ALTER TABLE redline_execution
    ADD CONSTRAINT fk_redline_execution_version_org
        FOREIGN KEY (contract_version_id, contract_id, organization_id)
        REFERENCES contract_version (id, contract_id, organization_id);
