/**
 * "Continue where you left off" is device-local. Nothing here is sent to or read from the backend,
 * so it can never claim to follow the reviewer to another browser or device.
 *
 * Only facts the browser observed are stored: which Review was opened, for which contract, under
 * what filename, and when. The route is derived from the two ids on read and is never trusted from
 * storage, so a tampered or stale value cannot send the reviewer somewhere unexpected. No progress,
 * next-step or decision data is kept, because the browser never knew any.
 */

import { matchPath } from 'react-router'

export const RESUME_KEY = 'rv-resume'

const RECORD_VERSION = 1
/** The same character set the backend uses for ids. Anything else is not a Review reference. */
const ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/

export interface ResumeRecord {
  reviewId: string
  contractId: string
  filename: string
  /** The contract version this Review is pinned to, when known. */
  versionNumber: number | null
  /** ISO timestamp of the last time this browser opened the Review. */
  visitedAt: string
}

/**
 * Where a saved place may lead. A Review has a front door (its overview) and a workspace, and the
 * Reviews list is a safe place to land. Anything else, such as a removed Knowledge page, an old
 * prototype address or a route this build no longer has, is not a destination to resume into.
 */
const CURRENT_DESTINATIONS = [
  '/reviews',
  '/reviews/:contractId/overview/:reviewId/*',
  '/reviews/:contractId/analysis/:reviewId',
]

/** True only for an in-app path that one of the current Review destinations accepts. */
export function isCurrentDestination(route: string): boolean {
  if (!route.startsWith('/') || route.startsWith('//')) return false
  const path = route.split(/[?#]/)[0] ?? ''
  return CURRENT_DESTINATIONS.some((pattern) => matchPath({ path: pattern, end: true }, path) !== null)
}

/**
 * Older builds could keep an address next to the ids. The route is always derived from the ids on
 * read, so a stored address is never followed. If one is present and no longer current, the whole
 * entry is stale and is dropped.
 */
const STORED_ADDRESS_FIELDS = ['route', 'to', 'path', 'href', 'url'] as const

function hasStaleAddress(raw: Record<string, unknown>): boolean {
  return STORED_ADDRESS_FIELDS.some((field) => {
    const value = raw[field]
    return value !== undefined && !(typeof value === 'string' && isCurrentDestination(value))
  })
}

export function resumeRoute(record: Pick<ResumeRecord, 'contractId' | 'reviewId'>): string {
  return `/reviews/${record.contractId}/analysis/${record.reviewId}`
}

function isValidDate(value: unknown): value is string {
  return typeof value === 'string' && !Number.isNaN(new Date(value).getTime())
}

/** Returns a record only when every field is well formed. Anything else is "no resume state". */
export function parseResumeRecord(value: unknown): ResumeRecord | null {
  if (typeof value !== 'object' || value === null) return null
  const raw = value as Record<string, unknown>
  if (raw.v !== RECORD_VERSION) return null
  if (hasStaleAddress(raw)) return null
  const { reviewId, contractId, filename, versionNumber, visitedAt } = raw
  if (typeof reviewId !== 'string' || !ID_PATTERN.test(reviewId)) return null
  if (typeof contractId !== 'string' || !ID_PATTERN.test(contractId)) return null
  if (typeof filename !== 'string' || filename.trim() === '' || filename.length > 300) return null
  if (!isValidDate(visitedAt)) return null
  if (versionNumber !== null && !(Number.isInteger(versionNumber) && (versionNumber as number) > 0)) {
    return null
  }
  return {
    reviewId,
    contractId,
    filename: filename.trim(),
    versionNumber: versionNumber as number | null,
    visitedAt,
  }
}

/**
 * Storage can be blocked, full or absent. Every failure reads as "nothing saved". A saved value that
 * is present but is not a current, well-formed place is discarded, so it is not parsed again on every visit.
 */
export function readResume(): ResumeRecord | null {
  try {
    const stored = globalThis.localStorage?.getItem(RESUME_KEY)
    if (!stored) return null
    let parsed: ResumeRecord | null = null
    try {
      parsed = parseResumeRecord(JSON.parse(stored))
    } catch {
      parsed = null
    }
    if (!parsed) clearResume()
    return parsed
  } catch {
    return null
  }
}

export function writeResume(record: ResumeRecord): void {
  try {
    globalThis.localStorage?.setItem(RESUME_KEY, JSON.stringify({ v: RECORD_VERSION, ...record }))
  } catch {
    // Ignored on purpose: failing to remember a place must never break the Review it came from.
  }
}

export function clearResume(): void {
  try {
    globalThis.localStorage?.removeItem(RESUME_KEY)
  } catch {
    // Ignored on purpose, for the same reason as writeResume.
  }
}

/**
 * Clears the saved place only if it still points at this Review. A later visit to a different
 * Review must not be erased by a slow answer about an earlier one.
 */
export function clearResumeFor(reviewId: string): void {
  if (readResume()?.reviewId === reviewId) clearResume()
}
