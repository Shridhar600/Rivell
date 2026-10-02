-- M10B-B1 (Home read models): organization-wide ordered keyset pagination for
-- GET /api/reviews without a contractId filter, and for GET /api/contracts.
--
-- Why this is needed. GAP-A's existing index is
--   idx_review_org_contract (organization_id, contract_id, created_at DESC, id)
-- (V11:40). contract_id sits BETWEEN the equality column and the sort columns, so that
-- index cannot serve an organization-wide ordered scan; and idx_review_org_active was
-- dropped in V20. GAP-F has only idx_contract_organization (organization_id) (V2:51),
-- which carries no ordering columns at all. Both lists resume with a keyset predicate
-- on (created_at, id), which is only efficient when the index is in the same direction
-- as the query's ORDER BY -- hence id DESC rather than the default ASC. A btree declared
-- (created_at DESC, id) yields (DESC, ASC) forward and (ASC, DESC) backward, matching
-- neither scan direction of an ORDER BY created_at DESC, id DESC.
--
-- Additive: no column, constraint or data change. Neither index is a prefix of any
-- declared UNIQUE (checked: review UNIQUE (id, organization_id), uq_review_identity_org,
-- uq_review_client_request; contract has only its primary key), so neither repeats V20's
-- redundancy problem. Every aggregate the list queries is already index-supported:
-- review_plan_check via idx_review_plan_check_review, review_check_execution via
-- UNIQUE (run_id, plan_check_id), and raw_review_signal / finding via the UNIQUE
-- constraints V20 removed the duplicate indexes for.

CREATE INDEX idx_review_org_created
    ON review (organization_id, created_at DESC, id DESC);

-- Ordering columns are required, so this supersedes the prefix-only index exactly as
-- V20 removed idx_review_org_active. One index, one write cost.
CREATE INDEX idx_contract_org_created
    ON contract (organization_id, created_at DESC, id DESC);
DROP INDEX IF EXISTS idx_contract_organization;