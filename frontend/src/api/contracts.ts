import {
  useMutation,
  useQuery,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { apiBaseUrl, createApiClient } from './client'
import {
  asRecord,
  parseApiResult,
  readArray,
  readEnum,
  readNullableNumber,
  readNullableString,
  readNumber,
  readString,
  readStringArray,
} from './envelope'
import type { components } from './schema'
import { UploadApiError } from './uploadError'
import { boundedErrorMessage } from './errorMessage'

export type IngestResponse = components['schemas']['IngestResponse']
export type ContractSummary = components['schemas']['ContractSummaryResponse']
export type StructureResponse = components['schemas']['ContractStructureResponse']
export type DocumentNode = components['schemas']['NodeView']
export type SourceSpan = components['schemas']['SourceSpanView']
export type ParseStatus = IngestResponse['parseStatus']

const PARSE_STATUSES: readonly ParseStatus[] = ['PARSING', 'OK', 'FAILED']

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
    nodeType: readString(node, 'nodeType', what) as DocumentNode['nodeType'],
    ordinal: readNumber(node, 'ordinal', what),
    text: readNullableString(node, 'text', what),
    headingLevel: readNullableNumber(node, 'headingLevel', what),
    numberingLabel: readNullableString(node, 'numberingLabel', what),
    rawStyleId: readNullableString(node, 'rawStyleId', what),
    sourceSpan: parseSourceSpan(node.sourceSpan, what),
  }
}

export function parseIngestResponse(value: unknown, what = 'upload'): IngestResponse {
  const response = asRecord(value, what)
  return {
    contractId: readString(response, 'contractId', what),
    contractVersionId: readString(response, 'contractVersionId', what),
    versionNumber: readNumber(response, 'versionNumber', what),
    parseStatus: readEnum(response, 'parseStatus', what, PARSE_STATUSES),
    parseWarnings: readStringArray(response, 'parseWarnings', what),
    filename: readString(response, 'filename', what),
    sha256: readString(response, 'sha256', what),
    nodeCount: readNumber(response, 'nodeCount', what),
  }
}

export function parseContractSummary(value: unknown, what = 'contract'): ContractSummary {
  const response = asRecord(value, what)
  const latest = asRecord(response.latestVersion, what)
  return {
    id: readString(response, 'id', what),
    title: readNullableString(response, 'title', what),
    latestVersion: {
      versionNumber: readNumber(latest, 'versionNumber', what),
      parseStatus: readEnum(latest, 'parseStatus', what, PARSE_STATUSES),
      parseWarnings: readStringArray(latest, 'parseWarnings', what),
      parserVersion: readNullableString(latest, 'parserVersion', what),
      sha256: readString(latest, 'sha256', what),
      filename: readString(latest, 'filename', what),
      nodeCount: readNumber(latest, 'nodeCount', what),
    },
  }
}

export function parseStructureResponse(
  value: unknown,
  what = 'structure',
): StructureResponse {
  const response = asRecord(value, what)
  return {
    contractId: readString(response, 'contractId', what),
    versionNumber: readNumber(response, 'versionNumber', what),
    parserVersion: readNullableString(response, 'parserVersion', what),
    parseStatus: readEnum(response, 'parseStatus', what, PARSE_STATUSES),
    parseWarnings: readStringArray(response, 'parseWarnings', what),
    nodeCount: readNumber(response, 'nodeCount', what),
    nodes: readArray(response, 'nodes', what, parseNode),
  }
}

export class ContractApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ContractApiError'
    this.status = status
  }
}

export function isContractNotFoundError(error: unknown): boolean {
  return error instanceof ContractApiError && error.status === 404
}

export function isContractForbiddenError(error: unknown): boolean {
  return error instanceof ContractApiError && error.status === 403
}

export async function fetchContract(contractId: string): Promise<ContractSummary> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/contracts/{id}', {
    params: { path: { id: contractId } },
  })

  if (!data) {
    throw new ContractApiError(
      boundedErrorMessage(response.status, CONTRACT_MESSAGES, 'The contract could not be loaded.'),
      response.status,
    )
  }
  return parseApiResult(data, parseContractSummary, 'contract')
}

export async function fetchContractStructure(
  contractId: string,
  versionNumber: number,
): Promise<StructureResponse> {
  const client = createApiClient()
  const { data, response } = await client.GET(
    '/api/contracts/{id}/versions/{versionNumber}/structure',
    { params: { path: { id: contractId, versionNumber } } },
  )

  if (!data) {
    throw new Error(
      boundedErrorMessage(response.status, STRUCTURE_MESSAGES, 'The document structure could not be loaded.'),
    )
  }
  return parseApiResult(data, parseStructureResponse, 'structure')
}

const CONTRACT_MESSAGES: Partial<Record<number, string>> = {
  404: 'This contract could not be found.',
  500: 'The contract could not be loaded.',
  503: 'Contract storage is unavailable right now.',
}

const STRUCTURE_MESSAGES: Partial<Record<number, string>> = {
  404: 'This contract version has no parsed document structure.',
  409: 'This contract version is not parsed yet, so its structure is not available.',
  500: 'The document structure could not be loaded.',
  503: 'Document storage is unavailable right now.',
}

export function createUploadFormData(file: File): FormData {
  const formData = new FormData()
  formData.append('file', file)
  return formData
}

export async function uploadContract(file: File): Promise<IngestResponse> {
  const client = createApiClient()
  const { data, response } = await client.POST('/api/contracts', {
    body: { file: file.name },
    bodySerializer: () => createUploadFormData(file),
  })

  if (!data) throw new UploadApiError(response.status)
  return parseApiResult(data, parseIngestResponse, 'upload')
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
  return `${apiBaseUrl}/api/contracts/${encodeURIComponent(contractId)}/versions/${versionNumber}/original`
}
