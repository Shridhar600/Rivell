import { useEffect, useMemo, useRef, type KeyboardEvent, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useContractQuery, isContractNotFoundError } from '../api/contracts'
import { useSemanticsWhen, useStructureWhen } from '../api/lifecycleReads'
import {
  isRedlineForbiddenError,
  isRedlineNotFoundError,
  RedlineApiError,
  useRedlineExecutionQuery,
  useRetryRedline,
  useTriggerRedline,
} from '../api/redline'
import {
  isReviewForbiddenError,
  isReviewNotFoundError,
  useFindingsQuery,
  useReviewPacksQuery,
  useReviewSummaryQuery,
} from '../api/reviews'
import {
  isWorkflowNotFoundError,
  useApprovedChangeSetQuery,
  useCurrentDecisionsQuery,
  useHumanReviewQuery,
} from '../api/workflow'
import { AnalysisQuality } from '../components/lifecycle/AnalysisQuality'
import { ChangesPanel } from '../components/lifecycle/ChangesPanel'
import { LifecycleRail } from '../components/lifecycle/LifecycleRail'
import { Journey, StatusPill } from '../components/lifecycle/parts'
import { RedlinePanel } from '../components/lifecycle/RedlinePanel'
import { ReportPanel } from '../components/lifecycle/ReportPanel'
import { StateCard } from '../components/lifecycle/StateCard'
import { Inspector, TopBar } from '../components/shell/TopBar'
import { LinkButton } from '../components/ui/Button'
import { buildRegionLabelMap } from '../lib/contractSemantics'
import { contractPackLabel } from '../lib/evidence'
import { dateTime } from '../lib/dates'
import { clauseBodyText, sideChip, typeChip } from '../lib/lifecycleView'
import { classifyTriggerError, redlineTriggerErrorNotice } from '../lib/redline'
import {
  REVIEWS_PATH,
  START_REVIEW_PATH,
  activityEvents,
  isPostFinalize,
  journey,
  lifecycleKey,
  lifecyclePath,
  lifecycleStatus,
  lifecycleTabs,
  opensWorkspace,
  resolveTab,
  workspacePath,
  type LifecycleTab,
} from '../lib/reviewLifecycle'
import { useHeadingFocus } from '../lib/useHeadingFocus'
import type { ApprovedChange } from '../api/workflow'

/**
 * The surface between the Reviews list and the Review Workspace: one Review's lifecycle, from
 * queued analysis to a verified redline. Ported from the `#/r/:id` family in
 * `rivell-reviews-v1.html`. It reads server state and shows it. Finding decisions and Finalize stay
 * in the Workspace, and every action here either leads there or runs the existing redline request.
 */
export function ReviewLifecyclePage() {
  const { contractId = '', reviewId = '', tab: requestedTab } = useParams()
  const navigate = useNavigate()

  const summaryQuery = useReviewSummaryQuery(reviewId)
  const contractQuery = useContractQuery(contractId)
  const summary = summaryQuery.data
  const pinnedVersion = summary?.contractVersionNumber ?? undefined
  const completed = summary?.status === 'COMPLETED'

  const humanReviewQuery = useHumanReviewQuery(reviewId, completed)
  const humanReview = humanReviewQuery.data ?? null
  const finalized = humanReview?.status === 'COMPLETED'
  const changeSetQuery = useApprovedChangeSetQuery(reviewId, finalized)
  const redlineQuery = useRedlineExecutionQuery(reviewId, finalized)
  const trigger = useTriggerRedline(reviewId)
  const retry = useRetryRedline(reviewId)
  const findingsQuery = useFindingsQuery(reviewId, {}, completed)
  const decisionsQuery = useCurrentDecisionsQuery(reviewId, finalized)
  const packsQuery = useReviewPacksQuery()

  const execution = redlineQuery.data ?? null
  const redlineNotStarted = redlineQuery.isError && isRedlineNotFoundError(redlineQuery.error)
  const changeSet = changeSetQuery.data ?? null

  const redlineRunning = trigger.isPending || retry.isPending
  const facts = summary
    ? { summary, humanReview, changeSet, redline: execution, redlineRunning }
    : null
  const key = facts ? lifecycleKey(facts) : null
  const tab: LifecycleTab = key ? resolveTab(key, requestedTab) : 'redline'

  const needsClauseDetail = key !== null && isPostFinalize(key) && (tab === 'changes' || tab === 'report')
  const semanticsQuery = useSemanticsWhen(contractId, pinnedVersion, completed)
  const structureQuery = useStructureWhen(contractId, pinnedVersion, needsClauseDetail)
  const semantics = semanticsQuery.data ?? null
  const regionLabels = useMemo(
    () => buildRegionLabelMap(semantics?.clauses ?? [], structureQuery.data?.nodes ?? []),
    [semantics, structureQuery.data],
  )

  const pinnedFilename = summary?.document?.contractFilename ?? null

  const profile = summary?.snapshot?.profile ?? null
  const packKey = profile?.contractPackKey ?? null
  const packLabel = packKey
    ? (packsQuery.data?.find((pack) => pack.key === packKey)?.displayName ?? contractPackLabel(packKey))
    : null

  const headingRef = useHeadingFocus<HTMLHeadingElement>(summary?.id)
  const redlineHeadingRef = useRef<HTMLHeadingElement>(null)
  const wasRunning = useRef(false)
  useEffect(() => {
    // After a generation the result replaces the Generate card. Moving focus to its heading lets a
    // keyboard or screen-reader user land on what happened instead of on a vanished button.
    if (wasRunning.current && !redlineRunning) redlineHeadingRef.current?.focus()
    wasRunning.current = redlineRunning
  }, [redlineRunning])

  const title = pinnedFilename ?? contractQuery.data?.title ?? 'Contract review'
  const workspaceTo = workspacePath(contractId, reviewId)

  const crumbs = (
    <nav className="rv-crumbs" aria-label="Breadcrumb">
      <Link to={REVIEWS_PATH}>Contract reviews</Link>
      <span className="rv-crumbs__sep" aria-hidden="true">
        /
      </span>
      <b aria-current="page">{summary ? title : 'Review'}</b>
    </nav>
  )

  const shell = (content: ReactNode, rail?: ReactNode) => (
    <>
      <div className="rv-main">
        <TopBar
          title={crumbs}
          actions={
            <LinkButton to={START_REVIEW_PATH} variant="primary" compact>
              Start review
            </LinkButton>
          }
        />
        <div className="rv-scroll">
          <div className="rv-lc">{content}</div>
        </div>
      </div>
      <Inspector status="">{rail ?? <p className="rv-insp__note">Review details appear once the review has loaded.</p>}</Inspector>
    </>
  )

  if (!contractId || !reviewId) {
    return shell(<Notice tone="error" message="Missing contract or review id." />)
  }
  if (summaryQuery.isPending) return shell(<Notice message="Loading review…" />)

  const mismatched = summary !== undefined && summary.contractId.toLowerCase() !== contractId.toLowerCase()
  if (isReviewNotFoundError(summaryQuery.error) || isContractNotFoundError(contractQuery.error) || mismatched) {
    return shell(
      <Notice
        tone="error"
        message="This review could not be found. It may not exist, or you may not have access to it."
        back
      />,
    )
  }
  if (isReviewForbiddenError(summaryQuery.error)) {
    return shell(<Notice tone="error" message="You do not have permission to read this review." back />)
  }
  if (summaryQuery.isError || !summary || !facts) {
    return shell(
      <Notice tone="error" message="This review could not be loaded." onRetry={() => void summaryQuery.refetch()} />,
    )
  }

  const status = key ? lifecycleStatus(key) : null
  const type = typeChip(profile)
  const side = sideChip(profile)
  const humanReadFailed = completed && humanReviewQuery.isError
  const changeSetFailed = finalized && changeSetQuery.isError && !isWorkflowNotFoundError(changeSetQuery.error)
  const redlineFailedToLoad = finalized && redlineQuery.isError && !redlineNotStarted
  const redlineForbidden = redlineQuery.isError && isRedlineForbiddenError(redlineQuery.error)

  const failedTrigger = trigger.isError ? trigger : retry.isError ? retry : null
  const triggerKind = failedTrigger
    ? classifyTriggerError({
        status: failedTrigger.error instanceof RedlineApiError ? failedTrigger.error.status : null,
        execution,
        executionStateKnown: redlineNotStarted || redlineQuery.isSuccess,
      })
    : null
  // A recorded failure is shown by the failed-redline card itself, so it needs no second notice.
  const triggerNotice = triggerKind && triggerKind !== 'FAILED_EXECUTION' ? redlineTriggerErrorNotice(triggerKind) : null

  const findings = findingsQuery.data?.items ?? []
  const findingsById = new Map(findings.map((finding) => [finding.id, finding]))
  const decisionsByFindingId = new Map((decisionsQuery.data?.items ?? []).map((decision) => [decision.findingId, decision]))

  const clauseText = (change: ApprovedChange): string | null => {
    const nodes = structureQuery.data?.nodes
    return nodes && change.targetHeadingNodeId ? clauseBodyText(nodes, change.targetHeadingNodeId) : null
  }

  const goToTab = (next: LifecycleTab) => navigate(lifecyclePath(contractId, reviewId, next))

  const tabs = key ? lifecycleTabs(key) : []
  let body: ReactNode
  if (humanReadFailed) {
    body = (
      <Notice
        tone="error"
        message="Human review state could not be loaded, so this review cannot be placed in its lifecycle."
        onRetry={() => void humanReviewQuery.refetch()}
      />
    )
  } else if (changeSetFailed) {
    body = (
      <Notice
        tone="error"
        message="The approved change set could not be loaded."
        onRetry={() => void changeSetQuery.refetch()}
      />
    )
  } else if (redlineFailedToLoad) {
    body = redlineForbidden ? (
      <Notice tone="error" message="You do not have permission to read the redline for this review." />
    ) : (
      <Notice
        tone="error"
        message="The redline execution could not be loaded."
        onRetry={() => void redlineQuery.refetch()}
      />
    )
  } else if (!key) {
    body = <Notice message="Loading review state…" />
  } else if (!isPostFinalize(key)) {
    body = (
      <>
        <StateCard
          state={key}
          summary={summary}
          humanReview={humanReview}
          findings={findings}
          findingsComplete={findingsQuery.isSuccess && !findingsQuery.data.truncated}
          semantics={semantics}
          workspaceTo={workspaceTo}
          onRefresh={() => void summaryQuery.refetch()}
        />
        {completed ? <AnalysisQuality summary={summary} /> : null}
      </>
    )
  } else if (changeSet && humanReview) {
    body = (
      <>
        <div className="rv-lc-tabs" role="tablist" aria-label="Review result">
          {tabs.map((entry) => (
            <button
              key={entry.id}
              type="button"
              role="tab"
              id={`lc-tab-${entry.id}`}
              aria-selected={tab === entry.id}
              aria-controls="lc-panel"
              tabIndex={tab === entry.id ? 0 : -1}
              onClick={() => goToTab(entry.id)}
              onKeyDown={(event) => onTabKey(event, tabs, tab, goToTab)}
            >
              {entry.label}
            </button>
          ))}
        </div>
        <div id="lc-panel" role="tabpanel" aria-labelledby={`lc-tab-${tab}`} className="rv-lc-panel">
          {tab === 'changes' ? (
            <ChangesPanel
              key={reviewId}
              contractId={contractId}
              reviewId={reviewId}
              changeSet={changeSet}
              humanReview={humanReview}
              findingsById={findingsById}
              decisionsByFindingId={decisionsByFindingId}
              regionLabels={regionLabels}
              clauseText={clauseText}
              structure={structureQuery.isError ? 'error' : structureQuery.isSuccess ? 'ready' : 'loading'}
            />
          ) : tab === 'report' && execution ? (
            <ReportPanel
              execution={execution}
              approvedChanges={changeSet.changes}
              findingsById={findingsById}
              regionLabels={regionLabels}
            />
          ) : (
            <RedlinePanel
              state={key}
              reviewId={reviewId}
              contractVersion={summary.contractVersionNumber}
              sourceFilename={pinnedFilename}
              changeSet={changeSet}
              execution={execution}
              generating={redlineRunning}
              notice={triggerNotice}
              onGenerate={() => trigger.mutate()}
              onRetry={() => retry.mutate()}
              onOpenReport={() => goToTab('report')}
              headingRef={redlineHeadingRef}
            />
          )}
        </div>
      </>
    )
  } else {
    body = <Notice message="Loading the approved change set…" />
  }

  return shell(
    <>
      <header className="rv-lc-head">
        <div>
          <h1 id="review-heading" tabIndex={-1} ref={headingRef}>
            {title}
          </h1>
          <div className="rv-lc-meta">
            {status ? <StatusPill status={status} /> : null}
            {summary.contractVersionNumber !== null ? <span className="rv-chip">v{summary.contractVersionNumber}</span> : null}
            {type ? <span className="rv-chip">{type}</span> : null}
            {side ? <span className="rv-chip">{side}</span> : null}
            <span className="rv-lc-quiet">
              Review created <time dateTime={summary.createdAt}>{dateTime(summary.createdAt)}</time>
            </span>
          </div>
        </div>
        {key && opensWorkspace(key) ? (
          <div className="rv-lc-head__actions">
            <LinkButton to={workspaceTo}>Open Review Workspace</LinkButton>
          </div>
        ) : null}
      </header>
      {key ? <Journey steps={journey(key, humanReview)} /> : null}
      {body}
    </>,
    <LifecycleRail summary={summary} document={summary.document} packLabel={packLabel} events={activityEvents(facts)} />,
  )
}

/** Arrow keys, Home and End move between tabs, as a tab list is expected to. */
function onTabKey(
  event: KeyboardEvent<HTMLButtonElement>,
  tabs: { id: LifecycleTab }[],
  current: LifecycleTab,
  go: (tab: LifecycleTab) => void,
) {
  const index = tabs.findIndex((entry) => entry.id === current)
  const next =
    event.key === 'ArrowRight'
      ? (index + 1) % tabs.length
      : event.key === 'ArrowLeft'
        ? (index - 1 + tabs.length) % tabs.length
        : event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? tabs.length - 1
            : null
  if (next === null) return
  event.preventDefault()
  go(tabs[next].id)
  requestAnimationFrame(() => document.getElementById(`lc-tab-${tabs[next].id}`)?.focus())
}

function Notice({
  message,
  tone,
  onRetry,
  back,
}: {
  message: string
  tone?: 'error'
  onRetry?: () => void
  back?: boolean
}) {
  return (
    <div className={`rv-lc-notice${tone === 'error' ? ' rv-lc-notice--error' : ''}`} role={tone === 'error' ? 'alert' : 'status'}>
      <p>{message}</p>
      {onRetry ? (
        <button type="button" className="rv-btn rv-btn--compact" onClick={onRetry}>
          Try again
        </button>
      ) : null}
      {back ? (
        <Link className="rv-lc-lnk" to={REVIEWS_PATH}>
          Back to reviews
        </Link>
      ) : null}
    </div>
  )
}
