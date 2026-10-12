import { describe, expect, it } from 'vitest'
import type { FindingQuality } from '../api/reviews'
import { decidedByLabel } from './reviews'

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
