import { NavLink, Outlet } from 'react-router'

const navClass = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'nav__link nav__link--active' : 'nav__link'

export function AppShell() {
  return (
    <div className="app-shell">
      <header className="app-shell__header">
        <span className="app-shell__brand">Rivell Workbench</span>
        <nav aria-label="Primary">
          <ul className="nav">
            <li>
              <NavLink to="/reviews" className={navClass}>
                Reviews
              </NavLink>
            </li>
            <li>
              <NavLink to="/knowledge" className={navClass}>
                Knowledge
              </NavLink>
            </li>
          </ul>
        </nav>
      </header>
      <main id="main" className="app-shell__main">
        <Outlet />
      </main>
    </div>
  )
}
