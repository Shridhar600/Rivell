import { describe, expect, it } from 'vitest'
import { classifyCreateOutcome } from './reviewCreation'

describe('classifyCreateOutcome', () => {
  it('keeps a conflict copy true for both server causes', () => {
    const outcome = classifyCreateOutcome(409)

    expect(outcome.kind).toBe('CONFLICT')
    expect(outcome.uncertain).toBe(false)
    expect(outcome.message).toMatch(/analysis may not be ready/i)
    expect(outcome.message).toMatch(/already used for a different contract version/i)
    expect(outcome.message).not.toMatch(/IllegalState|Exception|at com\./)
  })

  it('classifies a rejected request body as an invalid pack', () => {
    expect(classifyCreateOutcome(400).kind).toBe('INVALID_PACK')
  })

  it('classifies a missing contract version as not found', () => {
    expect(classifyCreateOutcome(404).kind).toBe('NOT_FOUND')
  })

  it('classifies a permission failure as forbidden and certain', () => {
    const outcome = classifyCreateOutcome(403)

    expect(outcome.kind).toBe('FORBIDDEN')
    expect(outcome.uncertain).toBe(false)
  })

  it('classifies a payload ceiling failure without naming a byte number', () => {
    const outcome = classifyCreateOutcome(413)

    expect(outcome.kind).toBe('TOO_LARGE')
    expect(outcome.message).not.toMatch(/\d+\s*(MB|KB|bytes)/i)
  })

  it('treats every other outcome, including no status, as unconfirmed', () => {
    for (const status of [null, 0, 500, 502, 503]) {
      const outcome = classifyCreateOutcome(status)

      expect(outcome.kind).toBe('UNCONFIRMED')
      expect(outcome.uncertain).toBe(true)
    }
  })
})
