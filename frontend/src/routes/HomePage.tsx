import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { useHomeData } from '../api/home'
import { HOME_PAGE_LIMIT } from '../api/reviews'
import { longDate } from '../lib/dates'
import { boundedErrorMessage, TransportError } from '../api/errorMessage'
import {
  buildHomeGroups,
  knowledgeItemView,
  publicationItemView,
  reviewItemView,
  HOME_GROUP_LABELS,
  type HomeGroupKey,
  type HomeReviewSummary,
  type HomeWorkItem,
} from '../lib/home'
import { CollapsibleGroup } from '../components/ui/CollapsibleGroup'
import { SummaryPill } from '../components/ui/SummaryPill'
import { WorkRow } from '../components/ui/WorkRow'
import { StatusBadge } from '../components/ui/Status'
import { EmptyState, ErrorState, LoadingState, UnavailableNotice } from '../components/ui/State'
import { LinkButton } from '../components/ui/Button'
import { DocumentIcon, PlusIcon } from '../components/ui/Icon'
import { Inspector, TopBar } from '../components/shell/TopBar'

export function HomePage() {
  const { reviews, artifacts, publications } = useHomeData()
  const reviewItems = useMemo<HomeReviewSummary[]>(
    () =>
      (reviews.data?.items ?? []).map((review) => ({
        id: review.id,
        contractId: review.contractId,
        filename: review.contractFilename,
        contractVersionNumber: review.contractVersionNumber,
        status: review.status,
        createdAt: review.createdAt,
        startedAt: review.startedAt,
        completedAt: review.completedAt,
        findings: review.findings,
      })),
    [reviews.data],
  )
  const items = useMemo<HomeWorkItem[]>(() => {
    const collected: HomeWorkItem[] = []
    for (const review of reviewItems) collected.push(reviewItemView(review))
    for (const artifact of artifacts.data ?? []) collected.push(knowledgeItemView(artifact))
    for (const publication of publications.data ?? []) collected.push(publicationItemView(publication))
    return collected
  }, [reviewItems, artifacts.data, publications.data])

  const groups = useMemo(() => buildHomeGroups(items), [items])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  // One effective id drives both the row highlight and the inspector, so they cannot disagree.
  // Nothing is auto-selected: the inspector shows a hint until the reviewer chooses an item.
  // A selection that disappears after a refetch is dropped rather than reassigned silently.
  const effectiveId = items.some((item) => item.id === selectedId) ? selectedId : null
  const selected = items.find((item) => item.id === effectiveId) ?? null

  const [openGroups, setOpenGroups] = useState<ReadonlySet<HomeGroupKey>>(
    () => new Set<HomeGroupKey>(['needs_action', 'failed']),
  )
  function toggleGroup(key: HomeGroupKey) {
    setOpenGroups((current) => {
      const next = new Set(current)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }
  function revealGroup(key: HomeGroupKey) {
    setOpenGroups((current) => (current.has(key) ? current : new Set(current).add(key)))
  }

  // Failure is tracked per domain. Collapsing it into one flag let a knowledge refusal hide
  // readable review work, which is exactly the state the design forbids.
  const reviewState = domainState({
    isPending: reviews.isPending,
    isError: reviews.isError,
    error: reviews.error,
  })
  const artifactState = domainState({
    isPending: artifacts.isPending,
    isError: artifacts.isError,
    error: artifacts.error,
  })
  const publicationState = domainState({
    isPending: publications.isPending,
    isError: publications.isError,
    error: publications.error,
  })
  const knowledgeState = artifactState.loading || artifactState.failed || artifactState.forbidden
    ? artifactState
    : publicationState
  const loading = reviewState.loading || knowledgeState.loading
  // The headline describes all review work, so it waits for every read to settle.
  const resolved = !reviewState.loading && !knowledgeState.loading
  const showEmptyState = resolved && !reviewState.failed && !knowledgeState.failed && items.length === 0
  const moreReviewsExist = Boolean(reviews.data?.nextCursor)

  return (
    <>
      <div className="rv-main">
        <TopBar title="Home" />
        <div className="rv-scroll">
          <div className="rv-wrap">
            <Hero
              resolved={resolved}
              headlineReady={!reviewState.failed}
              needsAction={groups.needsAction.length}
              running={groups.running.length}
              failed={groups.failed.length}
              onReveal={revealGroup}
            />

            {loading ? <LoadingState label="Loading your work" /> : null}

            <DomainError state={reviewState} domain="reviews" onRetry={() => void reviews.refetch()} />
            <DomainError state={knowledgeState} domain="knowledge" onRetry={() => {
              void artifacts.refetch()
              void publications.refetch()
            }} />

            {showEmptyState ? (
              <EmptyState
                title="Nothing needs your attention"
                message="Start a review, or add a playbook or clause library to begin."
                action={
                  <LinkButton to="/reviews" variant="primary" compact>
                    <DocumentIcon className="rv-icon" />
                    Start a review
                  </LinkButton>
                }
              />
            ) : null}

            {moreReviewsExist ? (
              <p className="rv-note">
                Showing the newest {HOME_PAGE_LIMIT} reviews.{' '}
                <Link to="/reviews">Open Reviews for the full list.</Link>
              </p>
            ) : null}

            <GroupSection
              groupKey="needs_action"
              items={groups.needsAction}
              open={openGroups.has('needs_action')}
              onToggle={() => toggleGroup('needs_action')}
              selectedId={effectiveId}
              onSelect={setSelectedId}
            />
            <GroupSection
              groupKey="running"
              items={groups.running}
              open={openGroups.has('running')}
              onToggle={() => toggleGroup('running')}
              selectedId={effectiveId}
              onSelect={setSelectedId}
            />
            <GroupSection
              groupKey="failed"
              items={groups.failed}
              open={openGroups.has('failed')}
              onToggle={() => toggleGroup('failed')}
              selectedId={effectiveId}
              onSelect={setSelectedId}
            />
            <GroupSection
              groupKey="extracted"
              items={groups.extracted}
              open={openGroups.has('extracted')}
              onToggle={() => toggleGroup('extracted')}
              selectedId={effectiveId}
              onSelect={setSelectedId}
            />
            <GroupSection
              groupKey="completed"
              items={groups.completed}
              open={openGroups.has('completed')}
              onToggle={() => toggleGroup('completed')}
              selectedId={effectiveId}
              onSelect={setSelectedId}
            />
          </div>
        </div>
      </div>
      <Inspector status={selected ? `${selected.title}. ${selected.stateLabel}.` : ''}>
        {selected ? <InspectorBody item={selected} /> : <InspectorHint />}
      </Inspector>
    </>
  )
}

function statusOf(error: Error | null): number | null {
  const status = (error as { status?: number | null } | null)?.status
  return typeof status === 'number' ? status : null
}

interface DomainState {
  loading: boolean
  failed: boolean
  /** A permission denial, which is a view-only state rather than a failure. */
  forbidden: boolean
  error: Error | null
}

/**
 * Reduces one read to a state. Healthy reads report `failed: false` so a domain that loaded fine
 * never contributes a message, and a domain that failed never suppresses the others.
 */
function domainState(query: {
  isPending: boolean
  isError: boolean
  error: Error | null
}): DomainState {
  if (query.isPending) return { loading: true, failed: false, forbidden: false, error: null }
  if (query.isError) {
    return {
      loading: false,
      failed: statusOf(query.error) !== 403,
      forbidden: statusOf(query.error) === 403,
      error: query.error,
    }
  }
  return { loading: false, failed: false, forbidden: false, error: null }
}

function DomainError({
  state,
  domain,
  onRetry,
}: {
  state: DomainState
  domain: 'reviews' | 'knowledge'
  onRetry: () => void
}) {
  if (state.loading) return null
  const noun = domain === 'reviews' ? 'review work' : 'knowledge sources'
  if (state.forbidden) {
    return (
      <UnavailableNotice
        title="View only"
        to={domain === 'reviews' ? '/reviews' : '/knowledge'}
        actionLabel={domain === 'reviews' ? 'Open Reviews' : 'Open Knowledge'}
      >
        This Rivell deployment did not grant access to {noun}. Everything else on Home still works.
      </UnavailableNotice>
    )
  }
  if (!state.failed) return null
  return (
    <ErrorState
      title={`${domain === 'reviews' ? 'Review work' : 'Knowledge'} could not be loaded`}
      tone="danger"
      message={domainMessage(state.error, domain)}
      onRetry={onRetry}
    />
  )
}

/** Says what failed, what is intact, and what the user can do next. */
function domainMessage(error: Error | null, domain: 'reviews' | 'knowledge'): string {
  const noun = domain === 'reviews' ? 'review work' : 'knowledge sources'
  if (error instanceof TransportError) {
    return `Rivell could not be reached, so ${noun} is unavailable. Your work is unchanged. Check your connection and try again.`
  }
  if (statusOf(error) === 403) {
    return `This Rivell deployment did not grant access to ${noun}. The rest of Home still works.`
  }
  const MESSAGES: Record<number, string> = {
    404: `That ${noun} could not be found.`,
    500: 'Rivell could not complete that request.',
    503: `The service for ${noun} is unavailable right now.`,
  }
  const derived = boundedErrorMessage(statusOf(error) ?? 500, MESSAGES, `${noun} could not be loaded`)
  return `${derived} The rest of Home still works, and your work is unchanged.`
}

/** One line for a collapsed group, capped so an unbounded list cannot build a huge string. */
function summarise(items: HomeWorkItem[]): string {
  const MAX = 3
  if (items.length <= MAX) return items.map((item) => item.title).join(', ')
  return `${items.slice(0, MAX).map((item) => item.title).join(', ')} and ${items.length - MAX} more`
}

function plural(count: number, one: string, many: string): string {
  return count === 1 ? one : many
}

function Hero({
  resolved,
  headlineReady,
  needsAction,
  running,
  failed,
  onReveal,
}: {
  /** Data has arrived, so counts are safe to show. */
  resolved: boolean
  /** Every part of Rivell's work is readable, so a claim about all of it is safe. */
  headlineReady: boolean
  needsAction: number
  running: number
  failed: number
  onReveal: (group: HomeGroupKey) => void
}) {
  const total = needsAction + running + failed
  return (
    <div className="rv-hero">
      <div>
        <h1 className="rv-hero__title">Rivell</h1>
        {/* Claims nothing until every read resolves, and never duplicates the empty state. */}
        {resolved && headlineReady && total > 0 ? (
          <p className="rv-hero__line">Here is where your work stands.</p>
        ) : null}
      </div>
      <div className="rv-hero__side">
        {resolved ? (
          <div className="rv-pills">
            <SummaryPill
              count={needsAction}
              label={plural(needsAction, 'needs action', 'needs action')}
              tone="attention"
              targetId="needs-action"
              onReveal={() => onReveal('needs_action')}
            />
            <SummaryPill
              count={running}
              label={plural(running, 'running', 'running')}
              tone="active"
              targetId="running"
              onReveal={() => onReveal('running')}
            />
            <SummaryPill
              count={failed}
              label={plural(failed, 'failed', 'failed')}
              tone="danger"
              targetId="failed"
              onReveal={() => onReveal('failed')}
            />
          </div>
        ) : null}
        <div className="rv-hero__actions">
          <LinkButton to="/knowledge/new" compact>
            <PlusIcon className="rv-icon" />
            Add knowledge
          </LinkButton>
          <LinkButton to="/reviews" variant="primary" compact>
            <DocumentIcon className="rv-icon" />
            Start a review
          </LinkButton>
        </div>
      </div>
    </div>
  )
}

function GroupSection({
  groupKey,
  items,
  open,
  onToggle,
  selectedId,
  onSelect,
}: {
  groupKey: HomeGroupKey
  items: HomeWorkItem[]
  open: boolean
  onToggle: () => void
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  if (items.length === 0) return null
  return (
    <div id={groupKey.replace('_', '-')}>
      <CollapsibleGroup
        title={HOME_GROUP_LABELS[groupKey]}
        count={items.length}
        open={open}
        onToggle={onToggle}
        collapsedSummary={summarise(items)}
      >
        <WorkList items={items} selectedId={selectedId} onSelect={onSelect} />
      </CollapsibleGroup>
    </div>
  )
}

function WorkList({
  items,
  selectedId,
  onSelect,
}: {
  items: HomeWorkItem[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  // Never fall back to this group's first row: that made every group highlight a row.
  const currentId = items.some((item) => item.id === selectedId) ? selectedId : null

  function move(offset: number) {
    const index = items.findIndex((item) => item.id === currentId)
    // With no selection there is nothing to step from, so ArrowUp must not jump to the top.
    if (index < 0 && offset < 0) return
    const next = items[Math.min(items.length - 1, Math.max(0, index + offset))]
    if (next) onSelect(next.id)
  }

  return (
    <>
      {/* ul[role=listbox] is the valid ARIA mapping the design system requires for WorkRow. */}
      {/* oxlint-disable-next-line jsx-a11y/no-noninteractive-element-to-interactive-role */}
      <ul className="rv-rows" role="listbox" aria-label="Work">
        {items.map((item, index) => (
          <WorkRow
            key={item.id}
            item={item}
            selected={item.id === currentId}
            spin={item.group === 'running'}
            tabbable={currentId ? item.id === currentId : index === 0}
            onSelect={() => onSelect(item.id)}
            onStep={(offset) => move(offset)}
          />
        ))}
      </ul>
    </>
  )
}

function InspectorHint() {
  return (
    <>
      <p className="rv-insp__kind">Details</p>
      <p className="rv-insp__note">Select work to see what it is and what to do next.</p>
    </>
  )
}

const KIND_LABELS: Record<HomeWorkItem['kind'], string> = {
  review: 'Contract review',
  knowledge_artifact: 'Knowledge source',
  knowledge_publication: 'Knowledge publication',
}

function InspectorBody({ item }: { item: HomeWorkItem }) {
  return (
    <>
      <p className="rv-insp__kind">{KIND_LABELS[item.kind]}</p>
      <h2 className="rv-insp__title">{item.title}</h2>
      <StatusBadge tone={item.tone} label={item.stateLabel} />
      <dl className="rv-insp__props">
        {item.chip ? (
          <>
            <dt>Type</dt>
            <dd>{item.chip}</dd>
          </>
        ) : null}
        <dt>{item.timeLabelKind === 'added' ? 'Added' : 'Updated'}</dt>
        <dd>{longDate(item.timeValue)}</dd>
      </dl>
      {item.guidance ? <p className="rv-insp__note">{item.guidance}</p> : null}
      <div className="rv-insp__action">
        <LinkButton to={item.to} variant="primary">
          {item.primaryActionLabel}
        </LinkButton>
      </div>
    </>
  )
}
