import { useEffect, useRef, useState } from 'react'
import type { FindingDetail } from '../../api/reviews'
import type { ChangeActionType, DecisionAction } from '../../api/workflow'
import {
  changeActionLabel,
  defaultEditAction,
  editActionsForSubject,
  editLanguageRequired,
  validateDecisionForm,
} from '../../lib/workflow'
import { Button } from '../ui/Button'

export interface DecisionSubmission {
  action: DecisionAction
  approvedAction?: ChangeActionType
  approvedLanguage?: string
  comment?: string
}

interface Props {
  kind: 'EDIT' | 'REJECT' | 'ESCALATE'
  finding: FindingDetail
  /** The clause as received, shown above a replacement. */
  currentText: string | null
  /** The language of the existing edit, so changing a decision starts from it. */
  initialLanguage: string
  isSubmitting: boolean
  error: string | null
  onSubmit: (submission: DecisionSubmission) => void
  onCancel: () => void
}

/**
 * Edit, Reject and Escalate each need something from the reviewer. Reject and Escalate need a
 * reason. Edit needs the approved language, and its change type is the real one for the finding:
 * a clause can be replaced or deleted, a missing provision can only be inserted.
 */
export function DecisionComposer({ kind, finding, currentText, initialLanguage, isSubmitting, error, onSubmit, onCancel }: Props) {
  const changeTypes = editActionsForSubject(finding.subjectKind)
  const [approvedAction, setApprovedAction] = useState<ChangeActionType>(defaultEditAction(finding.subjectKind))
  const [language, setLanguage] = useState(initialLanguage)
  const [comment, setComment] = useState('')
  const [errors, setErrors] = useState<{ comment?: string; approvedLanguage?: string }>({})
  const firstField = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    firstField.current?.focus()
  }, [])

  const needsReason = kind !== 'EDIT'
  const needsLanguage = kind === 'EDIT' && editLanguageRequired(approvedAction)
  const cannotEdit = kind === 'EDIT' && changeTypes.length === 0

  function save() {
    const next = validateDecisionForm({ action: kind, approvedAction, approvedLanguage: language, comment })
    setErrors(next)
    if (next.comment || next.approvedLanguage) return
    const submission: DecisionSubmission = { action: kind }
    if (kind === 'EDIT') submission.approvedAction = approvedAction
    if (needsLanguage) submission.approvedLanguage = language
    if (comment.trim() || needsReason) submission.comment = comment
    onSubmit(submission)
  }

  const title = kind === 'EDIT' ? 'Edit decision' : kind === 'REJECT' ? 'Reject finding' : 'Escalate finding'
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
      {kind === 'REJECT' ? <p>No approved change will be created from this finding.</p> : null}
      {kind === 'ESCALATE' ? <p>This finding will remain unresolved and block finalization.</p> : null}

      {kind === 'EDIT' ? (
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
              This finding cannot be edited here. Reject or escalate it instead.
            </p>
          ) : null}
        </>
      ) : null}

      <div>
        <textarea
          ref={needsLanguage ? undefined : firstField}
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
          {kind === 'EDIT' ? 'Save decision' : kind === 'REJECT' ? 'Reject' : 'Escalate'}
        </Button>
      </div>
    </form>
  )
}
