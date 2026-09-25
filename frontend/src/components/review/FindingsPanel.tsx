import type { FindingFilters, FindingSummary, ReviewStatus } from '../../api/reviews'
import { CLASSIFICATIONS, SEVERITIES } from '../../api/reviews'
import { TAXONOMY_CONCEPTS, conceptLabel } from '../../lib/contractSemantics'
import {
  classificationLabel,
  findingSubjectLabel,
  issueFamilyLabel,
  severityLabel,
  severityModifier,
} from '../../lib/reviews'
import { EmptyState } from '../EmptyState'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'

type Props = {
  findings: FindingSummary[]
  truncated: boolean
  filters: FindingFilters
  onFiltersChange: (next: FindingFilters) => void
  selectedFindingId: string | null
  onSelect: (findingId: string) => void
  regionLabels: Map<string, string>
  reviewStatus: ReviewStatus
  isEnabled: boolean
  isStateUnrecognized: boolean
  isPending: boolean
  isError: boolean
  onRetry: () => void
}

function hasFilters(filters: FindingFilters): boolean {
  return Boolean(filters.severity || filters.classification || filters.concept)
}

export function FindingsPanel({
  findings,
  truncated,
  filters,
  onFiltersChange,
  selectedFindingId,
  onSelect,
  regionLabels,
  reviewStatus,
  isEnabled,
  isStateUnrecognized,
  isPending,
  isError,
  onRetry,
}: Props) {
  return (
    <section className="panel findings-panel" aria-labelledby="findings-heading">
      <h2 id="findings-heading">Findings</h2>

      {isEnabled && isStateUnrecognized ? (
        <p className="panel__hint">
          This review is in a state this version of the app does not recognise. Findings below
          are the latest persisted generation.
        </p>
      ) : null}

      {!isEnabled ? (
        <p className="panel__hint">
          {isStateUnrecognized
            ? 'Findings are unavailable while the review state is unrecognized. Check again above.'
            : 'Findings appear here when the automated analysis completes.'}
        </p>
      ) : null}

      {isEnabled ? (
        <div className="finding-filters">
          <div className="field">
            <label htmlFor="finding-severity">Severity</label>
            <select
              id="finding-severity"
              value={filters.severity ?? ''}
              onChange={(event) =>
                onFiltersChange({
                  ...filters,
                  severity: (event.target.value || undefined) as
                    | FindingFilters['severity']
                    | undefined,
                })
              }
            >
              <option value="">All severities</option>
              {SEVERITIES.map((severity) => (
                <option key={severity} value={severity}>
                  {severityLabel(severity)}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="finding-classification">Classification</label>
            <select
              id="finding-classification"
              value={filters.classification ?? ''}
              onChange={(event) =>
                onFiltersChange({
                  ...filters,
                  classification: (event.target.value || undefined) as
                    | FindingFilters['classification']
                    | undefined,
                })
              }
            >
              <option value="">All classifications</option>
              {CLASSIFICATIONS.map((classification) => (
                <option key={classification} value={classification}>
                  {classificationLabel(classification)}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="finding-concept">Legal concept</label>
            <select
              id="finding-concept"
              value={filters.concept ?? ''}
              onChange={(event) =>
                onFiltersChange({
                  ...filters,
                  concept: (event.target.value || undefined) as
                    | FindingFilters['concept']
                    | undefined,
                })
              }
            >
              <option value="">All concepts</option>
              {TAXONOMY_CONCEPTS.map((concept) => (
                <option key={concept} value={concept}>
                  {conceptLabel(concept)}
                </option>
              ))}
            </select>
          </div>
        </div>
      ) : null}

      {isEnabled && isPending ? <LoadingState label="Loading findings…" /> : null}

      {isEnabled && isError ? (
        <ErrorState message="Findings could not be loaded." onRetry={onRetry} />
      ) : null}

      {isEnabled && !isPending && !isError && findings.length === 0 ? (
        hasFilters(filters) ? (
          <EmptyState
            title="No findings match these filters"
            message="Clear the filters to see every finding in this review."
          />
        ) : reviewStatus === 'COMPLETED' ? (
          <EmptyState
            title="No findings"
            message="The automated review recorded no findings for this contract version."
          />
        ) : (
          <EmptyState
            title="No findings available"
            message="This review did not complete an analysis, so there are no findings to show."
          />
        )
      ) : null}

      {isEnabled && !isPending && !isError && findings.length > 0 ? (
        <>
          <ul className="finding-list">
            {findings.map((finding) => (
              <li key={finding.id}>
                <button
                  type="button"
                  className={
                    finding.id === selectedFindingId
                      ? 'finding-list__item finding-list__item--selected'
                      : 'finding-list__item'
                  }
                  aria-current={finding.id === selectedFindingId}
                  onClick={() => onSelect(finding.id)}
                >
                  <span className="finding-list__subject">
                    {findingSubjectLabel(finding, regionLabels)}
                  </span>
                  <span className="finding-list__issue">
                    {issueFamilyLabel(finding.issueFamily)}
                  </span>
                  <span className="finding-list__meta">
                    <span className={`badge ${severityModifier(finding.severity)}`}>
                      {severityLabel(finding.severity)}
                    </span>
                    <span className="finding-list__classification">
                      {classificationLabel(finding.reviewClassification)}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
          {truncated ? (
            <p className="panel__hint">Showing the first findings only.</p>
          ) : null}
        </>
      ) : null}
    </section>
  )
}
