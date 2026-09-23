import { Link } from 'react-router'
import { PublicationsList } from '../components/knowledge/PublicationsList'
import { useHeadingFocus } from '../lib/useHeadingFocus'

export function KnowledgePublicationsPage() {
  const headingRef = useHeadingFocus<HTMLHeadingElement>(true)

  return (
    <section className="page" aria-labelledby="publications-heading">
      <header className="page__header">
        <div>
          <h1 id="publications-heading" tabIndex={-1} ref={headingRef}>
            Published versions
          </h1>
          <p className="meta">
            Immutable, versioned organizational knowledge created from approved
            candidates.
          </p>
        </div>
        <Link to="/knowledge" className="button">
          Knowledge sources
        </Link>
      </header>
      <PublicationsList />
    </section>
  )
}
