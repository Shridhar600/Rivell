import type { ReviewCoverage } from '../../api/reviews'
import { useTaxonomy } from '../../api/taxonomy'
import {
  coverageExpectationLabel,
  coverageLabel,
  coverageLimitationSummary,
} from '../../lib/reviews'

export function ReviewCoveragePanel({ coverage }: { coverage: ReviewCoverage[] }) {
  const { labelFor } = useTaxonomy()
  if (coverage.length === 0) return null
  const limitation = coverageLimitationSummary(coverage, labelFor)

  return (
    <section className="rv-ws-sec" aria-labelledby="review-coverage-heading">
      <h2 id="review-coverage-heading" className="rv-ws-sec-h">
        Review coverage
      </h2>
      <p className="rv-ws-caption">
        How the planned concepts were found in this contract version. Coverage describes what was
        reviewed; it is not a severity or a legal conclusion.
      </p>
      <ul className="rv-ws-cov">
        {coverage.map((entry) => (
          <li key={entry.concept}>
            <span className="rv-ws-cov-c">{labelFor(entry.concept)}</span>
            <span className="rv-ws-cov-e">{coverageExpectationLabel(entry.expectation)}</span>
            <span className="rv-ws-chip" data-tone={entry.coverage === 'PRESENT_PRIMARY' ? undefined : 'amber'}>
              {coverageLabel(entry.coverage)}
            </span>
          </li>
        ))}
      </ul>
      {limitation ? <p className="rv-ws-warn">{limitation}</p> : null}
    </section>
  )
}
