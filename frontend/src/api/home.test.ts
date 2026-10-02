import { describe, expect, it } from 'vitest'
import { HOME_REVIEWS_UNAVAILABLE_CODE, homeReviewsUnavailable } from './home'

describe('cross-contract review seam', () => {
  it('reports a typed unavailable code instead of inventing an endpoint', () => {
    const result = homeReviewsUnavailable()
    expect(result.availability).toBe('unavailable')
    if (result.availability === 'unavailable') {
      expect(result.code).toBe(HOME_REVIEWS_UNAVAILABLE_CODE)
    }
  })

  it('issues no network request of any kind', () => {
    const original = globalThis.fetch
    const calls: string[] = []
    globalThis.fetch = ((input: RequestInfo | URL) => {
      calls.push(typeof input === 'string' ? input : String(input))
      return Promise.reject(new Error('must not be called'))
    }) as typeof globalThis.fetch
    try {
      homeReviewsUnavailable()
    } finally {
      globalThis.fetch = original
    }
    expect(calls).toEqual([])
  })

  it('carries a plain user-facing reason, with no internal jargon', () => {
    const result = homeReviewsUnavailable()
    if (result.availability !== 'unavailable') throw new Error('expected unavailable')
    expect(result.reason).toMatch(/contract/i)
    expect(result.reason).not.toMatch(/endpoint|api|contractId|openapi|N\+1/i)
    expect(result.reason.endsWith('.')).toBe(true)
  })
})