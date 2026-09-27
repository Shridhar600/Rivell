import { describe, expect, it } from 'vitest'
import { boundedErrorMessage } from './errorMessage'

describe('boundedErrorMessage', () => {
  it('derives copy from the status alone and never accepts server text', () => {
    expect(boundedErrorMessage(404)).toMatch(/could not be found/i)
    expect(boundedErrorMessage(409)).toMatch(/current state/i)
    expect(boundedErrorMessage(403)).toMatch(/permission/i)
    expect(boundedErrorMessage(500)).not.toMatch(/exception|at com\./i)
  })

  it('prefers a caller-supplied message for a status and falls back for an unknown one', () => {
    expect(boundedErrorMessage(409, { 409: 'Only approved candidates can be published.' })).toBe(
      'Only approved candidates can be published.',
    )
    expect(boundedErrorMessage(418, {}, 'Nothing was published.')).toBe('Nothing was published.')
    expect(boundedErrorMessage(418)).toMatch(/could not complete/i)
  })

  it('returns a bounded string for every status in the envelope', () => {
    for (const status of [400, 401, 403, 404, 409, 413, 422, 500, 502, 503, 504]) {
      const message = boundedErrorMessage(status)
      expect(message.length).toBeGreaterThan(10)
      expect(message).not.toMatch(/exception|com\.rivell|select |insert |stack/i)
    }
  })
})
