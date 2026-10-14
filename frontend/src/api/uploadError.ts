const UPLOAD_ERROR_MESSAGES: Record<number, string> = {
  400: 'The upload was not accepted. Check that it is the file type this section supports.',
  403: 'You do not have permission to upload this file.',
  404: 'The upload target could not be found.',
  413: 'The file is larger than this Rivell deployment accepts.',
  500: 'The file could not be stored. Nothing was changed.',
  503: 'The storage service is unavailable right now. The file was not stored.',
}

export class UploadApiError extends Error {
  readonly status: number

  /** `messages` replaces the default wording for a surface whose failures mean something different. */
  constructor(status: number, messages: Partial<Record<number, string>> = {}) {
    super(messages[status] ?? UPLOAD_ERROR_MESSAGES[status] ?? 'The file could not be stored.')
    this.name = 'UploadApiError'
    this.status = status
  }
}
