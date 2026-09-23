import type { Candidate, VerificationState } from '../../api/knowledge'
import {
  approvalDiverge,
  escalationDiverge,
  formatBoolean,
  formatPositions,
  humanizeCode,
  interpretationFor,
  positionsDiverge,
  proposedApproval,
  proposedEscalationTrigger,
  proposedPositions,
  proposedVariantRole,
  variantRoleDiverge,
  POSITION_LABELS,
  VERIFICATION_LABELS,
} from '../../lib/semantics'
import { VerificationBadge } from './VerificationBadge'

const KIND_LABELS: Record<string, string> = {
  DETERMINISTIC: 'Taken from a structured source value',
  BOUNDED_CLASSIFICATION: 'Classified from the source text',
  GENERATIVE_EXTRACTION: 'Extracted from prose',
}

function kindLabel(kind: string): string {
  return KIND_LABELS[kind] ?? humanizeCode(kind)
}

function showsVerification(
  verification: VerificationState,
  kind: string,
): boolean {
  return verification !== 'NOT_VERIFIED' || kind === 'GENERATIVE_EXTRACTION'
}

function SemanticRow({
  label,
  effective,
  proposed,
  diverged,
  verification,
  kind,
  support,
}: {
  label: string
  effective: string
  proposed: string | null
  diverged: boolean
  verification: VerificationState | null
  kind: string | null
  support: number
}) {
  return (
    <div className="data-list__row">
      <dt>{label}</dt>
      <dd className="semantic-value">
        <span className="semantic-value__effective">{effective}</span>
        {verification && kind && showsVerification(verification, kind) ? (
          <VerificationBadge state={verification} />
        ) : null}
        {support > 0 ? (
          <span className="meta">
            Supported by {support} source region{support === 1 ? '' : 's'}
          </span>
        ) : null}
        {diverged && proposed !== null ? (
          <p className="meta">Rivell proposed: {proposed}</p>
        ) : null}
      </dd>
    </div>
  )
}

export function SemanticFieldReview({ candidate }: { candidate: Candidate }) {
  const isPlaybook = candidate.candidateType === 'PLAYBOOK_RULE'
  const isClause = candidate.candidateType === 'CLAUSE_VARIANT'

  const positionsInterpretation = interpretationFor(candidate, 'positions')
  const variantInterpretation = interpretationFor(candidate, 'variantRole')
  const escalationInterpretation = interpretationFor(candidate, 'escalationTrigger')
  const approvalInterpretation = interpretationFor(candidate, 'requiresApproval')

  const proposedPositionList = proposedPositions(candidate)
  const proposedRole = proposedVariantRole(candidate)
  const proposedApprovalValue = proposedApproval(candidate)
  const proposedTrigger = proposedEscalationTrigger(candidate)

  const showPositions =
    (isPlaybook && candidate.positions.length > 0) || positionsInterpretation !== null
  const showVariant = isClause || variantInterpretation !== null
  const showEscalation =
    (isPlaybook && candidate.escalationTrigger !== null) ||
    escalationInterpretation !== null
  const showApproval =
    (isPlaybook && candidate.requiresApproval !== null) ||
    approvalInterpretation !== null

  if (!showPositions && !showVariant && !showEscalation && !showApproval) {
    return null
  }

  return (
    <>
      {showPositions ? (
        <SemanticRow
          label="Negotiation position"
          effective={formatPositions(candidate.positions)}
          proposed={
            proposedPositionList && proposedPositionList.length > 0
              ? formatPositions(proposedPositionList)
              : null
          }
          diverged={positionsDiverge(candidate)}
          verification={positionsInterpretation?.verification ?? null}
          kind={positionsInterpretation?.kind ?? null}
          support={positionsInterpretation?.supportingSourceNodes ?? 0}
        />
      ) : null}
      {showVariant ? (
        <SemanticRow
          label="Clause variant role"
          effective={POSITION_LABELS[candidate.variantRole ?? 'UNSPECIFIED']}
          proposed={proposedRole ? POSITION_LABELS[proposedRole] : null}
          diverged={variantRoleDiverge(candidate)}
          verification={variantInterpretation?.verification ?? null}
          kind={variantInterpretation?.kind ?? null}
          support={variantInterpretation?.supportingSourceNodes ?? 0}
        />
      ) : null}
      {showEscalation ? (
        <SemanticRow
          label="Escalation trigger"
          effective={candidate.escalationTrigger ?? 'None extracted'}
          proposed={proposedTrigger}
          diverged={escalationDiverge(candidate)}
          verification={escalationInterpretation?.verification ?? null}
          kind={escalationInterpretation?.kind ?? null}
          support={escalationInterpretation?.supportingSourceNodes ?? 0}
        />
      ) : null}
      {showApproval ? (
        <SemanticRow
          label="General counsel approval"
          effective={formatBoolean(candidate.requiresApproval)}
          proposed={
            proposedApprovalValue === null
              ? null
              : formatBoolean(proposedApprovalValue)
          }
          diverged={approvalDiverge(candidate)}
          verification={approvalInterpretation?.verification ?? null}
          kind={approvalInterpretation?.kind ?? null}
          support={approvalInterpretation?.supportingSourceNodes ?? 0}
        />
      ) : null}
    </>
  )
}

export function InterpretationProvenance({
  candidate,
}: {
  candidate: Candidate
}) {
  if (candidate.interpretations.length === 0) return null

  return (
    <div className="interpretation-provenance">
      <h5>Interpretation provenance</h5>
      <ul className="provenance">
        {candidate.interpretations.map((interpretation) => {
          const parts = [
            `${humanizeCode(interpretation.field)}: ${kindLabel(interpretation.kind)}`,
            `${interpretation.supportingSourceNodes} source region${
              interpretation.supportingSourceNodes === 1 ? '' : 's'
            }`,
          ]
          if (showsVerification(interpretation.verification, interpretation.kind)) {
            parts.push(VERIFICATION_LABELS[interpretation.verification].toLowerCase())
          }
          return <li key={interpretation.field}>{parts.join(' · ')}</li>
        })}
      </ul>
    </div>
  )
}
