import { useId, useRef, useState, type DragEvent, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useUploadKnowledge, type ArtifactManifestInput, type ArtifactType } from '../../../api/knowledge'
import { formatBytes } from '../../../lib/knowledgeLabels'
import { sourceFileProblem } from '../../../lib/knowledgeUpload'
import { Button } from '../../ui/Button'
import { Dialog } from '../../ui/Dialog'
import { ErrorState } from '../../ui/State'
import { FileIcon, UploadIcon } from '../../ui/Icon'

/** The two authority classes Add knowledge can create. The reviewer chooses; Rivell never infers it. */
export type AddType = Extract<ArtifactType, 'PLAYBOOK' | 'CLAUSE_LIBRARY'>

const TYPES: { type: AddType; label: string; hint: string }[] = [
  { type: 'PLAYBOOK', label: 'Playbook', hint: 'Positions: preferred, fallback, escalation' },
  { type: 'CLAUSE_LIBRARY', label: 'Clause library', hint: 'Approved reusable wording' },
]

interface Details {
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

const NO_DETAILS: Details = {
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

function manifestFrom(type: AddType, details: Details): ArtifactManifestInput {
  return {
    artifactType: type,
    declaredContractTypes: details.declaredContractTypes
      .split(',')
      .map((entry) => entry.trim())
      .filter((entry) => entry.length > 0),
    contractPackKey: details.contractPackKey,
    partyPerspective: details.partyPerspective,
    paperSource: details.paperSource,
    jurisdiction: details.jurisdiction,
    language: details.language,
    effectiveFrom: details.effectiveFrom,
    effectiveTo: details.effectiveTo,
    note: details.note,
  }
}

interface AddKnowledgeDialogProps {
  /** Chosen for the reviewer when they opened the dialog from "Add playbook" or "Add clause library". */
  preset: AddType | null
  onClose: () => void
}

/**
 * Add knowledge. The reviewer chooses what the document is and gives a DOCX or XLSX file. The upload
 * stores the immutable original and extracts it in one request, so there is no progress to show
 * beyond "working". When the server answers, the reviewer is taken into the compilation of the
 * source it just created.
 */
export function AddKnowledgeDialog({ preset, onClose }: AddKnowledgeDialogProps) {
  const ids = useId()
  const navigate = useNavigate()
  const upload = useUploadKnowledge()
  const input = useRef<HTMLInputElement>(null)
  const [type, setType] = useState<AddType | null>(preset)
  const [file, setFile] = useState<File | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
  const [details, setDetails] = useState<Details>(NO_DETAILS)
  const [periodError, setPeriodError] = useState<string | null>(null)
  const busy = upload.isPending

  function choose(next: File | undefined) {
    if (!next) return
    const problem = sourceFileProblem(next)
    setFileError(problem)
    setFile(problem ? null : next)
  }

  function onDrop(event: DragEvent) {
    event.preventDefault()
    setDragging(false)
    if (!busy) choose(event.dataTransfer.files[0])
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!type || !file || busy) return
    if (details.effectiveFrom && details.effectiveTo && details.effectiveFrom > details.effectiveTo) {
      setPeriodError('Effective from must not be after effective to.')
      return
    }
    setPeriodError(null)
    upload.mutate(
      { file, manifest: manifestFrom(type, details) },
      { onSuccess: (artifact) => navigate(`/knowledge/compilations/${artifact.id}`) },
    )
  }

  const set = (key: keyof Details) => (event: { target: { value: string } }) =>
    setDetails((previous) => ({ ...previous, [key]: event.target.value }))
  const titleId = `${ids}-title`

  return (
    <Dialog labelledBy={titleId} onClose={() => (busy ? undefined : onClose())}>
      <form className="rv-dlg__in" onSubmit={submit} aria-busy={busy}>
        <h2 id={titleId}>Add knowledge</h2>

        <fieldset className="rv-k-add__field" disabled={busy}>
          <legend className="rv-dlg__lb">What is this document?</legend>
          <div className="rv-k-add__types" role="radiogroup" aria-label="What is this document?">
            {TYPES.map((entry) => (
              <label key={entry.type} className="rv-k-add__type">
                <input
                  type="radio"
                  name={`${ids}-type`}
                  className="rv-visually-hidden"
                  checked={type === entry.type}
                  onChange={() => setType(entry.type)}
                />
                <b>{entry.label}</b>
                <span>{entry.hint}</span>
              </label>
            ))}
          </div>
          <p className="rv-dlg__note">Rivell uses the type you choose.</p>
        </fieldset>

        <div>
          <div className="rv-dlg__lb" id={`${ids}-file-label`}>
            File (Word or Excel)
          </div>
          {file ? (
            <div className="rv-k-add__file">
              <FileIcon className="rv-icon" />
              <b>{file.name}</b>
              <span>{formatBytes(file.size)}</span>
              <button type="button" className="rv-lnk" disabled={busy} onClick={() => setFile(null)}>
                Change
              </button>
            </div>
          ) : (
            <div
              className={`rv-k-add__drop${dragging ? ' rv-k-add__drop--over' : ''}`}
              onDragEnter={(event) => {
                event.preventDefault()
                setDragging(true)
              }}
              onDragOver={(event) => event.preventDefault()}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
            >
              <UploadIcon className="rv-icon" />
              <button type="button" className="rv-lnk" aria-describedby={`${ids}-file-label`} onClick={() => input.current?.click()}>
                Choose a file
              </button>
              <span> or drop it here</span>
            </div>
          )}
          <input
            ref={input}
            type="file"
            className="rv-visually-hidden"
            tabIndex={-1}
            aria-label="Source file"
            accept=".docx,.xlsx"
            onChange={(event) => {
              choose(event.target.files?.[0])
              event.target.value = ''
            }}
          />
          {fileError ? (
            <p className="rv-k-add__error" role="alert">
              {fileError}
            </p>
          ) : null}
        </div>

        <details className="rv-k-add__more">
          <summary>Where this applies (optional)</summary>
          <p className="rv-dlg__note">
            Where this knowledge applies. You can change it for each entry later.
          </p>
          <div className="rv-k-add__grid">
            <label>
              Contract types
              <input value={details.declaredContractTypes} onChange={set('declaredContractTypes')} placeholder="MSA, NDA" disabled={busy} />
            </label>
            <label>
              Contract pack
              <input value={details.contractPackKey} onChange={set('contractPackKey')} disabled={busy} />
            </label>
            <label>
              Party perspective
              <input value={details.partyPerspective} onChange={set('partyPerspective')} disabled={busy} />
            </label>
            <label>
              Paper source
              <input value={details.paperSource} onChange={set('paperSource')} disabled={busy} />
            </label>
            <label>
              Jurisdiction
              <input value={details.jurisdiction} onChange={set('jurisdiction')} disabled={busy} />
            </label>
            <label>
              Language
              <input value={details.language} onChange={set('language')} disabled={busy} />
            </label>
            <label>
              Effective from
              <input type="date" value={details.effectiveFrom} onChange={set('effectiveFrom')} disabled={busy} />
            </label>
            <label>
              Effective to
              <input type="date" value={details.effectiveTo} onChange={set('effectiveTo')} disabled={busy} />
            </label>
          </div>
          {periodError ? (
            <p className="rv-k-add__error" role="alert">
              {periodError}
            </p>
          ) : null}
          <label className="rv-k-add__wide">
            Note
            <textarea rows={2} value={details.note} onChange={set('note')} disabled={busy} />
          </label>
        </details>

        <p className="rv-dlg__note">Nothing is published until you review the entries. The original is kept unchanged.</p>

        {busy ? (
          <p role="status" className="rv-k-add__busy">
            Saving and reading the file. This can take a moment.
          </p>
        ) : null}
        {upload.isError ? <ErrorState title="The file was not added" message={upload.error.message} /> : null}

        <div className="rv-dlg__foot">
          <Button disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" disabled={!type || !file || busy}>
            {busy ? 'Reading…' : upload.isError ? 'Try again' : 'Continue to draft'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
