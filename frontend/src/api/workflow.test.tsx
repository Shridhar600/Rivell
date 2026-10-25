import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  fetchApprovedChangeSet,
  fetchCurrentDecisions,
  fetchHumanReview,
  isWorkflowConflictError,
  isWorkflowForbiddenError,
  isWorkflowNotFoundError,
  parseApprovedChangeSet,
  parseDecision,
  parseDecisionList,
  parseHumanReview,
  submitDecision,
  useApprovedChangeSetQuery,
  useCurrentDecisionsQuery,
  useDecisionHistoryQuery,
  useHumanReviewQuery,
  useSubmitDecision,
  workflowKeys,
} from './workflow'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-26T10:00:00Z' }
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
})

const decisionPayload = {
  id: 'd-2',
  findingId: 'f-1',
  revision: 2,
  action: 'EDIT',
  approvedAction: 'REPLACE',
  approvedLanguage: 'The total liability shall not exceed the fees paid.',
  languageSource: 'HUMAN_EDIT',
  approvedDraftingEvidenceId: null,
  comment: 'Aligned with the negotiated cap.',
  actorId: 'u-1',
  decidedAt: '2026-09-26T09:00:00Z',
  clientRequestId: 'cr-1',
  supersedesDecisionId: 'd-1',
}

const humanReviewPayload = {
  reviewId: 'r-1',
  status: 'IN_PROGRESS',
  analysisStatus: 'COMPLETED',
  actionableFindings: 3,
  resolvedFindings: 1,
  remainingFindings: 2,
  accepted: 0,
  edited: 1,
  rejected: 0,
  escalated: 1,
  informationalFindings: 2,
  informationalDecided: 1,
  readyToFinalize: false,
  blockers: [
    {
      code: 'UNRESOLVED_REVIEW_REQUIRED_FINDINGS',
      message: '2 findings still need a decision.',
      findingIds: ['f-2', 'f-3'],
    },
  ],
  changeSetId: null,
  finalizedBy: null,
  finalizedAt: null,
}

const changeSetPayload = {
  id: 'cs-1',
  reviewId: 'r-1',
  contractId: 'c-1',
  contractVersionNumber: 1,
  runId: 'run-1',
  changeSetVersion: 1,
  finalizedBy: 'u-1',
  finalizedAt: '2026-09-26T11:00:00Z',
  changes: [
    {
      id: 'ch-1',
      findingId: 'f-1',
      decisionId: 'd-2',
      ordinal: 1,
      actionType: 'REPLACE',
      targetKind: 'CLAUSE',
      targetHeadingNodeId: 'node-6',
      absenceConcept: null,
      approvedLanguage: 'The total liability shall not exceed the fees paid.',
      languageSource: 'HUMAN_EDIT',
      approvedDraftingEvidenceId: null,
    },
  ],
}

  it('writes the decision list from the response instead of refetching it', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(decisionPayload)))
    const { queryClient, wrapper } = createWrapper()
    const invalidated: string[][] = []
    const originalInvalidate = queryClient.invalidateQueries.bind(queryClient)
    queryClient.invalidateQueries = ((filters: { queryKey?: readonly unknown[] }) => {
      if (filters?.queryKey) invalidated.push(filters.queryKey.map(String))
      return originalInvalidate(filters as never)
    }) as typeof queryClient.invalidateQueries

    const submit = renderHook(() => useSubmitDecision('r-1'), { wrapper })
    await act(async () => {
      await submit.result.current.mutateAsync({
        findingId: 'f-1',
        expectedVersion: 0,
        action: 'ACCEPT' as never,
      })
    })

    expect(requests).toHaveLength(1)
    expect(invalidated).toEqual([
      ['reviews', 'human-review', 'r-1'],
      ['reviews', 'decision-history', 'r-1'],
    ])
  })

describe('workflow parsers', () => {
  it('parses a decision revision with language provenance and revision chain', () => {
    const list = parseDecisionList({ items: [decisionPayload], truncated: false })
    expect(list.items).toHaveLength(1)
    const decision = list.items[0]
    expect(decision.id).toBe('d-2')
    expect(decision.findingId).toBe('f-1')
    expect(decision.revision).toBe(2)
    expect(decision.action).toBe('EDIT')
    expect(decision.approvedAction).toBe('REPLACE')
    expect(decision.approvedLanguage).toContain('total liability')
    expect(decision.languageSource).toBe('HUMAN_EDIT')
    expect(decision.comment).toBe('Aligned with the negotiated cap.')
    expect(decision.supersedesDecisionId).toBe('d-1')
  })

  it('keeps unknown enum values visible instead of guessing', () => {
    const list = parseDecisionList({
      items: [{ ...decisionPayload, action: 'DELEGATE', approvedAction: 'MERGE' }],
      truncated: false,
    })
    expect(list.items[0].action).toBe('UNKNOWN')
    expect(list.items[0].approvedAction).toBe('UNKNOWN')
  })

  it('parses human review counts, status and typed blockers', () => {
    const review = parseHumanReview(humanReviewPayload)
    expect(review.status).toBe('IN_PROGRESS')
    expect(review.actionableFindings).toBe(3)
    expect(review.resolvedFindings).toBe(1)
    expect(review.remainingFindings).toBe(2)
    expect(review.escalated).toBe(1)
    expect(review.informationalFindings).toBe(2)
    expect(review.informationalDecided).toBe(1)
    expect(review.readyToFinalize).toBe(false)
    expect(review.blockers).toEqual([
      {
        code: 'UNRESOLVED_REVIEW_REQUIRED_FINDINGS',
        message: '2 findings still need a decision.',
        findingIds: ['f-2', 'f-3'],
      },
    ])
    expect(review.changeSetId).toBeNull()
  })

  it('fails closed on a decision that omits its actor or timestamp', () => {
    const complete = {
      id: 'd-1',
      findingId: 'f-1',
      revision: 1,
      action: 'ACCEPT',
      approvedAction: null,
      approvedLanguage: null,
      languageSource: null,
      approvedDraftingEvidenceId: null,
      comment: null,
      actorId: 'u-1',
      decidedAt: '2026-09-26T10:00:00Z',
      clientRequestId: null,
      supersedesDecisionId: null,
    }

    expect(parseDecision(complete).actorId).toBe('u-1')
    expect(parseDecision(complete).decidedAt).toBe('2026-09-26T10:00:00Z')
    expect(() => parseDecision({ ...complete, actorId: null })).toThrow(/could not read this response/i)
    expect(() => parseDecision({ ...complete, actorId: 7 })).toThrow(/could not read this response/i)
    expect(() => parseDecision({ ...complete, decidedAt: undefined })).toThrow(/could not read this response/i)
  })

  it('keeps unknown human status and blocker codes readable', () => {
    const review = parseHumanReview({
      ...humanReviewPayload,
      status: 'PAUSED',
      blockers: [{ code: 'SOMETHING_NEW', message: 'New blocker.', findingIds: [] }],
    })
    expect(review.status).toBe('UNKNOWN')
    expect(review.blockers[0].code).toBe('SOMETHING_NEW')
  })

  it('parses the frozen approved change set with target and provenance', () => {
    const changeSet = parseApprovedChangeSet(changeSetPayload)
    expect(changeSet.changeSetVersion).toBe(1)
    expect(changeSet.contractVersionNumber).toBe(1)
    expect(changeSet.changes).toHaveLength(1)
    expect(changeSet.changes[0]).toMatchObject({
      findingId: 'f-1',
      decisionId: 'd-2',
      actionType: 'REPLACE',
      targetKind: 'CLAUSE',
      targetHeadingNodeId: 'node-6',
      languageSource: 'HUMAN_EDIT',
    })
  })

  it('parses an absence INSERT change without inventing a clause target', () => {
    const changeSet = parseApprovedChangeSet({
      ...changeSetPayload,
      changes: [
        {
          ...changeSetPayload.changes[0],
          actionType: 'INSERT',
          targetKind: 'ABSENCE',
          targetHeadingNodeId: null,
          absenceConcept: 'LIMITATION_OF_LIABILITY',
        },
      ],
    })
    expect(changeSet.changes[0].targetHeadingNodeId).toBeNull()
    expect(changeSet.changes[0].absenceConcept).toBe('LIMITATION_OF_LIABILITY')
  })

  it('rejects a malformed change set instead of showing partial truth', () => {
    expect(() => parseApprovedChangeSet({ ...changeSetPayload, changes: 'nope' })).toThrow(
      /could not read this response/,
    )
  })
})

describe('workflow query keys', () => {
  it('scopes every workflow resource by review and history by finding', () => {
    expect(workflowKeys.decisions('r-1')).not.toEqual(workflowKeys.decisions('r-2'))
    expect(workflowKeys.humanReview('r-1')).not.toEqual(workflowKeys.humanReview('r-2'))
    expect(workflowKeys.changeSet('r-1')).not.toEqual(workflowKeys.changeSet('r-2'))
    expect(workflowKeys.decisionHistory('r-1', 'f-1')).not.toEqual(
      workflowKeys.decisionHistory('r-1', 'f-2'),
    )
    expect(workflowKeys.decisionHistory('r-1', 'f-1')).not.toEqual(
      workflowKeys.decisionHistory('r-2', 'f-1'),
    )
  })
})

describe('workflow fetchers', () => {
  it('reads current decisions with a bounded limit and parses the envelope', async () => {
    const requests = stubFetch(() =>
      jsonResponse(envelope({ items: [decisionPayload], truncated: false })),
    )
    const list = await fetchCurrentDecisions('r-1')
    expect(list.items[0].id).toBe('d-2')
    const url = new URL(requests[0].url)
    expect(url.pathname).toBe('/api/reviews/r-1/decisions')
    expect(url.searchParams.get('limit')).toBe('100')
  })

  it('reads human review and the change set from their review-scoped paths', async () => {
    const requests = stubFetch((request) =>
      request.url.includes('human-review')
        ? jsonResponse(envelope(humanReviewPayload))
        : jsonResponse(envelope(changeSetPayload)),
    )
    const review = await fetchHumanReview('r-1')
    const changeSet = await fetchApprovedChangeSet('r-1')
    expect(review.status).toBe('IN_PROGRESS')
    expect(changeSet.id).toBe('cs-1')
    expect(new URL(requests[0].url).pathname).toBe('/api/reviews/r-1/human-review')
    expect(new URL(requests[1].url).pathname).toBe('/api/reviews/r-1/approved-change-set')
  })

  it('classifies workflow HTTP failures by status without leaking raw detail', async () => {
    stubFetch(() =>
      jsonResponse(
        { code: '409', title: 'Invalid state', detail: 'Finding f-1 decision expected version 3' },
        409,
      ),
    )
    const conflict = await fetchHumanReview('r-1').catch((error: unknown) => error)
    expect(isWorkflowConflictError(conflict)).toBe(true)
    expect((conflict as Error).message).not.toContain('Finding f-1')
    stubFetch(() => jsonResponse({ code: '404', title: 'Not found', detail: 'x' }, 404))
    const missing = await fetchCurrentDecisions('r-1').catch((error: unknown) => error)
    expect(isWorkflowNotFoundError(missing)).toBe(true)
    stubFetch(() => jsonResponse({ code: '403', title: 'Forbidden', detail: 'x' }, 403))
    const forbidden = await fetchHumanReview('r-1').catch((error: unknown) => error)
    expect(isWorkflowForbiddenError(forbidden)).toBe(true)
  })
})

describe('decision submission body', () => {
  it('sends ACCEPT with only expectedVersion, action and idempotency key', async () => {
    const requests = stubFetch(() => jsonResponse(envelope({ ...decisionPayload, action: 'ACCEPT' })))
    await submitDecision('r-1', {
      findingId: 'f-1',
      expectedVersion: 0,
      action: 'ACCEPT',
      clientRequestId: 'cr-9',
    })
    const request = requests[0]
    expect(request.method).toBe('PUT')
    expect(new URL(request.url).pathname).toBe('/api/reviews/r-1/findings/f-1/decision')
    const body = await request.clone().json()
    expect(body).toEqual({ expectedVersion: 0, action: 'ACCEPT', clientRequestId: 'cr-9' })
    expect(body).not.toHaveProperty('approvedAction')
    expect(body).not.toHaveProperty('approvedLanguage')
  })

  it('sends EDIT with human language and never a drafting evidence id', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(decisionPayload)))
    await submitDecision('r-1', {
      findingId: 'f-1',
      expectedVersion: 1,
      action: 'EDIT',
      approvedAction: 'REPLACE',
      approvedLanguage: 'Human text.',
      comment: 'Because.',
      clientRequestId: 'cr-10',
    })
    const body = await requests[0].clone().json()
    expect(body).toEqual({
      expectedVersion: 1,
      action: 'EDIT',
      approvedAction: 'REPLACE',
      approvedLanguage: 'Human text.',
      comment: 'Because.',
      clientRequestId: 'cr-10',
    })
    expect(body).not.toHaveProperty('approvedDraftingEvidenceId')
  })

  it('omits language for DELETE edits', async () => {
    const requests = stubFetch(() =>
      jsonResponse(envelope({ ...decisionPayload, approvedAction: 'DELETE', approvedLanguage: null })),
    )
    await submitDecision('r-1', {
      findingId: 'f-1',
      expectedVersion: 2,
      action: 'EDIT',
      approvedAction: 'DELETE',
    })
    const body = await requests[0].clone().json()
    expect(body).toEqual({
      expectedVersion: 2,
      action: 'EDIT',
      approvedAction: 'DELETE',
    })
    expect(body).not.toHaveProperty('approvedLanguage')
  })

  it('requires and sends the comment for REJECT and ESCALATE', async () => {
    const requests = stubFetch(() => jsonResponse(envelope({ ...decisionPayload, action: 'REJECT' })))
    await submitDecision('r-1', {
      findingId: 'f-1',
      expectedVersion: 2,
      action: 'REJECT',
      comment: 'Not acceptable for this deal.',
    })
    const body = await requests[0].clone().json()
    expect(body.action).toBe('REJECT')
    expect(body.comment).toBe('Not acceptable for this deal.')
  })

  it('does not retry a failed decision submission even when mutation retries are configured', async () => {
    const requests = stubFetch(() => jsonResponse({ code: '500', title: 'Error' }, 500))
    const { wrapper } = createWrapper(2)
    const { result } = renderHook(() => useSubmitDecision('r-1'), { wrapper })
    result.current.mutate({
      findingId: 'f-1',
      expectedVersion: 0,
      action: 'ACCEPT',
      clientRequestId: 'cr-11',
    })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(requests).toHaveLength(1)
  })
})

describe('workflow hooks', () => {
  it('does not fetch decisions, human review or change set while disabled', async () => {
    const requests = stubFetch(() => jsonResponse(envelope({ items: [], truncated: false })))
    const { wrapper } = createWrapper()
    renderHook(
      () => {
        useCurrentDecisionsQuery('r-1', false)
        useHumanReviewQuery('r-1', false)
        useApprovedChangeSetQuery('r-1', false)
        useDecisionHistoryQuery('r-1', 'f-1', false)
      },
      { wrapper },
    )
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(requests).toHaveLength(0)
  })

  it('seeds the current decisions cache from a successful submission', async () => {
    const requests = stubFetch((request) =>
      request.method === 'PUT'
        ? jsonResponse(envelope(decisionPayload))
        : jsonResponse(envelope({ items: [], truncated: false })),
    )
    const { wrapper, queryClient } = createWrapper()
    queryClient.setQueryData(workflowKeys.decisions('r-1'), {
      items: [{ ...decisionPayload, id: 'd-old', revision: 1 }],
      truncated: false,
    })
    const { result } = renderHook(() => useSubmitDecision('r-1'), { wrapper })
    result.current.mutate({
      findingId: 'f-1',
      expectedVersion: 1,
      action: 'EDIT',
      approvedAction: 'REPLACE',
      approvedLanguage: 'Human text.',
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const cached = queryClient.getQueryData<{ items: { id: string }[] }>(
      workflowKeys.decisions('r-1'),
    )
    expect(cached?.items[0]?.id).toBe('d-2')
    expect(cached?.items).toHaveLength(1)
    expect(requests.length).toBeGreaterThan(0)
  })

  it('fetches the change set only when enabled', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(changeSetPayload)))
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useApprovedChangeSetQuery('r-1', true), { wrapper })
    await waitFor(() => expect(result.current.data?.id).toBe('cs-1'))
    expect(requests).toHaveLength(1)
  })
})
