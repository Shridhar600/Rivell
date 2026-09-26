import type { FindingSummary } from '../../api/reviews'
import type { ApprovedChangeSet, HumanDecision } from '../../api/workflow'
import { formatTimestamp } from '../../lib/knowledgeLabels'
import { changeSetCounts } from '../../lib/workflow'
import { ApprovedChangePreview } from './ApprovedChangePreview'

type Props = {
  changeSet: ApprovedChangeSet
  findings: FindingSummary[]
  decisionsByFindingId: Map<string, HumanDecision>
  regionLabels: Map<string, string>
  contractId: string
  canDeepLink: boolean
  onSelectFinding: (findingId: string) => void
}

function countsLabel(changeSet: ApprovedChangeSet): string {
  const counts = changeSetCounts(changeSet.changes)
  const parts: string[] = []
  if (counts.replace > 0) parts.push(`${counts.replace} replacement(s)`)
  if (counts.insert > 0) parts.push(`${counts.insert} insertion(s)`)
  if (counts.delete > 0) parts.push(`${counts.delete} deletion(s)`)
  return parts.length > 0 ? parts.join(' · ') : 'No changes'
}

export function ApprovedChangeSetSummary({
  changeSet,
  findings,
  decisionsByFindingId,
  regionLabels,
  contractId,
  canDeepLink,
  onSelectFinding,
}: Props) {
  const findingsById = new Map(findings.map((finding) => [finding.id, finding]))
  return (
    <section className="change-set" aria-labelledby="change-set-heading">
      <div className="change-set__header">
        <h2 id="change-set-heading">Approved change set</h2>
        <span className="status-badge status-badge--complete">Frozen</span>
      </div>
      <p className="panel__hint">
        Version {changeSet.changeSetVersion} · contract v{changeSet.contractVersionNumber} ·
        finalized {changeSet.finalizedAt ? formatTimestamp(changeSet.finalizedAt) : '—'}
      </p>
      <p className="change-set__counts">{countsLabel(changeSet)}</p>
      {changeSet.changes.length === 0 ? (
        <p>No contract changes were approved in this review.</p>
      ) : (
        <ol className="change-set__list" aria-label="Approved changes">
          {changeSet.changes.map((change) => (
            <li key={change.id}>
              <ApprovedChangePreview
                change={change}
                finding={findingsById.get(change.findingId)}
                decision={decisionsByFindingId.get(change.findingId)}
                regionLabels={regionLabels}
                contractId={contractId}
                canDeepLink={canDeepLink}
                onSelectFinding={onSelectFinding}
              />
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
