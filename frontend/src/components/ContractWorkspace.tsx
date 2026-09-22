import { useEffect, useRef, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import {
  originalDocumentUrl,
  useContractQuery,
  useContractStructureQuery,
} from '../api/contracts'
import { isParseFailed, isParsing } from '../lib/parseStatus'
import { DocumentViewer } from './DocumentViewer'
import { EmptyState } from './EmptyState'
import { ErrorState } from './ErrorState'
import { LoadingState } from './LoadingState'
import { ParseNotice } from './ParseNotice'

export function ContractWorkspace({ contractId }: { contractId: string }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedId = searchParams.get('node')
  const headingRef = useRef<HTMLHeadingElement>(null)

  const contractQuery = useContractQuery(contractId)
  const contract = contractQuery.data
  const structureQuery = useContractStructureQuery(
    contractId,
    contract?.latestVersion.versionNumber,
  )
  const structure = structureQuery.data

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

  const selectNode = (nodeId: string) => {
    if (nodeId === selectedId) return
    setSearchParams((previous) => {
      const next = new URLSearchParams(previous)
      next.set('node', nodeId)
      return next
    })
  }

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

  if (structureQuery.isPending) {
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
      {body}
    </section>
  )
}
