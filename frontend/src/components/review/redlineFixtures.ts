import type {
  RedlineExecution,
  RedlineExecutionChange,
  RedlineFailureCode,
} from '../../api/redline'

export function fixtureRedlineChange(
  overrides: Partial<RedlineExecutionChange> = {},
): RedlineExecutionChange {
  return {
    approvedChangeId: 'ch-1',
    ordinal: 1,
    actionType: 'REPLACE',
    targetKind: 'CLAUSE',
    status: 'APPLIED',
    verification: 'VERIFIED',
    failureCode: null,
    placement: null,
    resolvedTarget: '8. Limitation of Liability',
    paragraphsDeleted: 2,
    paragraphsInserted: 2,
    ...overrides,
  }
}

export function fixtureRedlineExecution(
  overrides: Partial<RedlineExecution> = {},
): RedlineExecution {
  return {
    id: 're-1',
    reviewId: 'r-1',
    changeSetId: 'cs-1',
    contractId: 'c1',
    contractVersionNumber: 1,
    changeSetVersion: 1,
    attempt: 1,
    status: 'COMPLETED',
    outcome: 'REDLINE_GENERATED',
    failureCode: null,
    failureDetail: null,
    rendererVersion: 'rivell-redline-renderer-1',
    placementStrategyVersion: 'rivell-insert-placement-1',
    sourceSha256: 'a'.repeat(64),
    changeCount: 1,
    requestedBy: 'u-1',
    startedAt: '2026-09-26T13:00:00Z',
    completedAt: '2026-09-26T13:00:02Z',
    artifactFilename: 'acme-msa-v1-redline.docx',
    artifactSizeBytes: 4403,
    artifactSha256: 'b'.repeat(64),
    downloadAvailable: true,
    changes: [fixtureRedlineChange()],
    ...overrides,
  }
}

export function fixtureMixedRedlineExecution(): RedlineExecution {
  return fixtureRedlineExecution({
    changeCount: 3,
    changes: [
      fixtureRedlineChange(),
      fixtureRedlineChange({
        approvedChangeId: 'ch-2',
        ordinal: 2,
        actionType: 'INSERT',
        targetKind: 'ABSENCE',
        placement: 'AFTER_CLAUSE[CONFIDENTIALITY_OBLIGATIONS]',
        resolvedTarget: 'CONFIDENTIALITY_OBLIGATIONS',
        paragraphsDeleted: 0,
        paragraphsInserted: 1,
      }),
      fixtureRedlineChange({
        approvedChangeId: 'ch-3',
        ordinal: 3,
        actionType: 'DELETE',
        targetKind: 'CLAUSE',
        resolvedTarget: '5. Term and Renewal',
        paragraphsDeleted: 3,
        paragraphsInserted: 0,
      }),
    ],
  })
}

export function fixtureFailedRedlineExecution(
  failureCode: RedlineFailureCode = 'PREEXISTING_REVISIONS_UNSUPPORTED',
  overrides: Partial<RedlineExecution> = {},
): RedlineExecution {
  return fixtureRedlineExecution({
    status: 'FAILED',
    outcome: null,
    failureCode,
    failureDetail: 'The document contains existing tracked revisions.',
    artifactFilename: null,
    artifactSizeBytes: null,
    artifactSha256: null,
    downloadAvailable: false,
    changes: [
      fixtureRedlineChange({
        status: 'FAILED',
        verification: null,
        failureCode,
        resolvedTarget: null,
        paragraphsDeleted: null,
        paragraphsInserted: null,
      }),
    ],
    ...overrides,
  })
}
