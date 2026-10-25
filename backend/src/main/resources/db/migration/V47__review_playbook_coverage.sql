-- R3-002 coverage list: what one analysis run did with every applicable published playbook
-- position (compared with a verdict, searched and not found, not compared, out of scope).
-- Written once when the run finishes, from the run's own check detail; null on runs that predate
-- the column, so a reader never invents coverage for them.
ALTER TABLE review_analysis_run
    ADD COLUMN playbook_coverage JSONB;
