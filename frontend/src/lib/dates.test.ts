import { describe, expect, it } from 'vitest'
import { rowDate } from './dates'

const NOW = new Date(2026, 9, 3, 15, 0)

describe('rowDate', () => {
  it('says Today and Yesterday relative to the supplied clock', () => {
    expect(rowDate(new Date(2026, 9, 3, 9, 12).toISOString(), NOW)).toMatch(/^Today, /)
    expect(rowDate(new Date(2026, 9, 2, 16, 40).toISOString(), NOW)).toMatch(/^Yesterday, /)
  })

  it('uses a weekday inside the week and a date beyond it', () => {
    expect(rowDate(new Date(2026, 9, 1, 15, 10).toISOString(), NOW)).toMatch(/^Thu, /)
    expect(rowDate(new Date(2026, 8, 20, 8, 43).toISOString(), NOW)).toMatch(/Sep/)
  })

  it('is empty for an unusable timestamp', () => {
    expect(rowDate('not a date', NOW)).toBe('')
  })
})
