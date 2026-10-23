-- D2: two honest rejection reasons replace the single "Reject" action, and a resolved
-- escalation records who approved the clause. Both are new columns rather than new
-- actions, apart from RESOLVE, so every existing row keeps its meaning.

ALTER TABLE human_decision
    ADD COLUMN reject_reason TEXT;
ALTER TABLE human_decision
    ADD COLUMN approved_by TEXT;

ALTER TABLE human_decision
    DROP CONSTRAINT human_decision_action_check;
ALTER TABLE human_decision
    ADD CONSTRAINT human_decision_action_check
    CHECK (action IN ('ACCEPT', 'EDIT', 'REJECT', 'ESCALATE', 'RESOLVE'));

-- A rejection with a reason carries its meaning in the reason; the old comment
-- requirement stays for a rejection without one, so legacy writers still work.
ALTER TABLE human_decision
    DROP CONSTRAINT human_decision_check4;
ALTER TABLE human_decision
    ADD CONSTRAINT human_decision_comment_check
    CHECK (action NOT IN ('REJECT', 'ESCALATE')
        OR (comment IS NOT NULL AND length(comment) > 0)
        OR (action = 'REJECT' AND reject_reason IS NOT NULL));

ALTER TABLE human_decision
    ADD CONSTRAINT human_decision_reject_reason_check
    CHECK (reject_reason IS NULL
        OR (action = 'REJECT'
            AND reject_reason IN ('KEEP_AS_DRAFTED', 'FINDING_WRONG')));

ALTER TABLE human_decision
    ADD CONSTRAINT human_decision_approved_by_check
    CHECK ((action = 'RESOLVE') = (approved_by IS NOT NULL));
ALTER TABLE human_decision
    ADD CONSTRAINT human_decision_approved_by_length_check
    CHECK (approved_by IS NULL
        OR (length(btrim(approved_by)) > 0 AND length(approved_by) <= 120));
