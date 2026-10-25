const UPLOAD_ERROR_MESSAGES: Record<number, string> = {
  400: 'The upload was not accepted. Check that it is the file type this section supports.',
  403: 'You do not have permission to upload this file.',
  404: 'The upload target could not be found.',
  413: 'The file is larger than this Rivell deployment accepts.',
  500: 'The file could not be stored. Nothing was changed.',
  503: 'The storage service is unavailable right now. The file was not stored.',
}

/** A 503 that carries Retry-After is Rivell at its document-work limit, not a storage outage. */
const UPLOAD_BUSY_MESSAGE =
  'Rivell is handling as many files as it can right now. The file was not stored; try again in a moment.'

export class UploadApiError extends Error {
  readonly status: number

  /**
   * `messages` replaces the default wording for a surface whose failures mean something different.
   * `retryAfter` tells the busy limit apart from a storage outage, which share the 503 status.
   */
  constructor(status: number, messages: Partial<Record<number, string>> = {}, retryAfter: string | null = null) {
    super(
      (status === 503 && retryAfter
        ? UPLOAD_BUSY_MESSAGE
        : messages[status] ?? UPLOAD_ERROR_MESSAGES[status]) ?? 'The file could not be stored.',
    )
    this.name = 'UploadApiError'
    this.status = status
  }
}
