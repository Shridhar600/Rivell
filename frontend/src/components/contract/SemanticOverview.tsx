import type { SemanticContract } from '../../api/semantics'
import {
  CONTRACT_TYPE_LABELS,
  JURISDICTION_LABELS,
  PAPER_SOURCE_LABELS,
  metadataFieldLabel,
  roleLabel,
} from '../../lib/contractSemantics'
import { QualityNotes } from './QualityNotes'
import { SourceRegionLinks } from './SourceRegionLinks'
import { ReRunAnalysis } from './ReRunAnalysis'

function ProfileSummary({ semantics }: { semantics: SemanticContract }) {
  const profile = semantics.reviewProfile
  return (
    <dl className="data-list profile-summary">
      <div className="data-list__row">
        <dt>Contract type</dt>
        <dd>{CONTRACT_TYPE_LABELS[profile.contractType] ?? profile.contractType}</dd>
      </div>
      <div className="data-list__row">
        <dt>Our side</dt>
        <dd>{profile.ourSideRole ? roleLabel(profile.ourSideRole) : 'Not specified'}</dd>
      </div>
      <div className="data-list__row">
        <dt>Paper source</dt>
        <dd>
          {profile.paperSource
            ? PAPER_SOURCE_LABELS[profile.paperSource] ?? profile.paperSource
            : 'Not specified'}
        </dd>
      </div>
      <div className="data-list__row">
        <dt>Jurisdiction</dt>
        <dd>
          {profile.jurisdiction
            ? JURISDICTION_LABELS[profile.jurisdiction] ?? profile.jurisdiction
            : 'Not specified'}
        </dd>
      </div>
    </dl>
  )
}

function MetadataList({
  semantics,
  labelByNodeId,
  onNavigateToNode,
}: {
  semantics: SemanticContract
  labelByNodeId: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
}) {
  if (semantics.metadata.length === 0) {
    return (
      <p className="semantic-empty">
        No metadata was detected in this contract version.
      </p>
    )
  }

  return (
    <dl className="data-list metadata-list">
      {semantics.metadata.map((fact) => (
        <div className="data-list__row" key={fact.field}>
          <dt>{metadataFieldLabel(fact.field)}</dt>
          <dd>
            <span className="metadata-list__value">
              {fact.corrected && fact.correctedValue !== null
                ? fact.correctedValue
                : fact.value}
            </span>
            {fact.corrected && fact.correctedValue !== null ? (
              <span className="badge badge--edited">Corrected</span>
            ) : null}
            {fact.corrected && fact.correctedValue === null ? (
              <span className="metadata-list__source">Correction recorded</span>
            ) : null}
            {fact.sourceNodeIds.length > 0 ? (
              <span className="metadata-list__source">
                <SourceRegionLinks
                  nodeIds={fact.sourceNodeIds}
                  labelByNodeId={labelByNodeId}
                  onNavigateToNode={onNavigateToNode}
                  prefix="Source: "
                />
              </span>
            ) : null}
          </dd>
        </div>
      ))}
    </dl>
  )
}

function Coverage({ semantics }: { semantics: SemanticContract }) {
  const rows: Array<[string, number]> = [
    ['Parties', semantics.parties.length],
    ['Defined terms', semantics.definedTerms.length],
    ['Cross-references', semantics.references.length],
    ['Clauses', semantics.clauses.length],
  ]
  if (semantics.structuralRegions.length > 0) {
    rows.push(['Schedules and other regions', semantics.structuralRegions.length])
  }
  rows.push(['Relationships', semantics.relationships.length])
  return (
    <dl className="data-list coverage-list">
      {rows.map(([label, count]) => (
        <div className="data-list__row" key={label}>
          <dt>{label}</dt>
          <dd>{count}</dd>
        </div>
      ))}
    </dl>
  )
}

export function SemanticOverview({
  semantics,
  contractId,
  versionNumber,
  labelByNodeId,
  onNavigateToNode,
}: {
  semantics: SemanticContract
  contractId: string
  versionNumber: number
  labelByNodeId: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
}) {
  return (
    <section aria-labelledby="semantic-overview-heading">
      <h2 id="semantic-overview-heading" tabIndex={-1} data-section-heading>
        Overview
      </h2>
      <div className="semantic-columns">
        <div className="panel">
          <h3>Interpretation profile</h3>
          <ProfileSummary semantics={semantics} />
          <ReRunAnalysis
            contractId={contractId}
            versionNumber={versionNumber}
            storedProfile={semantics.reviewProfile}
          />
        </div>
        <div className="panel">
          <h3>Detected metadata</h3>
          <MetadataList
            semantics={semantics}
            labelByNodeId={labelByNodeId}
            onNavigateToNode={onNavigateToNode}
          />
        </div>
        <div className="panel">
          <h3>Detected semantics</h3>
          <Coverage semantics={semantics} />
        </div>
      </div>
      <QualityNotes notes={semantics.quality} observations={semantics.observations} />
    </section>
  )
}
