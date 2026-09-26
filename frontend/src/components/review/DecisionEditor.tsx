import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import type { FindingDetail } from '../../api/reviews'
import type { ChangeActionType, DecisionAction, HumanDecision } from '../../api/workflow'
import {
  acceptEligibility,
  changeActionLabel,
  changeLanguageProvenanceLabel,
  decisionActionLabel,
  defaultEditAction,
  editActionsForSubject,
  editLanguageRequired,
  validateDecisionForm,
} from '../../lib/workflow'

export type DecisionSubmission = {
  action: DecisionAction
  approvedAction?: ChangeActionType
  approvedLanguage?: string
  comment?: string
  clientRequestId?: string
}

type Props = {
  finding: FindingDetail
  currentDecision: HumanDecision | null
  isSubmitting: boolean
  submitError: string | null
  onSubmit: (submission: DecisionSubmission) => void
  onCancel: () => void
}

const DECISION_ACTIONS: DecisionAction[] = ['ACCEPT', 'EDIT', 'REJECT', 'ESCALATE']

function acceptedActionLabel(finding: FindingDetail): string | null {
  if (finding.proposedAction === 'USE_APPROVED_FALLBACK') return changeActionLabel('REPLACE')
  if (finding.proposedAction === 'CONSIDER_GENERATED_LANGUAGE') {
    return changeActionLabel(finding.subjectKind === 'ABSENCE' ? 'INSERT' : 'REPLACE')
  }
  return null
}

function newRequestId(): string | null {
  const cryptoApi = globalThis.crypto
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') return cryptoApi.randomUUID()
  if (cryptoApi && typeof cryptoApi.getRandomValues === 'function') {
    const bytes = cryptoApi.getRandomValues(new Uint8Array(16))
    bytes[6] = (bytes[6] & 0x0f) | 0x40
    bytes[8] = (bytes[8] & 0x3f) | 0x80
    const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  return null
}

export function DecisionEditor({
  finding,
  currentDecision,
  isSubmitting,
  submitError,
  onSubmit,
  onCancel,
}: Props) {
  const eligibility = acceptEligibility(finding)
  const initialAction: DecisionAction = eligibility.eligible ? 'ACCEPT' : 'EDIT'
  const [action, setAction] = useState<DecisionAction>(initialAction)
  const [approvedAction, setApprovedAction] = useState<ChangeActionType>(
    defaultEditAction(finding.subjectKind),
  )
  const [language, setLanguage] = useState(
    currentDecision?.action === 'EDIT' && currentDecision.approvedLanguage
      ? currentDecision.approvedLanguage
      : '',
  )
  const [comment, setComment] = useState('')
  const [errors, setErrors] = useState<{ comment?: string; approvedLanguage?: string }>({})
  const commentRef = useRef<HTMLTextAreaElement>(null)
  const languageRef = useRef<HTMLTextAreaElement>(null)
  const initialRadioRef = useRef<HTMLInputElement>(null)
  const requestIdRef = useRef<{ signature: string; id: string } | null>(null)

  useEffect(() => {
    initialRadioRef.current?.focus()
  }, [])

  const commentRequired = action === 'REJECT' || action === 'ESCALATE'
  const editableActions = editActionsForSubject(finding.subjectKind)
  const showLanguage =
    action === 'EDIT' && approvedAction !== undefined && editLanguageRequired(approvedAction)
  const acceptedAction = action === 'ACCEPT' ? acceptedActionLabel(finding) : null

  function signature(): string {
    return [action, approvedAction, language, comment].join('\u0000')
  }

  function requestId(): string | undefined {
    const currentSignature = signature()
    if (!requestIdRef.current || requestIdRef.current.signature !== currentSignature) {
      const id = newRequestId()
      requestIdRef.current = id ? { signature: currentSignature, id } : null
    }
    return requestIdRef.current?.id
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const nextErrors = validateDecisionForm({
      action,
      approvedAction,
      approvedLanguage: language,
      comment,
    })
    setErrors(nextErrors)
    if (nextErrors.approvedLanguage) {
      languageRef.current?.focus()
      return
    }
    if (nextErrors.comment) {
      commentRef.current?.focus()
      return
    }
    const submission: DecisionSubmission = { action }
    const id = requestId()
    if (id) submission.clientRequestId = id
    if (action === 'EDIT') submission.approvedAction = approvedAction
    if (showLanguage) submission.approvedLanguage = language
    if (comment.trim().length > 0 || commentRequired) submission.comment = comment
    onSubmit(submission)
  }

  return (
    <form className="decision-editor" onSubmit={handleSubmit} aria-label="Record decision">
      <fieldset className="decision-editor__actions">
        <legend>Your decision</legend>
        {DECISION_ACTIONS.map((option) => {
          const disabled = option === 'ACCEPT' && !eligibility.eligible
          return (
            <label key={option} className="decision-editor__action">
              <input
                type="radio"
                name="decision-action"
                value={option}
                checked={action === option}
                disabled={disabled}
                ref={option === initialAction ? initialRadioRef : undefined}
                onChange={() => {
                  setAction(option)
                  setErrors({})
                }}
              />
              <span>{decisionActionLabel(option)}</span>
            </label>
          )
        })}
      </fieldset>
      {!eligibility.eligible ? (
        <p className="panel__hint">{eligibility.reason}</p>
      ) : null}

      {action === 'ACCEPT' && eligibility.eligible ? (
        <div className="decision-editor__accept-preview">
          <p>
            You are approving:{' '}
            {acceptedAction ? `${acceptedAction} using the machine proposal` : 'the machine proposal'}
          </p>
          {finding.proposedLanguage ? (
            <blockquote className="finding-language__text">{finding.proposedLanguage}</blockquote>
          ) : null}
          <p className="panel__hint">
            {finding.proposedLanguageSource === 'APPROVED_DRAFTING'
              ? changeLanguageProvenanceLabel('APPROVED_DRAFTING')
              : finding.proposedLanguageSource === 'GENERATIVE'
                ? changeLanguageProvenanceLabel('GENERATIVE')
                : changeLanguageProvenanceLabel('UNKNOWN')}
          </p>
        </div>
      ) : null}

      {action === 'EDIT' ? (
        <>
          <fieldset className="decision-editor__change-type">
            <legend>Change type</legend>
            {editableActions.map((option) => (
              <label key={option} className="decision-editor__action">
                <input
                  type="radio"
                  name="decision-change-type"
                  value={option}
                  checked={approvedAction === option}
                  onChange={() => {
                    setApprovedAction(option)
                    setErrors({})
                  }}
                />
                <span>{changeActionLabel(option)}</span>
              </label>
            ))}
          </fieldset>
          {showLanguage ? (
            <div className="decision-editor__field">
              <label htmlFor="decision-language">Final approved language (required)</label>
              <textarea
                id="decision-language"
                ref={languageRef}
                value={language}
                onChange={(event) => setLanguage(event.target.value)}
                aria-required
                aria-invalid={errors.approvedLanguage ? true : undefined}
                aria-describedby={errors.approvedLanguage ? 'decision-language-error' : undefined}
              />
              {errors.approvedLanguage ? (
                <p id="decision-language-error" className="field-error">
                  {errors.approvedLanguage}
                </p>
              ) : null}
              {finding.proposedLanguage ? (
                <button
                  type="button"
                  className="link-button"
                  onClick={() => {
                    setLanguage(finding.proposedLanguage ?? '')
                    setErrors((current) => ({ ...current, approvedLanguage: undefined }))
                  }}
                >
                  Copy machine-proposed wording
                </button>
              ) : null}
            </div>
          ) : null}
        </>
      ) : null}

      {action === 'REJECT' ? (
        <p className="panel__hint">
          No contract change will be created from this finding.
        </p>
      ) : null}
      {action === 'ESCALATE' ? (
        <p className="panel__hint">
          This finding remains unresolved and the review cannot be finalized while the
          escalation is current.
        </p>
      ) : null}

      <div className="decision-editor__field">
        <label htmlFor="decision-comment">
          Comment ({commentRequired ? 'required' : 'optional'})
        </label>
        <textarea
          id="decision-comment"
          ref={commentRef}
          value={comment}
          onChange={(event) => setComment(event.target.value)}
          aria-required={commentRequired}
          aria-invalid={errors.comment ? true : undefined}
          aria-describedby={errors.comment ? 'decision-comment-error' : undefined}
        />
        {errors.comment ? (
          <p id="decision-comment-error" className="field-error">
            {errors.comment}
          </p>
        ) : null}
      </div>

      {submitError ? (
        <p className="state state--warning" role="alert">
          {submitError}
        </p>
      ) : null}

      {action === 'EDIT' && editableActions.length === 0 ? (
        <p className="state state--warning" role="alert">
          This finding's subject type is not recognized, so a change cannot be authored here.
          Reject or escalate it instead.
        </p>
      ) : null}

      <div className="decision-editor__buttons">
        <button
          type="submit"
          className="button"
          disabled={isSubmitting || (action === 'EDIT' && editableActions.length === 0)}
        >
          {decisionActionLabel(action)}
        </button>
        <button type="button" className="link-button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}
