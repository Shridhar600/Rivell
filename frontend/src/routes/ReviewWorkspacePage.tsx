import { useMemo } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import {
  isContractForbiddenError,
  isContractNotFoundError,
  useContractQuery,
  useContractStructureQuery,
} from '../api/contracts'
import type { FindingFilters } from '../api/reviews'
import {
  CLASSIFICATIONS,
  SEVERITIES,
  isReviewForbiddenError,
  isReviewNotFoundError,
  isReviewTerminal,
  useFindingQuery,
  useFindingsQuery,
  useReviewSummaryQuery,
} from '../api/reviews'
import {
  isWorkflowConflictError,
  isWorkflowNotFoundError,
  useApprovedChangeSetQuery,
  useCurrentDecisionsQuery,
  useFinalizeReview,
  useHumanReviewQuery,
  workflowErrorMessage,
} from '../api/workflow'
import { useSemanticsQuery } from '../api/semantics'
import { ErrorState } from '../components/ErrorState'
import { LoadingState } from '../components/LoadingState'
import { buildRegionLabelMap, TAXONOMY_CONCEPTS } from '../lib/contractSemantics'
import { coverageLimitations } from '../lib/reviews'
import { subtreeNodes } from '../lib/documentTree'
import { ApprovedChangeSetSummary } from '../components/review/ApprovedChangeSetSummary'
import { FinalizationPanel } from '../components/review/FinalizationPanel'
import { FindingDetailPane } from '../components/review/FindingDetailPane'
import { FindingsPanel } from '../components/review/FindingsPanel'
import { HumanReviewProgress } from '../components/review/HumanReviewProgress'
import { RedlineExecutionPanel } from '../components/review/RedlineExecutionPanel'
import { ReviewCoveragePanel } from '../components/review/ReviewCoveragePanel'
import { ReviewHeader } from '../components/review/ReviewHeader'
import { ReviewLifecyclePanel } from '../components/review/ReviewLifecyclePanel'

function readSeverity(value: string | null): FindingFilters['severity'] {
  return value && (SEVERITIES as readonly string[]).includes(value)
    ? (value as FindingFilters['severity'])
    : undefined
}

function readClassification(value: string | null): FindingFilters['classification'] {
  return value && (CLASSIFICATIONS as readonly string[]).includes(value)
    ? (value as FindingFilters['classification'])
    : undefined
}

function readConcept(value: string | null): FindingFilters['concept'] {
  return value && (TAXONOMY_CONCEPTS as readonly string[]).includes(value)
    ? (value as FindingFilters['concept'])
    : undefined
}

export function ReviewWorkspacePage() {
  const { contractId = '', reviewId = '' } = useParams()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()

  const contractQuery = useContractQuery(contractId)
  const summaryQuery = useReviewSummaryQuery(reviewId)
  const pinnedVersion = summaryQuery.data?.contractVersionNumber ?? undefined
  const latestVersion = contractQuery.data?.latestVersion.versionNumber
  const structureQuery = useContractStructureQuery(contractId, pinnedVersion)
  const semanticsQuery = useSemanticsQuery(contractId, pinnedVersion)

  const selectedFindingParam = searchParams.get('finding')
  const filters: FindingFilters = {
    severity: readSeverity(searchParams.get('severity')),
    classification: readClassification(searchParams.get('classification')),
    concept: readConcept(searchParams.get('concept')),
  }
  const status = summaryQuery.data?.status
  const canReadFindings = isReviewTerminal(status) || status === 'UNKNOWN'
  const findingsQuery = useFindingsQuery(reviewId, filters, canReadFindings)

  // A finding id in the URL only means something for the review that produced it. The detail
  // endpoint is scoped to one review, so a selection carried across reviews requests a finding that
  // cannot exist here and can only answer 404. Membership in this review's own findings is the
  // guard, rather than the shape of the id, so no cross-review authority is inferred.
  //
  // The membership check is only sound when the list represents the whole review: absence from a
  // filtered or truncated list proves nothing. So the guard applies when the list is authoritative,
  // and stands down when it is not, rather than withholding a finding the URL legitimately names.
  const findingsListIsAuthoritative =
    !filters.severity && !filters.classification && !filters.concept && !findingsQuery.data?.truncated
  const selectedFindingBelongsToReview =
    !findingsListIsAuthoritative ||
    (findingsQuery.data?.items ?? []).some((f) => f.id === selectedFindingParam)
  const selectedFindingId =
    selectedFindingParam !== null && selectedFindingBelongsToReview ? selectedFindingParam : null
  const findingQuery = useFindingQuery(reviewId, canReadFindings ? selectedFindingId : null)

  const analysisCompleted = status === 'COMPLETED'
  const decisionsQuery = useCurrentDecisionsQuery(reviewId, analysisCompleted)
  const humanReviewQuery = useHumanReviewQuery(reviewId, analysisCompleted)
  const humanReview = humanReviewQuery.data ?? null
  const finalizeMutation = useFinalizeReview(reviewId)
  const humanReviewUnavailable = analysisCompleted && humanReviewQuery.isError
  const changeSetQuery = useApprovedChangeSetQuery(
    reviewId,
    analysisCompleted && (humanReview?.status === 'COMPLETED' || humanReviewUnavailable),
  )
  // A change set exists only once finalization has run, so a successful read is proof of
  // finalization. This is the fallback for the case where the human-review read failed and the
  // change set is therefore the only remaining authority.
  const isFinalized = humanReview?.status === 'COMPLETED' || changeSetQuery.isSuccess
  const changeSetReadFailed =
    changeSetQuery.isError && !isWorkflowNotFoundError(changeSetQuery.error)
  const decisionsLoaded = decisionsQuery.isSuccess
  const finalizeError = finalizeMutation.isError
    ? isWorkflowConflictError(finalizeMutation.error)
      ? humanReview?.readyToFinalize
        ? null
        : 'The review cannot be finalized yet. The blockers shown under Human review are current.'
      : workflowErrorMessage(
          finalizeMutation.error,
          'The review could not be finalized. Check your connection and try again.',
        )
    : null
  const decisionsByFindingId = useMemo(
    () =>
      new Map((decisionsQuery.data?.items ?? []).map((decision) => [decision.findingId, decision])),
    [decisionsQuery.data],
  )

  const regionLabels = useMemo(
    () =>
      buildRegionLabelMap(
        semanticsQuery.data?.clauses ?? [],
        structureQuery.data?.nodes ?? [],
      ),
    [semanticsQuery.data, structureQuery.data],
  )

  const clauseHeadings = useMemo(
    () =>
      new Map(
        (semanticsQuery.data?.clauses ?? []).map((clause) => [clause.id, clause.headingNodeId]),
      ),
    [semanticsQuery.data],
  )

  const onNavigateToNode = (nodeId: string) => {
    navigate(`/reviews/${contractId}?view=document&node=${nodeId}`)
  }

  const onOpenClauseSemantics = (headingNodeId: string) => {
    const params = new URLSearchParams()
    params.set('view', 'semantics')
    params.set('section', 'clauses')
    params.set('clause', headingNodeId)
    navigate(`/reviews/${contractId}?${params.toString()}`)
  }

  const clauseSource = useMemo(() => {
    const finding = findingQuery.data
    if (!finding || finding.subjectKind !== 'CLAUSE' || !finding.subjectHeadingNodeId) {
      return null
    }
    return {
      nodeId: finding.subjectHeadingNodeId,
      heading: regionLabels.get(finding.subjectHeadingNodeId) ?? 'Clause source unavailable',
      nodes: subtreeNodes(structureQuery.data?.nodes ?? [], finding.subjectHeadingNodeId),
    }
  }, [findingQuery.data, structureQuery.data, regionLabels])

  function selectFinding(findingId: string) {
    const next = new URLSearchParams(searchParams)
    next.set('finding', findingId)
    setSearchParams(next)
  }

  function updateFilters(next: FindingFilters) {
    const params = new URLSearchParams(searchParams)
    const entries: [string, string | undefined][] = [
      ['severity', next.severity],
      ['classification', next.classification],
      ['concept', next.concept],
    ]
    for (const [key, value] of entries) {
      if (value) params.set(key, value)
      else params.delete(key)
    }
    params.delete('finding')
    setSearchParams(params)
  }

  // The route needs both identities to mean anything. Without them the reads are inert, so this
  // must come before the loading check: an unresolvable identity otherwise waits forever.
  if (!contractId || !reviewId) {
    return <ErrorState message="Missing contract or review id." />
  }

  if (contractQuery.isPending || summaryQuery.isPending) {
    return <LoadingState label="Loading review…" />
  }

  const matchesRoute =
    summaryQuery.data !== undefined &&
    summaryQuery.data.contractId.toLowerCase() === contractId.toLowerCase()

  if (
    isReviewNotFoundError(summaryQuery.error) ||
    isContractNotFoundError(contractQuery.error) ||
    (summaryQuery.data !== undefined && !matchesRoute)
  ) {
    // Permanent: an identical retry cannot make a missing contract or review appear.
    return (
      <ErrorState message="This review could not be found. It may not exist, or you may not have access to it." />
    )
  }

  if (isReviewForbiddenError(summaryQuery.error)) {
    return <ErrorState message="You do not have permission to read this review." />
  }

  // contracts:read is a separate permission from reviews:read, so a refused contract read is a
  // contract problem and must not be reported as a refused review.
  if (isContractForbiddenError(contractQuery.error)) {
    return (
      <ErrorState message="You do not have permission to read the contract this review belongs to." />
    )
  }

  if (summaryQuery.isError || !summaryQuery.data || contractQuery.isError) {
    return (
      <ErrorState
        message="This review could not be loaded."
        onRetry={() => {
          void summaryQuery.refetch()
          void contractQuery.refetch()
        }}
      />
    )
  }

  return (
    <section className="review-page" aria-labelledby="review-heading">
      <ReviewHeader
        contractId={contractId}
        contractTitle={contractQuery.data?.title ?? 'Untitled contract'}
        summary={summaryQuery.data}
      />
      <ReviewLifecyclePanel
        contractId={contractId}
        reviewId={reviewId}
        summary={summaryQuery.data}
        coverageLimited={coverageLimitations(summaryQuery.data.coverage).length > 0}
        onRefresh={() => void summaryQuery.refetch()}
      />
      <ReviewCoveragePanel coverage={summaryQuery.data.coverage} />
      {analysisCompleted ? (
        humanReviewQuery.isError ? (
          <p className="state state--warning" role="alert">
            Human review state could not be loaded, so the approved change set and redline
            cannot be confirmed from it.{' '}
            <button
              type="button"
              className="link-button"
              onClick={() => void humanReviewQuery.refetch()}
            >
              Try again
            </button>
          </p>
        ) : null
      ) : null}
      {analysisCompleted && humanReview ? (
        <>
          {decisionsQuery.isError ? (
            <p className="state state--warning" role="alert">
              Decisions could not be loaded.{' '}
              <button
                type="button"
                className="link-button"
                onClick={() => void decisionsQuery.refetch()}
              >
                Try again
              </button>
            </p>
          ) : null}
          <HumanReviewProgress
            review={humanReview}
            findings={findingsQuery.data?.items ?? []}
            regionLabels={regionLabels}
            onSelectFinding={selectFinding}
          />
          <FinalizationPanel
            key={reviewId}
            review={humanReview}
            isFinalizing={finalizeMutation.isPending}
            finalizeError={finalizeError}
            onFinalize={() => finalizeMutation.mutate()}
          />
        </>
      ) : null}
      {isFinalized || changeSetReadFailed ? (
        changeSetReadFailed ? (
          <p className="state state--warning" role="alert">
            The approved change set could not be loaded.{' '}
            <button
              type="button"
              className="link-button"
              onClick={() => void changeSetQuery.refetch()}
            >
              Try again
            </button>
          </p>
        ) : changeSetQuery.data ? (
          <ApprovedChangeSetSummary
            changeSet={changeSetQuery.data}
            findings={findingsQuery.data?.items ?? []}
            decisionsByFindingId={decisionsByFindingId}
            regionLabels={regionLabels}
            contractId={contractId}
            canDeepLink={
              summaryQuery.data.contractVersionNumber !== null &&
              latestVersion !== undefined &&
              summaryQuery.data.contractVersionNumber === latestVersion
            }
            onSelectFinding={selectFinding}
          />
        ) : null
      ) : null}
      {analysisCompleted ? (
        <RedlineExecutionPanel
          key={reviewId}
          reviewId={reviewId}
          isFinalized={isFinalized}
          approvedChangeSet={changeSetQuery.data ?? null}
          regionLabels={regionLabels}
          onSelectFinding={selectFinding}
        />
      ) : null}
      <div className="review-split">
        <FindingsPanel
          findings={findingsQuery.data?.items ?? []}
          truncated={findingsQuery.data?.truncated ?? false}
          filters={filters}
          onFiltersChange={updateFilters}
          selectedFindingId={selectedFindingId}
          onSelect={selectFinding}
          regionLabels={regionLabels}
          decisionsByFindingId={decisionsByFindingId}
          showDecisionState={analysisCompleted && humanReview !== null}
          decisionsTruncated={decisionsQuery.data?.truncated ?? false}
          decisionsLoaded={decisionsLoaded}
          reviewStatus={status ?? 'UNKNOWN'}
          coverageLimited={coverageLimitations(summaryQuery.data.coverage).length > 0}
          isEnabled={canReadFindings}
          isStateUnrecognized={status === 'UNKNOWN'}
          isPending={findingsQuery.isPending}
          isError={findingsQuery.isError}
          onRetry={() => void findingsQuery.refetch()}
        />
        <FindingDetailPane
          finding={findingQuery.data}
          regionLabels={regionLabels}
          contractId={contractId}
          clauseSource={clauseSource}
          sourceVersion={summaryQuery.data.contractVersionNumber}
          latestVersion={latestVersion ?? null}
          clauseHeadings={clauseHeadings}
          onNavigateToNode={onNavigateToNode}
          onOpenClauseSemantics={onOpenClauseSemantics}
          reviewId={reviewId}
          currentDecision={
            selectedFindingId ? (decisionsByFindingId.get(selectedFindingId) ?? null) : null
          }
          humanReview={humanReview}
          isFinalized={isFinalized}
          showDecisionControls={analysisCompleted}
          canDecide={analysisCompleted && humanReview !== null}
          analysisComplete={analysisCompleted}
          decisionsLoaded={decisionsLoaded}
          decisionsTruncated={decisionsQuery.data?.truncated ?? false}
          findingId={canReadFindings ? selectedFindingId : null}
          isPending={Boolean(selectedFindingId) && findingQuery.isPending}
          isError={findingQuery.isError}
          onRetry={() => void findingQuery.refetch()}
        />
      </div>
    </section>
  )
}