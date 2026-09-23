import { useEnrichContract, type ReviewProfile } from '../../api/semantics'
import { ReviewProfileForm } from './ReviewProfileForm'

export function ReRunAnalysis({
  contractId,
  versionNumber,
  storedProfile,
}: {
  contractId: string
  versionNumber: number
  storedProfile: ReviewProfile
}) {
  const enrich = useEnrichContract(contractId, versionNumber)

  return (
    <details className="rerun-panel">
      <summary>Update profile and re-run analysis</summary>
      <div className="rerun-panel__body">
        <p>
          Re-running recomputes the machine interpretations. Human corrections are kept
          and applied again to the new results.
        </p>
        <ReviewProfileForm
          initialProfile={storedProfile}
          submitLabel="Re-run semantic analysis"
          pending={enrich.isPending}
          onSubmit={(profile) => enrich.mutate(profile)}
        />
        {enrich.isPending ? (
          <p role="status" className="state">
            Re-analysing this contract. This can take a moment.
          </p>
        ) : null}
        {enrich.isError ? (
          <div role="alert" className="state state--error">
            <p>{enrich.error.message}</p>
          </div>
        ) : null}
      </div>
    </details>
  )
}
