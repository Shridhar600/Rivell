import type { ArtifactType } from '../../../api/knowledge'
import { longDate } from '../../../lib/dates'
import { countNoun, type VersionEntry } from '../../../lib/knowledgeCatalog'

function summary(entry: VersionEntry, type: ArtifactType): string {
  const parts: string[] = []
  if (entry.added > 0) parts.push(`${countNoun(type, entry.added)} added`)
  if (entry.superseded > 0) parts.push(`${entry.superseded} superseded`)
  const base = parts.join(', ')
  return entry.publication.note ? `${base}. ${entry.publication.note}` : base
}

interface VersionTimelineProps {
  versions: VersionEntry[]
  type: ArtifactType
  /** The publication number being viewed. */
  viewing: number
  /** Where the published content came from, when one source accounts for all of it. */
  source?: string
  onView: (publicationNumber: number) => void
}

/**
 * Real publications that changed this knowledge, newest first. Each is an immutable snapshot
 * of the organization-wide published set, so viewing one reads that snapshot and never edits
 * the current one.
 */
export function VersionTimeline({ versions, type, viewing, source, onView }: VersionTimelineProps) {
  return (
    <ul className="rv-k-list rv-k-timeline" aria-label="Version history">
      {versions.map((entry, position) => {
        const isViewing = entry.publication.number === viewing
        return (
          <li key={entry.publication.id} aria-current={isViewing ? 'true' : undefined}>
            <span className="rv-k-dot" aria-hidden="true" />
            <div>
              <b>
                Knowledge version {entry.publication.number}
                {position === 0 ? ' (current)' : ''}
              </b>
              <p>
                {summary(entry, type)}
                {source ? `. Source: ${source}` : ''}
              </p>
            </div>
            <div className="rv-k-timeline__side">
              <span>{longDate(entry.publication.createdAt)}</span>
              {isViewing ? (
                <span className="rv-stb">Viewing</span>
              ) : (
                <button
                  type="button"
                  className="rv-btn rv-btn--compact"
                  aria-label={`View knowledge version ${entry.publication.number}`}
                  onClick={() => onView(entry.publication.number)}
                >
                  View
                </button>
              )}
            </div>
          </li>
        )
      })}
    </ul>
  )
}
