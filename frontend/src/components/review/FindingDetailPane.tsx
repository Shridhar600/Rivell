import { Link } from 'react-router'
import type { EvidenceItem } from '../../api/retrieval'
import type { FindingClaim, FindingDetail } from '../../api/reviews'
import type { HumanDecision, HumanReview } from '../../api/workflow'
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
import { authorityLabel } from '../../lib/evidence'
import { FindingEvidencePanel, type RelatedProvision } from './FindingEvidencePanel'
import { HumanDecisionControls } from './HumanDecisionControls'

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
  clauseHeadings: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
  onOpenClauseSemantics: (headingNodeId: string) => void
  reviewId: string
  currentDecision: HumanDecision | null
  humanReview: HumanReview | null
  isFinalized: boolean
  showDecisionControls: boolean
  canDecide: boolean
  analysisComplete: boolean
  decisionsLoaded: boolean
  decisionsTruncated: boolean
}

function ClaimEvidenceRefs({
  claim,
  evidenceById,
}: {
  claim: FindingClaim
  evidenceById: Map<string, EvidenceItem>
}) {
  if (claim.evidenceItemIds.length === 0) return null
  return (
    <p className="finding-claims__refs">
      Evidence:{' '}
      {claim.evidenceItemIds.map((id, index) => {
        const item = evidenceById.get(id)
        return (
          <span key={id}>
            {index > 0 ? ', ' : ''}
            {item ? (
              <a href={`#finding-evidence-${id}`}>{authorityLabel(item.authorityClass)}</a>
            ) : (
              <span className="finding-claims__missing">unavailable in this response</span>
            )}
          </span>
        )
      })}
    </p>
  )
}

export function FindingDetailView({
  finding,
  regionLabels,
  contractId,
  clauseSource,
  sourceVersion,
  latestVersion,
  clauseHeadings,
  onNavigateToNode,
  onOpenClauseSemantics,
  reviewId,
  currentDecision,
  humanReview,
  isFinalized,
  showDecisionControls,
  canDecide,
  analysisComplete,
  decisionsLoaded,
  decisionsTruncated,
}: Props) {
  const headingRef = useHeadingFocus<HTMLHeadingElement>(finding.id)
  const why = whyClaims(finding.claims)
  const recommendations = recommendationClaims(finding.claims)
  const absences = absenceClaims(finding.claims)
  const qualityRows = qualityFactorRows(finding.quality)
  const concepts = findingConceptLabels(finding)
  const policyNote = policyNoteText(finding)
  const evidenceById = new Map(
    finding.evidenceItems.map((item) => [item.evidenceId, item]),
  )
  const hasUnresolvedClaimEvidence = finding.claims.some((claim) =>
    claim.evidenceItemIds.some((id) => !evidenceById.has(id)),
  )
  const subjectNodeId = finding.subjectHeadingNodeId
  const subjectClauseId = subjectNodeId
    ? ([...clauseHeadings.entries()].find(([, heading]) => heading === subjectNodeId)?.[0] ?? null)
    : null
  const relatedProvisions: RelatedProvision[] = [
    ...new Map(
      finding.evidenceItems
        .filter(
          (item) =>
            item.authorityClass === 'CURRENT_CONTRACT' &&
            item.sourceType === 'CURRENT_CONTRACT_CLAUSE' &&
            item.sourceClauseId !== null &&
            item.sourceClauseId !== subjectClauseId,
        )
        .map((item) => {
          const headingNodeId = clauseHeadings.get(item.sourceClauseId as string)
          if (!headingNodeId) return null
          return [headingNodeId, regionLabels.get(headingNodeId) ?? 'Contract provision'] as [
            string,
            string,
          ]
        })
        .filter((entry): entry is [string, string] => entry !== null),
    ).entries(),
  ].map(([nodeId, label]) => ({ nodeId, label }))
  const canDeepLink =
    sourceVersion !== null && latestVersion !== null && sourceVersion === latestVersion

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

      {hasUnresolvedClaimEvidence ? (
        <p className="state state--warning" role="status">
          Some statements reference supporting evidence that is not available in this response;
          they are not shown as grounded.
        </p>
      ) : null}

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
                  <ClaimEvidenceRefs claim={claim} evidenceById={evidenceById} />
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
                <ClaimEvidenceRefs claim={claim} evidenceById={evidenceById} />
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <section className="finding-section" aria-labelledby="finding-recommendation-heading">
        <h3 id="finding-recommendation-heading">Recommended action</h3>
        <p>{proposedActionLabel(finding.proposedAction)}</p>
        {recommendations.map((claim) => (
          <div key={claim.ordinal}>
            <p>{claim.text}</p>
            <ClaimEvidenceRefs claim={claim} evidenceById={evidenceById} />
          </div>
        ))}
      </section>

      {finding.proposedLanguage &&
      finding.proposedLanguageSource === 'APPROVED_DRAFTING' &&
      finding.approvedDraftingEvidenceId &&
      evidenceById.get(finding.approvedDraftingEvidenceId)?.authorityClass ===
        'APPROVED_DRAFTING' ? (
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

      {showDecisionControls ? (
        <HumanDecisionControls
          key={finding.id}
          reviewId={reviewId}
          finding={finding}
          currentDecision={currentDecision}
          humanReview={humanReview}
          isFinalized={isFinalized}
          canDecide={canDecide}
          analysisComplete={analysisComplete}
          decisionsLoaded={decisionsLoaded}
          decisionsTruncated={decisionsTruncated}
        />
      ) : null}

      {finding.evidenceItems.length > 0 ? (
        <FindingEvidencePanel
          items={finding.evidenceItems}
          relatedProvisions={relatedProvisions}
          canDeepLink={canDeepLink}
          contractId={contractId}
          regionLabels={regionLabels}
          clauseHeadings={clauseHeadings}
          onNavigateToNode={onNavigateToNode}
          onOpenClauseSemantics={onOpenClauseSemantics}
        />
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
  clauseHeadings,
  onNavigateToNode,
  onOpenClauseSemantics,
  reviewId,
  currentDecision,
  humanReview,
  isFinalized,
  showDecisionControls,
  canDecide,
  analysisComplete,
  decisionsLoaded,
  decisionsTruncated,
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
  clauseHeadings: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
  onOpenClauseSemantics: (headingNodeId: string) => void
  reviewId: string
  currentDecision: HumanDecision | null
  humanReview: HumanReview | null
  isFinalized: boolean
  showDecisionControls: boolean
  canDecide: boolean
  analysisComplete: boolean
  decisionsLoaded: boolean
  decisionsTruncated: boolean
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
      clauseHeadings={clauseHeadings}
      onNavigateToNode={onNavigateToNode}
      onOpenClauseSemantics={onOpenClauseSemantics}
      reviewId={reviewId}
      currentDecision={currentDecision}
      humanReview={humanReview}
      isFinalized={isFinalized}
      showDecisionControls={showDecisionControls}
      canDecide={canDecide}
      analysisComplete={analysisComplete}
      decisionsLoaded={decisionsLoaded}
      decisionsTruncated={decisionsTruncated}
    />
  )
}
