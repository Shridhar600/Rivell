import { forwardRef } from 'react'
import { Chip, StatusGlyph } from './Status'

interface WorkRowProps {
  item: {
    id: string
    title: string
    meta: string | null
    chip: string | null
    stateLabel: string
    tone: 'active' | 'attention' | 'danger' | 'neutral' | 'complete'
    timeLabel: string
    timeLabelKind: 'added' | 'updated'
    to: string
  }
  selected: boolean
  /** Real in-progress work spins; a known-complete or failed state never does. */
  spin: boolean
  /** Roving tabindex: only the selected option is in the tab order. */
  tabbable: boolean
  onSelect: () => void
  /** Arrow keys and j/k move selection within the list. */
  onStep: (offset: number) => void
}

/**
 * One unit of work. Clicking selects it and fills the inspector; the inspector's primary action is
 * what opens the item. Selecting must never navigate, or the reviewer loses their place.
 */
export const WorkRow = forwardRef<HTMLLIElement, WorkRowProps>(function WorkRow(
  { item, selected, spin, tabbable, onSelect, onStep },
  ref,
) {
  return (
    <li ref={ref} role="presentation">
      <button
        type="button"
        className="rv-row"
        role="option"
        aria-selected={selected}
        tabIndex={tabbable ? 0 : -1}
        onClick={onSelect}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'j') {
            event.preventDefault()
            onStep(1)
          } else if (event.key === 'ArrowUp' || event.key === 'k') {
            event.preventDefault()
            onStep(-1)
          }
        }}
      >
        <StatusGlyph tone={item.tone} spin={spin} />
        <span className="rv-row__main">
          <span className="rv-row__title">{item.title}</span>
          {item.meta ? <span className="rv-row__meta">{item.meta}</span> : null}
          {item.chip ? <Chip>{item.chip}</Chip> : null}
        </span>
        <span className="rv-row__state">{item.stateLabel}</span>
        {item.timeLabel ? (
          <span
            className="rv-row__time"
            aria-label={`${item.timeLabelKind === 'added' ? 'Added' : 'Updated'} ${item.timeLabel}`}
          >
            {item.timeLabel}
          </span>
        ) : null}
      </button>
    </li>
  )
})