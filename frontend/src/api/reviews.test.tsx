import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  createReview,
  isReviewConflictError,
  isReviewMalformedError,
  parseFindingDetail,
  parseFindingList,
  parseReviewList,
  parseReviewProgress,
  parseReviewSummary,
  reviewKeys,
  reviewPollInterval,
  useCreateReview,
  useFindingsQuery,
  useReviewSummaryQuery,
} from './reviews'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-25T10:00:00Z' }
}

function stubFetch(handler: (request: Request) => Response) {
  const requests: Request[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init)
    requests.push(request)
    return handler(request)
  })
  vi.stubGlobal('fetch', fetchMock)
  return requests
}

function createWrapper(mutationRetry = 0) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: mutationRetry },
    },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { queryClient, wrapper }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

const queuedSummary = {
  id: 'r-1',
  contractId: 'c-1',
  contractVersionNumber: 1,
  status: 'ANALYSIS_QUEUED',
  failureReason: null,
  createdAt: '2026-09-25T09:00:00Z',
  startedAt: null,
  completedAt: null,
  activeRun: null,
  latestRunStatus: null,
  plannedChecks: 3,
  completedChecks: 0,
  degradedChecks: 0,
  failedChecks: 0,
  signals: 0,
  findings: 0,
  contractPackKey: 'MSA_PACK',
  packVersion: 'rivell-review-pack-1',
  knowledgePinNumber: 6,
  analysisWarnings: [],
  analysisWarningsTruncated: false,
}

const completedSummary = {
  ...queuedSummary,
  status: 'COMPLETED',
  startedAt: '2026-09-25T09:01:00Z',
  completedAt: '2026-09-25T09:02:00Z',
  activeRun: { id: 'run-1', runNumber: 1, status: 'COMPLETED' },
  latestRunStatus: 'COMPLETED',
  completedChecks: 3,
  degradedChecks: 1,
  signals: 5,
  findings: 2,
  analysisWarnings: [
    {
      checkKey: 'LIABILITY_CAP:LIMITATION_OF_LIABILITY',
      status: 'DEGRADED',
      reason: 'provider_unavailable: bounded semantic decision unavailable',
    },
  ],
}

const clauseFindingDetail = {
  id: 'f-1',
  reviewId: 'r-1',
  runId: 'run-1',
  subjectKind: 'CLAUSE',
  subjectHeadingNodeId: 'node-6',
  absenceConcept: null,
  concept: 'LIMITATION_OF_LIABILITY',
  secondaryConcept: null,
  concepts: ['LIMITATION_OF_LIABILITY'],
  issueFamily: 'LIABILITY_CAP_DEVIATION',
  observationKind: 'CAP_ABOVE_PREFERRED',
  observationText:
    "The bounded classification found the liability cap above the organization's preferred position (cap multiple 3x).",
  severity: 'HIGH',
  reviewClassification: 'REVIEW_REQUIRED',
  proposedAction: 'USE_APPROVED_FALLBACK',
  proposedLanguage: 'The total liability of Supplier shall not exceed two times the fees paid.',
  proposedLanguageSource: 'APPROVED_DRAFTING',
  approvedDraftingEvidenceId: 'e-drafting',
  quality: {
    sufficiency: 'SUFFICIENT',
    verificationOutcome: 'VERIFIED',
    degradationFlags: [],
  },
  claims: [
    {
      ordinal: 0,
      claimKind: 'OBSERVATION',
      text: "The bounded classification found the liability cap above the organization's preferred position.",
      absence: false,
      evidenceItemIds: ['e-clause', 'e-policy'],
    },
    {
      ordinal: 1,
      claimKind: 'POLICY_BASIS',
      text: 'Published policy prefers a cap of two times the fees paid.',
      absence: false,
      evidenceItemIds: ['e-policy'],
    },
    {
      ordinal: 2,
      claimKind: 'RECOMMENDATION',
      text: 'Use the approved fallback wording for this clause.',
      absence: false,
      evidenceItemIds: ['e-drafting'],
    },
  ],
  evidenceItemIds: ['e-clause', 'e-policy', 'e-drafting'],
  signalIds: ['s-1', 's-2', 's-3'],
  absenceBasis: null,
  createdAt: '2026-09-25T09:02:00Z',
}

const absenceFindingDetail = {
  ...clauseFindingDetail,
  id: 'f-2',
  subjectKind: 'ABSENCE',
  subjectHeadingNodeId: null,
  absenceConcept: 'LIMITATION_OF_LIABILITY',
  concept: 'LIMITATION_OF_LIABILITY',
  concepts: ['LIMITATION_OF_LIABILITY'],
  issueFamily: 'MISSING_REQUIRED_PROVISION',
  observationKind: 'MISSING_REQUIRED_CONCEPT',
  observationText: 'The required provision LIMITATION_OF_LIABILITY is missing from this contract.',
  severity: 'HIGH',
  reviewClassification: 'BLOCKING',
  proposedAction: 'ADD_REQUIRED_PROVISION',
  proposedLanguage: null,
  proposedLanguageSource: 'NONE',
  approvedDraftingEvidenceId: null,
  quality: { sufficiency: null, verificationOutcome: null, degradationFlags: [] },
  claims: [
    {
      ordinal: 0,
      claimKind: 'ABSENCE_BASIS',
      text: 'No limitation of liability provision was found in this contract.',
      absence: true,
      evidenceItemIds: [],
    },
  ],
  evidenceItemIds: [],
  signalIds: ['s-4'],
  absenceBasis: { planCheckId: 'pc-1', checkKey: 'PRESENCE:LIMITATION_OF_LIABILITY' },
}

describe('review parsers', () => {
  it('parses a completed summary with pack, pin and warnings', () => {
    const summary = parseReviewSummary(completedSummary)
    expect(summary.status).toBe('COMPLETED')
    expect(summary.activeRun?.status).toBe('COMPLETED')
    expect(summary.contractPackKey).toBe('MSA_PACK')
    expect(summary.knowledgePinNumber).toBe(6)
    expect(summary.analysisWarnings[0].checkKey).toBe('LIABILITY_CAP:LIMITATION_OF_LIABILITY')
  })

  it('tolerates unknown enum-like values instead of throwing', () => {
    const summary = parseReviewSummary({
      ...completedSummary,
      status: 'REANALYZING',
      severity: 'EXTREME',
    })
    expect(summary.status).toBe('UNKNOWN')

    const detail = parseFindingDetail({ ...clauseFindingDetail, severity: 'EXTREME' })
    expect(detail.severity).toBe('UNKNOWN')
    expect(detail.issueFamily).toBe('LIABILITY_CAP_DEVIATION')
  })

  it('parses clause finding detail including untyped quality and claims', () => {
    const detail = parseFindingDetail(clauseFindingDetail)
    expect(detail.subjectKind).toBe('CLAUSE')
    expect(detail.proposedLanguageSource).toBe('APPROVED_DRAFTING')
    expect(detail.quality).toEqual({
      sufficiency: 'SUFFICIENT',
      verificationOutcome: 'VERIFIED',
      degradationFlags: [],
    })
    expect(detail.claims.map((claim) => claim.claimKind)).toEqual([
      'OBSERVATION',
      'POLICY_BASIS',
      'RECOMMENDATION',
    ])
    expect(detail.absenceBasis).toBeNull()
  })

  it('parses absence finding detail with plan-check basis and no evidence', () => {
    const detail = parseFindingDetail(absenceFindingDetail)
    expect(detail.subjectKind).toBe('ABSENCE')
    expect(detail.subjectHeadingNodeId).toBeNull()
    expect(detail.absenceBasis?.checkKey).toBe('PRESENCE:LIMITATION_OF_LIABILITY')
    expect(detail.claims[0].absence).toBe(true)
    expect(detail.evidenceItemIds).toEqual([])
  })

  it('parses bounded lists with truncation', () => {
    const list = parseReviewList({
      items: [{ ...completedSummary, id: 'r-1' }],
      truncated: true,
    })
    expect(list.items).toHaveLength(1)
    expect(list.truncated).toBe(true)

    const findings = parseFindingList({
      items: [clauseFindingDetail, absenceFindingDetail],
      truncated: true,
    })
    expect(findings.items).toHaveLength(2)
    expect(findings.items[1].absenceConcept).toBe('LIMITATION_OF_LIABILITY')
  })

  it('parses progress counts', () => {
    const progress = parseReviewProgress({
      ...queuedSummary,
      pendingChecks: 2,
      runningChecks: 1,
      skippedChecks: 0,
    })
    expect(progress.pendingChecks).toBe(2)
    expect(progress.runningChecks).toBe(1)
  })

  it('polls only while a review is queued or analyzing', () => {
    expect(reviewPollInterval('ANALYSIS_QUEUED')).toBe(2500)
    expect(reviewPollInterval('ANALYZING')).toBe(2500)
    expect(reviewPollInterval('COMPLETED')).toBe(false)
    expect(reviewPollInterval('FAILED')).toBe(false)
    expect(reviewPollInterval('UNKNOWN')).toBe(false)
    expect(reviewPollInterval(undefined)).toBe(false)
  })
})

describe('review requests', () => {
  it('sends exactly one create POST with the explicit pack', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(queuedSummary)))
    const summary = await createReview('c-1', 2, 'MSA_PACK')
    expect(summary.id).toBe('r-1')
    expect(requests).toHaveLength(1)
    expect(requests[0].method).toBe('POST')
    expect(requests[0].url).toContain('/api/contracts/c-1/versions/2/reviews')
    expect(await requests[0].json()).toEqual({ contractPackKey: 'MSA_PACK' })
  })

  it('omits the pack key for a pack-less review', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(queuedSummary)))
    await createReview('c-1', 1)
    expect(await requests[0].json()).toEqual({})
  })

  it('does not retry a failed creation (no duplicate review)', async () => {
    const requests = stubFetch(() =>
      jsonResponse({ code: 'ENRICHMENT_NOT_READY', detail: 'Semantic analysis not ready' }, 409),
    )
    await expect(createReview('c-1', 1)).rejects.toSatisfy(isReviewConflictError)
    expect(requests).toHaveLength(1)
  })

  it('never retries the create mutation and seeds the summary cache', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(queuedSummary)))
    const { queryClient, wrapper } = createWrapper()
    const create = renderHook(() => useCreateReview('c-1', 1), { wrapper })

    await act(async () => {
      await create.result.current.mutateAsync({ contractPackKey: 'MSA_PACK' })
    })

    expect(requests).toHaveLength(1)
    expect(queryClient.getQueryData(reviewKeys.summary('r-1'))).toMatchObject({ id: 'r-1' })
  })

  it('overrides an app-level mutation retry so a failed create never repeats', async () => {
    const requests = stubFetch(() => jsonResponse({ code: 'SERVER_ERROR' }, 500))
    const { wrapper } = createWrapper(2)
    const create = renderHook(() => useCreateReview('c-1', 1), { wrapper })

    await act(async () => {
      await expect(create.result.current.mutateAsync({})).rejects.toThrow()
    })

    expect(requests).toHaveLength(1)
  })

  it('surfaces malformed payloads as malformed errors', async () => {
    stubFetch(() => jsonResponse(envelope({ id: 'r-1' })))
    await expect(createReview('c-1', 1)).rejects.toSatisfy(isReviewMalformedError)
  })
})

describe('review polling integration', () => {
  it('polls a queued review and stops once it completes', async () => {
    vi.useFakeTimers()
    let call = 0
    const requests = stubFetch(() => {
      const body = call === 0 ? queuedSummary : completedSummary
      call += 1
      return jsonResponse(envelope(body))
    })
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useReviewSummaryQuery('r-1'), { wrapper })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10)
    })
    expect(result.current.data?.status).toBe('ANALYSIS_QUEUED')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600)
    })
    expect(result.current.data?.status).toBe('COMPLETED')
    expect(requests).toHaveLength(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000)
    })
    expect(requests).toHaveLength(2)
    vi.useRealTimers()
  })
})

describe('findings query gating', () => {
  it('does not request findings while disabled and includes filters when enabled', async () => {
    const requests = stubFetch(() => jsonResponse(envelope({ items: [], truncated: false })))
    const { wrapper } = createWrapper()

    const disabled = renderHook(() => useFindingsQuery('r-1', {}, false), { wrapper })
    await act(async () => {
      await Promise.resolve()
    })
    expect(disabled.result.current.fetchStatus).toBe('idle')
    expect(requests).toHaveLength(0)

    const enabled = renderHook(
      () => useFindingsQuery('r-1', { severity: 'HIGH', classification: 'BLOCKING' }, true),
      { wrapper },
    )
    await waitFor(() => expect(enabled.result.current.data?.items).toEqual([]))
    expect(requests).toHaveLength(1)
    expect(requests[0].url).toContain('severity=HIGH')
    expect(requests[0].url).toContain('classification=BLOCKING')
    expect(requests[0].url).toContain('limit=100')
  })
})