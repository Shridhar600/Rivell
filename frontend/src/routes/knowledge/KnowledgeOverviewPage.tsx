import { Link } from 'react-router'
import { useKnowledgeCatalog } from '../../api/knowledgeRead'
import { useTaxonomy } from '../../api/taxonomy'
import { CatalogGate, CatalogNotices } from '../../components/knowledge/read/CatalogGate'
import { KnowledgeFrame } from '../../components/knowledge/read/KnowledgeFrame'
import { KnowledgeLinkRow, SourceStateBadge } from '../../components/knowledge/read/KnowledgeRows'
import { BookIcon, ClauseIcon, SourceIcon } from '../../components/ui/Icon'
import {
  artifactName,
  clauseGroups,
  countNoun,
  reviewedText,
  type Catalog,
  type RecentPublication,
} from '../../lib/knowledgeCatalog'
import { longDate } from '../../lib/dates'

const CRUMBS = [{ label: 'Knowledge' }]

export function KnowledgeOverviewPage() {
  const state = useKnowledgeCatalog()
  return (
    <CatalogGate state={state} crumbs={CRUMBS} title="Knowledge">
      {(catalog) => <Overview catalog={catalog} state={state} />}
    </CatalogGate>
  )
}

function recentTarget(entry: RecentPublication): string {
  return entry.type === 'PLAYBOOK'
    ? `/knowledge/playbooks/${entry.artifact.id}?publication=${entry.publication.number}`
    : `/knowledge/clauses?publication=${entry.publication.number}`
}

function Overview({ catalog, state }: { catalog: Catalog; state: ReturnType<typeof useKnowledgeCatalog> }) {
  const { labelFor } = useTaxonomy()
  const clauses = clauseGroups(catalog.clauseLibrary, catalog.index, labelFor)
  const variantCount = clauses.reduce((sum, group) => sum + group.variants.length, 0)
  return (
    <KnowledgeFrame
      crumbs={CRUMBS}
      title="Knowledge"
      lede="What Rivell knows about how your organization contracts."
    >
      <CatalogNotices state={state} catalog={catalog} />

      {catalog.inCompilation.length > 0 ? (
        <section aria-labelledby="k-compiling">
          <div className="rv-k-sech">
            <h2 id="k-compiling">Drafts</h2>
            <span>Not used in reviews until published</span>
          </div>
          <ul className="rv-k-list">
            {catalog.inCompilation.map((source) => (
              <KnowledgeLinkRow
                key={source.artifact.id}
                accent
                to={`/knowledge/compilations/${source.artifact.id}`}
                type={source.artifact.artifactType}
                title={artifactName(source.artifact)}
                detail={`From ${source.artifact.filename}${reviewedText(source) ? `, ${reviewedText(source)}` : ''}`}
                right={<SourceStateBadge state={source.state} />}
              />
            ))}
          </ul>
        </section>
      ) : null}

      <section aria-labelledby="k-published">
        <div className="rv-k-sech">
          <h2 id="k-published">Published knowledge</h2>
        </div>
        <ul className="rv-k-cards">
          <li>
            <Link to="/knowledge/playbooks" className="rv-k-card">
              <span className="rv-k-card__ico">
                <BookIcon className="rv-icon" />
              </span>
              <span className="rv-k-card__n">{catalog.playbooks.length}</span>
              <b>Playbooks</b>
              <small>Preferred, fallback and escalation positions</small>
            </Link>
          </li>
          <li>
            <Link to="/knowledge/clauses" className="rv-k-card">
              <span className="rv-k-card__ico">
                <ClauseIcon className="rv-icon" />
              </span>
              <span className="rv-k-card__n">{clauses.length}</span>
              <b>Clause library</b>
              <small>
                {variantCount === 0
                  ? 'Approved reusable wording'
                  : `Approved reusable wording, ${variantCount} ${variantCount === 1 ? 'variant' : 'variants'}`}
              </small>
            </Link>
          </li>
          <li>
            <Link to="/knowledge/sources" className="rv-k-card">
              <span className="rv-k-card__ico">
                <SourceIcon className="rv-icon" />
              </span>
              <span className="rv-k-card__n">{catalog.sources.length}</span>
              <b>Sources</b>
              <small>Original files, kept unchanged</small>
            </Link>
          </li>
        </ul>
      </section>

      <section aria-labelledby="k-recent">
        <div className="rv-k-sech">
          <h2 id="k-recent">Recently published</h2>
        </div>
        {catalog.recent.length === 0 ? (
          <p className="rv-note">Nothing has been published yet. Published playbooks and clauses appear here.</p>
        ) : (
          <ul className="rv-k-list">
            {catalog.recent.slice(0, 3).map((entry) => (
              <KnowledgeLinkRow
                key={entry.key}
                to={recentTarget(entry)}
                type={entry.type}
                title={artifactName(entry.artifact)}
                detail={`Knowledge version ${entry.publication.number}, published ${longDate(entry.publication.createdAt)}`}
                right={countNoun(entry.artifact.artifactType, entry.count)}
              />
            ))}
          </ul>
        )}
      </section>
    </KnowledgeFrame>
  )
}
