import { useMemo, useState } from 'react'
import { useOrganizationReviewsQuery } from '../api/reviews'
import { buildHomeSections, homeReview, type HomeSectionKey, type HomeSections } from '../lib/home'
import { useHomeResume } from '../lib/useHomeResume'
import { CREATE_ACTIONS } from '../components/shell/navigation'
import { useShell } from '../components/shell/ShellContext'
import { Inspector, TopBar } from '../components/shell/TopBar'
import { LinkButton } from '../components/ui/Button'
import { DocumentIcon, PlusIcon } from '../components/ui/Icon'
import { EmptyState, LoadingState } from '../components/ui/State'
import { ContinueCard } from '../components/home/ContinueCard'
import { HomeCoverageNote, ReviewReadProblem } from '../components/home/HomeNotices'
import { HomeHero } from '../components/home/HomeHero'
import { HomeSection } from '../components/home/HomeSection'
import { WorkDetails } from '../components/home/WorkDetails'

const SECTION_ORDER: readonly HomeSectionKey[] = ['needs', 'running', 'done']

/**
 * Home: start work, pick a Review back up, and see which Reviews need you, are running, or just
 * finished. It reads one bounded Review list and nothing else. Knowledge work is on Knowledge.
 */
export function HomePage() {
  const reviews = useOrganizationReviewsQuery()
  const sections = useMemo(() => buildHomeSections(reviews.data?.items ?? []), [reviews.data])
  const placed = useMemo(() => [...sections.needs, ...sections.running, ...sections.done], [sections])

  const [selectedId, setSelectedId] = useState<string | null>(null)
  // One effective id drives both the row highlight and the inspector, so they cannot disagree.
  // Nothing is auto-selected, and a selection that disappears after a refetch is dropped.
  const selected = placed.find((item) => item.id === selectedId) ?? null
  const { inspectorCollapsed, toggleInspector } = useShell()
  // Choosing a row is asking to see it, so a closed details panel opens.
  const select = (id: string) => {
    setSelectedId(id)
    if (inspectorCollapsed) toggleInspector()
  }

  // Only the reviewer's own choices are stored. Everything else follows `defaultOpen`, so a list
  // that fills in after the first render still opens or stays closed by the same rule.
  const [toggled, setToggled] = useState<ReadonlyMap<HomeSectionKey, boolean>>(() => new Map())
  const isOpen = (key: HomeSectionKey) => toggled.get(key) ?? defaultOpen(key, sections)
  const setOpen = (key: HomeSectionKey, open: boolean) => setToggled((current) => new Map(current).set(key, open))

  const settled = !reviews.isPending
  const complete = reviews.isSuccess && !reviews.data.nextCursor
  const resume = useHomeResume({
    settled,
    loadedIds: (reviews.data?.items ?? []).map((review) => review.id),
    complete,
  })
  // Looked up among every loaded Review, so a Review Home cannot place still shows its real state.
  const liveReview = resume.record ? reviews.data?.items.find((review) => review.id === resume.record?.reviewId) : undefined
  const live = liveReview ? homeReview(liveReview) : null
  const hasReviews = (reviews.data?.items.length ?? 0) > 0

  return (
    <>
      {/* The details panel opens only once a row is chosen, so Home never shows an empty panel. */}
      <div className={selected ? 'rv-main' : 'rv-main rv-main--wide'}>
        <TopBar
          title="Home"
          actions={
            <>
              <LinkButton to={CREATE_ACTIONS.addKnowledge}>
                <PlusIcon className="rv-icon" />
                Add knowledge
              </LinkButton>
              <LinkButton to={CREATE_ACTIONS.startReview} variant="primary">
                <DocumentIcon className="rv-icon" />
                Start review
              </LinkButton>
            </>
          }
        />
        <div className="rv-scroll">
          <div className="rv-wrap">
            <HomeHero
              resolved={reviews.isSuccess}
              hasWork={hasReviews}
              counts={{
                needs: sections.needs.length,
                analyzing: sections.running.filter((item) => !item.queued).length,
                queued: sections.running.filter((item) => item.queued).length,
              }}
              onReveal={(key) => setOpen(key, true)}
            />

            {reviews.isPending ? <LoadingState label="Loading your reviews" /> : null}
            {reviews.isError ? <ReviewReadProblem error={reviews.error} onRetry={() => void reviews.refetch()} /> : null}

            {reviews.isSuccess && !hasReviews ? (
              <EmptyState
                title="No reviews yet"
                message="Start a review to see its progress here."
                action={
                  <LinkButton to={CREATE_ACTIONS.startReview} variant="primary" compact>
                    <DocumentIcon className="rv-icon" />
                    Start review
                  </LinkButton>
                }
              />
            ) : null}

            {resume.show && resume.record ? <ContinueCard record={resume.record} live={live} onDismiss={resume.dismiss} /> : null}

            {SECTION_ORDER.map((key) => (
              <HomeSection
                key={key}
                section={key}
                items={sections[key]}
                open={isOpen(key)}
                onToggle={() => setOpen(key, !isOpen(key))}
                selectedId={selected?.id ?? null}
                onSelect={select}
                footer={key === 'done' ? resultsFooter(sections) : undefined}
              />
            ))}

            <HomeCoverageNote more={Boolean(reviews.data?.nextCursor)} unplaced={sections.unplaced} />
          </div>
        </div>
      </div>
      {selected ? (
        <Inspector status={`${selected.title}. ${selected.stateLabel}.`}>
          <WorkDetails item={selected} />
        </Inspector>
      ) : null}
    </>
  )
}

/**
 * Work that needs a person is always open. When there is none, the first non-empty list opens
 * instead, so Home never greets the reviewer with a column of closed cards.
 */
function defaultOpen(key: HomeSectionKey, sections: HomeSections): boolean {
  if (key === 'needs') return true
  if (sections.needs.length > 0) return false
  const first = (['running', 'done'] as const).find((candidate) => sections[candidate].length > 0)
  return key === first
}

function resultsFooter(sections: HomeSections): string | undefined {
  const hidden = sections.doneTotal - sections.done.length
  return hidden > 0 ? `${hidden} more finished ${hidden === 1 ? 'review is' : 'reviews are'} on the Reviews page.` : undefined
}
