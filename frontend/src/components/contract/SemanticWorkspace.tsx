import { useEffect, useRef } from 'react'
import { Link, useSearchParams } from 'react-router'
import type { RegionLabelNode } from '../../lib/contractSemantics'
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
  const previousSection = useRef<SectionKey | undefined>(undefined)
  const previousClause = useRef<string | null | undefined>(undefined)
  const previousStatus = useRef<string | undefined>(undefined)

  const semantics = semanticsQuery.data
  const section = normalizeSection(searchParams.get('section'))
  const clauseId = searchParams.get('clause')

  useEffect(() => {
    if (
      previousSection.current !== undefined &&
      previousSection.current !== section
    ) {
      containerRef.current
        ?.querySelector<HTMLElement>('[data-section-heading]')
        ?.focus()
    }
    previousSection.current = section
  }, [section])

  useEffect(() => {
    if (
      previousClause.current !== undefined &&
      previousClause.current !== clauseId
    ) {
      document.querySelector<HTMLElement>('[data-clause-heading]')?.focus()
    }
    previousClause.current = clauseId
  }, [clauseId])

  const enrichmentStatus = semanticsQuery.isPending
    ? 'LOADING'
    : semantics == null
      ? 'NONE'
      : semantics.enrichmentStatus
  useEffect(() => {
    if (
      previousStatus.current !== undefined &&
      previousStatus.current !== 'READY' &&
      enrichmentStatus === 'READY'
    ) {
      containerRef.current
        ?.querySelector<HTMLElement>('[data-section-heading]')
        ?.focus()
    }
    previousStatus.current = enrichmentStatus
  }, [enrichmentStatus])

  const nodes: RegionLabelNode[] = (structureQuery.data?.nodes ?? []).map(
    (node) => ({
      id: node.id,
      text: node.text,
      numberingLabel: node.numberingLabel,
    }),
  )

  const navigateToNode = (nodeId: string) => {
    const next = new URLSearchParams(searchParams)
    next.set('view', 'document')
    next.set('node', nodeId)
    setSearchParams(next)
  }

  if (semanticsQuery.isPending) {
    return <LoadingState label="Loading semantic analysis…" />
  }

  if (semanticsQuery.isError) {
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

  const selectClause = (nextClauseId: string | null) => {
    const next = new URLSearchParams(searchParams)
    if (nextClauseId) next.set('clause', nextClauseId)
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
          clauses={semantics.clauses}
          nodes={nodes}
          onNavigateToNode={navigateToNode}
        />
      )
      break
    case 'clauses':
      content = (
        <ClausesPanel
          clauses={semantics.clauses}
          selectedClauseId={clauseId}
          onSelectClause={selectClause}
          contractId={contractId}
          versionNumber={versionNumber}
          onNavigateToNode={navigateToNode}
        />
      )
      break
    case 'relationships':
      content = (
        <RelationshipsPanel
          relationships={semantics.relationships}
          clauses={semantics.clauses}
          nodes={nodes}
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
      {degraded && section !== 'overview' ? (
        <p className="state state--warning" role="status">
          Some contract semantics could not be resolved automatically. Available
          interpretations are shown as they are.
        </p>
      ) : null}
      {content}
    </div>
  )
}
