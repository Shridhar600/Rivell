import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  buildEvidence,
  fetchRetrievalTrace,
  isConflictError,
  isForbiddenError,
  isMalformedError,
  isNotFoundError,
  parseEvidenceBundle,
  parseRetrievalTrace,
  retrievalKeys,
  useBuildEvidence,
  useEvidenceBundle,
  useRetrievalTraceQuery,
} from './retrieval'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-24T05:00:00Z' }
}

function stubFetch(handler: (request: Request) => Response) {
  const requests: Request[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request =
      input instanceof Request ? input : new Request(String(input), init)
    requests.push(request)
    return handler(request)
  })
  vi.stubGlobal('fetch', fetchMock)
  return requests
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const policyItem = {
  evidenceId: 'e-policy',
  authorityClass: 'POLICY',
  applicabilityScope: {
    contractPackKey: 'MSA_PACK',
    declaredContractTypes: ['MSA'],
    partyPerspective: 'CUSTOMER',
    paperSource: null,
    jurisdiction: null,
    effectiveFrom: null,
    effectiveTo: null,
  },
  sourceType: 'PUBLISHED_PLAYBOOK_RULE',
  sourceArtifactId: 'a-playbook',
  sourceCandidateId: 'k-rule',
  sourceContractVersionId: null,
  sourceClauseId: null,
  legalConcept: 'LIMITATION_OF_LIABILITY',
  text: 'Liability must be capped at fees paid in the preceding 12 months.',
  policyFacts: {
    positions: ['PREFERRED', 'FALLBACK'],
    requiresApproval: true,
    escalationTrigger: 'Uncapped liability or consequential damages',
  },
  contractFacts: [],
  retrievalReason: 'STRUCTURED',
  laneInfo: { lexicalRank: null, vectorRank: null, fusedRank: null, fusedScore: null },
  semanticAssessment: null,
  policyCompatibility: 'NOT_ASSESSED',
  provenanceNodeIds: [],
  publicationId: 'pub-2',
  publicationNumber: 2,
}

const precedentItem = {
  evidenceId: 'e-precedent',
  authorityClass: 'PRECEDENT',
  sourceType: 'PUBLISHED_PRECEDENT',
  sourceArtifactId: 'a-precedent',
  sourceCandidateId: 'k-precedent',
  sourceContractVersionId: null,
  sourceClauseId: null,
  legalConcept: 'LIMITATION_OF_LIABILITY',
  text: 'The aggregate liability of either party is limited to the fees paid.',
  policyFacts: null,
  contractFacts: [],
  retrievalReason: 'FUSION',
  laneInfo: { lexicalRank: 2, vectorRank: 1, fusedRank: 1, fusedScore: 0.0328 },
  semanticAssessment: {
    judgments: { sameLegalIssue: true, sameClauseFunction: true, comparableContext: false },
    decisionSpecId: 'm6-precedent-relevance-1',
    decisionSpecVersion: 1,
  },
  policyCompatibility: 'COMPATIBLE',
  provenanceNodeIds: [],
  publicationId: 'pub-2',
  publicationNumber: 2,
}

const contractItem = {
  evidenceId: 'e-contract',
  authorityClass: 'CURRENT_CONTRACT',
  sourceType: 'CURRENT_CONTRACT_CLAUSE',
  sourceArtifactId: null,
  sourceCandidateId: null,
  sourceContractVersionId: 'v-1',
  sourceClauseId: 'cl-6',
  legalConcept: 'LIMITATION_OF_LIABILITY',
  text: '6. Limitation of Liability — aggregate liability shall not exceed…',
  policyFacts: null,
  contractFacts: [
    {
      factSchema: 'liability-facts',
      schemaVersion: 1,
      values: { liabilityCapMultiple: '3', capBasis: 'PRECEDING_12_MONTH_FEES' },
    },
  ],
  retrievalReason: 'CANONICAL_STRUCTURE',
  laneInfo: { lexicalRank: null, vectorRank: null, fusedRank: null, fusedScore: null },
  semanticAssessment: null,
  policyCompatibility: null,
  provenanceNodeIds: ['node-6', 'node-7'],
  publicationId: null,
  publicationNumber: null,
}

const hybridBundle = {
  bundleId: 'b-1',
  request: {
    purpose: 'CLAUSE_REVIEW',
    legalConcept: 'LIMITATION_OF_LIABILITY',
    contractId: 'c-1',
    versionNumber: 1,
    clauseId: 'cl-6',
    effectiveOn: '2026-09-24',
    knowledgePinPublicationNumber: null,
  },
  items: [policyItem, precedentItem, contractItem],
  sufficiency: 'PARTIAL',
  traceId: 't-1',
  degradationWarnings: ['INDEX_INCOMPLETE'],
}

const trace = {
  id: 't-1',
  purpose: 'CLAUSE_REVIEW',
  request: {
    legalConcept: 'LIMITATION_OF_LIABILITY',
    effectiveOn: '2026-09-24',
    reviewProfile: { contractType: 'MSA', ourSideRole: 'CUSTOMER' },
    clauseText: '6. Limitation of Liability…',
  },
  stages: {
    structured: {
      stage: 'structured',
      candidateCount: 1,
      candidateIds: ['k-rule'],
      candidateRanks: [],
      executionIds: [],
    },
    fusion: {
      stage: 'fusion',
      candidateCount: 2,
      candidateIds: ['k-precedent', 'k-other'],
      candidateRanks: [1, 2],
      executionIds: [],
    },
    rerank: {
      stage: 'rerank',
      candidateCount: 1,
      candidateIds: ['k-precedent'],
      candidateRanks: [],
      executionIds: ['exec-1'],
    },
  },
  selectedEvidenceIds: ['e-policy', 'e-precedent', 'e-contract'],
  rejectedEvidence: [
    { candidateId: 'k-other', stage: 'rerank', reason: 'different legal issue' },
  ],
  degradation: ['INDEX_INCOMPLETE'],
  stageTimings: { structured: 12, precedent: 240, totalMs: 300 },
  modelVersions: {
    'spec:m6-precedent-relevance-1': '1',
    embedding: 'openai-compatible:embeddinggemma:768',
  },
  knowledgePinPublicationNumber: 2,
}

describe('parseEvidenceBundle', () => {
  it('parses a hybrid bundle with lanes, facts, ranks and provenance', () => {
    const bundle = parseEvidenceBundle(hybridBundle)

    expect(bundle.bundleId).toBe('b-1')
    expect(bundle.sufficiency).toBe('PARTIAL')
    expect(bundle.traceId).toBe('t-1')
    expect(bundle.degradationWarnings).toEqual(['INDEX_INCOMPLETE'])
    expect(bundle.request).toEqual({
      purpose: 'CLAUSE_REVIEW',
      legalConcept: 'LIMITATION_OF_LIABILITY',
      contractId: 'c-1',
      versionNumber: 1,
      clauseId: 'cl-6',
      effectiveOn: '2026-09-24',
      knowledgePinPublicationNumber: null,
    })

    const policy = bundle.items[0]
    expect(policy.authorityClass).toBe('POLICY')
    expect(policy.sourceType).toBe('PUBLISHED_PLAYBOOK_RULE')
    expect(policy.policyFacts).toEqual({
      positions: ['PREFERRED', 'FALLBACK'],
      requiresApproval: true,
      escalationTrigger: 'Uncapped liability or consequential damages',
    })
    expect(policy.publicationNumber).toBe(2)
    expect(policy.laneInfo.fusedRank).toBeNull()

    const precedent = bundle.items[1]
    expect(precedent.laneInfo).toEqual({
      lexicalRank: 2,
      vectorRank: 1,
      fusedRank: 1,
      fusedScore: 0.0328,
    })
    expect(precedent.semanticAssessment?.judgments.sameLegalIssue).toBe(true)
    expect(precedent.policyCompatibility).toBe('COMPATIBLE')

    expect(policy.applicabilityScope).toEqual({
      contractPackKey: 'MSA_PACK',
      declaredContractTypes: ['MSA'],
      partyPerspective: 'CUSTOMER',
      paperSource: null,
      jurisdiction: null,
      effectiveFrom: null,
      effectiveTo: null,
    })

    const contract = bundle.items[2]
    expect(contract.applicabilityScope).toBeNull()
    expect(contract.sourceClauseId).toBe('cl-6')
    expect(contract.contractFacts[0].values.liabilityCapMultiple).toBe('3')
    expect(contract.provenanceNodeIds).toEqual(['node-6', 'node-7'])
    expect(contract.publicationId).toBeNull()
  })

  it('tolerates unknown enum-like values without failing the page', () => {
    const bundle = parseEvidenceBundle({
      ...hybridBundle,
      sufficiency: 'FUTURE_STATE',
      items: [
        {
          ...policyItem,
          authorityClass: 'FUTURE_LANE',
          sourceType: 'FUTURE_SOURCE',
          retrievalReason: 'FUTURE_REASON',
          policyCompatibility: 'MAYBE',
        },
      ],
    })

    expect(bundle.sufficiency).toBe('UNKNOWN')
    expect(bundle.items[0].authorityClass).toBe('UNKNOWN')
    expect(bundle.items[0].policyCompatibility).toBe('UNKNOWN')
    expect(bundle.items[0].sourceType).toBe('FUTURE_SOURCE')
    expect(bundle.items[0].retrievalReason).toBe('FUTURE_REASON')
  })

  it('rejects malformed bundles', () => {
    expect(() => parseEvidenceBundle({ ...hybridBundle, bundleId: undefined })).toThrow(
      /Malformed/,
    )
    expect(() => parseEvidenceBundle({ ...hybridBundle, items: 'none' })).toThrow(
      /Malformed/,
    )
    expect(() =>
      parseEvidenceBundle({ ...hybridBundle, items: [{ ...policyItem, evidenceId: null }] }),
    ).toThrow(/Malformed/)
  })
})

describe('parseRetrievalTrace', () => {
  it('parses stages, rejections, degradation and versions', () => {
    const parsed = parseRetrievalTrace(trace)

    expect(parsed.id).toBe('t-1')
    expect(parsed.knowledgePinPublicationNumber).toBe(2)
    expect(parsed.stages.fusion.candidateIds).toEqual(['k-precedent', 'k-other'])
    expect(parsed.stages.fusion.candidateRanks).toEqual([1, 2])
    expect(parsed.stages.rerank.executionIds).toEqual(['exec-1'])
    expect(parsed.rejectedEvidence).toEqual([
      { candidateId: 'k-other', stage: 'rerank', reason: 'different legal issue' },
    ])
    expect(parsed.stageTimings.totalMs).toBe(300)
    expect(parsed.modelVersions.embedding).toBe('openai-compatible:embeddinggemma:768')
    expect(parsed.request).toEqual(trace.request)
  })
})

describe('buildEvidence', () => {
  it('posts the minimal typed request and parses the bundle', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(hybridBundle)))

    const bundle = await buildEvidence({
      contractId: 'c-1',
      versionNumber: 1,
      clauseId: 'cl-6',
    })

    expect(requests).toHaveLength(1)
    expect(requests[0].method).toBe('POST')
    expect(requests[0].url).toContain('/api/retrieval/evidence')
    expect(await requests[0].json()).toEqual({
      contractId: 'c-1',
      versionNumber: 1,
      clauseId: 'cl-6',
    })
    expect(bundle.items).toHaveLength(3)
  })

  it('maps 409, 404 and 403 to typed errors', async () => {
    stubFetch(() =>
      jsonResponse(
        { code: '409', title: 'Invalid state', detail: 'Enrichment is not ready' },
        409,
      ),
    )
    const conflict = await buildEvidence({
      contractId: 'c-1',
      versionNumber: 1,
      clauseId: 'cl-6',
    }).catch((error: unknown) => error)
    expect(isConflictError(conflict)).toBe(true)
    expect(isNotFoundError(conflict)).toBe(false)

    stubFetch(() => jsonResponse({ code: '404', title: 'Not found' }, 404))
    const notFound = await buildEvidence({
      contractId: 'c-1',
      versionNumber: 1,
      clauseId: 'cl-6',
    }).catch((error: unknown) => error)
    expect(isNotFoundError(notFound)).toBe(true)

    stubFetch(() => jsonResponse({ code: '403', title: 'Forbidden' }, 403))
    const forbidden = await buildEvidence({
      contractId: 'c-1',
      versionNumber: 1,
      clauseId: 'cl-6',
    }).catch((error: unknown) => error)
    expect(isForbiddenError(forbidden)).toBe(true)
  })

  it('flags malformed 200 responses as contract mismatches', async () => {
    stubFetch(() => jsonResponse(envelope({ nonsense: true })))

    const error = await buildEvidence({
      contractId: 'c-1',
      versionNumber: 1,
      clauseId: 'cl-6',
    }).catch((caught: unknown) => caught)

    expect(isMalformedError(error)).toBe(true)
    expect(isConflictError(error)).toBe(false)
  })
})

describe('fetchRetrievalTrace', () => {
  it('fetches one trace by id', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(trace)))

    const parsed = await fetchRetrievalTrace('t-1')

    expect(requests[0].method).toBe('GET')
    expect(requests[0].url).toContain('/api/retrieval/traces/t-1')
    expect(parsed.id).toBe('t-1')
  })
})

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { queryClient, wrapper }
}

describe('bundle cache behaviour', () => {
  it('reads bundles from the cache without ever fetching', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(hybridBundle)))
    const { queryClient, wrapper } = createWrapper()

    const { result } = renderHook(() => useEvidenceBundle('c-1', 1, 'cl-6'), { wrapper })
    const withoutSelection = renderHook(() => useEvidenceBundle('c-1', 1, null), { wrapper })

    expect(result.current.data).toBeUndefined()
    expect(withoutSelection.result.current.data).toBeUndefined()
    expect(requests).toHaveLength(0)

    act(() => {
      queryClient.setQueryData(
        retrievalKeys.bundle('c-1', 1, 'cl-6'),
        parseEvidenceBundle(hybridBundle),
      )
    })

    await waitFor(() => expect(result.current.data?.bundleId).toBe('b-1'))
    expect(withoutSelection.result.current.data).toBeUndefined()
    expect(requests).toHaveLength(0)
  })

  it('keeps only the newest build when requests overlap', async () => {
    let call = 0
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) => {
      const index = call
      call += 1
      await new Promise((resolve) => setTimeout(resolve, index === 0 ? 60 : 0))
      return jsonResponse(
        envelope({ ...hybridBundle, bundleId: index === 0 ? 'b-first' : 'b-second' }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const { queryClient, wrapper } = createWrapper()
    const build = renderHook(() => useBuildEvidence('c-1', 1), { wrapper })

    await act(async () => {
      const mutate = build.result.current.mutateAsync
      await Promise.all([
        mutate({ clauseId: 'cl-6' }),
        mutate({ clauseId: 'cl-6' }),
      ])
    })

    expect(
      queryClient.getQueryData(retrievalKeys.bundle('c-1', 1, 'cl-6')),
    ).toMatchObject({ bundleId: 'b-second' })
  })

  it('writes a built bundle under the requested clause id and reads it back', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(hybridBundle)))
    const { queryClient, wrapper } = createWrapper()

    const read = renderHook(() => useEvidenceBundle('c-1', 1, 'cl-6'), { wrapper })
    const build = renderHook(() => useBuildEvidence('c-1', 1), { wrapper })

    await act(async () => {
      await build.result.current.mutateAsync({ clauseId: 'cl-6' })
    })

    expect(requests).toHaveLength(1)
    expect(
      queryClient.getQueryData(retrievalKeys.bundle('c-1', 1, 'cl-6')),
    ).toMatchObject({ bundleId: 'b-1' })
    await waitFor(() => expect(read.result.current.data?.bundleId).toBe('b-1'))
  })
})

describe('trace query behaviour', () => {
  it('fetches only when enabled and never without an id', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(trace)))
    const { wrapper } = createWrapper()

    const disabled = renderHook(() => useRetrievalTraceQuery('t-1', false), { wrapper })
    const missing = renderHook(() => useRetrievalTraceQuery(null, true), { wrapper })

    expect(requests).toHaveLength(0)
    expect(disabled.result.current.fetchStatus).toBe('idle')
    expect(disabled.result.current.data).toBeUndefined()
    expect(missing.result.current.data).toBeUndefined()

    const enabled = renderHook(() => useRetrievalTraceQuery('t-1', true), { wrapper })

    await waitFor(() => expect(enabled.result.current.data?.id).toBe('t-1'))
    expect(requests).toHaveLength(1)
    expect(requests[0].url).toContain('/api/retrieval/traces/t-1')
    expect(missing.result.current.data).toBeUndefined()
  })
})

describe('retrievalKeys', () => {
  it('distinguishes bundles by contract, version and clause and traces by id', () => {
    expect(retrievalKeys.bundle('c-1', 1, 'cl-6')).not.toEqual(
      retrievalKeys.bundle('c-1', 2, 'cl-6'),
    )
    expect(retrievalKeys.bundle('c-1', 1, 'cl-6')).not.toEqual(
      retrievalKeys.bundle('c-1', 1, 'cl-7'),
    )
    expect(retrievalKeys.trace('t-1')).not.toEqual(retrievalKeys.trace('t-2'))
  })
})
