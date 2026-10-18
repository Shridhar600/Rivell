import { useId } from 'react'
import type { ArtifactDetail, Candidate, ExtractionUnit } from '../../../api/knowledge'
import { OUTCOME_LABELS, kindLabel, outcomeOf, primaryCondition } from '../../../lib/compilation'
import { UNIT_KIND_LABELS } from '../../../lib/knowledgeLabels'
import { contextWindow, type SourceBlock } from '../../../lib/knowledgeSource'
import { Button, IconButton } from '../../ui/Button'
import { Dialog } from '../../ui/Dialog'
import { CloseIcon } from '../../ui/Icon'

interface Props {
  artifact: ArtifactDetail
  candidate: Candidate
  unit: ExtractionUnit | null
  location: string | null
  /** The extracted original in reading order, with this candidate's nodes marked. */
  blocks: SourceBlock[] | null
  onClose: () => void
}

function Block({ block, marked }: { block: SourceBlock; marked: boolean }) {
  const text = marked ? <mark className="rv-kc-hl">{block.text}</mark> : block.text
  return (
    <div className={`rv-kc-pass${marked ? '' : ' rv-kc-pass--dim'}`}>
      {block.kind === 'title' || block.kind === 'heading' ? <h4>{text}</h4> : <p>{text}</p>}
    </div>
  )
}

/**
 * The candidate's source in context, as a side sheet. The marked passages are exactly the source
 * nodes the candidate's extraction unit recorded. The text around them is the extracted original,
 * a few blocks either side. Closing returns to the candidate.
 */
export function SourceSheet({ artifact, candidate, unit, location, blocks, onClose }: Props) {
  const titleId = useId()
  const window = blocks ? contextWindow(blocks, candidate.id) : null
  const condition = outcomeOf(candidate) === 'pending' || outcomeOf(candidate) === 'corrected' ? primaryCondition(candidate) : null
  return (
    <Dialog variant="sheet" labelledBy={titleId} onClose={onClose}>
      <div className="rv-kc-sheet-h">
        <span>Source</span>
        <IconButton label="Close source context" title="Close source context" onClick={onClose}>
          <CloseIcon className="rv-icon" />
        </IconButton>
      </div>
      <div className="rv-kc-sheet-b">
        <div className="rv-kc-kicker">{kindLabel(artifact.artifactType)} source</div>
        <h2 id={titleId}>{candidate.rawLabel}</h2>
        <dl className="rv-kc-props">
          <dt>Source</dt>
          <dd>{artifact.filename}</dd>
          <dt>Location</dt>
          <dd>{location ?? 'Not recorded when the file was read'}</dd>
          {unit ? (
            <>
              <dt>Source part</dt>
              <dd>
                {unit.ordinal + 1}, {UNIT_KIND_LABELS[unit.kind]}, {unit.sourceNodeIds.length} {unit.sourceNodeIds.length === 1 ? 'passage' : 'passages'}
              </dd>
            </>
          ) : null}
          <dt>Source state</dt>
          <dd>Original retained unchanged</dd>
          <dt>Entry</dt>
          <dd>
            {OUTCOME_LABELS[outcomeOf(candidate)]}
            {condition ? `, ${condition.label.toLowerCase()}` : ''}
          </dd>
        </dl>
        <div className="rv-kc-sech">Source context</div>
        <div className="rv-kc-xcard">
          <div className="rv-kc-xb">
            {blocks === null ? <p className="rv-kc-muted">Loading the source…</p> : null}
            {blocks !== null && !window ? <p className="rv-kc-muted">The source passage for this entry is not available.</p> : null}
            {window ? (
              <>
                {window.moreBefore ? <p className="rv-kc-muted">… earlier source …</p> : null}
                {window.before.map((block) => (
                  <Block key={block.id} block={block} marked={false} />
                ))}
                {window.focus.map((block) => (
                  <Block key={block.id} block={block} marked={block.candidateIds.includes(candidate.id)} />
                ))}
                {window.after.map((block) => (
                  <Block key={block.id} block={block} marked={false} />
                ))}
                {window.moreAfter ? <p className="rv-kc-muted">… later source …</p> : null}
              </>
            ) : null}
          </div>
        </div>
      </div>
      <div className="rv-kc-sheet-f">
        <Button onClick={onClose}>Back to the entry</Button>
      </div>
    </Dialog>
  )
}
