import type { FindingSummary, ProposedChangeCounts, ReviewSummary } from '../../api/reviews'
import type { SemanticContract } from '../../api/semantics'
import { useTaxonomy } from '../../api/taxonomy'
import type { HumanReview } from '../../api/workflow'
import { metadataFieldLabel } from '../../lib/contractSemantics'
import {
  classificationCounts,
  decisionSegments,
  nextUnresolvedTitle,
  pluralise,
} from '../../lib/lifecycleView'
import {
  REVIEWS_PATH,
  START_REVIEW_PATH,
  decidedLine,
  type LifecycleKey,
} from '../../lib/reviewLifecycle'
import { blockerLabel } from '../../lib/workflow'
import { reviewFailureMessage } from '../../lib/reviews'
import { Button, LinkButton } from '../ui/Button'
import { NeedsHumanIcon, QueuedIcon, RunningIcon } from '../ui/Icon'
import { EscalateIcon, ProgressIcon } from './icons'
import { BigGlyph, Checks, LcBody, LcCard, LcFoot, Spacer, Stat3 } from './parts'

interface Props {
  state: LifecycleKey
  summary: ReviewSummary
  humanReview: HumanReview | null
  findings: FindingSummary[]
  /** True when the Finding list is the whole list, so counts taken from it are exact. */
  findingsComplete: boolean
  semantics: SemanticContract | null
  workspaceTo: string
  onRefresh: () => void
}

/**
 * The states before Finalize, ported from the canonical `body()`: one card per state, driven by
 * the server's analysis status and human-review progress. Nothing here decides anything. Every
 * action leads into the Review Workspace, which owns Finding decisions and Finalize.
 */
export function StateCard(props: Props) {
  const { state, summary, humanReview, workspaceTo } = props
  switch (state) {
    case 'queued':
      return (
        <LcCard label="Analysis waiting to start">
          <LcBody>
            <BigGlyph>
              <QueuedIcon className="rv-icon" />
            </BigGlyph>
            <h2>Waiting for analysis to start</h2>
            <p role="status">
              Your contract and review details are saved. Analysis starts on its own, so you can leave
              this page.
            </p>
          </LcBody>
        </LcCard>
      )
    case 'analyzing':
      return (
        <LcCard label="Analyzing">
          <LcBody>
            <BigGlyph>
              <RunningIcon className="rv-icon rv-glyph--spin" />
            </BigGlyph>
            <h2>Analyzing the contract</h2>
            <p role="status">
              Rivell is reviewing the contract. This page updates when analysis finishes.
            </p>
            <CheckCounts summary={summary} />
          </LcBody>
        </LcCard>
      )
    case 'failed':
      return (
        <LcCard label="Analysis failed">
          <LcBody>
            <BigGlyph tone="red">
              <EscalateIcon className="rv-icon" />
            </BigGlyph>
            <h2>Analysis could not be completed</h2>
            <p>
              Analysis could not finish. Your contract is unchanged and no findings were produced.
            </p>
            <p className="rv-lc-quiet">{reviewFailureMessage(summary.failure)}</p>
          </LcBody>
          <LcFoot>
            <LinkButton to={REVIEWS_PATH}>Back to reviews</LinkButton>
            <Spacer />
            <LinkButton to={START_REVIEW_PATH} variant="primary">
              Start another review
            </LinkButton>
          </LcFoot>
        </LcCard>
      )
    case 'degraded':
      return (
        <LcCard label="Analysis degraded">
          <LcBody>
            <BigGlyph tone="amber">
              <NeedsHumanIcon className="rv-icon" />
            </BigGlyph>
            <h2>The analysis did not produce a usable result</h2>
            <p>{blockerLabel('ANALYSIS_DEGRADED')} No findings are offered for your review.</p>
            <CheckCounts summary={summary} />
          </LcBody>
          <LcFoot>
            <LinkButton to={REVIEWS_PATH}>Back to reviews</LinkButton>
            <Spacer />
            <LinkButton to={START_REVIEW_PATH} variant="primary">
              Start another review
            </LinkButton>
          </LcFoot>
        </LcCard>
      )
    case 'incomplete':
      return (
        <LcCard label="Analysis state">
          <LcBody>
            <BigGlyph>
              <QueuedIcon className="rv-icon" />
            </BigGlyph>
            <h2>Analysis results are not final yet</h2>
            <p
              role="status"
              title="The analysis is not finished yet, so you cannot decide on findings. This page shows the current state."
            >
              Analysis is still running.
            </p>
          </LcBody>
          <LcFoot>
            <Spacer />
            <Button onClick={props.onRefresh}>Check again</Button>
          </LcFoot>
        </LcCard>
      )
    case 'completed':
      return <ReadyForReview {...props} />
    case 'progress':
      return humanReview ? <InProgress {...props} humanReview={humanReview} /> : null
    case 'blocked':
      return humanReview ? (
        <LcCard label="Escalation blocking">
          <LcBody>
            <BigGlyph tone="amber">
              <EscalateIcon className="rv-icon" />
            </BigGlyph>
            <h2>An escalated finding is still unresolved</h2>
            <p>
              Open the review and resolve the finding with the approver's name before you finalize.
            </p>
            <p className="rv-lc-prv">
              <span>
                Escalated <b>{humanReview.escalated}</b>
              </span>
              <span>
                Still open <b>{humanReview.remainingFindings}</b>
              </span>
              <span>
                Decided <b>{humanReview.resolvedFindings}</b> of <b>{humanReview.actionableFindings}</b>
              </span>
            </p>
          </LcBody>
          <LcFoot>
            <Spacer />
            <LinkButton to={workspaceTo} variant="primary">
              Open review
            </LinkButton>
          </LcFoot>
        </LcCard>
      ) : null
    case 'ready':
      return humanReview ? (
        <LcCard label="Ready to finalize">
          <LcBody>
            <BigGlyph>
              <NeedsHumanIcon className="rv-icon" />
            </BigGlyph>
            <h2>Ready to finalize</h2>
            <p>
              No blockers remain. Finalize the review when you are ready.
            </p>
            <Stat3
              label="Your decisions"
              stats={[
                { figure: humanReview.accepted, label: 'Accepted' },
                { figure: humanReview.edited, label: 'Edited' },
                { figure: humanReview.rejected, label: 'No approved change' },
              ]}
            />
            <Checks
              items={[
                { text: 'Finalizing makes the approved changes final' },
                { text: 'Your decisions become read-only' },
                { text: 'Generating the redline is a separate step after Finalize' },
              ]}
            />
          </LcBody>
          <LcFoot>
            <Spacer />
            <LinkButton to={workspaceTo} variant="primary">
              Open review to finalize
            </LinkButton>
          </LcFoot>
        </LcCard>
      ) : null
    default:
      return (
        <LcCard label="Review state">
          <LcBody>
            <BigGlyph>
              <QueuedIcon className="rv-icon" />
            </BigGlyph>
            <h2>This review is in a state this version does not recognise</h2>
            <p role="status">It may still be running. Check again to read the latest state.</p>
          </LcBody>
          <LcFoot>
            <Spacer />
            <Button onClick={props.onRefresh}>Check again</Button>
          </LcFoot>
        </LcCard>
      )
  }
}

/**
 * What Rivell itself proposes, before anyone decides. Replacements carry drafted wording and are
 * the only executable proposal; detected missing provisions are counted because a person must
 * author the clause. Deletion is a human decision only and is never a machine proposal, so it is
 * not shown as one.
 */
function ProposedChanges({ counts }: { counts: ProposedChangeCounts }) {
  if (counts.replace === 0 && counts.insert === 0) return null
  return (
    <>
      <Stat3
        label="What Rivell proposes"
        stats={[
          { figure: counts.replace, label: 'Replacement wording' },
          { figure: counts.insert, label: 'Missing provisions to draft' },
        ]}
      />
      {counts.insert > 0 ? (
        <p className="rv-lc-quiet">
          For each missing provision Rivell detected the gap but proposes no wording for it; a person
          authors the clause.
        </p>
      ) : null}
    </>
  )
}

/** Real check counts from the review summary. No percentage: a count is not a progress promise. */
function CheckCounts({ summary }: { summary: ReviewSummary }) {
  if (summary.plannedChecks <= 0) return null
  return (
    <p className="rv-lc-prv" aria-label="Analysis check counts">
      <span>
        Planned checks <b>{summary.plannedChecks}</b>
      </span>
      <span>
        Completed <b>{summary.completedChecks}</b>
      </span>
      {summary.degradedChecks > 0 ? (
        <span>
          Degraded <b>{summary.degradedChecks}</b>
        </span>
      ) : null}
      {summary.failedChecks > 0 ? (
        <span>
          Failed <b>{summary.failedChecks}</b>
        </span>
      ) : null}
    </p>
  )
}

function ReadyForReview({ summary, findings, findingsComplete, semantics, workspaceTo }: Props) {
  const rows = semanticsRows(semantics)
  return (
    <>
      <LcCard label="Ready for review">
        <LcBody>
          <BigGlyph tone="amber">
            <NeedsHumanIcon className="rv-icon" />
          </BigGlyph>
          <h2>
            {summary.findings === 1
              ? '1 finding is ready for your review'
              : `${summary.findings} findings are ready for your review`}
          </h2>
          <p>
            Analysis is complete. The contract does not change until you resolve the findings and
            finalize.
          </p>
          {findingsComplete && findings.length > 0 ? (
            <Stat3
              label="By classification"
              stats={classificationCounts(findings).map((entry) => ({ figure: entry.count, label: entry.label }))}
            />
          ) : null}
          <ProposedChanges counts={summary.proposedChanges} />
        </LcBody>
        <LcFoot>
          <Spacer />
          <LinkButton to={workspaceTo} variant="primary">
            Open review
          </LinkButton>
        </LcFoot>
      </LcCard>
      {rows.length > 0 ? (
        <LcCard label="Contract details">
          <LcBody>
            <h3 className="rv-lc-lb">Contract details</h3>
            <dl className="rv-lc-fdl">
              {rows.map((row) => (
                <div key={row.label}>
                  <dt>{row.label}</dt>
                  <dd>{row.value}</dd>
                </div>
              ))}
            </dl>
          </LcBody>
        </LcCard>
      ) : null}
    </>
  )
}

/** Agreement, parties, governing law and term, only where the semantic layer actually found them. */
function semanticsRows(semantics: SemanticContract | null): { label: string; value: string }[] {
  if (!semantics || semantics.enrichmentStatus !== 'READY') return []
  const fact = (field: string) => {
    const entry = semantics.metadata.find((metadata) => metadata.field === field)
    return entry ? (entry.correctedValue ?? entry.value) : null
  }
  const rows: { label: string; value: string }[] = []
  const agreement = fact('AGREEMENT_TITLE')
  if (agreement) rows.push({ label: 'Agreement', value: agreement })
  if (semantics.parties.length > 0) {
    rows.push({ label: 'Parties', value: semantics.parties.map((party) => party.name).join('; ') })
  }
  for (const field of ['GOVERNING_LAW', 'TERM'] as const) {
    const value = fact(field)
    if (value) rows.push({ label: metadataFieldLabel(field), value })
  }
  return rows
}

function InProgress({
  humanReview,
  findings,
  workspaceTo,
}: Props & { humanReview: HumanReview }) {
  const { labelFor } = useTaxonomy()
  const next = nextUnresolvedTitle(humanReview, findings, labelFor)
  const segments = decisionSegments(humanReview)
  return (
    <LcCard label="Review in progress">
      <LcBody>
        <BigGlyph>
          <ProgressIcon className="rv-icon" />
        </BigGlyph>
        <h2>{decidedLine(humanReview)}</h2>
        <p>
          {next ? `Next open: ${next}. ` : ''}
          {pluralise(humanReview.remainingFindings, 'finding')} still open. Decisions can change until you
          finalize.
        </p>
        <div className="rv-lc-seg" role="img" aria-label={decidedLine(humanReview)}>
          {segments.map((decided, index) => (
            <i key={index} className={decided ? 'rv-lc-seg__d' : undefined} />
          ))}
        </div>
      </LcBody>
      <LcFoot>
        <Spacer />
        <LinkButton to={workspaceTo} variant="primary">
          Continue review
        </LinkButton>
      </LcFoot>
    </LcCard>
  )
}

