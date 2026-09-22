import {
  useMutation,
  useQuery,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { apiBaseUrl, createApiClient } from './client'
import type { components } from './schema'

export type ContractSummary = components['schemas']['ContractSummary']
export type StructureResponse = components['schemas']['StructureResponse']
export type DocumentNode = components['schemas']['DocumentNode']
export type SourceSpan = components['schemas']['SourceSpan']

function errorMessage(error: unknown, fallback: string): string {
  if (
    typeof error === 'object' &&
    error !== null &&
    'message' in error &&
    typeof error.message === 'string' &&
    error.message.length > 0
  ) {
    return error.message
  }
  return fallback
}

export async function fetchContract(
  contractId: string,
): Promise<ContractSummary> {
  const client = createApiClient()
  const { data, error } = await client.GET('/api/contracts/{contractId}', {
    params: { path: { contractId } },
  })

  if (!data) {
    throw new Error(errorMessage(error, 'Contract unavailable'))
  }
  return data
}

export async function fetchContractStructure(
  contractId: string,
  versionNumber: number,
): Promise<StructureResponse> {
  const client = createApiClient()
  const { data, error } = await client.GET(
    '/api/contracts/{contractId}/versions/{versionNumber}/structure',
    { params: { path: { contractId, versionNumber } } },
  )

  if (!data) {
    throw new Error(errorMessage(error, 'Structure unavailable'))
  }
  return data
}

export function createUploadFormData(file: File): FormData {
  const formData = new FormData()
  formData.append('file', file)
  return formData
}

export async function uploadContract(file: File): Promise<ContractSummary> {
  const client = createApiClient()
  const { data, error } = await client.POST('/api/contracts', {
    body: { file: file.name },
    bodySerializer: () => createUploadFormData(file),
  })

  if (!data) {
    throw new Error(errorMessage(error, 'Upload failed'))
  }
  return data
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
    queryFn: () => fetchContractStructure(contractId, versionNumber as number),
    enabled: versionNumber !== undefined,
  })
}

export function useUploadContract(): UseMutationResult<
  ContractSummary,
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
