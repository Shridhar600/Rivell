import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { TopBar } from '../../shell/TopBar'
import { LinkButton } from '../../ui/Button'
import { PlusIcon } from '../../ui/Icon'
import { useHeadingFocus } from '../../../lib/useHeadingFocus'

export interface Crumb {
  label: string
  to?: string
}

function Breadcrumbs({ crumbs }: { crumbs: Crumb[] }) {
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

/**
 * "Add knowledge" opens the existing Add knowledge page. That page already works, so this is a
 * real destination and not a placeholder. The upload and compilation flow itself is not part of
 * the read surfaces.
 */
export function AddKnowledgeButton() {
  return (
    <LinkButton to="/knowledge/new" variant="primary">
      <PlusIcon className="rv-icon" />
      Add knowledge
    </LinkButton>
  )
}

interface KnowledgeFrameProps {
  crumbs: Crumb[]
  title: string
  lede?: string
  /** Chips and status under the title. */
  meta?: ReactNode
  /** Page-level actions next to the title, before "Add knowledge". */
  actions?: ReactNode
  /** False on pages whose detail panel is not shown, so the page takes the panel's column too. */
  withInspector?: boolean
  children: ReactNode
}

/** The canonical Knowledge page: breadcrumb top bar, then a scrolling page with a serif title. */
export function KnowledgeFrame({ crumbs, title, lede, meta, actions, withInspector = false, children }: KnowledgeFrameProps) {
  const headingRef = useHeadingFocus<HTMLHeadingElement>(title)
  return (
    <div className={`rv-main${withInspector ? '' : ' rv-main--wide'}`}>
      <TopBar title={<Breadcrumbs crumbs={crumbs} />} actions={<AddKnowledgeButton />} />
      <div className="rv-scroll">
        <div className="rv-page">
          <div className="rv-page__head">
            <div>
              <h1 className="rv-page__title rv-k-title" tabIndex={-1} ref={headingRef}>
                {title}
              </h1>
              {lede ? <p className="rv-page__lede">{lede}</p> : null}
              {meta ? <div className="rv-k-meta">{meta}</div> : null}
            </div>
            {actions ? <div className="rv-k-actions">{actions}</div> : null}
          </div>
          {children}
        </div>
      </div>
    </div>
  )
}
