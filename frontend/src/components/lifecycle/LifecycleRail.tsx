import { Link } from 'react-router'
import type { ReviewDocument, ReviewSummary } from '../../api/reviews'
import { contextRows, pluralise, type ContextRow } from '../../lib/lifecycleView'
import { dateTime } from '../../lib/dates'
import { formatBytes } from '../../lib/knowledgeLabels'
import type { ActivityEvent } from '../../lib/reviewLifecycle'
import { ArrowIcon } from './icons'

interface Props {
  summary: ReviewSummary
  /** The pinned version's uploaded document, as the Review itself reports it. */
  document: ReviewDocument | null
  packLabel: string | null
  events: ActivityEvent[]
}

/**
 * The Inspector content, ported from the canonical `insHub()`. Every value is read from the
 * Review itself: the version it pins, the document behind that version, the context frozen at
 * creation, and the moments the server recorded.
 */
export function LifecycleRail({ summary, document, packLabel, events }: Props) {
  const snapshot = summary.snapshot
  const profile = snapshot?.profile ?? null
  const version = summary.contractVersionNumber
  const context: ContextRow[] = contextRows(profile, packLabel)
  const knowledge =
    snapshot?.knowledgePinNumber == null
      ? 'Not recorded'
      : snapshot.knowledgePinNumber > 0
        ? `Publication ${snapshot.knowledgePinNumber}`
        : 'None published at creation'
  return (
    <div className="rv-lc-rail">
      <div>
        <h3>Contract version</h3>
        <dl className="rv-lc-fdl rv-lc-fdl--rail">
          {document ? (
            <div>
              <dt>File</dt>
              <dd>{document.contractFilename}</dd>
            </div>
          ) : null}
          <div>
            <dt>Version</dt>
            <dd>{version !== null ? `v${version}, immutable` : 'Not recorded'}</dd>
          </div>
          {document ? (
            <div>
              <dt>Uploaded</dt>
              <dd>{dateTime(document.uploadedAt)}</dd>
            </div>
          ) : null}
          {document ? (
            <div>
              <dt>Size</dt>
              <dd>{formatBytes(document.sizeBytes)}</dd>
            </div>
          ) : null}
        </dl>
      </div>
      <div>
        <h3>Confirmed review context</h3>
        {profile ? (
          <dl className="rv-lc-fdl rv-lc-fdl--rail">
            {context.map((row) => (
              <div key={row.label}>
                <dt>{row.label}</dt>
                <dd>{row.value}</dd>
              </div>
            ))}
          </dl>
        ) : (
          <p className="rv-lc-quiet">The frozen review context is not available for this review.</p>
        )}
      </div>
      <div>
        <h3>Frozen review snapshot</h3>
        <p className="rv-lc-quiet">Knowledge and review context are pinned to this review.</p>
        <dl className="rv-lc-fdl rv-lc-fdl--rail">
          <div>
            <dt>Knowledge</dt>
            <dd>{knowledge}</dd>
          </div>
          {snapshot?.packVersion ? (
            <div>
              <dt>Pack version</dt>
              <dd>{snapshot.packVersion}</dd>
            </div>
          ) : null}
          {snapshot?.effectiveOn ? (
            <div>
              <dt>Effective on</dt>
              <dd>{snapshot.effectiveOn}</dd>
            </div>
          ) : null}
        </dl>
        <Link className="rv-lc-lnk" to="/knowledge">
          Open Knowledge <ArrowIcon className="rv-icon" />
        </Link>
      </div>
      <div>
        <h3>Lifecycle activity</h3>
        <ol className="rv-lc-ev" aria-label={`Lifecycle activity, ${pluralise(events.length, 'event')}`}>
          {events.map((event) => (
            <li key={`${event.label}-${event.time}`}>
              <i className={event.failed ? 'rv-lc-ev__x' : undefined} aria-hidden="true" />
              <b>{event.label}</b>
              <time dateTime={event.time}>{dateTime(event.time)}</time>
            </li>
          ))}
        </ol>
      </div>
    </div>
  )
}
