import type { SemanticDefinedTerm } from '../../api/semantics'
import { SourceRegionLinks } from './SourceRegionLinks'

export function DefinitionsPanel({
  terms,
  labelByNodeId,
  onNavigateToNode,
}: {
  terms: SemanticDefinedTerm[]
  labelByNodeId: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
}) {
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
                <SourceRegionLinks
                  nodeIds={[term.definingNodeId]}
                  labelByNodeId={labelByNodeId}
                  onNavigateToNode={onNavigateToNode}
                  prefix="Defined in: "
                  emptyLabel="Defining region not available"
                />
              </p>
              <p className="provenance-note">
                {term.referenceNodeIds.length === 0 ? (
                  'No references found in the contract text'
                ) : (
                  <SourceRegionLinks
                    nodeIds={term.referenceNodeIds}
                    labelByNodeId={labelByNodeId}
                    onNavigateToNode={onNavigateToNode}
                    prefix={
                      term.referenceNodeIds.length === 1
                        ? 'Used in: '
                        : `Used in ${term.referenceNodeIds.length} regions: `
                    }
                  />
                )}
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
