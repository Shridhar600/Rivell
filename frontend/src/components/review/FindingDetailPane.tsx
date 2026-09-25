import { Link } from 'react-router'
import type { FindingDetail } from '../../api/reviews'
import { conceptLabel } from '../../lib/contractSemantics'
import type { StructureNode } from '../../lib/documentTree'
import {
  absenceClaims,
  checkKeyLabel,
  claimKindLabel,
  classificationLabel,
  findingConceptLabels,
  findingSubjectLabel,
  issueFamilyLabel,
  observationKindLabel,
  policyNoteText,
  proposedActionLabel,
  qualityFactorRows,
  recommendationClaims,
  severityLabel,
  severityModifier,
  whyClaims,
} from '../../lib/reviews'
import { useHeadingFocus } from '../../lib/useHeadingFocus'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'
import { StructuralRenderer } from '../StructuralRenderer'

type ClauseSource = {
  nodeId: string
  heading: string
  nodes: StructureNode[]
}

type Props = {
  finding: FindingDetail
  regionLabels: Map<string, string>
  contractId: string
  clauseSource: ClauseSource | null
  sourceVersion: number | null
  latestVersion: number | null
}

export function FindingDetailView({
  finding,
  regionLabels,
  contractId,
  clauseSource,
  sourceVersion,
  latestVersion,
}: Props) {
  const headingRef = useHeadingFocus<HTMLHeadingElement>(finding.id)
  const why = whyClaims(finding.claims)
  const recommendations = recommendationClaims(finding.claims)
  const absences = absenceClaims(finding.claims)
  const qualityRows = qualityFactorRows(finding.quality)
  const concepts = findingConceptLabels(finding)
  const policyNote = policyNoteText(finding)

  return (
    <article className="finding-detail" aria-labelledby="finding-heading">
      <header className="finding-detail__header">
        <h2 id="finding-heading" tabIndex={-1} ref={headingRef}>
          {findingSubjectLabel(finding, regionLabels)}
        </h2>
        <p className="finding-detail__meta">
          <span className={`badge ${severityModifier(finding.severity)}`}>
            Severity: {severityLabel(finding.severity)}
          </span>
          <span className="badge badge--muted">
            Review classification: {classificationLabel(finding.reviewClassification)}
          </span>
          <span>{issueFamilyLabel(finding.issueFamily)}</span>
        </p>
        <p className="finding-detail__concepts">
          {concepts.length > 0 ? concepts.join(' · ') : conceptLabel('UNKNOWN')}
        </p>
      </header>

      {finding.subjectKind === 'ABSENCE' ? (
        <section className="finding-section" aria-labelledby="finding-absence-heading">
          <h3 id="finding-absence-heading">Missing provision</h3>
          <p>
            <strong>Required provision missing:</strong>{' '}
            {finding.absenceConcept ? conceptLabel(finding.absenceConcept) : 'Unknown provision'}
          </p>
          {finding.absenceBasis ? (
            <p className="panel__hint">
              Expected by the review plan ({checkKeyLabel(finding.absenceBasis.checkKey)}).
            </p>
          ) : null}
          {absences.length > 0 ? (
            <ul className="finding-claims">
              {absences.map((claim) => (
                <li key={claim.ordinal}>
                  <span className="finding-claims__kind">{claimKindLabel(claim.claimKind)}</span>
                  <p>{claim.text}</p>
                </li>
              ))}
            </ul>
          ) : null}
        </section>
      ) : null}

      <section className="finding-section" aria-labelledby="finding-observation-heading">
        <h3 id="finding-observation-heading">Observation</h3>
        <p className="finding-observation__kind">
          {observationKindLabel(finding.observationKind)}
        </p>
        <p>{finding.observationText}</p>
      </section>

      {finding.subjectKind === 'CLAUSE' && clauseSource ? (
        <section className="finding-section" aria-labelledby="finding-source-heading">
          <h3 id="finding-source-heading">Contract clause</h3>
          {clauseSource.nodes.length > 0 ? (
            <StructuralRenderer
              nodes={clauseSource.nodes}
              selectedId={clauseSource.nodeId}
              onSelect={() => undefined}
              selectable={false}
              label="Contract clause source"
            />
          ) : (
            <p>{clauseSource.heading}</p>
          )}
          <p>
            {sourceVersion !== null &&
            latestVersion !== null &&
            sourceVersion === latestVersion ? (
              <Link
                className="link-button"
                to={`/reviews/${contractId}?view=document&node=${clauseSource.nodeId}`}
              >
                View clause in contract
              </Link>
            ) : (
              <>
                {sourceVersion !== null && latestVersion !== null
                  ? `This review is pinned to v${sourceVersion}; the contract's current version is v${latestVersion}. `
                  : 'The contract version for this review could not be confirmed. '}
                <Link className="link-button" to={`/reviews/${contractId}`}>
                  Open the contract
                </Link>
              </>
            )}
          </p>
        </section>
      ) : null}

      {policyNote ? <p className="finding-detail__note">{policyNote}</p> : null}

      {why.length > 0 ? (
        <section className="finding-section" aria-labelledby="finding-why-heading">
          <h3 id="finding-why-heading">Why</h3>
          <ul className="finding-claims">
            {why.map((claim) => (
              <li key={claim.ordinal}>
                <span className="finding-claims__kind">{claimKindLabel(claim.claimKind)}</span>
                <p>{claim.text}</p>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <section className="finding-section" aria-labelledby="finding-recommendation-heading">
        <h3 id="finding-recommendation-heading">Recommended action</h3>
        <p>{proposedActionLabel(finding.proposedAction)}</p>
        {recommendations.map((claim) => (
          <p key={claim.ordinal}>{claim.text}</p>
        ))}
      </section>

      {finding.proposedLanguage &&
      finding.proposedLanguageSource === 'APPROVED_DRAFTING' &&
      finding.approvedDraftingEvidenceId ? (
        <section className="finding-section" aria-labelledby="finding-language-heading">
          <h3 id="finding-language-heading">Approved language</h3>
          <p>
            <span className="badge badge--approved">Approved</span>
          </p>
          <blockquote className="finding-language__text">
            {finding.proposedLanguage}
          </blockquote>
          <p className="panel__hint">
            Approved organizational wording, quoted verbatim from the published policy.
          </p>
        </section>
      ) : null}

      {finding.proposedLanguage && finding.proposedLanguageSource === 'GENERATIVE' ? (
        <section className="finding-section" aria-labelledby="finding-language-heading">
          <h3 id="finding-language-heading">AI-proposed language</h3>
          <p>
            <span className="badge badge--pending">Machine-proposed</span>
          </p>
          <blockquote className="finding-language__text">
            {finding.proposedLanguage}
          </blockquote>
          <p className="panel__hint">
            This wording was produced by a model for consideration. It is not approved
            organizational language.
          </p>
        </section>
      ) : null}

      {qualityRows.length > 0 ? (
        <section className="finding-section" aria-labelledby="finding-quality-heading">
          <h3 id="finding-quality-heading">System assessment</h3>
          <ul className="finding-quality">
            {qualityRows.map((row, index) => (
              <li key={`${row}-${index}`}>{row}</li>
            ))}
          </ul>
        </section>
      ) : null}

      <p className="finding-detail__note">Machine-generated finding.</p>
    </article>
  )
}

export function FindingDetailPane({
  finding,
  regionLabels,
  contractId,
  clauseSource,
  sourceVersion,
  latestVersion,
  findingId,
  isPending,
  isError,
  onRetry,
}: {
  finding: FindingDetail | undefined
  regionLabels: Map<string, string>
  contractId: string
  clauseSource: ClauseSource | null
  sourceVersion: number | null
  latestVersion: number | null
  findingId: string | null
  isPending: boolean
  isError: boolean
  onRetry: () => void
}) {
  if (!findingId) {
    return (
      <div className="finding-detail finding-detail--empty">
        <p>Select a finding to see its observation, policy basis, recommendation and language.</p>
      </div>
    )
  }

  if (isPending) return <LoadingState label="Loading finding…" />

  if (isError || !finding) {
    return <ErrorState message="This finding could not be loaded." onRetry={onRetry} />
  }

  return (
    <FindingDetailView
      finding={finding}
      regionLabels={regionLabels}
      contractId={contractId}
      clauseSource={clauseSource}
      sourceVersion={sourceVersion}
      latestVersion={latestVersion}
    />
  )
}
