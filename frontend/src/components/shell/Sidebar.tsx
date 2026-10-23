import { Link, NavLink } from 'react-router'
import rivellLogo from '../../assets/brand/rivell-logo.svg'
import { IconButton } from '../ui/Button'
import { PanelLeftIcon } from '../ui/Icon'
import { NAV_GROUPS, type NavDestination } from './navigation'
import { PanelResizeHandle } from './PanelResizeHandle'
import { SIDEBAR_WIDTH } from './ShellContext'

export const SIDEBAR_ID = 'rv-sidebar'

interface SidebarProps {
  collapsed: boolean
  width: number
  onResize: (width: number) => void
}

export function Sidebar({ collapsed, width, onResize }: SidebarProps) {
  return (
    // A closed sidebar is out of view, so it is also out of the tab order and the accessibility tree.
    <aside className="rv-side" id={SIDEBAR_ID} aria-label="Sidebar" inert={collapsed}>
      <div className="rv-side__inner">
        {/* No organisation name, user menu or greeting: identity is deferred and must not be faked. */}
        <div className="rv-side__head">
          <Link to="/" className="rv-side__brand" aria-label="Rivell">
            <img className="rv-side__wordmark" src={rivellLogo} alt="" width={68} height={24} />
          </Link>
        </div>

        {NAV_GROUPS.map((group) => (
          <nav key={group.id} className="rv-nav" aria-label={group.heading ?? 'Primary'}>
            {group.heading ? <div className="rv-nav__heading">{group.heading}</div> : null}
            {group.items.map((destination) => (
              <NavItem key={destination.id} destination={destination} />
            ))}
          </nav>
        ))}
      </div>

      {collapsed ? null : (
        <PanelResizeHandle className="rv-side__resize" label="Resize sidebar" edge="right" width={width} limits={SIDEBAR_WIDTH} onResize={onResize} />
      )}
    </aside>
  )
}

/**
 * The one sidebar toggle. It stays in the top left corner whether the sidebar is open or closed, so
 * the reader always finds it in the same place. Stable name plus aria-expanded: a toggle must not
 * change its accessible name when it changes state.
 */
export function SidebarToggle({ collapsed, onToggle }: { collapsed: boolean; onToggle: () => void }) {
  return (
    <IconButton
      className="rv-side-toggle"
      label="Sidebar"
      expanded={!collapsed}
      controls={SIDEBAR_ID}
      title={collapsed ? 'Show sidebar [' : 'Hide sidebar ['}
      onClick={onToggle}
    >
      <PanelLeftIcon className="rv-icon" />
    </IconButton>
  )
}

function NavItem({ destination }: { destination: NavDestination }) {
  const { to, label, end, Icon } = destination
  return (
    <NavLink to={to} end={end} className="rv-nav__link">
      <Icon className="rv-icon" />
      <span className="rv-nav__text">{label}</span>
    </NavLink>
  )
}
