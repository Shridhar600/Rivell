import { useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useUploadContract } from '../api/contracts'
import { Button } from './ui/Button'

function isDocx(file: File): boolean {
  return file.name.toLowerCase().endsWith('.docx')
}

export function NewReviewForm({ onCancel }: { onCancel?: () => void }) {
  const navigate = useNavigate()
  const upload = useUploadContract()
  const inputRef = useRef<HTMLInputElement>(null)
  const [validationError, setValidationError] = useState<string | null>(null)

  function validateFile(file: File | undefined): string | null {
    if (!file) return 'Choose a .docx file to start a review.'
    if (!isDocx(file)) return 'Only .docx files are supported.'
    return null
  }

  function handleFileChange() {
    const file = inputRef.current?.files?.[0]
    setValidationError(file ? validateFile(file) : null)
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const file = inputRef.current?.files?.[0]
    const error = validateFile(file)
    setValidationError(error)
    if (error || !file) return

    upload.mutate(file, {
      onSuccess: (ingest) => navigate(`/reviews/${ingest.contractId}`),
    })
  }

  return (
    <form onSubmit={handleSubmit} className="rv-card rv-form" aria-label="New review">
      <h2 className="rv-state__title">Start a contract review</h2>
      <p className="rv-state__body">
        Upload a DOCX. Rivell stores it unchanged as the source contract version, then you confirm the
        review context on the contract.
      </p>

      <label htmlFor="contract-file" className="rv-form__label">
        Contract file (.docx)
      </label>
      <div className="rv-form__drop">
        <input
          ref={inputRef}
          id="contract-file"
          name="file"
          type="file"
          accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
          disabled={upload.isPending}
          onChange={handleFileChange}
        />
        <p className="rv-form__hint">Reviewed contract input is DOCX. The original is never modified.</p>
      </div>

      {validationError ? (
        <p role="alert" className="rv-form__error">
          {validationError}
        </p>
      ) : null}
      {upload.isError ? (
        <p role="alert" className="rv-form__error">
          {upload.error.message || 'Upload failed.'}
        </p>
      ) : null}

      <div className="rv-form__actions">
        {upload.isPending ? (
          <p role="status" className="rv-state__body">
            Uploading and parsing…
          </p>
        ) : (
          <Button type="submit" variant="primary">
            Start Review
          </Button>
        )}
        {onCancel && !upload.isPending ? <Button onClick={onCancel}>Cancel</Button> : null}
      </div>
    </form>
  )
}
