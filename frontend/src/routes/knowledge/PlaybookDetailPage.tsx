import { useCallback } from 'react'
import { useParams, useSearchParams } from 'react-router'
import { useKnowledgeCatalog } from '../../api/knowledgeRead'
import { CatalogGate, CatalogNotices } from '../../components/knowledge/read/CatalogGate'
import { KnowledgeFrame, type Crumb } from '../../components/knowledge/read/KnowledgeFrame'
import { KnowledgeTabs } from '../../components/knowledge/read/KnowledgeTabs'
import { PositionInspector } from '../../components/knowledge/read/PositionInspector'
import { PositionList } from '../../components/knowledge/read/PositionList'
import { VersionBanner } from '../../components/knowledge/read/VersionBanner'
import { VersionTimeline } from '../../components/knowledge/read/VersionTimeline'
import { Inspector } from '../../components/shell/TopBar'
import { LinkButton } from '../../components/ui/Button'
import { SourceIcon } from '../../components/ui/Icon'
import { Chip } from '../../components/ui/Status'
import { EmptyState } from '../../components/ui/State'
import { longDate } from '../../lib/dates'
import { useMediaQuery } from '../../lib/useMediaQuery'
import {
  artifactName,
  clauseGroups,
  playbookPositions,
  type Catalog,
  type PlaybookView,
} from '../../lib/knowledgeCatalog'
import { scopeLine } from '../../lib/knowledgePosition'

type Tab = 'positions' | 'history'

export function PlaybookDetailPage() {
  const { artifactId = '' } = useParams()
  const state = useKnowledgeCatalog()
  const crumbs: Crumb[] = [{ label: 'Knowledge', to: '/knowledge' }, { label: 'Playbooks', to: '/knowledge/playbooks' }, { label: 'Playbook' }]
  return (
    <CatalogGate state={state} crumbs={crumbs} title="Playbook">
      {(catalog) => {
        const playbook = catalog.playbooks.find((entry) => entry.artifact.id === artifactId)
        if (playbook) return <PlaybookDetail catalog={catalog} playbook={playbook} state={state} />
        const source = catalog.sources.find((entry) => entry.artifact.id === artifactId)
        return (
          <KnowledgeFrame crumbs={crumbs} title={source ? artifactName(source.artifact) : 'Playbook not found'}>
            <CatalogNotices state={state} catalog={catalog} />
            {source ? (
              <EmptyState
                title="Nothing from this source is published"
                message="This source has no published positions, so it is not a published playbook."
                action={<LinkButton to={`/knowledge/sources/${source.artifact.id}`}>Open the source</LinkButton>}
              />
            ) : (
              <EmptyState
                title="This playbook could not be found"
                message="It may have been opened from an old link."
                action={<LinkButton to="/knowledge/playbooks">All playbooks</LinkButton>}
              />
            )}
          </KnowledgeFrame>
        )
      }}
    </CatalogGate>
  )
}

function PlaybookDetail({
  catalog,
  playbook,
  state,
}: {
  catalog: Catalog
  playbook: PlaybookView
  state: ReturnType<typeof useKnowledgeCatalog>
}) {
  const [params, setParams] = useSearchParams()
  const tab: Tab = params.get('tab') === 'history' ? 'history' : 'positions'

  const requested = Number(params.get('publication'))
  const viewing = playbook.versions.find((entry) => entry.publication.number === requested) ?? playbook.versions[0]
  const current = playbook.versions[0]
  const isCurrent = viewing === current
  const positions = playbookPositions(playbook, catalog.index, viewing.publication.number)

  const selected = positions.find((candidate) => candidate.id === params.get('position')) ?? positions[0] ?? null

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
      if (publicationNumber === null || publicationNumber === current.publication.number) next.delete('publication')
      else next.set('publication', String(publicationNumber))
      next.delete('position')
      next.delete('tab')
    })

  const name = artifactName(playbook.artifact)
  const scope = scopeLine(playbook.published)
  const crumbs: Crumb[] = [
    { label: 'Knowledge', to: '/knowledge' },
    { label: 'Playbooks', to: '/knowledge/playbooks' },
    { label: name },
  ]
  // Below this width the shell hides its detail panel, so the detail moves under the list.
  const compact = useMediaQuery('(max-width: 1180px)')
  const showInspector = tab === 'positions'
  const clause = selected
    ? (clauseGroups(catalog.clauseLibrary, catalog.index).find(
        (group) => group.conceptKey !== null && group.conceptKey === selected.effectiveConceptKey,
      ) ?? null)
    : null
  const selectedPublication = selected ? (catalog.index.added.get(selected.id) ?? null) : null

  return (
    <>
      <KnowledgeFrame
        crumbs={crumbs}
        title={name}
        withInspector={showInspector}
        meta={
          <>
            <span className={`rv-stb${isCurrent && positions.length > 0 ? ' rv-stb--ok' : ''}`}>
              {isCurrent && positions.length > 0 ? 'Current' : 'Superseded'}
            </span>
            <Chip>Publication {viewing.publication.number}</Chip>
            {scope ? <Chip>{scope}</Chip> : null}
            <span className="rv-k-meta__text">Published {longDate(viewing.publication.createdAt)}</span>
          </>
        }
        actions={
          <LinkButton to={`/knowledge/sources/${playbook.artifact.id}`}>
            <SourceIcon className="rv-icon" />
            Source
          </LinkButton>
        }
      >
        <CatalogNotices state={state} catalog={catalog} />
        {isCurrent ? null : (
          <VersionBanner
            viewing={viewing.publication.number}
            current={current.publication.number}
            what="This is the playbook"
            onBack={() => view(null)}
          />
        )}
        <KnowledgeTabs
          label="Playbook sections"
          selected={tab}
          tabs={[
            { key: 'positions', label: `Positions (${positions.length})` },
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
              versions={playbook.versions}
              type="PLAYBOOK"
              viewing={viewing.publication.number}
              source={playbook.artifact.filename}
              onView={(number) => view(number)}
            />
          ) : positions.length === 0 ? (
            <EmptyState
              title="No positions in this publication"
              message="Every position this source published was superseded by a later publication. Choose another publication in Version history."
            />
          ) : (
            <PositionList
              positions={positions}
              selectedId={selected?.id ?? null}
              onSelect={(id) => update((next) => next.set('position', id))}
            />
          )}
          {compact && showInspector && selected && selectedPublication ? (
            <div className="rv-k-inline">
              <PositionInspector
                key={selected.id}
                candidate={selected}
                artifact={playbook.artifact}
                publication={selectedPublication}
                clause={clause}
              />
            </div>
          ) : null}
        </KnowledgeTabs>
      </KnowledgeFrame>
      {showInspector && !compact ? (
        <Inspector status={selected ? `Selected position ${selected.rawLabel}` : ''}>
          {selected && selectedPublication ? (
            <PositionInspector
              key={selected.id}
              candidate={selected}
              artifact={playbook.artifact}
              publication={selectedPublication}
              clause={clause}
            />
          ) : (
            <p className="rv-insp__note">Select a position to see its wording and where it came from.</p>
          )}
        </Inspector>
      ) : null}
    </>
  )
}
