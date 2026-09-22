import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createUploadFormData,
  fetchContract,
  fetchContractStructure,
  originalDocumentUrl,
  uploadContract,
} from './contracts'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const summary = {
  contractId: 'c1',
  title: 'MSA',
  filename: 'msa.docx',
  versionNumber: 1,
  parseStatus: 'COMPLETED',
  parseQuality: 'OK',
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('fetchContract', () => {
  it('GETs the contract summary', async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(jsonResponse(summary)),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await fetchContract('c1')

    expect(result.contractId).toBe('c1')
    const request = fetchMock.mock.calls[0][0] as Request
    expect(request.url).toContain('/api/contracts/c1')
    expect(request.method).toBe('GET')
  })

  it('throws a readable error on failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
        Promise.resolve(jsonResponse({ message: 'gone' }, 404)),
      ),
    )

    await expect(fetchContract('missing')).rejects.toThrow(/gone|unavailable/i)
  })
})

describe('fetchContractStructure', () => {
  it('GETs structure for the given version', async () => {
    const structure = {
      contractId: 'c1',
      versionNumber: 1,
      parserVersion: 'redstar-docx-parser-1',
      parseStatus: 'COMPLETED',
      parseQuality: 'OK',
      warnings: [],
      nodes: [],
    }
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(jsonResponse(structure)),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await fetchContractStructure('c1', 1)

    expect(result.nodes).toEqual([])
    const request = fetchMock.mock.calls[0][0] as Request
    expect(request.url).toContain('/api/contracts/c1/versions/1/structure')
  })
})

describe('createUploadFormData', () => {
  it('places the file under the file field', () => {
    const file = new File(['bytes'], 'msa.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })

    const formData = createUploadFormData(file)

    expect(formData.get('file')).toBe(file)
  })
})

describe('uploadContract', () => {
  it('POSTs multipart form data with the file field', async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(jsonResponse(summary, 201)),
    )
    vi.stubGlobal('fetch', fetchMock)

    const file = new File(['bytes'], 'msa.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    const result = await uploadContract(file)

    expect(result.contractId).toBe('c1')
    const request = fetchMock.mock.calls[0][0] as Request
    expect(request.url).toContain('/api/contracts')
    expect(request.method).toBe('POST')
    const contentType = request.headers.get('content-type') ?? ''
    expect(contentType).toMatch(/^multipart\/form-data/)
    const raw = await request.text()
    expect(raw).toContain('name="file"')
  })

  it('throws on upload failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
        Promise.resolve(jsonResponse({ message: 'not a docx' }, 400)),
      ),
    )

    const file = new File(['bad'], 'bad.txt', { type: 'text/plain' })
    await expect(uploadContract(file)).rejects.toThrow(/not a docx|upload/i)
  })
})

describe('originalDocumentUrl', () => {
  it('builds the original document URL from base + path', () => {
    expect(originalDocumentUrl('c1', 2)).toMatch(
      /\/api\/contracts\/c1\/versions\/2\/original$/,
    )
  })
})
