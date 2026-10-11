-- Independent check of how much of an uploaded knowledge source's text the extracted structure
-- holds. NULL for artifacts extracted before the check existed or when it could not run.
ALTER TABLE knowledge_artifact ADD COLUMN parse_coverage JSONB;
