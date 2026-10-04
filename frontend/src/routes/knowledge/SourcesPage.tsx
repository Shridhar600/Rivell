import { useMemo } from 'react'
import { useKnowledgeCatalog } from '../../api/knowledgeRead'
import { CatalogGate, CatalogNotices } from '../../components/knowledge/read/CatalogGate'
import { AddKnowledgeButton, KnowledgeFrame, type Crumb } from '../../components/knowledge/read/KnowledgeFrame'
import { KnowledgeLinkRow, SourceStateBadge } from '../../components/knowledge/read/KnowledgeRows'
import { LinkButton } from '../../components/ui/Button'
import { EmptyState } from '../../components/ui/State'
import { longDate } from '../../lib/dates'
import { ARTIFACT_TYPE_LABELS } from '../../lib/knowledgeLabels'
import { artifactFormat, sourceNote, type Catalog } from '../../lib/knowledgeCatalog'

const CRUMBS: Crumb[] = [{ label: 'Knowledge', to: '/knowledge' }, { label: 'Sources' }]
const LEDE = 'Every uploaded original is kept unchanged.'

export function SourcesPage() {
  const state = useKnowledgeCatalog()
  return (
    <CatalogGate state={state} crumbs={CRUMBS} title="Sources">
      {(catalog) => <Sources catalog={catalog} state={state} />}
    </CatalogGate>
  )
}

function Sources({ catalog, state }: { catalog: Catalog; state: ReturnType<typeof useKnowledgeCatalog> }) {
  const sources = useMemo(
    () => [...catalog.sources].sort((a, b) => b.artifact.createdAt.localeCompare(a.artifact.createdAt)),
    [catalog.sources],
  )
  return (
    <KnowledgeFrame crumbs={CRUMBS} title="Sources" lede={LEDE} actions={<AddKnowledgeButton />}>
      <CatalogNotices state={state} catalog={catalog} />
      {sources.length === 0 ? (
        <EmptyState
          title="No sources yet"
          message="Upload a playbook or clause library. The original is kept here unchanged."
          action={<LinkButton to="/knowledge/new">Add knowledge</LinkButton>}
        />
      ) : (
        <ul className="rv-k-list" aria-label="Sources">
          {sources.map((source) => (
            <KnowledgeLinkRow
              key={source.artifact.id}
              to={`/knowledge/sources/${source.artifact.id}`}
              type="FILE"
              title={source.artifact.filename}
              detail={`${ARTIFACT_TYPE_LABELS[source.artifact.artifactType]}, ${artifactFormat(source.artifact)}, ${longDate(source.artifact.createdAt)}. ${sourceNote(source)}`}
              right={<SourceStateBadge state={source.state} />}
            />
          ))}
        </ul>
      )}
    </KnowledgeFrame>
  )
}
