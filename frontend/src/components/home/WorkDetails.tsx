import { longDate } from '../../lib/dates'
import type { HomeWorkItem } from '../../lib/home'
import { LinkButton } from '../ui/Button'
import { StatusBadge } from '../ui/Status'

const KIND_LABELS: Record<HomeWorkItem['kind'], string> = {
  review: 'Contract review',
  knowledge_artifact: 'Knowledge source',
  knowledge_publication: 'Knowledge publication',
}

/**
 * What a selected row is and what to do next: kind, title, state, real facts, one line of
 * guidance, one primary action. The panel and the narrow-screen inline detail share this, so the
 * two can never describe the same row differently.
 */
export function WorkDetails({ item }: { item: HomeWorkItem }) {
  const when = longDate(item.timeValue)
  return (
    <>
      <p className="rv-insp__kind">{KIND_LABELS[item.kind]}</p>
      <h2 className="rv-insp__title">{item.title}</h2>
      <div className="rv-insp__status">
        <StatusBadge tone={item.tone} label={item.stateLabel} />
      </div>
      <dl className="rv-insp__props">
        {item.details.map((detail) => (
          <Property key={detail.label} label={detail.label} value={detail.value} />
        ))}
        {when ? (
          <Property label={item.timeLabelKind === 'added' ? 'Added' : 'Updated'} value={when} />
        ) : null}
      </dl>
      {item.guidance ? <p className="rv-insp__note">{item.guidance}</p> : null}
      <div className="rv-insp__action">
        <LinkButton to={item.to} variant="primary" className="rv-btn--block">
          {item.primaryActionLabel}
        </LinkButton>
      </div>
    </>
  )
}

function Property({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </>
  )
}

/** Shown until a row is chosen. Nothing is auto-selected, so the panel says how to fill it. */
export function WorkDetailsHint() {
  return (
    <div className="rv-insp__empty">
      <p className="rv-insp__kind">Details</p>
      <p className="rv-insp__note">Select work to see what it is and what to do next.</p>
    </div>
  )
}
