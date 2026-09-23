import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  fetchSemantics,
  parseSemanticContract,
  semanticsKeys,
} from './semantics'

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
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(String(input))
    requests.push(request)
    return Promise.resolve(handler(request))
  })
  vi.stubGlobal('fetch', fetchMock)
  return requests
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const readyContract = {
  contractId: 'c1',
  versionNumber: 1,
  enrichmentStatus: 'READY',
  pipelineVersion: 'rivell-contract-enrichment-1',
  reviewProfile: {
    contractType: 'MSA',
    ourSideRole: 'CUSTOMER',
    paperSource: 'COUNTERPARTY_PAPER',
    jurisdiction: 'UK',
  },
  metadata: [
    {
      field: 'AGREEMENT_TITLE',
      value: 'Master Services Agreement',
      correctedValue: null,
      corrected: false,
      sourceNodeCount: 1,
    },
  ],
  parties: [
    {
      id: 'p1',
      name: 'Northwind Customer Group LLC',
      aliases: [],
      machineRoles: ['CUSTOMER'],
      effectiveRoles: ['CUSTOMER', 'PARTY_A'],
      corrected: true,
      sourceNodeCount: 1,
    },
  ],
  definedTerms: [
    {
      id: 't1',
      term: 'Services',
      definition: 'the managed cloud services described in Schedule A.',
      referenceCount: 4,
      duplicate: false,
    },
  ],
  references: [
    {
      id: 'r1',
      referenceText: 'Section 6.3',
      kind: 'SECTION',
      status: 'RESOLVED',
      targetNodeId: 'node-63',
      candidateCount: 1,
    },
    {
      id: 'r2',
      referenceText: 'Section 99',
      kind: 'SECTION',
      status: 'BROKEN',
      targetNodeId: null,
      candidateCount: 0,
    },
  ],
  clauses: [
    {
      id: 'cl1',
      headingNodeId: 'node-6',
      heading: '6. Limitation of Liability',
      contentText: '6. Limitation of Liability\n6.1 Subject to Section 6.3…',
      primaryConcept: 'LIMITATION_OF_LIABILITY',
      secondaryConcepts: [],
      correctedPrimaryConcept: null,
      machineFacts: [
        {
          factSchema: 'liability-facts',
          schemaVersion: 1,
          values: { liabilityCapMultiple: '3', capBasis: 'PRECEDING_12_MONTH_FEES' },
        },
      ],
      effectiveFacts: [
        {
          factSchema: 'liability-facts',
          schemaVersion: 1,
          values: { liabilityCapMultiple: '3', capBasis: 'PRECEDING_12_MONTH_FEES' },
        },
      ],
      interpretations: [
        {
          field: 'liabilityCapMultiple',
          kind: 'DETERMINISTIC',
          proposedValue: '3',
          specId: 'contract-facts-liability',
          sourceNodeCount: 1,
          verification: 'VERIFIED',
        },
      ],
      corrected: false,
    },
  ],
  relationships: [
    {
      id: 'rel1',
      sourceRegionNodeId: 'node-5',
      targetRegionNodeId: 'node-6',
      type: 'DEPENDS_ON',
      detector: 'SEMANTIC',
      basis: 'narrowed pair analysis',
      probability: 0.91,
      verification: 'NOT_VERIFIED',
    },
  ],
  quality: ['UNCLASSIFIED_REGIONS', 'PROVIDER_DEGRADATION'],
  observations: {
    regionCount: 15,
    decisionExecutionCount: 21,
    questionCount: 97,
    generativeExtractionCount: 0,
    deterministicFactCount: 2,
    providerDegraded: true,
    budgetExhausted: false,
  },
}

describe('parseSemanticContract', () => {
  it('parses a ready contract with all surfaces', () => {
    const parsed = parseSemanticContract(readyContract)

    expect(parsed.enrichmentStatus).toBe('READY')
    expect(parsed.reviewProfile).toEqual({
      contractType: 'MSA',
      ourSideRole: 'CUSTOMER',
      paperSource: 'COUNTERPARTY_PAPER',
      jurisdiction: 'UK',
    })
    expect(parsed.metadata[0]).toMatchObject({
      field: 'AGREEMENT_TITLE',
      value: 'Master Services Agreement',
      correctedValue: null,
      corrected: false,
      sourceNodeCount: 1,
    })
    expect(parsed.parties[0]).toMatchObject({
      machineRoles: ['CUSTOMER'],
      effectiveRoles: ['CUSTOMER', 'PARTY_A'],
      corrected: true,
    })
    expect(parsed.definedTerms[0]).toMatchObject({
      term: 'Services',
      referenceCount: 4,
      duplicate: false,
    })
    expect(parsed.references[1]).toMatchObject({
      status: 'BROKEN',
      targetNodeId: null,
      candidateCount: 0,
    })
    expect(parsed.clauses[0].machineFacts[0].values).toEqual({
      liabilityCapMultiple: '3',
      capBasis: 'PRECEDING_12_MONTH_FEES',
    })
    expect(parsed.clauses[0].interpretations[0].verification).toBe('VERIFIED')
    expect(parsed.relationships[0]).toMatchObject({
      type: 'DEPENDS_ON',
      detector: 'SEMANTIC',
      probability: 0.91,
    })
    expect(parsed.quality).toEqual(['UNCLASSIFIED_REGIONS', 'PROVIDER_DEGRADATION'])
    expect(parsed.observations?.providerDegraded).toBe(true)
  })

  it('parses a failed contract with empty collections and no observations', () => {
    const parsed = parseSemanticContract({
      contractId: 'c1',
      versionNumber: 1,
      enrichmentStatus: 'FAILED',
      pipelineVersion: 'rivell-contract-enrichment-1',
      reviewProfile: {
        contractType: 'NDA',
        ourSideRole: null,
        paperSource: null,
        jurisdiction: null,
      },
      metadata: [],
      parties: [],
      definedTerms: [],
      references: [],
      clauses: [],
      relationships: [],
      quality: ['PROVIDER_DEGRADATION'],
      observations: null,
    })

    expect(parsed.enrichmentStatus).toBe('FAILED')
    expect(parsed.observations).toBeNull()
    expect(parsed.clauses).toEqual([])
  })

  it('keeps unknown enum-like codes instead of failing the page', () => {
    const parsed = parseSemanticContract({
      ...readyContract,
      enrichmentStatus: 'PARTIALLY_READY',
      quality: ['FUTURE_REASON_CODE'],
      clauses: [
        {
          ...readyContract.clauses[0],
          primaryConcept: 'DATA_PROTECTION',
          machineFacts: [
            {
              factSchema: 'privacy-facts',
              schemaVersion: 2,
              values: { retentionDays: '30' },
            },
          ],
          interpretations: [
            { ...readyContract.clauses[0].interpretations[0], verification: 'REVALIDATED' },
          ],
        },
      ],
    })

    expect(parsed.enrichmentStatus).toBe('UNKNOWN')
    expect(parsed.quality).toEqual(['FUTURE_REASON_CODE'])
    expect(parsed.clauses[0].primaryConcept).toBe('DATA_PROTECTION')
    expect(parsed.clauses[0].machineFacts[0].factSchema).toBe('privacy-facts')
    expect(parsed.clauses[0].interpretations[0].verification).toBe('REVALIDATED')
  })

  it('rejects malformed payloads', () => {
    expect(() => parseSemanticContract({ contractId: 'c1' })).toThrow(
      /malformed/i,
    )
    expect(() =>
      parseSemanticContract({ ...readyContract, parties: [{ id: 'p1' }] }),
    ).toThrow(/malformed/i)
    expect(() =>
      parseSemanticContract({ ...readyContract, enrichmentStatus: 7 }),
    ).toThrow(/malformed/i)
  })
})

describe('fetchSemantics', () => {
  it('returns null when the version has no semantics yet', async () => {
    stubFetch(() => jsonResponse({ code: '404', title: 'Not found', detail: 'x' }, 404))

    await expect(fetchSemantics('c1', 1)).resolves.toBeNull()
  })

  it('requests the version-scoped semantics endpoint', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(readyContract)))

    const result = await fetchSemantics('c1', 2)

    expect(requests[0].url).toContain('/api/contracts/c1/versions/2/semantics')
    expect(result?.enrichmentStatus).toBe('READY')
  })

  it('throws the server message on other failures', async () => {
    stubFetch(
      () =>
        new Response(
          JSON.stringify({ code: '409', title: 'Invalid state', detail: 'Enrichment already running' }),
          { status: 409, headers: { 'Content-Type': 'application/json' } },
        ),
    )

    await expect(fetchSemantics('c1', 1)).rejects.toThrow(/already running/i)
  })
})

describe('semanticsKeys', () => {
  it('scopes cache entries by contract and version', () => {
    expect(semanticsKeys.semantics('c1', 1)).toEqual([
      'contract',
      'c1',
      'semantics',
      1,
    ])
    expect(semanticsKeys.semantics('c1', 2)).not.toEqual(
      semanticsKeys.semantics('c1', 1),
    )
  })
})
