import { useMemo, useState } from 'react'
import { useHomeData } from '../api/home'
import { longDate } from '../lib/dates'
import { boundedErrorMessage, TransportError } from '../api/errorMessage'
import {
  buildHomeGroups,
  knowledgeItemView,
  publicationItemView,
  reviewItemView,
  HOME_GROUP_LABELS,
  type HomeGroupKey,
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
  const items = useMemo<HomeWorkItem[]>(() => {
    const collected: HomeWorkItem[] = []
    if (reviews.availability === 'available') {
      for (const review of reviews.items) collected.push(reviewItemView(review))
    }
    for (const artifact of artifacts.data ?? []) collected.push(knowledgeItemView(artifact))
    for (const publication of publications.data ?? []) collected.push(publicationItemView(publication))
    return collected
  }, [reviews, artifacts.data, publications.data])

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

  const loading = artifacts.isPending || publications.isPending
  const failed = artifacts.isError || publications.isError
  const error = artifacts.error ?? publications.error ?? null
  const forbidden = statusOf(error) === 403
  const errorMessage = homeErrorMessage(error)

  return (
    <>
      <div className="rv-main">
        <TopBar title="Home" />
        <div className="rv-scroll">
          <div className="rv-wrap">
            <Hero
              resolved={!loading}
              headlineReady={reviews.availability === 'available'}
              needsAction={groups.needsAction.length}
              running={groups.running.length}
              failed={groups.failed.length}
              onReveal={revealGroup}
            />

            {reviews.availability === 'unavailable' ? (
              <UnavailableNotice
                title="Review work across contracts is not readable yet."
                to="/reviews"
                actionLabel="Start a review"
              >
                {reviews.reason} Start a review, or open Reviews to work on a contract you already
                have.
              </UnavailableNotice>
            ) : null}

            {loading ? <LoadingState label="Loading your work" /> : null}

            {failed ? (
              <ErrorState
                title={forbidden ? 'You do not have access to this knowledge' : 'Something went wrong'}
                tone={forbidden ? 'neutral' : 'danger'}
                message={errorMessage}
                onRetry={
                  forbidden
                    ? undefined
                    : () => {
                        void artifacts.refetch()
                        void publications.refetch()
                      }
                }
              />
            ) : null}

            {!loading && !failed && items.length === 0 && reviews.availability === 'available' ? (
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

/** Says what failed, what is intact, and what the user can do next. */
function homeErrorMessage(error: Error | null): string {
  if (error instanceof TransportError) {
    return 'Rivell could not be reached. Your work is unchanged. Check your connection and try again.'
  }
  if (statusOf(error) === 403) {
    return 'This Rivell deployment did not grant access to knowledge sources. Nothing was changed.'
  }
  const HOME_MESSAGES: Record<number, string> = {
    404: 'That knowledge could not be found.',
    500: 'Rivell could not complete that request.',
    503: 'The knowledge service is unavailable right now.',
  }
  const derived = boundedErrorMessage(
    statusOf(error) ?? 500,
    HOME_MESSAGES,
    'Your work could not be loaded right now.',
  )
  return `${derived} Your work is unchanged.`
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
