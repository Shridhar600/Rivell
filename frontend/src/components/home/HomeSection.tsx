import { HOME_SECTION_LABELS, type HomeReview, type HomeSectionKey } from '../../lib/home'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { CollapsibleGroup } from '../ui/CollapsibleGroup'
import { WorkRow } from '../ui/WorkRow'
import { WorkDetails } from './WorkDetails'

interface HomeSectionProps {
  section: HomeSectionKey
  items: HomeReview[]
  open: boolean
  onToggle: () => void
  selectedId: string | null
  onSelect: (id: string) => void
  /** Shown under a collapsed result list when more finished Reviews exist than Home lists. */
  footer?: string
}

/** One of Home's lists. Empty sections render nothing, so Home never shows an empty card. */
export function HomeSection({ section, items, open, onToggle, selectedId, onSelect, footer }: HomeSectionProps) {
  // The details panel is hidden at this width, so the selected row shows its details inline.
  const compact = useMediaQuery('(max-width: 1180px)')
  if (items.length === 0) return null
  const selectedHere = items.find((item) => item.id === selectedId) ?? null
  return (
    <div id={section} className="rv-anchor">
      <CollapsibleGroup
        title={HOME_SECTION_LABELS[section]}
        count={items.length}
        open={open}
        onToggle={onToggle}
        collapsedSummary={summarise(items)}
        footer={
          <>
            {compact && open && selectedHere ? (
              <div className="rv-inline-detail">
                <WorkDetails item={selectedHere} />
              </div>
            ) : null}
            {footer ? <p className="rv-group__foot">{footer}</p> : null}
          </>
        }
      >
        <WorkList items={items} selectedId={selectedId} onSelect={onSelect} />
      </CollapsibleGroup>
    </div>
  )
}

/** One line for a collapsed section, capped so a long list cannot build a huge string. */
function summarise(items: HomeReview[]): string {
  const MAX = 3
  if (items.length <= MAX) return items.map((item) => item.title).join(', ')
  return `${items.slice(0, MAX).map((item) => item.title).join(', ')} and ${items.length - MAX} more`
}

function WorkList({
  items,
  selectedId,
  onSelect,
}: {
  items: HomeReview[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  // Never fall back to this list's first row: that made every list highlight a row.
  const currentId = items.some((item) => item.id === selectedId) ? selectedId : null

  function move(offset: number) {
    const index = items.findIndex((item) => item.id === currentId)
    // With no selection there is nothing to step from, so ArrowUp must not jump to the top.
    if (index < 0 && offset < 0) return
    const next = items[Math.min(items.length - 1, Math.max(0, index + offset))]
    if (next) onSelect(next.id)
  }

  return (
    // ul[role=listbox] is the valid ARIA mapping the design system requires for WorkRow.
    // oxlint-disable-next-line jsx-a11y/no-noninteractive-element-to-interactive-role
    <ul className="rv-rows" role="listbox" aria-label="Reviews">
      {items.map((item, index) => (
        <WorkRow
          key={item.id}
          item={item}
          selected={item.id === currentId}
          spin={item.tone === 'active' && !item.queued}
          tabbable={currentId ? item.id === currentId : index === 0}
          onSelect={() => onSelect(item.id)}
          onStep={move}
        />
      ))}
    </ul>
  )
}
