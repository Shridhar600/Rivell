import { dateTime } from '../../lib/dates'
import { resumeRoute, type ResumeRecord } from '../../lib/resume'
import type { HomeWorkItem } from '../../lib/home'
import { Button, LinkButton } from '../ui/Button'
import { ResumeIcon } from '../ui/Icon'
import { Chip, StatusBadge } from '../ui/Status'

interface ContinueCardProps {
  record: ResumeRecord
  /** The same Review as Home just read it, when it is in the bounded list. Source of live state. */
  live: HomeWorkItem | null
  onDismiss: () => void
}

/**
 * Picks the reviewer's place back up. This is device-local: it states what this browser saw, never
 * decision progress or a next step, because the browser never held those. Where Home has also read
 * the Review, its current state is shown beside the saved place.
 */
export function ContinueCard({ record, live, onDismiss }: ContinueCardProps) {
  const title = live?.title ?? record.filename
  const version = live?.chip ?? (record.versionNumber === null ? null : `v${record.versionNumber}`)
  const opened = dateTime(record.visitedAt)
  return (
    <section className="rv-resume" aria-label="Continue where you left off">
      <div className="rv-resume__main">
        <p className="rv-resume__kicker">
          <ResumeIcon className="rv-icon" />
          Continue where you left off
        </p>
        <h2 className="rv-resume__title">
          {title}
          {version ? <Chip>{version}</Chip> : null}
        </h2>
        <p className="rv-resume__facts">
          {live ? <StatusBadge tone={live.tone} label={live.stateLabel} /> : null}
          {live?.meta ? <span>{live.meta}</span> : null}
          <span>
            Last opened on this device{opened ? `, ${opened}` : ''}
          </span>
        </p>
      </div>
      <div className="rv-resume__actions">
        <LinkButton to={resumeRoute(record)} variant="primary">
          Continue review
        </LinkButton>
        <Button onClick={onDismiss} aria-label={`Dismiss ${title} from Continue`}>
          Dismiss
        </Button>
      </div>
    </section>
  )
}
