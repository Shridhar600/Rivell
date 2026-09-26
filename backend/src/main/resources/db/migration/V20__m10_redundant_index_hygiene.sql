-- M10 / DW-028: index hygiene. M11-M13 added indexes that a declared UNIQUE already provides a
-- prefix for, so every write pays for them twice. No correctness issue, and no query in the system
-- relies on an index that is not listed here as redundant. Kept as its own migration, separate from
-- the behaviour changes, so a rollback can be attributed to one cause.
--
-- idx_raw_review_signal_run (V12) is a prefix of UNIQUE (run_id, dedupe_key) (V12).
-- idx_finding_run (V13) is a prefix of UNIQUE (run_id, consolidation_key) (V13).
-- idx_review_analysis_run_review (V12) is a prefix of UNIQUE (review_id, run_number) (V12).
-- idx_review_org_active (V13) is a prefix of idx_review_org_covering from V12.

DROP INDEX IF EXISTS idx_raw_review_signal_run;
DROP INDEX IF EXISTS idx_finding_run;
DROP INDEX IF EXISTS idx_review_analysis_run_review;
DROP INDEX IF EXISTS idx_review_org_active;
