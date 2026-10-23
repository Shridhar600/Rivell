-- Declared review jurisdiction covers jurisdictions beyond the United States and the
-- United Kingdom. Values stored before this migration (US, UK, UNSPECIFIED) stay valid.
ALTER TABLE review_snapshot DROP CONSTRAINT review_snapshot_jurisdiction_check;
ALTER TABLE review_snapshot ADD CONSTRAINT review_snapshot_jurisdiction_check
    CHECK (jurisdiction IN ('US', 'UK', 'AUSTRALIA', 'CANADA', 'IRELAND', 'INDIA',
        'NEW_ZEALAND', 'SINGAPORE', 'HONG_KONG', 'GERMANY', 'FRANCE', 'NETHERLANDS',
        'OTHER', 'UNSPECIFIED'));
