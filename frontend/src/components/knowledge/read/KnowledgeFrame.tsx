import type { ReactNode } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { Breadcrumbs, type Crumb } from '../../shell/Breadcrumbs'
import { TopBar } from '../../shell/TopBar'
import { CheckIcon, CloseIcon, PlusIcon } from '../../ui/Icon'
import { IconButton } from '../../ui/Button'
import { useHeadingFocus } from '../../../lib/useHeadingFocus'
import { readPublishedNotice, type PublishedNotice } from '../../../lib/publishedNotice'

export type { Crumb } from '../../shell/Breadcrumbs'

/** Which document the reviewer is adding. A page can offer one type, and the dialog still lets them change it. */
export type AddPreset = 'playbook' | 'clause-library'

const PRESET_LABEL: Record<AddPreset, string> = { playbook: 'Add playbook', 'clause-library': 'Add clause library' }

/**
 * Opens Add knowledge on this same page. The dialog is addressed by `?add=`, so it survives a
 * reload and the page behind it keeps its place. `KnowledgeLayout` renders it.
 */
export function AddKnowledgeButton({ preset }: { preset?: AddPreset }) {
  const [params] = useSearchParams()
  const next = new URLSearchParams(params)
  next.set('add', preset ?? '1')
  return (
    <Link to={{ search: `?${next.toString()}` }} replace className="rv-btn rv-btn--primary">
      <PlusIcon className="rv-icon" />
      {preset ? PRESET_LABEL[preset] : 'Add knowledge'}
    </Link>
  )
}

function publishedMessage(notice: PublishedNotice): string {
  const [one, many] = notice.type === 'CLAUSE_LIBRARY' ? ['clause variant', 'clause variants'] : ['position', 'positions']
  const parts = [`${notice.published} ${notice.published === 1 ? one : many} published`]
  if (notice.corrected > 0) parts.push(`${notice.corrected} corrected by you`)
  if (notice.rejected > 0) parts.push(`${notice.rejected} rejected and left out`)
  return `${parts.join(', ')}. The original source is unchanged and earlier publications remain reproducible.`
}

interface KnowledgeFrameProps {
  crumbs: Crumb[]
  title: string
  lede?: string
  /** Chips and status under the title. */
  meta?: ReactNode
  /** Page-level actions next to the title. The Add action is not one of them: the frame owns it. */
  actions?: ReactNode
  /** The Add action's preset, so the one header action names what this page adds. */
  addPreset?: AddPreset
  /** False on pages whose detail panel is not shown, so the page takes the panel's column too. */
  withInspector?: boolean
  children: ReactNode
}

/** The canonical Knowledge page: breadcrumb top bar, then a scrolling page with a serif title. */
export function KnowledgeFrame({ crumbs, title, lede, meta, actions, addPreset, withInspector = false, children }: KnowledgeFrameProps) {
  const headingRef = useHeadingFocus<HTMLHeadingElement>(title)
  // Said on the page the reviewer lands on right after publishing, until they dismiss it or move on.
  const location = useLocation()
  const navigate = useNavigate()
  const notice = readPublishedNotice(location.state)
  const dismiss = () => void navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: null })
  return (
    <div className={`rv-main${withInspector ? '' : ' rv-main--wide'}`}>
      <TopBar title={<Breadcrumbs crumbs={crumbs} />} actions={<AddKnowledgeButton preset={addPreset} />} />
      <div className="rv-scroll">
        <div className="rv-page">
          {notice ? (
            <p className="rv-k-published" role="status">
              <CheckIcon className="rv-icon" />
              <span>
                <b>Published as publication {notice.publicationNumber}.</b> {publishedMessage(notice)}
              </span>
              <IconButton label="Dismiss" onClick={dismiss}>
                <CloseIcon className="rv-icon" />
              </IconButton>
            </p>
          ) : null}
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
