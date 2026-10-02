import { describe, expect, it } from 'vitest'
import { fetchOrganizationReviewList } from './reviews'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-10-02T09:00:00Z' }
}

function reviewRow(overrides: Record<string, unknown> = {}) {
  return {
    id: 'rev-1',
    contractId: 'con-1',
    contractVersionNumber: 2,
    contractFilename: 'Northstar Vendor MSA.docx',
    status: 'ANALYSIS_QUEUED',
    failure: null,
    createdAt: '2026-10-02T08:00:00Z',
    startedAt: null,
    completedAt: null,
    activeRunNumber: null,
    activeRunStatus: null,
    latestRunStatus: null,
    plannedChecks: 6,
    completedChecks: 0,
    degradedChecks: 0,
    failedChecks: 0,
    signals: 0,
    findings: 0,
    ...overrides,
  }
}

function stubFetch(urls: string[], body: unknown) {
  const original = globalThis.fetch
  globalThis.fetch = ((input: RequestInfo | URL) => {
    // openapi-fetch hands over a Request, so the URL lives on .url rather than in the argument.
    urls.push(input instanceof Request ? input.url : String(input))
    return Promise.resolve(jsonResponse(envelope(body)))
  }) as typeof globalThis.fetch
  return () => {
    globalThis.fetch = original
  }
}

describe('organization review list read', () => {
  it('reads the whole organization in one request, with no contract filter', async () => {
    const urls: string[] = []
    const restore = stubFetch(urls, { items: [reviewRow()], truncated: false })
    try {
      await fetchOrganizationReviewList()
    } finally {
      restore()
    }
    const reviewCalls = urls.filter((u) => u.includes('/api/reviews'))
    expect(reviewCalls).toHaveLength(1)
    expect(reviewCalls[0]).not.toContain('contractId=')
    expect(reviewCalls[0]).toContain('limit=20')
  })

  it('never enumerates contracts or fans out per contract', async () => {
    const urls: string[] = []
    const restore = stubFetch(urls, { items: [reviewRow(), reviewRow({ id: 'rev-2' })], truncated: false })
    try {
      await fetchOrganizationReviewList()
    } finally {
      restore()
    }
    expect(urls.filter((u) => u.includes('/api/contracts'))).toEqual([])
    expect(urls.filter((u) => u.includes('/api/reviews'))).toHaveLength(1)
  })

  it('carries the fields Home needs to label a row honestly', async () => {
    const urls: string[] = []
    const restore = stubFetch(urls, {
      items: [reviewRow({ findings: 4, startedAt: '2026-10-02T08:05:00Z' })],
      truncated: false,
    })
    let result
    try {
      result = await fetchOrganizationReviewList()
    } finally {
      restore()
    }
    expect(result.items[0]).toMatchObject({
      contractFilename: 'Northstar Vendor MSA.docx',
      contractVersionNumber: 2,
      status: 'ANALYSIS_QUEUED',
      findings: 4,
      plannedChecks: 6,
    })
  })

  it('reports the continuation cursor so Home can admit it is showing a page', async () => {
    const urls: string[] = []
    const restore = stubFetch(urls, { items: [reviewRow()], truncated: true, nextCursor: '2026-10-02T08:00:00Z' })
    let result
    try {
      result = await fetchOrganizationReviewList()
    } finally {
      restore()
    }
    expect(result.truncated).toBe(true)
    expect(result.nextCursor).toBe('2026-10-02T08:00:00Z')
  })

  it('degrades an unrecognised status to UNKNOWN instead of guessing a state', async () => {
    const urls: string[] = []
    const restore = stubFetch(urls, { items: [reviewRow({ status: 'WAT' })], truncated: false })
    let result
    try {
      result = await fetchOrganizationReviewList()
    } finally {
      restore()
    }
    expect(result.items[0].status).toBe('UNKNOWN')
  })

  it('keeps the filename even when the rest of the row is sparse', async () => {
    const urls: string[] = []
    const restore = stubFetch(urls, {
      items: [{ id: 'rev-9', contractId: 'con-9', contractFilename: 'Bare.docx', status: 'COMPLETED', createdAt: '2026-10-02T08:00:00Z' }],
      truncated: false,
    })
    let result
    try {
      result = await fetchOrganizationReviewList()
    } finally {
      restore()
    }
    expect(result.items[0]).toMatchObject({
      contractFilename: 'Bare.docx',
      contractVersionNumber: null,
      completedAt: null,
      findings: 0,
    })
  })
})