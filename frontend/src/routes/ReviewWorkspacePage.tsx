import { useParams } from 'react-router'
import { useContractQuery } from '../api/contracts'
import { isReviewNotFoundError, useReviewSummaryQuery } from '../api/reviews'
import { ErrorState } from '../components/ErrorState'
import { LoadingState } from '../components/LoadingState'
import { ReviewHeader } from '../components/review/ReviewHeader'
import { ReviewLifecyclePanel } from '../components/review/ReviewLifecyclePanel'

export function ReviewWorkspacePage() {
  const { contractId = '', reviewId = '' } = useParams()
  const contractQuery = useContractQuery(contractId)
  const summaryQuery = useReviewSummaryQuery(reviewId)

  if (contractQuery.isPending || summaryQuery.isPending) {
    return <LoadingState label="Loading review…" />
  }

  const matchesRoute =
    summaryQuery.data !== undefined &&
    summaryQuery.data.contractId.toLowerCase() === contractId.toLowerCase()

  if (
    isReviewNotFoundError(summaryQuery.error) ||
    isReviewNotFoundError(contractQuery.error) ||
    (summaryQuery.data !== undefined && !matchesRoute)
  ) {
    return (
      <ErrorState
        message="This review could not be found. It may not exist, or you may not have access to it."
        onRetry={() => void summaryQuery.refetch()}
      />
    )
  }

  if (summaryQuery.isError || !summaryQuery.data || contractQuery.isError) {
    return (
      <ErrorState
        message="This review could not be loaded."
        onRetry={() => void summaryQuery.refetch()}
      />
    )
  }

  return (
    <section className="review-page" aria-labelledby="review-heading">
      <ReviewHeader
        contractId={contractId}
        contractTitle={contractQuery.data?.title ?? 'Untitled contract'}
        summary={summaryQuery.data}
      />
      <ReviewLifecyclePanel
        contractId={contractId}
        reviewId={reviewId}
        summary={summaryQuery.data}
        onRefresh={() => void summaryQuery.refetch()}
      />
    </section>
  )
}