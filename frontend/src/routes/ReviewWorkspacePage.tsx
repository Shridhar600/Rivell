import { useParams } from 'react-router'
import { useContractQuery } from '../api/contracts'
import { isReviewNotFoundError, useReviewSummaryQuery } from '../api/reviews'
import { ErrorState } from '../components/ErrorState'
import { LoadingState } from '../components/LoadingState'
import { ReviewHeader } from '../components/review/ReviewHeader'

export function ReviewWorkspacePage() {
  const { contractId = '', reviewId = '' } = useParams()
  const contractQuery = useContractQuery(contractId)
  const summaryQuery = useReviewSummaryQuery(reviewId)

  if (contractQuery.isPending || summaryQuery.isPending) {
    return <LoadingState label="Loading review…" />
  }

  if (isReviewNotFoundError(summaryQuery.error) || isReviewNotFoundError(contractQuery.error)) {
    return (
      <ErrorState
        message="This review could not be found. It may not exist, or you may not have access to it."
        onRetry={() => void summaryQuery.refetch()}
      />
    )
  }

  if (summaryQuery.isError || !summaryQuery.data) {
    return (
      <ErrorState
        message="This review could not be loaded."
        onRetry={() => void summaryQuery.refetch()}
      />
    )
  }

  return (
    <section className="review-workspace" aria-labelledby="review-heading">
      <ReviewHeader
        contractId={contractId}
        contractTitle={contractQuery.data?.title ?? 'Untitled contract'}
        summary={summaryQuery.data}
      />
    </section>
  )
}