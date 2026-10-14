-- Retry Redline: the frozen ApprovedChangeSet is unchanged and every attempt is preserved.
--
-- A retry APPENDS a numbered attempt. It never updates or deletes the prior FAILED row, so
-- the recorded evidence of that failure stays queryable. The alternative -- deleting or
-- overwriting the failed row -- would erase the failure and make a retry indistinguishable
-- from a first attempt, which is exactly what the retry must not do.
--
-- Why no child table changes: redline_execution_change and redline_artifact are already
-- keyed by execution_id, so attempt history needs no change there and no new join.

ALTER TABLE redline_execution ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1;

-- V16 made a second execution for one change set structurally impossible. Replace that
-- single-row invariant with an ordered attempt series. The dropped constraint's generated
-- name is asserted by RedlineSchemaIT, so it is named explicitly rather than left implicit.
ALTER TABLE redline_execution DROP CONSTRAINT redline_execution_change_set_id_key;

ALTER TABLE redline_execution
    ADD CONSTRAINT ck_redline_execution_attempt_number CHECK (attempt_number >= 1),
    ADD CONSTRAINT uq_redline_execution_change_set_attempt UNIQUE (change_set_id, attempt_number);

-- At most one COMPLETED attempt per change set, enforced by the schema rather than by the
-- retry guard alone. Two verified artifacts for one frozen change set is never a legitimate
-- state, so the database refuses it even if a caller reaches that path.
--
-- This index is deliberately NOT the ON CONFLICT arbiter: a statement that breaks it must be
-- reported, not silently absorbed. The repository translates that duplicate key into the same
-- lost-race outcome the arbiter produces, so a concurrent retry still reads back the winner.
CREATE UNIQUE INDEX uq_redline_execution_single_success
    ON redline_execution (change_set_id)
    WHERE status = 'COMPLETED';

-- The default is dropped again so the column can never be populated implicitly. Every insert
-- names its attempt explicitly, and a writer that forgets now fails loudly instead of
-- colliding with the real first attempt.
ALTER TABLE redline_execution ALTER COLUMN attempt_number DROP DEFAULT;

-- Deployment note: migrate before starting the new binary. Rolling the application back after
-- this migration is NOT safe, because the previous release's insert names
-- ON CONFLICT (change_set_id) and that constraint no longer exists.
