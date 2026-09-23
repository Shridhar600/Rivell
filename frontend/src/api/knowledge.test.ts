import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  fetchArtifacts,
  fetchCandidates,
  parseArtifactDetail,
  parseCandidate,
  parseExtractionUnit,
  parseKnowledgeNode,
  parsePublication,
  uploadKnowledge,
} from './knowledge'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown): { data: unknown; status: number; timestamp: string } {
  return { data, status: 200, timestamp: '2026-09-23T05:17:29.636341Z' }
}

function stubFetch(handler: (request: Request) => Response) {
  const requests: Request[] = []
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const request = input as Request
    requests.push(request)
    return Promise.resolve(handler(request))
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, requests }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const artifactDetail = {
  id: '666c50a4-b4d0-435d-97ee-eec8822cc9f9',
  artifactType: 'PLAYBOOK',
  filename: 'knowledge-playbook.docx',
  contentType: 'application/octet-stream',
  sizeBytes: 1487,
  sha256: '3eaad38b59dba45ea79754850eb04fd71eaac95c865d7a8a0be01683b9581f04',
  storageKey: '00000000-0000-0000-0000-000000000002/knowledge/666c50a4.docx',
  declaredContractTypes: [],
  contractPackKey: null,
  partyPerspective: null,
  paperSource: null,
  jurisdiction: null,
  language: null,
  effectiveFrom: null,
  effectiveTo: null,
  manifestNote: null,
  ingestStatus: 'EXTRACTED',
  parserVersion: 'rivell-docx-parser-2',
  parseWarnings: [],
  createdAt: '2026-09-23T05:17:29.604991Z',
}

const candidate = {
  id: 'd67d0ddc-1a4e-4207-a050-33e6db6d2dd2',
  artifactId: '666c50a4-b4d0-435d-97ee-eec8822cc9f9',
  extractionUnitId: 'c4847c0c-8ac0-465d-842f-90b218a9286d',
  candidateType: 'PLAYBOOK_RULE',
  reviewStatus: 'PENDING',
  rawLabel: 'Liability cap',
  contentText: 'Liability cap | 1x annual fees | 2x annual fees',
  payloadColumns: {
    Clause: 'Liability cap',
    Preferred: '1x annual fees',
    Fallback: '2x annual fees',
  },
  legalConceptKey: null,
  editedContentText: null,
  editedPayloadColumns: {},
  supersedingCandidateId: null,
  contractPackKey: null,
  declaredContractTypes: [],
  partyPerspective: null,
  paperSource: null,
  jurisdiction: null,
  effectiveFrom: null,
  effectiveTo: null,
  conflictSignals: [
    {
      conflictType: 'DUPLICATE',
      message: 'Matches published candidate with the same label and content',
      publishedCandidateId: '40e2c1ac-8da5-48fc-ad8a-09a17f2222e3',
    },
  ],
  reviewNote: null,
  reviewedAt: null,
  createdAt: '2026-09-23T05:17:29.612542Z',
  updatedAt: '2026-09-23T05:17:29.612542Z',
}

const docxNode = {
  id: '1459c2d5-3e00-4eeb-9137-0745109b75ab',
  artifactId: '666c50a4-b4d0-435d-97ee-eec8822cc9f9',
  parentId: null,
  nodeType: 'TITLE',
  ordinal: 0,
  text: 'Negotiation Playbook 2026',
  headingLevel: null,
  numberingLabel: null,
  partPath: 'word/document.xml',
  bodyIndex: 0,
  paragraphIndex: 0,
  tableIndex: null,
  rowIndex: null,
  columnIndex: null,
  runStart: null,
  runEnd: null,
  sheetName: null,
  mergedRegion: null,
  rawStyleId: 'Title',
}

const xlsxNode = {
  id: '1b1a0f0f-0000-0000-0000-000000000001',
  artifactId: '53fbc5ca-70d4-4a8e-9001-4be28377360b',
  parentId: '9362f466-eee2-4164-9e21-8ee2852303be',
  nodeType: 'SPREADSHEET_CELL',
  ordinal: 0,
  text: 'Escalation rules apply to all contract families',
  headingLevel: null,
  numberingLabel: null,
  partPath: 'xl/worksheets/sheet1.xml',
  bodyIndex: 0,
  paragraphIndex: null,
  tableIndex: null,
  rowIndex: 0,
  columnIndex: 0,
  runStart: null,
  runEnd: null,
  sheetName: 'Notes',
  mergedRegion: { firstRow: 0, lastRow: 0, firstColumn: 0, lastColumn: 2 },
  rawStyleId: null,
}

const unit = {
  id: 'c4847c0c-8ac0-465d-842f-90b218a9286d',
  artifactId: '666c50a4-b4d0-435d-97ee-eec8822cc9f9',
  ordinal: 2,
  kind: 'TABLE',
  title: null,
  contentText: null,
  sourceNodeIds: ['15c96ac8-03c8-49a7-bb8c-52a3c0e7cfc3'],
}

const publication = {
  id: 'fd8f2941-880b-4298-8348-7d62d70f4ccd',
  organizationId: '00000000-0000-0000-0000-000000000002',
  publicationNumber: 2,
  note: 'm2 frontend probe',
  items: [
    {
      id: '53a5babc-84b8-4e06-ab69-a9bde6a51e1c',
      candidateId: '39e2ff95-e518-44b5-9300-dab0a0f3a133',
      supersededCandidateId: null,
      action: 'ADD',
    },
  ],
  createdAt: '2026-09-23T05:17:59.300169Z',
}

describe('knowledge parsers', () => {
  it('parses a real artifact detail payload', () => {
    const parsed = parseArtifactDetail(artifactDetail)

    expect(parsed.artifactType).toBe('PLAYBOOK')
    expect(parsed.ingestStatus).toBe('EXTRACTED')
    expect(parsed.contractPackKey).toBeNull()
    expect(parsed.declaredContractTypes).toEqual([])
  })

  it('parses a table candidate with payload columns and conflict signals', () => {
    const parsed = parseCandidate(candidate)

    expect(parsed.reviewStatus).toBe('PENDING')
    expect(parsed.payloadColumns).toEqual({
      Clause: 'Liability cap',
      Preferred: '1x annual fees',
      Fallback: '2x annual fees',
    })
    expect(parsed.conflictSignals[0].conflictType).toBe('DUPLICATE')
    expect(parsed.conflictSignals[0].publishedCandidateId).toBe(
      '40e2c1ac-8da5-48fc-ad8a-09a17f2222e3',
    )
  })

  it('normalizes unknown conflict types to UNKNOWN without hiding the message', () => {
    const parsed = parseCandidate({
      ...candidate,
      conflictSignals: [
        { conflictType: 'FUTURE_CONFLICT', message: 'New signal', publishedCandidateId: null },
      ],
    })

    expect(parsed.conflictSignals[0].conflictType).toBe('UNKNOWN')
    expect(parsed.conflictSignals[0].message).toBe('New signal')
  })

  it('rejects candidates with an unknown review status', () => {
    expect(() => parseCandidate({ ...candidate, reviewStatus: 'ARCHIVED' })).toThrow(
      /malformed candidate response/i,
    )
  })

  it('parses DOCX and XLSX structure nodes including merged regions', () => {
    const docx = parseKnowledgeNode(docxNode)
    expect(docx.nodeType).toBe('TITLE')
    expect(docx.mergedRegion).toBeNull()

    const xlsx = parseKnowledgeNode(xlsxNode)
    expect(xlsx.sheetName).toBe('Notes')
    expect(xlsx.columnIndex).toBe(0)
    expect(xlsx.mergedRegion).toEqual({
      firstRow: 0,
      lastRow: 0,
      firstColumn: 0,
      lastColumn: 2,
    })
  })

  it('parses extraction units and publications', () => {
    expect(parseExtractionUnit(unit).kind).toBe('TABLE')
    const parsed = parsePublication(publication)
    expect(parsed.publicationNumber).toBe(2)
    expect(parsed.items[0].action).toBe('ADD')
    expect(parsed.items[0].supersededCandidateId).toBeNull()
  })
})

describe('knowledge list queries', () => {
  it('unwraps the envelope for the artifact list', async () => {
    stubFetch(() => jsonResponse(envelope([artifactDetail])))

    const artifacts = await fetchArtifacts()

    expect(artifacts).toHaveLength(1)
    expect(artifacts[0].filename).toBe('knowledge-playbook.docx')
  })

  it('rejects a list response that is not enveloped', async () => {
    stubFetch(() => jsonResponse([artifactDetail]))

    await expect(fetchArtifacts()).rejects.toThrow(/malformed knowledge sources response/i)
  })

  it('unwraps the envelope for candidates', async () => {
    stubFetch(() => jsonResponse(envelope([candidate])))

    const candidates = await fetchCandidates('a1')

    expect(candidates[0].rawLabel).toBe('Liability cap')
  })
})

describe('uploadKnowledge', () => {
  it('sends the multipart file with manifest query parameters', async () => {
    const { requests } = stubFetch(() => jsonResponse(envelope(artifactDetail)))

    const file = new File(['bytes'], 'knowledge-playbook.docx')
    const result = await uploadKnowledge(file, {
      artifactType: 'PLAYBOOK',
      declaredContractTypes: ['MSA', 'NDA'],
      contractPackKey: 'MSA',
      partyPerspective: 'Customer',
      jurisdiction: 'US',
      effectiveFrom: '2026-01-01',
      note: '  ',
    })

    expect(result.id).toBe(artifactDetail.id)
    const url = new URL(requests[0].url)
    expect(url.pathname).toBe('/api/knowledge/artifacts')
    expect(url.searchParams.get('artifactType')).toBe('PLAYBOOK')
    expect(url.searchParams.get('declaredContractTypes')).toBe('MSA,NDA')
    expect(url.searchParams.get('contractPackKey')).toBe('MSA')
    expect(url.searchParams.get('partyPerspective')).toBe('Customer')
    expect(url.searchParams.get('jurisdiction')).toBe('US')
    expect(url.searchParams.get('effectiveFrom')).toBe('2026-01-01')
    expect(url.searchParams.has('note')).toBe(false)
    expect(requests[0].method).toBe('POST')
    expect(requests[0].headers.get('content-type')).toContain('multipart/form-data')
  })

  it('surfaces backend upload errors', async () => {
    stubFetch(() =>
      jsonResponse(
        {
          code: '400',
          title: 'Invalid request',
          detail: 'Only .docx, .xlsx files are supported',
        },
        400,
      ),
    )

    const file = new File(['bad'], 'notes.txt')
    await expect(
      uploadKnowledge(file, { artifactType: 'PLAYBOOK', declaredContractTypes: [] }),
    ).rejects.toThrow(/only .docx, \.xlsx files are supported/i)
  })

  it('requires the runtime envelope on upload even though OpenAPI shows the inner DTO', async () => {
    stubFetch(() => jsonResponse(artifactDetail))

    const file = new File(['bytes'], 'knowledge-playbook.docx')
    await expect(
      uploadKnowledge(file, { artifactType: 'PLAYBOOK', declaredContractTypes: [] }),
    ).rejects.toThrow(/malformed knowledge source response/i)
  })
})
