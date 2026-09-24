import { degradationMessage } from '../../lib/evidence'

export function EvidenceWarnings({ warnings }: { warnings: string[] }) {
  if (warnings.length === 0) return null

  return (
    <section className="evidence-warnings" aria-labelledby="evidence-warnings-heading">
      <h2 id="evidence-warnings-heading">Retrieval quality notes</h2>
      <ul>
        {warnings.map((code) => (
          <li key={code}>{degradationMessage(code)}</li>
        ))}
      </ul>
    </section>
  )
}
