import { describe, expect, it } from 'vitest'
import type { ReviewListHumanReview, ReviewListItem } from '../api/reviews'
import { mergeReviewPages, reviewLifecycle, reviewRowView } from './reviewHistory'

function item(overrides: Partial<ReviewListItem> = {}): ReviewListItem {
  return {
    id: 'r1',
    contractId: 'c1',
    contractVersionNumber: 2,
    contractFilename: 'Acme MSA.docx',
    uploadedAt: '2026-10-02T09:12:00Z',
    status: 'COMPLETED',
    failure: null,
    createdAt: '2026-10-02T09:15:00Z',
    startedAt: null,
    completedAt: null,
    plannedChecks: 6,
    completedChecks: 6,
    degradedChecks: 0,
    failedChecks: 0,
    findings: 8,
    context: { contractType: 'MSA', ourSideRole: 'SUPPLIER', paperSource: 'COUNTERPARTY_PAPER', jurisdiction: 'UK' },
    humanReview: human(),
    finalization: null,
    redline: null,
    ...overrides,
  }
}

function human(overrides: Partial<ReviewListHumanReview> = {}): ReviewListHumanReview {
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

const finalization = (approvedChanges: number) => ({ finalizedAt: '2026-10-02T10:02:00Z', approvedChanges })

describe('reviewLifecycle: the canonical states, from server facts', () => {
  const cases: [string, Partial<ReviewListItem>, string, string | null][] = [
    ['queued', { status: 'ANALYSIS_QUEUED' }, 'Waiting to start', 'running'],
    ['analyzing', { status: 'ANALYZING' }, 'Analyzing', 'running'],
    ['failed', { status: 'FAILED' }, 'Analysis failed', 'needs'],
    ['no decisions yet', { humanReview: human({ status: 'NOT_STARTED', resolvedFindings: 0 }) }, 'Ready for review', 'needs'],
    ['some decided', {}, 'In review', 'needs'],
    [
      'an escalation blocks',
      { humanReview: human({ escalated: 1, blockers: ['ACTIVE_ESCALATION'] }) },
      'Escalated, needs resolution',
      'needs',
    ],
    [
      'ready to finalize',
      { humanReview: human({ readyToFinalize: true, blockers: [], resolvedFindings: 8, remainingFindings: 0 }) },
      'Ready to finalize',
      'needs',
    ],
    ['finalized', { finalization: finalization(7) }, 'Finalized', 'done'],
    ['finalized with no changes', { finalization: finalization(0) }, 'Review completed', 'done'],
    [
      'verified redline',
      {
        finalization: finalization(7),
        redline: { status: 'COMPLETED', outcome: 'REDLINE_GENERATED', verifiedChangeCount: 7, downloadAvailable: true, completedAt: null },
      },
      'Verified redline',
      'done',
    ],
    [
      'failed redline',
      {
        finalization: finalization(7),
        redline: { status: 'FAILED', outcome: null, verifiedChangeCount: null, downloadAvailable: false, completedAt: null },
      },
      'Redline failed',
      'needs',
    ],
  ]

  it.each(cases)('%s', (_name, overrides, label, group) => {
    const lifecycle = reviewLifecycle(item(overrides))
    expect(lifecycle.label).toBe(label)
    expect(lifecycle.group).toBe(group)
  })

  it('is not human attention while the machine has not finished, whatever the blockers say', () => {
    for (const status of ['ANALYSIS_QUEUED', 'ANALYZING']) {
      const lifecycle = reviewLifecycle(
        item({ status, humanReview: human({ status: 'NOT_STARTED', blockers: ['ANALYSIS_NOT_COMPLETE'] }) } as Partial<ReviewListItem>),
      )
      expect(lifecycle.group).toBe('running')
    }
  })

  it('claims no human attention for a completed review that still reports the analysis as incomplete', () => {
    const lifecycle = reviewLifecycle(item({ humanReview: human({ blockers: ['ANALYSIS_NOT_COMPLETE'] }) }))
    expect(lifecycle).toMatchObject({ key: 'analysis_complete', group: null })
  })

  it('claims no human attention when the server sent no human-review state', () => {
    expect(reviewLifecycle(item({ humanReview: null }))).toMatchObject({ key: 'analysis_complete', group: null })
  })

  it('says the analysis is degraded when no planned check completed, instead of offering a review', () => {
    const lifecycle = reviewLifecycle(
      item({ humanReview: human({ status: 'NOT_STARTED', blockers: ['ANALYSIS_DEGRADED'] }) }),
    )
    expect(lifecycle).toMatchObject({ label: 'Analysis incomplete', group: 'needs' })
  })

  it('does not offer a review when some planned checks did not finish', () => {
    const lifecycle = reviewLifecycle(
      item({ humanReview: human({ status: 'NOT_STARTED', blockers: ['ANALYSIS_PARTIAL'] }) }),
    )
    expect(lifecycle).toMatchObject({ label: 'Analysis incomplete', group: 'needs' })
  })

  it('offers no verified redline without a downloadable artifact', () => {
    const lifecycle = reviewLifecycle(
      item({
        finalization: finalization(3),
        redline: { status: 'COMPLETED', outcome: 'REDLINE_GENERATED', verifiedChangeCount: 3, downloadAvailable: false, completedAt: null },
      }),
    )
    expect(lifecycle.label).toBe('Finalized')
  })

  it('does not recognise a status it does not know', () => {
    expect(reviewLifecycle(item({ status: 'UNKNOWN' }))).toMatchObject({ key: 'unknown', group: null })
  })

  it('marks real analysis with a spinner and a frozen review with a lock, nothing else', () => {
    expect(reviewLifecycle(item({ status: 'ANALYZING' })).glyph).toBe('spin')
    expect(reviewLifecycle(item({ finalization: finalization(2) })).glyph).toBe('lock')
    expect(reviewLifecycle(item({ status: 'ANALYSIS_QUEUED' })).glyph).toBe('none')
  })
})

describe('reviewRowView', () => {
  it('writes the canonical progress column from the server counts', () => {
    expect(reviewRowView(item()).progress).toBe('5 of 8 decided')
    expect(reviewRowView(item({ status: 'ANALYSIS_QUEUED' })).progress).toBe('—')
    expect(reviewRowView(item({ status: 'FAILED' })).progress).toBe('—')
    expect(reviewRowView(item({ humanReview: human({ status: 'NOT_STARTED' }) })).progress).toBe('8 findings')
    expect(reviewRowView(item({ finalization: finalization(7) })).progress).toBe('7 approved changes')
    expect(reviewRowView(item({ finalization: finalization(1) })).progress).toBe('1 approved change')
    expect(reviewRowView(item({ finalization: finalization(0) })).progress).toBe('No approved changes')
    expect(
      reviewRowView(
        item({
          finalization: finalization(7),
          redline: { status: 'COMPLETED', outcome: 'REDLINE_GENERATED', verifiedChangeCount: 7, downloadAvailable: true, completedAt: null },
        }),
      ).progress,
    ).toBe('7 changes verified')
    expect(
      reviewRowView(
        item({
          finalization: finalization(7),
          redline: { status: 'FAILED', outcome: null, verifiedChangeCount: null, downloadAvailable: false, completedAt: null },
        }),
      ).progress,
    ).toBe('Redline could not be generated')
  })

  it('never reports a verified count for an execution that produced none', () => {
    const row = reviewRowView(
      item({
        finalization: finalization(7),
        redline: { status: 'COMPLETED', outcome: 'REDLINE_GENERATED', verifiedChangeCount: null, downloadAvailable: true, completedAt: null },
      }),
    )
    expect(row.progress).toBe('Verified')
  })

  it('names the version and the declared contract type as chips, and the context as the second line', () => {
    const row = reviewRowView(item())
    expect(row).toMatchObject({
      versionChip: 'v2',
      typeChip: 'MSA',
      detail: 'Supplier side, their template, United Kingdom',
      title: 'Acme MSA.docx',
    })
  })

  it('has no second line and no type chip when no context was frozen', () => {
    expect(reviewRowView(item({ context: null }))).toMatchObject({ typeChip: null, detail: '' })
  })

  it('shows a dash where the upload time is unknown', () => {
    expect(reviewRowView(item({ uploadedAt: null })).uploadedLabel).toBe('—')
  })
})

describe('mergeReviewPages', () => {
  it('lists a review once when two pages overlap', () => {
    const merged = mergeReviewPages([{ items: [item({ id: 'a' }), item({ id: 'b' })] }, { items: [item({ id: 'b' }), item({ id: 'c' })] }])
    expect(merged.map((review) => review.id)).toEqual(['a', 'b', 'c'])
  })
})
