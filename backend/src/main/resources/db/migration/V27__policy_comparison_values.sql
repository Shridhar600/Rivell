-- Policy comparison replaces the liability-only mechanism (design 6a). The legacy
-- values stay admissible only so this constraint change succeeds on databases that
-- still hold pre-change rows; the readers no longer map them and no new code writes
-- them (design: local/demo data is reseeded, no production data exists).
alter table review_plan_check drop constraint if exists review_plan_check_mechanism_check;
alter table review_plan_check add constraint review_plan_check_mechanism_check
    check (mechanism in ('PRESENCE', 'LIABILITY_CAP', 'POLICY_COMPARISON',
        'CONFIDENTIALITY_SURVIVAL', 'CARVE_OUT_CONSISTENCY'));

alter table raw_review_signal drop constraint if exists raw_review_signal_issue_family_check;
alter table raw_review_signal add constraint raw_review_signal_issue_family_check
    check (issue_family in ('LIABILITY_CAP_DEVIATION', 'LIABILITY_CAP_UNCERTAIN',
        'MISSING_REQUIRED_PROVISION', 'EXPECTED_PROVISION_ABSENT', 'SURVIVAL_COVERAGE_GAP',
        'CARVE_OUT_INCONSISTENCY', 'POLICY_UNAVAILABLE', 'POLICY_AMBIGUOUS',
        'OPEN_ENDED_LIABILITY', 'POSITION_WALK_AWAY', 'POSITION_NEEDS_FALLBACK',
        'POSITION_ACCEPTABLE_FALLBACK', 'POSITION_AMBIGUOUS', 'POSITION_UNCERTAIN',
        'OPEN_ENDED_POSITION'));

alter table finding drop constraint if exists finding_issue_family_check;
alter table finding add constraint finding_issue_family_check
    check (issue_family in ('LIABILITY_CAP_DEVIATION', 'LIABILITY_CAP_UNCERTAIN',
        'MISSING_REQUIRED_PROVISION', 'EXPECTED_PROVISION_ABSENT', 'SURVIVAL_COVERAGE_GAP',
        'CARVE_OUT_INCONSISTENCY', 'POLICY_UNAVAILABLE', 'POLICY_AMBIGUOUS',
        'OPEN_ENDED_LIABILITY', 'POSITION_WALK_AWAY', 'POSITION_NEEDS_FALLBACK',
        'POSITION_ACCEPTABLE_FALLBACK', 'POSITION_AMBIGUOUS', 'POSITION_UNCERTAIN',
        'OPEN_ENDED_POSITION'));
