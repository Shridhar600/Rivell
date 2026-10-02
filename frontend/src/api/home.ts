import type { UseQueryResult } from '@tanstack/react-query'
import {
  useArtifactsQuery,
  usePublicationsQuery,
  type ArtifactSummary,
  type Publication,
} from './knowledge'
import {
  useOrganizationReviewsQuery,
  type ReviewListResponse,
} from './reviews'

export interface HomeData {
  /** One bounded read across every contract. Not a per-contract fan-out. */
  reviews: UseQueryResult<ReviewListResponse>
  artifacts: UseQueryResult<ArtifactSummary[], Error>
  publications: UseQueryResult<Publication[], Error>
}

export function useHomeData(): HomeData {
  return {
    reviews: useOrganizationReviewsQuery(),
    artifacts: useArtifactsQuery(),
    publications: usePublicationsQuery(),
  }
}