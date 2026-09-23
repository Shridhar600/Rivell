import { useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import {
  ARTIFACT_TYPES,
  useUploadKnowledge,
  type ArtifactManifestInput,
  type ArtifactType,
} from '../../api/knowledge'
import {
  ARTIFACT_TYPE_DESCRIPTIONS,
  ARTIFACT_TYPE_LABELS,
} from '../../lib/knowledgeLabels'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'

interface FormState {
  artifactType: ArtifactType | ''
  declaredContractTypes: string
  contractPackKey: string
  partyPerspective: string
  paperSource: string
  jurisdiction: string
  language: string
  effectiveFrom: string
  effectiveTo: string
  note: string
}

const INITIAL_FORM: FormState = {
  artifactType: '',
  declaredContractTypes: '',
  contractPackKey: '',
  partyPerspective: '',
  paperSource: '',
  jurisdiction: '',
  language: '',
  effectiveFrom: '',
  effectiveTo: '',
  note: '',
}

interface FormErrors {
  file?: string
  artifactType?: string
  effectivePeriod?: string
}

function sourceFileError(file: File | undefined): string | undefined {
  if (!file) return 'Choose a .docx or .xlsx source file.'
  if (!/\.(docx|xlsx)$/i.test(file.name)) {
    return 'Only .docx and .xlsx files are supported.'
  }
  return undefined
}

function splitContractTypes(value: string): string[] {
  return value
    .split(',')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0)
}

export function AddKnowledgeForm() {
  const navigate = useNavigate()
  const upload = useUploadKnowledge()
  const inputRef = useRef<HTMLInputElement>(null)
  const [form, setForm] = useState<FormState>(INITIAL_FORM)
  const [errors, setErrors] = useState<FormErrors>({})

  function setField<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((previous) => ({ ...previous, [key]: value }))
  }

  function buildManifest(): ArtifactManifestInput {
    if (form.artifactType === '') {
      throw new Error('Artifact type is required')
    }
    return {
      artifactType: form.artifactType,
      declaredContractTypes: splitContractTypes(form.declaredContractTypes),
      contractPackKey: form.contractPackKey,
      partyPerspective: form.partyPerspective,
      paperSource: form.paperSource,
      jurisdiction: form.jurisdiction,
      language: form.language,
      effectiveFrom: form.effectiveFrom,
      effectiveTo: form.effectiveTo,
      note: form.note,
    }
  }

  function validate(): FormErrors {
    const next: FormErrors = {}
    const file = inputRef.current?.files?.[0]
    const fileProblem = sourceFileError(file)
    if (fileProblem) next.file = fileProblem
    if (form.artifactType === '') {
      next.artifactType = 'Select the source artifact type.'
    }
    if (
      form.effectiveFrom !== '' &&
      form.effectiveTo !== '' &&
      form.effectiveFrom > form.effectiveTo
    ) {
      next.effectivePeriod = 'Effective from must not be after effective to.'
    }
    return next
  }

  function startUpload(file: File) {
    upload.mutate(
      { file, manifest: buildManifest() },
      {
        onSuccess: (artifact) => navigate(`/knowledge/artifacts/${artifact.id}`),
      },
    )
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const next = validate()
    setErrors(next)
    const file = inputRef.current?.files?.[0]
    if (Object.keys(next).length > 0 || !file) return
    startUpload(file)
  }

  function retryUpload() {
    const file = inputRef.current?.files?.[0]
    if (file) startUpload(file)
  }

  const selectedType = form.artifactType === '' ? null : form.artifactType

  return (
    <form
      className="upload-form knowledge-form"
      aria-label="Add knowledge"
      onSubmit={handleSubmit}
    >
      <p className="meta">
        Rivell stores the exact source file and deterministically extracts its
        structure, extraction units, and typed knowledge candidates. Nothing
        becomes organizational knowledge until a person reviews and publishes it.
      </p>

      <fieldset className="field-group">
        <legend>Source</legend>

        <div className="field">
          <label htmlFor="knowledge-file">Source file (.docx or .xlsx)</label>
          <input
            ref={inputRef}
            id="knowledge-file"
            name="file"
            type="file"
            accept=".docx,.xlsx,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            disabled={upload.isPending}
            aria-invalid={errors.file ? true : undefined}
            aria-describedby={errors.file ? 'knowledge-file-error' : undefined}
            onChange={() =>
              setErrors((previous) => ({ ...previous, file: undefined }))
            }
          />
          {errors.file ? (
            <p id="knowledge-file-error" className="field__error" role="alert">
              {errors.file}
            </p>
          ) : null}
        </div>

        <div className="field">
          <label htmlFor="knowledge-artifact-type">Artifact type</label>
          <select
            id="knowledge-artifact-type"
            value={form.artifactType}
            disabled={upload.isPending}
            aria-invalid={errors.artifactType ? true : undefined}
            aria-describedby="knowledge-artifact-type-hint"
            onChange={(event) =>
              setField('artifactType', event.target.value as ArtifactType | '')
            }
          >
            <option value="">Select artifact type…</option>
            {ARTIFACT_TYPES.map((artifactType) => (
              <option key={artifactType} value={artifactType}>
                {ARTIFACT_TYPE_LABELS[artifactType]}
              </option>
            ))}
          </select>
          <p id="knowledge-artifact-type-hint" className="field__hint">
            {selectedType
              ? ARTIFACT_TYPE_DESCRIPTIONS[selectedType]
              : 'The selected type is authoritative and defines how Rivell compiles this source.'}
          </p>
          {errors.artifactType ? (
            <p className="field__error" role="alert">
              {errors.artifactType}
            </p>
          ) : null}
        </div>
      </fieldset>

      <fieldset className="field-group">
        <legend>Manifest</legend>

        <div className="field">
          <label htmlFor="knowledge-declared-types">
            Declared contract types
          </label>
          <input
            id="knowledge-declared-types"
            type="text"
            value={form.declaredContractTypes}
            placeholder="MSA, NDA"
            disabled={upload.isPending}
            onChange={(event) =>
              setField('declaredContractTypes', event.target.value)
            }
          />
          <p className="field__hint">
            Comma-separated contract types this source applies to.
          </p>
        </div>

        <div className="field">
          <label htmlFor="knowledge-contract-pack">Contract pack key</label>
          <input
            id="knowledge-contract-pack"
            type="text"
            value={form.contractPackKey}
            placeholder="MSA"
            disabled={upload.isPending}
            onChange={(event) => setField('contractPackKey', event.target.value)}
          />
        </div>

        <div className="field">
          <label htmlFor="knowledge-party-perspective">
            Party perspective
          </label>
          <input
            id="knowledge-party-perspective"
            type="text"
            value={form.partyPerspective}
            placeholder="Customer"
            disabled={upload.isPending}
            onChange={(event) =>
              setField('partyPerspective', event.target.value)
            }
          />
        </div>

        <div className="field">
          <label htmlFor="knowledge-paper-source">Paper source</label>
          <input
            id="knowledge-paper-source"
            type="text"
            value={form.paperSource}
            placeholder="Third-party"
            disabled={upload.isPending}
            onChange={(event) => setField('paperSource', event.target.value)}
          />
        </div>

        <div className="field">
          <label htmlFor="knowledge-jurisdiction">Jurisdiction</label>
          <input
            id="knowledge-jurisdiction"
            type="text"
            value={form.jurisdiction}
            placeholder="US"
            disabled={upload.isPending}
            onChange={(event) => setField('jurisdiction', event.target.value)}
          />
        </div>

        <div className="field">
          <label htmlFor="knowledge-language">Language</label>
          <input
            id="knowledge-language"
            type="text"
            value={form.language}
            placeholder="en"
            disabled={upload.isPending}
            onChange={(event) => setField('language', event.target.value)}
          />
        </div>

        <div className="field">
          <label htmlFor="knowledge-effective-from">Effective from</label>
          <input
            id="knowledge-effective-from"
            type="date"
            value={form.effectiveFrom}
            disabled={upload.isPending}
            aria-invalid={errors.effectivePeriod ? true : undefined}
            aria-describedby={
              errors.effectivePeriod ? 'knowledge-effective-period-error' : undefined
            }
            onChange={(event) => setField('effectiveFrom', event.target.value)}
          />
        </div>

        <div className="field">
          <label htmlFor="knowledge-effective-to">Effective to</label>
          <input
            id="knowledge-effective-to"
            type="date"
            value={form.effectiveTo}
            disabled={upload.isPending}
            aria-invalid={errors.effectivePeriod ? true : undefined}
            aria-describedby={
              errors.effectivePeriod ? 'knowledge-effective-period-error' : undefined
            }
            onChange={(event) => setField('effectiveTo', event.target.value)}
          />
          {errors.effectivePeriod ? (
            <p
              id="knowledge-effective-period-error"
              className="field__error"
              role="alert"
            >
              {errors.effectivePeriod}
            </p>
          ) : null}
        </div>

        <div className="field">
          <label htmlFor="knowledge-note">Note</label>
          <textarea
            id="knowledge-note"
            value={form.note}
            rows={3}
            disabled={upload.isPending}
            onChange={(event) => setField('note', event.target.value)}
          />
        </div>
      </fieldset>

      {upload.isPending ? (
        <LoadingState label="Storing source and extracting structure…" />
      ) : (
        <button type="submit" className="button button--primary">
          Add knowledge
        </button>
      )}

      {upload.isError ? (
        <ErrorState
          message={upload.error.message || 'Upload failed.'}
          onRetry={retryUpload}
        />
      ) : null}
    </form>
  )
}
