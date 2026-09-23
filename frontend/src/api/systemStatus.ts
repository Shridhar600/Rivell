import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { createApiClient } from './client'
import {
  asRecord,
  errorMessage,
  parseApiResult,
  readString,
} from './envelope'
import type { components } from './schema'

export type SystemStatus = components['schemas']['SystemStatusResponse']

function parseSystemStatus(value: unknown, what: string): SystemStatus {
  const record = asRecord(value, what)
  return {
    service: readString(record, 'service', what),
    status: readString(record, 'status', what),
  }
}

export async function fetchSystemStatus(): Promise<SystemStatus> {
  const client = createApiClient()
  const { data, error } = await client.GET('/api/system/status')

  if (!data) {
    throw new Error(errorMessage(error, 'System status unavailable'))
  }

  return parseApiResult(data, parseSystemStatus, 'system status')
}

export function useSystemStatusQuery(): UseQueryResult<SystemStatus, Error> {
  return useQuery({
    queryKey: ['system-status'],
    queryFn: fetchSystemStatus,
    retry: false,
  })
}
