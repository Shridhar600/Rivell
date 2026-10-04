import { useId, type ReactNode } from 'react'
import { onTabKeys } from '../../../lib/tabKeys'

export interface TabSpec<K extends string> {
  key: K
  label: string
}

/**
 * The canonical underlined tabs and their one panel. The selected tab is the only one in the tab
 * order; the others are reached with the arrow keys, as in the ARIA tabs pattern.
 */
export function KnowledgeTabs<K extends string>({
  label,
  tabs,
  selected,
  onSelect,
  children,
}: {
  label: string
  tabs: TabSpec<K>[]
  selected: K
  onSelect: (key: K) => void
  children: ReactNode
}) {
  const base = useId()
  return (
    <>
      <div className="rv-k-tabs" role="tablist" aria-label={label}>
        {tabs.map((tab) => (
          <button
            key={tab.key}
            type="button"
            role="tab"
            id={`${base}-${tab.key}`}
            className="rv-k-tab"
            aria-selected={tab.key === selected}
            aria-controls={`${base}-panel`}
            tabIndex={tab.key === selected ? 0 : -1}
            onClick={() => onSelect(tab.key)}
            onKeyDown={onTabKeys}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <div id={`${base}-panel`} role="tabpanel" aria-labelledby={`${base}-${selected}`}>
        {children}
      </div>
    </>
  )
}
