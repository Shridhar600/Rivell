import { vi } from 'vitest'
import type { SemanticContract } from '../../api/semantics'
import type { StructureNode } from '../../lib/documentTree'

export type RecordedRequest = {
  method: string
  url: string
  body: unknown
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-24T05:00:00Z' }
}

function span(bodyIndex: number, partial: Record<string, unknown> = {}) {
  return {
    partPath: 'word/document.xml',
    bodyIndex,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: null,
    cellIndex: null,
    runStart: null,
    runEnd: null,
    ...partial,
  }
}

export function fixtureNode(
  partial: Partial<StructureNode> & { id: string },
): StructureNode {
  return {
    parentId: null,
    nodeType: 'PARAGRAPH',
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    rawStyleId: null,
    sourceSpan: span(0),
    ...partial,
  } as StructureNode
}

export const fixtureNodes: StructureNode[] = [
  fixtureNode({ id: 'body', nodeType: 'BODY' }),
  fixtureNode({
    id: 'title',
    parentId: 'body',
    nodeType: 'TITLE',
    text: 'Master Services Agreement',
    sourceSpan: span(0),
  }),
  fixtureNode({
    id: 'node-1',
    parentId: 'body',
    nodeType: 'HEADING',
    headingLevel: 2,
    text: '1. Definitions',
    sourceSpan: span(1, { paragraphIndex: 1 }),
  }),
  fixtureNode({
    id: 'node-5',
    parentId: 'body',
    nodeType: 'HEADING',
    headingLevel: 2,
    text: '5. Termination',
    sourceSpan: span(2, { paragraphIndex: 2 }),
  }),
  fixtureNode({
    id: 'node-6',
    parentId: 'body',
    nodeType: 'HEADING',
    headingLevel: 2,
    text: '6. Limitation of Liability',
    sourceSpan: span(3, { paragraphIndex: 3 }),
  }),
  fixtureNode({
    id: 'node-63',
    parentId: 'body',
    text: '6.3 The exclusions in Section 6.2 do not apply to breaches of Section 8.',
    sourceSpan: span(4, { paragraphIndex: 4 }),
  }),
  fixtureNode({
    id: 'node-9',
    parentId: 'body',
    nodeType: 'HEADING',
    headingLevel: 2,
    text: 'Schedule A — Services',
    sourceSpan: span(5, { paragraphIndex: 5 }),
  }),
]

export function fixtureSemantics(
  overrides: Partial<SemanticContract> = {},
): SemanticContract {
  return {
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
      {
        field: 'GOVERNING_LAW',
        value: 'UK',
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
      {
        id: 'p2',
        name: 'Pallas Cloud Services GmbH',
        aliases: ['Supplier'],
        machineRoles: ['SUPPLIER'],
        effectiveRoles: ['SUPPLIER'],
        corrected: false,
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
      {
        id: 't2',
        term: 'Fees',
        definition: 'the fees set out in Schedule A.',
        referenceCount: 3,
        duplicate: true,
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
        headingNodeId: 'node-1',
        heading: '1. Definitions',
        contentText: '1. Definitions\n"Services" means…',
        primaryConcept: null,
        secondaryConcepts: [],
        correctedPrimaryConcept: null,
        machineFacts: [],
        effectiveFacts: [],
        interpretations: [],
        corrected: false,
      },
      {
        id: 'cl5',
        headingNodeId: 'node-5',
        heading: '5. Termination',
        contentText: '5. Termination\nEither party may terminate on 60 days notice.',
        primaryConcept: 'TERM_TERMINATION',
        secondaryConcepts: [],
        correctedPrimaryConcept: null,
        machineFacts: [
          {
            factSchema: 'termination-facts',
            schemaVersion: 1,
            values: { noticePeriodDays: '60', availableTo: 'ANY_PARTY' },
          },
        ],
        effectiveFacts: [
          {
            factSchema: 'termination-facts',
            schemaVersion: 1,
            values: { noticePeriodDays: '60', availableTo: 'ANY_PARTY' },
          },
        ],
        interpretations: [
          {
            field: 'noticePeriodDays',
            kind: 'DETERMINISTIC',
            proposedValue: '60',
            specId: 'contract-termination-facts',
            sourceNodeCount: 1,
            verification: 'VERIFIED',
          },
        ],
        corrected: false,
      },
      {
        id: 'cl6',
        headingNodeId: 'node-6',
        heading: '6. Limitation of Liability',
        contentText:
          '6. Limitation of Liability\n6.1 …shall not exceed three (3) times the Fees…',
        primaryConcept: 'LIMITATION_OF_LIABILITY',
        secondaryConcepts: ['INDEMNIFICATION'],
        correctedPrimaryConcept: null,
        machineFacts: [
          {
            factSchema: 'liability-facts',
            schemaVersion: 1,
            values: {
              liabilityCapMultiple: '3',
              capBasis: 'PRECEDING_12_MONTH_FEES',
              consequentialDamagesExcluded: 'true',
              dataBreachCarveout: 'true',
            },
          },
        ],
        effectiveFacts: [
          {
            factSchema: 'liability-facts',
            schemaVersion: 1,
            values: {
              liabilityCapMultiple: '3',
              capBasis: 'PRECEDING_12_MONTH_FEES',
              consequentialDamagesExcluded: 'true',
              dataBreachCarveout: 'true',
            },
          },
        ],
        interpretations: [
          {
            field: 'liabilityCapMultiple',
            kind: 'DETERMINISTIC',
            proposedValue: '3',
            specId: 'contract-liability-facts',
            sourceNodeCount: 1,
            verification: 'VERIFIED',
          },
          {
            field: 'capBasis',
            kind: 'DETERMINISTIC',
            proposedValue: 'PRECEDING_12_MONTH_FEES',
            specId: 'contract-liability-facts',
            sourceNodeCount: 1,
            verification: 'VERIFIED',
          },
          {
            field: 'consequentialDamagesExcluded',
            kind: 'BOUNDED_CLASSIFICATION',
            proposedValue: 'true',
            specId: 'contract-liability-facts',
            sourceNodeCount: 1,
            verification: 'UNCERTAIN',
          },
          {
            field: 'dataBreachCarveout',
            kind: 'BOUNDED_CLASSIFICATION',
            proposedValue: 'true',
            specId: 'contract-liability-facts',
            sourceNodeCount: 1,
            verification: 'DISPUTED',
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
      {
        id: 'rel2',
        sourceRegionNodeId: 'node-6',
        targetRegionNodeId: 'node-9',
        type: 'CARVES_OUT',
        detector: 'DETERMINISTIC',
        basis: 'explicit cross-reference',
        probability: 0.95,
        verification: 'VERIFIED',
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
    ...overrides,
  }
}

export const fixtureContractSummary = {
  id: 'c1',
  title: 'Master Services Agreement',
  latestVersion: {
    versionNumber: 1,
    parseStatus: 'OK',
    parseWarnings: [],
    parserVersion: 'rivell-docx-parser-2',
    sha256: 'abc123def456',
    filename: 'msa.docx',
    nodeCount: fixtureNodes.length,
  },
}

export const fixtureStructure = {
  contractId: 'c1',
  versionNumber: 1,
  parserVersion: 'rivell-docx-parser-2',
  parseStatus: 'OK',
  parseWarnings: [],
  nodeCount: fixtureNodes.length,
  nodes: fixtureNodes,
}

export type StubOptions = {
  semantics?: SemanticContract | null
  semanticsStatus?: number
  enrichmentResponse?: () => Response
  partyCorrectionResponse?: (body: unknown) => Response
  conceptCorrectionResponse?: (body: unknown) => Response
  factCorrectionResponse?: (body: unknown) => Response
}

export function stubContractApi(options: StubOptions = {}) {
  const requests: RecordedRequest[] = []
  let semantics = options.semantics === undefined ? fixtureSemantics() : options.semantics

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init)
    const url = request.url
    const method = request.method
    let body: unknown = null
    if (method !== 'GET' && method !== 'HEAD') {
      body = await request.clone().json().catch(() => null)
    }
    requests.push({ method, url, body })

    if (method === 'GET' && /\/api\/contracts\/c1$/.test(url)) {
      return jsonResponse(envelope(fixtureContractSummary))
    }
    if (method === 'GET' && url.includes('/versions/1/structure')) {
      return jsonResponse(envelope(fixtureStructure))
    }
    if (method === 'GET' && url.includes('/versions/1/semantics')) {
      if (options.semanticsStatus && options.semanticsStatus !== 200) {
        return jsonResponse(
          { code: String(options.semanticsStatus), title: 'Error', detail: 'x' },
          options.semanticsStatus,
        )
      }
      if (semantics === null) {
        return jsonResponse({ code: '404', title: 'Not found', detail: 'x' }, 404)
      }
      return jsonResponse(envelope(semantics))
    }
    if (method === 'POST' && url.includes('/enrichment')) {
      if (options.enrichmentResponse) return options.enrichmentResponse()
      semantics = fixtureSemantics()
      return jsonResponse(envelope(semantics))
    }
    if (method === 'POST' && url.includes('/semantics/parties/')) {
      if (options.partyCorrectionResponse) {
        return options.partyCorrectionResponse(body)
      }
      const roles = (body as { roles: string[] }).roles
      semantics = {
        ...(semantics as SemanticContract),
        parties: (semantics as SemanticContract).parties.map((party) =>
          party.id === 'p1'
            ? { ...party, effectiveRoles: roles, corrected: true }
            : party,
        ),
      }
      return jsonResponse(envelope(semantics))
    }
    if (method === 'POST' && url.includes('/semantics/clauses/')) {
      if (url.endsWith('/concepts')) {
        if (options.conceptCorrectionResponse) {
          return options.conceptCorrectionResponse(body)
        }
        const primary = (body as { primary: string }).primary
        semantics = {
          ...(semantics as SemanticContract),
          clauses: (semantics as SemanticContract).clauses.map((clause) =>
            clause.id === 'cl6'
              ? {
                  ...clause,
                  corrected: true,
                  correctedPrimaryConcept: primary,
                  primaryConcept: primary,
                }
              : clause,
          ),
        }
        return jsonResponse(envelope(semantics))
      }
      if (url.endsWith('/facts')) {
        if (options.factCorrectionResponse) {
          return options.factCorrectionResponse(body)
        }
        const values = (body as { values: Record<string, string> }).values
        semantics = {
          ...(semantics as SemanticContract),
          clauses: (semantics as SemanticContract).clauses.map((clause) =>
            clause.id === 'cl5'
              ? {
                  ...clause,
                  corrected: true,
                  effectiveFacts: clause.effectiveFacts.map((fact) => ({
                    ...fact,
                    values: { ...fact.values, ...values },
                  })),
                }
              : clause,
          ),
        }
        return jsonResponse(envelope(semantics))
      }
    }

    return jsonResponse({ message: 'not found' }, 404)
  })

  vi.stubGlobal('fetch', fetchMock)
  return {
    requests,
    getSemantics: () => semantics,
    setSemantics: (next: SemanticContract | null) => {
      semantics = next
    },
  }
}
