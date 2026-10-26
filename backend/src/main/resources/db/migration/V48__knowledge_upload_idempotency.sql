-- A client-supplied idempotency key makes a retried knowledge upload return the artifact the
-- first attempt stored instead of compiling and storing a second one. The key is optional: a
-- client that does not send one keeps the existing behaviour. NULLs do not collide under a
-- unique index, so unrelated uploads stay independent.
ALTER TABLE knowledge_artifact
    ADD COLUMN client_request_id UUID;

CREATE UNIQUE INDEX uq_knowledge_artifact_client_request
    ON knowledge_artifact (organization_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
