const DEFAULT_MESSAGES: Record<number, string> = {
  400: 'The request was not valid.',
  403: 'You do not have permission to do that.',
  404: 'That resource could not be found.',
  409: 'That action does not apply in the current state.',
  413: 'The submitted content is larger than this Rivell deployment accepts.',
  500: 'Rivell could not complete that request.',
  503: 'That Rivell service is unavailable right now.',
}

/**
 * Builds a bounded, status-derived error message.
 *
 * The server's error bodies carry a free-text `detail` that is, for several failure paths, a raw
 * exception message containing identifiers and internal locations. It is deliberately never read
 * for a message the user can see.
 */
export function boundedErrorMessage(
  status: number,
  messages: Partial<Record<number, string>> = {},
  fallback = 'Rivell could not complete that request.',
): string {
  return messages[status] ?? DEFAULT_MESSAGES[status] ?? fallback
}

/**
 * A request that never produced a response.
 *
 * Without this, a transport failure rethrows the browser's own error and surfaces as "Failed to
 * fetch", "NetworkError when attempting to fetch resource" or "Load failed" depending on the
 * browser. It carries no status, so callers classify it as unconfirmed rather than as a server
 * rejection.
 */
export class TransportError extends Error {
  readonly status = null

  constructor() {
    super('Rivell could not be reached. Check your connection and try again.')
    this.name = 'TransportError'
  }
}
