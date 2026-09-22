import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { createApiClient } from './client'
import type { paths } from './schema'

export type SystemStatus =
  paths['/api/system/status']['get']['responses'][200]['content']['application/json']

export type SystemStatusError =
  paths['/api/system/status']['get']['responses'][500]['content']['application/json']

export async function fetchSystemStatus(): Promise<SystemStatus> {
  const client = createApiClient()
  const { data, error } = await client.GET('/api/system/status')

  if (error || !data) {
    const message =
      (error as SystemStatusError | undefined)?.message ??
      'System status unavailable'
    throw new Error(message)
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
