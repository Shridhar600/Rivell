import { useId, useRef, useState, type DragEvent, type FormEvent } from 'react'
import { useReviewPacksQuery } from '../api/reviews'
import { Breadcrumbs } from '../components/shell/Breadcrumbs'
import { Inspector, TopBar } from '../components/shell/TopBar'
import { Button, LinkButton } from '../components/ui/Button'
import { CautionIcon, CheckIcon, FileIcon, UploadIcon } from '../components/ui/Icon'
import {
  CONTRACT_TYPES,
  CONTRACT_TYPE_LABELS,
  JURISDICTIONS,
  JURISDICTION_LABELS,
  PAPER_SOURCES,
  PAPER_SOURCE_LABELS,
  PARTY_ROLES,
  roleLabel,
} from '../lib/contractSemantics'
import { EMPTY_CONTEXT, STAGE_LABELS, canStart, contextSummary, filenameSuggestedType, fileSizeLabel, isDocx, type StartContext, startNeeds } from '../lib/startReview'
import { useStartReview } from '../lib/useStartReview'

const ACCEPT = '.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document'

/**
 * Start Review, ported from `rivell-reviews-v1.html#/new`: upload a DOCX, confirm the
 * authority-bearing context, optionally pick a pack, then start analysis. The three requests run
 * only when the reviewer starts the analysis, and never out of order.
 */
export function StartReviewPage() {
  const { status, start } = useStartReview()
  const packs = useReviewPacksQuery()
  const [file, setFile] = useState<File | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const [context, setContext] = useState<StartContext>(EMPTY_CONTEXT)
  const [dragging, setDragging] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const typeId = useId()
  const jurisdictionId = useId()
  const packId = useId()
  const packHintId = useId()

  const running = status.phase === 'running'
  const set = (key: keyof StartContext) => (value: string) => setContext((previous) => ({ ...previous, [key]: value }))
  /** Choosing the selected value again clears it: side and paper source are optional. */
  const toggle = (key: 'ourSideRole' | 'paperSource', value: string) =>
    setContext((previous) => ({ ...previous, [key]: previous[key] === value ? '' : value }))

  function choose(candidate: File | undefined) {
    if (!candidate) return
    if (!isDocx(candidate)) {
      setFileError('Choose a Word document.')
      return
    }
    setFileError(null)
    setFile(candidate)
  }

  function clear() {
    setFile(null)
    setFileError(null)
    if (input.current) input.current.value = ''
  }

  function onDrop(event: DragEvent) {
    event.preventDefault()
    setDragging(false)
    if (!running) choose(event.dataTransfer.files[0])
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (running || !file || !canStart(file, context)) return
    void start(file, context)
  }

  const pack = (packs.data ?? []).find((candidate) => candidate.key === context.packKey)
  const suggested = file === null ? null : filenameSuggestedType(file.name, context.contractType)
  const failure = status.phase === 'failed' ? status.failure : null

  return (
    <>
      <div className="rv-main">
        <TopBar
          pageTitle="Start review"
          title={<Breadcrumbs crumbs={[{ label: 'Reviews', to: '/reviews' }, { label: 'Start review' }]} />}
        />
        <div className="rv-scroll">
          <div className="rv-page rv-page--form">
            <div className="rv-page__head">
              <div>
                <h1 className="rv-page__title">Start a contract review</h1>
                <p className="rv-page__lede">Upload a contract, then confirm the details for this review.</p>
              </div>
            </div>

            <form className="rv-startform" onSubmit={submit} aria-label="Start a contract review">
              <section className="rv-card rv-cardform">
                <div className="rv-cb">
                  <h2 className="rv-lb">1. Contract</h2>
                  <input
                    ref={input}
                    type="file"
                    accept={ACCEPT}
                    aria-label="Contract file (Word)"
                    hidden
                    onChange={(event) => choose(event.target.files?.[0])}
                  />
                  {file ? (
                    <div className="rv-fsel">
                      <span className="rv-tile">
                        <FileIcon className="rv-icon" />
                      </span>
                      <div className="rv-fsel__text">
                        <b>{file.name}</b>
                        <div className="rv-hint">
                          Word file, {fileSizeLabel(file.size)}. Kept unchanged.
                        </div>
                      </div>
                      <button type="button" className="rv-lnk" onClick={clear} disabled={running}>
                        Replace
                      </button>
                    </div>
                  ) : (
                    <button
                      type="button"
                      className={`rv-fdrop${dragging ? ' rv-fdrop--over' : ''}`}
                      onClick={() => input.current?.click()}
                      onDragEnter={(event) => {
                        event.preventDefault()
                        setDragging(true)
                      }}
                      onDragOver={(event) => {
                        event.preventDefault()
                        setDragging(true)
                      }}
                      onDragLeave={() => setDragging(false)}
                      onDrop={onDrop}
                    >
                      <UploadIcon className="rv-icon" />
                      <div>
                        <b>Choose a Word file</b> or drop it here
                      </div>
                      <div className="rv-hint">Word files only. The original is never changed.</div>
                    </button>
                  )}
                  {fileError ? (
                    <p role="alert" className="rv-form__error">
                      {fileError}
                    </p>
                  ) : null}
                </div>
              </section>

              <section className="rv-card rv-cardform">
                <div className="rv-cb">
                  <h2 className="rv-lb">2. Review context</h2>
                  <p className="rv-cb__lede">
                    These details guide this review. Rivell may warn about a mismatch but will not change your choices.
                  </p>
                  <div className="rv-g2">
                    <div className="rv-fld">
                      <label className="rv-lb rv-lb--field" htmlFor={typeId}>
                        Contract type
                      </label>
                      <select
                        id={typeId}
                        className="rv-sel"
                        value={context.contractType}
                        aria-required="true"
                        disabled={running}
                        onChange={(event) => set('contractType')(event.target.value)}
                      >
                        <option value="">Select a contract type…</option>
                        {CONTRACT_TYPES.map((type) => (
                          <option key={type} value={type}>
                            {CONTRACT_TYPE_LABELS[type]}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div className="rv-fld">
                      <label className="rv-lb rv-lb--field" htmlFor={jurisdictionId}>
                        Jurisdiction
                      </label>
                      <select
                        id={jurisdictionId}
                        className="rv-sel"
                        value={context.jurisdiction}
                        disabled={running}
                        onChange={(event) => set('jurisdiction')(event.target.value)}
                      >
                        {JURISDICTIONS.map((jurisdiction) => (
                          <option key={jurisdiction} value={jurisdiction}>
                            {JURISDICTION_LABELS[jurisdiction]}
                          </option>
                        ))}
                      </select>
                    </div>
                  </div>

                  <OptionGroup
                    label="Our side"
                    options={PARTY_ROLES.map((role) => ({ value: role, label: roleLabel(role) }))}
                    selected={context.ourSideRole}
                    disabled={running}
                    onToggle={(value) => toggle('ourSideRole', value)}
                  />
                  <OptionGroup
                    label="Paper source"
                    options={PAPER_SOURCES.map((source) => ({ value: source, label: PAPER_SOURCE_LABELS[source] }))}
                    selected={context.paperSource}
                    disabled={running}
                    onToggle={(value) => toggle('paperSource', value)}
                  />

                  <div className="rv-fld">
                    <label className="rv-lb rv-lb--field" htmlFor={packId}>
                      Review pack <span className="rv-lb__opt">optional</span>
                    </label>
                    <select
                      id={packId}
                      className="rv-sel"
                      value={context.packKey}
                      aria-describedby={packHintId}
                      disabled={running}
                      onChange={(event) => set('packKey')(event.target.value)}
                    >
                      <option value="">No explicit pack</option>
                      {(packs.data ?? []).map((candidate) => (
                        <option key={candidate.key} value={candidate.key}>
                          {candidate.displayName}
                        </option>
                      ))}
                    </select>
                    <div className="rv-hint" id={packHintId}>
                      Choose a pack to apply its rules to this review. Packs are optional.
                      {pack ? ` ${pack.summary}` : ''}
                      {packs.isError ? ' The pack catalog could not be loaded, so only a pack-less review is available.' : ''}
                    </div>
                  </div>

                  {suggested ? (
                    <div className="rv-mm" role="note">
                      <CautionIcon className="rv-icon" />
                      <div>
                        <b>
                          The file name looks like {suggested}, but you chose{' '}
                          {CONTRACT_TYPE_LABELS[context.contractType] ?? context.contractType}.
                        </b>{' '}
                        Rivell will review it as you chose. Change the type if that is wrong.
                      </div>
                    </div>
                  ) : null}

                  {failure ? (
                    <div role="alert" className="rv-startfail">
                      <p>{failure.message}</p>
                      {failure.intact ? <p className="rv-startfail__intact">{failure.intact}</p> : null}
                    </div>
                  ) : null}
                </div>
                <div className="rv-ft">
                  <span className="rv-ft__sum" role={running ? 'status' : undefined}>
                    {running ? STAGE_LABELS[status.stage] : (startNeeds(file, context) ?? contextSummary(context))}
                  </span>
                  <LinkButton to="/reviews">Cancel</LinkButton>
                  <Button type="submit" variant="primary" disabled={running || !canStart(file, context)}>
                    Start analysis
                  </Button>
                </div>
              </section>
            </form>
          </div>
        </div>
      </div>
      <Inspector status="">
        <div className="rv-rail">
          <div>
            <h3>Review details</h3>
            <div className="rv-chk">
              <Kept>The contract version stays the same</Kept>
              <Kept>These details are fixed for this review</Kept>
              <Kept>The review uses the knowledge published at the time</Kept>
            </div>
          </div>
          <div>
            <h3>What happens next</h3>
            <div className="rv-chk">
              <Kept>Analysis runs in the background</Kept>
              <Kept>Findings appear for your decision</Kept>
              <Kept>Nothing changes in the contract until you finalize your decisions</Kept>
            </div>
          </div>
        </div>
      </Inspector>
    </>
  )
}

function Kept({ children }: { children: string }) {
  return (
    <div>
      <CheckIcon className="rv-icon" />
      {children}
    </div>
  )
}

function OptionGroup({
  label,
  options,
  selected,
  disabled,
  onToggle,
}: {
  label: string
  options: { value: string; label: string }[]
  selected: string
  disabled: boolean
  onToggle: (value: string) => void
}) {
  const labelId = useId()
  return (
    <div className="rv-fld">
      <div className="rv-lb rv-lb--field" id={labelId}>
        {label} <span className="rv-lb__opt">optional</span>
      </div>
      <div className="rv-opt" role="group" aria-labelledby={labelId}>
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            aria-pressed={selected === option.value}
            disabled={disabled}
            onClick={() => onToggle(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  )
}
