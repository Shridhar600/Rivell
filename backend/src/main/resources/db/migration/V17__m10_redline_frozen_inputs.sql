-- M10 correctness hardening. Additive only: no destructive change, no history rewrite.
--
-- (1) Frozen-input provenance for deterministic redline.
--     M8 finalization records the review snapshot's semantics digest on the frozen change set, and
--     M9 records the digest it verified against live semantics. That makes the INSERT placement
--     strategy's inputs auditable and lets execution fail closed when the contract semantics have
--     drifted since the review froze.
--
-- (2) Tenant integrity for redline_execution.contract_id / contract_version_id.
--     V16 gave every other M9 table an organisation-scoped composite foreign key but left the
--     contract reference on the newest table unconstrained, so a cross-organisation or wrong-version
--     contract reference was schema-possible. Closed with the same four-column key V11 already uses,
--     which binds organisation, contract AND version in one constraint.

-- ---------------------------------------------------------------- (1) frozen digest

ALTER TABLE review_finalization ADD COLUMN semantics_digest TEXT;

UPDATE review_finalization finalization
   SET semantics_digest = snapshot.semantics_digest
  FROM review_snapshot snapshot
 WHERE snapshot.review_id = finalization.review_id;

-- Fail loudly, and name the problem, rather than aborting later at SET NOT NULL with a bare
-- constraint error. A row here means a finalization exists whose review snapshot does not, which is
-- an application invariant violation and must be resolved before the schema is tightened.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM review_finalization WHERE semantics_digest IS NULL) THEN
        RAISE EXCEPTION
            'review_finalization rows exist without a review_snapshot to backfill from; '
            'resolve before applying this migration';
    END IF;
END $$;

ALTER TABLE review_finalization
    ALTER COLUMN semantics_digest SET NOT NULL,
    ADD CONSTRAINT ck_review_finalization_semantics_digest
        CHECK (semantics_digest ~ '^[0-9a-f]{64}$');

ALTER TABLE redline_execution ADD COLUMN semantics_digest TEXT;

UPDATE redline_execution execution
   SET semantics_digest = finalization.semantics_digest
  FROM review_finalization finalization
 WHERE finalization.id = execution.change_set_id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM redline_execution WHERE semantics_digest IS NULL) THEN
        RAISE EXCEPTION
            'redline_execution rows exist without a review_finalization to backfill from; '
            'resolve before applying this migration';
    END IF;
END $$;

ALTER TABLE redline_execution
    ALTER COLUMN semantics_digest SET NOT NULL,
    ADD CONSTRAINT ck_redline_execution_semantics_digest
        CHECK (semantics_digest ~ '^[0-9a-f]{64}$');

-- ---------------------------------------------------------------- (2) redline tenant FK

-- V11 already declared UNIQUE (id, contract_id, version_number, organization_id) on contract_version
-- as uq_contract_version_identity_org. Reusing it binds the version number as well as the contract
-- and the organisation, so redline_execution.contract_version_number cannot drift from the version
-- it points at, and no redundant index is needed.
ALTER TABLE redline_execution
    ADD CONSTRAINT fk_redline_execution_version_org
        FOREIGN KEY (contract_version_id, contract_id, contract_version_number,
                      organization_id)
        REFERENCES contract_version (id, contract_id, version_number, organization_id);
