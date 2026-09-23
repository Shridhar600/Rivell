import { Link } from 'react-router'
import { KnowledgeSourcesList } from '../components/knowledge/KnowledgeSourcesList'
import { PublicationsList } from '../components/knowledge/PublicationsList'
import { useHeadingFocus } from '../lib/useHeadingFocus'

export function KnowledgePage() {
  const headingRef = useHeadingFocus<HTMLHeadingElement>(true)

  return (
    <section aria-labelledby="knowledge-heading" className="page">
      <header className="page__header">
        <div>
          <h1 id="knowledge-heading" tabIndex={-1} ref={headingRef}>
            Knowledge
          </h1>
          <p className="meta">
            Immutable sources, deterministic extraction, human review, and
            versioned organizational knowledge.
          </p>
        </div>
        <Link to="/knowledge/new" className="button button--primary">
          Add knowledge
        </Link>
      </header>

      <section aria-labelledby="knowledge-sources-heading">
        <h2 id="knowledge-sources-heading">Sources</h2>
        <KnowledgeSourcesList />
      </section>

      <section aria-labelledby="knowledge-publications-heading">
        <div className="section-header">
          <h2 id="knowledge-publications-heading">Published versions</h2>
          <Link to="/knowledge/publications">View all</Link>
        </div>
        <PublicationsList limit={5} />
      </section>
    </section>
  )
}
