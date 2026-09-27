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
  fixtureNode({
    id: 'node-10',
    parentId: 'body',
    nodeType: 'HEADING',
    headingLevel: 2,
    text: 'Northwind Customer Group LLC',
    sourceSpan: span(6, { paragraphIndex: 6 }),
  }),
]

export function fixtureSemantics(
  overrides: Partial<SemanticContract> = {},
): SemanticContract {
  return {
    contractId: 'c1',
    versionNumber: 1,
    enrichmentStatus: 'READY',
    failureReason: null,
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
        sourceNodeIds: ['title'],
      },
      {
        field: 'GOVERNING_LAW',
        value: 'UK',
        correctedValue: null,
        corrected: false,
        sourceNodeIds: ['node-6'],
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
        sourceNodeIds: ['node-1'],
      },
      {
        id: 'p2',
        name: 'Pallas Cloud Services GmbH',
        aliases: ['Supplier'],
        machineRoles: ['SUPPLIER'],
        effectiveRoles: ['SUPPLIER'],
        corrected: false,
        sourceNodeIds: [],
      },
    ],
    definedTerms: [
      {
        id: 't1',
        term: 'Services',
        definition: 'the managed cloud services described in Schedule A.',
        definingNodeId: 'node-1',
        referenceNodeIds: ['node-5', 'node-6'],
        duplicate: false,
      },
      {
        id: 't2',
        term: 'Fees',
        definition: 'the fees set out in Schedule A.',
        definingNodeId: 'node-1',
        referenceNodeIds: [],
        duplicate: true,
      },
    ],
    references: [
      {
        id: 'r1',
        referenceText: 'Section 6.3',
        kind: 'SECTION',
        status: 'RESOLVED',
        sourceNodeId: 'node-5',
        targetNodeId: 'node-63',
        candidateNodeIds: ['node-63'],
      },
      {
        id: 'r2',
        referenceText: 'Section 99',
        kind: 'SECTION',
        status: 'BROKEN',
        sourceNodeId: 'node-6',
        targetNodeId: null,
        candidateNodeIds: [],
      },
      {
        id: 'r3',
        referenceText: 'Section 5',
        kind: 'SECTION',
        status: 'AMBIGUOUS',
        sourceNodeId: 'node-63',
        targetNodeId: null,
        candidateNodeIds: ['node-5', 'node-6'],
      },
    ],
    clauses: [
      {
        id: 'cl1',
        headingNodeId: 'node-1',
        regionKind: 'CLAUSE',
        heading: '1. Definitions',
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
        regionKind: 'CLAUSE',
        heading: '5. Termination',
        primaryConcept: 'TERM_TERMINATION',
        secondaryConcepts: [],
        correctedPrimaryConcept: null,
        machineFacts: [
          {
            factSchema: 'termination-facts',
            schemaVersion: 1,
            values: { noticePeriodDays: '60', availableTo: 'ANY_PARTY' },
            sourceNodeIds: ['node-5'],
          },
        ],
        effectiveFacts: [
          {
            factSchema: 'termination-facts',
            schemaVersion: 1,
            values: { noticePeriodDays: '60', availableTo: 'ANY_PARTY' },
            sourceNodeIds: ['node-5'],
          },
        ],
        interpretations: [
          {
            field: 'noticePeriodDays',
            kind: 'DETERMINISTIC',
            proposedValue: '60',
            specId: 'contract-termination-facts',
            sourceNodeIds: ['node-5'],
            verification: 'VERIFIED',
          },
        ],
        corrected: false,
      },
      {
        id: 'cl6',
        headingNodeId: 'node-6',
        regionKind: 'CLAUSE',
        heading: '6. Limitation of Liability',
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
            sourceNodeIds: ['node-6', 'node-63'],
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
            sourceNodeIds: ['node-6', 'node-63'],
          },
        ],
        interpretations: [
          {
            field: 'liabilityCapMultiple',
            kind: 'DETERMINISTIC',
            proposedValue: '3',
            specId: 'contract-liability-facts',
            sourceNodeIds: ['node-6'],
            verification: 'VERIFIED',
          },
          {
            field: 'capBasis',
            kind: 'DETERMINISTIC',
            proposedValue: 'PRECEDING_12_MONTH_FEES',
            specId: 'contract-liability-facts',
            sourceNodeIds: ['node-6'],
            verification: 'VERIFIED',
          },
          {
            field: 'consequentialDamagesExcluded',
            kind: 'BOUNDED_CLASSIFICATION',
            proposedValue: 'true',
            specId: 'contract-liability-facts',
            sourceNodeIds: ['node-6', 'node-63'],
            verification: 'UNCERTAIN',
          },
          {
            field: 'dataBreachCarveout',
            kind: 'BOUNDED_CLASSIFICATION',
            proposedValue: 'true',
            specId: 'contract-liability-facts',
            sourceNodeIds: ['node-63'],
            verification: 'DISPUTED',
          },
        ],
        corrected: false,
      },
    ],
    structuralRegions: [
      {
        id: 'sr9',
        headingNodeId: 'node-9',
        regionKind: 'SCHEDULE',
        heading: 'Schedule A — Services',
        primaryConcept: 'SERVICE_LEVEL',
        secondaryConcepts: [],
        correctedPrimaryConcept: null,
        machineFacts: [],
        effectiveFacts: [],
        interpretations: [],
        corrected: false,
      },
      {
        id: 'sr10',
        headingNodeId: 'node-10',
        regionKind: 'SIGNATURE_BLOCK',
        heading: 'Northwind Customer Group LLC',
        primaryConcept: null,
        secondaryConcepts: [],
        correctedPrimaryConcept: null,
        machineFacts: [],
        effectiveFacts: [],
        interpretations: [],
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
  contractSummary?: unknown
  semantics?: SemanticContract | null
  semanticsStatus?: number
  enrichmentResponse?: () => Response
  partyCorrectionResponse?: (body: unknown) => Response
  conceptCorrectionResponse?: (body: unknown) => Response
  factCorrectionResponse?: (body: unknown) => Response
  evidenceBundle?: unknown
  evidenceResponse?: (body: unknown) => Response | Promise<Response>
  evidenceStatus?: number
  trace?: unknown
  traceResponse?: () => Response | Promise<Response>
  traceStatus?: number
  packs?: unknown
  packsStatus?: number
  reviews?: unknown
  reviewsStatus?: number
  reviewSummary?: unknown
  reviewSummaryStatus?: number
  reviewProgress?: unknown
  reviewProgressStatus?: number
  findings?: unknown
  findingsStatus?: number
  findingsResponse?: (url: string) => Response | Promise<Response>
  findingDetail?: unknown
  findingDetailStatus?: number
  findingDetailResponse?: (findingId: string) => Response | Promise<Response>
  createReviewResponse?: (body: unknown) => Response | Promise<Response>
  createReviewSummary?: unknown
  createReviewStatus?: number
  decisions?: unknown
  decisionsStatus?: number
  decisionHistory?: unknown
  decisionHistoryStatus?: number
  humanReview?: unknown
  humanReviewStatus?: number
  changeSet?: unknown
  changeSetStatus?: number
  decisionResponse?: (body: unknown) => Response | Promise<Response>
  finalizeResponse?: () => Response | Promise<Response>
  redline?: unknown
  redlineStatus?: number
  redlineTriggerResponse?: () => Response | Promise<Response>
  redlineDownloadResponse?: () => Response | Promise<Response>
}

const defaultPackCatalog = {
  packs: [
    {
      key: 'MSA_PACK',
      displayName: 'MSA pack',
      summary: 'Liability, confidentiality and termination checks for master services agreements.',
      version: 'rivell-review-pack-1',
    },
    {
      key: 'NDA_PACK',
      displayName: 'NDA pack',
      summary: 'Confidentiality and term checks for non-disclosure agreements.',
      version: 'rivell-review-pack-1',
    },
  ],
}

export function stubContractApi(options: StubOptions = {}) {
  const requests: RecordedRequest[] = []
  let semantics = options.semantics === undefined ? fixtureSemantics() : options.semantics
  let semanticsStatus = options.semanticsStatus ?? 200
  let reviews = options.reviews ?? { items: [], truncated: false }
  let decisions: unknown = options.decisions ?? { items: [], truncated: false }
  let decisionHistory: unknown = options.decisionHistory ?? { items: [], truncated: false }
  let humanReview: unknown = options.humanReview ?? null
  let changeSet: unknown = options.changeSet ?? null
  let redline: unknown = options.redline ?? null
  let redlineStatus = options.redlineStatus ?? 200

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
      return jsonResponse(envelope(options.contractSummary ?? fixtureContractSummary))
    }
    if (method === 'GET' && url.includes('/versions/1/structure')) {
      return jsonResponse(envelope(fixtureStructure))
    }
    if (method === 'GET' && url.includes('/versions/1/semantics')) {
      if (semanticsStatus !== 200) {
        return jsonResponse(
          { code: String(semanticsStatus), title: 'Error', detail: 'x' },
          semanticsStatus,
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
      const clauseId = url.split('/semantics/clauses/')[1]?.split('/')[0]
      if (url.endsWith('/concepts')) {
        if (options.conceptCorrectionResponse) {
          return options.conceptCorrectionResponse(body)
        }
        const primary = (body as { primary: string }).primary
        semantics = {
          ...(semantics as SemanticContract),
          clauses: (semantics as SemanticContract).clauses.map((clause) =>
            clause.id === clauseId
              ? {
                  ...clause,
                  corrected: true,
                  correctedPrimaryConcept: primary,
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
            clause.id === clauseId
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

    if (method === 'POST' && url.includes('/api/retrieval/evidence')) {
      if (options.evidenceResponse) return await options.evidenceResponse(body)
      if (options.evidenceStatus && options.evidenceStatus !== 200) {
        return jsonResponse(
          {
            code: String(options.evidenceStatus),
            title: 'Error',
            detail: 'server detail text',
          },
          options.evidenceStatus,
        )
      }
      return jsonResponse(envelope(options.evidenceBundle ?? null))
    }
    if (method === 'GET' && url.includes('/api/retrieval/traces/')) {
      if (options.traceResponse) return await options.traceResponse()
      if (options.traceStatus && options.traceStatus !== 200) {
        return jsonResponse(
          { code: String(options.traceStatus), title: 'Error', detail: 'server detail text' },
          options.traceStatus,
        )
      }
      return jsonResponse(envelope(options.trace ?? null))
    }

    if (method === 'GET' && /\/api\/reviews\/packs$/.test(url)) {
      if (options.packsStatus && options.packsStatus !== 200) {
        return jsonResponse(
          { code: String(options.packsStatus), title: 'Error', detail: 'server detail text' },
          options.packsStatus,
        )
      }
      return jsonResponse(envelope(options.packs ?? defaultPackCatalog))
    }
    if (
      method === 'GET' &&
      /\/api\/reviews\/[^/?]+\/findings\/[^/?]+\/decisions(\?|$)/.test(url)
    ) {
      if (options.decisionHistoryStatus && options.decisionHistoryStatus !== 200) {
        return jsonResponse(
          {
            code: String(options.decisionHistoryStatus),
            title: 'Error',
            detail: 'server detail text',
          },
          options.decisionHistoryStatus,
        )
      }
      return jsonResponse(envelope(decisionHistory))
    }
    if (method === 'GET' && /\/api\/reviews\/[^/?]+\/decisions(\?|$)/.test(url)) {
      if (options.decisionsStatus && options.decisionsStatus !== 200) {
        return jsonResponse(
          {
            code: String(options.decisionsStatus),
            title: 'Error',
            detail: 'server detail text',
          },
          options.decisionsStatus,
        )
      }
      return jsonResponse(envelope(decisions))
    }
    if (method === 'GET' && /\/api\/reviews\/[^/?]+\/human-review$/.test(url)) {
      if (options.humanReviewStatus && options.humanReviewStatus !== 200) {
        return jsonResponse(
          {
            code: String(options.humanReviewStatus),
            title: 'Error',
            detail: 'server detail text',
          },
          options.humanReviewStatus,
        )
      }
      return jsonResponse(envelope(humanReview))
    }
    if (method === 'GET' && /\/api\/reviews\/[^/?]+\/approved-change-set$/.test(url)) {
      if (options.changeSetStatus && options.changeSetStatus !== 200) {
        return jsonResponse(
          {
            code: String(options.changeSetStatus),
            title: 'Error',
            detail: 'server detail text',
          },
          options.changeSetStatus,
        )
      }
      if (changeSet === null) {
        return jsonResponse(
          { code: '404', title: 'Not found', detail: 'server detail text' },
          404,
        )
      }
      return jsonResponse(envelope(changeSet))
    }
    if (method === 'GET' && /\/api\/reviews\/[^/?]+\/redline\/download$/.test(url)) {
      if (options.redlineDownloadResponse) return await options.redlineDownloadResponse()
      return new Response(new Uint8Array(4403), {
        status: 200,
        headers: {
          'Content-Type':
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          'Content-Disposition': "attachment; filename*=UTF-8''acme-msa-v1-redline.docx",
        },
      })
    }
    if (method === 'POST' && /\/api\/reviews\/[^/?]+\/redline$/.test(url)) {
      if (options.redlineTriggerResponse) return await options.redlineTriggerResponse()
      return jsonResponse(envelope(redline))
    }
    if (method === 'GET' && /\/api\/reviews\/[^/?]+\/redline$/.test(url)) {
      if (redlineStatus !== 200) {
        return jsonResponse(
          {
            code: String(redlineStatus),
            title: 'Error',
            detail: 'server detail text',
          },
          redlineStatus,
        )
      }
      if (redline === null) {
        return jsonResponse(
          { code: '404', title: 'Not found', detail: 'server detail text' },
          404,
        )
      }
      return jsonResponse(envelope(redline))
    }
    if (method === 'PUT' && /\/api\/reviews\/[^/?]+\/findings\/[^/?]+\/decision$/.test(url)) {
      if (options.decisionResponse) return await options.decisionResponse(body)
      const requestBody = (body ?? {}) as Record<string, unknown>
      const findingId = url.split('/findings/')[1]?.split('/')[0] ?? ''
      return jsonResponse(
        envelope({
          id: `d-${String(requestBody.expectedVersion ?? 0) + 1}`,
          findingId,
          revision: Number(requestBody.expectedVersion ?? 0) + 1,
          action: requestBody.action ?? 'ACCEPT',
          approvedAction: requestBody.approvedAction ?? null,
          approvedLanguage: requestBody.approvedLanguage ?? null,
          languageSource:
            requestBody.action === 'EDIT'
              ? 'HUMAN_EDIT'
              : requestBody.action === 'ACCEPT'
                ? 'APPROVED_DRAFTING'
                : null,
          approvedDraftingEvidenceId: null,
          comment: requestBody.comment ?? null,
          actorId: 'u-1',
          decidedAt: '2026-09-26T09:20:00Z',
          clientRequestId: requestBody.clientRequestId ?? null,
          supersedesDecisionId: null,
        }),
      )
    }
    if (method === 'POST' && /\/api\/reviews\/[^/?]+\/finalize$/.test(url)) {
      if (options.finalizeResponse) return await options.finalizeResponse()
      return jsonResponse(envelope(null))
    }
    if (method === 'GET' && /\/api\/reviews\?(.*)contractId=/.test(url)) {
      if (options.reviewsStatus && options.reviewsStatus !== 200) {
        return jsonResponse(
          { code: String(options.reviewsStatus), title: 'Error', detail: 'server detail text' },
          options.reviewsStatus,
        )
      }
      return jsonResponse(envelope(reviews))
    }
    if (method === 'GET' && /\/api\/reviews\/[^/?]+$/.test(url)) {
      if (options.reviewSummaryStatus && options.reviewSummaryStatus !== 200) {
        return jsonResponse(
          {
            code: String(options.reviewSummaryStatus),
            title: 'Error',
            detail: 'server detail text',
          },
          options.reviewSummaryStatus,
        )
      }
      return jsonResponse(envelope(options.reviewSummary ?? null))
    }
    if (method === 'GET' && url.includes('/progress')) {
      if (options.reviewProgressStatus && options.reviewProgressStatus !== 200) {
        return jsonResponse(
          {
            code: String(options.reviewProgressStatus),
            title: 'Error',
            detail: 'server detail text',
          },
          options.reviewProgressStatus,
        )
      }
      return jsonResponse(envelope(options.reviewProgress ?? null))
    }
    if (method === 'GET' && /\/api\/reviews\/[^/?]+\/findings\/[^/?]+$/.test(url)) {
      if (options.findingDetailResponse) {
        const findingId = url.split('/findings/')[1] ?? ''
        return await options.findingDetailResponse(findingId)
      }
      if (options.findingDetailStatus && options.findingDetailStatus !== 200) {
        return jsonResponse(
          {
            code: String(options.findingDetailStatus),
            title: 'Error',
            detail: 'server detail text',
          },
          options.findingDetailStatus,
        )
      }
      return jsonResponse(envelope(options.findingDetail ?? null))
    }
    if (method === 'GET' && /\/api\/reviews\/[^/?]+\/findings(\?|$)/.test(url)) {
      if (options.findingsResponse) return await options.findingsResponse(url)
      if (options.findingsStatus && options.findingsStatus !== 200) {
        return jsonResponse(
          { code: String(options.findingsStatus), title: 'Error', detail: 'server detail text' },
          options.findingsStatus,
        )
      }
      return jsonResponse(envelope(options.findings ?? { items: [], truncated: false }))
    }
    if (method === 'POST' && url.includes('/versions/1/reviews')) {
      if (options.createReviewResponse) return await options.createReviewResponse(body)
      if (options.createReviewStatus && options.createReviewStatus !== 200) {
        return jsonResponse(
          {
            code: String(options.createReviewStatus),
            title: 'Error',
            detail: 'server detail text',
          },
          options.createReviewStatus,
        )
      }
      return jsonResponse(envelope(options.createReviewSummary ?? null))
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
    setSemanticsStatus: (status: number) => {
      semanticsStatus = status
    },
    setReviews: (next: unknown) => {
      reviews = next as typeof reviews
    },
    setDecisions: (next: unknown) => {
      decisions = next as typeof decisions
    },
    setDecisionHistory: (next: unknown) => {
      decisionHistory = next
    },
    setHumanReview: (next: unknown) => {
      humanReview = next
    },
    setChangeSet: (next: unknown) => {
      changeSet = next
    },
    setRedline: (next: unknown) => {
      redline = next
    },
    setRedlineStatus: (status: number) => {
      redlineStatus = status
    },
  }
}
