import { Link, useParams } from 'react-router'
import { useContractQuery } from '../api/contracts'
import { useContractReviewsQuery } from '../api/reviews'
import { useSemanticsQuery } from '../api/semantics'
import { ErrorState } from '../components/ErrorState'
import { LoadingState } from '../components/LoadingState'
import { ReviewCreatePanel } from '../components/review/ReviewCreatePanel'
import { ReviewHistoryList } from '../components/review/ReviewHistoryList'
import { useHeadingFocus } from '../lib/useHeadingFocus'

export function ContractAnalysisPage() {
  const { contractId = '' } = useParams()
  const contractQuery = useContractQuery(contractId)
  const contract = contractQuery.data
  const versionNumber = contract?.latestVersion.versionNumber
  const semanticsQuery = useSemanticsQuery(contractId, versionNumber)
  const reviewsQuery = useContractReviewsQuery(contractId)
  const headingRef = useHeadingFocus<HTMLHeadingElement>(contract?.id)

  if (!contractId) {
    return <ErrorState message="No contract was selected." />
  }

  if (contractQuery.isPending) {
    return <LoadingState label="Loading contract…" />
  }

  if (contractQuery.isError || !contract) {
    return (
      <ErrorState
        message="This contract could not be loaded. It may not exist, or you may not have access to it."
        onRetry={() => void contractQuery.refetch()}
      />
    )
  }

  const status = semanticsQuery.data?.enrichmentStatus

  return (
    <section className="analysis-page" aria-labelledby="analysis-heading">
      <header className="analysis-page__header">
        <div>
          <p className="analysis-page__eyebrow">Automated reviews</p>
          <h1 id="analysis-heading" tabIndex={-1} ref={headingRef}>
            {contract.title ?? 'Untitled contract'}
          </h1>
        </div>
        <Link className="link-button" to={`/reviews/${contractId}`}>
          Back to contract
        </Link>
      </header>

      {semanticsQuery.isPending ? (
        <LoadingState label="Loading semantic analysis…" />
      ) : semanticsQuery.isError && !semanticsQuery.data ? (
        <ErrorState
          message="The contract's semantic analysis state could not be loaded."
          onRetry={() => void semanticsQuery.refetch()}
        />
      ) : status && status !== 'READY' && status !== 'UNKNOWN' ? (
        <section className="panel" aria-labelledby="analysis-gate-heading">
          <h2 id="analysis-gate-heading">Semantic analysis is not ready</h2>
          <p>
            An automated review needs a completed semantic analysis for this contract
            version. Complete the profile and semantic analysis first.
          </p>
          <Link className="button" to={`/reviews/${contractId}?view=semantics`}>
            Open semantic analysis
          </Link>
        </section>
      ) : (
        <ReviewCreatePanel
          contractId={contractId}
          versionNumber={contract.latestVersion.versionNumber}
          profile={semanticsQuery.data?.reviewProfile ?? null}
          reviewIds={(reviewsQuery.data?.items ?? []).map((review) => review.id)}
          onCheckReviews={async () => {
            const result = await reviewsQuery.refetch()
            return {
              ok: !result.isError,
              ids: (result.data?.items ?? []).map((review) => review.id),
            }
          }}
        />
      )}

      <ReviewHistoryList
        contractId={contractId}
        reviews={reviewsQuery.data?.items ?? []}
        truncated={reviewsQuery.data?.truncated ?? false}
        isPending={reviewsQuery.isPending}
        isError={reviewsQuery.isError}
        onRetry={() => void reviewsQuery.refetch()}
      />
    </section>
  )
}