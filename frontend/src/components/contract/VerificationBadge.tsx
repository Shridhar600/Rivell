import { verificationLabel } from '../../lib/contractSemantics'

function verificationTone(verification: string): string {
  switch (verification) {
    case 'VERIFIED':
      return 'badge--approved'
    case 'UNCERTAIN':
      return 'badge--pending'
    case 'DISPUTED':
      return 'badge--rejected'
    default:
      return 'badge--attention-routine'
  }
}

export function VerificationBadge({ verification }: { verification: string }) {
  return (
    <span className={`badge ${verificationTone(verification)}`}>
      {verificationLabel(verification)}
    </span>
  )
}
