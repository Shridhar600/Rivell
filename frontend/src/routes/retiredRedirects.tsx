import { Navigate, useParams } from 'react-router'
import { useContractReviewsQuery } from '../api/reviews'
import { LoadingState } from '../components/ui/State'

/**
 * `/reviews/:contractId` was the v1 contract page (and `/evidence` its evidence inspector). A
 * contract's analysis now lives in its review, so the address opens the contract's latest review,
 * or the Reviews list when it has none or the list cannot be read.
 */
export function ContractPageRedirect() {
  const { contractId = '' } = useParams()
  const reviews = useContractReviewsQuery(contractId)
  if (reviews.isPending) return <LoadingState label="Opening the contract's latest review" />
  const latest = (reviews.data?.items ?? [])
    // Ids are UUIDs: a hand-typed or pasted address may differ in letter case only.
    .filter((review) => review.contractId.toLowerCase() === contractId.toLowerCase())
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))[0]
  return <Navigate replace to={latest ? `/reviews/${encodeURIComponent(latest.contractId)}/overview/${encodeURIComponent(latest.id)}` : '/reviews'} />
}
