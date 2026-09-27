import type { ReviewProfile } from '../../api/semantics'
import { useEnrichContract } from '../../api/semantics'
import { ReviewProfileForm } from './ReviewProfileForm'
import { profileKey } from '../../lib/contractSemantics'

export function EnrichmentPanel({
  contractId,
  versionNumber,
  storedProfile,
  mode,
  retryable = true,
}: {
  contractId: string
  versionNumber: number
  storedProfile: ReviewProfile | null
  mode: 'initial' | 'retry'
  retryable?: boolean
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
          The previous analysis did not complete. The contract structure is still available in the
          document view.{' '}
          {retryable
            ? 'You can try again with the profile below.'
            : 'Rivell did not record whether trying again would help, so the profile below can still be changed and re-run.'}
        </p>
      )}
      <ReviewProfileForm
        key={profileKey(storedProfile)}
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
