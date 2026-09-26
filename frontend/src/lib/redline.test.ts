import { describe, expect, it } from 'vitest'
import type { ApprovedChange } from '../api/workflow'
import type { RedlineExecution } from '../api/redline'
import { REDLINE_FAILURE_CODES } from '../api/redline'
import {
  buildRedlineChangeRows,
  classifyTriggerError,
  redlineChangeStatusLabel,
  redlineExecutionTitle,
  redlineFailureTitle,
  redlinePlacementLabel,
  redlineTriggerErrorNotice,
  redlineVerificationLabel,
  redlineVerificationOutcome,
} from './redline'

function fixtureExecution(overrides: Partial<RedlineExecution> = {}): RedlineExecution {
  return {
    id: 're-1',
    reviewId: 'r-1',
    changeSetId: 'cs-1',
    contractId: 'c-1',
    contractVersionNumber: 1,
    changeSetVersion: 1,
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
    changes: [
      {
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
      },
    ],
    ...overrides,
  }
}

const approvedChanges: ApprovedChange[] = [
  {
    id: 'ch-1',
    findingId: 'f-1',
    decisionId: 'd-1',
    ordinal: 1,
    actionType: 'REPLACE',
    targetKind: 'CLAUSE',
    targetHeadingNodeId: 'node-6',
    absenceConcept: null,
    approvedLanguage: 'Liability is capped.',
    languageSource: 'HUMAN_EDIT',
    approvedDraftingEvidenceId: null,
  },
  {
    id: 'ch-2',
    findingId: 'f-2',
    decisionId: 'd-2',
    ordinal: 2,
    actionType: 'INSERT',
    targetKind: 'ABSENCE',
    targetHeadingNodeId: null,
    absenceConcept: 'CONFIDENTIALITY',
    approvedLanguage: 'Confidential information shall be protected.',
    languageSource: 'HUMAN_EDIT',
    approvedDraftingEvidenceId: null,
  },
  {
    id: 'ch-3',
    findingId: 'f-3',
    decisionId: 'd-3',
    ordinal: 3,
    actionType: 'DELETE',
    targetKind: 'CLAUSE',
    targetHeadingNodeId: 'node-5',
    absenceConcept: null,
    approvedLanguage: null,
    languageSource: 'NONE',
    approvedDraftingEvidenceId: null,
  },
]

const regionLabels = new Map([
  ['node-6', '8. Limitation of Liability'],
  ['node-5', '5. Term and Renewal'],
])

describe('execution titles and labels', () => {
  it('uses the backend status and outcome for the execution headline', () => {
    expect(redlineExecutionTitle(fixtureExecution())).toBe('Redline generated')
    expect(
      redlineExecutionTitle(
        fixtureExecution({ outcome: 'NO_CHANGES', downloadAvailable: false, changes: [] }),
      ),
    ).toBe('No document changes')
    expect(
      redlineExecutionTitle(
        fixtureExecution({ status: 'FAILED', outcome: null, downloadAvailable: false }),
      ),
    ).toBe('Redline could not be generated')
    expect(redlineExecutionTitle(fixtureExecution({ status: 'UNKNOWN' }))).toBe(
      'Execution state unknown',
    )
    expect(redlineExecutionTitle(fixtureExecution({ outcome: 'UNKNOWN' }))).toBe(
      'Redline execution completed',
    )
  })

  it('labels change execution and verification states', () => {
    expect(redlineChangeStatusLabel('APPLIED')).toBe('Applied')
    expect(redlineChangeStatusLabel('FAILED')).toBe('Execution failed')
    expect(redlineChangeStatusLabel('NOT_APPLIED')).toBe('Not applied')
    expect(redlineChangeStatusLabel('UNKNOWN')).toBe('Execution state unknown')
    expect(redlineVerificationLabel('VERIFIED')).toBe('Verified')
    expect(redlineVerificationLabel('FAILED')).toBe('Verification failed')
    expect(redlineVerificationLabel(null)).toBeNull()
    expect(redlineVerificationLabel('UNKNOWN')).toBe('Verification state unknown')
  })

  it('maps every failure code to distinct product copy and falls back safely', () => {
    const titles = REDLINE_FAILURE_CODES.map((code) => redlineFailureTitle(code))
    expect(new Set(titles).size).toBe(REDLINE_FAILURE_CODES.length)
    for (const title of titles) {
      expect(title.length).toBeGreaterThan(10)
      expect(title).not.toMatch(/_/)
    }
    expect(redlineFailureTitle('UNKNOWN')).not.toBe(titles[0])
  })

  it('formats backend placement labels without inferring placement', () => {
    expect(redlinePlacementLabel('AFTER_CLAUSE[CONFIDENTIALITY]')).toBe(
      'After clause: Confidentiality',
    )
    expect(redlinePlacementLabel('BEFORE_SIGNATURE_BLOCK')).toBe(
      'Before the signature block',
    )
    expect(redlinePlacementLabel('END_OF_BODY')).toBe('At the end of the document')
    expect(redlinePlacementLabel(null)).toBeNull()
    expect(redlinePlacementLabel('SOMEWHERE_ELSE')).toBe('Placement recorded by Rivell')
  })
})

describe('verification and counts', () => {
  it('reports verification only from applied and verified change rows', () => {
    expect(redlineVerificationOutcome(fixtureExecution())).toBe('PASSED')
    const partiallyVerified = fixtureExecution({
      changes: [
        { ...fixtureExecution().changes[0], verification: 'FAILED', status: 'APPLIED' },
      ],
    })
    expect(redlineVerificationOutcome(partiallyVerified)).toBe('INCOMPLETE')
    const mismatched = fixtureExecution({
      changeCount: 3,
      changes: [fixtureExecution().changes[0]],
    })
    expect(redlineVerificationOutcome(mismatched)).toBe('INCOMPLETE')
    const noRows = fixtureExecution({ changeCount: 1, changes: [] })
    expect(redlineVerificationOutcome(noRows)).toBe('INCOMPLETE')
    expect(
      redlineVerificationOutcome(
        fixtureExecution({ outcome: 'NO_CHANGES', downloadAvailable: false, changes: [] }),
      ),
    ).toBe('NOT_APPLICABLE')
    expect(
      redlineVerificationOutcome(
        fixtureExecution({ status: 'FAILED', outcome: null, downloadAvailable: false }),
      ),
    ).toBe('NOT_APPLICABLE')
  })

})

describe('change report rows', () => {
  it('builds semantic rows joined to the frozen approved changes', () => {
    const execution = fixtureExecution({
      changeCount: 3,
      changes: [
        fixtureExecution().changes[0],
        {
          approvedChangeId: 'ch-2',
          ordinal: 2,
          actionType: 'INSERT',
          targetKind: 'ABSENCE',
          status: 'APPLIED',
          verification: 'VERIFIED',
          failureCode: null,
          placement: 'AFTER_CLAUSE[CONFIDENTIALITY]',
          resolvedTarget: 'CONFIDENTIALITY',
          paragraphsDeleted: 0,
          paragraphsInserted: 1,
        },
        {
          approvedChangeId: 'ch-3',
          ordinal: 3,
          actionType: 'DELETE',
          targetKind: 'CLAUSE',
          status: 'APPLIED',
          verification: 'VERIFIED',
          failureCode: null,
          placement: null,
          resolvedTarget: '5. Term and Renewal',
          paragraphsDeleted: 3,
          paragraphsInserted: 0,
        },
      ],
    })
    const rows = buildRedlineChangeRows(execution, {
      approvedChanges,
      regionLabels,
    })
    expect(rows).toHaveLength(3)
    expect(rows[0]).toMatchObject({
      ordinal: 1,
      actionLabel: 'Replace clause',
      targetLabel: '8. Limitation of Liability',
      statusLabel: 'Applied',
      verificationLabel: 'Verified',
      placementLabel: null,
      paragraphSummary: '2 paragraphs replaced',
      findingId: 'f-1',
    })
    expect(rows[1]).toMatchObject({
      actionLabel: 'Insert provision',
      targetLabel: 'Missing provision: Confidentiality',
      placementLabel: 'After clause: Confidentiality',
      paragraphSummary: '1 paragraph inserted',
      findingId: 'f-2',
    })
    expect(rows[2]).toMatchObject({
      actionLabel: 'Delete clause',
      targetLabel: '5. Term and Renewal',
      paragraphSummary: '3 paragraphs deleted',
      findingId: 'f-3',
    })
  })

  it('prefers the frozen approved target label for INSERT over the raw resolved concept', () => {
    const execution = fixtureExecution({
      changeCount: 1,
      changes: [
        {
          ...fixtureExecution().changes[0],
          approvedChangeId: 'ch-2',
          actionType: 'INSERT',
          targetKind: 'ABSENCE',
          resolvedTarget: 'TERMINATION',
          placement: 'BEFORE_SIGNATURE_BLOCK',
        },
      ],
    })
    const rows = buildRedlineChangeRows(execution, { approvedChanges, regionLabels })
    expect(rows[0].targetLabel).toBe('Missing provision: Confidentiality')
    expect(rows[0].placementLabel).toBe('Before the signature block')
  })

  it('degrades safely when a change row is not in the frozen set or lacks labels', () => {
    const execution = fixtureExecution({
      changes: [
        {
          approvedChangeId: 'ch-missing',
          ordinal: 1,
          actionType: 'UNKNOWN',
          targetKind: 'UNKNOWN',
          status: 'UNKNOWN',
          verification: null,
          failureCode: null,
          placement: null,
          resolvedTarget: null,
          paragraphsDeleted: null,
          paragraphsInserted: null,
        },
      ],
    })
    const rows = buildRedlineChangeRows(execution, {
      approvedChanges: [],
      regionLabels,
    })
    expect(rows[0]).toMatchObject({
      actionLabel: 'Contract change',
      targetLabel: 'Target not recorded',
      statusLabel: 'Execution state unknown',
      verificationLabel: null,
      placementLabel: null,
      paragraphSummary: null,
      findingId: null,
    })
  })
})

describe('trigger error classification', () => {
  it('classifies permission, missing review, failed execution, unavailable and unconfirmed conflicts', () => {
    expect(
      classifyTriggerError({ status: 403, execution: null, executionStateKnown: true }),
    ).toBe('FORBIDDEN')
    expect(
      classifyTriggerError({ status: 404, execution: null, executionStateKnown: true }),
    ).toBe('NOT_FOUND')
    expect(
      classifyTriggerError({
        status: 409,
        execution: fixtureExecution({ status: 'FAILED' }),
        executionStateKnown: true,
      }),
    ).toBe('FAILED_EXECUTION')
    expect(
      classifyTriggerError({ status: 409, execution: null, executionStateKnown: true }),
    ).toBe('NOT_AVAILABLE')
    expect(
      classifyTriggerError({ status: 409, execution: null, executionStateKnown: false }),
    ).toBe('UNCONFIRMED')
    expect(
      classifyTriggerError({ status: null, execution: null, executionStateKnown: true }),
    ).toBe('TRANSPORT')
  })

  it('gives distinct safe notices for every trigger error kind', () => {
    const kinds = [
      'FORBIDDEN',
      'NOT_FOUND',
      'FAILED_EXECUTION',
      'NOT_AVAILABLE',
      'UNCONFIRMED',
      'TRANSPORT',
    ] as const
    const notices = kinds.map((kind) => redlineTriggerErrorNotice(kind))
    expect(new Set(notices).size).toBe(kinds.length)
    for (const notice of notices) expect(notice.length).toBeGreaterThan(10)
  })
})
