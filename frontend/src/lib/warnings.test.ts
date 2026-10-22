import { describe, expect, it } from 'vitest'
import { warningLabel } from './warnings'

describe('warningLabel', () => {
  it('says in words that a damaged file was repaired', () => {
    expect(warningLabel('DAMAGED_PACKAGE_REPAIRED')).toBe(
      'The file was damaged. Rivell repaired it the way Word does, by skipping links to parts that are missing.',
    )
  })

  it.each([
    ['TRACKED_CHANGES_PRESENT', 'The file already has tracked changes. Rivell read the text as if they were accepted.'],
    ['STYLES_UNRESOLVED', 'Rivell could not read the file’s styles, so headings and numbering may be read less well.'],
    ['COVERAGE_CHECK_FAILED', 'Rivell could not confirm it read all the text in this file.'],
  ])('says in words what %s means', (code, label) => {
    expect(warningLabel(code)).toBe(label)
  })

  it('never shows a raw code for a warning it does not know', () => {
    expect(warningLabel('SOMETHING_NEW')).toBe('Rivell noted a problem with part of this file.')
  })
})
