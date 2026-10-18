import { useRef, type ReactNode, type RefObject } from 'react'
import type { ArtifactDetail, Candidate } from '../../../api/knowledge'
import { useTaxonomy } from '../../../api/taxonomy'
import { interpretationRows, hasHumanCorrection } from '../../../lib/compilationFields'
import { artifactFormat } from '../../../lib/knowledgeCatalog'
import { kindLabel } from '../../../lib/compilation'
import type { SourcePassage } from '../../../lib/knowledgeSource'
import { ArrowRightIcon } from '../../ui/Icon'

export interface SourceState {
  /** The passages the candidate's extraction unit recorded. Null while the structure is still loading. */
  passages: SourcePassage[] | null
  /** The extraction unit's own text, shown only when the structure cannot supply the passages. */
  unitText: string | null
}

function SourceCard({ artifact, source, onContext }: { artifact: ArtifactDetail; source: SourceState; onContext?: (opener: RefObject<HTMLButtonElement | null>) => void }) {
  const opener = useRef<HTMLButtonElement>(null)
  const { passages, unitText } = source
  return (
    <section className="rv-kc-xcard rv-kc-xcard--source" aria-label="Source passage">
      <div className="rv-kc-xh">
        <strong>Source passage</strong>
        {onContext ? (
          <button type="button" ref={opener} className="rv-lnk" onClick={() => onContext(opener)}>
            View source context
          </button>
        ) : null}
      </div>
      <div className="rv-kc-xb">
        {passages === null ? <p className="rv-kc-muted">Loading the source passage…</p> : null}
        {passages !== null && passages.length > 0
          ? passages.map((passage) => (
              <div className="rv-kc-pass" key={passage.id}>
                {passage.location ? <div className="rv-kc-loc">{passage.location}</div> : null}
                <p>
                  <mark className="rv-kc-hl">{passage.text}</mark>
                </p>
              </div>
            ))
          : null}
        {passages !== null && passages.length === 0 && unitText ? (
          <div className="rv-kc-pass">
            <div className="rv-kc-loc">Text read from the source passage</div>
            <p>{unitText}</p>
          </div>
        ) : null}
        {passages !== null && passages.length === 0 && !unitText ? (
          <p className="rv-kc-muted">The source passage for this entry is not available.</p>
        ) : null}
        <div className="rv-kc-provline">
          <span>{artifact.filename}</span>
          <span>
            {kindLabel(artifact.artifactType)} · {artifactFormat(artifact)}
          </span>
          <span>Original retained</span>
        </div>
      </div>
    </section>
  )
}

function InterpretationCard({ candidate, artifact, editing }: { candidate: Candidate; artifact: ArtifactDetail; editing: ReactNode | null }) {
  const { labelFor } = useTaxonomy()
  const corrected = hasHumanCorrection(candidate, artifact)
  const rows = interpretationRows(candidate, labelFor)
  return (
    <section className="rv-kc-xcard" aria-label="What Rivell read">
      <div className="rv-kc-xh">
        <strong>What Rivell read</strong>
        {corrected ? <span className="rv-kc-stb rv-kc-stb--ok">Corrected by you</span> : <span className="rv-kc-xh__meta">Rivell read</span>}
      </div>
      <div className="rv-kc-xb">
        {editing ?? (
          <dl className="rv-kc-fields">
            {rows.map((row) => (
              <div className="rv-kc-fr" key={row.key}>
                <dt>{row.label}</dt>
                <dd>
                  {row.value}
                  {row.machine ? <span className="rv-kc-was">{row.machine}</span> : null}
                  {row.support ? <span className="rv-kc-unc">{row.support}</span> : null}
                </dd>
              </div>
            ))}
          </dl>
        )}
        {corrected && !editing ? (
          <p className="rv-kc-history">Rivell&apos;s original reading is kept. Your correction is used.</p>
        ) : null}
      </div>
    </section>
  )
}

/** The source passage beside the structured interpretation it produced. */
export function Transform({
  candidate,
  artifact,
  source,
  onContext,
  editing = null,
}: {
  candidate: Candidate
  artifact: ArtifactDetail
  source: SourceState
  onContext?: (opener: RefObject<HTMLButtonElement | null>) => void
  editing?: ReactNode | null
}) {
  return (
    <div className="rv-kc-xform">
      <SourceCard artifact={artifact} source={source} onContext={onContext} />
      <div className="rv-kc-xarrow" aria-hidden="true">
        <ArrowRightIcon className="rv-icon" />
      </div>
      <InterpretationCard candidate={candidate} artifact={artifact} editing={editing} />
    </div>
  )
}
