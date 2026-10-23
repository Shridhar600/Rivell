import type { ReactNode } from 'react'
import type { ArtifactType } from '../../../api/knowledge'
import { kindLabel } from '../../../lib/compilation'
import { Breadcrumbs, type Crumb } from '../../shell/Breadcrumbs'
import { TopBar } from '../../shell/TopBar'
import { PanelLeftIcon } from '../../ui/Icon'
import { IconButton } from '../../ui/Button'

const LIST_CRUMB: Partial<Record<ArtifactType, Crumb>> = {
  PLAYBOOK: { label: 'Playbooks', to: '/knowledge/playbooks' },
  CLAUSE_LIBRARY: { label: 'Clause library', to: '/knowledge/clauses' },
}

interface TopBarProps {
  type: ArtifactType | null
  filename: string
  /** The candidate list is hidden or shown. Absent where there is no list. */
  rail?: { open: boolean; onToggle: () => void; id: string }
  /** Progress and the publish control, when there is a compilation to review. */
  children?: ReactNode
}

export function CompileTopBar({ type, filename, rail, children }: TopBarProps) {
  const listCrumb = type ? LIST_CRUMB[type] : undefined
  return (
    <TopBar
      leading={
        rail ? (
          <IconButton label="Entry list" expanded={rail.open} controls={rail.id} title={rail.open ? 'Hide list [' : 'Show list ['} onClick={rail.onToggle}>
            <PanelLeftIcon className="rv-icon" />
          </IconButton>
        ) : null
      }
      title={
        <div className="rv-ws-crumbs">
          <Breadcrumbs crumbs={[{ label: 'Knowledge', to: '/knowledge' }, ...(listCrumb ? [listCrumb] : []), { label: filename }]} />
          {type ? <span className="rv-chip">{kindLabel(type)}</span> : null}
        </div>
      }
      actions={children}
    />
  )
}

interface LayoutProps {
  top: ReactNode
  rail?: ReactNode
  railOpen?: boolean
  children: ReactNode
}

/**
 * The compilation workspace takes the full page width inside the shell: the shared top bar, a
 * candidate list on the left and the candidate on the right.
 */
export function CompileLayout({ top, rail, railOpen = true, children }: LayoutProps) {
  return (
    <div className="rv-kc" data-panel-keys="own">
      {top}
      <div className="rv-kc-work" data-rail={rail && railOpen ? 'open' : 'closed'}>
        {rail && railOpen ? rail : null}
        <main className="rv-kc-main" aria-label="Entry review">
          {children}
        </main>
      </div>
    </div>
  )
}
