import { afterEach, describe, expect, it } from 'vitest'
import {
  RESUME_KEY,
  clearResume,
  clearResumeFor,
  parseResumeRecord,
  readResume,
  resumeRoute,
  writeResume,
  type ResumeRecord,
} from './resume'

const RECORD: ResumeRecord = {
  reviewId: 'rev-1',
  contractId: 'con-1',
  filename: 'Atlas Mutual NDA.docx',
  versionNumber: 2,
  visitedAt: '2026-10-03T09:00:00.000Z',
}

afterEach(() => {
  globalThis.localStorage?.clear()
})

describe('resume store', () => {
  it('round-trips a saved place', () => {
    writeResume(RECORD)
    expect(readResume()).toEqual(RECORD)
  })

  it('reads as nothing when nothing was saved', () => {
    expect(readResume()).toBeNull()
  })

  it('derives the route from the ids instead of trusting storage', () => {
    expect(resumeRoute(RECORD)).toBe('/reviews/con-1/analysis/rev-1')
    globalThis.localStorage.setItem(
      RESUME_KEY,
      JSON.stringify({ v: 1, ...RECORD, route: 'https://evil.example/' }),
    )
    // A stored address that is not a current Review destination marks the whole entry stale.
    expect(readResume()).toBeNull()
    expect(globalThis.localStorage.getItem(RESUME_KEY)).toBeNull()
  })

  it('treats corrupt JSON as no resume state', () => {
    globalThis.localStorage.setItem(RESUME_KEY, '{not json')
    expect(readResume()).toBeNull()
  })

  it('rejects an unknown record version', () => {
    globalThis.localStorage.setItem(RESUME_KEY, JSON.stringify({ v: 2, ...RECORD }))
    expect(readResume()).toBeNull()
  })

  it('rejects ids that could break out of the route', () => {
    expect(parseResumeRecord({ v: 1, ...RECORD, reviewId: '../x' })).toBeNull()
    expect(parseResumeRecord({ v: 1, ...RECORD, contractId: 'a/b' })).toBeNull()
    expect(parseResumeRecord({ v: 1, ...RECORD, contractId: '' })).toBeNull()
  })

  it('rejects a blank filename, a bad date and a bad version', () => {
    expect(parseResumeRecord({ v: 1, ...RECORD, filename: '   ' })).toBeNull()
    expect(parseResumeRecord({ v: 1, ...RECORD, visitedAt: 'yesterday-ish' })).toBeNull()
    expect(parseResumeRecord({ v: 1, ...RECORD, versionNumber: 0 })).toBeNull()
    expect(parseResumeRecord({ v: 1, ...RECORD, versionNumber: 1.5 })).toBeNull()
  })

  it('accepts a missing version as null', () => {
    expect(parseResumeRecord({ v: 1, ...RECORD, versionNumber: null })?.versionNumber).toBeNull()
  })

  it('clears the saved place', () => {
    writeResume(RECORD)
    clearResume()
    expect(readResume()).toBeNull()
  })

  it('clears only the Review it was told about', () => {
    writeResume(RECORD)
    clearResumeFor('someone-else')
    expect(readResume()).toEqual(RECORD)
    clearResumeFor('rev-1')
    expect(readResume()).toBeNull()
  })
})
