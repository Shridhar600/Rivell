import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { useReviewHistoryQuery } from '../api/reviews'
import {
  mergeReviewPages,
  reviewRowView,
  type ReviewGroup,
  type ReviewRowView,
} from '../lib/reviewHistory'
import { TopBar } from '../components/shell/TopBar'
import { Button, LinkButton } from '../components/ui/Button'
import { ChevronIcon, LockIcon, PlusIcon, RunningIcon } from '../components/ui/Icon'
import { Chip } from '../components/ui/Status'
import { EmptyState, ErrorState, LoadingState } from '../components/ui/State'

type Filter = 'all' | ReviewGroup

/** The canonical filter tabs, in order. "Finalized" is the canonical name of the `done` group. */
const FILTERS: { key: Filter; label: string; empty: string }[] = [
  { key: 'all', label: 'All', empty: 'No reviews are loaded.' },
  { key: 'needs', label: 'Needs you', empty: 'No loaded reviews need you.' },
  { key: 'running', label: 'Running', empty: 'No loaded reviews are running.' },
  { key: 'done', label: 'Finalized', empty: 'No loaded reviews are finalized.' },
]

export function ReviewsPage() {
  const history = useReviewHistoryQuery()
  const rows = useMemo(
    () => mergeReviewPages(history.data?.pages ?? []).map(reviewRowView),
    [history.data],
  )
  const [filter, setFilter] = useState<Filter>('all')

  const countOf = (key: Filter) => (key === 'all' ? rows.length : rows.filter((row) => row.lifecycle.group === key).length)
  const shown = filter === 'all' ? rows : rows.filter((row) => row.lifecycle.group === filter)
  const startButton = (
    <LinkButton to="/reviews/new" variant="primary">
      <PlusIcon className="rv-icon" />
      Start review
    </LinkButton>
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
                Each review keeps the contract version it started with.
              </p>
            </div>
            {startButton}
          </div>

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
                {FILTERS.map((entry) => (
                  <FilterTab
                    key={entry.key}
                    label={entry.label}
                    count={countOf(entry.key)}
                    pressed={filter === entry.key}
                    onClick={() => setFilter(entry.key)}
                  />
                ))}
              </div>

              <div className="rv-rlist">
                <div className="rv-rtab rv-rtab--head">
                  <span>Contract</span>
                  <span>Status</span>
                  <span>Progress</span>
                  <span>Uploaded</span>
                </div>
                {shown.length > 0 ? (
                  <ul className="rv-rrows">
                    {shown.map((row) => (
                      <ReviewRow key={row.id} row={row} />
                    ))}
                  </ul>
                ) : (
                  <p className="rv-rlist__empty">{FILTERS.find((entry) => entry.key === filter)?.empty}</p>
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
              <p className="rv-note">Showing the newest {rows.length} reviews. Counts include these reviews only.</p>
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
  onClick,
}: {
  label: string
  count: number
  pressed: boolean
  onClick: () => void
}) {
  return (
    <button type="button" className="rv-ftab" aria-pressed={pressed} onClick={onClick}>
      {label}
      <span>{count}</span>
    </button>
  )
}

function ReviewRow({ row }: { row: ReviewRowView }) {
  const { lifecycle } = row
  return (
    <li>
      <Link to={row.to} className="rv-rrow">
        <div className="rv-rtab">
          <div className="rv-rrow__name">
            <b className="rv-rrow__title">
              {row.title}
              {row.versionChip ? <Chip>{row.versionChip}</Chip> : null}
              {row.typeChip ? <Chip>{row.typeChip}</Chip> : null}
            </b>
            {row.detail ? <span className="rv-rrow__detail">{row.detail}</span> : null}
          </div>
          <div>
            <span className={`rv-stb${lifecycle.variant === 'plain' ? '' : ` rv-stb--${lifecycle.variant}`}`}>
              {lifecycle.glyph === 'spin' ? <RunningIcon className="rv-icon rv-glyph--spin" /> : null}
              {lifecycle.glyph === 'lock' ? <LockIcon className="rv-icon" /> : null}
              {lifecycle.label}
            </span>
          </div>
          <div className="rv-rrow__progress">{row.progress}</div>
          <div className="rv-rrow__time">{row.uploadedLabel}</div>
        </div>
        <ChevronIcon className="rv-icon" />
      </Link>
    </li>
  )
}
