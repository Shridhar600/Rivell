import { useEffect, useRef } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import {
  originalDocumentUrl,
  useContractQuery,
  useContractStructureQuery,
} from '../../api/contracts'
import {
  isConflictError,
  isForbiddenError,
  isNotFoundError,
  useBuildEvidence,
  useEvidenceBundle,
} from '../../api/retrieval'
import { useSemanticsQuery } from '../../api/semantics'
import {
  buildRegionLabelMap,
  conceptLabel,
  effectivePrimaryConcept,
} from '../../lib/contractSemantics'
import { humanizeCode } from '../../lib/domainLabels'
import { staleClassificationNote, sufficiencyPresentation } from '../../lib/evidence'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'
import { EvidenceBundleView } from './EvidenceBundleView'
import { RetrievalTracePanel } from './RetrievalTracePanel'

function buildErrorMessage(error: Error): string {
  if (isConflictError(error)) {
    return 'Semantic analysis is not ready for this clause. Check its classification in semantic analysis, then build evidence again.'
  }
  if (isNotFoundError(error)) {
    return 'This contract, version or clause could not be found.'
  }
  if (isForbiddenError(error)) {
    return 'You do not have access to this contract.'
  }
  return 'Evidence construction failed. Try again.'
}

export function EvidenceInspector({ contractId }: { contractId: string }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const selectedKey = searchParams.get('clause')
  const headingRef = useRef<HTMLHeadingElement>(null)

  const contractQuery = useContractQuery(contractId)
  const contract = contractQuery.data
  const versionNumber = contract?.latestVersion.versionNumber
  const structureQuery = useContractStructureQuery(contractId, versionNumber)
  const semanticsQuery = useSemanticsQuery(contractId, versionNumber)
  const semantics = semanticsQuery.data
  const clauses = semantics?.clauses ?? []

  const selectedClause =
    clauses.find((clause) => clause.headingNodeId === selectedKey) ?? null
  const selectedClauseId = selectedClause?.id ?? null
  const effectiveConcept = selectedClause ? effectivePrimaryConcept(selectedClause) : null
  const canBuild = selectedClauseId !== null && effectiveConcept !== null

  const build = useBuildEvidence(contractId, versionNumber ?? 0)
  const bundleQuery = useEvidenceBundle(contractId, versionNumber ?? 0, selectedClauseId)
  const bundle = bundleQuery.data

  const loadedContractId = contract?.id
  useEffect(() => {
    if (loadedContractId) headingRef.current?.focus()
  }, [contractId, loadedContractId])

  if (contractQuery.isPending) {
    return <LoadingState label="Loading contract…" />
  }

  if (contractQuery.isError || !contract) {
    return (
      <ErrorState
        message={contractQuery.error?.message ?? 'Contract unavailable.'}
        onRetry={() => void contractQuery.refetch()}
      />
    )
  }

  const latest = contract.latestVersion
  const regionLabels = buildRegionLabelMap(
    [...clauses, ...(semantics?.structuralRegions ?? [])],
    structureQuery.data?.nodes ?? [],
  )

  const navigateToNode = (nodeId: string) => {
    const next = new URLSearchParams()
    next.set('view', 'document')
    next.set('node', nodeId)
    navigate(`/reviews/${contractId}?${next.toString()}`)
  }

  const openClauseSemantics = (headingNodeId: string) => {
    const next = new URLSearchParams()
    next.set('view', 'semantics')
    next.set('section', 'clauses')
    next.set('clause', headingNodeId)
    navigate(`/reviews/${contractId}?${next.toString()}`)
  }

  const clauseHeadings = new Map(clauses.map((clause) => [clause.id, clause.headingNodeId]))

  const selectClause = (headingNodeId: string) => {
    setSearchParams((previous) => {
      const next = new URLSearchParams(previous)
      next.set('clause', headingNodeId)
      return next
    })
  }

  const semanticAnalysisLink = (() => {
    const next = new URLSearchParams()
    next.set('view', 'semantics')
    next.set('section', 'clauses')
    if (selectedClause) next.set('clause', selectedClause.headingNodeId)
    return `/reviews/${contractId}?${next.toString()}`
  })()

  const staleNote = bundle
    ? staleClassificationNote(bundle.request.legalConcept, effectiveConcept)
    : null
  const buildError =
    build.isError && build.variables?.clauseId === selectedClauseId ? build.error : null
  const unmatchedSelection = selectedKey !== null && selectedClause === null
  const announcement = build.isPending
    ? 'Building evidence…'
    : bundle
      ? `Evidence built. ${sufficiencyPresentation(bundle.sufficiency).label}`
      : ''

  const header = (
    <header className="workspace__header">
      <div>
        <h1 tabIndex={-1} ref={headingRef}>
          {contract.title ?? 'Untitled contract'}
        </h1>
        <p className="workspace__meta">
          {`Evidence inspection · v${latest.versionNumber} · ${latest.filename}`}
        </p>
      </div>
      <div className="workspace__actions">
        <a
          href={originalDocumentUrl(contractId, latest.versionNumber)}
          target="_blank"
          rel="noopener noreferrer"
          className="button"
        >
          View Original
        </a>
        <Link className="button" to={`/reviews/${contractId}?view=semantics`}>
          Back to contract
        </Link>
      </div>
    </header>
  )

  const semanticsReady = semantics?.enrichmentStatus === 'READY'

  let body
  if (semanticsQuery.isPending) {
    body = <LoadingState label="Loading semantic analysis…" />
  } else if (semanticsQuery.isError && semantics == null) {
    body = (
      <ErrorState
        message={semanticsQuery.error?.message ?? 'Semantic analysis unavailable.'}
        onRetry={() => void semanticsQuery.refetch()}
      />
    )
  } else if (!semanticsReady) {
    const analysisState = semantics?.enrichmentStatus
    const heading =
      analysisState === 'ENRICHING'
        ? 'Semantic analysis in progress'
        : analysisState === 'FAILED'
          ? 'Semantic analysis failed'
          : 'Semantic analysis required'
    const message =
      analysisState === 'ENRICHING'
        ? 'Semantic analysis is running for this contract. Evidence can be built once it is ready.'
        : analysisState === 'FAILED'
          ? 'Semantic analysis failed for this contract. Review the failure in semantic analysis, then try again.'
          : "Evidence is built from the contract's semantic analysis. Run semantic analysis for this contract first."
    body = (
      <section className="state state--warning" aria-labelledby="evidence-ready-heading">
        <h2 id="evidence-ready-heading">{heading}</h2>
        <p>{message}</p>
        <Link className="button" to={`/reviews/${contractId}?view=semantics`}>
          Open semantic analysis
        </Link>
      </section>
    )
  } else {
    body = (
      <>
        <section className="panel" aria-labelledby="evidence-request-heading">
          <h2 id="evidence-request-heading">Evidence request</h2>
          <p className="evidence-request__hint">
            Evidence is built for one clause from the contract&apos;s current semantic
            analysis.
          </p>
          <div className="field">
            <label htmlFor="evidence-clause">Clause</label>
            <select
              id="evidence-clause"
              value={selectedKey ?? ''}
              onChange={(event) => selectClause(event.target.value)}
            >
              <option value="" disabled>
                Select a clause
              </option>
              {unmatchedSelection ? (
                <option value={selectedKey ?? ''} disabled>
                  Unknown clause
                </option>
              ) : null}
              {clauses.map((clause) => (
                <option key={clause.headingNodeId} value={clause.headingNodeId}>
                  {clause.heading}
                </option>
              ))}
            </select>
          </div>
          <dl className="data-list">
            <div className="data-list__row">
              <dt>Purpose</dt>
              <dd>{bundle ? humanizeCode(bundle.request.purpose) : 'Clause review'}</dd>
            </div>
            <div className="data-list__row">
              <dt>Legal concept</dt>
              <dd>{effectiveConcept ? conceptLabel(effectiveConcept) : 'No primary concept'}</dd>
            </div>
            {bundle && bundle.request.effectiveOn ? (
              <div className="data-list__row">
                <dt>Effective on</dt>
                <dd>{bundle.request.effectiveOn}</dd>
              </div>
            ) : null}
          </dl>
          {selectedClause && !effectiveConcept ? (
            <p className="state state--warning">
              This clause has no primary legal concept yet. Correct its classification in
              semantic analysis before building evidence.
            </p>
          ) : null}
          {unmatchedSelection ? (
            <p className="state state--warning">
              This clause link is not part of the current semantic analysis for this
              version.{' '}
              <Link to={`/reviews/${contractId}?view=semantics&section=clauses`}>
                Open semantic analysis
              </Link>
            </p>
          ) : null}
          <div className="evidence-request__actions">
            <button
              type="button"
              className="button button--primary"
              disabled={!canBuild || build.isPending}
              aria-busy={build.isPending}
              onClick={() => {
                if (selectedClauseId) build.mutate({ clauseId: selectedClauseId })
              }}
            >
              {build.isPending
                ? 'Building evidence…'
                : bundle
                  ? 'Rebuild evidence'
                  : 'Build evidence'}
            </button>
          </div>
          {buildError ? (
            <div role="alert" className="state state--error">
              <p>{buildErrorMessage(buildError)}</p>
              {isConflictError(buildError) ? (
                <Link to={semanticAnalysisLink}>
                  Open semantic analysis
                </Link>
              ) : null}
            </div>
          ) : null}
          {bundle && bundle.request.knowledgePinPublicationNumber !== null ? (
            <p className="evidence-request__pin">
              {`Knowledge state: publication v${bundle.request.knowledgePinPublicationNumber}`}
            </p>
          ) : null}
          {staleNote ? <p className="state state--warning">{staleNote}</p> : null}
        </section>

        {bundle ? (
          <>
            <EvidenceBundleView
              bundle={bundle}
              regionLabels={regionLabels}
              clauseHeadings={clauseHeadings}
              onNavigateToNode={navigateToNode}
              onOpenClauseSemantics={openClauseSemantics}
            />
            <RetrievalTracePanel traceId={bundle.traceId} />
          </>
        ) : null}
      </>
    )
  }

  return (
    <section aria-labelledby="evidence-inspector-heading" className="workspace">
      <span id="evidence-inspector-heading" className="visually-hidden">
        Evidence inspection
      </span>
      {header}
      <p className="visually-hidden" role="status">
        {announcement}
      </p>
      {body}
    </section>
  )
}
