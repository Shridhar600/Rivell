import { Link } from 'react-router'

export interface Crumb {
  label: string
  to?: string
}

/** Where the reader is. The last crumb is the current page; the others are links up. */
export function Breadcrumbs({ crumbs }: { crumbs: Crumb[] }) {
  return (
    <nav className="rv-crumbs" aria-label="Breadcrumb">
      {crumbs.map((crumb, position) =>
        position === crumbs.length - 1 || !crumb.to ? (
          <b key={crumb.label} aria-current={position === crumbs.length - 1 ? 'page' : undefined}>
            {crumb.label}
          </b>
        ) : (
          <span key={crumb.label} className="rv-crumbs__item">
            <Link to={crumb.to}>{crumb.label}</Link>
            <span className="rv-crumbs__sep" aria-hidden="true">
              {' / '}
            </span>
          </span>
        ),
      )}
    </nav>
  )
}
