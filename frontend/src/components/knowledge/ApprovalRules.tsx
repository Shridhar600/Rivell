import type { AuthorityRule } from '../../api/knowledge'
import { AUTHORITY_SOURCE_LABELS } from '../../lib/compilationFields'
import { approvalSituationLabel } from '../../lib/knowledgePosition'

/**
 * The typed approval rules as a small table. A reader sees the situation that needs approval,
 * who approves it, and where the rule was stated; the source's own condition stays with the
 * situation, and joint approvers stay in the approver cell.
 */
export function ApprovalRulesTable({ rules }: { rules: AuthorityRule[] }) {
  if (rules.length === 0) return null
  return (
    <table className="rv-k-appr">
      <thead>
        <tr>
          <th scope="col">Situation</th>
          <th scope="col">Approver</th>
          <th scope="col">Source</th>
        </tr>
      </thead>
      <tbody>
        {rules.map((rule, index) => (
          <tr key={`${rule.approver}-${rule.source}-${index}`}>
            <td>
              {approvalSituationLabel(rule.stances)}
              {rule.condition ? <span className="rv-k-appr__cond">If {rule.condition}</span> : null}
            </td>
            <td>{rule.approver}</td>
            <td>{AUTHORITY_SOURCE_LABELS[rule.source]}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
