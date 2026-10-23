import type { ReviewSummary } from '../../api/reviews'
import { useTaxonomy } from '../../api/taxonomy'
import {
  analysisWarningMessage,
  checkKeyLabel,
  coverageLimitationSummary,
  hasNewerRun,
} from '../../lib/reviews'

/**
 * Coverage and quality notes that matter to a reader of the summary: checks that ran with reduced
 * certainty, concepts that were not found as a primary clause, and a newer analysis attempt. Shown
 * only when there is something to say, and kept small so diagnostics never become the page.
 */
export function AnalysisQuality({ summary }: { summary: ReviewSummary }) {
  const { labelFor } = useTaxonomy()
  const coverage = coverageLimitationSummary(summary.coverage, labelFor)
  const newer = hasNewerRun(summary)
  if (summary.analysisWarnings.length === 0 && !coverage && !newer) return null
  return (
    <section className="rv-lc-quality" aria-label="Analysis quality">
      <h3 className="rv-lc-lb">Analysis quality</h3>
      {newer ? (
        <p>
          A newer analysis has run. This page shows the latest completed one.
        </p>
      ) : null}
      {coverage ? <p>{coverage}</p> : null}
      {summary.analysisWarnings.length > 0 ? (
        <>
          <p>Some checks were incomplete.</p>
          <ul>
            {summary.analysisWarnings.map((warning, index) => (
              <li key={`${warning.checkKey}-${index}`}>
                <b>{checkKeyLabel(warning.checkKey, labelFor)}</b> {analysisWarningMessage(warning)}
              </li>
            ))}
          </ul>
          {summary.analysisWarningsTruncated ? <p>Additional warnings were omitted.</p> : null}
        </>
      ) : null}
    </section>
  )
}
