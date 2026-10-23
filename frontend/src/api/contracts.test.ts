import { UploadApiError } from './uploadError'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from './schema'
import {
  createUploadFormData,
  fetchContract,
  fetchContractStructure,
  originalDocumentUrl,
  uploadContract,
} from './contracts'

type NodeView = components['schemas']['NodeView']

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown): { data: unknown; status: number; timestamp: string } {
  return { data, status: 200, timestamp: '2026-09-23T05:00:00Z' }
}

function node(partial: Partial<NodeView> & { id: string }): NodeView {
  return {
    parentId: null,
    nodeType: 'PARAGRAPH',
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    rawStyleId: null,
    sourceSpan: {
      partPath: 'word/document.xml',
      bodyIndex: 0,
      paragraphIndex: null,
      tableIndex: null,
      rowIndex: null,
      cellIndex: null,
      runStart: null,
      runEnd: null,
    },
    ...partial,
  }
}

const summary = {
  id: 'c1',
  title: 'Master Services Agreement',
  latestVersion: {
    versionNumber: 1,
    parseStatus: 'OK',
    parseWarnings: [],
    parserVersion: 'rivell-docx-parser-2',
    sha256: 'a'.repeat(64),
    filename: 'msa.docx',
    nodeCount: 3,
  },
}

const structure = {
  contractId: 'c1',
  versionNumber: 1,
  parserVersion: 'rivell-docx-parser-2',
  parseStatus: 'OK',
  parseWarnings: ['SOME_BODY_CONTENT_COULD_NOT_BE_REPRESENTED'],
  nodeCount: 1,
  nodes: [node({ id: 'p1', text: 'The parties agree.' })],
}

const ingest = {
  contractId: 'c1',
  contractVersionId: 'v1',
  versionNumber: 1,
  parseStatus: 'OK',
  parseWarnings: [],
  filename: 'msa.docx',
  sha256: 'a'.repeat(64),
  nodeCount: 1,
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

describe('fetchContract', () => {
  it('GETs and validates the contract summary', async () => {
    const { requests } = stubFetch(() => jsonResponse(envelope(summary)))

    const result = await fetchContract('c1')

    expect(result.id).toBe('c1')
    expect(result.latestVersion.filename).toBe('msa.docx')
    expect(requests[0].url).toContain('/api/contracts/c1')
  })

  it('reports a bounded 404 and never the backend error body', async () => {
    stubFetch(() =>
      jsonResponse(
        {
          code: 'NOT_FOUND',
          title: 'Not found',
          detail: 'Contract 8f0e missing at com.rivell.workbench.contracts.ContractService.require:88',
        },
        404,
      ),
    )

    const error = await fetchContract('missing').catch((thrown: unknown) => thrown)

    expect((error as Error).message).toBe('This contract could not be found.')
    expect((error as Error).message).not.toMatch(/8f0e|com\.rivell/)
  })

  it('reports a bounded message for a status with no server body', async () => {
    stubFetch(() => new Response(null, { status: 500 }))

    await expect(fetchContract('broken')).rejects.toThrow(/contract could not be loaded/i)
  })

  it('rejects a malformed successful summary instead of crashing later', async () => {
    stubFetch(() => jsonResponse(envelope({ id: 'c1', title: 'MSA' })))

    await expect(fetchContract('c1')).rejects.toThrow(/could not read this response/i)
  })

  it('requires the runtime ApiResult envelope for contract reads', async () => {
    stubFetch(() => jsonResponse(summary))

    await expect(fetchContract('c1')).rejects.toThrow(/could not read this response/i)
  })
})

describe('fetchContractStructure', () => {
  it('GETs and validates structure for the given version', async () => {
    const { requests } = stubFetch(() => jsonResponse(envelope(structure)))

    const result = await fetchContractStructure('c1', 1)

    expect(result.nodes).toHaveLength(1)
    expect(result.parseWarnings).toEqual([
      'SOME_BODY_CONTENT_COULD_NOT_BE_REPRESENTED',
    ])
    expect(requests[0].url).toContain('/api/contracts/c1/versions/1/structure')
  })

  it('rejects malformed structure payloads', async () => {
    stubFetch(() => jsonResponse(envelope({ ...structure, nodes: 'not-an-array' })))

    await expect(fetchContractStructure('c1', 1)).rejects.toThrow(
      /could not read this response/i,
    )
  })

  it('rejects nodes without a usable source span', async () => {
    stubFetch(() =>
      jsonResponse(
        envelope({
          ...structure,
          nodes: [{ id: 'p1', nodeType: 'PARAGRAPH' }],
        }),
      ),
    )

    await expect(fetchContractStructure('c1', 1)).rejects.toThrow(
      /could not read this response/i,
    )
  })

  it('accepts unknown future node types at the boundary', async () => {
    stubFetch(() =>
      jsonResponse(
        envelope({
          ...structure,
          nodes: [
            node({ id: 'x1', nodeType: 'FUTURE_BLOCK' as NodeView['nodeType'] }),
          ],
        }),
      ),
    )

    const result = await fetchContractStructure('c1', 1)

    expect(result.nodes[0].nodeType).toBe('FUTURE_BLOCK')
  })
})

describe('uploadContract', () => {
  it('POSTs multipart form data with the file field', async () => {
    const { requests } = stubFetch(() => jsonResponse(envelope(ingest)))

    const file = new File(['bytes'], 'msa.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    const result = await uploadContract(file)

    expect(result.contractId).toBe('c1')
    expect(result.filename).toBe('msa.docx')
    const request = requests[0]
    expect(request.url).toContain('/api/contracts')
    expect(request.method).toBe('POST')
    expect(request.headers.get('content-type')).toContain('multipart/form-data')
  })

  it('sends the multipart file under the contract field name', () => {
    const file = new File(['bytes'], 'msa.docx')
    const formData = createUploadFormData(file)

    expect(formData.get('file')).toBeInstanceOf(File)
  })

  it('reports a bounded upload error that names no server text', async () => {
    stubFetch(() =>
      jsonResponse(
        {
          code: 'INVALID_UPLOAD',
          title: 'Invalid upload',
          detail: 'Rejected at com.rivell.workbench.contracts.ContractService.require:88',
        },
        400,
      ),
    )

    const file = new File(['bad'], 'bad.txt', { type: 'text/plain' })
    const error = await uploadContract(file).catch((thrown: unknown) => thrown)

    expect(error).toBeInstanceOf(UploadApiError)
    expect((error as Error).message).toMatch(/upload was not accepted/i)
    expect((error as Error).message).not.toMatch(/com\.rivell|require:88|only \.docx/)
  })

  it('rejects a malformed ingest response', async () => {
    stubFetch(() => jsonResponse(envelope({ contractId: 'c1' })))

    const file = new File(['bytes'], 'msa.docx')
    await expect(uploadContract(file)).rejects.toThrow(/could not read this response/i)
  })

  it('requires the runtime ApiResult envelope on upload', async () => {
    stubFetch(() => jsonResponse(ingest))

    const file = new File(['bytes'], 'msa.docx')
    await expect(uploadContract(file)).rejects.toThrow(/could not read this response/i)
  })
})

describe('originalDocumentUrl', () => {
  it('builds the original document URL from base + path', () => {
    expect(originalDocumentUrl('c1', 2)).toMatch(
      /\/api\/contracts\/c1\/versions\/2\/original$/,
    )
  })
})
