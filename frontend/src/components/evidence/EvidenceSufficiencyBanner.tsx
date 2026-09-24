import type { EvidenceSufficiency } from '../../api/retrieval'
import { sufficiencyPresentation } from '../../lib/evidence'

export function EvidenceSufficiencyBanner({
  sufficiency,
}: {
  sufficiency: EvidenceSufficiency
}) {
  const presentation = sufficiencyPresentation(sufficiency)
  const tone =
    sufficiency === 'SUFFICIENT'
      ? 'success'
      : sufficiency === 'UNKNOWN'
        ? 'neutral'
        : 'warning'

  return (
    <section
      className={`sufficiency sufficiency--${tone}`}
      aria-labelledby="evidence-sufficiency-heading"
    >
      <h2 id="evidence-sufficiency-heading">Evidence sufficiency</h2>
      <p className="sufficiency__state">{presentation.label}</p>
      <p className="sufficiency__description">{presentation.description}</p>
    </section>
  )
}
