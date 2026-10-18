import { useCallback } from 'react'
import { useSearchParams } from 'react-router'
import { useKnowledgeCatalog } from '../../api/knowledgeRead'
import { useTaxonomy } from '../../api/taxonomy'
import { CatalogGate, CatalogNotices } from '../../components/knowledge/read/CatalogGate'
import { KnowledgeFrame, type Crumb } from '../../components/knowledge/read/KnowledgeFrame'
import { KnowledgeLinkRow, SourceStateBadge } from '../../components/knowledge/read/KnowledgeRows'
import { KnowledgeTabs } from '../../components/knowledge/read/KnowledgeTabs'
import { VersionBanner } from '../../components/knowledge/read/VersionBanner'
import { VersionTimeline } from '../../components/knowledge/read/VersionTimeline'
import { Chip } from '../../components/ui/Status'
import { EmptyState } from '../../components/ui/State'
import { longDate } from '../../lib/dates'
import { artifactName, clauseGroups, reviewedText, type Catalog } from '../../lib/knowledgeCatalog'

type Tab = 'clauses' | 'history'
const CRUMBS: Crumb[] = [{ label: 'Knowledge', to: '/knowledge' }, { label: 'Clause library' }]
const LEDE = 'Approved clause wording, with the version and source it came from.'

export function ClausesPage() {
  const state = useKnowledgeCatalog()
  return (
    <CatalogGate state={state} crumbs={CRUMBS} title="Clause library">
      {(catalog) => <Clauses catalog={catalog} state={state} />}
    </CatalogGate>
  )
}

function Clauses({ catalog, state }: { catalog: Catalog; state: ReturnType<typeof useKnowledgeCatalog> }) {
  const { labelFor } = useTaxonomy()
  const [params, setParams] = useSearchParams()
  const library = catalog.clauseLibrary
  const tab: Tab = params.get('tab') === 'history' ? 'history' : 'clauses'
  const current = library.versions[0] ?? null
  const requested = Number(params.get('publication'))
  const viewing = library.versions.find((entry) => entry.publication.number === requested) ?? current
  const isCurrent = viewing === current
  const groups = clauseGroups(library, catalog.index, labelFor, viewing?.publication.number)
  const compiling = catalog.inCompilation.filter((source) => source.artifact.artifactType === 'CLAUSE_LIBRARY')

  const update = useCallback(
    (change: (next: URLSearchParams) => void) => {
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous)
          change(next)
          return next
        },
        { replace: true },
      )
    },
    [setParams],
  )
  const view = (publicationNumber: number | null) =>
    update((next) => {
      if (publicationNumber === null || publicationNumber === current?.publication.number) next.delete('publication')
      else next.set('publication', String(publicationNumber))
      next.delete('tab')
    })

  return (
    <KnowledgeFrame
      crumbs={CRUMBS}
      title="Clause library"
      lede={LEDE}
      addPreset="clause-library"
      meta={
        viewing ? (
          <>
            <span className={`rv-stb${isCurrent ? ' rv-stb--ok' : ''}`}>{isCurrent ? 'Current' : 'Superseded'}</span>
            <Chip>Knowledge version {viewing.publication.number}</Chip>
            <span className="rv-k-meta__text">Published {longDate(viewing.publication.createdAt)}</span>
          </>
        ) : null
      }
    >
      <CatalogNotices state={state} catalog={catalog} />

      {compiling.length > 0 ? (
        <ul className="rv-k-list" aria-label="Clause library drafts">
          {compiling.map((source) => (
            <KnowledgeLinkRow
              key={source.artifact.id}
              accent
              to={`/knowledge/compilations/${source.artifact.id}`}
              type="CLAUSE_LIBRARY"
              title={artifactName(source.artifact)}
              detail={`${reviewedText(source) ? `${reviewedText(source)}. ` : ''}Nothing is used in reviews until you publish it.`}
              right={<SourceStateBadge state={source.state} />}
            />
          ))}
        </ul>
      ) : null}

      {!viewing || !current ? (
        <EmptyState
          title="No clause library is published"
          message="Approved clause wording appears here once you publish a clause library."
        />
      ) : (
        <>
          {isCurrent ? null : (
            <VersionBanner
              viewing={viewing.publication.number}
              current={current.publication.number}
              onBack={() => view(null)}
            />
          )}
          <KnowledgeTabs
            label="Clause library sections"
            selected={tab}
            tabs={[
              { key: 'clauses', label: `Clauses (${groups.length})` },
              { key: 'history', label: 'Version history' },
            ]}
            onSelect={(key) =>
              update((next) => {
                if (key === 'history') next.set('tab', 'history')
                else next.delete('tab')
              })
            }
          >
            {tab === 'history' ? (
              <VersionTimeline
                versions={library.versions}
                type="CLAUSE_LIBRARY"
                viewing={viewing.publication.number}
                onView={(number) => view(number)}
              />
            ) : groups.length === 0 ? (
              <EmptyState
                title="No clauses in this version"
                message="This version was replaced. Choose another version in Version history."
              />
            ) : (
              <ul className="rv-k-list" aria-label="Clauses">
                {groups.map((group) => (
                  <KnowledgeLinkRow
                    key={group.key}
                    to={`/knowledge/clauses/${encodeURIComponent(group.key)}${isCurrent ? '' : `?publication=${viewing.publication.number}`}`}
                    type="CLAUSE_LIBRARY"
                    title={group.title}
                    detail={group.variants.map((variant) => variant.candidate.rawLabel).join(', ')}
                    right={`${group.variants.length} ${group.variants.length === 1 ? 'variant' : 'variants'}`}
                  />
                ))}
              </ul>
            )}
          </KnowledgeTabs>
        </>
      )}
    </KnowledgeFrame>
  )
}
