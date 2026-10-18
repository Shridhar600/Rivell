import type { KeyboardEvent, PointerEvent } from 'react'
import { Link, NavLink } from 'react-router'
import rivellIcon from '../../assets/brand/rivell-icon.svg'
import rivellLogo from '../../assets/brand/rivell-logo.svg'
import { IconButton } from '../ui/Button'
import { PanelLeftIcon } from '../ui/Icon'
import { NAV_GROUPS, type NavDestination } from './navigation'
import { clampSidebarWidth, SIDEBAR_WIDTH } from './ShellContext'

export const SIDEBAR_ID = 'rv-sidebar'

interface SidebarProps {
  collapsed: boolean
  onToggle: () => void
  width: number
  onResize: (width: number) => void
  onResizing: (resizing: boolean) => void
}

export function Sidebar({ collapsed, onToggle, width, onResize, onResizing }: SidebarProps) {
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

      {NAV_GROUPS.map((group) => (
        <nav key={group.id} className="rv-nav" aria-label={group.heading ?? 'Primary'}>
          {group.heading ? <div className="rv-nav__heading">{group.heading}</div> : null}
          {group.items.map((destination) => (
            <NavItem key={destination.id} destination={destination} collapsed={collapsed} />
          ))}
        </nav>
      ))}

      {collapsed ? null : <ResizeHandle width={width} onResize={onResize} onResizing={onResizing} />}
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

const KEY_STEP = 16

function ResizeHandle({ width, onResize, onResizing }: Pick<SidebarProps, 'width' | 'onResize' | 'onResizing'>) {
  function onPointerDown(event: PointerEvent<HTMLDivElement>) {
    if (event.button !== 0) return
    event.preventDefault()
    const handle = event.currentTarget
    const startX = event.clientX
    handle.setPointerCapture?.(event.pointerId)
    onResizing(true)
    const move = (moveEvent: globalThis.PointerEvent) => onResize(clampSidebarWidth(width + moveEvent.clientX - startX))
    const stop = () => {
      onResizing(false)
      handle.removeEventListener('pointermove', move)
      handle.removeEventListener('pointerup', stop)
      handle.removeEventListener('pointercancel', stop)
    }
    handle.addEventListener('pointermove', move)
    handle.addEventListener('pointerup', stop)
    handle.addEventListener('pointercancel', stop)
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const next =
      event.key === 'ArrowLeft' ? width - KEY_STEP
      : event.key === 'ArrowRight' ? width + KEY_STEP
      : event.key === 'Home' ? SIDEBAR_WIDTH.min
      : event.key === 'End' ? SIDEBAR_WIDTH.max
      : null
    if (next === null) return
    event.preventDefault()
    onResize(clampSidebarWidth(next))
  }

  return (
    // A focusable separator is the ARIA pattern for a resizable splitter.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
    <div
      className="rv-side__resize"
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize sidebar"
      aria-valuemin={SIDEBAR_WIDTH.min}
      aria-valuemax={SIDEBAR_WIDTH.max}
      aria-valuenow={width}
      tabIndex={0}
      title="Drag to resize. Double-click to reset."
      onPointerDown={onPointerDown}
      onKeyDown={onKeyDown}
      onDoubleClick={() => onResize(SIDEBAR_WIDTH.initial)}
    />
  )
}
