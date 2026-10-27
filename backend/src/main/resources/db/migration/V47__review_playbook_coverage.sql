-- Playbook coverage list: what one analysis run did with every applicable published playbook
-- position (compared with a verdict, searched and not found, not compared, out of scope).
-- Written once when the run finishes, from the run's own check detail; null on runs that predate
-- the column and on runs whose coverage could not be computed, so a reader never invents it.
ALTER TABLE review_analysis_run
    ADD COLUMN playbook_coverage JSONB;
