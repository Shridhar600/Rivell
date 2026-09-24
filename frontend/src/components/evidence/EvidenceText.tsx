import type { EvidenceItem } from '../../api/retrieval'
import { evidenceTextCollapsible } from '../../lib/evidence'

export function EvidenceText({ item, label }: { item: EvidenceItem; label?: string }) {
  if (!evidenceTextCollapsible(item)) {
    return <p className="evidence-card__text">{item.text}</p>
  }
  const excerpt = item.text.replace(/\s+/g, ' ').slice(0, 60)
  const name = label ? `${label} — ${excerpt}` : excerpt
  return (
    <details className="evidence-card__disclosure">
      <summary aria-label={`Show exact text: ${name}`}>Show exact text</summary>
      <blockquote className="evidence-card__text">{item.text}</blockquote>
    </details>
  )
}
