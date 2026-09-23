import { useState } from 'react'
import {
  useBulkApproveCandidates,
  type BulkApprovalResult,
  type Candidate,
} from '../../api/knowledge'
import { isBulkEligible } from '../../lib/semantics'
import { ErrorState } from '../ErrorState'

export function BulkReviewToolbar({
  candidates,
  selectedIds,
  onToggleAll,
  onClear,
  artifactId,
}: {
  candidates: Candidate[]
  selectedIds: Set<string>
  onToggleAll: () => void
  onClear: () => void
  artifactId: string
}) {
  const bulk = useBulkApproveCandidates()
  const [result, setResult] = useState<BulkApprovalResult | null>(null)

  const eligible = candidates.filter(isBulkEligible)
  const selectedEligible = eligible.filter((candidate) =>
    selectedIds.has(candidate.id),
  )
  const allSelected = eligible.length > 0 && selectedEligible.length === eligible.length

  const labelById = new Map(
    candidates.map((candidate) => [candidate.id, candidate.rawLabel]),
  )

  function labelFor(candidateId: string): string {
    return labelById.get(candidateId) ?? candidateId
  }

  function approveSelected() {
    setResult(null)
    bulk.mutate(
      {
        candidateIds: selectedEligible.map((candidate) => candidate.id),
        artifactId,
      },
      {
        onSuccess: (outcome) => {
          setResult(outcome)
          onClear()
        },
      },
    )
  }

  const skippedEntries = result
    ? Object.entries(result.skipped)
    : []

  return (
    <section className="panel bulk-toolbar" aria-labelledby="bulk-heading">
      <div className="section-header">
        <h2 id="bulk-heading">Bulk review</h2>
        <button
          type="button"
          className="button button--primary"
          disabled={selectedEligible.length === 0 || bulk.isPending}
          onClick={approveSelected}
        >
          Approve selected ({selectedEligible.length})
        </button>
      </div>
      <p className="meta">
        {eligible.length > 0
          ? `${eligible.length} of ${candidates.length} candidates are eligible for bulk approval (pending, routine review attention). The server verifies eligibility and reports anything skipped.`
          : 'No candidates are currently eligible for bulk approval. Only pending candidates with routine review attention can be bulk approved; the server decides eligibility.'}
      </p>
      {eligible.length > 1 ? (
        <button type="button" className="button" onClick={onToggleAll}>
          {allSelected ? 'Clear selection' : `Select all ${eligible.length} eligible`}
        </button>
      ) : null}

      {result ? (
        <div className="bulk-result" role="status">
          <p>
            {`Approved ${result.approvedIds.length} candidate${
              result.approvedIds.length === 1 ? '' : 's'
            }.`}
          </p>
          {skippedEntries.length > 0 ? (
            <>
              <p>{skippedEntries.length} skipped by the server:</p>
              <ul>
                {skippedEntries.map(([candidateId, reason]) => (
                  <li key={candidateId}>
                    {labelFor(candidateId)}: {reason}
                  </li>
                ))}
              </ul>
            </>
          ) : null}
        </div>
      ) : null}

      {bulk.isError ? (
        <ErrorState message={bulk.error.message || 'Bulk approval failed.'} />
      ) : null}
    </section>
  )
}
