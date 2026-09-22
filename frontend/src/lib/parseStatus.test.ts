import { describe, expect, it } from 'vitest'
import { isParseFailed, isParsing } from './parseStatus'

describe('isParseFailed', () => {
  it('is true only for the structured FAILED status', () => {
    expect(isParseFailed('FAILED')).toBe(true)
    expect(isParseFailed('OK')).toBe(false)
    expect(isParseFailed('PARSING')).toBe(false)
    expect(isParseFailed(undefined)).toBe(false)
  })
})

describe('isParsing', () => {
  it('is true only for the structured PARSING status', () => {
    expect(isParsing('PARSING')).toBe(true)
    expect(isParsing('OK')).toBe(false)
    expect(isParsing('FAILED')).toBe(false)
    expect(isParsing(undefined)).toBe(false)
  })
})
