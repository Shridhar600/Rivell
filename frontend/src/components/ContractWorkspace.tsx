import { useEffect, useRef, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import {
  originalDocumentUrl,
  useContractQuery,
  useContractStructureQuery,
} from '../api/contracts'
import { useSemanticsQuery } from '../api/semantics'
import { isParseFailed, isParsing } from '../lib/parseStatus'
import { ClauseSemanticSummary } from './contract/ClauseSemanticSummary'
import { DocumentViewer } from './DocumentViewer'
import { EmptyState } from './EmptyState'
import { ErrorState } from './ErrorState'
import { LoadingState } from './LoadingState'
import { ParseNotice } from './ParseNotice'
import { SemanticWorkspace } from './contract/SemanticWorkspace'

export function ContractWorkspace({ contractId }: { contractId: string }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedId = searchParams.get('node')
  const view = searchParams.get('view') === 'semantics' ? 'semantics' : 'document'
  const headingRef = useRef<HTMLHeadingElement>(null)
  const focusedNodeRef = useRef<string | null>(null)
  const previousViewRef = useRef<string | undefined>(undefined)

  const contractQuery = useContractQuery(contractId)
  const contract = contractQuery.data
  const structureQuery = useContractStructureQuery(
    contractId,
    contract?.latestVersion.versionNumber,
  )
  const structure = structureQuery.data
  const semanticsQuery = useSemanticsQuery(
    contractId,
    contract?.latestVersion.versionNumber,
  )

  const loadedContractId = contract?.id

  useEffect(() => {
    if (loadedContractId) headingRef.current?.focus()
  }, [contractId, loadedContractId])

  useEffect(() => {
    if (!structure || !selectedId) return
    const element = document.getElementById(`node-${selectedId}`)
    if (typeof element?.scrollIntoView === 'function') {
      element.scrollIntoView({ block: 'nearest' })
    }
  }, [structure, selectedId])

  useEffect(() => {
    const arrivedInDocument =
      view === 'document' &&
      (previousViewRef.current === undefined || previousViewRef.current !== 'document')
    if (arrivedInDocument && selectedId && structure) {
      if (focusedNodeRef.current !== selectedId) {
        const element = document.getElementById(`node-${selectedId}`)
        if (element) {
          element.focus()
          focusedNodeRef.current = selectedId
        }
      }
    }
    previousViewRef.current = view
  }, [view, selectedId, structure])

  const selectNode = (nodeId: string) => {
    if (nodeId === selectedId) return
    setSearchParams((previous) => {
      const next = new URLSearchParams(previous)
      next.set('node', nodeId)
      return next
    })
  }

  const openClauseSemantics = (clauseKey: string) => {
    const next = new URLSearchParams(searchParams)
    next.set('view', 'semantics')
    next.set('section', 'clauses')
    next.set('clause', clauseKey)
    setSearchParams(next)
  }

  const selectedClause =
    semanticsQuery.data?.clauses.find((clause) => clause.headingNodeId === selectedId) ??
    semanticsQuery.data?.structuralRegions.find(
      (region) => region.headingNodeId === selectedId,
    ) ??
    null

  const selectedRegionExtra = selectedClause ? (
    <ClauseSemanticSummary
      clause={selectedClause}
      onOpen={() => openClauseSemantics(selectedClause.headingNodeId)}
    />
  ) : null

  const viewLink = (target: 'document' | 'semantics') => {
    const next = new URLSearchParams(searchParams)
    next.set('view', target)
    return { search: next.toString() }
  }

  const viewNav = (
    <nav className="view-nav" aria-label="Contract views">
      <ul className="view-nav__list">
        <li>
          <Link
            to={viewLink('document')}
            className={
              view === 'document'
                ? 'view-nav__link view-nav__link--active'
                : 'view-nav__link'
            }
            aria-current={view === 'document' ? 'location' : undefined}
          >
            Document
          </Link>
        </li>
        <li>
          <Link
            to={viewLink('semantics')}
            className={
              view === 'semantics'
                ? 'view-nav__link view-nav__link--active'
                : 'view-nav__link'
            }
            aria-current={view === 'semantics' ? 'location' : undefined}
          >
            Semantic analysis
          </Link>
        </li>
      </ul>
    </nav>
  )

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
  const parseStatus = structure?.parseStatus ?? latest.parseStatus
  const warnings = structure?.parseWarnings ?? latest.parseWarnings
  const originalUrl = originalDocumentUrl(contractId, latest.versionNumber)

  const header = (
    <header className="workspace__header">
      <div>
        <h1 tabIndex={-1} ref={headingRef}>
          {contract.title ?? 'Untitled contract'}
        </h1>
        <p className="workspace__meta">
          v{latest.versionNumber} · {latest.filename} · parse {parseStatus}
          {warnings.length > 0 ? ` · ${warnings.length} warning(s)` : ''}
          {latest.parserVersion ? ` · ${latest.parserVersion}` : ''}
          {` · sha256 ${latest.sha256.slice(0, 12)}…`}
        </p>
      </div>
      <div className="workspace__actions">
        <Link className="button" to={`/reviews/${contractId}/analysis`}>
          Reviews &amp; findings
        </Link>
        <a
          href={originalUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="button"
        >
          View Original
        </a>
        <a href={originalUrl} download={latest.filename} className="button">
          Download Original
        </a>
      </div>
    </header>
  )

  let body: ReactNode

  if (view === 'semantics') {
    body =
      parseStatus === 'FAILED' ? (
        <EmptyState
          title="Semantic analysis is unavailable"
          message="The document could not be parsed, so Rivell cannot analyse it. The original file remains available to view or download."
        />
      ) : (
        <SemanticWorkspace
          contractId={contractId}
          versionNumber={latest.versionNumber}
        />
      )
  } else if (structureQuery.isPending) {
    body = <LoadingState label="Loading document structure…" />
  } else if (structureQuery.isError || !structure) {
    body = (
      <ErrorState
        message={structureQuery.error?.message ?? 'Structure unavailable.'}
        onRetry={() => void structureQuery.refetch()}
      />
    )
  } else if (isParsing(structure.parseStatus)) {
    body = <ParseNotice status={structure.parseStatus} warnings={[]} />
  } else if (isParseFailed(structure.parseStatus)) {
    body = (
      <>
        <ParseNotice
          status={structure.parseStatus}
          warnings={structure.parseWarnings}
        />
        {structure.nodes.length > 0 ? (
          <DocumentViewer
            structure={structure}
            selectedId={selectedId}
            onSelect={selectNode}
            selectedRegionExtra={selectedRegionExtra}
          />
        ) : null}
      </>
    )
  } else if (structure.nodes.length === 0) {
    body = (
      <EmptyState
        title="No document structure"
        message="The document parsed successfully but contains no readable structure."
      />
    )
  } else {
    body = (
      <>
        <ParseNotice
          status={structure.parseStatus}
          warnings={structure.parseWarnings}
        />
        <DocumentViewer
          structure={structure}
          selectedId={selectedId}
          onSelect={selectNode}
          selectedRegionExtra={selectedRegionExtra}
        />
      </>
    )
  }

  return (
    <section aria-labelledby="workspace-heading" className="workspace">
      <span id="workspace-heading" className="visually-hidden">
        Contract workspace
      </span>
      {header}
      {viewNav}
      {body}
    </section>
  )
}
