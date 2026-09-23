import { useEffect, useRef } from 'react'
import { Link, useSearchParams } from 'react-router'
import type { RegionLabelNode } from '../../lib/contractSemantics'
import { buildRegionLabelMap } from '../../lib/contractSemantics'
import { useContractStructureQuery } from '../../api/contracts'
import { useSemanticsQuery } from '../../api/semantics'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'
import { ClausesPanel } from './ClausesPanel'
import { DefinitionsPanel } from './DefinitionsPanel'
import { EnrichmentPanel } from './EnrichmentPanel'
import { PartiesPanel } from './PartiesPanel'
import { ReferencesPanel } from './ReferencesPanel'
import { RelationshipsPanel } from './RelationshipsPanel'
import { SemanticOverview } from './SemanticOverview'

const SECTIONS = [
  { key: 'overview', label: 'Overview' },
  { key: 'parties', label: 'Parties' },
  { key: 'definitions', label: 'Definitions' },
  { key: 'references', label: 'Cross-references' },
  { key: 'clauses', label: 'Clauses' },
  { key: 'relationships', label: 'Relationships' },
] as const

type SectionKey = (typeof SECTIONS)[number]['key']

function normalizeSection(value: string | null): SectionKey {
  const match = SECTIONS.find((section) => section.key === value)
  return match?.key ?? 'overview'
}

export function SemanticWorkspace({
  contractId,
  versionNumber,
}: {
  contractId: string
  versionNumber: number
}) {
  const [searchParams, setSearchParams] = useSearchParams()
  const semanticsQuery = useSemanticsQuery(contractId, versionNumber)
  const structureQuery = useContractStructureQuery(contractId, versionNumber)
  const containerRef = useRef<HTMLDivElement>(null)
  const focusedSection = useRef<SectionKey | null>(null)
  const focusedClause = useRef<string | null>(null)

  const semantics = semanticsQuery.data
  const refreshFailed = semanticsQuery.isError && semantics != null
  const section = normalizeSection(searchParams.get('section'))
  const clauseKey = searchParams.get('clause')

  useEffect(() => {
    if (focusedSection.current === section) return
    const heading = containerRef.current?.querySelector<HTMLElement>(
      '[data-section-heading]',
    )
    if (!heading) return
    heading.focus()
    focusedSection.current = section
  }, [section, semantics])

  useEffect(() => {
    if (!clauseKey) {
      focusedClause.current = null
      return
    }
    if (focusedClause.current === clauseKey) return
    const heading = document.querySelector<HTMLElement>('[data-clause-heading]')
    if (!heading) return
    heading.focus()
    focusedClause.current = clauseKey
  }, [clauseKey, semantics])



  const structureNodes = structureQuery.data?.nodes ?? []
  const nodes: RegionLabelNode[] = structureNodes.map((node) => ({
    id: node.id,
    text: node.text,
    numberingLabel: node.numberingLabel,
  }))
  const nodeIndex = new Map(structureNodes.map((node, index) => [node.id, index]))
  const orderOf = (nodeId: string) => nodeIndex.get(nodeId) ?? Number.MAX_SAFE_INTEGER
  const orderedClauses = semantics
    ? [...semantics.clauses].sort(
        (a, b) => orderOf(a.headingNodeId) - orderOf(b.headingNodeId),
      )
    : []
  const orderedRelationships = semantics
    ? [...semantics.relationships].sort(
        (a, b) =>
          orderOf(a.sourceRegionNodeId) - orderOf(b.sourceRegionNodeId) ||
          orderOf(a.targetRegionNodeId) - orderOf(b.targetRegionNodeId),
      )
    : []
  const labelByNodeId = buildRegionLabelMap(orderedClauses, nodes)

  const navigateToNode = (nodeId: string) => {
    const next = new URLSearchParams(searchParams)
    next.set('view', 'document')
    next.set('node', nodeId)
    setSearchParams(next)
  }

  if (semanticsQuery.isPending) {
    return <LoadingState label="Loading semantic analysis…" />
  }

  if (semanticsQuery.isError && semantics == null) {
    return (
      <ErrorState
        message={semanticsQuery.error.message}
        onRetry={() => void semanticsQuery.refetch()}
      />
    )
  }

  if (!semantics || semantics.enrichmentStatus === 'NOT_ENRICHED') {
    return (
      <EnrichmentPanel
        contractId={contractId}
        versionNumber={versionNumber}
        storedProfile={semantics?.reviewProfile ?? null}
        mode="initial"
      />
    )
  }

  if (semantics.enrichmentStatus === 'ENRICHING') {
    return (
      <section aria-labelledby="enriching-heading" className="panel semantic-panel">
        <h2 id="enriching-heading">Analysis in progress</h2>
        <p role="status">
          Rivell is analysing this contract version. The result will appear here.
        </p>
        <button type="button" onClick={() => void semanticsQuery.refetch()}>
          Check again
        </button>
        {refreshFailed ? (
          <p className="state state--warning">
            The latest refresh failed. Showing the last known state.
          </p>
        ) : null}
      </section>
    )
  }

  if (semantics.enrichmentStatus === 'FAILED') {
    return (
      <div className="semantic-workspace" ref={containerRef}>
        <section
          aria-labelledby="enrichment-failed-heading"
          className="state state--error"
          role="alert"
        >
          <h2 id="enrichment-failed-heading">Semantic analysis failed</h2>
          <p>
            This contract could not be analysed. The structural document view is still
            available.
          </p>
        </section>
        <EnrichmentPanel
          contractId={contractId}
          versionNumber={versionNumber}
          storedProfile={semantics.reviewProfile}
          mode="retry"
        />
      </div>
    )
  }

  const degraded =
    semantics.quality.some((note) =>
      ['PROVIDER_DEGRADATION', 'BUDGET_EXHAUSTED', 'PARSER_WARNINGS_PRESENT'].includes(
        note,
      ),
    ) ||
    semantics.observations?.providerDegraded === true ||
    semantics.observations?.budgetExhausted === true

  const selectClause = (nextClauseKey: string | null) => {
    const next = new URLSearchParams(searchParams)
    if (nextClauseKey) next.set('clause', nextClauseKey)
    else next.delete('clause')
    setSearchParams(next)
  }

  let content
  switch (section) {
    case 'parties':
      content = (
        <PartiesPanel
          parties={semantics.parties}
          contractId={contractId}
          versionNumber={versionNumber}
          onRefresh={() => void semanticsQuery.refetch()}
        />
      )
      break
    case 'definitions':
      content = <DefinitionsPanel terms={semantics.definedTerms} />
      break
    case 'references':
      content = (
        <ReferencesPanel
          references={semantics.references}
          labelByNodeId={labelByNodeId}
          onNavigateToNode={navigateToNode}
        />
      )
      break
    case 'clauses':
      content = (
        <ClausesPanel
          clauses={orderedClauses}
          selectedClauseKey={clauseKey}
          onSelectClause={selectClause}
          contractId={contractId}
          versionNumber={versionNumber}
          onNavigateToNode={navigateToNode}
          onRefresh={() => void semanticsQuery.refetch()}
        />
      )
      break
    case 'relationships':
      content = (
        <RelationshipsPanel
          relationships={orderedRelationships}
          labelByNodeId={labelByNodeId}
          onNavigateToNode={navigateToNode}
        />
      )
      break
    default:
      content = (
        <SemanticOverview
          semantics={semantics}
          contractId={contractId}
          versionNumber={versionNumber}
        />
      )
  }

  return (
    <div className="semantic-workspace" ref={containerRef}>
      {semantics.enrichmentStatus === 'UNKNOWN' ? (
        <p className="state state--warning" role="status">
          The analysis state of this contract version is not recognised. The available
          interpretations are shown as they were returned.
        </p>
      ) : null}
      <nav className="section-nav" aria-label="Semantic sections">
        <ul className="section-nav__list">
          {SECTIONS.map((entry) => {
            const next = new URLSearchParams(searchParams)
            next.set('section', entry.key)
            return (
              <li key={entry.key}>
                <Link
                  to={{ search: next.toString() }}
                  className={
                    entry.key === section
                      ? 'section-nav__link section-nav__link--active'
                      : 'section-nav__link'
                  }
                  aria-current={entry.key === section ? 'location' : undefined}
                >
                  {entry.label}
                </Link>
              </li>
            )
          })}
        </ul>
      </nav>
      {refreshFailed ? (
        <p className="state state--warning">
          The latest refresh failed. Showing the last loaded analysis.{' '}
          <button type="button" onClick={() => void semanticsQuery.refetch()}>
            Retry
          </button>
        </p>
      ) : null}
      {degraded && section !== 'overview' ? (
        <p className="state state--warning">
          Some contract semantics could not be resolved automatically. Available
          interpretations are shown as they are.
        </p>
      ) : null}
      {content}
    </div>
  )
}
