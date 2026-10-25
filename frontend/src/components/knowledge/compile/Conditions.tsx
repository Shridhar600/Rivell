import { useState } from 'react'
import { useCandidateQuery, type Candidate, type ConflictSignal, type ConflictType } from '../../../api/knowledge'
import { conditionsOf, needsConflictAcknowledgement, reasonLines, type Condition } from '../../../lib/compilation'
import { positionText } from '../../../lib/knowledgeCatalog'
import { displayScopeRows } from '../../../lib/knowledgePosition'
import { formatPositions } from '../../../lib/semantics'
import { ArrowRightIcon, CautionIcon } from '../../ui/Icon'

const SIGNAL_LABELS: Partial<Record<ConflictType, string>> = {
  DUPLICATE: 'Possible duplicate',
  CONTRADICTS_EXISTING: 'Possible conflict',
  SUPERSEDES_EXISTING: 'Possible supersession',
  OVERLAPPING_EFFECTIVE_PERIOD: 'Overlapping effective period',
  JURISDICTION_MISMATCH: 'Jurisdiction differs',
  AUTHORITY_CONFLICT: 'Conflict with other published knowledge',
}

function PublishedPeer({ candidateId }: { candidateId: string }) {
  const peer = useCandidateQuery(candidateId)
  if (peer.isPending) return <p className="rv-kc-muted">Loading the published knowledge…</p>
  if (peer.isError || !peer.data) return <p className="rv-kc-muted">The published knowledge could not be loaded.</p>
  const entry = peer.data
  const text = positionText(entry)
  const scope = displayScopeRows(entry)
  return (
    <div className="rv-kc-peer">
      {text ? <p>{text}</p> : <p className="rv-kc-muted">It has no authored wording.</p>}
      <dl className="rv-kc-peer__dl">
        {entry.positions.length > 0 ? (
          <div className="rv-kc-peer__row">
            <dt>Positions</dt>
            <dd>{formatPositions(entry.positions)}</dd>
          </div>
        ) : null}
        {scope.map((row) => (
          <div key={row.label} className="rv-kc-peer__row">
            <dt>{row.label}</dt>
            <dd>{row.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

function PeerLabel({ candidateId, titles }: { candidateId: string; titles: ReadonlyMap<string, string> }) {
  return <strong>{titles.get(candidateId) ?? 'earlier published knowledge'}</strong>
}

interface PeerRelation {
  peerId: string
  /** What the server compared them as, each at most once. */
  types: ConflictType[]
}

/** One row per published candidate, with every way the server related this one to it. */
function relations(signals: ConflictSignal[]): PeerRelation[] {
  const byPeer = new Map<string, ConflictType[]>()
  for (const signal of signals) {
    if (!signal.publishedCandidateId) continue
    const types = byPeer.get(signal.publishedCandidateId) ?? []
    if (!types.includes(signal.conflictType)) types.push(signal.conflictType)
    byPeer.set(signal.publishedCandidateId, types)
  }
  return [...byPeer.entries()].map(([peerId, types]) => ({ peerId, types }))
}

function Relation({ relation, peerTitles }: { relation: PeerRelation; peerTitles: ReadonlyMap<string, string> }) {
  const [open, setOpen] = useState(false)
  return (
    <li className="rv-kc-rel">
      <div className="rv-kc-rel__h">
        <span>
          <PeerLabel candidateId={relation.peerId} titles={peerTitles} />
          <span className="rv-kc-rel__t">{relation.types.map((type) => SIGNAL_LABELS[type] ?? 'Relationship not determined').join(', ')}</span>
        </span>
        <button type="button" className="rv-lnk" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
          {open ? 'Hide' : 'Compare'}
        </button>
      </div>
      {open ? <PublishedPeer candidateId={relation.peerId} /> : null}
    </li>
  )
}

function calloutTone(condition: Condition): string {
  if (condition.kind === 'supersession') return 'rv-kc-callout--teal'
  if (condition.kind === 'duplicate') return 'rv-kc-callout--neutral'
  return ''
}

interface Props {
  candidate: Candidate
}

/**
 * Why this candidate needs attention. These are conditions, which explain the need to look. They
 * are not the review outcome, and nothing here decides anything until the reviewer acts.
 */
export function ConditionNotice({ candidate }: Props) {
  const [first, ...others] = conditionsOf(candidate)
  if (!first) return null
  const reasons = reasonLines(candidate)
  return (
    <div className={`rv-kc-callout ${calloutTone(first)}`} role="group" aria-label={first.label}>
      {first.kind === 'supersession' ? <ArrowRightIcon className="rv-icon" /> : <CautionIcon className="rv-icon" />}
      <div>
        <b>{first.label}</b>
        {first.detail}
        {others.length > 0 ? <span className="rv-kc-callout__also">Also: {others.map((condition) => condition.label.toLowerCase()).join(', ')}.</span> : null}
        {reasons.length > 0 ? (
          <details className="rv-kc-callout__why">
            <summary>Why Rivell flagged this</summary>
            <ul>
              {reasons.map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          </details>
        ) : null}
      </div>
    </div>
  )
}

const SHOWN_AT_FIRST = 4

interface ChoicesProps extends Props {
  /** The reviewer has said they compared the published knowledge and still want to approve. */
  acknowledged: boolean
  onAcknowledge: (value: boolean) => void
  /** The published candidate this one will replace on publication, if the reviewer chose that. */
  replaces: string | null
  onReplace: (published: string | null) => void
  /** Titles of the candidates this compilation was compared with, loaded once by the workspace. */
  peerTitles: ReadonlyMap<string, string>
}

/**
 * What the reviewer can do about a condition: compare with the published knowledge the server
 * named, choose whether this replaces one of them when it is published, and confirm a conflict
 * knowingly. None of it changes a candidate. It is all read from the server's answer.
 */
export function ConditionChoices({ candidate, acknowledged, onAcknowledge, replaces, onReplace, peerTitles }: ChoicesProps) {
  const [all, setAll] = useState(false)
  const related = relations(candidate.conflictSignals)
  const replaceable = related.filter((relation) => relation.types.includes('SUPERSEDES_EXISTING')).map((relation) => relation.peerId)
  const mustAcknowledge = needsConflictAcknowledgement(candidate)
  if (related.length === 0 && !mustAcknowledge) return null
  const shown = all ? related : related.slice(0, SHOWN_AT_FIRST)

  return (
    <div className="rv-kc-conds">
      {related.length > 0 ? (
        <section className="rv-kc-rels" aria-label="Published knowledge this was compared with">
          <h2 className="rv-kc-sech">Compared with published knowledge</h2>
          <ul>
            {shown.map((relation) => (
              <Relation key={relation.peerId} relation={relation} peerTitles={peerTitles} />
            ))}
          </ul>
          {related.length > SHOWN_AT_FIRST ? (
            <button type="button" className="rv-lnk rv-kc-rels__more" aria-expanded={all} onClick={() => setAll((value) => !value)}>
              {all ? 'Show fewer' : `Show all ${related.length}`}
            </button>
          ) : null}
        </section>
      ) : null}

      {replaceable.length > 0 ? (
        <fieldset className="rv-kc-replace">
          <legend>When I publish this</legend>
          <label className="rv-kc-choice">
            <input type="radio" name={`replace-${candidate.id}`} checked={replaces === null} onChange={() => onReplace(null)} />
            <span>Do not replace anything. The earlier knowledge stays in use alongside it.</span>
          </label>
          {replaceable.map((peerId) => (
            <label key={peerId} className="rv-kc-choice">
              <input type="radio" name={`replace-${candidate.id}`} checked={replaces === peerId} onChange={() => onReplace(peerId)} />
              <span>
                Replace <PeerLabel candidateId={peerId} titles={peerTitles} />. It is not deleted: earlier versions still show it.
              </span>
            </label>
          ))}
          <small>An entry can replace only one published entry.</small>
        </fieldset>
      ) : null}

      {mustAcknowledge ? (
        <div className="rv-kc-ack">
          <p>
            Correct or reject it, or compare it with published knowledge and confirm below.
          </p>
          <label className="rv-kc-choice">
            <input type="checkbox" checked={acknowledged} onChange={(event) => onAcknowledge(event.target.checked)} />
            <span>I compared this with published knowledge and still want to approve it.</span>
          </label>
        </div>
      ) : null}
    </div>
  )
}
