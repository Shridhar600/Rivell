import { useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useUploadContract } from '../api/contracts'
import { ErrorState } from './ErrorState'
import { LoadingState } from './LoadingState'

function isDocx(file: File): boolean {
  return file.name.toLowerCase().endsWith('.docx')
}

export function NewReviewForm() {
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
      onSuccess: (summary) => navigate(`/reviews/${summary.contractId}`),
    })
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="upload-form"
      aria-label="New review"
    >
      <h2>New Review</h2>
      <p>Upload a .docx contract to open its structural workspace.</p>

      <label htmlFor="contract-file">Contract file (.docx)</label>
      <input
        ref={inputRef}
        id="contract-file"
        name="file"
        type="file"
        accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        disabled={upload.isPending}
        onChange={handleFileChange}
      />

      {validationError ? (
        <p role="alert" className="state state--error">
          {validationError}
        </p>
      ) : null}

      {upload.isPending ? (
        <LoadingState label="Uploading and parsing…" />
      ) : (
        <button type="submit" className="button">
          Start Review
        </button>
      )}

      {upload.isError ? (
        <ErrorState
          message={upload.error.message || 'Upload failed.'}
          onRetry={() => {
            const file = inputRef.current?.files?.[0]
            if (file) upload.mutate(file)
          }}
        />
      ) : null}
    </form>
  )
}
