import type { OutlineItem } from '../lib/documentTree'

function OutlineList({
  items,
  selectedId,
  onSelect,
}: {
  items: OutlineItem[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  return (
    <ol className="outline__list">
      {items.map((item) => (
        <li key={item.id} className="outline__item">
          <button
            type="button"
            className="outline__button"
            aria-current={item.id === selectedId ? 'true' : undefined}
            onClick={() => onSelect(item.id)}
          >
            {item.label}
          </button>
          {item.children.length > 0 ? (
            <OutlineList
              items={item.children}
              selectedId={selectedId}
              onSelect={onSelect}
            />
          ) : null}
        </li>
      ))}
    </ol>
  )
}

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
      <OutlineList items={items} selectedId={selectedId} onSelect={onSelect} />
    </nav>
  )
}
