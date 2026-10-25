import { describe, expect, it } from 'vitest'
import { UploadApiError } from './uploadError'

describe('upload error copy', () => {
  it('keeps the storage wording for a storage outage', () => {
    const message = new UploadApiError(503).message
    expect(message).toMatch(/storage/i)
    expect(message).toMatch(/not stored/i)
  })

  it('says Rivell is busy, not that storage failed, when the server sends Retry-After', () => {
    const message = new UploadApiError(503, {}, '5').message
    expect(message).toMatch(/try again/i)
    expect(message).not.toMatch(/storage/i)
    expect(message).not.toMatch(/exception|com\.rivell|stack/i)
  })
})
