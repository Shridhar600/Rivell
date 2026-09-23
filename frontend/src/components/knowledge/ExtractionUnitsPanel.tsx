import { useExtractionUnitsQuery } from '../../api/knowledge'
import { UNIT_KIND_LABELS } from '../../lib/knowledgeLabels'
import { EmptyState } from '../EmptyState'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'

export function ExtractionUnitsPanel({ artifactId }: { artifactId: string }) {
  const query = useExtractionUnitsQuery(artifactId)

  if (query.isPending) {
    return <LoadingState label="Loading extraction units…" />
  }

  if (query.isError) {
    return (
      <ErrorState
        message={query.error.message || 'Extraction units unavailable.'}
        onRetry={() => void query.refetch()}
      />
    )
  }

  if (query.data.length === 0) {
    return (
      <EmptyState
        title="No extraction units"
        message="Rivell did not identify any coherent source regions in this artifact."
      />
    )
  }

  return (
    <ul className="unit-list">
      {query.data.map((unit) => (
        <li key={unit.id} className="unit">
          <div className="unit__header">
            <strong>{unit.title ?? `Unit ${unit.ordinal + 1}`}</strong>
            <span className="meta">
              {UNIT_KIND_LABELS[unit.kind]} · {unit.sourceNodeIds.length} source
              node{unit.sourceNodeIds.length === 1 ? '' : 's'}
            </span>
          </div>
          {unit.contentText ? (
            <p className="unit__content">{unit.contentText}</p>
          ) : null}
        </li>
      ))}
    </ul>
  )
}
