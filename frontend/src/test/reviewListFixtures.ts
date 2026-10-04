/**
 * Wire-format Review list rows, as `GET /api/reviews` returns them. The tests that read Home or the
 * Reviews list build their rows here so every one of them speaks the real contract.
 */

export const CONTEXT = { contractType: 'MSA', ourSideRole: 'CUSTOMER', paperSource: 'COUNTERPARTY_PAPER', jurisdiction: 'UK' }

export function humanWire(overrides: Record<string, unknown> = {}) {
  return {
    status: 'IN_PROGRESS',
    actionableFindings: 8,
    resolvedFindings: 5,
    remainingFindings: 3,
    escalated: 0,
    readyToFinalize: false,
    blockers: ['UNRESOLVED_REVIEW_REQUIRED_FINDINGS'],
    ...overrides,
  }
}

export function reviewWire(id: string, status: string, filename: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    contractId: `con-${id}`,
    contractVersionNumber: 1,
    contractFilename: filename,
    uploadedAt: '2026-10-02T07:30:00Z',
    context: CONTEXT,
    humanReview: null,
    finalization: null,
    redline: null,
    status,
    failure: null,
    createdAt: '2026-10-02T08:00:00Z',
    startedAt: null,
    completedAt: status === 'COMPLETED' || status === 'FAILED' ? '2026-10-02T09:00:00Z' : null,
    activeRunNumber: null,
    activeRunStatus: null,
    latestRunStatus: null,
    plannedChecks: 6,
    completedChecks: status === 'COMPLETED' ? 6 : 0,
    degradedChecks: 0,
    failedChecks: 0,
    signals: 0,
    findings: 0,
    ...overrides,
  }
}

/** One Review for every lifecycle state the product can report, keyed by what it should look like. */
export const LIFECYCLE_ROWS = {
  queued: reviewWire('q1', 'ANALYSIS_QUEUED', 'Queued NDA.docx', {
    humanReview: humanWire({ status: 'NOT_STARTED', blockers: ['ANALYSIS_NOT_COMPLETE'] }),
  }),
  analyzing: reviewWire('a1', 'ANALYZING', 'Analyzing MSA.docx', {
    startedAt: '2026-10-02T08:05:00Z',
    humanReview: humanWire({ status: 'NOT_STARTED', blockers: ['ANALYSIS_NOT_COMPLETE'] }),
  }),
  analysisFailed: reviewWire('f1', 'FAILED', 'Failed DPA.docx'),
  unresolved: reviewWire('u1', 'COMPLETED', 'Unresolved MSA.docx', { findings: 8, humanReview: humanWire() }),
  notStarted: reviewWire('n1', 'COMPLETED', 'Fresh NDA.docx', {
    findings: 6,
    humanReview: humanWire({ status: 'NOT_STARTED', resolvedFindings: 0, remainingFindings: 6, actionableFindings: 6 }),
  }),
  escalated: reviewWire('e1', 'COMPLETED', 'Escalated Order Form.docx', {
    findings: 5,
    humanReview: humanWire({ escalated: 1, blockers: ['ACTIVE_ESCALATION'] }),
  }),
  ready: reviewWire('r1', 'COMPLETED', 'Ready SOW.docx', {
    findings: 4,
    humanReview: humanWire({ status: 'COMPLETE', resolvedFindings: 4, actionableFindings: 4, remainingFindings: 0, readyToFinalize: true, blockers: [] }),
  }),
  degraded: reviewWire('d1', 'COMPLETED', 'Degraded Lease.docx', {
    humanReview: humanWire({ blockers: ['ANALYSIS_DEGRADED'] }),
  }),
  finalized: reviewWire('x1', 'COMPLETED', 'Finalized Services.docx', {
    findings: 5,
    humanReview: humanWire({ status: 'COMPLETE', readyToFinalize: false, blockers: [] }),
    finalization: { finalizedAt: '2026-10-03T10:00:00Z', approvedChanges: 3 },
  }),
  finalizedNoChange: reviewWire('z1', 'COMPLETED', 'No Change NDA.docx', {
    findings: 4,
    humanReview: humanWire({ status: 'COMPLETE', blockers: [] }),
    finalization: { finalizedAt: '2026-10-02T12:00:00Z', approvedChanges: 0 },
  }),
  verified: reviewWire('v1', 'COMPLETED', 'Verified Procurement.docx', {
    findings: 7,
    humanReview: humanWire({ status: 'COMPLETE', blockers: [] }),
    finalization: { finalizedAt: '2026-10-04T08:30:00Z', approvedChanges: 7 },
    redline: { status: 'COMPLETED', outcome: 'REDLINE_GENERATED', verifiedChangeCount: 7, downloadAvailable: true, completedAt: '2026-10-04T08:31:00Z' },
  }),
  redlineFailed: reviewWire('w1', 'COMPLETED', 'Redline Failed Vendor.docx', {
    findings: 3,
    humanReview: humanWire({ status: 'COMPLETE', blockers: [] }),
    finalization: { finalizedAt: '2026-10-04T09:00:00Z', approvedChanges: 3 },
    redline: { status: 'FAILED', outcome: null, verifiedChangeCount: null, downloadAvailable: false, completedAt: '2026-10-04T09:01:00Z' },
  }),
} as const
