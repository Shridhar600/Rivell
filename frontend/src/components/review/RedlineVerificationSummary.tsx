import type { RedlineExecution } from '../../api/redline'
import { redlineVerificationOutcome, redlineVerificationSentence } from '../../lib/redline'

export function RedlineVerificationSummary({ execution }: { execution: RedlineExecution }) {
  if (execution.status !== 'COMPLETED' || execution.outcome !== 'REDLINE_GENERATED') {
    return null
  }
  const outcome = redlineVerificationOutcome(execution)
  const passed = outcome === 'PASSED'
  return (
    <div className={`redline__verification ${passed ? 'redline__verification--passed' : ''}`}>
      <p className="redline__verification-title">
        {passed ? 'Verification passed' : 'Verification incomplete'}
      </p>
      <p className="panel__hint">{redlineVerificationSentence(outcome)}</p>
    </div>
  )
}
