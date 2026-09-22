import type { ParseStatus } from '../api/contracts'

export function isParseFailed(status: ParseStatus | null | undefined): boolean {
  return status === 'FAILED'
}

export function isParsing(status: ParseStatus | null | undefined): boolean {
  return status === 'PARSING'
}
