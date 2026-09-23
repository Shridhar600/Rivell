import type { IngestStatus } from '../../api/knowledge'
import { INGEST_STATUS_LABELS } from '../../lib/knowledgeLabels'
import { warningLabel } from '../../lib/warnings'

function WarningList({ warnings }: { warnings: string[] }) {
  return (
    <ul className="parse-notice__warnings">
      {warnings.map((warning) => (
        <li key={warning}>{warningLabel(warning)}</li>
      ))}
    </ul>
  )
}

export function IngestionNotice({
  status,
  warnings,
}: {
  status: IngestStatus
  warnings: string[]
}) {
  if (status === 'FAILED') {
    return (
      <section
        className="state state--error parse-notice"
        role="alert"
        aria-labelledby="ingestion-notice-heading"
      >
        <h2 id="ingestion-notice-heading">Knowledge ingestion failed</h2>
        <p>
          Rivell could not compile this source. The original file remains
          available to view or download.
        </p>
        {warnings.length > 0 ? <WarningList warnings={warnings} /> : null}
      </section>
    )
  }

  if (status === 'RECEIVED' || status === 'EXTRACTING') {
    return (
      <p className="state" role="status">
        {INGEST_STATUS_LABELS[status]}: Rivell is storing and extracting this
        source. This page refreshes while extraction is in progress.
      </p>
    )
  }

  if (warnings.length === 0) return null

  return (
    <section
      className="state state--warning parse-notice"
      role="status"
      aria-labelledby="ingestion-notice-heading"
    >
      <h2 id="ingestion-notice-heading">Extracted with warnings</h2>
      <p>
        Rivell reported limitations while compiling this source. The structural
        view and candidates may not represent every part of it.
      </p>
      <WarningList warnings={warnings} />
    </section>
  )
}
