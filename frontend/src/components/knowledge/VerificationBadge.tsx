import type { VerificationState } from '../../api/knowledge'
import { VERIFICATION_LABELS } from '../../lib/semantics'

const TONES: Record<VerificationState, string> = {
  VERIFIED: 'badge--approved',
  UNCERTAIN: 'badge--pending',
  DISPUTED: 'badge--rejected',
  NOT_VERIFIED: 'badge--attention-routine',
}

export function VerificationBadge({ state }: { state: VerificationState }) {
  return <span className={`badge ${TONES[state]}`}>{VERIFICATION_LABELS[state]}</span>
}
