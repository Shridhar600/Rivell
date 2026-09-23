import { useState, type FormEvent, type RefObject } from 'react'
import { Link } from 'react-router'
import {
  originalArtifactUrl,
  useApproveCandidate,
  useRejectCandidate,
  type ArtifactDetail,
  type Candidate,
  type KnowledgeNode,
  type Publication,
} from '../../api/knowledge'
import {
  effectiveContentText,
  effectivePayloadColumns,
  isReviewable,
  resolveCandidateSource,
} from '../../lib/candidates'
import {
  CANDIDATE_TYPE_LABELS,
  REVIEW_STATUS_LABELS,
  UNIT_KIND_LABELS,
} from '../../lib/knowledgeLabels'
import { compareKnowledgePosition, nodeSpanLabel } from '../../lib/knowledgeTree'
import {
  conceptLabel,
  interpretationFor,
  scopeDifferences,
} from '../../lib/semantics'
import type { ExtractionUnit } from '../../api/knowledge'
import { ErrorState } from '../ErrorState'
import { CandidateEditForm } from './CandidateEditForm'
import { ConflictComparison } from './ConflictComparison'
import { ReviewAttentionSection } from './ReviewAttention'
import {
  InterpretationProvenance,
  SemanticFieldReview,
} from './SemanticFieldReview'

function SourceNodeView({
  node,
  allNodes,
}: {
  node: KnowledgeNode
  allNodes: KnowledgeNode[]
}) {
  if (node.nodeType === 'TABLE') {
    const rows = allNodes
      .filter((entry) => entry.parentId === node.id && entry.nodeType === 'TABLE_ROW')
      .sort(compareKnowledgePosition)
    return (
      <table className="doc-table">
        <tbody>
          {rows.map((row) => {
            const cells = allNodes
              .filter(
                (entry) => entry.parentId === row.id && entry.nodeType === 'TABLE_CELL',
              )
              .sort(compareKnowledgePosition)
            return (
              <tr key={row.id}>
                {cells.map((cell) => (
                  <td key={cell.id}>{cell.text ?? ''}</td>
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    )
  }

  if (node.nodeType === 'SPREADSHEET_ROW') {
    const cells = allNodes
      .filter(
        (entry) =>
          entry.parentId === node.id && entry.nodeType === 'SPREADSHEET_CELL',
      )
      .sort(compareKnowledgePosition)
    return (
      <p className="source-region__row">
        {cells.map((cell) => cell.text ?? '').join('  |  ')}
      </p>
    )
  }

  if (node.text) {
    return <p className="source-region__text">{node.text}</p>
  }
  return null
}

function ScopeRow({
  label,
  value,
}: {
  label: string
  value: string | null
}) {
  return (
    <div className="data-list__row">
      <dt>{label}</dt>
      <dd>
        {value === null || value.trim().length === 0 ? (
          <span className="meta">Not set</span>
        ) : (
          value
        )}
      </dd>
    </div>
  )
}

export function CandidateReviewPanel({
  candidate,
  headingRef,
  artifact,
  units,
  nodes,
  sourceContextPending,
  publishedIn,
  supersessionChoice,
  onSupersessionChange,
}: {
  candidate: Candidate
  headingRef: RefObject<HTMLHeadingElement | null>
  artifact: ArtifactDetail
  units: ExtractionUnit[]
  nodes: KnowledgeNode[]
  sourceContextPending: boolean
  publishedIn?: Publication | null
  supersessionChoice: string | null
  onSupersessionChange: (peerId: string | null) => void
}) {
  const approve = useApproveCandidate()
  const reject = useRejectCandidate()
  const [editOpen, setEditOpen] = useState(false)
  const [rejectOpen, setRejectOpen] = useState(false)
  const [rejectNote, setRejectNote] = useState('')
  const [rejectError, setRejectError] = useState<string | null>(null)

  const { unit, nodes: sourceNodes } = resolveCandidateSource(
    candidate,
    units,
    nodes,
  )
  const scopeInterpretation = interpretationFor(candidate, 'scope')
  const reviewable = isReviewable(candidate)
  const effectiveText = effectiveContentText(candidate)
  const machineText = candidate.contentText
  const textDiverged = machineText !== null && machineText !== effectiveText
  const effectiveColumns = effectivePayloadColumns(candidate)
  const divergingColumns = Object.keys(candidate.payloadColumns).filter(
    (column) => candidate.payloadColumns[column] !== effectiveColumns[column],
  )
  const hasPayload = Object.keys(candidate.payloadColumns).length > 0
  const scopeDiffs = scopeDifferences(candidate, artifact)
  const narrowingApplied =
    scopeInterpretation !== null &&
    scopeInterpretation.proposedValue === (candidate.jurisdiction ?? '')

  function handleReject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (rejectNote.trim().length === 0) {
      setRejectError('Add a short reason for rejecting this candidate.')
      return
    }
    setRejectError(null)
    reject.mutate(
      { candidateId: candidate.id, note: rejectNote },
      { onSuccess: () => setRejectOpen(false) },
    )
  }

  function payloadEntries(): Array<[string, string, boolean]> {
    return Object.entries(effectiveColumns).map(([column, value]) => [
      column,
      value,
      candidate.editedPayloadColumns[column] !== undefined &&
        candidate.editedPayloadColumns[column] !== candidate.payloadColumns[column],
    ])
  }

  return (
    <article className="review-panel" aria-labelledby="candidate-heading">
      <header className="review-panel__header">
        <h3 id="candidate-heading" tabIndex={-1} ref={headingRef}>
          {candidate.rawLabel}
        </h3>
        <p className="meta">
          {CANDIDATE_TYPE_LABELS[candidate.candidateType]} ·{' '}
          <span className={`badge badge--${candidate.reviewStatus.toLowerCase()}`}>
            {REVIEW_STATUS_LABELS[candidate.reviewStatus]}
          </span>
          {candidate.reviewedAt ? ` · reviewed` : ''}
        </p>
      </header>

      <ReviewAttentionSection candidate={candidate} />

      <section aria-labelledby="candidate-extracted-heading">
        <h4 id="candidate-extracted-heading">Interpretation</h4>
        <dl className="data-list">
          <div className="data-list__row">
            <dt>Normalized legal concept</dt>
            <dd>
              {conceptLabel(candidate.legalConceptKey) ?? (
                <span className="meta">Not confidently classified</span>
              )}
            </dd>
          </div>
          <SemanticFieldReview candidate={candidate} />
          {effectiveText && (!hasPayload || textDiverged) ? (
            <div className="data-list__row">
              <dt>{hasPayload ? 'Extracted text' : 'Content'}</dt>
              <dd className="extracted-content">{effectiveText}</dd>
            </div>
          ) : null}
          {payloadEntries().map(([column, value, editedValue]) => (
            <div className="data-list__row" key={column}>
              <dt>
                {column}
                {editedValue ? <span className="meta"> (edited)</span> : null}
              </dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>

        {textDiverged || divergingColumns.length > 0 ? (
          <div className="machine-proposal">
            <h5>Machine proposal (preserved)</h5>
            {textDiverged && machineText ? (
              <p className="extracted-content">{machineText}</p>
            ) : null}
            {divergingColumns.length > 0 ? (
              <dl className="data-list">
                {divergingColumns.map((column) => (
                  <div className="data-list__row" key={column}>
                    <dt>{column}</dt>
                    <dd>{candidate.payloadColumns[column]}</dd>
                  </div>
                ))}
              </dl>
            ) : null}
          </div>
        ) : null}

        <InterpretationProvenance candidate={candidate} />
      </section>

      <section aria-labelledby="candidate-source-heading">
        <h4 id="candidate-source-heading">Source</h4>
        {sourceContextPending ? (
          <p className="meta" role="status">
            Loading source context…
          </p>
        ) : unit ? (
          <>
            <p className="meta">
              {UNIT_KIND_LABELS[unit.kind]}
              {unit.title ? ` · ${unit.title}` : ''} · unit {unit.ordinal + 1}
            </p>
            <div className="source-region">
              {sourceNodes.length > 0 ? (
                sourceNodes.map((node) => (
                  <SourceNodeView key={node.id} node={node} allNodes={nodes} />
                ))
              ) : (
                <p className="meta">
                  The source region is not available in the extracted structure.
                </p>
              )}
            </div>
          </>
        ) : (
          <p className="meta">
            The extraction unit for this candidate is not available.
          </p>
        )}
      </section>

      <section aria-labelledby="candidate-scope-heading">
        <h4 id="candidate-scope-heading">Applicability</h4>
        <dl className="data-list">
          <div className="data-list__row">
            <dt>Declared contract types</dt>
            <dd>
              {candidate.declaredContractTypes.length > 0 ? (
                candidate.declaredContractTypes.join(', ')
              ) : (
                <span className="meta">Not set</span>
              )}
            </dd>
          </div>
          <ScopeRow label="Contract pack" value={candidate.contractPackKey} />
          <ScopeRow label="Party perspective" value={candidate.partyPerspective} />
          <ScopeRow label="Paper source" value={candidate.paperSource} />
          <ScopeRow label="Jurisdiction" value={candidate.jurisdiction} />
          <ScopeRow
            label="Effective from"
            value={candidate.effectiveFrom}
          />
          <ScopeRow label="Effective to" value={candidate.effectiveTo} />
        </dl>
        {scopeDiffs.length > 0 ? (
          <p className="meta">
            Adjusted from the artifact manifest:{' '}
            {scopeDiffs
              .map(
                (difference) =>
                  `${difference.label} (manifest: ${difference.manifestValue ?? 'not set'})`,
              )
              .join('; ')}
            .
          </p>
        ) : null}
        {scopeInterpretation && !narrowingApplied ? (
          <p className="meta">
            Rivell suggests narrowing jurisdiction to{' '}
            {scopeInterpretation.proposedValue} based on the source. The manifest
            scope stays effective until a human applies it.
          </p>
        ) : null}
      </section>

      {candidate.conflictSignals.length > 0 ||
      candidate.supersedingCandidateId ? (
        <section aria-labelledby="candidate-analysis-heading">
          <h4 id="candidate-analysis-heading">Published knowledge comparison</h4>
          <ConflictComparison
            candidate={candidate}
            supersessionChoice={supersessionChoice}
            onSupersessionChange={onSupersessionChange}
          />
        </section>
      ) : null}

      <section aria-labelledby="candidate-provenance-heading">
        <h4 id="candidate-provenance-heading">Provenance</h4>
        <ol className="provenance">
          <li>Candidate {candidate.id}</li>
          <li>
            {unit
              ? `Extraction unit ${unit.ordinal + 1} (${UNIT_KIND_LABELS[unit.kind]})`
              : 'Extraction unit unavailable'}
          </li>
          <li>
            {sourceNodes.length > 0
              ? `Source nodes: ${sourceNodes.map((node) => nodeSpanLabel(node)).join('; ')}`
              : 'Source nodes unavailable'}
          </li>
          <li>
            {artifact.filename} · sha256 {artifact.sha256}{' '}
            <a href={originalArtifactUrl(artifact.id)} download={artifact.filename}>
              download original
            </a>
          </li>
        </ol>
      </section>

      <section aria-labelledby="candidate-review-heading">
        <h4 id="candidate-review-heading">Review</h4>

        {!reviewable ? (
          candidate.reviewStatus === 'PUBLISHED' ? (
            <p className="state state--success" role="status">
              Published knowledge is immutable. This candidate is part of a
              published version
              {publishedIn ? (
                <>
                  {' — '}
                  <Link to={`/knowledge/publications/${publishedIn.id}`}>
                    publication v{publishedIn.publicationNumber}
                  </Link>
                </>
              ) : (
                '.'
              )}
            </p>
          ) : (
            <p className="state" role="status">
              This candidate was rejected
              {candidate.reviewNote ? `: ${candidate.reviewNote}` : '.'}
            </p>
          )
        ) : (
          <>
            {candidate.reviewNote ? (
              <p className="meta">Review note: {candidate.reviewNote}</p>
            ) : null}

            <div className="review-actions">
              {candidate.reviewStatus !== 'APPROVED' ? (
                <button
                  type="button"
                  className="button button--primary"
                  disabled={approve.isPending}
                  onClick={() => approve.mutate({ candidateId: candidate.id })}
                >
                  Approve
                </button>
              ) : null}
              <button
                type="button"
                className="button"
                disabled={editOpen}
                onClick={() => setEditOpen(true)}
              >
                Edit
              </button>
              <button
                type="button"
                className="button button--danger"
                disabled={rejectOpen}
                onClick={() => setRejectOpen(true)}
              >
                Reject
              </button>
            </div>

            {candidate.reviewStatus === 'APPROVED' ? (
              <p className="meta">
                Approved candidates can be published as a new knowledge version.
              </p>
            ) : null}
            {candidate.reviewStatus === 'EDITED' ? (
              <p className="meta">
                Edited candidates must be approved before they can be published.
              </p>
            ) : null}

            {approve.isError ? (
              <ErrorState message={approve.error.message || 'Approval failed.'} />
            ) : null}

            {editOpen ? (
              <CandidateEditForm
                candidate={candidate}
                artifact={artifact}
                onCancel={() => setEditOpen(false)}
              />
            ) : null}

            {rejectOpen ? (
              <form
                className="reject-form"
                aria-label="Reject candidate"
                onSubmit={handleReject}
              >
                <div className="field">
                  <label htmlFor="reject-note">Reason for rejection</label>
                  <textarea
                    id="reject-note"
                    rows={2}
                    value={rejectNote}
                    disabled={reject.isPending}
                    onChange={(event) => setRejectNote(event.target.value)}
                  />
                </div>
                {rejectError ? (
                  <p className="field__error" role="alert">
                    {rejectError}
                  </p>
                ) : null}
                <div className="review-actions">
                  <button
                    type="submit"
                    className="button button--danger"
                    disabled={reject.isPending}
                  >
                    Reject candidate
                  </button>
                  <button
                    type="button"
                    className="button"
                    disabled={reject.isPending}
                    onClick={() => setRejectOpen(false)}
                  >
                    Cancel
                  </button>
                </div>
                {reject.isError ? (
                  <ErrorState message={reject.error.message || 'Rejection failed.'} />
                ) : null}
              </form>
            ) : null}
          </>
        )}
      </section>
    </article>
  )
}
