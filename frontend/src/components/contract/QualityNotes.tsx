import { qualityGroups, qualityNoteLabel } from '../../lib/contractSemantics'
import type { SemanticObservations } from '../../api/semantics'

export function QualityNotes({
  notes,
  observations,
}: {
  notes: string[]
  observations?: SemanticObservations | null
}) {
  const { analysis, unresolved } = qualityGroups(notes)
  const degraded =
    analysis.length > 0 ||
    observations?.providerDegraded === true ||
    observations?.budgetExhausted === true

  if (!degraded && unresolved.length === 0) return null

  return (
    <section aria-labelledby="quality-heading" className="semantic-quality">
      <h3 id="quality-heading">Analysis quality</h3>
      <p className="section-note">
        These notes describe the machine analysis. Human corrections are shown in the
        relevant sections.
      </p>
      {degraded ? (
        <div className="state state--warning" role="status">
          <p>
            Some contract semantics could not be resolved automatically. You can still
            inspect the contract and the interpretations that are available.
          </p>
          {analysis.length > 0 ? (
            <ul className="quality-list">
              {analysis.map((note) => (
                <li key={note}>{qualityNoteLabel(note)}</li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
      {unresolved.length > 0 ? (
        <div className="quality-notes">
          <h4>Could not be fully resolved</h4>
          <p>These items are incomplete interpretations, not legal conclusions.</p>
          <ul className="quality-list">
            {unresolved.map((note) => (
              <li key={note}>{qualityNoteLabel(note)}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  )
}
