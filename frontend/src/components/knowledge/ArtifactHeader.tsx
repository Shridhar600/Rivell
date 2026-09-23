import type { RefObject } from 'react'
import { originalArtifactUrl, type ArtifactDetail } from '../../api/knowledge'
import {
  ARTIFACT_TYPE_LABELS,
  formatBytes,
  formatTimestamp,
  INGEST_STATUS_LABELS,
} from '../../lib/knowledgeLabels'

export function ArtifactHeader({
  artifact,
  headingRef,
}: {
  artifact: ArtifactDetail
  headingRef: RefObject<HTMLHeadingElement | null>
}) {
  const originalUrl = originalArtifactUrl(artifact.id)

  return (
    <header className="workspace__header">
      <div>
        <h1 id="artifact-heading" tabIndex={-1} ref={headingRef}>
          {artifact.filename}
        </h1>
        <p className="meta">
          {ARTIFACT_TYPE_LABELS[artifact.artifactType]} ·{' '}
          <span className={`badge badge--${artifact.ingestStatus.toLowerCase()}`}>
            {INGEST_STATUS_LABELS[artifact.ingestStatus]}
          </span>{' '}
          · {formatBytes(artifact.sizeBytes)} · added{' '}
          {formatTimestamp(artifact.createdAt)}
        </p>
        <p className="meta meta--mono">sha256 {artifact.sha256}</p>
        {artifact.parserVersion ? (
          <p className="meta">parser {artifact.parserVersion}</p>
        ) : null}
      </div>
      <div className="workspace__actions">
        <a
          href={originalUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="button"
        >
          View original
        </a>
        <a href={originalUrl} download={artifact.filename} className="button">
          Download original
        </a>
      </div>
    </header>
  )
}
