import { NavLink } from 'react-router'
import { IconButton } from '../ui/Button'
import { BookIcon, DocumentIcon, HomeIcon, PanelLeftIcon } from '../ui/Icon'
import type { ComponentType } from 'react'

interface SidebarProps {
  collapsed: boolean
  onToggle: () => void
}

export function Sidebar({ collapsed, onToggle }: SidebarProps) {
  return (
    <aside className="rv-side" aria-label="Sidebar">
      {/* No organisation name, user menu or greeting: identity is deferred and must not be faked. */}
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

      <div className="rv-side__foot">
        {/* The sidebar owns its own collapse control, and the shortcut is stated next to it. */}
        <span className="rv-side__hint" aria-hidden="true">
          [
        </span>
        <IconButton
          label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          pressed={!collapsed}
          onClick={onToggle}
        >
          <PanelLeftIcon className="rv-icon" />
        </IconButton>
      </div>
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
  Icon: ComponentType<{ className?: string }>
  collapsed: boolean
}) {
  return (
    <NavLink to={to} end={end} className="rv-nav__link" title={collapsed ? label : undefined}>
      <Icon className="rv-icon" />
      <span className="rv-nav__text">{label}</span>
    </NavLink>
  )
}