import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  downloadRedline,
  RedlineDownloadIntegrityError,
  fetchRedlineExecution,
  isRedlineForbiddenError,
  isRedlineNotFoundError,
  parseRedlineExecution,
  RedlineApiError,
  redlineKeys,
  triggerRedline,
  useRedlineExecutionQuery,
  useTriggerRedline,
  type RedlineExecution,
} from './redline'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-26T14:00:00Z' }
}

function errorResponse(status: number) {
  return jsonResponse(
    { code: String(status), title: 'Invalid state', detail: 'server detail text' },
    status,
  )
}

function stubFetch(handler: (request: Request) => Response | Promise<Response>) {
  const requests: Request[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init)
    requests.push(request)
    return handler(request)
  })
  vi.stubGlobal('fetch', fetchMock)
  return requests
}

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { queryClient, wrapper }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const generatedPayload = {
  id: 're-1',
  reviewId: 'r-1',
  changeSetId: 'cs-1',
  contractId: 'c-1',
  contractVersionNumber: 1,
  changeSetVersion: 1,
  status: 'COMPLETED',
  outcome: 'REDLINE_GENERATED',
  failureCode: null,
  failureDetail: null,
  rendererVersion: 'rivell-redline-renderer-1',
  placementStrategyVersion: 'rivell-insert-placement-1',
  sourceSha256: 'a'.repeat(64),
  changeCount: 3,
  requestedBy: 'u-1',
  startedAt: '2026-09-26T13:00:00Z',
  completedAt: '2026-09-26T13:00:02Z',
  artifactFilename: 'acme-msa-v1-redline.docx',
  artifactSizeBytes: 4403,
  artifactSha256: 'b'.repeat(64),
  downloadAvailable: true,
  changes: [
    {
      approvedChangeId: 'ch-1',
      ordinal: 1,
      actionType: 'REPLACE',
      targetKind: 'CLAUSE',
      status: 'APPLIED',
      verification: 'VERIFIED',
      failureCode: null,
      placement: null,
      resolvedTarget: '8. Limitation of Liability',
      paragraphsDeleted: 2,
      paragraphsInserted: 2,
    },
    {
      approvedChangeId: 'ch-2',
      ordinal: 2,
      actionType: 'INSERT',
      targetKind: 'ABSENCE',
      status: 'APPLIED',
      verification: 'VERIFIED',
      failureCode: null,
      placement: 'AFTER_CLAUSE[CONFIDENTIALITY]',
      resolvedTarget: 'CONFIDENTIALITY',
      paragraphsDeleted: 0,
      paragraphsInserted: 1,
    },
    {
      approvedChangeId: 'ch-3',
      ordinal: 3,
      actionType: 'DELETE',
      targetKind: 'CLAUSE',
      status: 'APPLIED',
      verification: 'VERIFIED',
      failureCode: null,
      placement: null,
      resolvedTarget: '5. Term and Renewal',
      paragraphsDeleted: 3,
      paragraphsInserted: 0,
    },
  ],
}

describe('redline parsers', () => {
  it('parses a completed execution with per-change execution and verification truth', () => {
    const execution = parseRedlineExecution(generatedPayload)
    expect(execution.id).toBe('re-1')
    expect(execution.reviewId).toBe('r-1')
    expect(execution.status).toBe('COMPLETED')
    expect(execution.outcome).toBe('REDLINE_GENERATED')
    expect(execution.downloadAvailable).toBe(true)
    expect(execution.changeCount).toBe(3)
    expect(execution.artifactFilename).toBe('acme-msa-v1-redline.docx')
    expect(execution.artifactSizeBytes).toBe(4403)
    expect(execution.changes).toHaveLength(3)
    expect(execution.changes[1]).toMatchObject({
      approvedChangeId: 'ch-2',
      ordinal: 2,
      actionType: 'INSERT',
      targetKind: 'ABSENCE',
      status: 'APPLIED',
      verification: 'VERIFIED',
      placement: 'AFTER_CLAUSE[CONFIDENTIALITY]',
      paragraphsInserted: 1,
    })
  })

  it('parses a failed execution with failure code, detail and not-applied rows', () => {
    const execution = parseRedlineExecution({
      ...generatedPayload,
      status: 'FAILED',
      outcome: null,
      failureCode: 'PREEXISTING_REVISIONS_UNSUPPORTED',
      failureDetail: 'The document contains existing tracked revisions.',
      artifactFilename: null,
      artifactSizeBytes: null,
      artifactSha256: null,
      downloadAvailable: false,
      changes: [
        {
          approvedChangeId: 'ch-1',
          ordinal: 1,
          actionType: 'REPLACE',
          targetKind: 'CLAUSE',
          status: 'FAILED',
          verification: null,
          failureCode: 'PREEXISTING_REVISIONS_UNSUPPORTED',
          placement: null,
          resolvedTarget: null,
          paragraphsDeleted: null,
          paragraphsInserted: null,
        },
        {
          approvedChangeId: 'ch-2',
          ordinal: 2,
          actionType: 'INSERT',
          targetKind: 'ABSENCE',
          status: 'NOT_APPLIED',
          verification: null,
          failureCode: null,
          placement: null,
          resolvedTarget: null,
          paragraphsDeleted: null,
          paragraphsInserted: null,
        },
      ],
    })
    expect(execution.status).toBe('FAILED')
    expect(execution.outcome).toBeNull()
    expect(execution.failureCode).toBe('PREEXISTING_REVISIONS_UNSUPPORTED')
    expect(execution.failureDetail).toBe(
      'The document contains existing tracked revisions.',
    )
    expect(execution.downloadAvailable).toBe(false)
    expect(execution.changes[0].status).toBe('FAILED')
    expect(execution.changes[1].status).toBe('NOT_APPLIED')
    expect(execution.changes[1].verification).toBeNull()
  })

  it('degrades unknown enum values to UNKNOWN instead of throwing', () => {
    const execution = parseRedlineExecution({
      ...generatedPayload,
      status: 'PROCESSING_DOCUMENT',
      outcome: 'ALMOST_DONE',
      failureCode: 'NOT_A_REAL_CODE',
      changes: [
        {
          ...generatedPayload.changes[0],
          actionType: 'TRANSLATE',
          targetKind: 'PARAGRAPH',
          status: 'IN_PROGRESS',
          verification: 'MAYBE',
        },
      ],
    })
    expect(execution.status).toBe('UNKNOWN')
    expect(execution.outcome).toBe('UNKNOWN')
    expect(execution.failureCode).toBe('UNKNOWN')
    expect(execution.changes[0].actionType).toBe('UNKNOWN')
    expect(execution.changes[0].targetKind).toBe('UNKNOWN')
    expect(execution.changes[0].status).toBe('UNKNOWN')
    expect(execution.changes[0].verification).toBe('UNKNOWN')
  })

  it('accepts the two M10 failure codes and still degrades anything unknown', () => {
    for (const code of ['APPROVED_SEMANTICS_DRIFT', 'SOURCE_EXPANSION_LIMIT_EXCEEDED'] as const) {
      expect(parseRedlineExecution({ ...generatedPayload, failureCode: code }).failureCode).toBe(code)
    }
    expect(
      parseRedlineExecution({ ...generatedPayload, failureCode: 'A_FUTURE_CODE' }).failureCode,
    ).toBe('UNKNOWN')
  })

  it('names document storage, not the engine, for a storage failure', () => {
    const storage = new RedlineApiError(503).message
    expect(storage).toMatch(/storage/i)
    expect(storage).not.toMatch(/engine/i)
    expect(new RedlineApiError(500).message).toMatch(/engine/i)
  })

  it('defaults a missing downloadAvailable flag to false', () => {
    const { downloadAvailable, ...withoutFlag } = generatedPayload
    void downloadAvailable
    expect(parseRedlineExecution(withoutFlag).downloadAvailable).toBe(false)
  })

  it('rejects a malformed execution envelope payload', () => {
    expect(() => parseRedlineExecution({ status: 'COMPLETED' })).toThrow(
      /Malformed redline execution/,
    )
  })
})

describe('redline fetchers', () => {
  it('reads the canonical execution for a review', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(generatedPayload)))
    const execution = await fetchRedlineExecution('r-1')
    expect(execution.status).toBe('COMPLETED')
    expect(requests[0].method).toBe('GET')
    expect(requests[0].url).toContain('/api/reviews/r-1/redline')
  })

  it('classifies 404 as no execution and 403/409 as typed errors', async () => {
    stubFetch(() => errorResponse(404))
    const notFound = await fetchRedlineExecution('r-1').catch((error) => error)
    expect(isRedlineNotFoundError(notFound)).toBe(true)
    expect(notFound).toMatchObject({ status: 404 })

    stubFetch(() => errorResponse(403))
    const forbidden = await fetchRedlineExecution('r-1').catch((error) => error)
    expect(isRedlineForbiddenError(forbidden)).toBe(true)

    stubFetch(() => errorResponse(409))
    const conflict = await fetchRedlineExecution('r-1').catch((error) => error)
    expect(conflict).toBeInstanceOf(RedlineApiError)
    expect(conflict).toMatchObject({ status: 409 })
  })

  it('triggers synchronous generation with an empty POST', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(generatedPayload)))
    const execution = await triggerRedline('r-1')
    expect(execution.outcome).toBe('REDLINE_GENERATED')
    expect(requests[0].method).toBe('POST')
    expect(requests[0].url).toContain('/api/reviews/r-1/redline')
    expect(requests[0].body).toBeNull()
  })

  it('keeps execution caches isolated per review', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(generatedPayload)))
    const { queryClient, wrapper } = createWrapper()
    queryClient.setQueryData(redlineKeys.execution('r-9'), {
      ...generatedPayload,
      id: 're-9',
      reviewId: 'r-9',
    })
    const { result } = renderHook(() => useRedlineExecutionQuery('r-1', true), { wrapper })
    await waitFor(() => expect(result.current.data?.id).toBe('re-1'))
    expect(
      (queryClient.getQueryData<RedlineExecution>(redlineKeys.execution('r-9')) ?? null)?.id,
    ).toBe('re-9')
    expect(requests.filter((request) => request.url.includes('/redline'))).toHaveLength(1)
  })

  it('does not query before the review is finalized', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(generatedPayload)))
    const { wrapper } = createWrapper()
    renderHook(() => useRedlineExecutionQuery('r-1', false), { wrapper })
    expect(requests).toHaveLength(0)
  })
})

describe('trigger mutation state', () => {
  it('seeds the canonical execution from the trigger response without refetching', async () => {
    const requests = stubFetch((request) =>
      request.method === 'GET'
        ? errorResponse(404)
        : jsonResponse(envelope(generatedPayload)),
    )
    const { queryClient, wrapper } = createWrapper()
    const { result } = renderHook(
      () => ({ query: useRedlineExecutionQuery('r-1', true), trigger: useTriggerRedline('r-1') }),
      { wrapper },
    )
    await waitFor(() =>
      expect(isRedlineNotFoundError(result.current.query.error)).toBe(true),
    )
    act(() => result.current.trigger.mutate())
    await waitFor(() => expect(result.current.trigger.isSuccess).toBe(true))
    const cached = queryClient.getQueryData<RedlineExecution>(redlineKeys.execution('r-1'))
    expect(cached?.id).toBe('re-1')
    const gets = requests.filter((request) => request.method === 'GET')
    expect(gets).toHaveLength(1)
  })

  it('refetches the canonical failed execution after a 409 and never touches other keys', async () => {
    let getCount = 0
    const failedPayload = {
      ...generatedPayload,
      status: 'FAILED',
      outcome: null,
      failureCode: 'PREEXISTING_REVISIONS_UNSUPPORTED',
      artifactFilename: null,
      artifactSizeBytes: null,
      artifactSha256: null,
      downloadAvailable: false,
    }
    const requests = stubFetch((request) => {
      if (request.method === 'POST') return errorResponse(409)
      getCount += 1
      return getCount === 1
        ? errorResponse(404)
        : jsonResponse(envelope(failedPayload))
    })
    const { queryClient, wrapper } = createWrapper()
    queryClient.setQueryData(['reviews', 'decisions', 'r-1'], { items: [], truncated: false })
    const { result } = renderHook(
      () => ({ query: useRedlineExecutionQuery('r-1', true), trigger: useTriggerRedline('r-1') }),
      { wrapper },
    )
    await waitFor(() =>
      expect(isRedlineNotFoundError(result.current.query.error)).toBe(true),
    )
    act(() => result.current.trigger.mutate())
    await waitFor(() => expect(result.current.trigger.isError).toBe(true))
    await waitFor(() =>
      expect(
        queryClient.getQueryData<RedlineExecution>(redlineKeys.execution('r-1'))?.status,
      ).toBe('FAILED'),
    )
    const gets = requests.filter((request) => request.method === 'GET')
    expect(gets).toHaveLength(2)
    const otherReads = requests.filter(
      (request) =>
        request.method === 'GET' &&
        (request.url.includes('/decisions') ||
          request.url.includes('/human-review') ||
          request.url.includes('/approved-change-set')),
    )
    expect(otherReads).toHaveLength(0)
  })
})

describe('redline download', () => {
  it('returns the verified bytes and the server-provided filename', async () => {
    const bytes = new Uint8Array([0x50, 0x4b, 0x03, 0x04])
    const requests = stubFetch(
      () =>
        new Response(bytes, {
          status: 200,
          headers: {
            'Content-Type':
              'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
            'Content-Disposition': "attachment; filename*=UTF-8''acme-msa-v1-redline.docx",
          },
        }),
    )
    const download = await downloadRedline('r-1')
    expect(download.filename).toBe('acme-msa-v1-redline.docx')
    expect(download.blob.size).toBe(4)
    expect(requests[0].url).toContain('/api/reviews/r-1/redline/download')
  })

  it('falls back to a plain DOCX filename when the header is absent', async () => {
    stubFetch(() => new Response(new Uint8Array([1]), { status: 200 }))
    const download = await downloadRedline('r-1')
    expect(download.filename).toBe('redline.docx')
  })

  it('rejects an empty or non-DOCX download body before it can be saved', async () => {
    stubFetch(
      () =>
        new Response(new Uint8Array([]), {
          status: 200,
          headers: { 'Content-Type': 'application/octet-stream' },
        }),
    )
    const empty = await downloadRedline('r-1').catch((error) => error)
    expect(empty).toBeInstanceOf(RedlineDownloadIntegrityError)

    stubFetch(
      () =>
        new Response(new Uint8Array([1, 2]), {
          status: 200,
          headers: { 'Content-Type': 'text/html' },
        }),
    )
    const wrongType = await downloadRedline('r-1').catch((error) => error)
    expect(wrongType).toBeInstanceOf(RedlineDownloadIntegrityError)
  })

  it('reports download unavailability as a typed error, separate from execution state', async () => {
    stubFetch(() => errorResponse(409))
    const conflict = await downloadRedline('r-1').catch((error) => error)
    expect(conflict).toBeInstanceOf(RedlineApiError)
    expect(conflict).toMatchObject({ status: 409 })

    stubFetch(() => errorResponse(403))
    const forbidden = await downloadRedline('r-1').catch((error) => error)
    expect(isRedlineForbiddenError(forbidden)).toBe(true)
  })

  it('propagates transport failures without marking the download as available', async () => {
    stubFetch(() => {
      throw new TypeError('Failed to fetch')
    })
    const failure = await downloadRedline('r-1').catch((error) => error)
    expect(failure).toBeInstanceOf(TypeError)
  })
})
