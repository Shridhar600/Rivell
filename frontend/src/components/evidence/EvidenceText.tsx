import type { EvidenceItem } from '../../api/retrieval'
import { evidenceTextCollapsible } from '../../lib/evidence'

export function EvidenceText({ item }: { item: EvidenceItem }) {
  if (!evidenceTextCollapsible(item)) {
    return <p className="evidence-card__text">{item.text}</p>
  }
  return (
    <details className="evidence-card__disclosure">
      <summary>Show exact text</summary>
      <blockquote className="evidence-card__text">{item.text}</blockquote>
    </details>
  )
}
