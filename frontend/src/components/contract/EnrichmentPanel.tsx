import type { ReviewProfile } from '../../api/semantics'
import { useEnrichContract } from '../../api/semantics'
import { ReviewProfileForm } from './ReviewProfileForm'

export function EnrichmentPanel({
  contractId,
  versionNumber,
  storedProfile,
  mode,
}: {
  contractId: string
  versionNumber: number
  storedProfile: ReviewProfile | null
  mode: 'initial' | 'retry'
}) {
  const enrich = useEnrichContract(contractId, versionNumber)

  return (
    <section aria-labelledby="enrichment-heading" className="panel semantic-panel">
      <h2 id="enrichment-heading">Semantic analysis</h2>
      {mode === 'initial' ? (
        <p>
          Rivell can analyse this contract version to detect parties and their roles,
          key metadata, defined terms, cross-references, clause meaning and typed
          facts. The original document is never changed.
        </p>
      ) : (
        <p>
          The previous analysis did not complete. The contract structure is still
          available in the document view. You can try again with the profile below.
        </p>
      )}
      <ReviewProfileForm
        initialProfile={storedProfile}
        submitLabel={
          mode === 'initial' ? 'Run semantic analysis' : 'Try semantic analysis again'
        }
        pending={enrich.isPending}
        onSubmit={(profile) => enrich.mutate(profile)}
      />
      {enrich.isPending ? (
        <p role="status" className="state">
          Analysing this contract. This can take a moment.
        </p>
      ) : null}
      {enrich.isError ? (
        <div role="alert" className="state state--error">
          <p>{enrich.error.message}</p>
        </div>
      ) : null}
    </section>
  )
}
