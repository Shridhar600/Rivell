import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { useReviewHistoryQuery } from '../api/reviews'
import { mergeReviewPages, reviewRowView, type ReviewRowView } from '../lib/reviewHistory'
import { NewReviewForm } from '../components/NewReviewForm'
import { TopBar } from '../components/shell/TopBar'
import { Button } from '../components/ui/Button'
import { ChevronIcon, PlusIcon, RunningIcon } from '../components/ui/Icon'
import { Chip } from '../components/ui/Status'
import { EmptyState, ErrorState, LoadingState } from '../components/ui/State'

const PANEL_ID = 'rv-start-review'

type Filter = 'all' | 'running'

export function ReviewsPage() {
  const history = useReviewHistoryQuery()
  const rows = useMemo(
    () => mergeReviewPages(history.data?.pages ?? []).map(reviewRowView),
    [history.data],
  )
  const [startOpen, setStartOpen] = useState(false)
  const [filter, setFilter] = useState<Filter>('all')

  const running = rows.filter((row) => row.running)
  const shown = filter === 'running' ? running : rows
  const startButton = (
    <Button
      variant="primary"
      aria-expanded={startOpen}
      aria-controls={PANEL_ID}
      onClick={() => setStartOpen((open) => !open)}
    >
      <PlusIcon className="rv-icon" />
      Start review
    </Button>
  )

  return (
    <div className="rv-main rv-main--wide">
      <TopBar title="Contract reviews" actions={startButton} />
      <div className="rv-scroll">
        <div className="rv-page">
          <div className="rv-page__head">
            <div>
              <h1 className="rv-page__title">Contract reviews</h1>
              <p className="rv-page__lede">
                Every review is tied to one immutable contract version and frozen review context.
              </p>
            </div>
            {startButton}
          </div>

          {startOpen ? (
            <div id={PANEL_ID}>
              <NewReviewForm onCancel={() => setStartOpen(false)} />
            </div>
          ) : null}

          {history.isPending ? <LoadingState label="Loading reviews" /> : null}
          {history.isError && rows.length === 0 ? (
            <ErrorState
              title="Reviews could not be loaded"
              message={history.error.message}
              onRetry={() => void history.refetch()}
            />
          ) : null}
          {history.isSuccess && rows.length === 0 ? (
            <EmptyState title="No reviews yet" message="Upload a contract to start your first review." />
          ) : null}

          {rows.length > 0 ? (
            <>
              <div className="rv-ftabs" role="group" aria-label="Filter reviews">
                <FilterTab label="All" count={rows.length} pressed={filter === 'all'} onClick={() => setFilter('all')} />
                {/* Needs a per-review decision projection the list read does not carry. */}
                <FilterTab label="Needs you" unavailable />
                <FilterTab
                  label="Running"
                  count={running.length}
                  pressed={filter === 'running'}
                  onClick={() => setFilter('running')}
                />
                {/* Needs a finalization state the list read does not carry. */}
                <FilterTab label="Finalized" unavailable />
              </div>

              <div className="rv-rlist">
                <div className="rv-rtab rv-rtab--head">
                  <span>Contract</span>
                  <span>Status</span>
                  <span>Progress</span>
                  <span>Created</span>
                </div>
                {shown.length > 0 ? (
                  <ul className="rv-rrows">
                    {shown.map((row) => (
                      <ReviewRow key={row.id} row={row} />
                    ))}
                  </ul>
                ) : (
                  <p className="rv-rlist__empty">No loaded reviews are running.</p>
                )}
              </div>
            </>
          ) : null}

          {history.isFetchNextPageError ? (
            <ErrorState
              title="More reviews could not be loaded"
              message={history.error?.message ?? 'Try again.'}
              onRetry={() => void history.fetchNextPage()}
            />
          ) : null}
          {history.hasNextPage && !history.isFetchNextPageError ? (
            <div className="rv-more">
              <p className="rv-note">Showing the newest {rows.length} reviews. Counts cover these only.</p>
              <Button compact disabled={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()}>
                {history.isFetchingNextPage ? 'Loading…' : 'Load more reviews'}
              </Button>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  )
}

function FilterTab({
  label,
  count,
  pressed,
  unavailable,
  onClick,
}: {
  label: string
  count?: number
  pressed?: boolean
  unavailable?: boolean
  onClick?: () => void
}) {
  return (
    <button
      type="button"
      className="rv-ftab"
      aria-pressed={unavailable ? undefined : pressed}
      disabled={unavailable}
      title={unavailable ? 'Not available yet: the review list does not report this.' : undefined}
      onClick={onClick}
    >
      {label}
      {count === undefined ? null : <span>{count}</span>}
    </button>
  )
}

function ReviewRow({ row }: { row: ReviewRowView }) {
  return (
    <li>
      <Link to={row.to} className="rv-rrow">
        <div className="rv-rtab">
          <div className="rv-rrow__name">
            <b className="rv-rrow__title">
              {row.title}
              {row.versionChip ? <Chip>{row.versionChip}</Chip> : null}
            </b>
            <span className="rv-rrow__detail">{row.detail}</span>
          </div>
          <div>
            <span className={`rv-stb${row.variant === 'plain' ? '' : ` rv-stb--${row.variant}`}`}>
              {row.analyzing ? <RunningIcon className="rv-icon rv-glyph--spin" /> : null}
              {row.stateLabel}
            </span>
          </div>
          <div className="rv-rrow__progress">{row.progress}</div>
          <div className="rv-rrow__time">{row.createdLabel}</div>
        </div>
        <ChevronIcon className="rv-icon" />
      </Link>
    </li>
  )
}
