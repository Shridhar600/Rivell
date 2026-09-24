import { useEffect, useRef } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { useContractQuery, useContractStructureQuery } from '../../api/contracts'
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

  const openClauseSemantics = (clauseId: string) => {
    const clause = clauses.find((entry) => entry.id === clauseId)
    const next = new URLSearchParams()
    next.set('view', 'semantics')
    next.set('section', 'clauses')
    if (clause) next.set('clause', clause.headingNodeId)
    navigate(`/reviews/${contractId}?${next.toString()}`)
  }

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

  const staleConcept =
    bundle !== undefined &&
    bundle.request.legalConcept !== null &&
    effectiveConcept !== null &&
    bundle.request.legalConcept !== effectiveConcept

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
    body = (
      <section className="state state--warning" aria-labelledby="evidence-ready-heading">
        <h2 id="evidence-ready-heading">Semantic analysis required</h2>
        <p>
          Evidence is built from the contract&apos;s semantic analysis. Run semantic
          analysis for this contract first.
        </p>
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
              <dd>Clause review</dd>
            </div>
            <div className="data-list__row">
              <dt>Legal concept</dt>
              <dd>{effectiveConcept ? conceptLabel(effectiveConcept) : 'No primary concept'}</dd>
            </div>
          </dl>
          {selectedClause && !effectiveConcept ? (
            <p className="state state--warning">
              This clause has no primary legal concept yet. Correct its classification in
              semantic analysis before building evidence.
            </p>
          ) : null}
          <div className="evidence-request__actions">
            <button
              type="button"
              className="button button--primary"
              disabled={!canBuild || build.isPending}
              onClick={() => {
                if (selectedClauseId) build.mutate({ clauseId: selectedClauseId })
              }}
            >
              {bundle ? 'Rebuild evidence' : 'Build evidence'}
            </button>
          </div>
          {build.isError ? (
            <div role="alert" className="state state--error">
              <p>{buildErrorMessage(build.error)}</p>
              {isConflictError(build.error) ? (
                <Link className="link-inline" to={semanticAnalysisLink}>
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
          {staleConcept && bundle ? (
            <p className="state state--warning">
              {`This evidence was built with the classification ${conceptLabel(
                bundle.request.legalConcept as string,
              )}. The contract's current classification is ${conceptLabel(
                effectiveConcept as string,
              )} — rebuild to refresh the evidence.`}
            </p>
          ) : null}
        </section>

        {bundle ? (
          <>
            <EvidenceBundleView
              bundle={bundle}
              regionLabels={regionLabels}
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
      {body}
    </section>
  )
}
