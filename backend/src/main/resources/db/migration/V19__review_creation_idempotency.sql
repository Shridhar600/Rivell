-- M10 / DW-020: review creation accepted no client-supplied idempotency key, so a retry or a
-- double-click created a second review with a second frozen snapshot and a second analysis, at the
-- cost of a second full model run. The key is optional: a client that does not send one keeps the
-- existing behaviour. NULLs do not collide under a unique index, so unrelated creations stay
-- independent.
ALTER TABLE review
    ADD COLUMN client_request_id UUID;

CREATE UNIQUE INDEX uq_review_client_request
    ON review (organization_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
