-- Supply, services, reseller, order form and amendment are now declared contract types. Widen
-- the snapshot check so a review created with any of them can be stored.
ALTER TABLE review_snapshot DROP CONSTRAINT review_snapshot_contract_type_check;
ALTER TABLE review_snapshot ADD CONSTRAINT review_snapshot_contract_type_check
    CHECK (contract_type IN ('NDA', 'MNDA', 'ONDA', 'MSA', 'SAAS', 'DPA', 'SOW', 'SWL',
        'SUPPLY', 'SERVICES', 'RESELLER', 'ORDER_FORM', 'AMENDMENT', 'OTHER'));
