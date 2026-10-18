import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { SearchIcon } from '../workspace/WorkspaceIcons'

const GROUP_LIMIT = 6
const GROUP_LIMIT_SEARCHING = 20

export interface PaletteItem {
  group: string
  label: string
  hint?: string
  key?: string
  icon: ReactNode
  run: () => void
}

interface Props {
  open: boolean
  onClose: () => void
  items: PaletteItem[]
  /** Find a term in the open contract's text. Only the review workspace offers it. */
  onSearch?: (term: string) => void
  /** Shown while the lists behind the menu are still loading. */
  loading?: boolean
}

/**
 * The one search menu (⌘K). Every page lists the same places to go: reviews, playbooks, clauses
 * and sources by name. The page that is open adds its own commands on top.
 */
export function CommandPalette({ open, onClose, items, onSearch, loading = false }: Props) {
  const ref = useRef<HTMLDialogElement>(null)
  const [query, setQuery] = useState('')
  const [index, setIndex] = useState(0)

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  // A click on the backdrop lands on the dialog element itself and closes it.
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    const close = (event: MouseEvent) => {
      if (event.target === dialog) onClose()
    }
    dialog.addEventListener('click', close)
    return () => dialog.removeEventListener('click', close)
  }, [onClose])

  const term = query.trim()
  const shown = useMemo(() => {
    const needle = term.toLowerCase()
    // A long list would bury the page's own commands, so each group shows its first few entries.
    const perGroup = new Map<string, number>()
    const matches = items.filter((item) => {
      if (needle && !`${item.label} ${item.hint ?? ''} ${item.group}`.toLowerCase().includes(needle)) return false
      const count = (perGroup.get(item.group) ?? 0) + 1
      perGroup.set(item.group, count)
      return count <= (needle ? GROUP_LIMIT_SEARCHING : GROUP_LIMIT)
    })
    if (onSearch && term.length > 2) {
      matches.push({
        group: 'Search',
        label: `Find “${term}” in this contract`,
        icon: <SearchIcon />,
        run: () => onSearch(term),
      })
    }
    return matches
  }, [items, term, onSearch])
  const active = Math.min(index, Math.max(0, shown.length - 1))

  function run(item: PaletteItem | undefined) {
    if (!item) return
    onClose()
    setTimeout(item.run, 0)
  }

  return (
    <dialog
      ref={ref}
      className="rv-ws-dialog rv-ws-pal rv-cmd"
      aria-label="Search"
      onClose={() => {
        setQuery('')
        setIndex(0)
        onClose()
      }}
    >
      <div className="rv-ws-pal-i">
        <SearchIcon />
        <input
          placeholder="Search reviews, playbooks and clauses"
          aria-label="Search"
          autoComplete="off"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value)
            setIndex(0)
          }}
          onKeyDown={(event) => {
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
              event.preventDefault()
              const step = event.key === 'ArrowDown' ? 1 : -1
              setIndex((active + step + shown.length) % Math.max(1, shown.length))
            } else if (event.key === 'Enter') {
              event.preventDefault()
              run(shown[active])
            }
          }}
        />
        <kbd>Esc</kbd>
      </div>
      <div className="rv-ws-pal-l" role="listbox" aria-label="Results">
        {shown.length === 0 ? <div className="rv-ws-pal-e">{loading ? 'Loading…' : 'No results'}</div> : null}
        {shown.map((item, n) => {
          const heading = n === 0 || shown[n - 1].group !== item.group ? <div className="rv-ws-pal-h">{item.group}</div> : null
          return (
            <div key={`${item.group}-${item.label}-${n}`}>
              {heading}
              <button
                type="button"
                role="option"
                className="rv-ws-pal-o"
                aria-selected={n === active}
                onMouseMove={() => setIndex(n)}
                onClick={() => run(item)}
              >
                {item.icon}
                <span>{item.label}</span>
                {item.hint ? <small>{item.hint}</small> : null}
                {item.key ? <kbd>{item.key}</kbd> : null}
              </button>
            </div>
          )
        })}
      </div>
      <div className="rv-ws-pal-f">
        <span>
          <kbd>↑</kbd>
          <kbd>↓</kbd>move
        </span>
        <span>
          <kbd>↵</kbd>select
        </span>
      </div>
    </dialog>
  )
}
