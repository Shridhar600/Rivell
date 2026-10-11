import type { ReviewCoverage } from '../../api/reviews'
import { useTaxonomy } from '../../api/taxonomy'
import {
  coverageExpectationLabel,
  coverageLabel,
  coverageLimitationSummary,
  coverageModifier,
} from '../../lib/reviews'

export function ReviewCoveragePanel({ coverage }: { coverage: ReviewCoverage[] }) {
  const { labelFor } = useTaxonomy()
  if (coverage.length === 0) return null
  const limitation = coverageLimitationSummary(coverage, labelFor)

  return (
    <section className="panel" aria-labelledby="review-coverage-heading">
      <h2 id="review-coverage-heading">Review coverage</h2>
      <p className="panel__hint">
        How the planned concepts were found in this contract version. Coverage describes what was
        reviewed; it is not a severity or a legal conclusion.
      </p>
      <ul className="coverage-list">
        {coverage.map((entry) => (
          <li key={entry.concept} className="coverage-list__item">
            <span className="coverage-list__concept">{labelFor(entry.concept)}</span>
            <span className="coverage-list__expectation">
              {coverageExpectationLabel(entry.expectation)}
            </span>
            <span className={`badge ${coverageModifier(entry.coverage)}`}>
              {coverageLabel(entry.coverage)}
            </span>
          </li>
        ))}
      </ul>
      {limitation ? <p className="state state--warning">{limitation}</p> : null}
    </section>
  )
}