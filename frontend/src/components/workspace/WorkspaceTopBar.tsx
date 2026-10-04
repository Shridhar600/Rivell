import { Link } from 'react-router'
import type { HumanReview } from '../../api/workflow'
import { Button } from '../ui/Button'
import { Chip } from '../ui/Status'
import { BackIcon, LockIcon, SearchIcon } from './WorkspaceIcons'
import { PanelLeftIcon, PanelRightIcon } from '../ui/Icon'

export const FROZEN_NOTE_ID = 'rv-ws-frozen-note'

interface Props {
  title: string
  chips: string[]
  humanReview: HumanReview | null
  isFinalized: boolean
  /** One segment per finding needing a decision: resolved, escalated or open. */
  segments: ('d' | 'x' | '')[]
  leftOpen: boolean
  rightOpen: boolean
  onToggleLeft: () => void
  onToggleRight: () => void
  onOpenPalette: () => void
  onFinalize: () => void
}

/**
 * Finalize reads the server's readiness and blockers verbatim. The label never claims more than
 * the server says: unresolved counts and the first blocker are the server's own.
 */
export function WorkspaceTopBar({
  title,
  chips,
  humanReview,
  isFinalized,
  segments,
  leftOpen,
  rightOpen,
  onToggleLeft,
  onToggleRight,
  onOpenPalette,
  onFinalize,
}: Props) {
  const ready = humanReview?.readyToFinalize === true
  const blockers = humanReview?.blockers ?? []
  const label = isFinalized
    ? 'Finalized'
    : ready || !humanReview || humanReview.remainingFindings === 0
      ? 'Finalize review'
      : `Finalize review · ${humanReview.remainingFindings} unresolved`
  const note = isFinalized
    ? 'Approved changes frozen'
    : !humanReview
      ? ''
      : ready
        ? 'Ready to finalize'
        : (blockers[0]?.message ?? 'Not ready to finalize')

  return (
    <header className="rv-ws-top">
      <button
        type="button"
        className="rv-iconbtn"
        aria-label="Toggle Findings list"
        aria-pressed={leftOpen}
        title="Toggle Findings ["
        onClick={onToggleLeft}
      >
        <PanelLeftIcon className="rv-icon" />
      </button>
      <Link className="rv-ws-back" to="/">
        <BackIcon />
        Home
      </Link>
      <span className="rv-ws-sep">/</span>
      <span className="rv-ws-title">{title}</span>
      {chips.map((chip) => (
        <Chip key={chip}>{chip}</Chip>
      ))}
      <span className="rv-ws-sp" />
      <button type="button" className="rv-ws-search" aria-label="Open command menu" onClick={onOpenPalette}>
        <SearchIcon />
        <span>Jump to or run</span>
        <kbd>⌘K</kbd>
      </button>
      {humanReview ? (
        <div className="rv-ws-prog">
          <div className="rv-ws-seg" aria-hidden="true">
            {segments.map((state, index) => (
              <i key={index} className={state} />
            ))}
          </div>
          <span>
            {humanReview.resolvedFindings} of {humanReview.actionableFindings} resolved
          </span>
        </div>
      ) : null}
      <button
        type="button"
        className="rv-iconbtn"
        aria-label="Toggle detail panel"
        aria-pressed={rightOpen}
        title="Toggle detail ]"
        onClick={onToggleRight}
      >
        <PanelRightIcon className="rv-icon" />
      </button>
      {note ? (
        // After Finalize this is the one status that is always on screen, so it is the focus fallback
        // when the detail panel is closed.
        <span className="rv-ws-blocker" id={FROZEN_NOTE_ID} tabIndex={isFinalized ? -1 : undefined}>
          {note}
        </span>
      ) : null}
      <Button
        variant="primary"
        disabled={!ready || isFinalized}
        title={blockers.map((blocker) => blocker.message).join('. ') || undefined}
        onClick={onFinalize}
      >
        <LockIcon />
        {label}
      </Button>
    </header>
  )
}
