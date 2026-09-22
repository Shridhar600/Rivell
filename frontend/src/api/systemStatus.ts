import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { createApiClient } from './client'
import type { paths } from './schema'

export type SystemStatus =
  paths['/api/system/status']['get']['responses'][200]['content']['application/json']

function errorMessage(error: unknown): string {
  if (
    typeof error === 'object' &&
    error !== null &&
    'message' in error &&
    typeof error.message === 'string' &&
    error.message.length > 0
  ) {
    return error.message
  }
  return 'System status unavailable'
}

export async function fetchSystemStatus(): Promise<SystemStatus> {
  const client = createApiClient()
  const { data, error } = await client.GET('/api/system/status')

  if (!data) {
    throw new Error(errorMessage(error))
  }

  return data
}

export function useSystemStatusQuery(): UseQueryResult<SystemStatus, Error> {
  return useQuery({
    queryKey: ['system-status'],
    queryFn: fetchSystemStatus,
    retry: false,
  })
}
