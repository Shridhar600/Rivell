import { describe, expect, it } from 'vitest'
import type { FindingQuality } from '../api/reviews'
import { clauseMarkLabel, clauseMarksLegend, decidedByLabel, findingIssueLabel } from './reviews'

const quality = (overrides: Partial<FindingQuality>): FindingQuality => ({
  sufficiency: null,
  verificationOutcome: null,
  degradationFlags: [],
  category: null,
  flagProbability: null,
  probabilities: {},
  secondaryTopic: false,
  uncoveredProbability: null,
  route: null,
  jevCategory: null,
  secondOpinionOutcome: null,
  secondOpinionCategory: null,
  secondOpinionQuote: null,
  secondOpinionReason: null,
  ...overrides,
})

describe('decidedByLabel', () => {
  it('says who decided, with the first review’s estimate, and never names a provider', () => {
    expect(decidedByLabel(quality({ route: 'JEV', flagProbability: 0.87 }))).toBe(
      'Decided by Rivell’s first review · 87%',
    )
    expect(decidedByLabel(quality({ route: 'JEV', uncoveredProbability: 0.62 }))).toBe(
      'Decided by Rivell’s first review · 62%',
    )
    expect(decidedByLabel(quality({ route: 'SECOND_OPINION' }))).toBe('Decided with a second opinion')
    expect(decidedByLabel(quality({ route: 'NEEDS_YOUR_LOOK' }))).toBe('Needs your look')
  })

  it('shows nothing for a finding that carries no route', () => {
    expect(decidedByLabel(quality({}))).toBeNull()
    expect(decidedByLabel(null)).toBeNull()
  })
})

describe('clause marks', () => {
  it('says in plain words what Rivell did with each clause', () => {
    expect(clauseMarkLabel('FLAGGED')).toBe('Flagged')
    expect(clauseMarkLabel('CHECKED')).toBe('Checked — no issue found')
    expect(clauseMarkLabel('NOT_COVERED')).toBe('Not covered by your playbook')
    expect(clauseMarkLabel('NOT_ANALYSED')).toBe('Not analysed — the check did not finish')
    expect(clauseMarkLabel('NOT_PLANNED')).toBe('Not reviewed — outside the review plan')
  })

  it('counts the marks for the legend and leaves out a mark no clause has', () => {
    expect(clauseMarksLegend(['FLAGGED', 'CHECKED', 'CHECKED', 'NOT_ANALYSED', 'NOT_PLANNED'])).toBe(
      '1 flagged · 2 checked · 1 not analysed · 1 not reviewed',
    )
    expect(clauseMarksLegend([])).toBe('')
  })
})

describe('a finding a reviewer added', () => {
  it('is listed as added by a reviewer', () => {
    expect(findingIssueLabel({ issueFamily: 'USER_ADDED', observationKind: 'USER_ADDED' })).toBe(
      'Added by a reviewer',
    )
  })
})
