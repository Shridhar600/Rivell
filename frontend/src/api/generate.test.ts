import { describe, expect, it } from 'vitest'
import type { components, paths } from './schema'

type SystemStatusResponse = components['schemas']['SystemStatusResponse']
type IngestResponse = components['schemas']['IngestResponse']
type ErrorResponse = components['schemas']['ErrorResponse']
type NodeView = components['schemas']['NodeView']
type CandidateView = components['schemas']['CandidateView']
type KnowledgeNodeView = components['schemas']['KnowledgeStructureNodeView']
type PublicationView = components['schemas']['PublicationView']
type ApiResultCandidateView = components['schemas']['ApiResultCandidateView']

const node: NodeView = {
  id: '11111111-1111-1111-1111-111111111111',
  parentId: null,
  nodeType: 'PARAGRAPH',
  ordinal: 0,
  text: 'hello',
  headingLevel: null,
  numberingLabel: null,
  rawStyleId: null,
  sourceSpan: {
    partPath: 'word/document.xml',
    bodyIndex: 0,
    paragraphIndex: 0,
    tableIndex: null,
    rowIndex: null,
    cellIndex: null,
    runStart: null,
    runEnd: null,
  },
}

describe('generated OpenAPI client workflow', () => {
  it('exposes the enveloped system status path from the generated schema', () => {
    type StatusGet = paths['/api/system/status']['get']
    type SuccessBody =
      StatusGet['responses'][200]['content']['application/json']

    const ok: SuccessBody = {
      data: { service: 'rivell-backend', status: 'ready' },
      status: 200,
      timestamp: '2026-09-23T05:00:00Z',
    }

    const typed: SystemStatusResponse = ok.data ?? {}
    expect(typed.status).toBe('ready')
    expect(ok.status).toBe(200)
    expect('/api/system/status' satisfies keyof paths).toBe(
      '/api/system/status',
    )
  })

  it('exposes M1 contract paths from generated schema', () => {
    expect('/api/contracts' satisfies keyof paths).toBe('/api/contracts')
    expect('/api/contracts/{id}' satisfies keyof paths).toBe('/api/contracts/{id}')
    expect(
      '/api/contracts/{id}/versions/{versionNumber}/structure' satisfies keyof paths,
    ).toBe('/api/contracts/{id}/versions/{versionNumber}/structure')
    expect(
      '/api/contracts/{id}/versions/{versionNumber}/original' satisfies keyof paths,
    ).toBe('/api/contracts/{id}/versions/{versionNumber}/original')

    expect(node.nodeType).toBe('PARAGRAPH')
    expect(node.parentId).toBeNull()
  })

  it('exposes M2 knowledge paths from generated schema', () => {
    expect('/api/knowledge/artifacts' satisfies keyof paths).toBe(
      '/api/knowledge/artifacts',
    )
    expect('/api/knowledge/artifacts/{id}' satisfies keyof paths).toBe(
      '/api/knowledge/artifacts/{id}',
    )
    expect(
      '/api/knowledge/artifacts/{id}/structure' satisfies keyof paths,
    ).toBe('/api/knowledge/artifacts/{id}/structure')
    expect('/api/knowledge/artifacts/{id}/units' satisfies keyof paths).toBe(
      '/api/knowledge/artifacts/{id}/units',
    )
    expect('/api/knowledge/artifacts/{id}/candidates' satisfies keyof paths).toBe(
      '/api/knowledge/artifacts/{id}/candidates',
    )
    expect('/api/knowledge/artifacts/{id}/original' satisfies keyof paths).toBe(
      '/api/knowledge/artifacts/{id}/original',
    )
    expect('/api/knowledge/candidates/{id}' satisfies keyof paths).toBe(
      '/api/knowledge/candidates/{id}',
    )
    expect('/api/knowledge/candidates/{id}/approve' satisfies keyof paths).toBe(
      '/api/knowledge/candidates/{id}/approve',
    )
    expect('/api/knowledge/candidates/{id}/edit' satisfies keyof paths).toBe(
      '/api/knowledge/candidates/{id}/edit',
    )
    expect('/api/knowledge/candidates/{id}/reject' satisfies keyof paths).toBe(
      '/api/knowledge/candidates/{id}/reject',
    )
    expect('/api/knowledge/publications' satisfies keyof paths).toBe(
      '/api/knowledge/publications',
    )
    expect('/api/knowledge/publications/{id}' satisfies keyof paths).toBe(
      '/api/knowledge/publications/{id}',
    )
  })

  it('types M2 upload manifest query parameters', () => {
    type UploadQuery =
      paths['/api/knowledge/artifacts']['post']['parameters']['query']

    const query: UploadQuery = {
      artifactType: 'PLAYBOOK',
      declaredContractTypes: 'MSA,NDA',
      contractPackKey: 'MSA',
      effectiveFrom: '2026-01-01',
    }

    expect(query.artifactType).toBe('PLAYBOOK')
    // @ts-expect-error artifactType is a required manifest query parameter
    const missing: UploadQuery = { contractPackKey: 'MSA' }
    expect(missing).toBeDefined()
  })

  it('documents the ApiResult envelope on upload responses', () => {
    type KnowledgeUpload =
      paths['/api/knowledge/artifacts']['post']['responses'][200]['content']['*/*']
    type ContractUpload =
      paths['/api/contracts']['post']['responses'][200]['content']['*/*']

    const knowledgeUpload: KnowledgeUpload = {
      data: { id: '11111111-1111-1111-1111-111111111111' },
      status: 200,
      timestamp: '2026-09-23T05:00:00Z',
    }
    const contractUpload: ContractUpload = {
      data: {
        contractId: '11111111-1111-1111-1111-111111111111',
        contractVersionId: '22222222-2222-2222-2222-222222222222',
        versionNumber: 1,
        parseStatus: 'OK',
        parseWarnings: [],
        filename: 'msa.docx',
        sha256: 'a'.repeat(64),
        nodeCount: 1,
      },
      status: 200,
      timestamp: '2026-09-23T05:00:00Z',
    }

    expect(knowledgeUpload.data?.id).toBeDefined()
    expect(contractUpload.data?.filename).toBe('msa.docx')
  })

  it('types M2 candidate review status and conflict enums', () => {
    const candidate: CandidateView = {
      candidateType: 'PLAYBOOK_RULE',
      reviewStatus: 'PENDING',
      payloadColumns: { Clause: 'Liability cap' },
      conflictSignals: [
        {
          conflictType: 'DUPLICATE',
          message: 'Matches published candidate',
          publishedCandidateId: '22222222-2222-2222-2222-222222222222',
        },
      ],
    }

    expect(candidate.reviewStatus).toBe('PENDING')

    const approved: CandidateView['reviewStatus'] = 'APPROVED'
    const published: CandidateView['reviewStatus'] = 'PUBLISHED'
    // @ts-expect-error review status is a closed enum in the contract
    const archived: CandidateView['reviewStatus'] = 'ARCHIVED'
    expect([approved, published, archived]).toEqual(['APPROVED', 'PUBLISHED', 'ARCHIVED'])

    const envelope: ApiResultCandidateView = {
      data: candidate,
      status: 200,
      timestamp: '2026-09-23T05:00:00Z',
    }
    expect(envelope.data?.reviewStatus).toBe('PENDING')
  })

  it('types M2 structure node and publication shapes', () => {
    const xlsx: KnowledgeNodeView = {
      nodeType: 'SPREADSHEET_CELL',
      text: 'Clause',
      sheetName: 'Positions',
      rowIndex: 0,
      columnIndex: 0,
      mergedRegion: { firstRow: 0, lastRow: 0, firstColumn: 0, lastColumn: 2 },
    }
    expect(xlsx.mergedRegion?.lastColumn).toBe(2)

    const publication: PublicationView = {
      publicationNumber: 1,
      items: [
        {
          candidateId: '11111111-1111-1111-1111-111111111111',
          action: 'ADD',
        },
      ],
    }
    expect(publication.items?.[0].action).toBe('ADD')
  })

  it('documents the M3 unit-classification endpoint and its domain view', () => {
    expect(
      '/api/knowledge/artifacts/{artifactId}/units/{unitId}/classification' satisfies keyof paths,
    ).toBe('/api/knowledge/artifacts/{artifactId}/units/{unitId}/classification')

    type Classification =
      paths['/api/knowledge/artifacts/{artifactId}/units/{unitId}/classification']['post']['responses'][200]['content']['*/*']

    const response: Classification = {
      data: {
        unitId: '11111111-1111-1111-1111-111111111111',
        artifactId: '22222222-2222-2222-2222-222222222222',
        proposedConcepts: [
          { concept: 'LIMITATION_OF_LIABILITY', probability: 0.91 },
        ],
        evaluatedConcepts: [
          { concept: 'CONFIDENTIALITY', probability: 0.02 },
          { concept: 'LIMITATION_OF_LIABILITY', probability: 0.91 },
        ],
        noneMatched: false,
      },
      status: 200,
      timestamp: '2026-09-23T14:00:00Z',
    }

    expect(response.data?.proposedConcepts?.[0].concept).toBe(
      'LIMITATION_OF_LIABILITY',
    )
    expect(response.data?.noneMatched).toBe(false)

    // @ts-expect-error the M3 concept set is a closed enum in the contract
    const unknownConcept: NonNullable<
      components['schemas']['ConceptView']['concept']
    > = 'FORCE_MAJEURE'
    expect(unknownConcept).toBe('FORCE_MAJEURE')
  })

  it('types corrected required, nullable, enum and error contract fields', () => {
    const ingest: IngestResponse = {
      contractId: '11111111-1111-1111-1111-111111111111',
      contractVersionId: '22222222-2222-2222-2222-222222222222',
      versionNumber: 1,
      parseStatus: 'OK',
      parseWarnings: ['SOME_BODY_CONTENT_COULD_NOT_BE_REPRESENTED'],
      filename: 'msa.docx',
      sha256: 'a'.repeat(64),
      nodeCount: 1,
    }
    expect(ingest.parseWarnings).toHaveLength(1)

    const error: ErrorResponse = {
      code: 'NOT_FOUND',
      title: 'Not found',
      detail: 'Contract does not exist in this organization',
    }
    expect(error.detail).toContain('does not exist')

    const failed: IngestResponse['parseStatus'] = 'FAILED'
    const parsing: IngestResponse['parseStatus'] = 'PARSING'
    // @ts-expect-error parse status is a closed enum in the contract
    const unknown: IngestResponse['parseStatus'] = 'COMPLETED'
    expect([failed, parsing, unknown]).toEqual(['FAILED', 'PARSING', 'COMPLETED'])

    type Summary = components['schemas']['ContractSummaryResponse']
    const summary: Summary = {
      id: '11111111-1111-1111-1111-111111111111',
      title: null,
      latestVersion: {
        versionNumber: 1,
        parseStatus: 'OK',
        parseWarnings: [],
        parserVersion: 'rivell-docx-parser-2',
        sha256: 'b'.repeat(64),
        filename: 'msa.docx',
        nodeCount: 1,
      },
    }
    expect(summary.latestVersion.filename).toBe('msa.docx')

    type Structure = components['schemas']['ContractStructureResponse']
    const structure: Structure = {
      contractId: '11111111-1111-1111-1111-111111111111',
      versionNumber: 1,
      parserVersion: 'rivell-docx-parser-2',
      parseStatus: 'OK',
      parseWarnings: [],
      nodeCount: 1,
      nodes: [node],
    }
    expect(structure.nodes[0].numberingLabel).toBeNull()
  })
})
