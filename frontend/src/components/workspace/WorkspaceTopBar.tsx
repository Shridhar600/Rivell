import type { HumanReview } from '../../api/workflow'
import { Breadcrumbs } from '../shell/Breadcrumbs'
import { TopBar } from '../shell/TopBar'
import { Button } from '../ui/Button'
import { Chip } from '../ui/Status'
import { LockIcon } from './WorkspaceIcons'
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
  onFinalize: () => void
}

/**
 * Finalize reads the server's readiness and blockers verbatim. The label never claims more than
 * the server says, and the status is stated once: the progress count, and either a short readiness
 * word or the frozen state. The exact blocker is one disclosure away rather than a clipped line,
 * and the button never repeats the unresolved count.
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
  onFinalize,
}: Props) {
  const ready = humanReview?.readyToFinalize === true
  const blockers = humanReview?.blockers ?? []
  const label = isFinalized ? 'Finalized' : 'Finalize review'
  const status = isFinalized
    ? 'Approved changes frozen'
    : !humanReview
      ? ''
      : ready
        ? 'Ready to finalize'
        : 'Not ready to finalize'

  return (
    <TopBar
      leading={
        <button
          type="button"
          className="rv-iconbtn"
          aria-label="Toggle Findings list"
          aria-pressed={leftOpen}
          title="Show findings ["
          onClick={onToggleLeft}
        >
          <PanelLeftIcon className="rv-icon" />
        </button>
      }
      title={
        <div className="rv-ws-crumbs">
          <Breadcrumbs crumbs={[{ label: 'Reviews', to: '/reviews' }, { label: title }]} />
          {chips.map((chip) => (
            <Chip key={chip}>{chip}</Chip>
          ))}
        </div>
      }
      actions={
        <>
          {humanReview ? (
            <div className="rv-ws-prog" title={status || undefined}>
              <div className="rv-ws-seg" aria-hidden="true">
                {segments.map((state, index) => (
                  <i key={index} className={state} />
                ))}
              </div>
              <span>
                {humanReview.resolvedFindings} of {humanReview.actionableFindings} decided
              </span>
            </div>
          ) : null}
          {isFinalized ? (
            // After Finalize this is the one status that is always on screen, so it is the focus fallback
            // when the detail panel is closed.
            <span className="rv-ws-blocker" id={FROZEN_NOTE_ID} tabIndex={-1}>
              {status}
            </span>
          ) : null}
          <button
            type="button"
            className="rv-iconbtn"
            aria-label="Toggle detail panel"
            aria-pressed={rightOpen}
            title="Show details ]"
            onClick={onToggleRight}
          >
            <PanelRightIcon className="rv-icon" />
          </button>
          {/* A disabled button shows no tooltip, so the reason it cannot be used sits on its wrapper. */}
          <span className="rv-ws-fin" title={isFinalized ? undefined : ready ? 'Freeze your decisions so the redline can be created' : blockers.map((blocker) => blocker.message).join(' ') || 'Decide every open finding first'}>
            <Button variant="primary" disabled={!ready || isFinalized} onClick={onFinalize}>
              <LockIcon />
              {label}
            </Button>
          </span>
        </>
      }
    />
  )
}
