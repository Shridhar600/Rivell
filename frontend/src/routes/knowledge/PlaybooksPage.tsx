import { useKnowledgeCatalog } from '../../api/knowledgeRead'
import { CatalogGate, CatalogNotices } from '../../components/knowledge/read/CatalogGate'
import { KnowledgeFrame, type Crumb } from '../../components/knowledge/read/KnowledgeFrame'
import { KnowledgeLinkRow, SourceStateBadge } from '../../components/knowledge/read/KnowledgeRows'
import { EmptyState } from '../../components/ui/State'
import { longDate } from '../../lib/dates'
import { artifactName, countNoun, reviewedText, type Catalog, type PlaybookView } from '../../lib/knowledgeCatalog'
import { scopeLine } from '../../lib/knowledgePosition'

const CRUMBS: Crumb[] = [{ label: 'Knowledge', to: '/knowledge' }, { label: 'Playbooks' }]
const LEDE = "Your team's approved positions for the contract types you review."

export function PlaybooksPage() {
  const state = useKnowledgeCatalog()
  return (
    <CatalogGate state={state} crumbs={CRUMBS} title="Playbooks">
      {(catalog) => <Playbooks catalog={catalog} state={state} />}
    </CatalogGate>
  )
}

function playbookDetail(playbook: PlaybookView): string {
  const scope = scopeLine(playbook.published)
  return `${scope ? `Applies to ${scope}. ` : ''}Published ${longDate(playbook.latest.createdAt)}`
}

function Playbooks({ catalog, state }: { catalog: Catalog; state: ReturnType<typeof useKnowledgeCatalog> }) {
  const compiling = catalog.inCompilation.filter((source) => source.artifact.artifactType === 'PLAYBOOK')
  return (
    <KnowledgeFrame crumbs={CRUMBS} title="Playbooks" lede={LEDE} addPreset="playbook">
      <CatalogNotices state={state} catalog={catalog} />

      {compiling.length > 0 ? (
        <ul className="rv-k-list" aria-label="Playbook drafts">
          {compiling.map((source) => (
            <KnowledgeLinkRow
              key={source.artifact.id}
              accent
              to={`/knowledge/compilations/${source.artifact.id}`}
              type="PLAYBOOK"
              title={artifactName(source.artifact)}
              detail={`${reviewedText(source) ? `${reviewedText(source)}. ` : ''}Nothing is used in reviews until you publish it.`}
              right={<SourceStateBadge state={source.state} />}
            />
          ))}
        </ul>
      ) : null}

      {catalog.playbooks.length === 0 ? (
        <EmptyState
          title="No playbook is published"
          message="A playbook appears here once you publish its reviewed positions."
        />
      ) : (
        <ul className="rv-k-list" aria-label="Published playbooks">
          {catalog.playbooks.map((playbook) => (
            <KnowledgeLinkRow
              key={playbook.artifact.id}
              to={`/knowledge/playbooks/${playbook.artifact.id}`}
              type="PLAYBOOK"
              title={artifactName(playbook.artifact)}
              detail={playbookDetail(playbook)}
              right={
                <>
                  <span className="rv-chip">Knowledge version {playbook.latest.number}</span>
                  {countNoun('PLAYBOOK', playbook.live)}
                </>
              }
            />
          ))}
        </ul>
      )}
    </KnowledgeFrame>
  )
}
