import { useState } from 'react'
import { useCorrectCandidateConcept, useEditCandidate, type Candidate } from '../../../api/knowledge'
import { effectiveContentText, effectivePayloadColumns } from '../../../lib/candidates'
import {
  sameStringMap,
  scopeDraftChanged,
  scopeDraftFrom,
  scopeInputFrom,
  semanticDraftFrom,
  semanticInputFrom,
  type ScopeDraft,
  type SemanticDraft,
} from '../../../lib/semantics'

export interface CorrectionDraft {
  title: string
  contentText: string
  columns: Record<string, string>
  scope: ScopeDraft
  semantic: SemanticDraft
  /** A taxonomy key, or an empty string for "not classified". */
  concept: string
  note: string
}

export function draftFrom(candidate: Candidate): CorrectionDraft {
  return {
    title: candidate.title,
    contentText: effectiveContentText(candidate) ?? '',
    columns: { ...effectivePayloadColumns(candidate) },
    scope: scopeDraftFrom(candidate),
    semantic: semanticDraftFrom(candidate),
    concept: candidate.effectiveConceptKey ?? '',
    note: '',
  }
}

/** What would be sent, and nothing else. The backend preserves the machine value beside each change. */
export function correctionChanges(candidate: Candidate, draft: CorrectionDraft) {
  const initialColumns = effectivePayloadColumns(candidate)
  const hasColumns = Object.keys(initialColumns).length > 0
  const trimmedTitle = draft.title.trim()
  // A blank title clears an existing correction. With no correction to clear it changes nothing,
  // and the backend refuses a title-only save that changes nothing.
  const titleChanged = trimmedTitle === '' ? candidate.editedTitle !== null : trimmedTitle !== candidate.title
  const contentChanged = draft.contentText !== (effectiveContentText(candidate) ?? '') && draft.contentText.trim().length > 0
  const columnsChanged = hasColumns && !sameStringMap(draft.columns, initialColumns)
  const scopeChanged = scopeDraftChanged(draft.scope, candidate)
  const semantic = semanticInputFrom(draft.semantic, candidate)
  const conceptChanged = draft.concept !== (candidate.effectiveConceptKey ?? '')
  const edit = titleChanged || contentChanged || columnsChanged || scopeChanged || semantic !== undefined
  return { titleChanged, contentChanged, columnsChanged, scopeChanged, semantic, conceptChanged, edit, any: edit || conceptChanged }
}

/**
 * Saves a correction as the backend defines it. Wording, columns, scope, roles, escalation and
 * approval go to the edit endpoint, and the legal concept goes to its own endpoint, so a
 * correction can take two requests. Nothing is applied locally: the candidate is read again from
 * the server after each one, and what the reviewer sees afterwards is that answer.
 */
export function useCorrection(candidate: Candidate, onSaved: () => void) {
  const edit = useEditCandidate()
  const concept = useCorrectCandidateConcept()
  const [draft, setDraft] = useState<CorrectionDraft>(() => draftFrom(candidate))
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const changes = correctionChanges(candidate, draft)

  async function save() {
    if (!changes.any) {
      setError('Change at least one value before saving.')
      return
    }
    setError(null)
    setPending(true)
    let savedEdit = false
    try {
      if (changes.edit) {
        const saved = await edit.mutateAsync({
          candidateId: candidate.id,
          title: changes.titleChanged ? draft.title : undefined,
          contentText: changes.contentChanged ? draft.contentText : undefined,
          payloadColumns: changes.columnsChanged ? draft.columns : undefined,
          scope: changes.scopeChanged ? scopeInputFrom(draft.scope) : undefined,
          semanticFields: changes.semantic,
          note: draft.note,
        })
        setDraft(draftFrom(saved))
        savedEdit = true
      }
      if (changes.conceptChanged) {
        const saved = await concept.mutateAsync({ candidateId: candidate.id, primary: draft.concept })
        setDraft(draftFrom(saved))
      }
      onSaved()
    } catch (failure) {
      const message = failure instanceof Error && failure.message ? failure.message : 'The correction could not be saved.'
      setError(savedEdit ? `Your other corrections were saved, but the topic was not: ${message}` : message)
    } finally {
      setPending(false)
    }
  }

  return { draft, setDraft, save, pending, error, changed: changes.any }
}

export type Correction = ReturnType<typeof useCorrection>
