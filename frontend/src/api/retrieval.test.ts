import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  buildEvidence,
  fetchRetrievalTrace,
  isEnrichmentNotReadyError,
  isForbiddenError,
  isNotFoundError,
  parseEvidenceBundle,
  parseRetrievalTrace,
  retrievalKeys,
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
  policyCompatibility: null,
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
  sufficiency: 'SUFFICIENT',
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
    subjectClauseText: '6. Limitation of Liability…',
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
  stageTimings: { structured: 12, precedent: 240, total: 300 },
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
    expect(bundle.sufficiency).toBe('SUFFICIENT')
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

    const contract = bundle.items[2]
    expect(contract.sourceClauseId).toBe('cl-6')
    expect(contract.contractFacts[0].values.liabilityCapMultiple).toBe('3')
    expect(contract.provenanceNodeIds).toEqual(['node-6', 'node-7'])
    expect(contract.publicationId).toBeNull()
  })

  it('preserves unknown enum-like values instead of failing the page', () => {
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
    expect(bundle.items[0].sourceType).toBe('FUTURE_SOURCE')
    expect(bundle.items[0].retrievalReason).toBe('FUTURE_REASON')
    expect(bundle.items[0].policyCompatibility).toBe('UNKNOWN')
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
    expect(parsed.stageTimings.total).toBe(300)
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
    expect(isEnrichmentNotReadyError(conflict)).toBe(true)
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
