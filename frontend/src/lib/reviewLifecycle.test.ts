import { describe, expect, it } from 'vitest'
import { parseReviewSummary, type ReviewSummary } from '../api/reviews'
import type { RedlineExecution } from '../api/redline'
import type { HumanReview } from '../api/workflow'
import {
  fixtureCompletedReviewSummary,
  fixtureFailedReviewSummary,
  fixtureQueuedReviewSummary,
} from '../components/review/testFixtures'
import {
  fixtureFailedRedlineExecution,
  fixtureRedlineChange,
  fixtureRedlineExecution,
} from '../components/review/redlineFixtures'
import { fixtureChangeSet, fixtureHumanReview } from '../components/review/workflowFixtures'
import {
  activityEvents,
  isPostFinalize,
  journey,
  lifecycleKey,
  lifecyclePath,
  lifecycleStatus,
  lifecycleTabs,
  opensWorkspace,
  resolveTab,
  workspacePath,
  type LifecycleFacts,
} from './reviewLifecycle'

const summary = (raw: unknown): ReviewSummary => parseReviewSummary(raw)

function facts(overrides: Partial<LifecycleFacts> = {}): LifecycleFacts {
  return {
    summary: summary(fixtureCompletedReviewSummary),
    humanReview: fixtureHumanReview(),
    changeSet: null,
    redline: null,
    redlineRunning: false,
    ...overrides,
  }
}

const finalizedReview = (): HumanReview =>
  fixtureHumanReview({
    status: 'COMPLETED',
    readyToFinalize: false,
    blockers: [],
    resolvedFindings: 1,
    remainingFindings: 0,
    changeSetId: 'cs-1',
    finalizedAt: '2026-09-26T11:00:00Z',
  })

const finalized = (overrides: Partial<LifecycleFacts> = {}) =>
  facts({ humanReview: finalizedReview(), changeSet: fixtureChangeSet(), ...overrides })

describe('lifecycleKey: analysis states come from analysis status alone', () => {
  it('keeps queued, analyzing and failed apart, and never reads human review for them', () => {
    expect(lifecycleKey(facts({ summary: summary(fixtureQueuedReviewSummary), humanReview: null }))).toBe('queued')
    expect(
      lifecycleKey(facts({ summary: summary({ ...fixtureQueuedReviewSummary, status: 'ANALYZING' }), humanReview: null })),
    ).toBe('analyzing')
    expect(lifecycleKey(facts({ summary: summary(fixtureFailedReviewSummary), humanReview: null }))).toBe('failed')
  })

  it('is not human attention while the machine is working, whatever the server lists as a blocker', () => {
    const blocked = fixtureHumanReview({
      status: 'NOT_STARTED',
      blockers: [{ code: 'ANALYSIS_NOT_COMPLETE', message: 'x', findingIds: [] }],
    })
    for (const status of ['ANALYSIS_QUEUED', 'ANALYZING']) {
      const key = lifecycleKey(facts({ summary: summary({ ...fixtureQueuedReviewSummary, status }), humanReview: blocked }))
      expect(key === 'queued' || key === 'analyzing').toBe(true)
    }
  })

  it('does not recognise a status it does not know', () => {
    expect(lifecycleKey(facts({ summary: summary({ ...fixtureQueuedReviewSummary, status: 'SOMETHING_NEW' }) }))).toBe('unknown')
  })

  it('names no state until the human-review facts have arrived', () => {
    expect(lifecycleKey(facts({ humanReview: null }))).toBeNull()
    expect(lifecycleKey(facts({ humanReview: finalizedReview(), changeSet: null }))).toBeNull()
  })
})

describe('lifecycleKey: human review comes from server state, never from counts', () => {
  const pending = { code: 'UNRESOLVED_REVIEW_REQUIRED_FINDINGS', message: 'x', findingIds: ['f-1'] }

  it('ready for review: nothing decided yet', () => {
    expect(lifecycleKey(facts({ humanReview: fixtureHumanReview({ status: 'NOT_STARTED' }) }))).toBe('completed')
  })

  it('in review: some decided', () => {
    expect(
      lifecycleKey(facts({ humanReview: fixtureHumanReview({ resolvedFindings: 5, actionableFindings: 8, remainingFindings: 3 }) })),
    ).toBe('progress')
  })

  it('escalation blocking: an active escalation, even with every other Finding decided', () => {
    expect(
      lifecycleKey(
        facts({
          humanReview: fixtureHumanReview({
            escalated: 1,
            blockers: [{ code: 'ACTIVE_ESCALATION', message: 'x', findingIds: ['f-1'] }],
          }),
        }),
      ),
    ).toBe('blocked')
  })

  it('ready to finalize only when the server says so', () => {
    expect(lifecycleKey(facts({ humanReview: fixtureHumanReview({ readyToFinalize: true, blockers: [] }) }))).toBe('ready')
    // Every Finding decided is not the same as ready: the server still lists a blocker.
    expect(
      lifecycleKey(
        facts({
          humanReview: fixtureHumanReview({ resolvedFindings: 1, remainingFindings: 0, readyToFinalize: false, blockers: [pending] }),
        }),
      ),
    ).toBe('progress')
  })

  it('degraded analysis offers no review', () => {
    expect(
      lifecycleKey(
        facts({
          humanReview: fixtureHumanReview({
            status: 'NOT_STARTED',
            blockers: [{ code: 'ANALYSIS_DEGRADED', message: 'x', findingIds: [] }],
          }),
        }),
      ),
    ).toBe('degraded')
  })

  it('claims no attention for a completed review that still reports the analysis as incomplete', () => {
    expect(
      lifecycleKey(
        facts({
          humanReview: fixtureHumanReview({ blockers: [{ code: 'ANALYSIS_NOT_COMPLETE', message: 'x', findingIds: [] }] }),
        }),
      ),
    ).toBe('incomplete')
  })

  it('an unknown human-review status is unknown, not progress', () => {
    expect(
      lifecycleKey(facts({ humanReview: fixtureHumanReview({ status: 'UNKNOWN', blockers: [] }) })),
    ).toBe('unknown')
  })
})

describe('lifecycleKey: finalized and redline', () => {
  it('finalized: the change set is frozen and no redline exists', () => {
    expect(lifecycleKey(finalized())).toBe('finalized')
  })

  it('zero approved changes needs no redline, with or without a recorded execution', () => {
    const empty = fixtureChangeSet({ changes: [] })
    expect(lifecycleKey(finalized({ changeSet: empty }))).toBe('finalized_zero')
    expect(
      lifecycleKey(finalized({ changeSet: empty, redline: fixtureRedlineExecution({ outcome: 'NO_CHANGES', changes: [], changeCount: 0 }) })),
    ).toBe('finalized_zero')
  })

  it('verified only for a completed execution with every change applied and verified and an artifact', () => {
    expect(lifecycleKey(finalized({ redline: fixtureRedlineExecution() }))).toBe('rl_verified')
  })

  it('fails closed: no download, or one change not verified, is never a verified redline', () => {
    expect(lifecycleKey(finalized({ redline: fixtureRedlineExecution({ downloadAvailable: false }) }))).toBe('rl_unverified')
    expect(
      lifecycleKey(
        finalized({
          redline: fixtureRedlineExecution({ changes: [fixtureRedlineChange({ verification: 'FAILED' })] }),
        }),
      ),
    ).toBe('rl_unverified')
    expect(
      lifecycleKey(finalized({ redline: fixtureRedlineExecution({ changes: [], changeCount: 1 }) })),
    ).toBe('rl_unverified')
  })

  it('a failed execution is a failed redline, even when some changes were applied before it', () => {
    const redline: RedlineExecution = {
      ...fixtureFailedRedlineExecution(),
      changes: [fixtureRedlineChange({ status: 'APPLIED', verification: null })],
    }
    expect(lifecycleKey(finalized({ redline }))).toBe('rl_failed')
  })

  it('shows a running redline only while this page has the request in flight', () => {
    expect(lifecycleKey(finalized({ redlineRunning: true }))).toBe('rl_running')
    expect(lifecycleKey(finalized({ redlineRunning: false }))).toBe('finalized')
  })
})

describe('labels, tabs and workspace actions', () => {
  it('uses the canonical vocabulary and keeps the failed states apart from the done ones', () => {
    const labels = Object.fromEntries(
      (['queued', 'analyzing', 'failed', 'completed', 'progress', 'blocked', 'ready', 'finalized', 'finalized_zero', 'rl_verified', 'rl_failed'] as const).map(
        (key) => [key, lifecycleStatus(key).label],
      ),
    )
    expect(labels).toEqual({
      queued: 'Queued',
      analyzing: 'Analyzing',
      failed: 'Analysis failed',
      completed: 'Ready for review',
      progress: 'In review',
      blocked: 'Escalation blocking',
      ready: 'Ready to finalize',
      finalized: 'Finalized',
      finalized_zero: 'Review completed',
      rl_verified: 'Verified redline',
      rl_failed: 'Redline failed',
    })
  })

  it('opens the Workspace only where a person still has work to do', () => {
    expect(
      (['queued', 'analyzing', 'failed', 'degraded', 'completed', 'progress', 'blocked', 'ready', 'finalized', 'rl_failed'] as const).filter(opensWorkspace),
    ).toEqual(['completed', 'progress', 'blocked', 'ready'])
  })

  it('offers tabs only after Finalize, and a report only once an execution is recorded', () => {
    expect(lifecycleTabs('ready')).toEqual([])
    expect(lifecycleTabs('finalized').map((tab) => tab.id)).toEqual(['redline', 'changes'])
    expect(lifecycleTabs('finalized_zero').map((tab) => tab.id)).toEqual(['redline', 'changes'])
    expect(lifecycleTabs('rl_verified').map((tab) => tab.label)).toEqual(['Redline', 'Approved changes', 'Change report'])
    expect(lifecycleTabs('rl_failed').map((tab) => tab.label)).toEqual(['Redline', 'Approved changes', 'Diagnostic report'])
    expect(isPostFinalize('ready')).toBe(false)
  })

  it('falls back to Redline for a tab that is not available in this state', () => {
    expect(resolveTab('finalized', 'report')).toBe('redline')
    expect(resolveTab('rl_verified', 'report')).toBe('report')
    expect(resolveTab('rl_verified', 'nonsense')).toBe('redline')
    expect(resolveTab('progress', 'changes')).toBe('redline')
  })

  it('builds routes from the real identifiers', () => {
    expect(workspacePath('c1', 'r-1')).toBe('/reviews/c1/analysis/r-1')
    expect(lifecyclePath('c1', 'r-1')).toBe('/reviews/c1/overview/r-1')
    expect(lifecyclePath('c1', 'r-1', 'changes')).toBe('/reviews/c1/overview/r-1/changes')
    expect(lifecyclePath('c1', 'r-1', 'redline')).toBe('/reviews/c1/overview/r-1')
  })
})

describe('journey', () => {
  it('shows only the server counts for the Review step', () => {
    const steps = journey('progress', fixtureHumanReview({ resolvedFindings: 5, actionableFindings: 8 }))
    expect(steps.map((step) => step.detail)).toEqual(['Completed', '5 of 8 decided', 'Not started', 'Not started'])
  })

  it('walks the four steps for queued, failed, finalized and verified', () => {
    expect(journey('queued', null).map((step) => step.tone)).toEqual(['a', 'i', 'i', 'i'])
    expect(journey('failed', null)[0]).toMatchObject({ tone: 'x', detail: 'Failed' })
    expect(journey('finalized', null).map((step) => step.detail)).toEqual(['Completed', 'Resolved', 'Frozen', 'Not generated'])
    expect(journey('finalized_zero', null)[3]).toMatchObject({ tone: 'd', detail: 'No changes required' })
    expect(journey('rl_verified', null).map((step) => step.tone)).toEqual(['d', 'd', 'd', 'd'])
    expect(journey('rl_failed', null)[3]).toMatchObject({ tone: 'x', detail: 'Failed' })
    expect(journey('rl_unverified', null)[3]).toMatchObject({ tone: 'x', detail: 'Not verified' })
  })

  it('marks escalation and readiness as the canonical amber and active steps', () => {
    expect(journey('blocked', null)[1]).toMatchObject({ tone: 'w', detail: 'Escalation unresolved' })
    expect(journey('ready', null)[2]).toMatchObject({ tone: 'a', detail: 'Ready to finalize' })
  })
})

describe('activityEvents', () => {
  it('lists only moments the server timestamped', () => {
    const events = activityEvents(
      finalized({ redline: fixtureRedlineExecution() }),
    ).map((event) => event.label)
    expect(events).toEqual([
      'Review created',
      'Analysis started',
      'Analysis completed',
      'Review finalized; approved changes frozen',
      'Redline execution started',
      'Redline completed',
    ])
    expect(events).not.toContain('Human review opened')
  })

  it('marks a failure with the failed flag', () => {
    const events = activityEvents(finalized({ redline: fixtureFailedRedlineExecution() }))
    expect(events.at(-1)).toMatchObject({ label: 'Redline failed', failed: true })
  })

  it('has just the creation event for a review that has not started', () => {
    const events = activityEvents(facts({ summary: summary(fixtureQueuedReviewSummary), humanReview: null }))
    expect(events.map((event) => event.label)).toEqual(['Review created'])
  })
})
