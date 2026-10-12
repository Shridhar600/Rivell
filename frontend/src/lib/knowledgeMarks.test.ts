import { describe, expect, it } from 'vitest'
import type { Candidate, ExtractionUnit } from '../api/knowledge'
import { candidate, unit } from '../test/knowledgeFixtures'
import { unitMark } from './knowledgeMarks'

const part = (over: Partial<ExtractionUnit>): ExtractionUnit =>
  unit({ id: 'u1', artifactId: 'a1', contentText: 'Cap at the fees paid.', ...over })
const holding = (unitId: string): Candidate[] => [candidate({ id: 'c1', artifactId: 'a1', extractionUnitId: unitId })]

describe('unitMark', () => {
  it('marks a part that holds a candidate as taken, whatever its kind', () => {
    expect(unitMark(part({ recordKind: 'GUIDANCE' }), holding('u1'))).toBe('ABSORBED')
    expect(unitMark(part({ recordKind: null }), holding('u1'))).toBe('ABSORBED')
  })

  it('marks a part Rivell read as something else as not a position', () => {
    for (const kind of ['ESCALATION', 'GUIDANCE', 'DOCUMENT_CONTROL', 'OTHER'] as const) {
      expect(unitMark(part({ recordKind: kind }), [])).toBe('NOT_A_POSITION')
    }
  })

  it('marks a part Rivell read as a position or wording but holds nothing as missed', () => {
    expect(unitMark(part({ recordKind: 'POSITION' }), [])).toBe('MISSED')
    expect(unitMark(part({ recordKind: 'WORDING' }), [])).toBe('MISSED')
  })

  it('gives no mark to a part with no text or no kind decision', () => {
    expect(unitMark(part({ contentText: '  ', recordKind: 'GUIDANCE' }), [])).toBeNull()
    expect(unitMark(part({ recordKind: null }), [])).toBeNull()
  })
})
