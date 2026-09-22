import type { OutlineItem } from '../lib/documentTree'

export function OutlineTree({
  items,
  selectedId,
  onSelect,
}: {
  items: OutlineItem[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  if (items.length === 0) {
    return <p className="outline__empty">No outline yet.</p>
  }

  return (
    <nav aria-label="Document outline" className="outline">
      <ol className="outline__list">
        {items.map((item) => (
          <li
            key={item.id}
            className="outline__item"
            style={{ marginInlineStart: `${item.depth * 0.75}rem` }}
          >
            <button
              type="button"
              className="outline__button"
              aria-current={item.id === selectedId ? 'true' : undefined}
              onClick={() => onSelect(item.id)}
            >
              {item.label}
            </button>
          </li>
        ))}
      </ol>
    </nav>
  )
}
