import { useState, type ReactNode } from 'react'
import type { ArtifactDetail, Candidate, ExtractionUnit, RecordKind } from '../../../api/knowledge'
import { artifactFormat } from '../../../lib/knowledgeCatalog'
import { RECORD_KIND_LABELS, UNIT_KIND_LABELS } from '../../../lib/knowledgeLabels'
import {
  OUTCOME_LABELS,
  kindLabel,
  outcomeOf,
  primaryCondition,
  type Outcome,
  type Readiness,
  type ReviewGroups,
} from '../../../lib/compilation'
import { CautionIcon, CheckIcon, ClauseIcon, StateApprovedIcon, StateOpenIcon, StateRejectedIcon } from '../../ui/Icon'

function Glyph({ outcome, attention }: { outcome: Outcome; attention: boolean }) {
  if (outcome === 'approved' || outcome === 'published') return <StateApprovedIcon className="rv-icon rv-kc-g rv-kc-g--ok" />
  if (outcome === 'rejected') return <StateRejectedIcon className="rv-icon rv-kc-g rv-kc-g--no" />
  if (attention) return <CautionIcon className="rv-icon rv-kc-g rv-kc-g--att" />
  return <StateOpenIcon className="rv-icon rv-kc-g" />
}

/** The kind the compiler judged the unit to be. Old data has none, and shows no label. */
function RecordKindTag({ kind }: { kind: RecordKind | null }) {
  if (!kind) return null
  return <span className="rv-kc-kind">{RECORD_KIND_LABELS[kind]}</span>
}

function Row({ candidate, selected, location, recordKind, madeByReviewer, onSelect }: { candidate: Candidate; selected: boolean; location: string | null; recordKind: RecordKind | null; madeByReviewer: boolean; onSelect: (id: string) => void }) {
  const outcome = outcomeOf(candidate)
  const open = outcome === 'pending' || outcome === 'corrected'
  const condition = open ? primaryCondition(candidate) : null
  return (
    <li>
      <button type="button" className="rv-kc-row" aria-current={selected ? 'true' : undefined} onClick={() => onSelect(candidate.id)}>
        <Glyph outcome={outcome} attention={condition !== null} />
        <span>
          <b>{candidate.title}</b>
          <span className="rv-kc-row__sub">
            {OUTCOME_LABELS[outcome]}
            {open && location ? ` · ${location}` : ''}
          </span>
          {condition ? (
            <span className={`rv-kc-flag${condition.kind === 'supersession' ? ' rv-kc-flag--sup' : ''}`}>{condition.label}</span>
          ) : null}
          {madeByReviewer ? (
            <>
              {recordKind ? <span className="rv-kc-kind">Rivell’s reading: {RECORD_KIND_LABELS[recordKind]}</span> : null}
              <span className="rv-kc-kind rv-kc-kind--you">Added by a reviewer</span>
            </>
          ) : (
            <RecordKindTag kind={recordKind} />
          )}
        </span>
      </button>
    </li>
  )
}

/** A record the compiler kept as a unit instead of a candidate: shown, but plainly secondary. */
function UnitRow({ unit, onMakePosition }: { unit: ExtractionUnit; onMakePosition: ((unitId: string) => Promise<void>) | null }) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const text = unit.contentText?.replace(/\s+/g, ' ').trim()
  const title = unit.title?.trim() || (text ? text.slice(0, 90) : `Source part ${unit.ordinal + 1}`)
  async function make() {
    if (!onMakePosition || pending) return
    setPending(true)
    setError(null)
    try {
      await onMakePosition(unit.id)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'The part could not be made a position.')
    } finally {
      setPending(false)
    }
  }
  return (
    <li>
      <div className="rv-kc-unit">
        <span>
          <b>{title}</b>
          <span className="rv-kc-row__sub">{UNIT_KIND_LABELS[unit.kind]}</span>
          {error ? (
            <span className="rv-kc-unit__err" role="alert">
              {error}
            </span>
          ) : null}
        </span>
        <RecordKindTag kind={unit.recordKind} />
        {onMakePosition ? (
          <button type="button" className="rv-lnk rv-kc-unit__make" disabled={pending} onClick={() => void make()}>
            Make this a position
          </button>
        ) : null}
      </div>
    </li>
  )
}

function UnitGroup({ title, units, onMakePosition }: { title: string; units: ExtractionUnit[]; onMakePosition: ((unitId: string) => Promise<void>) | null }) {
  if (units.length === 0) return null
  return (
    <section className="rv-kc-grp">
      <h3 className="rv-kc-fgrp">
        <span>{title}</span>
        <span>{units.length}</span>
      </h3>
      <ul className="rv-kc-rows">
        {units.map((unit) => (
          <UnitRow key={unit.id} unit={unit} onMakePosition={onMakePosition} />
        ))}
      </ul>
    </section>
  )
}

function Group({ title, count, children }: { title: string; count: number; children: ReactNode }) {
  if (count === 0) return null
  return (
    <section className="rv-kc-grp">
      <h3 className="rv-kc-fgrp">
        <span>{title}</span>
        <span>{count}</span>
      </h3>
      <ul className="rv-kc-rows">{children}</ul>
    </section>
  )
}

interface RailProps {
  id: string
  artifact: ArtifactDetail
  groups: ReviewGroups
  readiness: Readiness
  locations: ReadonlyMap<string, string | null>
  /** The unit behind each candidate, for its record kind. */
  unitsById: ReadonlyMap<string, ExtractionUnit>
  /** Units the compiler kept as records, not candidates. Secondary, and never compared. */
  /** Parts Rivell read as something other than a position. */
  notPosition: ExtractionUnit[]
  /** Parts Rivell read as a position or wording that hold no candidate. */
  missed: ExtractionUnit[]
  /** Makes a part a position; null where the file type sorts no parts into positions. */
  onMakePosition: ((unitId: string) => Promise<void>) | null
  selectedId: string | null
  groupSelected: boolean
  onSelect: (id: string) => void
  onOpenGroup: () => void
}

/** The candidate list, grouped the way a reviewer works: what needs attention, what to review, the safe group, what is done. */
export function CandidateRail({ id, artifact, groups, readiness, locations, unitsById, notPosition, missed, onMakePosition, selectedId, groupSelected, onSelect, onOpenGroup }: RailProps) {
  const rows = (list: Candidate[]) =>
    list.map((candidate) => (
      <Row
        key={candidate.id}
        candidate={candidate}
        selected={!groupSelected && candidate.id === selectedId}
        location={locations.get(candidate.id) ?? null}
        recordKind={unitsById.get(candidate.extractionUnitId)?.recordKind ?? null}
        madeByReviewer={Boolean(unitsById.get(candidate.extractionUnitId)?.recordKindOverride)}
        onSelect={onSelect}
      />
    ))
  const ready = readiness.canPublish
  const done = readiness.published > 0 && readiness.awaiting === 0 && readiness.approved === 0
  return (
    <aside className="rv-kc-fl" id={id} aria-label="Entries">
      <div className="rv-kc-src">
        <b>{artifact.filename}</b>
        <small>
          {kindLabel(artifact.artifactType)} · {artifactFormat(artifact)} source
        </small>
        <small>Original retained unchanged</small>
      </div>
      <nav className="rv-kc-list" aria-label="Entry list">
        <Group title="Needs attention" count={groups.attention.length}>
          {rows(groups.attention)}
        </Group>
        <Group title="To review" count={groups.toReview.length}>
          {rows(groups.toReview)}
        </Group>
        {groups.bulk.length > 0 ? (
          <section className="rv-kc-grp">
            <h3 className="rv-kc-fgrp">
              <span>Review together</span>
            </h3>
            <ul className="rv-kc-rows">
              <li>
                <button type="button" className="rv-kc-bulkrow" aria-current={groupSelected ? 'true' : undefined} onClick={onOpenGroup}>
                  <ClauseIcon className="rv-icon rv-kc-g" />
                  <span>
                    <b>
                      {groups.bulk.length} routine {groups.bulk.length === 1 ? 'entry' : 'entries'}
                    </b>
                    <span className="rv-kc-row__sub">Inspect, then approve together</span>
                  </span>
                </button>
              </li>
            </ul>
          </section>
        ) : null}
        <Group title="Reviewed" count={groups.reviewed.length}>
          {rows(groups.reviewed)}
        </Group>
        <Group title="Published, cannot be changed" count={groups.published.length}>
          {rows(groups.published)}
        </Group>
        <UnitGroup title="Missed" units={missed} onMakePosition={onMakePosition} />
        <UnitGroup title="Not treated as a position" units={notPosition} onMakePosition={onMakePosition} />
      </nav>
      <div className="rv-kc-ready" aria-live="polite">
        <b>{ready ? 'Ready to publish' : done ? 'Published' : 'Draft review'}</b>
        <div className="rv-kc-ready__m">
          <span>Awaiting review</span>
          <span>{readiness.awaiting}</span>
        </div>
        <div className="rv-kc-ready__m">
          <span>Approved</span>
          <span>{readiness.approved}</span>
        </div>
        <div className="rv-kc-ready__m">
          <span>Rejected</span>
          <span>{readiness.rejected}</span>
        </div>
        {readiness.published > 0 ? (
          <div className="rv-kc-ready__m">
            <span>Published</span>
            <span>{readiness.published}</span>
          </div>
        ) : null}
        <p className={readiness.attention > 0 ? 'rv-kc-ready__no' : 'rv-kc-ready__ok'}>
          {readiness.attention > 0 ? <CautionIcon className="rv-icon" /> : <CheckIcon className="rv-icon" />}
          {readiness.attention > 0 ? `${readiness.attention} need closer review` : 'No flagged entries remain'}
        </p>
        <p className="rv-kc-ready__hint">{done ? 'Published knowledge cannot be changed.' : 'Nothing is published until you publish it.'}</p>
      </div>
    </aside>
  )
}
