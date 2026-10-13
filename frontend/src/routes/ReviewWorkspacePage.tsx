import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import {
  isContractForbiddenError,
  isContractNotFoundError,
  useContractQuery,
  useContractStructureQuery,
  type ContractSummary,
} from '../api/contracts'
import {
  isReviewForbiddenError,
  isReviewNotFoundError,
  isReviewTerminal,
  useFindingQuery,
  useClauseMarksQuery,
  useFindingsQuery,
  useReviewSummaryQuery,
  type ReviewSummary,
} from '../api/reviews'
import {
  isWorkflowConflictError,
  isWorkflowNotFoundError,
  useAddFinding,
  useApprovedChangeSetQuery,
  useCurrentDecisionsQuery,
  useFinalizeReview,
  useHumanReviewQuery,
  workflowErrorMessage,
  workflowKeys,
  type AddFindingInput,
  type DecisionListResponse,
} from '../api/workflow'
import { useSemanticsQuery } from '../api/semantics'
import { useTaxonomy } from '../api/taxonomy'
import { buildRegionLabelMap, CONTRACT_TYPE_LABELS } from '../lib/contractSemantics'
import { clearResumeFor, writeResume } from '../lib/resume'
import { coverageLimitationSummary, coverageLimitations, findingSubjectLabel, hasNewerRun, runStatusLabel } from '../lib/reviews'
import {
  defaultFinding,
  executableChange,
  finalizeSummary,
  findingState,
  isActionable,
  nextOpenFinding,
  orderForReview,
  profileLine,
  regionText,
} from '../lib/workspace'
import { AnalysisWarnings, ReviewLifecyclePanel } from '../components/review/ReviewLifecyclePanel'
import { ReviewContext } from '../components/review/ReviewContext'
import { ReviewCoveragePanel } from '../components/review/ReviewCoveragePanel'
import { readPanelFlag, writePanelFlag } from '../components/shell/ShellContext'
import { ErrorState, LoadingState } from '../components/ui/State'
import { CommandPalette, type PaletteItem } from '../components/workspace/CommandPalette'
import { ContractPane, type DocMode } from '../components/workspace/ContractPane'
import { DecisionHistorySheet } from '../components/workspace/DecisionHistorySheet'
import { FindingNavigator } from '../components/workspace/FindingNavigator'
import { FindingPanel, SUMMARY_LINK_ID } from '../components/workspace/FindingPanel'
import { FinalizeDialog } from '../components/workspace/FinalizeDialog'
import { SourceContent } from '../components/workspace/SourceContent'
import { LinkButton } from '../components/ui/Button'
import { sourceLink } from '../lib/evidence'
import { useDecisionFlow } from '../components/workspace/useDecisionFlow'
import { WorkspaceSheet } from '../components/workspace/WorkspaceSheet'
import { FROZEN_NOTE_ID, WorkspaceTopBar } from '../components/workspace/WorkspaceTopBar'
import {
  CheckIcon,
  ClauseIcon,
  EditIcon,
  EscalateIcon,
  CloseIcon,
  LockIcon,
} from '../components/workspace/WorkspaceIcons'
import { BookIcon, DocumentIcon, HomeIcon } from '../components/ui/Icon'
import '../styles/m10b-workspace.css'

const LEFT_KEY = 'rv-l'
const RIGHT_KEY = 'rv-r'
const MODE_CYCLE: Record<DocMode, DocMode> = { changes: 'orig', orig: 'final', final: 'changes' }

type Sheet = { kind: 'source'; evidenceId: string } | { kind: 'history' } | { kind: 'notes' }

function Gate({ children }: { children: ReactNode }) {
  return (
    <div className="rv-ws-gate">
      {children}
      <Link className="rv-btn" to="/">
        Back to Home
      </Link>
    </div>
  )
}

/**
 * The route needs both identities to mean anything, and the review has to belong to the contract in
 * the URL. Only then is the workspace drawn, so everything inside can rely on a loaded review.
 */
export function ReviewWorkspacePage() {
  const { contractId = '', reviewId = '' } = useParams()
  const contractQuery = useContractQuery(contractId)
  const summaryQuery = useReviewSummaryQuery(reviewId)

  // Device-local "Continue where you left off". It is saved only once this exact Review has loaded
  // for this exact contract, so Home never offers a place the reviewer did not really reach.
  const summary = summaryQuery.data
  const reviewLoaded = summary !== undefined && summary.contractId.toLowerCase() === contractId.toLowerCase()
  const resumeFilename = contractQuery.data?.latestVersion.filename
  const pinnedVersion = summary?.contractVersionNumber ?? null
  useEffect(() => {
    if (!reviewId || !contractId || !reviewLoaded || !resumeFilename) return
    writeResume({ reviewId, contractId, filename: resumeFilename, versionNumber: pinnedVersion, visitedAt: new Date().toISOString() })
  }, [reviewId, contractId, reviewLoaded, resumeFilename, pinnedVersion])
  const reviewMissing = isReviewNotFoundError(summaryQuery.error)
  useEffect(() => {
    // A Review that no longer exists cannot be continued, so a saved place pointing at it is stale.
    if (reviewMissing && reviewId) clearResumeFor(reviewId)
  }, [reviewMissing, reviewId])

  if (!contractId || !reviewId) {
    return <Gate><ErrorState message="Missing contract or review id." /></Gate>
  }
  if (contractQuery.isPending || summaryQuery.isPending) {
    return <Gate><LoadingState label="Loading review…" /></Gate>
  }
  if (reviewMissing || isContractNotFoundError(contractQuery.error) || (summary !== undefined && !reviewLoaded)) {
    // Permanent: an identical retry cannot make a missing contract or review appear.
    return (
      <Gate>
        <ErrorState message="This review could not be found. It may not exist, or you may not have access to it." />
      </Gate>
    )
  }
  if (isReviewForbiddenError(summaryQuery.error)) {
    return <Gate><ErrorState message="You do not have permission to read this review." /></Gate>
  }
  // contracts:read is a separate permission from reviews:read, so a refused contract read is a
  // contract problem and must not be reported as a refused review.
  if (isContractForbiddenError(contractQuery.error)) {
    return <Gate><ErrorState message="You do not have permission to read the contract this review belongs to." /></Gate>
  }
  if (summaryQuery.isError || !summary || contractQuery.isError || !contractQuery.data) {
    return (
      <Gate>
        <ErrorState
          message="This review could not be loaded."
          onRetry={() => {
            void summaryQuery.refetch()
            void contractQuery.refetch()
          }}
        />
      </Gate>
    )
  }

  return (
    <Workspace
      contractId={contractId}
      reviewId={reviewId}
      contract={contractQuery.data}
      summary={summary}
      onRefresh={() => void summaryQuery.refetch()}
    />
  )
}

interface WorkspaceProps {
  contractId: string
  reviewId: string
  contract: ContractSummary
  summary: ReviewSummary
  onRefresh: () => void
}

function Workspace({ contractId, reviewId, contract, summary, onRefresh }: WorkspaceProps) {
  const { labelFor } = useTaxonomy()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()

  const [leftOpen, setLeftOpen] = useState(() => !readPanelFlag(LEFT_KEY, true))
  const [rightOpen, setRightOpen] = useState(() => !readPanelFlag(RIGHT_KEY, true))
  const [mode, setMode] = useState<DocMode>('changes')
  const [term, setTerm] = useState('')
  const [sheet, setSheet] = useState<Sheet | null>(null)
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [finalizeOpen, setFinalizeOpen] = useState(false)
  // An evidence jump target in this workspace's own document, so "View in document" never leaves it.
  const [docFocus, setDocFocus] = useState<{ nodeId: string } | null>(null)
  // Set when a Finalize succeeds, so focus can move on once the dialog is closed and the page shows
  // the frozen state. A ref, because moving focus needs no render of its own.
  const focusFrozen = useRef(false)

  const pinnedVersion = summary.contractVersionNumber ?? undefined
  const latestVersion = contract.latestVersion.versionNumber
  const structureQuery = useContractStructureQuery(contractId, pinnedVersion)
  const semanticsQuery = useSemanticsQuery(contractId, pinnedVersion)
  const nodes = structureQuery.data?.nodes

  const status = summary.status
  const analysisCompleted = status === 'COMPLETED'
  const canReadFindings = isReviewTerminal(status) || status === 'UNKNOWN'
  const findingsQuery = useFindingsQuery(reviewId, {}, canReadFindings)
  const findings = useMemo(() => orderForReview(findingsQuery.data?.items ?? []), [findingsQuery.data])

  const clauseMarksQuery = useClauseMarksQuery(reviewId, analysisCompleted)
  const clauseMarks = useMemo(() => {
    const data = clauseMarksQuery.data
    if (!data || data.runId === null) return null
    return new Map(data.clauses.map((clause) => [clause.headingNodeId, clause.mark]))
  }, [clauseMarksQuery.data])
  const addFindingMutation = useAddFinding(reviewId ?? '')
  const decisionsQuery = useCurrentDecisionsQuery(reviewId, analysisCompleted)
  const humanReviewQuery = useHumanReviewQuery(reviewId, analysisCompleted)
  const humanReview = humanReviewQuery.data ?? null
  const humanReviewUnavailable = analysisCompleted && humanReviewQuery.isError
  const finalizeMutation = useFinalizeReview(reviewId)
  const changeSetQuery = useApprovedChangeSetQuery(
    reviewId,
    analysisCompleted && (humanReview?.status === 'COMPLETED' || humanReviewUnavailable),
  )
  // A change set exists only once finalization has run, so a successful read is proof of
  // finalization. It is the fallback when the human-review read failed.
  const isFinalized = humanReview?.status === 'COMPLETED' || changeSetQuery.isSuccess
  const changeSetReadFailed = changeSetQuery.isError && !isWorkflowNotFoundError(changeSetQuery.error)
  const decisionsLoaded = decisionsQuery.isSuccess
  const decisions = useMemo(
    () => new Map((decisionsQuery.data?.items ?? []).map((decision) => [decision.findingId, decision])),
    [decisionsQuery.data],
  )

  const regionLabels = useMemo(
    () => buildRegionLabelMap(semanticsQuery.data?.clauses ?? [], nodes ?? []),
    [semanticsQuery.data, nodes],
  )
  const clauseHeadings = useMemo(
    () => new Map((semanticsQuery.data?.clauses ?? []).map((clause) => [clause.id, clause.headingNodeId])),
    [semanticsQuery.data],
  )

  // The URL names the finding, so a reload or a link lands on the same one. With no finding named,
  // the first that still needs a decision is shown, once decisions are known.
  const selectedParam = searchParams.get('finding')
  const decisionsSettled = !analysisCompleted || !decisionsQuery.isPending
  const fallback = decisionsSettled ? defaultFinding(findings, decisions) : null
  const named = selectedParam !== null && (findings.some((f) => f.id === selectedParam) || findingsQuery.data?.truncated === true)
  const selectedId = named ? selectedParam : (fallback?.id ?? null)
  const selectFinding = useCallback(
    (findingId: string) => {
      setSearchParams(
        (current) => {
          const next = new URLSearchParams(current)
          next.set('finding', findingId)
          return next
        },
        { replace: true },
      )
    },
    [setSearchParams],
  )
  // A reviewer picking a finding leaves the evidence highlight behind; the URL fallback above is
  // not a pick, so it does not need this.
  const chooseFinding = useCallback(
    (findingId: string) => {
      setDocFocus(null)
      selectFinding(findingId)
    },
    [selectFinding],
  )
  useEffect(() => {
    if (selectedParam === null && fallback) selectFinding(fallback.id)
  }, [selectedParam, fallback, selectFinding])

  const findingQuery = useFindingQuery(reviewId, canReadFindings ? selectedId : null)
  const finding = findingQuery.data
  const decision = selectedId ? (decisions.get(selectedId) ?? null) : null
  const index = findings.findIndex((f) => f.id === selectedId)
  const headingNodeId = finding?.subjectHeadingNodeId
  const currentText = useMemo(
    () => (headingNodeId && nodes ? regionText(nodes, headingNodeId) : null),
    [headingNodeId, nodes],
  )

  const flow = useDecisionFlow({
    reviewId,
    finding,
    decision,
    humanReview,
    analysisComplete: analysisCompleted,
    decisionsLoaded,
    onSaved: (savedId) => {
      const fresh = queryClient.getQueryData<DecisionListResponse>(workflowKeys.decisions(reviewId))
      const latest = new Map((fresh?.items ?? []).map((d) => [d.findingId, d]))
      const next = nextOpenFinding(findings, latest, savedId)
      if (next && next.id !== savedId) chooseFinding(next.id)
    },
  })
  const canDecideNow = analysisCompleted && humanReview !== null && decisionsLoaded && !isFinalized && !flow.readOnly && finding !== undefined
  // A finding Rivell missed can be added wherever a decision could be made: never once finalized.
  const canAddFinding = analysisCompleted && humanReview !== null && !isFinalized && !flow.readOnly
  const addFinding = useCallback(
    async (input: AddFindingInput) => {
      const findingId = await addFindingMutation.mutateAsync(input)
      setDocFocus(null)
      selectFinding(findingId)
    },
    [addFindingMutation, selectFinding],
  )

  function togglePanel(side: 'l' | 'r') {
    const set = side === 'l' ? setLeftOpen : setRightOpen
    const current = side === 'l' ? leftOpen : rightOpen
    set(!current)
    writePanelFlag(side === 'l' ? LEFT_KEY : RIGHT_KEY, current)
  }

  // The Finalize button the dialog was opened from is disabled once the review is frozen, which
  // would drop focus to the page. It moves to the next action instead: the review summary link. When
  // the detail panel is closed that link is hidden, so the always-visible frozen status takes focus.
  useEffect(() => {
    if (!focusFrozen.current || !isFinalized || finalizeOpen) return
    focusFrozen.current = false
    const link = rightOpen ? document.getElementById(SUMMARY_LINK_ID) : null
    ;(link ?? document.getElementById(FROZEN_NOTE_ID))?.focus()
  }, [isFinalized, finalizeOpen, rightOpen])

  function startFinalize() {
    if (humanReview?.readyToFinalize && !isFinalized) {
      finalizeMutation.reset()
      setFinalizeOpen(true)
    }
  }

  function showInDocument() {
    // The same reveal the evidence jump uses, on the selected finding's own clause.
    if (headingNodeId) {
      showInDocumentAt(headingNodeId)
      return
    }
    setMode('changes')
    setTerm('')
    window.requestAnimationFrame(() =>
      document.querySelector('.rv-ws-page [data-sel="1"]')?.scrollIntoView({ block: 'center', behavior: 'smooth' }),
    )
  }

  // Bring the selected finding's place in the contract into view when the selection changes.
  useEffect(() => {
    if (!selectedId) return
    document.querySelector('.rv-ws-page [data-sel="1"]')?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [selectedId, nodes])

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const typing = (event.target as HTMLElement | null)?.closest('input, textarea, select, [contenteditable="true"]')
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setPaletteOpen((open) => !open)
        return
      }
      if (typing || event.metaKey || event.ctrlKey || event.altKey || paletteOpen || finalizeOpen || sheet) return
      const key = event.key.toLowerCase()
      const step = (by: number) => {
        event.preventDefault()
        const target = findings[Math.min(findings.length - 1, Math.max(0, index + by))]
        if (target) chooseFinding(target.id)
      }
      if (key === 'j' || event.key === 'ArrowDown') step(1)
      else if (key === 'k' || event.key === 'ArrowUp') step(-1)
      else if (key === 'a' && canDecideNow) flow.decide('ACCEPT')
      else if (key === 'e' && canDecideNow) {
        event.preventDefault()
        flow.decide('EDIT')
      } else if (key === 'r' && canDecideNow) {
        event.preventDefault()
        flow.decide('REJECT')
      } else if (key === 's' && canDecideNow) {
        event.preventDefault()
        flow.decide('ESCALATE')
      } else if (key === 'v') {
        setMode((current) => MODE_CYCLE[current])
        setTerm('')
      } else if (event.key === '[') togglePanel('l')
      else if (event.key === ']') togglePanel('r')
      else if (event.key === '/') {
        event.preventDefault()
        setPaletteOpen(true)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  })

  const segments = findings
    .filter(isActionable)
    .map((f) => {
      const tone = findingState(f, decisions.get(f.id)).tone
      return tone === 'resolved' || tone === 'rejected' ? 'd' : tone === 'escalated' ? 'x' : ''
    })

  const paletteItems: PaletteItem[] = [
    ...findings.map((f) => ({
      group: 'Findings',
      label: findingSubjectLabel(f, regionLabels, labelFor),
      hint: decisionsLoaded ? findingState(f, decisions.get(f.id)).label : undefined,
      icon: <DocumentIcon className="rv-icon" />,
      run: () => chooseFinding(f.id),
    })),
    ...(canDecideNow
      ? ([
          ['ACCEPT', 'Accept', 'A', <CheckIcon key="a" />],
          ['EDIT', 'Edit', 'E', <EditIcon key="e" />],
          ['REJECT', 'Reject', 'R', <CloseIcon key="r" />],
          ['ESCALATE', 'Escalate', 'S', <EscalateIcon key="s" />],
        ] as const)
          .filter(([kind]) => kind !== 'ACCEPT' || flow.acceptEligibility.eligible)
          .map(([kind, label, key, icon]) => ({
            group: 'This Finding',
            label: `${label} finding`,
            key,
            icon,
            run: () => flow.decide(kind),
          }))
      : []),
    ...([
      ['changes', 'Show changes', 'V'],
      ['orig', 'Show original document', ''],
      ['final', isFinalized ? 'Show approved preview' : 'Show decision preview', ''],
    ] as const).map(([target, label, key]) => ({
      group: 'Document',
      label,
      key: key || undefined,
      icon: <DocumentIcon className="rv-icon" />,
      run: () => {
        setMode(target)
        setTerm('')
      },
    })),
    ...(finding?.evidenceItems ?? []).map((item) => ({
      group: 'Sources',
      label: `Source: ${item.legalConcept ? labelFor(item.legalConcept) : item.sourceType}`,
      icon: <BookIcon className="rv-icon" />,
      run: () => setSheet({ kind: 'source', evidenceId: item.evidenceId }),
    })),
    ...(humanReview?.readyToFinalize && !isFinalized
      ? [{ group: 'Review', label: 'Finalize review', icon: <LockIcon />, run: startFinalize }]
      : []),
    { group: 'Review', label: 'Review context, coverage and notes', icon: <ClauseIcon />, run: () => setSheet({ kind: 'notes' }) },
    { group: 'Go to', label: 'Home', icon: <HomeIcon className="rv-icon" />, run: () => void navigate('/') },
    { group: 'Go to', label: 'Contract reviews', icon: <DocumentIcon className="rv-icon" />, run: () => void navigate('/reviews') },
    { group: 'Go to', label: 'Knowledge', icon: <BookIcon className="rv-icon" />, run: () => void navigate('/knowledge') },
  ]

  const finalizeLines = humanReview
    ? finalizeSummary(
        humanReview,
        decisionsQuery.data && !decisionsQuery.data.truncated
          ? findings.flatMap((f) => executableChange(decisions.get(f.id)) ?? [])
          : null,
      )
    : []
  const finalizeError = finalizeMutation.isError
    ? isWorkflowConflictError(finalizeMutation.error)
      ? humanReview?.readyToFinalize
        ? 'The server did not finalize the review. Its state changed; check the summary and try again.'
        : 'The review cannot be finalized yet. The server’s current blockers are shown above.'
      : workflowErrorMessage(finalizeMutation.error, 'The review could not be finalized. Check your connection and try again.')
    : null

  const noteLines: string[] = []
  if (pinnedVersion !== undefined && pinnedVersion !== latestVersion) {
    noteLines.push(`This review is pinned to v${pinnedVersion}; the contract’s current version is v${latestVersion}.`)
  }
  const coverageNote = coverageLimitationSummary(summary.coverage, labelFor)
  if (coverageNote) noteLines.push(coverageNote)
  if (analysisCompleted && summary.failedChecks > 0) {
    noteLines.push('Some checks did not complete. Review the analysis warnings.')
  }
  if (analysisCompleted && hasNewerRun(summary)) {
    noteLines.push(
      `A newer analysis attempt exists (${runStatusLabel(summary.latestRunStatus ?? 'UNKNOWN')}). This page shows the latest completed analysis.`,
    )
  }
  if (summary.analysisWarnings.length > 0) {
    noteLines.push(`${summary.analysisWarnings.length} analysis ${summary.analysisWarnings.length === 1 ? 'warning' : 'warnings'}.`)
  }
  const retryNotes: [boolean, string, () => void][] = [
    [humanReviewUnavailable, 'Human review state could not be loaded, so decisions and finalization cannot be confirmed.', () => void humanReviewQuery.refetch()],
    [analysisCompleted && humanReview !== null && decisionsQuery.isError, 'Decisions could not be loaded.', () => void decisionsQuery.refetch()],
    [changeSetReadFailed, 'The approved change set could not be loaded.', () => void changeSetQuery.refetch()],
  ]
  const hasNotes = noteLines.length > 0 || retryNotes.some(([shown]) => shown)

  const notes = (
    <>
      {!analysisCompleted ? (
        <div className="rv-ws-lifecycle">
          <ReviewLifecyclePanel
            reviewId={reviewId}
            summary={summary}
            coverageLimited={coverageLimitations(summary.coverage).length > 0}
            onRefresh={onRefresh}
          />
        </div>
      ) : null}
      {hasNotes ? (
        <div className="rv-ws-banner" role="status">
          {noteLines.map((line) => (
            <div key={line}>{line}</div>
          ))}
          {retryNotes.map(([shown, text, retry]) =>
            shown ? (
              <div key={text}>
                {text}{' '}
                <button type="button" className="rv-ws-link" onClick={retry}>
                  Try again
                </button>
              </div>
            ) : null,
          )}
          <button type="button" className="rv-ws-link" onClick={() => setSheet({ kind: 'notes' })}>
            Review coverage and notes
          </button>
        </div>
      ) : null}
    </>
  )

  const sourceItem = sheet?.kind === 'source' ? finding?.evidenceItems.find((item) => item.evidenceId === sheet.evidenceId) : undefined
  const sourceRecord = sourceItem ? sourceLink(sourceItem) : null
  // "View in document" stays in this workspace: the clause is revealed in the document beside the
  // panel, keyed by the exact node the evidence recorded, on the version the review pinned.
  function showInDocumentAt(nodeId: string) {
    setSheet(null)
    setMode('changes')
    setTerm('')
    setDocFocus({ nodeId })
  }
  const goToClause = (headingNodeId: string) =>
    void navigate(`/reviews/${contractId}?${new URLSearchParams({ view: 'semantics', section: 'clauses', clause: headingNodeId })}`)
  const contractType = summary.snapshot?.profile?.contractType
  const pin = summary.snapshot?.knowledgePinNumber
  const context = [profileLine(summary.snapshot), pin ? `Knowledge publication ${pin}` : null].filter(Boolean).join('. ') || null

  return (
    <div className="rv-ws">
      <WorkspaceTopBar
        title={contract.title ?? 'Untitled contract'}
        chips={[
          ...(summary.contractVersionNumber !== null ? [`v${summary.contractVersionNumber}`] : []),
          ...(contractType ? [CONTRACT_TYPE_LABELS[contractType] ?? contractType] : []),
        ]}
        humanReview={analysisCompleted ? humanReview : null}
        isFinalized={isFinalized}
        segments={segments}
        leftOpen={leftOpen}
        rightOpen={rightOpen}
        onToggleLeft={() => togglePanel('l')}
        onToggleRight={() => togglePanel('r')}
        onOpenPalette={() => setPaletteOpen(true)}
        onFinalize={startFinalize}
      />
      <div className="rv-ws-work" data-l={leftOpen ? undefined : 'c'} data-r={rightOpen ? undefined : 'c'}>
        <FindingNavigator
          findings={findings}
          truncated={findingsQuery.data?.truncated ?? false}
          selectedId={selectedId}
          decisions={decisions}
          showState={analysisCompleted && humanReview !== null && decisionsLoaded}
          remaining={analysisCompleted && humanReview ? humanReview.remainingFindings : null}
          regionLabels={regionLabels}
          enabled={canReadFindings}
          analysisComplete={analysisCompleted}
          isPending={findingsQuery.isPending}
          isError={findingsQuery.isError}
          onRetry={() => void findingsQuery.refetch()}
          onSelect={chooseFinding}
        />
        <ContractPane
          title={contract.title ?? 'Untitled contract'}
          versionNumber={summary.contractVersionNumber}
          nodes={nodes}
          isPending={structureQuery.isPending}
          isError={structureQuery.isError}
          onRetry={() => void structureQuery.refetch()}
          mode={mode}
          onMode={(next) => {
            setMode(next)
            setTerm('')
          }}
          term={term}
          onClearTerm={() => setTerm('')}
          findings={findings}
          decisions={decisions}
          selectedId={selectedId}
          selected={finding}
          onSelect={chooseFinding}
          isFinalized={isFinalized}
          remaining={humanReview ? humanReview.remainingFindings : null}
          notes={notes}
          focusTarget={docFocus}
          marks={clauseMarks}
          onAddFinding={canAddFinding ? addFinding : null}
        />
        <FindingPanel
          finding={finding}
          hasSelection={selectedId !== null}
          isPending={selectedId !== null && findingQuery.isPending}
          isError={findingQuery.isError}
          onRetry={() => void findingQuery.refetch()}
          position={index >= 0 ? { index: index + 1, total: findings.length } : null}
          context={context}
          regionLabels={regionLabels}
          currentText={currentText}
          decision={decision}
          decisionsLoaded={decisionsLoaded}
          decisionsTruncated={decisionsQuery.data?.truncated ?? false}
          canDecide={analysisCompleted && humanReview !== null}
          analysisComplete={analysisCompleted}
          isFinalized={isFinalized}
          frozenChanges={changeSetQuery.data?.changes.length ?? null}
          flow={flow}
          onShowInDocument={showInDocument}
          onOpenSource={(evidenceId) => setSheet({ kind: 'source', evidenceId })}
          onOpenHistory={() => setSheet({ kind: 'history' })}
          summaryPath={`/reviews/${contractId}/overview/${reviewId}`}
        />
      </div>

      <WorkspaceSheet
        open={sheet !== null}
        title={sheet?.kind === 'history' ? 'History' : sheet?.kind === 'notes' ? 'Review notes' : 'Source'}
        onClose={() => setSheet(null)}
        actions={sourceRecord ? <LinkButton to={sourceRecord.to}>{sourceRecord.label}</LinkButton> : null}
      >
        {sheet?.kind === 'source' ? (
          sourceItem ? (
            <SourceContent
              item={sourceItem}
              regionLabels={regionLabels}
              clauseHeadings={clauseHeadings}
              onNavigateToNode={showInDocumentAt}
              onOpenClauseSemantics={pinnedVersion === latestVersion ? goToClause : undefined}
            />
          ) : (
            <p className="rv-ws-caption">This evidence is not part of the selected finding.</p>
          )
        ) : null}
        {sheet?.kind === 'history' && selectedId ? (
          <DecisionHistorySheet reviewId={reviewId} findingId={selectedId} title={finding ? findingSubjectLabel(finding, regionLabels, labelFor) : ''} />
        ) : null}
        {sheet?.kind === 'notes' ? (
          <>
            <ReviewContext summary={summary} findings={findings} />
            <ReviewCoveragePanel coverage={summary.coverage} />
            <AnalysisWarnings summary={summary} />
          </>
        ) : null}
      </WorkspaceSheet>

      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        items={paletteItems}
        onSearch={(value) => {
          setTerm(value)
          setMode('orig')
        }}
      />
      <FinalizeDialog
        open={finalizeOpen}
        onClose={() => setFinalizeOpen(false)}
        onConfirm={() =>
          finalizeMutation.mutate(undefined, {
            onSuccess: () => {
              focusFrozen.current = true
              setFinalizeOpen(false)
            },
          })
        }
        isFinalizing={finalizeMutation.isPending}
        error={finalizeError}
        summary={finalizeLines}
        contractLabel={`${contract.title ?? 'this contract'}${summary.contractVersionNumber !== null ? ` v${summary.contractVersionNumber}` : ''}`}
      />
    </div>
  )
}
