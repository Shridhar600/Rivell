import { describe, expect, it } from 'vitest'
import {
  conditionsOf,
  groupCandidates,
  isPublishedCompilation,
  needsConflictAcknowledgement,
  nextTarget,
  outcomeOf,
  primaryCondition,
  readiness,
  reviewedCount,
} from './compilation'
import { candidate } from '../test/knowledgeFixtures'
import type { Candidate } from '../api/knowledge'

const make = (id: string, over: Partial<Candidate> = {}) =>
  candidate({ id, artifactId: 'a', reviewStatus: 'PENDING', reviewCategory: 'ROUTINE', ...over })

describe('conditions are not outcomes', () => {
  it('reads conditions from server review reasons and leaves the review status alone', () => {
    const duplicate = make('d', { reviewCategory: 'ELEVATED', reviewReasons: ['DUPLICATE_DETECTED'] })
    expect(conditionsOf(duplicate).map((c) => c.kind)).toEqual(['duplicate'])
    // A duplicate is a reason to look, not a decision: it is still waiting for a human.
    expect(outcomeOf(duplicate)).toBe('pending')
  })

  it('orders conditions by seriousness and names the worst one first', () => {
    const many = make('m', {
      reviewCategory: 'HIGH',
      reviewReasons: ['LOW_CLASSIFICATION_CONFIDENCE', 'SUPERSESSION_PROPOSED', 'CONFLICT_DETECTED', 'DUPLICATE_DETECTED'],
    })
    expect(conditionsOf(many).map((c) => c.kind)).toEqual(['conflict', 'supersession', 'duplicate', 'uncertain'])
    expect(primaryCondition(many)?.label).toBe('Conflicts with published knowledge')
  })

  it('folds several uncertainty reasons into one condition', () => {
    const uncertain = make('u', {
      reviewCategory: 'ELEVATED',
      reviewReasons: ['VERIFICATION_DISPUTED', 'UNVERIFIED_GENERATIVE_FIELD', 'LOW_CLASSIFICATION_CONFIDENCE'],
    })
    expect(conditionsOf(uncertain)).toHaveLength(1)
  })

  it('never drops a conflict signal that has no matching reason', () => {
    const signalled = make('s', {
      reviewReasons: [],
      conflictSignals: [{ conflictType: 'CONTRADICTS_EXISTING', message: 'x', publishedCandidateId: 'p' }],
    })
    expect(conditionsOf(signalled).map((c) => c.kind)).toEqual(['conflict'])
  })

  it('keeps a reason the app does not know yet, under its own name', () => {
    const future = make('f', { reviewCategory: 'ELEVATED', reviewReasons: ['SOMETHING_NEW'] })
    expect(conditionsOf(future)).toEqual([expect.objectContaining({ kind: 'other', label: 'Something new' })])
  })

  it('has no condition for a routine candidate', () => {
    expect(conditionsOf(make('r'))).toEqual([])
    expect(primaryCondition(make('r'))).toBeNull()
  })

  it('asks for acknowledgement only where there is a conflict', () => {
    expect(needsConflictAcknowledgement(make('c', { reviewReasons: ['CONFLICT_DETECTED'] }))).toBe(true)
    expect(needsConflictAcknowledgement(make('d', { reviewReasons: ['DUPLICATE_DETECTED'] }))).toBe(false)
    expect(needsConflictAcknowledgement(make('s', { reviewReasons: ['SUPERSESSION_PROPOSED'] }))).toBe(false)
  })

  it('maps each review status to its own outcome', () => {
    expect(['PENDING', 'EDITED', 'APPROVED', 'REJECTED', 'PUBLISHED'].map((status) => outcomeOf(make('x', { reviewStatus: status as Candidate['reviewStatus'] })))).toEqual([
      'pending',
      'corrected',
      'approved',
      'rejected',
      'published',
    ])
  })
})

describe('grouping', () => {
  const list = [
    make('att', { reviewCategory: 'HIGH', reviewReasons: ['CONFLICT_DETECTED'] }),
    make('r1'),
    make('r2'),
    make('r3'),
    make('fixed', { reviewStatus: 'EDITED' }),
    make('ok', { reviewStatus: 'APPROVED' }),
    make('no', { reviewStatus: 'REJECTED' }),
    make('out', { reviewStatus: 'PUBLISHED' }),
  ]

  it('puts exactly the server-eligible candidates in the grouped set', () => {
    const groups = groupCandidates(list)
    expect(groups.bulk.map((c) => c.id)).toEqual(['r1', 'r2', 'r3'])
    expect(groups.attention.map((c) => c.id)).toEqual(['att'])
    // A corrected candidate is not pending, so the server would refuse it in bulk: it is reviewed alone.
    expect(groups.toReview.map((c) => c.id)).toEqual(['fixed'])
    expect(groups.reviewed.map((c) => c.id)).toEqual(['ok', 'no'])
    expect(groups.published.map((c) => c.id)).toEqual(['out'])
  })

  it('does not group a candidate the server would not accept', () => {
    const groups = groupCandidates([make('e1', { reviewCategory: 'ELEVATED', reviewReasons: ['LOW_CLASSIFICATION_CONFIDENCE'] }), make('r1'), make('r2')])
    expect(groups.bulk.map((c) => c.id)).toEqual(['r1', 'r2'])
    expect(groups.attention.map((c) => c.id)).toEqual(['e1'])
  })

  it('does not call one routine candidate a group', () => {
    const groups = groupCandidates([make('only')])
    expect(groups.bulk).toEqual([])
    expect(groups.toReview.map((c) => c.id)).toEqual(['only'])
  })

  it('takes a candidate out of the group when the reviewer chooses to review it alone', () => {
    const groups = groupCandidates([make('r1'), make('r2'), make('r3')], new Set(['r2']))
    expect(groups.bulk.map((c) => c.id)).toEqual(['r1', 'r3'])
    expect(groups.toReview.map((c) => c.id)).toEqual(['r2'])
  })

  it('treats a candidate with no category as not eligible', () => {
    const groups = groupCandidates([make('n1', { reviewCategory: null }), make('n2', { reviewCategory: null })])
    expect(groups.bulk).toEqual([])
    expect(groups.toReview).toHaveLength(2)
  })
})

describe('readiness', () => {
  it('is not ready while anything awaits a decision, and says how many', () => {
    const state = readiness([make('a', { reviewStatus: 'APPROVED' }), make('b'), make('c', { reviewStatus: 'EDITED' })])
    expect(state).toMatchObject({ awaiting: 2, approved: 1, canPublish: false, blocker: '2 candidates are still to review.' })
  })

  it('is ready when every candidate is approved or rejected and something is approved', () => {
    const state = readiness([make('a', { reviewStatus: 'APPROVED' }), make('b', { reviewStatus: 'REJECTED' })])
    expect(state).toMatchObject({ canPublish: true, blocker: null, approved: 1, rejected: 1 })
  })

  it('never offers to publish an empty set', () => {
    expect(readiness([make('a', { reviewStatus: 'REJECTED' }), make('b', { reviewStatus: 'REJECTED' })])).toMatchObject({
      canPublish: false,
      blocker: 'Every candidate was rejected, so there is nothing to publish.',
    })
    expect(readiness([])).toMatchObject({ canPublish: false, blocker: 'No candidates were compiled from this source.' })
  })

  it('does not republish what is already published', () => {
    const state = readiness([make('a', { reviewStatus: 'PUBLISHED' }), make('b', { reviewStatus: 'REJECTED' })])
    expect(state).toMatchObject({ canPublish: false, blocker: 'Everything approved here is already published.' })
    expect(isPublishedCompilation([make('a', { reviewStatus: 'PUBLISHED' }), make('b', { reviewStatus: 'REJECTED' })])).toBe(true)
  })

  it('counts a flagged candidate as needing attention only while it is open', () => {
    const flagged = (status: Candidate['reviewStatus']) => make('f', { reviewStatus: status, reviewReasons: ['DUPLICATE_DETECTED'], reviewCategory: 'ELEVATED' })
    expect(readiness([flagged('PENDING')]).attention).toBe(1)
    expect(readiness([flagged('APPROVED')]).attention).toBe(0)
  })

  it('counts a human outcome as reviewed, published or not', () => {
    expect(reviewedCount([make('a'), make('b', { reviewStatus: 'APPROVED' }), make('c', { reviewStatus: 'REJECTED' }), make('d', { reviewStatus: 'PUBLISHED' }), make('e', { reviewStatus: 'EDITED' })])).toBe(3)
  })

  it('is not a published compilation while something is still open or approved', () => {
    expect(isPublishedCompilation([make('a', { reviewStatus: 'PUBLISHED' }), make('b', { reviewStatus: 'APPROVED' })])).toBe(false)
    expect(isPublishedCompilation([make('a', { reviewStatus: 'PUBLISHED' }), make('b')])).toBe(false)
  })
})

describe('what to open next', () => {
  const list = [
    make('r1'),
    make('r2'),
    make('plain', { reviewCategory: 'ROUTINE', reviewStatus: 'EDITED' }),
    make('att', { reviewCategory: 'ELEVATED', reviewReasons: ['DUPLICATE_DETECTED'] }),
  ]

  it('goes to what needs attention first', () => {
    expect(nextTarget(list, 'plain')).toEqual({ kind: 'candidate', id: 'att' })
  })

  it('then to the next candidate to review, and only then to the grouped review', () => {
    expect(nextTarget(list, 'att')).toEqual({ kind: 'candidate', id: 'plain' })
    expect(nextTarget(list.filter((c) => c.id !== 'plain'), 'att')).toEqual({ kind: 'bulk' })
  })

  it('stops when nothing is left', () => {
    expect(nextTarget([make('x', { reviewStatus: 'APPROVED' })], 'x')).toBeNull()
  })
})
