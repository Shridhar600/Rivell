import { Link } from 'react-router'
import { boundedErrorMessage, TransportError } from '../../api/errorMessage'
import { HOME_PAGE_LIMIT } from '../../api/reviews'
import { ErrorState, UnavailableNotice } from '../ui/State'

function statusOf(error: Error | null): number | null {
  const status = (error as { status?: number | null } | null)?.status
  return typeof status === 'number' ? status : null
}

/** A permission denial is a view-only state. Anything else that failed is an error with a retry. */
export function ReviewReadProblem({ error, onRetry }: { error: Error; onRetry: () => void }) {
  if (statusOf(error) === 403) {
    return (
      <UnavailableNotice title="View only" to="/reviews" actionLabel="Open Reviews">
        This Rivell deployment did not grant access to review work. You can still start a review or add knowledge.
      </UnavailableNotice>
    )
  }
  return <ErrorState title="Review work could not be loaded" tone="danger" message={messageFor(error)} onRetry={onRetry} />
}

/** Says what failed, what is intact, and what the user can do next. */
function messageFor(error: Error): string {
  if (error instanceof TransportError) {
    return 'Rivell could not be reached, so review work is unavailable. Your work is unchanged. Check your connection and try again.'
  }
  const MESSAGES: Record<number, string> = {
    404: 'That review work could not be found.',
    500: 'Rivell could not complete that request.',
    503: 'The service for review work is unavailable right now.',
  }
  const derived = boundedErrorMessage(statusOf(error) ?? 500, MESSAGES, 'review work could not be loaded')
  return `${derived} Your work is unchanged.`
}

/** What Home is not showing, said plainly. Both facts are reachable from the Reviews page. */
export function HomeCoverageNote({ more, unplaced }: { more: boolean; unplaced: number }) {
  if (!more && unplaced === 0) return null
  return (
    <p className="rv-note">
      {more ? `Showing the newest ${HOME_PAGE_LIMIT} reviews. ` : null}
      {unplaced > 0
        ? `${unplaced} ${unplaced === 1 ? 'review has' : 'reviews have'} no state Home can place. `
        : null}
      <Link to="/reviews">Open Reviews for the full list.</Link>
    </p>
  )
}
