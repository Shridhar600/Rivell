import { useState, type Ref } from 'react'
import type { RedlineExecution } from '../../api/redline'
import { RedlineDownloadIntegrityError, downloadRedline, isRedlineForbiddenError } from '../../api/redline'
import type { ApprovedChangeSet } from '../../api/workflow'
import { changeSetFigures, pluralise } from '../../lib/lifecycleView'
import type { LifecycleKey } from '../../lib/reviewLifecycle'
import { redlineFailureTitle, redlineVerificationOutcome } from '../../lib/redline'
import { saveFile } from '../../lib/saveFile'
import { Button } from '../ui/Button'
import { CompleteIcon, RunningIcon } from '../ui/Icon'
import { DownloadIcon, EscalateIcon, PageIcon, TickIcon } from './icons'
import { BigGlyph, Checks, LcBody, LcCard, LcFoot, Spacer, Stat3 } from './parts'

interface Props {
  state: LifecycleKey
  reviewId: string
  contractVersion: number | null
  /** The pinned version's file name, when the API makes it known. */
  sourceFilename: string | null
  changeSet: ApprovedChangeSet
  execution: RedlineExecution | null
  generating: boolean
  /** A safe sentence about a failed request, or null. */
  notice: string | null
  onGenerate: () => void
  onOpenReport: () => void
  headingRef: Ref<HTMLHeadingElement>
}

/**
 * The Redline tab, ported from the canonical `vRedline()`. Generation is the existing synchronous
 * request, so "Generating" means this page's own request is in flight. Success is shown only when
 * the persisted execution says COMPLETED and every change is applied and verified; a failure is
 * shown as the failure it is, with no download.
 */
export function RedlinePanel(props: Props) {
  const { state, changeSet, execution, headingRef } = props
  switch (state) {
    case 'finalized_zero':
      return (
        <LcCard label="Finalized with no approved changes">
          <LcBody>
            <BigGlyph>
              <TickIcon className="rv-icon" />
            </BigGlyph>
            <h2 tabIndex={-1} ref={headingRef}>
              Review finalized with no approved changes
            </h2>
            <p>The frozen ApprovedChangeSet is empty. No tracked-change artifact is needed for this review.</p>
            <Checks items={[{ text: 'Human decisions are read-only' }, { text: 'Original contract remains unchanged' }]} />
          </LcBody>
        </LcCard>
      )
    case 'finalized': {
      const figures = changeSetFigures(changeSet)
      return (
        <LcCard label="Generate the redline">
          <LcBody>
            <BigGlyph>
              <PageIcon className="rv-icon" />
            </BigGlyph>
            <h2 tabIndex={-1} ref={headingRef}>
              Generate the redline
            </h2>
            <p>
              Rivell will execute the frozen ApprovedChangeSet against the original DOCX using deterministic
              targeting and tracked changes. No new legal reasoning occurs here.
            </p>
            <Stat3
              label="Approved changes"
              stats={[
                { figure: figures.total, label: 'Approved changes' },
                { figure: figures.replace, label: 'Replace' },
                { figure: `${figures.insert} + ${figures.delete}`, label: 'Insert + delete' },
              ]}
            />
            {props.notice ? (
              <p role="alert" className="rv-lc-warn">
                {props.notice}
              </p>
            ) : null}
          </LcBody>
          <LcFoot>
            <Spacer note="A verified artifact is offered only if the complete approved change set executes and verifies." />
            <Button variant="primary" disabled={props.generating} onClick={props.onGenerate}>
              Generate redline
            </Button>
          </LcFoot>
        </LcCard>
      )
    }
    case 'rl_running':
      return (
        <LcCard label="Generating the redline">
          <LcBody>
            <BigGlyph>
              <RunningIcon className="rv-icon rv-glyph--spin" />
            </BigGlyph>
            <h2 tabIndex={-1} ref={headingRef}>
              Generating and verifying the redline
            </h2>
            <p role="status">
              Rivell is executing the frozen ApprovedChangeSet and verifying the result. This page shows the
              outcome as soon as the server records it.
            </p>
          </LcBody>
        </LcCard>
      )
    case 'rl_verified':
      return execution ? <Verified {...props} execution={execution} /> : null
    case 'rl_failed':
      return execution ? <Failed {...props} execution={execution} /> : null
    case 'rl_unverified':
      return execution ? <NotVerified {...props} execution={execution} /> : null
    default:
      return null
  }
}

function Verified({
  execution,
  reviewId,
  contractVersion,
  sourceFilename,
  headingRef,
  onOpenReport,
}: Props & { execution: RedlineExecution }) {
  const applied = execution.changes.filter((change) => change.status === 'APPLIED').length
  const verified = execution.changes.filter((change) => change.verification === 'VERIFIED').length
  return (
    <LcCard label="Verified redline">
      <LcBody>
        <div className="rv-lc-lead">
          <BigGlyph>
            <CompleteIcon className="rv-icon" />
          </BigGlyph>
          <div>
            <h2 tabIndex={-1} ref={headingRef}>
              Verified redline
            </h2>
            <p className="rv-lc-prv">
              <span>Complete ApprovedChangeSet executed</span>
              <span>Round-trip verification passed</span>
            </p>
          </div>
        </div>
        <Checks
          items={[
            { text: pluralise(execution.changeCount, 'approved change') },
            { text: `${applied} applied` },
            { text: `${verified} verified` },
            { text: 'One verified tracked-DOCX artifact is available' },
          ]}
        />
        <div className="rv-lc-art">
          <span className="rv-lc-tile">
            <PageIcon className="rv-icon" />
          </span>
          <div className="rv-lc-art__text">
            <b>{execution.artifactFilename ?? 'Tracked-changes DOCX'}</b>
            <div className="rv-lc-quiet">
              Tracked-changes DOCX produced from {sourceFilename ? `${sourceFilename} ` : 'the contract '}
              {contractVersion !== null ? `v${contractVersion}` : ''}
            </div>
          </div>
          <DownloadTrackedDocx reviewId={reviewId} execution={execution} />
        </div>
        <p className="rv-lc-quiet">
          Verification is scoped to execution against the frozen ApprovedChangeSet. It is not a claim of
          universal legal correctness or Microsoft Word certification.
        </p>
      </LcBody>
      <LcFoot>
        <Spacer />
        <Button onClick={onOpenReport}>View change report</Button>
      </LcFoot>
    </LcCard>
  )
}

function Failed({ execution, headingRef, onOpenReport }: Props & { execution: RedlineExecution }) {
  return (
    <LcCard label="Redline failed">
      <LcBody>
        <div className="rv-lc-lead">
          <BigGlyph tone="red">
            <EscalateIcon className="rv-icon" />
          </BigGlyph>
          <div>
            <h2 tabIndex={-1} ref={headingRef}>
              Redline failed
            </h2>
            <p className="rv-lc-prv">
              <span>Top-level execution result: FAILED</span>
            </p>
          </div>
        </div>
        <p>
          Rivell could not deterministically execute and verify the complete frozen ApprovedChangeSet. No
          verified document is available.
        </p>
        {execution.failureCode ? <p className="rv-lc-quiet">{redlineFailureTitle(execution.failureCode)}</p> : null}
        <Checks
          items={[
            { text: 'ApprovedChangeSet remains frozen and intact' },
            { text: 'No verified DOCX is available to download', failed: true },
          ]}
        />
      </LcBody>
      <LcFoot>
        <Button onClick={onOpenReport}>View diagnostic report</Button>
        <Spacer note="Generating again returns this same recorded result; the redline for a frozen change set is executed once." />
      </LcFoot>
    </LcCard>
  )
}

function NotVerified({ execution, headingRef, onOpenReport }: Props & { execution: RedlineExecution }) {
  const outcome = redlineVerificationOutcome(execution)
  return (
    <LcCard label="Redline not verified">
      <LcBody>
        <div className="rv-lc-lead">
          <BigGlyph tone="red">
            <EscalateIcon className="rv-icon" />
          </BigGlyph>
          <div>
            <h2 tabIndex={-1} ref={headingRef}>
              Redline not verified
            </h2>
            <p className="rv-lc-prv">
              <span>Verification: {outcome === 'INCOMPLETE' ? 'incomplete' : 'not confirmed'}</span>
            </p>
          </div>
        </div>
        <p>
          The server recorded an execution, but not every approved change is applied and verified, so no
          document is offered.
        </p>
        <Checks
          items={[
            { text: 'ApprovedChangeSet remains frozen and intact' },
            { text: 'No verified DOCX is available to download', failed: true },
          ]}
        />
      </LcBody>
      <LcFoot>
        <Button onClick={onOpenReport}>View diagnostic report</Button>
      </LcFoot>
    </LcCard>
  )
}

/**
 * Downloads the real verified artifact. The bytes are checked against what the execution recorded
 * before they are handed to the browser, and the button exists only for a verified result.
 */
function DownloadTrackedDocx({ reviewId, execution }: { reviewId: string; execution: RedlineExecution }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function download() {
    setBusy(true)
    setError(null)
    try {
      const file = await downloadRedline(reviewId)
      if (execution.artifactSizeBytes !== null && file.blob.size !== execution.artifactSizeBytes) {
        throw new RedlineDownloadIntegrityError()
      }
      saveFile(file.blob, execution.artifactFilename ?? file.filename)
    } catch (caught) {
      setError(
        isRedlineForbiddenError(caught)
          ? 'You do not have permission to download this redline.'
          : 'The redline was generated, but the file could not be downloaded. Try again.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="rv-lc-art__action">
      <Button variant="primary" disabled={busy} onClick={() => void download()}>
        <DownloadIcon className="rv-icon" />
        Download tracked DOCX
      </Button>
      {busy ? <span role="status" className="rv-visually-hidden">Preparing download…</span> : null}
      {error ? (
        <p role="alert" className="rv-lc-warn">
          {error}
        </p>
      ) : null}
    </div>
  )
}
