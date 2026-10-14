import { Link, NavLink, useLocation } from 'react-router'
import rivellIcon from '../../assets/brand/rivell-icon.svg'
import rivellLogo from '../../assets/brand/rivell-logo.svg'
import { IconButton } from '../ui/Button'
import { PanelLeftIcon } from '../ui/Icon'
import { openSections, TOP_LEVEL_NAVIGATION, type NavDestination } from './navigation'

export const SIDEBAR_ID = 'rv-sidebar'

interface SidebarProps {
  collapsed: boolean
  onToggle: () => void
}

export function Sidebar({ collapsed, onToggle }: SidebarProps) {
  const { pathname } = useLocation()
  return (
    <aside className="rv-side" id={SIDEBAR_ID} aria-label="Sidebar">
      {/* No organisation name, user menu or greeting: identity is deferred and must not be faked. */}
      <div className="rv-side__head">
        <Link to="/" className="rv-side__brand" aria-label="Rivell" title={collapsed ? 'Rivell' : undefined}>
          {/* The wordmark needs 72 px; the collapsed sidebar shows the square icon instead. */}
          <img className="rv-side__wordmark" src={rivellLogo} alt="" width={76} height={27} />
          <img className="rv-side__icon" src={rivellIcon} alt="" width={22} height={22} />
        </Link>
        {/*
          The sidebar owns its own collapse control, so it sits in the sidebar's header and moves
          with the sidebar. Stable name plus aria-expanded: a toggle button must not change its
          accessible name when it changes state.
        */}
        <IconButton
          className="rv-side__toggle"
          label="Sidebar"
          expanded={!collapsed}
          controls={SIDEBAR_ID}
          title={collapsed ? 'Expand sidebar [' : 'Collapse sidebar ['}
          onClick={onToggle}
        >
          <PanelLeftIcon className="rv-icon" />
        </IconButton>
      </div>

      <nav className="rv-nav" aria-label="Primary">
        {TOP_LEVEL_NAVIGATION.map((destination) => (
          <NavItem key={destination.id} destination={destination} collapsed={collapsed} />
        ))}
      </nav>

      {/* Sections of the destination the reader is inside. They come from the shared navigation model. */}
      {openSections(pathname).map((parent) => (
        <nav key={parent.id} className={`rv-nav rv-nav--${parent.id}`} aria-label={parent.childrenLabel ?? parent.label}>
          <div className="rv-nav__heading">{parent.childrenLabel ?? parent.label}</div>
          {parent.children?.map((child) => (
            <NavItem key={child.id} destination={child} collapsed={collapsed} />
          ))}
        </nav>
      ))}
    </aside>
  )
}

function NavItem({ destination, collapsed }: { destination: NavDestination; collapsed: boolean }) {
  const { to, label, end, Icon } = destination
  return (
    <NavLink to={to} end={end} className="rv-nav__link" title={collapsed ? label : undefined}>
      <Icon className="rv-icon" />
      <span className="rv-nav__text">{label}</span>
    </NavLink>
  )
}
