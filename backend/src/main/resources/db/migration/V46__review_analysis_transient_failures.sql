-- R4/R2: a transient infrastructure error must not kill a review. The run stays RUNNING and
-- records how many transient failures it has survived plus the last one's code-owned reason;
-- JobRunr retries analyze() and the existing terminal-check skip resumes the same run.

ALTER TABLE review_analysis_run
    ADD COLUMN transient_failures INTEGER NOT NULL DEFAULT 0
        CHECK (transient_failures >= 0),
    ADD COLUMN last_transient_reason TEXT;
