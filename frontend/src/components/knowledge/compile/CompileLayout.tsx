import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { ArtifactType } from '../../../api/knowledge'
import { backTarget, kindLabel } from '../../../lib/compilation'
import { BackIcon, PanelLeftIcon } from '../../ui/Icon'
import { IconButton } from '../../ui/Button'

interface TopBarProps {
  type: ArtifactType | null
  filename: string
  /** The candidate list is hidden or shown. Absent where there is no list. */
  rail?: { open: boolean; onToggle: () => void; id: string }
  /** Progress and the publish control, when there is a compilation to review. */
  children?: ReactNode
}

export function CompileTopBar({ type, filename, rail, children }: TopBarProps) {
  return (
    <header className="rv-kc-top">
      {rail ? (
        <IconButton label="Candidate list" expanded={rail.open} controls={rail.id} title={rail.open ? 'Hide list [' : 'Show list ['} onClick={rail.onToggle}>
          <PanelLeftIcon className="rv-icon" />
        </IconButton>
      ) : null}
      <Link className="rv-kc-back" to={backTarget(type)}>
        <BackIcon className="rv-icon" />
        Knowledge
      </Link>
      <span className="rv-kc-sep" aria-hidden="true">
        /
      </span>
      <span className="rv-kc-ttl">{filename}</span>
      {type ? <span className="rv-chip">{kindLabel(type)}</span> : null}
      <span className="rv-kc-sp" />
      {children}
    </header>
  )
}

interface LayoutProps {
  top: ReactNode
  rail?: ReactNode
  railOpen?: boolean
  children: ReactNode
}

/**
 * The compilation workspace is full-bleed, as in the canonical design: its own top bar, a candidate
 * list on the left and the candidate on the right. It carries no app sidebar, so it renders outside
 * the shell.
 */
export function CompileLayout({ top, rail, railOpen = true, children }: LayoutProps) {
  return (
    <div className="rv-kc" data-panel-keys="own">
      {top}
      <div className="rv-kc-work" data-rail={rail && railOpen ? 'open' : 'closed'}>
        {rail && railOpen ? rail : null}
        <main className="rv-kc-main" aria-label="Candidate review">
          {children}
        </main>
      </div>
    </div>
  )
}
