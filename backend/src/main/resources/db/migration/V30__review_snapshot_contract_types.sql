-- Contract types now follow the taxonomy codes (ContractType enum); keep the column checked.
ALTER TABLE review_snapshot DROP CONSTRAINT review_snapshot_contract_type_check;
ALTER TABLE review_snapshot ADD CONSTRAINT review_snapshot_contract_type_check
    CHECK (contract_type IN ('NDA', 'MNDA', 'ONDA', 'MSA', 'SAAS', 'DPA', 'SOW', 'SWL', 'OTHER'));
