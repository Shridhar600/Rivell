-- P2b (design 2026-10-12 §9): the user adds a finding Rivell missed. It is an ordinary
-- finding of the active run, so decisions, finalization and redline treat it like any other;
-- added_by records who added it (null = assembled by Rivell from its checks).
ALTER TABLE finding ADD COLUMN added_by UUID;

ALTER TABLE finding DROP CONSTRAINT IF EXISTS finding_issue_family_check;
ALTER TABLE finding ADD CONSTRAINT finding_issue_family_check
    CHECK (issue_family IN ('LIABILITY_CAP_DEVIATION', 'LIABILITY_CAP_UNCERTAIN',
        'MISSING_REQUIRED_PROVISION', 'EXPECTED_PROVISION_ABSENT', 'SURVIVAL_COVERAGE_GAP',
        'CARVE_OUT_INCONSISTENCY', 'POLICY_UNAVAILABLE', 'POLICY_AMBIGUOUS',
        'OPEN_ENDED_LIABILITY', 'POSITION_WALK_AWAY', 'POSITION_NEEDS_FALLBACK',
        'POSITION_ACCEPTABLE_FALLBACK', 'POSITION_AMBIGUOUS', 'POSITION_UNCERTAIN',
        'OPEN_ENDED_POSITION', 'USER_ADDED'));
ALTER TABLE finding ADD CONSTRAINT finding_user_added_has_author
    CHECK ((issue_family = 'USER_ADDED') = (added_by IS NOT NULL));
