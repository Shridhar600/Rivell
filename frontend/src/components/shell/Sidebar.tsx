import type { JSX } from 'react'
import { NavLink } from 'react-router'
import { IconButton } from '../ui/Button'
import { BookIcon, DocumentIcon, HomeIcon, PanelLeftIcon } from '../ui/Icon'

export const SIDEBAR_ID = 'rv-sidebar'

interface SidebarProps {
  collapsed: boolean
  onToggle: () => void
}

export function Sidebar({ collapsed, onToggle }: SidebarProps) {
  return (
    <aside className="rv-side" id={SIDEBAR_ID} aria-label="Sidebar">
      {/* No organisation name, user menu or greeting: identity is deferred and must not be faked. */}
      <div className="rv-side__scroll">
        <NavLink to="/" className="rv-side__brand">
          <span className="rv-logo" aria-hidden="true">
            R
          </span>
          <span className="rv-side__brand-text">Rivell</span>
        </NavLink>

        <nav className="rv-nav" aria-label="Primary">
          <NavItem to="/" label="Home" end Icon={HomeIcon} collapsed={collapsed} />
          <NavItem to="/reviews" label="Reviews" Icon={DocumentIcon} collapsed={collapsed} />
          <NavItem to="/knowledge" label="Knowledge" Icon={BookIcon} collapsed={collapsed} />
        </nav>
      </div>

      {/*
        The sidebar owns its own collapse control and it sits on the sidebar's own edge, so the
        control is never in the central bar. Stable name plus aria-expanded: a toggle button must
        not change its accessible name when it changes state.
      */}
      <IconButton
        className="rv-edge rv-edge--sidebar"
        label="Sidebar"
        expanded={!collapsed}
        controls={SIDEBAR_ID}
        title={collapsed ? 'Expand sidebar [' : 'Collapse sidebar ['}
        onClick={onToggle}
      >
        <PanelLeftIcon className="rv-icon" />
      </IconButton>
    </aside>
  )
}

function NavItem({
  to,
  label,
  end,
  Icon,
  collapsed,
}: {
  to: string
  label: string
  end?: boolean
  Icon: (props: { className?: string }) => JSX.Element
  collapsed: boolean
}) {
  return (
    <NavLink to={to} end={end} className="rv-nav__link" title={collapsed ? label : undefined}>
      <Icon className="rv-icon" />
      <span className="rv-nav__text">{label}</span>
    </NavLink>
  )
}
