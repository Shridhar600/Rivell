import { describe, expect, it } from 'vitest'
import { parseReviewList } from '../api/reviews'
import { LIFECYCLE_ROWS, humanWire, reviewWire } from '../test/reviewListFixtures'
import { buildHomeSections, homeReview, WAITING_LONG_MS, waitingText, queuedGuidance, RECENT_RESULTS_LIMIT } from './home'

function parse(...rows: unknown[]) {
  return parseReviewList({ items: rows, truncated: false }).items
}

function sectionOf(key: keyof typeof LIFECYCLE_ROWS) {
  return homeReview(parse(LIFECYCLE_ROWS[key])[0]!)
}

describe('which Home section a Review belongs to', () => {
  it('treats queued and analyzing as running, never as needing the reviewer', () => {
    expect(sectionOf('queued').section).toBe('running')
    expect(sectionOf('analyzing').section).toBe('running')
    expect(sectionOf('queued').stateLabel).toBe('Waiting to start')
    expect(sectionOf('analyzing').stateLabel).toBe('Analyzing')
  })

  it('keeps ANALYSIS_NOT_COMPLETE from making a running Review look like it needs a person', () => {
    // The server lists this blocker on a Review the machine has not finished.
    expect(parse(LIFECYCLE_ROWS.analyzing)[0]!.humanReview?.blockers).toContain('ANALYSIS_NOT_COMPLETE')
    expect(sectionOf('analyzing').section).not.toBe('needs')
  })

  it.each([
    ['analysisFailed', 'Analysis failed', 'danger'],
    ['unresolved', 'In review', 'attention'],
    ['notStarted', 'Ready for review', 'attention'],
    ['escalated', 'Escalated, needs resolution', 'attention'],
    ['ready', 'Ready to finalize', 'attention'],
    ['degraded', 'Analysis incomplete', 'attention'],
    ['redlineFailed', 'Redline failed', 'danger'],
  ] as const)('puts %s in Needs you with the server state "%s"', (key, label, tone) => {
    const view = sectionOf(key)
    expect(view.section).toBe('needs')
    expect(view.stateLabel).toBe(label)
    expect(view.tone).toBe(tone)
  })

  it.each([
    ['finalized', 'Finalized'],
    ['finalizedNoChange', 'Review completed'],
    ['verified', 'Verified redline'],
  ] as const)('puts %s in the finished results', (key, label) => {
    const view = sectionOf(key)
    expect(view.section).toBe('done')
    expect(view.stateLabel).toBe(label)
    expect(view.tone).toBe('complete')
  })

  it('does not compute readiness: a Review is ready only when the server says so', () => {
    const notReady = parse(
      reviewWire('x', 'COMPLETED', 'All decided but not flagged.docx', {
        humanReview: humanWire({ status: 'COMPLETE', resolvedFindings: 4, actionableFindings: 4, remainingFindings: 0, readyToFinalize: false, blockers: [] }),
      }),
    )[0]!
    expect(homeReview(notReady).stateLabel).not.toBe('Ready to finalize')
  })

  it('claims nothing about a Review whose human-review state the server did not send', () => {
    const view = homeReview(parse(reviewWire('p', 'COMPLETED', 'No human review block.docx'))[0]!)
    expect(view.section).toBeNull()
    expect(view.tone).toBe('neutral')
    expect(view.stateLabel).toBe('Analysis complete')
  })

  it('shows an unrecognised status neutrally', () => {
    const view = homeReview(parse(reviewWire('p', 'FUTURE_STATUS', 'Future.docx'))[0]!)
    expect(view.section).toBeNull()
    expect(view.stateLabel).toBe('Status unknown')
  })
})

describe('a waiting review says how long it has waited', () => {
  const createdAt = '2026-10-04T09:00:00Z'
  const at = (minutes: number) => Date.parse(createdAt) + minutes * 60_000

  it('shows the wait in minutes, and adds the retry line only after ten minutes', () => {
    expect(waitingText(createdAt, at(2))).toBe('Waiting 2 min')
    expect(queuedGuidance(createdAt, at(2))).toBe(
      'Waiting 2 min. You can leave this page. Analysis starts on its own.',
    )
    expect(queuedGuidance(createdAt, at(10))).toContain('Taking longer than usual. Rivell keeps retrying.')
    expect(at(10) - Date.parse(createdAt)).toBe(WAITING_LONG_MS)
  })

  it('uses the waiting line for a queued row on Home', () => {
    const row = parse(LIFECYCLE_ROWS.queued)[0]!
    const view = homeReview(row, Date.parse(row.createdAt) + 2 * 60_000)
    expect(view.guidance).toBe('Waiting 2 min. You can leave this page. Analysis starts on its own.')
  })
})

describe('Home sections', () => {
  const all = parse(...Object.values(LIFECYCLE_ROWS))

  it('splits every loaded Review into exactly one place', () => {
    const sections = buildHomeSections(all)
    expect(sections.needs).toHaveLength(7)
    expect(sections.running).toHaveLength(2)
    expect(sections.doneTotal).toBe(3)
    expect(sections.unplaced).toBe(0)
    expect(sections.needs.length + sections.running.length + sections.doneTotal).toBe(all.length)
  })

  it('lists failures first in Needs you', () => {
    const sections = buildHomeSections(all)
    const tones = sections.needs.map((item) => item.tone)
    const lastDanger = tones.lastIndexOf('danger')
    expect(tones.slice(0, lastDanger + 1).every((tone) => tone === 'danger')).toBe(true)
  })

  it('orders finished Reviews by when they finished, newest first', () => {
    const { done } = buildHomeSections(all)
    expect(done.map((item) => item.title)).toEqual([
      'Verified Procurement.docx',
      'Finalized Services.docx',
      'No Change NDA.docx',
    ])
  })

  it('shows only the latest results and counts the rest', () => {
    const many = parse(
      ...Array.from({ length: RECENT_RESULTS_LIMIT + 3 }, (_, index) =>
        reviewWire(`d${index}`, 'COMPLETED', `Done ${index}.docx`, {
          humanReview: LIFECYCLE_ROWS.finalized.humanReview,
          finalization: { finalizedAt: `2026-09-0${index + 1}T10:00:00Z`, approvedChanges: 1 },
        }),
      ),
    )
    const sections = buildHomeSections(many)
    expect(sections.done).toHaveLength(RECENT_RESULTS_LIMIT)
    expect(sections.doneTotal).toBe(RECENT_RESULTS_LIMIT + 3)
  })

  it('counts Reviews it cannot place instead of dropping them silently', () => {
    const sections = buildHomeSections(parse(reviewWire('p', 'COMPLETED', 'Unplaced.docx')))
    expect(sections.unplaced).toBe(1)
    expect(sections.needs).toEqual([])
  })
})

describe('what a Review row links to', () => {
  it('opens the Review overview, which is its front door', () => {
    expect(sectionOf('unresolved').to).toBe('/reviews/con-u1/overview/u1')
  })

  it('never links to Knowledge or to a retired address', () => {
    for (const row of parse(...Object.values(LIFECYCLE_ROWS))) {
      expect(homeReview(row).to).toMatch(/^\/reviews\/[^/]+\/overview\/[^/]+$/)
    }
  })
})

describe('what a Review row claims', () => {
  it('shows real decision progress for a Review in review', () => {
    expect(sectionOf('unresolved').meta).toBe('v1 · 5 of 8 decided')
    expect(sectionOf('unresolved').progress).toBe('5 of 8 decided')
  })

  it('shows no progress for a Review that is still running', () => {
    expect(sectionOf('analyzing').progress).toBeNull()
    expect(sectionOf('analyzing').details.map((detail) => detail.label)).not.toContain('Findings')
  })

  it('reports verified changes only for a verified redline', () => {
    expect(sectionOf('verified').details).toContainEqual({ label: 'Verified changes', value: '7' })
    expect(sectionOf('finalized').details.map((detail) => detail.label)).not.toContain('Verified changes')
  })

  it('uses the finish time for a finished Review and labels it as an update', () => {
    const view = sectionOf('verified')
    expect(view.timeValue).toBe('2026-10-04T08:31:00Z')
    expect(view.timeLabelKind).toBe('updated')
  })

  it('labels a Review that has done nothing yet by when it was added', () => {
    const view = homeReview(parse(reviewWire('p', 'ANALYSIS_QUEUED', 'Waiting.docx'))[0]!)
    expect(view.timeLabelKind).toBe('added')
  })

  it('uses a placeholder for a contract with no filename, never an id', () => {
    const view = homeReview(parse(reviewWire('p', 'ANALYSIS_QUEUED', '', { contractFilename: null }))[0]!)
    expect(view.title).toBe('Untitled contract')
  })
})
