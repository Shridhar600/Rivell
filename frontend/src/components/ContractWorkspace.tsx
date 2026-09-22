import { useCallback, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import { originalDocumentUrl, useContractQuery, useContractStructureQuery } from '../api/contracts'
import { buildOutlineItems, isParseFailed } from '../lib/documentTree'
import { EmptyState } from './EmptyState'
import { ErrorState } from './ErrorState'
import { LoadingState } from './LoadingState'
import { OutlineTree } from './OutlineTree'
import { SourceSpanPanel } from './SourceSpanPanel'
import { StructuralRenderer } from './StructuralRenderer'

export function ContractWorkspace({ contractId }: { contractId: string }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedId = searchParams.get('node')

  const contractQuery = useContractQuery(contractId)
  const contract = contractQuery.data
  const structureQuery = useContractStructureQuery(
    contractId,
    contract?.latestVersion?.versionNumber,
  )
  const structure = structureQuery.data

  const selectNode = useCallback(
    (nodeId: string) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          next.set('node', nodeId)
          return next
        },
        { replace: false },
      )
      requestAnimationFrame(() => {
        const el = document.getElementById(`node-${nodeId}`)
        if (typeof el?.scrollIntoView === 'function') {
          el.scrollIntoView({ block: 'nearest' })
        }
      })
    },
    [setSearchParams],
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

  const versionNumber = contract.latestVersion?.versionNumber ?? 1
  const originalUrl = originalDocumentUrl(contractId, versionNumber)
  const parseFailed = isParseFailed(
    structure?.parseStatus ?? contract.latestVersion?.parseStatus,
  )

  const header = (
    <header className="workspace__header">
      <div>
        <h1>{contract.title ?? 'Untitled contract'}</h1>
        <p className="workspace__meta">
          v{versionNumber}
          {contract.latestVersion?.parseStatus
            ? ` · parse ${contract.latestVersion.parseStatus}`
            : ''}
          {contract.latestVersion?.sha256
            ? ` · sha256 ${contract.latestVersion.sha256.slice(0, 12)}…`
            : ''}
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
        <a href={originalUrl} download="original.docx" className="button">
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
  } else if (parseFailed) {
    body = (
      <div role="alert" className="state state--error">
        <p>Document parsing failed. The original file remains available.</p>
      </div>
    )
  } else if (structure.nodes.length === 0) {
    body = (
      <EmptyState
        title="No document structure"
        message="The document parsed successfully but contains no readable structure."
      />
    )
  } else {
    const selectedNode = structure.nodes.find((n) => n.id === selectedId)
    body = (
      <div className="workspace__grid">
        <OutlineTree
          items={buildOutlineItems(structure.nodes)}
          selectedId={selectedId}
          onSelect={selectNode}
        />
        <div>
          <StructuralRenderer
            nodes={structure.nodes}
            selectedId={selectedId}
            onSelect={selectNode}
          />
          <SourceSpanPanel
            label="Selected region"
            span={selectedNode?.sourceSpan ?? null}
          />
        </div>
      </div>
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
