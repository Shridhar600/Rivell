import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { SourceState } from '../../../lib/knowledgeCatalog'
import type { ArtifactType } from '../../../api/knowledge'
import { BookIcon, ChevronIcon, ClauseIcon, FileIcon } from '../../ui/Icon'

export function TypeIcon({ type }: { type: ArtifactType | 'FILE' }) {
  if (type === 'PLAYBOOK') return <BookIcon className="rv-icon" />
  if (type === 'CLAUSE_LIBRARY') return <ClauseIcon className="rv-icon" />
  return <FileIcon className="rv-icon" />
}

interface LinkRowProps {
  to: string
  type: ArtifactType | 'FILE'
  title: string
  detail: ReactNode
  right?: ReactNode
  /** The accent tile marks work that is not published yet. */
  accent?: boolean
}

/** One canonical list row: tile, title and one line of detail, state on the right, chevron. */
export function KnowledgeLinkRow({ to, type, title, detail, right, accent }: LinkRowProps) {
  return (
    <li>
      <Link to={to} className="rv-k-row">
        <span className={`rv-tile${accent ? ' rv-tile--k' : ''}`}>
          <TypeIcon type={type} />
        </span>
        <span className="rv-k-row__text">
          <b>{title}</b>
          <span>{detail}</span>
        </span>
        <span className="rv-k-row__right">{right}</span>
        <ChevronIcon className="rv-icon" />
      </Link>
    </li>
  )
}

const SOURCE_STATE: Record<SourceState, { label: string; variant: string }> = {
  published: { label: 'Published', variant: ' rv-stb--ok' },
  review: { label: 'Awaiting review', variant: ' rv-stb--am' },
  compiling: { label: 'Reading', variant: '' },
  failed: { label: 'Could not read', variant: ' rv-stb--red' },
  none: { label: 'Not published', variant: '' },
  unreadable: { label: 'Status unavailable', variant: '' },
}

export function SourceStateBadge({ state }: { state: SourceState }) {
  const { label, variant } = SOURCE_STATE[state]
  return <span className={`rv-stb${variant}`}>{label}</span>
}
