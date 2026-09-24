import { useState } from 'react'
import type { RetrievalTrace } from '../../api/retrieval'
import { useRetrievalTraceQuery } from '../../api/retrieval'
import {
  degradationMessage,
  traceRequestEntries,
  traceStageLabel,
  traceTimingLabel,
} from '../../lib/evidence'

function TraceContent({ trace }: { trace: RetrievalTrace }) {
  const requestEntries = traceRequestEntries(trace.request)
  const shortEntries = requestEntries.filter((entry) => !entry.long)
  const longEntries = requestEntries.filter((entry) => entry.long)
  const stages = Object.values(trace.stages)
  const timings = Object.entries(trace.stageTimings)
  const versions = Object.entries(trace.modelVersions)

  return (
    <div className="trace-panel__content">
      <section aria-labelledby="trace-request-heading">
        <h3 id="trace-request-heading">Request</h3>
        <dl className="data-list">
          {shortEntries.map((entry) => (
            <div className="data-list__row" key={entry.key}>
              <dt>{entry.label}</dt>
              <dd>{entry.value}</dd>
            </div>
          ))}
        </dl>
        {longEntries.map((entry) => (
          <details key={entry.key} className="trace-panel__long">
            <summary>{entry.label}</summary>
            <p>{entry.value}</p>
          </details>
        ))}
      </section>

      {stages.length > 0 ? (
        <section aria-labelledby="trace-stages-heading">
          <h3 id="trace-stages-heading">Stages</h3>
          <ul className="trace-panel__stages">
            {stages.map((stage) => (
              <li key={stage.stage}>
                <h4>{traceStageLabel(stage.stage)}</h4>
                <p>{`${stage.candidateCount} candidate(s)`}</p>
                {stage.candidateRanks.length > 0 ? (
                  <p>{`Ranks: ${stage.candidateRanks.join(', ')}`}</p>
                ) : null}
                {stage.executionIds.length > 0 ? (
                  <p>{`Model executions: ${stage.executionIds.join(', ')}`}</p>
                ) : null}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <section aria-labelledby="trace-selected-heading">
        <h3 id="trace-selected-heading">Selected evidence</h3>
        <p>{`${trace.selectedEvidenceIds.length} item(s) selected`}</p>
        {trace.rejectedEvidence.length > 0 ? (
          <>
            <h4>Rejected candidates</h4>
            <ul className="trace-panel__rejected">
              {trace.rejectedEvidence.map((rejected, index) => (
                <li key={`${rejected.candidateId}-${index}`}>
                  <code>{rejected.candidateId}</code>
                  {` · ${traceStageLabel(rejected.stage)} · ${rejected.reason}`}
                </li>
              ))}
            </ul>
          </>
        ) : null}
      </section>

      {trace.degradation.length > 0 ? (
        <section aria-labelledby="trace-degradation-heading">
          <h3 id="trace-degradation-heading">Degradation</h3>
          <ul>
            {trace.degradation.map((code) => (
              <li key={code}>{degradationMessage(code)}</li>
            ))}
          </ul>
        </section>
      ) : null}

      {timings.length > 0 ? (
        <section aria-labelledby="trace-timings-heading">
          <h3 id="trace-timings-heading">Timings</h3>
          <dl className="data-list">
            {timings.map(([key, value]) => (
              <div className="data-list__row" key={key}>
                <dt>{traceTimingLabel(key)}</dt>
                <dd>{`${value} ms`}</dd>
              </div>
            ))}
          </dl>
        </section>
      ) : null}

      {versions.length > 0 ? (
        <section aria-labelledby="trace-versions-heading">
          <h3 id="trace-versions-heading">Model and spec versions</h3>
          <dl className="data-list">
            {versions.map(([key, value]) => (
              <div className="data-list__row" key={key}>
                <dt>{key}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
        </section>
      ) : null}

      <p className="trace-panel__note">
        Ranks and timings are retrieval diagnostics, not legal confidence.
      </p>
    </div>
  )
}

export function RetrievalTracePanel({ traceId }: { traceId: string }) {
  const [open, setOpen] = useState(false)
  const traceQuery = useRetrievalTraceQuery(traceId, open)

  return (
    <details
      className="trace-panel"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary>Retrieval details</summary>
      {open ? (
        <div className="trace-panel__body">
          {traceQuery.isPending ? <p>Loading retrieval details…</p> : null}
          {traceQuery.isError ? (
            <div className="trace-panel__error">
              <p>Retrieval details could not be loaded.</p>
              <button
                type="button"
                className="button"
                onClick={() => void traceQuery.refetch()}
              >
                Try again
              </button>
            </div>
          ) : null}
          {traceQuery.data ? <TraceContent trace={traceQuery.data} /> : null}
        </div>
      ) : null}
    </details>
  )
}
