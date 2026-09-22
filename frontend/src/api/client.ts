import createClient from 'openapi-fetch'
import type { paths } from './schema'

export const apiBaseUrl =
  import.meta.env.VITE_API_BASE_URL ||
  globalThis.location?.origin ||
  ''

export function createApiClient() {
  return createClient<paths>({ baseUrl: apiBaseUrl, fetch: globalThis.fetch })
}
