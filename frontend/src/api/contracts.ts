import {
  useMutation,
  useQuery,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { apiBaseUrl, createApiClient } from './client'
import type { components } from './schema'

export type IngestResponse = components['schemas']['IngestResponse']
export type ContractSummary = components['schemas']['ContractSummaryResponse']
export type StructureResponse = components['schemas']['ContractStructureResponse']
export type DocumentNode = components['schemas']['NodeView']
export type SourceSpan = components['schemas']['SourceSpanView']
export type ParseStatus = IngestResponse['parseStatus']

export function isParseStatus(value: unknown): value is ParseStatus {
  return value === 'PARSING' || value === 'OK' || value === 'FAILED'
}

function malformed(what: string): Error {
  return new Error(`Malformed ${what} response from the server`)
}

function asRecord(value: unknown, what: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw malformed(what)
  }
  return value as Record<string, unknown>
}

function readString(source: Record<string, unknown>, key: string, what: string): string {
  const value = source[key]
  if (typeof value !== 'string' || value.length === 0) throw malformed(what)
  return value
}

function readNumber(source: Record<string, unknown>, key: string, what: string): number {
  const value = source[key]
  if (typeof value !== 'number' || !Number.isFinite(value)) throw malformed(what)
  return value
}

function readNullableString(
  source: Record<string, unknown>,
  key: string,
  what: string,
): string | null {
  const value = source[key]
  if (value === null) return null
  if (typeof value !== 'string') throw malformed(what)
  return value
}

function readNullableNumber(
  source: Record<string, unknown>,
  key: string,
  what: string,
): number | null {
  const value = source[key]
  if (value === null) return null
  if (typeof value !== 'number' || !Number.isFinite(value)) throw malformed(what)
  return value
}

function readStringArray(
  source: Record<string, unknown>,
  key: string,
  what: string,
): string[] {
  const value = source[key]
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw malformed(what)
  }
  return value as string[]
}

function readParseStatus(source: Record<string, unknown>, what: string): ParseStatus {
  const value = source.parseStatus
  if (!isParseStatus(value)) throw malformed(what)
  return value
}

function readNodeType(source: Record<string, unknown>, what: string): DocumentNode['nodeType'] {
  const value = source.nodeType
  if (typeof value !== 'string' || value.length === 0) throw malformed(what)
  return value as DocumentNode['nodeType']
}

function parseSourceSpan(value: unknown, what: string): SourceSpan {
  const span = asRecord(value, what)
  return {
    partPath: readString(span, 'partPath', what),
    bodyIndex: readNumber(span, 'bodyIndex', what),
    paragraphIndex: readNullableNumber(span, 'paragraphIndex', what),
    tableIndex: readNullableNumber(span, 'tableIndex', what),
    rowIndex: readNullableNumber(span, 'rowIndex', what),
    cellIndex: readNullableNumber(span, 'cellIndex', what),
    runStart: readNullableNumber(span, 'runStart', what),
    runEnd: readNullableNumber(span, 'runEnd', what),
  }
}

function parseNode(value: unknown, what: string): DocumentNode {
  const node = asRecord(value, what)
  return {
    id: readString(node, 'id', what),
    parentId: readNullableString(node, 'parentId', what),
    nodeType: readNodeType(node, what),
    ordinal: readNumber(node, 'ordinal', what),
    text: readNullableString(node, 'text', what),
    headingLevel: readNullableNumber(node, 'headingLevel', what),
    numberingLabel: readNullableString(node, 'numberingLabel', what),
    rawStyleId: readNullableString(node, 'rawStyleId', what),
    sourceSpan: parseSourceSpan(node.sourceSpan, what),
  }
}

export function parseIngestResponse(value: unknown): IngestResponse {
  const what = 'upload'
  const response = asRecord(value, what)
  return {
    contractId: readString(response, 'contractId', what),
    contractVersionId: readString(response, 'contractVersionId', what),
    versionNumber: readNumber(response, 'versionNumber', what),
    parseStatus: readParseStatus(response, what),
    parseWarnings: readStringArray(response, 'parseWarnings', what),
    filename: readString(response, 'filename', what),
    sha256: readString(response, 'sha256', what),
    nodeCount: readNumber(response, 'nodeCount', what),
  }
}

export function parseContractSummary(value: unknown): ContractSummary {
  const what = 'contract'
  const response = asRecord(value, what)
  const latest = asRecord(response.latestVersion, what)
  return {
    id: readString(response, 'id', what),
    title: readNullableString(response, 'title', what),
    latestVersion: {
      versionNumber: readNumber(latest, 'versionNumber', what),
      parseStatus: readParseStatus(latest, what),
      parseWarnings: readStringArray(latest, 'parseWarnings', what),
      parserVersion: readNullableString(latest, 'parserVersion', what),
      sha256: readString(latest, 'sha256', what),
      filename: readString(latest, 'filename', what),
      nodeCount: readNumber(latest, 'nodeCount', what),
    },
  }
}

export function parseStructureResponse(value: unknown): StructureResponse {
  const what = 'structure'
  const response = asRecord(value, what)
  const nodes = response.nodes
  if (!Array.isArray(nodes)) throw malformed(what)
  return {
    contractId: readString(response, 'contractId', what),
    versionNumber: readNumber(response, 'versionNumber', what),
    parserVersion: readNullableString(response, 'parserVersion', what),
    parseStatus: readParseStatus(response, what),
    parseWarnings: readStringArray(response, 'parseWarnings', what),
    nodeCount: readNumber(response, 'nodeCount', what),
    nodes: nodes.map((node) => parseNode(node, what)),
  }
}

function errorMessage(error: unknown, fallback: string): string {
  if (typeof error === 'object' && error !== null) {
    const record = error as Record<string, unknown>
    for (const key of ['detail', 'title', 'message'] as const) {
      const value = record[key]
      if (typeof value === 'string' && value.trim().length > 0) return value
    }
  }
  return fallback
}

export async function fetchContract(
  contractId: string,
): Promise<ContractSummary> {
  const client = createApiClient()
  const { data, error } = await client.GET('/api/contracts/{id}', {
    params: { path: { id: contractId } },
  })

  if (!data) {
    throw new Error(errorMessage(error, 'Contract unavailable'))
  }
  return parseContractSummary(data)
}

export async function fetchContractStructure(
  contractId: string,
  versionNumber: number,
): Promise<StructureResponse> {
  const client = createApiClient()
  const { data, error } = await client.GET(
    '/api/contracts/{id}/versions/{versionNumber}/structure',
    { params: { path: { id: contractId, versionNumber } } },
  )

  if (!data) {
    throw new Error(errorMessage(error, 'Structure unavailable'))
  }
  return parseStructureResponse(data)
}

export function createUploadFormData(file: File): FormData {
  const formData = new FormData()
  formData.append('file', file)
  return formData
}

export async function uploadContract(file: File): Promise<IngestResponse> {
  const client = createApiClient()
  const { data, error } = await client.POST('/api/contracts', {
    body: { file: file.name },
    bodySerializer: () => createUploadFormData(file),
  })

  if (!data) {
    throw new Error(errorMessage(error, 'Upload failed'))
  }
  return parseIngestResponse(data)
}

export function useContractQuery(
  contractId: string,
): UseQueryResult<ContractSummary, Error> {
  return useQuery({
    queryKey: ['contract', contractId],
    queryFn: () => fetchContract(contractId),
  })
}

export function useContractStructureQuery(
  contractId: string,
  versionNumber: number | undefined,
): UseQueryResult<StructureResponse, Error> {
  return useQuery({
    queryKey: ['contract', contractId, 'structure', versionNumber],
    queryFn: () => {
      if (versionNumber === undefined) {
        throw new Error('Contract version is not available yet')
      }
      return fetchContractStructure(contractId, versionNumber)
    },
    enabled: versionNumber !== undefined,
  })
}

export function useUploadContract(): UseMutationResult<
  IngestResponse,
  Error,
  File
> {
  return useMutation({ mutationFn: uploadContract })
}

export function originalDocumentUrl(
  contractId: string,
  versionNumber: number,
): string {
  return `${apiBaseUrl}/api/contracts/${contractId}/versions/${versionNumber}/original`
}
