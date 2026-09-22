import createClient from 'openapi-fetch'
import type { paths } from './schema'

const baseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export function createApiClient() {
  return createClient<paths>({ baseUrl, fetch: globalThis.fetch })
}
