import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { SearchIcon } from './WorkspaceIcons'

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
  /** Find a term in the contract text. Local to the open document. */
  onSearch: (term: string) => void
}

/**
 * Only commands that can run here are listed: jump to a loaded finding, decide the selected one,
 * switch the document view, open a source, finalize when the server allows it, and navigate.
 * There is no global entity search.
 */
export function CommandPalette({ open, onClose, items, onSearch }: Props) {
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
    const matches = items.filter((item) => !needle || `${item.label} ${item.hint ?? ''} ${item.group}`.toLowerCase().includes(needle))
    if (term.length > 2) {
      matches.push({
        group: 'Search',
        label: `Find “${term}” in contract`,
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
      className="rv-ws-dialog rv-ws-pal"
      aria-label="Command menu"
      onClose={() => {
        setQuery('')
        setIndex(0)
        onClose()
      }}
    >
      <div className="rv-ws-pal-i">
        <SearchIcon />
        <input
          placeholder="Jump to or run a command"
          aria-label="Command menu search"
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
      <div className="rv-ws-pal-l" role="listbox" aria-label="Commands">
        {shown.length === 0 ? <div className="rv-ws-pal-e">No results</div> : null}
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
