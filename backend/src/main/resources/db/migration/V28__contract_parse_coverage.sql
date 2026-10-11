-- Independent check of how much of the uploaded file's text the parsed structure holds.
-- NULL for versions parsed before the check existed or when the check could not run.
ALTER TABLE contract_version ADD COLUMN parse_coverage JSONB;
