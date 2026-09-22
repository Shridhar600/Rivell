import { describe, expect, it } from 'vitest'
import type { components, paths } from './schema'

type SystemStatusResponse = components['schemas']['SystemStatusResponse']
type IngestResponse = components['schemas']['IngestResponse']
type ErrorResponse = components['schemas']['ErrorResponse']
type NodeView = components['schemas']['NodeView']

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
  it('exposes typed system status path from generated schema', () => {
    type StatusGet = paths['/api/system/status']['get']
    type SuccessBody =
      StatusGet['responses'][200]['content']['application/json']

    const ok: SuccessBody = {
      service: 'redstar-backend',
      status: 'ready',
    }

    const typed: SystemStatusResponse = ok
    expect(typed.status).toBe('ready')
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
        parserVersion: 'redstar-docx-parser-2',
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
      parserVersion: 'redstar-docx-parser-2',
      parseStatus: 'OK',
      parseWarnings: [],
      nodeCount: 1,
      nodes: [node],
    }
    expect(structure.nodes[0].numberingLabel).toBeNull()
  })
})
