export function malformed(what: string): Error {
  return new Error(`Malformed ${what} response from the server`)
}

export function asRecord(value: unknown, what: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw malformed(what)
  }
  return value as Record<string, unknown>
}

export function readString(
  source: Record<string, unknown>,
  key: string,
  what: string,
): string {
  const value = source[key]
  if (typeof value !== 'string' || value.length === 0) throw malformed(what)
  return value
}

export function readNumber(
  source: Record<string, unknown>,
  key: string,
  what: string,
): number {
  const value = source[key]
  if (typeof value !== 'number' || !Number.isFinite(value)) throw malformed(what)
  return value
}

export function readNullableString(
  source: Record<string, unknown>,
  key: string,
  what: string,
): string | null {
  const value = source[key]
  if (value === null) return null
  if (typeof value !== 'string') throw malformed(what)
  return value
}

export function readNullableNumber(
  source: Record<string, unknown>,
  key: string,
  what: string,
): number | null {
  const value = source[key]
  if (value === null) return null
  if (typeof value !== 'number' || !Number.isFinite(value)) throw malformed(what)
  return value
}

export function readStringArray(
  source: Record<string, unknown>,
  key: string,
  what: string,
): string[] {
  const value = source[key]
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw malformed(what)
  }
  return value as string[]
}

export function readStringMap(
  source: Record<string, unknown>,
  key: string,
  what: string,
): Record<string, string> {
  const value = source[key]
  if (value === null || value === undefined) return {}
  if (typeof value !== 'object' || Array.isArray(value)) throw malformed(what)
  const entries = Object.entries(value)
  if (entries.some(([, entry]) => typeof entry !== 'string')) {
    throw malformed(what)
  }
  return Object.fromEntries(entries) as Record<string, string>
}

export function readEnum<T extends string>(
  source: Record<string, unknown>,
  key: string,
  what: string,
  values: readonly T[],
): T {
  const value = source[key]
  if (typeof value !== 'string' || !(values as readonly string[]).includes(value)) {
    throw malformed(what)
  }
  return value as T
}

export function readArrayValue<T>(
  value: unknown,
  what: string,
  parseItem: (item: unknown, what: string) => T,
): T[] {
  if (!Array.isArray(value)) throw malformed(what)
  return value.map((item) => parseItem(item, what))
}

export function readArray<T>(
  source: Record<string, unknown>,
  key: string,
  what: string,
  parseItem: (item: unknown, what: string) => T,
): T[] {
  return readArrayValue(source[key], what, parseItem)
}

export function parseApiResult<T>(
  raw: unknown,
  parseData: (data: unknown, what: string) => T,
  what: string,
): T {
  const envelope = asRecord(raw, what)
  const status = envelope.status
  if (typeof status !== 'number' || !Number.isFinite(status)) throw malformed(what)
  const timestamp = envelope.timestamp
  if (timestamp !== undefined && typeof timestamp !== 'string') {
    throw malformed(what)
  }
  if (envelope.data === undefined || envelope.data === null) {
    throw malformed(what)
  }
  return parseData(envelope.data, what)
}

export function errorMessage(error: unknown, fallback: string): string {
  if (typeof error === 'object' && error !== null) {
    const record = error as Record<string, unknown>
    for (const key of ['detail', 'title', 'message'] as const) {
      const value = record[key]
      if (typeof value === 'string' && value.trim().length > 0) return value
    }
  }
  return fallback
}
