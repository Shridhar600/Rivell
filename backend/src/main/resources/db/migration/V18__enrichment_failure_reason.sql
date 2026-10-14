-- M10: a FAILED contract semantic enrichment must carry a structural reason.
--
-- Previously the only trace was a warning string built from the exception class name
-- ("Enrichment failed (ProviderUnavailableException)"). That is not a contract, is not
-- queryable, and does not tell a caller whether the failure is worth retrying. This adds a
-- code-owned reason column. Existing rows are backfilled to INTERNAL rather than left null,
-- so no consumer has to handle a third state.

ALTER TABLE contract_semantics
    ADD COLUMN enrichment_failure_reason TEXT;

UPDATE contract_semantics
   SET enrichment_failure_reason = 'INTERNAL'
 WHERE enrichment_status = 'FAILED'
   AND enrichment_failure_reason IS NULL;

ALTER TABLE contract_semantics
    ADD CONSTRAINT ck_contract_semantics_failure_reason
        CHECK ((enrichment_status = 'FAILED') = (enrichment_failure_reason IS NOT NULL));
