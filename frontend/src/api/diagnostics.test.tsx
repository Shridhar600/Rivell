import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  ReviewDiagnosticsApiError,
  diagnosticsKeys,
  fetchReviewDiagnostics,
  isReviewDiagnosticsForbiddenError,
  isReviewDiagnosticsNotFoundError,
  parseReviewDiagnostics,
  useReviewDiagnosticsQuery,
} from './diagnostics'
import { redlineKeys } from './redline'
import { retrievalKeys } from './retrieval'
import { reviewKeys } from './reviews'
import { semanticsKeys } from './semantics'
import { workflowKeys } from './workflow'

const RAW_INTERNAL = 'IllegalStateException:Review snapshot missing@com.rivell.workbench.review.ReviewService.require:81'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-27T10:00:00Z' }
}

function stubFetch(handler: () => Response | Promise<Response>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    void input
    void init
    return handler()
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { wrapper }
}

const completedPayload = {
  reviewId: 'r-1',
  contractId: 'c-1',
  contractVersionNumber: 2,
  reviewStatus: 'COMPLETED',
  reviewFailure: null,
  backgroundWorkerEnabled: true,
  analysis: {
    runId: 'run-1',
    runNumber: 1,
    status: 'COMPLETED',
    trigger: 'INITIAL',
    pipelineVersion: 'rivell-review-1',
    startedAt: '2026-09-27T09:00:00Z',
    completedAt: '2026-09-27T09:02:00Z',
    ageSeconds: 120,
    failureReason: null,
    environmentMatchesSnapshot: true,
    checks: [
      {
        key: 'CLAUSE:LIMITATION_OF_LIABILITY:9f0b1f2e',
        status: 'COMPLETED',
        signalCount: 2,
        degradedReason: null,
        startedAt: '2026-09-27T09:00:10Z',
        completedAt: '2026-09-27T09:00:40Z',
        durationMillis: 30000,
      },
      {
        key: 'PRESENCE:CONFIDENTIALITY',
        status: 'DEGRADED',
        signalCount: 0,
        degradedReason: RAW_INTERNAL,
        startedAt: '2026-09-27T09:00:10Z',
        completedAt: '2026-09-27T09:00:50Z',
        durationMillis: 40000,
      },
    ],
  },
  findings: { total: 3, blocking: 1, reviewRequired: 1, informational: 1 },
  decisions: { accepted: 1, edited: 0, rejected: 1, escalated: 0 },
  finalization: {
    finalized: true,
    changeSetId: 'cs-1',
    finalizedAt: '2026-09-27T10:00:00Z',
    approvedChanges: 2,
  },
  redline: {
    status: 'COMPLETED',
    outcome: 'REDLINE_GENERATED',
    failureCode: null,
    changeCount: 2,
    downloadAvailable: true,
    ageSeconds: 30,
  },
  stalled: [],
}

afterEach(() => {
  vi.unstubAllGlobals()
})

import completedWire from '../test/fixtures/diagnosticsCompleted.json'
import failedRedlineWire from '../test/fixtures/diagnosticsFailedRedline.json'

describe('parseReviewDiagnostics against recorded backend responses', () => {
  it('maps a real completed-review response', () => {
    const parsed = parseReviewDiagnostics(completedWire.data)

    expect(parsed.reviewId).toBe('5fed6948-d76a-4596-bcf5-5d1298d75f16')
    expect(parsed.reviewStatus).toBe('COMPLETED')
    expect(parsed.backgroundWorkerEnabled).toBe(true)
    expect(parsed.analysis?.status).toBe('COMPLETED')
    expect(parsed.analysis?.trigger).toBe('INITIAL')
    expect(parsed.analysis?.pipelineVersion).toBe('rivell-review-pipeline-1')
    expect(parsed.analysis?.runDurationSeconds).toBe(0)
    expect(parsed.analysis?.semanticsMatchFrozenSnapshot).toBe(true)
    expect(parsed.analysis?.checks).toHaveLength(8)
    expect(parsed.analysis?.checks[0].key).toMatch(/^PRESENCE:/)
    expect(parsed.findings.total).toBe(2)
    expect(parsed.decisions.edited).toBe(2)
    expect(parsed.finalization.finalized).toBe(true)
    expect(parsed.finalization.approvedChanges).toBe(2)
    expect(parsed.redline?.outcome).toBe('REDLINE_GENERATED')
    expect(parsed.redline?.downloadAvailable).toBe(true)
    expect(parsed.stalled).toEqual([])

    // A FAILED check writes its reason under a different detail key, so this field is null for it.
    const failedCheck = parsed.analysis?.checks.find((check) => check.status === 'FAILED')
    expect(failedCheck).toBeDefined()
    expect(failedCheck?.degradedReason).toBe('NONE')
    expect(failedCheck?.signalCount).toBe(0)
  })

  it('maps a real failed-redline response', () => {
    const parsed = parseReviewDiagnostics(failedRedlineWire.data)

    expect(parsed.redline?.status).toBe('FAILED')
    expect(parsed.redline?.outcome).toBeNull()
    expect(parsed.redline?.failureCode).toBe('APPROVED_SEMANTICS_DRIFT')
    expect(parsed.redline?.downloadAvailable).toBe(false)
    expect(parsed.redline?.changeCount).toBe(1)
    expect(parsed.finalization.approvedChanges).toBe(1)
  })

  it('carries no internal string out of a real response', () => {
    for (const wire of [completedWire, failedRedlineWire]) {
      expect(JSON.stringify(parseReviewDiagnostics(wire.data))).not.toMatch(
        /IllegalState|com\.rivell|at [a-z]+\.[A-Z]/,
      )
    }
  })
})

describe('parseReviewDiagnostics', () => {
  it('maps a completed review response field by field', () => {
    const parsed = parseReviewDiagnostics(completedPayload)

    expect(parsed.reviewId).toBe('r-1')
    expect(parsed.contractVersionNumber).toBe(2)
    expect(parsed.reviewStatus).toBe('COMPLETED')
    expect(parsed.backgroundWorkerEnabled).toBe(true)
    expect(parsed.analysis?.status).toBe('COMPLETED')
    expect(parsed.analysis?.trigger).toBe('INITIAL')
    expect(parsed.analysis?.checks).toHaveLength(2)
    expect(parsed.analysis?.checks[0].signalCount).toBe(2)
    expect(parsed.analysis?.checks[1].status).toBe('DEGRADED')
    expect(parsed.findings).toEqual({ total: 3, blocking: 1, reviewRequired: 1, informational: 1 })
    expect(parsed.decisions).toEqual({ accepted: 1, edited: 0, rejected: 1, escalated: 0 })
    expect(parsed.finalization.finalized).toBe(true)
    expect(parsed.finalization.changeSetId).toBe('cs-1')
    expect(parsed.redline?.outcome).toBe('REDLINE_GENERATED')
    expect(parsed.redline?.downloadAvailable).toBe(true)
    expect(parsed.stalled).toEqual([])
  })

  it('renames the misleading server fields rather than passing them through', () => {
    const parsed = parseReviewDiagnostics(completedPayload)

    expect(parsed.analysis?.runDurationSeconds).toBe(120)
    expect(parsed.analysis).not.toHaveProperty('ageSeconds')
    expect(parsed.analysis?.semanticsMatchFrozenSnapshot).toBe(true)
    expect(parsed.analysis).not.toHaveProperty('environmentMatchesSnapshot')
  })

  it('keeps only a presence flag for every internal string', () => {
    const parsed = parseReviewDiagnostics({
      ...completedPayload,
      reviewFailure: RAW_INTERNAL,
      analysis: { ...completedPayload.analysis, failureReason: RAW_INTERNAL },
    })

    expect(parsed.reviewFailureDetail).toBe('RECORDED')
    expect(parsed.analysis?.failureDetail).toBe('RECORDED')
    expect(parsed.analysis?.checks[1].degradedReason).toBe('RECORDED')
    const serialized = JSON.stringify(parsed)
    expect(serialized).not.toContain('IllegalStateException')
    expect(serialized).not.toContain('com.rivell')
    expect(serialized).not.toContain('require:81')
  })

  it('degrades an unrecognised code instead of throwing', () => {
    const parsed = parseReviewDiagnostics({
      ...completedPayload,
      reviewStatus: 'SOMETHING_NEW',
      analysis: {
        ...completedPayload.analysis,
        status: 'PAUSED',
        trigger: 'MANUAL',
        checks: [{ ...completedPayload.analysis.checks[0], status: 'THINKING' }],
      },
      redline: { ...completedPayload.redline, status: 'STARTED', outcome: 'MAYBE', failureCode: 'NEW_CODE' },
    })

    expect(parsed.reviewStatus).toBe('UNKNOWN')
    expect(parsed.analysis?.status).toBe('UNKNOWN')
    expect(parsed.analysis?.trigger).toBe('UNKNOWN')
    expect(parsed.analysis?.checks[0].status).toBe('UNKNOWN')
    expect(parsed.redline?.status).toBe('UNKNOWN')
    expect(parsed.redline?.outcome).toBe('UNKNOWN')
    expect(parsed.redline?.failureCode).toBe('UNKNOWN')
  })

  it('treats a missing analysis and redline as absent but keeps the counts', () => {
    const parsed = parseReviewDiagnostics({
      reviewId: 'r-1',
      contractId: 'c-1',
      contractVersionNumber: 1,
      reviewStatus: 'ANALYSIS_QUEUED',
      backgroundWorkerEnabled: false,
      findings: { total: 0, blocking: 0, reviewRequired: 0, informational: 0 },
      decisions: { accepted: 0, edited: 0, rejected: 0, escalated: 0 },
      finalization: { finalized: false, changeSetId: null, finalizedAt: null, approvedChanges: 0 },
      stalled: ['No analysis run has been created for this review.'],
    })

    expect(parsed.analysis).toBeNull()
    expect(parsed.redline).toBeNull()
    expect(parsed.reviewFailureDetail).toBe('NONE')
    expect(parsed.findings.total).toBe(0)
    expect(parsed.finalization.finalized).toBe(false)
    expect(parsed.stalled).toEqual(['No analysis run has been created for this review.'])
  })

  it('bounds the stall list', () => {
    const parsed = parseReviewDiagnostics({
      ...completedPayload,
      stalled: ['a'.repeat(500), 'b', 7, null, 'c', 'd', 'e'],
    })

    expect(parsed.stalled).toHaveLength(4)
    expect(parsed.stalled[0]).toHaveLength(200)
    expect(parsed.stalled).toEqual(['a'.repeat(200), 'b', 'c', 'd'])
  })

  it('rejects a payload with no review identity', () => {
    expect(() => parseReviewDiagnostics({ contractId: 'c-1' })).toThrow(/malformed/i)
  })

  it('fails closed rather than reporting a missing count block as zero', () => {
    const { findings, decisions, finalization, ...withoutCounts } = completedPayload
    void findings
    void decisions
    void finalization

    expect(() => parseReviewDiagnostics(withoutCounts)).toThrow(/malformed/i)
    expect(() =>
      parseReviewDiagnostics({ ...completedPayload, findings: null }),
    ).toThrow(/malformed/i)
    expect(() =>
      parseReviewDiagnostics({ ...completedPayload, finalization: null }),
    ).toThrow(/malformed/i)
    expect(() =>
      parseReviewDiagnostics({ ...completedPayload, decisions: undefined }),
    ).toThrow(/malformed/i)
  })

  it('fails closed when the worker flag is missing rather than claiming it is off', () => {
    const { backgroundWorkerEnabled, ...withoutWorker } = completedPayload
    void backgroundWorkerEnabled

    expect(() => parseReviewDiagnostics(withoutWorker)).toThrow(/malformed/i)
  })
})

describe('fetchReviewDiagnostics', () => {
  it('reads the diagnostics endpoint and classifies 403 and 404', async () => {
    stubFetch(() => jsonResponse(envelope(completedPayload), 403))
    const forbidden = await fetchReviewDiagnostics('r-1').catch((thrown: unknown) => thrown)
    expect(isReviewDiagnosticsForbiddenError(forbidden)).toBe(true)
    expect(isReviewDiagnosticsNotFoundError(forbidden)).toBe(false)
    expect((forbidden as Error).message).toMatch(/permission/i)
    expect((forbidden as Error).message).not.toMatch(/IllegalState|com\.rivell/)

    stubFetch(() => jsonResponse(envelope(completedPayload), 404))
    const missing = await fetchReviewDiagnostics('r-1').catch((thrown: unknown) => thrown)
    expect(isReviewDiagnosticsNotFoundError(missing)).toBe(true)
    expect(isReviewDiagnosticsForbiddenError(missing)).toBe(false)
  })

  it('builds the endpoint from the generated contract', async () => {
    const fetchMock = stubFetch(() => jsonResponse(envelope(completedPayload)))
    await fetchReviewDiagnostics('r-9')

    const request = fetchMock.mock.calls[0][0] as unknown as Request
    expect(request.method).toBe('GET')
    expect(request.url).toContain('/api/reviews/r-9/diagnostics')
  })
})

describe('useReviewDiagnosticsQuery', () => {
  it('issues exactly one request when enabled and does not retry a failure', async () => {
    const fetchMock = stubFetch(() => jsonResponse({ code: '500', detail: RAW_INTERNAL }, 500))
    const { wrapper } = createWrapper()
    const query = renderHook(() => useReviewDiagnosticsQuery('r-1', true), { wrapper })

    await waitFor(() => expect(query.result.current.isError).toBe(true))

    expect(query.result.current.error).toBeInstanceOf(ReviewDiagnosticsApiError)
    expect((query.result.current.error as Error).message).not.toMatch(/IllegalState|com\.rivell/)
    const diagnosticsCalls = fetchMock.mock.calls.filter((call) =>
      (call[0] as Request).url.includes('/diagnostics'),
    )
    expect(diagnosticsCalls).toHaveLength(1)
    expect((query.result.current as { refetchInterval?: unknown }).refetchInterval).toBeFalsy()
  })

  it('issues no request while it is not enabled', async () => {
    const fetchMock = stubFetch(() => jsonResponse(envelope(completedPayload)))
    const { wrapper } = createWrapper()
    const query = renderHook(() => useReviewDiagnosticsQuery('r-1', false), { wrapper })

    await act(async () => {
      await Promise.resolve()
    })

    expect(query.result.current.fetchStatus).toBe('idle')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('issues no request without a review id', async () => {
    const fetchMock = stubFetch(() => jsonResponse(envelope(completedPayload)))
    const { wrapper } = createWrapper()
    renderHook(() => useReviewDiagnosticsQuery(undefined, true), { wrapper })

    await act(async () => {
      await Promise.resolve()
    })

    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('diagnostics query key', () => {
  it('cannot be swallowed by an invalidation prefix of another review query', () => {
    const id = 'r-1'
    const diagnostics = diagnosticsKeys.forReview(id)
    const others = [
      reviewKeys.packs(),
      reviewKeys.list('c-1'),
      reviewKeys.summary(id),
      reviewKeys.progress(id),
      reviewKeys.findings(id, {}),
      reviewKeys.finding(id, 'f-1'),
      workflowKeys.decisions(id),
      workflowKeys.humanReview(id),
      workflowKeys.decisionHistory(id, 'f-1'),
      workflowKeys.changeSet(id),
      redlineKeys.execution(id),
      semanticsKeys.semantics('c-1', 1),
      retrievalKeys.bundle('c-1', 1, 'n-1'),
      retrievalKeys.trace('t-1'),
    ]

    const isPrefix = (prefix: readonly unknown[], key: readonly unknown[]) =>
      prefix.every((part, index) => part === key[index])

    for (const other of others) {
      expect(isPrefix(other, diagnostics)).toBe(false)
      expect(isPrefix(diagnostics, other)).toBe(false)
    }
  })
})
