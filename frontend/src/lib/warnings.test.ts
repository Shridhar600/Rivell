import { describe, expect, it } from 'vitest'
import { warningLabel } from './warnings'

describe('warningLabel', () => {
  it('never shows a raw code for a warning it does not know', () => {
    expect(warningLabel('SOMETHING_NEW')).toBe('Rivell noted a problem with part of this file.')
  })
})
