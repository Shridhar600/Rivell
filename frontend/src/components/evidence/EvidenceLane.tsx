import type { ReactNode } from 'react'
import type { EvidenceLane as EvidenceLaneDefinition } from '../../lib/evidence'

export function EvidenceLane({
  lane,
  count,
  emptyState,
  emptyNote,
  children,
}: {
  lane: EvidenceLaneDefinition
  count: number
  emptyState?: string
  emptyNote?: string
  children?: ReactNode
}) {
  const headingId = `evidence-lane-${lane.authority.toLowerCase()}`
  return (
    <section
      className={`evidence-lane evidence-lane--${lane.prominence}`}
      aria-labelledby={headingId}
    >
      <header className="evidence-lane__header">
        <h2 id={headingId}>{lane.label}</h2>
        {count > 0 ? (
          <span className="evidence-lane__count">{`${count} item${count === 1 ? '' : 's'}`}</span>
        ) : null}
      </header>
      <p className="evidence-lane__description">{lane.description}</p>
      {count === 0 ? (
        <div className="evidence-lane__empty">
          <p>{emptyState ?? lane.emptyState}</p>
          {emptyNote ? <p>{emptyNote}</p> : null}
        </div>
      ) : (
        <ul className="evidence-list">{children}</ul>
      )}
    </section>
  )
}
