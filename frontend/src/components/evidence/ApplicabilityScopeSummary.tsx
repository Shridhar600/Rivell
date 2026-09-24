import type { ApplicabilityScope } from '../../api/retrieval'
import { applicabilityDimensions, applicabilityNote } from '../../lib/evidence'

export function ApplicabilityScopeSummary({ scope }: { scope: ApplicabilityScope | null }) {
  if (!scope) return null
  const dimensions = applicabilityDimensions(scope)
  if (dimensions.length === 0) return null
  const note = applicabilityNote(scope)

  return (
    <div className="applicability">
      <p className="applicability__label">Applicability</p>
      <dl className="data-list">
        {dimensions.map((dimension) => (
          <div className="data-list__row" key={dimension.key}>
            <dt>{dimension.label}</dt>
            <dd>{dimension.value}</dd>
          </div>
        ))}
      </dl>
      {note ? <p className="applicability__note">{note}</p> : null}
    </div>
  )
}
