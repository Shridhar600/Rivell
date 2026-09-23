import { describe, expect, it } from 'vitest'
import {
  errorMessage,
  parseApiResult,
  readEnum,
  readNullableNumber,
  readNullableString,
  readNumber,
  readString,
  readStringArray,
  readStringMap,
} from './envelope'

const STATUSES = ['PENDING', 'APPROVED'] as const

describe('parseApiResult', () => {
  it('unwraps and validates the runtime envelope', () => {
    const raw = {
      data: { id: 'a1' },
      status: 200,
      timestamp: '2026-09-23T05:00:00Z',
    }

    const result = parseApiResult(raw, (data, what) => {
      const record = data as Record<string, unknown>
      return { id: readString(record, 'id', what) }
    }, 'artifact')

    expect(result.id).toBe('a1')
  })

  it('rejects a response that is not enveloped', () => {
    expect(() =>
      parseApiResult({ id: 'a1' }, (data) => data, 'artifact'),
    ).toThrow(/malformed artifact response/i)
  })

  it('rejects an envelope without data', () => {
    expect(() =>
      parseApiResult({ status: 200 }, (data) => data, 'artifact'),
    ).toThrow(/malformed artifact response/i)
  })

  it('rejects an envelope without a numeric status', () => {
    expect(() =>
      parseApiResult({ data: [], status: 'ok' }, (data) => data, 'artifact'),
    ).toThrow(/malformed artifact response/i)
  })

  it('rejects a non-object timestamp', () => {
    expect(() =>
      parseApiResult({ data: [], status: 200, timestamp: 5 }, (data) => data, 'artifact'),
    ).toThrow(/malformed artifact response/i)
  })
})

describe('guard primitives', () => {
  it('reads required strings and numbers', () => {
    const record = { name: 'playbook.docx', count: 2 }
    expect(readString(record, 'name', 'artifact')).toBe('playbook.docx')
    expect(readNumber(record, 'count', 'artifact')).toBe(2)
  })

  it('rejects empty required strings', () => {
    expect(() => readString({ name: '' }, 'name', 'artifact')).toThrow(
      /malformed artifact response/i,
    )
  })

  it('reads nullable strings and numbers', () => {
    const record = { note: null, level: null, title: 'x', row: 3 }
    expect(readNullableString(record, 'note', 'artifact')).toBeNull()
    expect(readNullableNumber(record, 'level', 'artifact')).toBeNull()
    expect(readNullableString(record, 'title', 'artifact')).toBe('x')
    expect(readNullableNumber(record, 'row', 'artifact')).toBe(3)
  })

  it('rejects missing nullable fields so contract drift is visible', () => {
    expect(() => readNullableString({}, 'note', 'artifact')).toThrow(
      /malformed artifact response/i,
    )
  })

  it('reads string arrays', () => {
    expect(readStringArray({ warnings: ['A', 'B'] }, 'warnings', 'artifact')).toEqual([
      'A',
      'B',
    ])
    expect(() => readStringArray({ warnings: [1] }, 'warnings', 'artifact')).toThrow(
      /malformed artifact response/i,
    )
  })

  it('normalizes missing column maps to an empty record', () => {
    expect(readStringMap({ columns: { Clause: 'Term' } }, 'columns', 'artifact')).toEqual({
      Clause: 'Term',
    })
    expect(readStringMap({ columns: null }, 'columns', 'artifact')).toEqual({})
    expect(() => readStringMap({ columns: { a: 1 } }, 'columns', 'artifact')).toThrow(
      /malformed artifact response/i,
    )
  })

  it('validates closed enum values', () => {
    expect(readEnum({ status: 'APPROVED' }, 'status', 'candidate', STATUSES)).toBe(
      'APPROVED',
    )
    expect(() =>
      readEnum({ status: 'ARCHIVED' }, 'status', 'candidate', STATUSES),
    ).toThrow(/malformed candidate response/i)
  })
})

describe('errorMessage', () => {
  it('prefers detail, then title, then message', () => {
    expect(
      errorMessage({ detail: 'The requested resource does not exist.', title: 'Not found' }, 'fallback'),
    ).toBe('The requested resource does not exist.')
    expect(errorMessage({ title: 'Not found' }, 'fallback')).toBe('Not found')
    expect(errorMessage({ message: 'network' }, 'fallback')).toBe('network')
  })

  it('falls back for empty or non-object errors', () => {
    expect(errorMessage(null, 'fallback')).toBe('fallback')
    expect(errorMessage({ detail: '  ' }, 'fallback')).toBe('fallback')
  })
})
