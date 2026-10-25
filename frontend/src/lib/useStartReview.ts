import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { uploadContract } from '../api/contracts'
import { UploadApiError } from '../api/uploadError'
import { createReview, reviewErrorStatus, reviewKeys } from '../api/reviews'
import { enrichContract, SemanticApiError, semanticsKeys } from '../api/semantics'
import { newRequestId } from './requestId'
import { classifyCreateOutcome } from './reviewCreation'
import { profileFrom, type StartContext, type StartStage } from './startReview'

export interface StartFailure {
  stage: StartStage
  message: string
  /** What is intact, in the reviewer's terms. Null when nothing was stored. */
  intact: string | null
  /** The stored contract, once there is one, so the reviewer is never left without a way onward. */
  contractId: string | null
}

export type StartStatus =
  | { phase: 'idle' }
  | { phase: 'running'; stage: StartStage }
  | { phase: 'failed'; failure: StartFailure }

interface Stored {
  file: File
  contractId: string
  versionNumber: number
  /** Whether the parser read it as a document. An unreadable file cannot be reviewed. */
  readable: boolean
}

const UPLOADED = 'Your contract is uploaded and stored unchanged. No review was started.'
const UPLOADED_AND_CONFIRMED = 'Your contract is uploaded and stored unchanged, with the confirmed context. No review was started.'

/**
 * Starts a review in the order the backend requires: store the DOCX, confirm the declared context
 * (and optional pack) and wait for an explicit READY, then create the review. A later attempt
 * resumes where the last one stopped: a stored contract is never uploaded twice, and a context
 * that was already confirmed is not confirmed again. The create request keeps one idempotency key
 * for an unchanged operation, so retrying after an uncertain outcome cannot start a second review.
 */
export function useStartReview() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<StartStatus>({ phase: 'idle' })
  const stored = useRef<Stored | null>(null)
  const confirmed = useRef<string | null>(null)
  const request = useRef<{ signature: string; id: string | null } | null>(null)
  const running = useRef(false)
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const start = useCallback(
    async (file: File, context: StartContext) => {
      if (running.current) return
      running.current = true
      const report = (next: StartStatus) => {
        if (mounted.current) setStatus(next)
      }
      let stage: StartStage = 'upload'
      try {
        report({ phase: 'running', stage })
        if (stored.current?.file !== file) {
          const ingest = await uploadContract(file)
          stored.current = {
            file,
            contractId: ingest.contractId,
            versionNumber: ingest.versionNumber,
            readable: ingest.parseStatus === 'OK',
          }
          confirmed.current = null
        }
        const { contractId, versionNumber, readable } = stored.current
        if (!readable) {
          report({
            phase: 'failed',
            failure: {
              stage,
              message: 'Rivell could not read this file as a contract, so no review was started. Choose another Word file.',
              intact: 'The file is stored unchanged.',
              contractId,
            },
          })
          return
        }

        stage = 'confirm'
        report({ phase: 'running', stage })
        const profile = profileFrom(context)
        const profileKey = `${contractId}:${versionNumber}:${JSON.stringify(profile)}`
        if (confirmed.current !== profileKey) {
          const semantics = await enrichContract(contractId, versionNumber, profile)
          queryClient.setQueryData(semanticsKeys.semantics(contractId, versionNumber), semantics)
          // Only an explicit READY lets a review start. Anything else is not "probably fine".
          if (semantics.enrichmentStatus !== 'READY') {
            report({
              phase: 'failed',
              failure: {
                stage,
                message: `The contract's analysis is not ready yet, so no review was started.`,
                intact: UPLOADED,
                contractId,
              },
            })
            return
          }
          confirmed.current = profileKey
        }

        stage = 'create'
        report({ phase: 'running', stage })
        // A changed context is a different operation, so it gets its own key.
        if (request.current?.signature !== profileKey) {
          request.current = { signature: profileKey, id: newRequestId() }
        }
        const summary = await createReview(contractId, versionNumber, context.packKey || null, request.current.id)
        queryClient.setQueryData(reviewKeys.summary(summary.id), summary)
        void queryClient.invalidateQueries({ queryKey: reviewKeys.history() })
        void queryClient.invalidateQueries({ queryKey: reviewKeys.list(contractId) })
        if (mounted.current) navigate(`/reviews/${contractId}/overview/${summary.id}`)
      } catch (error) {
        report({ phase: 'failed', failure: describeFailure(stage, error, stored.current?.contractId ?? null) })
      } finally {
        running.current = false
      }
    },
    [navigate, queryClient],
  )

  // The three steps run in this page. Closing or reloading it midway would leave an uploaded
  // contract with no review, so the browser asks first. Moving inside Rivell is safe: the steps
  // carry on and the review appears in the list.
  const starting = status.phase === 'running'
  useEffect(() => {
    if (!starting) return
    const hold = (event: BeforeUnloadEvent) => event.preventDefault()
    window.addEventListener('beforeunload', hold)
    return () => window.removeEventListener('beforeunload', hold)
  }, [starting])

  const reset = useCallback(() => setStatus({ phase: 'idle' }), [])

  return { status, start, reset }
}

function describeFailure(stage: StartStage, error: unknown, contractId: string | null): StartFailure {
  if (stage === 'upload') {
    return {
      stage,
      message:
        error instanceof UploadApiError
          ? error.message
          : 'The upload could not be confirmed, so no review was started. Check your connection and try again.',
      intact: null,
      contractId: null,
    }
  }
  if (stage === 'confirm') {
    return {
      stage,
      message:
        error instanceof SemanticApiError
          ? error.message
          : 'The review context could not be confirmed, so no review was started. Check your connection and try again.',
      intact: UPLOADED,
      contractId,
    }
  }
  const outcome = classifyCreateOutcome(reviewErrorStatus(error))
  return {
    stage,
    message: outcome.uncertain
      ? 'We could not confirm whether the review started. Starting again reuses the same request, so it cannot create a second review.'
      : outcome.message,
    intact: UPLOADED_AND_CONFIRMED,
    contractId,
  }
}
