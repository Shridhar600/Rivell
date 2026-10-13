-- Engine v2 omissions lane (P4): one plan check that reads the run's clause checks and
-- judges the published positions no clause addressed. The OMISSION mechanism joins the
-- existing constraint values; the legacy values stay admissible for pre-change rows.
alter table review_plan_check drop constraint if exists review_plan_check_mechanism_check;
alter table review_plan_check add constraint review_plan_check_mechanism_check
    check (mechanism in ('PRESENCE', 'LIABILITY_CAP', 'POLICY_COMPARISON',
        'CONFIDENTIALITY_SURVIVAL', 'CARVE_OUT_CONSISTENCY', 'OMISSION'));
