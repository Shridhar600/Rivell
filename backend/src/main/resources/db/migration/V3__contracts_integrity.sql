-- Integrity invariants: parents and versions must stay within the same contract version
-- and the same organization.

ALTER TABLE document_node ADD COLUMN numbering_label TEXT;

ALTER TABLE contract ADD CONSTRAINT uq_contract_id_org UNIQUE (id, organization_id);
ALTER TABLE source_artifact ADD CONSTRAINT uq_source_artifact_id_org UNIQUE (id, organization_id);
ALTER TABLE document_node ADD CONSTRAINT uq_document_node_id_version UNIQUE (id, contract_version_id);

ALTER TABLE contract_version ADD COLUMN organization_id UUID;
UPDATE contract_version cv
SET organization_id = c.organization_id
FROM contract c
WHERE c.id = cv.contract_id;
ALTER TABLE contract_version ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE contract_version ALTER COLUMN parser_version DROP NOT NULL;

ALTER TABLE contract_version
    ADD CONSTRAINT fk_contract_version_contract_org
    FOREIGN KEY (contract_id, organization_id) REFERENCES contract (id, organization_id);
ALTER TABLE contract_version
    ADD CONSTRAINT fk_contract_version_artifact_org
    FOREIGN KEY (source_artifact_id, organization_id) REFERENCES source_artifact (id, organization_id);

ALTER TABLE document_node
    ADD CONSTRAINT fk_document_node_parent_same_version
    FOREIGN KEY (parent_id, contract_version_id) REFERENCES document_node (id, contract_version_id);

ALTER TABLE contract_version
    ADD CONSTRAINT ck_contract_version_role CHECK (role IN ('ORIGINAL')),
    ADD CONSTRAINT ck_contract_version_parse_status CHECK (parse_status IN ('PARSING', 'OK', 'FAILED'));
