import { describe, expect, it } from 'vitest'
import { newRequestId } from './requestId'

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

describe('newRequestId', () => {
  it('produces a version 4 UUID with a valid variant nibble', () => {
    expect(newRequestId()).toMatch(UUID_V4)
  })

  it('produces a distinct id per call', () => {
    const ids = new Set<string | null>()
    for (let index = 0; index < 500; index += 1) ids.add(newRequestId())
    expect(ids.size).toBe(500)
  })

  it('falls back to getRandomValues when randomUUID is unavailable', () => {
    const original = globalThis.crypto
    Object.defineProperty(globalThis, 'crypto', {
      configurable: true,
      value: {
        getRandomValues: (array: Uint8Array) => {
          for (let index = 0; index < array.length; index += 1) array[index] = index
          return array
        },
      },
    })
    try {
      expect(newRequestId()).toMatch(UUID_V4)
    } finally {
      Object.defineProperty(globalThis, 'crypto', { configurable: true, value: original })
    }
  })

  it('degrades to null when no uuid source exists', () => {
    const original = globalThis.crypto
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: {} })
    try {
      expect(newRequestId()).toBeNull()
    } finally {
      Object.defineProperty(globalThis, 'crypto', { configurable: true, value: original })
    }
  })
})
