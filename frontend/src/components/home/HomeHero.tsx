import type { HomeSectionKey } from '../../lib/home'
import { SummaryPill } from '../ui/SummaryPill'

export interface HomeCounts {
  needs: number
  analyzing: number
  queued: number
}

interface HomeHeroProps {
  /** Data has arrived, so counts are safe to show. */
  resolved: boolean
  hasWork: boolean
  counts: HomeCounts
  onReveal: (section: HomeSectionKey) => void
}

/**
 * The title and three counts. A count appears only when it is above zero, and only once the read
 * has settled, so the page never claims work it cannot see.
 */
export function HomeHero({ resolved, hasWork, counts, onReveal }: HomeHeroProps) {
  return (
    <div className="rv-hero">
      <div>
        <h1 className="rv-hero__title">Rivell</h1>
        {/* Claims nothing until the read settles, and never duplicates the empty state. */}
        {resolved && hasWork ? <p className="rv-hero__line">Here is where your reviews stand.</p> : null}
      </div>
      {resolved ? (
        <div className="rv-pills">
          <SummaryPill
            count={counts.needs}
            label={counts.needs === 1 ? 'needs you' : 'need you'}
            tone="attention"
            targetId="needs"
            onReveal={() => onReveal('needs')}
          />
          <SummaryPill count={counts.analyzing} label="analyzing" tone="active" targetId="running" onReveal={() => onReveal('running')} />
          <SummaryPill count={counts.queued} label="queued" tone="neutral" targetId="running" onReveal={() => onReveal('running')} />
        </div>
      ) : null}
    </div>
  )
}
