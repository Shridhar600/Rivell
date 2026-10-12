-- Contract types a knowledge record itself says it applies to ("Applies to: MSA, SOW"); empty
-- when the record states none. Narrows within the manifest's declared types at review time.
ALTER TABLE knowledge_candidate
    ADD COLUMN source_contract_types JSONB NOT NULL DEFAULT '[]'::jsonb;
