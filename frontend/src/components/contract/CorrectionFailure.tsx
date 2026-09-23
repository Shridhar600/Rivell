import { isConflictError } from '../../api/semantics'

export function CorrectionFailure({
  error,
  onRefresh,
}: {
  error: Error
  onRefresh: () => void
}) {
  if (isConflictError(error)) {
    return (
      <div role="alert" className="state state--error">
        <p>
          The semantic analysis for this contract is not ready. Refresh the analysis
          state and try again.
        </p>
        <button type="button" onClick={onRefresh}>
          Refresh analysis state
        </button>
      </div>
    )
  }

  return (
    <p role="alert" className="correction__error">
      {error.message}
    </p>
  )
}
