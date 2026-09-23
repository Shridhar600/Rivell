import type { SemanticDefinedTerm } from '../../api/semantics'

export function DefinitionsPanel({ terms }: { terms: SemanticDefinedTerm[] }) {
  return (
    <section aria-labelledby="definitions-heading">
      <h2 id="definitions-heading" tabIndex={-1} data-section-heading>
        Defined terms
      </h2>
      {terms.length === 0 ? (
        <p className="semantic-empty">
          No defined terms were identified in this contract version.
        </p>
      ) : (
        <ul className="term-list">
          {terms.map((term) => (
            <li className="panel term-card" key={term.id}>
              <div className="term-card__header">
                <h3>{term.term}</h3>
                {term.duplicate ? (
                  <span className="badge badge--conflict">Defined more than once</span>
                ) : null}
              </div>
              <p className="term-card__definition">{term.definition}</p>
              <p className="provenance-note">
                {term.referenceCount === 1
                  ? 'Used once in the contract text'
                  : `Used ${term.referenceCount} times in the contract text`}
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
