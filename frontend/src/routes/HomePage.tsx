import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'
import { useHomeData } from '../api/home'
import { HOME_PAGE_LIMIT } from '../api/reviews'
import { boundedErrorMessage, TransportError } from '../api/errorMessage'
import {
  buildHomeGroups,
  knowledgeItemView,
  publicationItemView,
  reviewItemView,
  HOME_GROUP_LABELS,
  type HomeGroupKey,
  type HomeGroups,
  type HomeReviewSummary,
  type HomeWorkItem,
} from '../lib/home'
import { clearResume, clearResumeFor, readResume, type ResumeRecord } from '../lib/resume'
import { useMediaQuery } from '../lib/useMediaQuery'
import { CollapsibleGroup } from '../components/ui/CollapsibleGroup'
import { SummaryPill } from '../components/ui/SummaryPill'
import { WorkRow } from '../components/ui/WorkRow'
import { EmptyState, ErrorState, LoadingState, UnavailableNotice } from '../components/ui/State'
import { LinkButton } from '../components/ui/Button'
import { DocumentIcon, PlusIcon } from '../components/ui/Icon'
import { ContinueCard } from '../components/home/ContinueCard'
import { WorkDetails, WorkDetailsHint } from '../components/home/WorkDetails'
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

  // Only the reviewer's own choices are stored. Everything else follows `defaultOpen`, so a group
  // that fills in after the first render still opens or stays closed by the same rule.
  const [toggled, setToggled] = useState<ReadonlyMap<HomeGroupKey, boolean>>(() => new Map())
  const openGroups = useMemo(() => {
    const open = new Set<HomeGroupKey>()
    for (const key of GROUP_ORDER) {
      if (toggled.get(key) ?? defaultOpen(key, groups)) open.add(key)
    }
    return open
  }, [toggled, groups])
  function toggleGroup(key: HomeGroupKey) {
    setToggled((current) => new Map(current).set(key, !openGroups.has(key)))
  }
  function revealGroup(key: HomeGroupKey) {
    setToggled((current) => (openGroups.has(key) ? current : new Map(current).set(key, true)))
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

  // Device-local resumption. Read once when Home opens; the Review route writes it as it is visited.
  const [resume, setResume] = useState<ResumeRecord | null>(readResume)
  const reviewsListComplete = reviews.isSuccess && !reviews.data?.nextCursor
  const liveResumeItem = resume
    ? (items.find((item) => item.id === `review:${resume.reviewId}`) ?? null)
    : null
  // A complete read that lacks the saved Review proves it is gone. A truncated or failed read
  // proves nothing, so the saved place is kept and the Review route itself reports any problem.
  const resumeIsStale = resume !== null && reviewsListComplete && liveResumeItem === null
  useEffect(() => {
    if (resume && resumeIsStale) clearResumeFor(resume.reviewId)
  }, [resume, resumeIsStale])
  // Waits for the Review read so the card never flashes in and then disappears.
  const showResume = resume !== null && !reviewState.loading && !resumeIsStale
  const compact = useMediaQuery('(max-width: 1180px)')

  const loading = reviewState.loading || knowledgeState.loading
  // The headline describes all review work, so it waits for every read to settle.
  const resolved = !reviewState.loading && !knowledgeState.loading
  const showEmptyState = resolved && !reviewState.failed && !knowledgeState.failed && items.length === 0
  const moreReviewsExist = Boolean(reviews.data?.nextCursor)

  const groupProps = {
    openGroups,
    onToggle: toggleGroup,
    selectedId: effectiveId,
    onSelect: setSelectedId,
    compact,
  }

  return (
    <>
      <div className="rv-main">
        <TopBar
          title="Home"
          actions={
            <>
              <LinkButton to="/knowledge/new">
                <PlusIcon className="rv-icon" />
                Add knowledge
              </LinkButton>
              <LinkButton to="/reviews" variant="primary">
                <DocumentIcon className="rv-icon" />
                Start a review
              </LinkButton>
            </>
          }
        />
        <div className="rv-scroll">
          <div className="rv-wrap">
            <Hero
              resolved={resolved}
              headlineReady={!reviewState.failed}
              hasWork={items.length > 0}
              counts={{
                needs_action: groups.needsAction.length,
                running: groups.running.length,
                failed: groups.failed.length,
                extracted: groups.extracted.length,
              }}
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

            {showResume && resume ? (
              <ContinueCard
                record={resume}
                live={liveResumeItem}
                onDismiss={() => {
                  clearResume()
                  setResume(null)
                }}
              />
            ) : null}

            <GroupSection groupKey="needs_action" items={groups.needsAction} {...groupProps} />
            <GroupSection groupKey="running" items={groups.running} {...groupProps} />
            <GroupSection groupKey="failed" items={groups.failed} {...groupProps} />
            <GroupSection groupKey="extracted" items={groups.extracted} {...groupProps} />
            <GroupSection groupKey="completed" items={groups.completed} {...groupProps} />

            {moreReviewsExist ? (
              <p className="rv-note">
                Showing the newest {HOME_PAGE_LIMIT} reviews.{' '}
                <Link to="/reviews">Open Reviews for the full list.</Link>
              </p>
            ) : null}
          </div>
        </div>
      </div>
      <Inspector status={selected ? `${selected.title}. ${selected.stateLabel}.` : ''}>
        {selected ? <WorkDetails item={selected} /> : <WorkDetailsHint />}
      </Inspector>
    </>
  )
}

const GROUP_ORDER: readonly HomeGroupKey[] = ['needs_action', 'running', 'failed', 'extracted', 'completed']

const GROUP_ITEMS: Record<HomeGroupKey, (groups: HomeGroups) => HomeWorkItem[]> = {
  needs_action: (groups) => groups.needsAction,
  running: (groups) => groups.running,
  failed: (groups) => groups.failed,
  extracted: (groups) => groups.extracted,
  completed: (groups) => groups.completed,
}

/**
 * Work that needs a person, and work that failed, is always open. When there is none of either, the
 * first settled-or-moving group opens instead, so Home never greets the reviewer with a column of
 * closed cards. Everything else stays collapsed until asked for.
 */
function defaultOpen(key: HomeGroupKey, groups: HomeGroups): boolean {
  if (key === 'needs_action' || key === 'failed') return true
  if (groups.needsAction.length > 0 || groups.failed.length > 0) return false
  const first = (['running', 'extracted', 'completed'] as const).find(
    (candidate) => GROUP_ITEMS[candidate](groups).length > 0,
  )
  return key === first
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

type SummaryKey = 'needs_action' | 'running' | 'failed' | 'extracted'

const SUMMARY_PILLS: { key: SummaryKey; label: string; tone: 'attention' | 'active' | 'danger' | 'neutral' }[] = [
  { key: 'needs_action', label: 'need action', tone: 'attention' },
  { key: 'running', label: 'running', tone: 'active' },
  { key: 'failed', label: 'failed', tone: 'danger' },
  { key: 'extracted', label: 'extracted', tone: 'neutral' },
]

function Hero({
  resolved,
  headlineReady,
  hasWork,
  counts,
  onReveal,
}: {
  /** Data has arrived, so counts are safe to show. */
  resolved: boolean
  /** Every part of Rivell's work is readable, so a claim about all of it is safe. */
  headlineReady: boolean
  hasWork: boolean
  counts: Record<SummaryKey, number>
  onReveal: (group: HomeGroupKey) => void
}) {
  return (
    <div className="rv-hero">
      <div>
        <h1 className="rv-hero__title">Rivell</h1>
        {/* Claims nothing until every read resolves, and never duplicates the empty state. */}
        {resolved && headlineReady && hasWork ? (
          <p className="rv-hero__line">Here is where your work stands.</p>
        ) : null}
      </div>
      {resolved ? (
        <div className="rv-pills">
          {SUMMARY_PILLS.map(({ key, label, tone }) => (
            <SummaryPill
              key={key}
              count={counts[key]}
              label={key === 'needs_action' && counts[key] === 1 ? 'needs action' : label}
              tone={tone}
              targetId={key.replace('_', '-')}
              onReveal={() => onReveal(key)}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}

function GroupSection({
  groupKey,
  items,
  openGroups,
  onToggle,
  selectedId,
  onSelect,
  compact,
}: {
  groupKey: HomeGroupKey
  items: HomeWorkItem[]
  openGroups: ReadonlySet<HomeGroupKey>
  onToggle: (key: HomeGroupKey) => void
  selectedId: string | null
  onSelect: (id: string) => void
  /** The details panel is hidden at this width, so the selected row shows its details inline. */
  compact: boolean
}) {
  if (items.length === 0) return null
  const open = openGroups.has(groupKey)
  const selectedHere = items.find((item) => item.id === selectedId) ?? null
  return (
    <div id={groupKey.replace('_', '-')} className="rv-anchor">
      <CollapsibleGroup
        title={HOME_GROUP_LABELS[groupKey]}
        count={items.length}
        open={open}
        onToggle={() => onToggle(groupKey)}
        collapsedSummary={summarise(items)}
        footer={
          compact && open && selectedHere ? (
            <div className="rv-inline-detail">
              <WorkDetails item={selectedHere} />
            </div>
          ) : null
        }
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
