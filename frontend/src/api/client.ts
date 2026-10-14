import createClient from 'openapi-fetch'
import type { paths } from './schema'
import { TransportError } from './errorMessage'

export const apiBaseUrl =
  import.meta.env.VITE_API_BASE_URL ||
  globalThis.location?.origin ||
  ''

async function fetchWithBoundedTransportFailure(request: Request): Promise<Response> {
  try {
    return await globalThis.fetch(request)
  } catch {
    // A rejection here means the request never produced a response, so there is no status to map.
    // Left alone it would surface the browser's own wording, which differs per browser.
    throw new TransportError()
  }
}

export function createApiClient() {
  return createClient<paths>({
    baseUrl: apiBaseUrl,
    fetch: fetchWithBoundedTransportFailure,
  })
}
