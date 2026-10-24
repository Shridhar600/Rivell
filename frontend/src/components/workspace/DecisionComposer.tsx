import { useEffect, useRef, useState } from 'react'
import type { FindingDetail } from '../../api/reviews'
import type {
  ChangeActionType,
  DecisionAction,
  RejectReason,
} from '../../api/workflow'
import {
  changeActionLabel,
  defaultEditAction,
  editActionsForSubject,
  editLanguageRequired,
  validateDecisionForm,
} from '../../lib/workflow'
import { Button } from '../ui/Button'

export type ComposeKind = 'EDIT' | 'KEEP_AS_DRAFTED' | 'FINDING_WRONG' | 'ESCALATE' | 'RESOLVE'

export interface DecisionSubmission {
  action: DecisionAction
  approvedAction?: ChangeActionType
  approvedLanguage?: string
  comment?: string
  rejectReason?: RejectReason
  approvedBy?: string
}

interface Props {
  kind: ComposeKind
  finding: FindingDetail
  /** The clause as received, shown above a replacement. */
  currentText: string | null
  /** The starting text: the clause as received, or the wording of the existing edit. */
  initialLanguage: string
  /** What an edit is compared with to refuse an unchanged save. Defaults to initialLanguage. */
  baselineLanguage?: string
  isSubmitting: boolean
  error: string | null
  onSubmit: (submission: DecisionSubmission) => void
  onCancel: () => void
}

const TITLES: Record<ComposeKind, string> = {
  EDIT: 'Edit decision',
  KEEP_AS_DRAFTED: 'Keep as drafted',
  FINDING_WRONG: 'Dismiss finding',
  ESCALATE: 'Escalate finding',
  RESOLVE: 'Resolve escalation',
}

/**
 * Edit needs the approved language and its change type; Keep and Dismiss explain that the
 * clause is left alone; Escalate needs a reason because it blocks finalization; Resolve
 * needs the name of the person who approved the clause. No action asks a person to retype
 * text: a replacement starts from the clause as received.
 */
export function DecisionComposer({ kind, finding, currentText, initialLanguage, baselineLanguage, isSubmitting, error, onSubmit, onCancel }: Props) {
  const changeTypes = editActionsForSubject(finding.subjectKind)
  const [approvedAction, setApprovedAction] = useState<ChangeActionType>(defaultEditAction(finding.subjectKind))
  const [language, setLanguage] = useState(initialLanguage)
  const [comment, setComment] = useState('')
  const [approvedBy, setApprovedBy] = useState('')
  const [errors, setErrors] = useState<{ comment?: string; approvedLanguage?: string; approvedBy?: string }>({})
  const firstField = useRef<HTMLTextAreaElement>(null)
  const approverField = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (kind === 'RESOLVE') approverField.current?.focus()
    else firstField.current?.focus()
  }, [kind])

  const isEdit = kind === 'EDIT'
  const isResolve = kind === 'RESOLVE'
  const needsReason = kind === 'ESCALATE'
  const needsLanguage = isEdit && editLanguageRequired(approvedAction)
  const cannotEdit = isEdit && changeTypes.length === 0
  const rejectReason: RejectReason | undefined =
    kind === 'KEEP_AS_DRAFTED' ? 'KEEP_AS_DRAFTED' : kind === 'FINDING_WRONG' ? 'FINDING_WRONG' : undefined
  const action: DecisionAction =
    kind === 'EDIT' || kind === 'ESCALATE' || kind === 'RESOLVE' ? kind : 'REJECT'

  function save() {
    if (isEdit && needsLanguage && (baselineLanguage ?? initialLanguage) !== ''
        && language === (baselineLanguage ?? initialLanguage)) {
      setErrors({ approvedLanguage: 'Change the wording before you save.' })
      return
    }
    const next = validateDecisionForm({
      action,
      approvedAction,
      approvedLanguage: language,
      comment,
      rejectReason,
      approvedBy,
    })
    setErrors(next)
    if (next.comment || next.approvedLanguage || next.approvedBy) return
    const submission: DecisionSubmission = { action }
    if (isEdit) submission.approvedAction = approvedAction
    if (needsLanguage) submission.approvedLanguage = language
    if (comment.trim()) submission.comment = comment
    if (rejectReason) submission.rejectReason = rejectReason
    if (isResolve) submission.approvedBy = approvedBy.trim()
    onSubmit(submission)
  }

  const title = TITLES[kind]
  const submitLabel =
    kind === 'EDIT' ? 'Save decision'
      : kind === 'KEEP_AS_DRAFTED' ? 'Keep as drafted'
        : kind === 'FINDING_WRONG' ? 'Dismiss finding'
          : kind === 'RESOLVE' ? 'Resolve'
            : 'Escalate'

  return (
    <form
      className="rv-ws-composer"
      aria-label={title}
      onSubmit={(event) => {
        event.preventDefault()
        save()
      }}
    >
      <h3>{title}</h3>
      {kind === 'KEEP_AS_DRAFTED' ? (
        <p>The clause stays as written. No approved change will be created.</p>
      ) : null}
      {kind === 'FINDING_WRONG' ? <p>No approved change will be created from this finding.</p> : null}
      {kind === 'ESCALATE' ? <p>This finding will remain unresolved and block finalization.</p> : null}
      {kind === 'RESOLVE' ? (
        <p>Record who approved this clause. The escalation will no longer block finalization.</p>
      ) : null}

      {isEdit ? (
        <>
          <div className="rv-ws-change-type">
            <span>Change type</span>
            {changeTypes.length > 1 ? (
              <select
                aria-label="Change type"
                value={approvedAction}
                onChange={(event) => {
                  setApprovedAction(event.target.value as ChangeActionType)
                  setErrors({})
                }}
              >
                {changeTypes.map((type) => (
                  <option key={type} value={type}>
                    {changeActionLabel(type)}
                  </option>
                ))}
              </select>
            ) : (
              <span className={`rv-ws-typ rv-ws-typ--${changeTypes[0]}`}>{changeTypes[0] ?? 'Unknown'}</span>
            )}
          </div>
          {approvedAction === 'REPLACE' && currentText ? (
            <div>
              <div className="rv-ws-sec-h">Current text</div>
              <div className="rv-ws-current">{currentText}</div>
            </div>
          ) : null}
          {approvedAction === 'DELETE' ? (
            <p>This deletes the clause. No replacement wording is needed.</p>
          ) : null}
          {needsLanguage ? (
            <div>
              <div className="rv-ws-sec-h">{approvedAction === 'INSERT' ? 'Insertion language' : 'Replacement language'}</div>
              <textarea
                ref={firstField}
                aria-label="Approved language"
                placeholder="Enter the approved wording"
                value={language}
                aria-required
                aria-invalid={errors.approvedLanguage ? true : undefined}
                onChange={(event) => setLanguage(event.target.value)}
              />
              {finding.proposedLanguage ? (
                <div style={{ marginTop: 7 }}>
                  <button type="button" className="rv-ws-link" onClick={() => setLanguage(finding.proposedLanguage ?? '')}>
                    Use proposed language
                  </button>
                </div>
              ) : null}
              {errors.approvedLanguage ? <p className="rv-ws-error">{errors.approvedLanguage}</p> : null}
            </div>
          ) : null}
          {cannotEdit ? (
            <p className="rv-ws-error" role="alert">
              This finding cannot be edited here. Keep the clause as drafted, dismiss or escalate it instead.
            </p>
          ) : null}
        </>
      ) : null}

      {isResolve ? (
        <div>
          <div className="rv-ws-sec-h">Approved by</div>
          <input
            ref={approverField}
            type="text"
            aria-label="Approved by"
            placeholder="Name of the person who approved"
            maxLength={120}
            value={approvedBy}
            aria-required
            aria-invalid={errors.approvedBy ? true : undefined}
            onChange={(event) => setApprovedBy(event.target.value)}
          />
          {errors.approvedBy ? <p className="rv-ws-error">{errors.approvedBy}</p> : null}
        </div>
      ) : null}

      <div>
        <textarea
          ref={isEdit && needsLanguage ? undefined : isResolve ? undefined : firstField}
          aria-label={needsReason ? 'Reason' : 'Note (optional)'}
          placeholder={needsReason ? 'Reason required' : 'Note (optional)'}
          style={needsReason ? undefined : { minHeight: 56 }}
          value={comment}
          aria-required={needsReason}
          aria-invalid={errors.comment ? true : undefined}
          onChange={(event) => setComment(event.target.value)}
        />
        {errors.comment ? <p className="rv-ws-error">{errors.comment}</p> : null}
      </div>

      {error ? (
        <p className="rv-ws-error" role="alert">
          {error}
        </p>
      ) : null}
      <div className="rv-ws-composer-actions">
        <Button onClick={onCancel}>Cancel</Button>
        <Button variant="primary" disabled={isSubmitting || cannotEdit} onClick={save}>
          {submitLabel}
        </Button>
      </div>
    </form>
  )
}
