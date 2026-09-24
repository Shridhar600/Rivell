import { Link } from 'react-router'
import type { EvidenceItem } from '../../api/retrieval'
import {
  policyCompatibilityLabel,
  retrievalReasonLabel,
  sourceTypeLabel,
} from '../../lib/evidence'

export function EvidenceItemSource({ item }: { item: EvidenceItem }) {
  const sourceLabel = sourceTypeLabel(item.sourceType)
  const compatibility =
    item.authorityClass === 'PRECEDENT'
      ? policyCompatibilityLabel(item.policyCompatibility)
      : null

  return (
    <div className="evidence-source">
      <p className="evidence-source__line">
        {item.sourceArtifactId ? (
          <Link
            to={`/knowledge/artifacts/${item.sourceArtifactId}`}
            aria-label={`Open source artifact: ${sourceLabel}`}
            className="link-inline"
          >
            {sourceLabel}
          </Link>
        ) : (
          <span>{sourceLabel}</span>
        )}
        {item.publicationNumber !== null ? (
          <>
            {' · '}
            {item.publicationId ? (
              <Link
                to={`/knowledge/publications/${item.publicationId}`}
                aria-label={`Open publication v${item.publicationNumber}`}
                className="link-inline"
              >
                {`Published in v${item.publicationNumber}`}
              </Link>
            ) : (
              <span>{`Published in v${item.publicationNumber}`}</span>
            )}
          </>
        ) : null}
        {' · '}
        <span className="evidence-source__reason">
          {`Why: ${retrievalReasonLabel(item.retrievalReason)}`}
        </span>
      </p>
      {compatibility ? (
        <p className="evidence-source__compatibility">
          <span className="badge badge--muted">{compatibility}</span>
        </p>
      ) : null}
    </div>
  )
}
