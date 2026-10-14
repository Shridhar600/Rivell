import { describe, expect, it } from 'vitest'
import { warningLabel } from './warnings'

describe('warningLabel', () => {
  it('says in words that a damaged file was repaired', () => {
    expect(warningLabel('DAMAGED_PACKAGE_REPAIRED')).toBe(
      'The file was damaged. Rivell repaired it the way Word does, by skipping links to parts that are missing.',
    )
  })

  it('shows an unknown code as it is', () => {
    expect(warningLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })
})
