import { useEffect, useState } from 'react'
import { clearResume, clearResumeFor, readResume, type ResumeRecord } from './resume'

interface ResumeInput {
  /** The Review read has settled, so the card never flashes in and then disappears. */
  settled: boolean
  /** Ids of the Reviews that read returned. */
  loadedIds: readonly string[]
  /** The read returned every Review. Only then does a missing Review prove it is gone. */
  complete: boolean
}

export interface HomeResume {
  record: ResumeRecord | null
  show: boolean
  dismiss: () => void
}

/**
 * Device-local "Continue". Read once when Home opens, and only ever shown for a place that is still
 * valid: `readResume` already drops anything malformed or pointing at a retired route. A complete
 * read that lacks the saved Review proves it is gone. A truncated or failed read proves nothing, so
 * the saved place is kept and the Review route itself reports any problem.
 */
export function useHomeResume({ settled, loadedIds, complete }: ResumeInput): HomeResume {
  const [record, setRecord] = useState<ResumeRecord | null>(readResume)
  const stale = record !== null && complete && !loadedIds.includes(record.reviewId)
  useEffect(() => {
    if (record && stale) clearResumeFor(record.reviewId)
  }, [record, stale])
  return {
    record,
    show: record !== null && settled && !stale,
    dismiss: () => {
      clearResume()
      setRecord(null)
    },
  }
}
