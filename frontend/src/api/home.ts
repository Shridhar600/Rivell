import { useMemo } from 'react'
import type { UseQueryResult } from '@tanstack/react-query'
import {
  useArtifactsQuery,
  usePublicationsQuery,
  type ArtifactSummary,
  type Publication,
} from './knowledge'
import type { HomeReviewSummary } from '../lib/home'

/**
 * Home needs one bounded organization-scoped review read.
 *
 * `GET /api/reviews` requires `contractId`, and `GET /api/contracts` has no GET, so the shipped
 * contract cannot produce a cross-contract list. The backend endpoint for this is in flight.
 *
 * Until it lands this seam reports `unavailable`. It deliberately:
 *   - does not invent a temporary endpoint;
 *   - does not fan out one `GET /api/reviews` per contract (that is the N+1 the design forbids);
 *   - does not synthesize a single review from the knowledge domain.
 *
 * When the endpoint lands, replace `homeReviewsUnavailable` with a real fetch + parser and the
 * rest of Home is unchanged: everything downstream reads `HomeReviewSummary[]`.
 */
export const HOME_REVIEWS_UNAVAILABLE_CODE = 'NO_CROSS_CONTRACT_REVIEW_READ'

export type HomeReviewsResult =
  | { availability: 'available'; items: HomeReviewSummary[] }
  | {
      availability: 'unavailable'
      code: typeof HOME_REVIEWS_UNAVAILABLE_CODE
      reason: string
    }

export function homeReviewsUnavailable(): HomeReviewsResult {
  return {
    availability: 'unavailable',
    code: HOME_REVIEWS_UNAVAILABLE_CODE,
    reason: 'Rivell cannot yet list review work across contracts.',
  }
}

export interface HomeData {
  reviews: HomeReviewsResult
  artifacts: UseQueryResult<ArtifactSummary[], Error>
  publications: UseQueryResult<Publication[], Error>
}

export function useHomeData(): HomeData {
  const artifacts = useArtifactsQuery()
  const publications = usePublicationsQuery()
  const reviews = useMemo(() => homeReviewsUnavailable(), [])
  return { reviews, artifacts, publications }
}