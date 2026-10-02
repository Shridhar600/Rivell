import { describe, expect, it } from 'vitest'
import {
  HOME_GROUP_LABELS,
  buildHomeGroups,
  knowledgeItemView,
  mapIngestStatus,
  mapReviewStatus,
  publicationItemView,
  reviewItemView,
  type HomeWorkItem,
} from './home'

const AT = '2026-10-01T09:00:00Z'

describe('mapIngestStatus', () => {
  it('maps every real knowledge ingest status to a truthful group and label', () => {
    expect(mapIngestStatus('RECEIVED')).toEqual({ group: 'running', label: 'Queued for compilation', tone: 'active' })
    expect(mapIngestStatus('EXTRACTING')).toEqual({ group: 'running', label: 'Compiling', tone: 'active' })
    expect(mapIngestStatus('EXTRACTED')).toEqual({ group: 'needs_action', label: 'Ready for review', tone: 'attention' })
    expect(mapIngestStatus('FAILED')).toEqual({ group: 'failed', label: 'Ingestion failed', tone: 'danger' })
  })

  it('maps an unrecognised code to a neutral unknown state, never to failure or success', () => {
    expect(mapIngestStatus('SOMETHING_NEW')).toEqual({ group: 'needs_action', label: 'Status unknown', tone: 'neutral' })
    expect(mapIngestStatus(null)).toEqual({ group: 'needs_action', label: 'Status unknown', tone: 'neutral' })
  })
})

describe('mapReviewStatus', () => {
  it('maps every real review status to a truthful group and label', () => {
    expect(mapReviewStatus('ANALYSIS_QUEUED')).toEqual({ group: 'running', label: 'Analysis queued', tone: 'active' })
    expect(mapReviewStatus('ANALYZING')).toEqual({ group: 'running', label: 'Analyzing', tone: 'active' })
    expect(mapReviewStatus('COMPLETED')).toEqual({ group: 'needs_action', label: 'Ready for review', tone: 'attention' })
    expect(mapReviewStatus('FAILED')).toEqual({ group: 'failed', label: 'Analysis failed', tone: 'danger' })
  })

  it('maps an unrecognised code to a neutral unknown state', () => {
    expect(mapReviewStatus('NEW_SERVER_STATE')).toEqual({ group: 'needs_action', label: 'Status unknown', tone: 'neutral' })
    expect(mapReviewStatus(undefined)).toEqual({ group: 'needs_action', label: 'Status unknown', tone: 'neutral' })
  })

  it('never claims retryability or timing', () => {
    const labels = ['ANALYSIS_QUEUED', 'ANALYZING', 'COMPLETED', 'FAILED', 'NEW_SERVER_STATE']
      .map((s) => mapReviewStatus(s).label)
      .join(' ')
    expect(labels).not.toMatch(/retry|again|minute|hour|wait/i)
  })
})

describe('knowledgeItemView', () => {
  const artifact = {
    id: 'art-1',
    artifactType: 'PLAYBOOK' as const,
    filename: 'msa-playbook.docx',
    contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    sizeBytes: 1024,
    sha256: 'abc',
    ingestStatus: 'EXTRACTED' as const,
    createdAt: AT,
  }

  it('produces a review work item with a real title, chip and destination', () => {
    const item = knowledgeItemView(artifact)
    expect(item.kind).toBe('knowledge_artifact')
    expect(item.title).toBe('msa-playbook.docx')
    expect(item.chip).toBe('Playbook')
    expect(item.to).toBe('/knowledge/artifacts/art-1')
    expect(item.group).toBe('needs_action')
  })

  it('labels a clause library artifact without inventing a contract type', () => {
    expect(knowledgeItemView({ ...artifact, artifactType: 'CLAUSE_LIBRARY' }).chip).toBe('Clause library')
  })

  it('falls back to a neutral chip for an unknown artifact type', () => {
    expect(knowledgeItemView({ ...artifact, artifactType: 'MYSTERY' as never }).chip).toBe('Knowledge source')
  })
})

describe('publicationItemView', () => {
  it('produces a completed work item labelled Published', () => {
    const item = publicationItemView({
      id: 'pub-1',
      organizationId: 'org-1',
      publicationNumber: 12,
      note: 'Q3 policy refresh',
      items: [],
      createdAt: AT,
    })
    expect(item.kind).toBe('knowledge_publication')
    expect(item.title).toBe('Knowledge publication 12')
    expect(item.group).toBe('recently_completed')
    expect(item.stateLabel).toBe('Published')
    expect(item.to).toBe('/knowledge/publications/pub-1')
  })
})

describe('reviewItemView', () => {
  it('renders only the facts the server supplied', () => {
    const item = reviewItemView({
      id: 'rev-1',
      contractId: 'con-1',
      contractTitle: 'Acme MSA',
      contractVersionNumber: 2,
      status: 'COMPLETED',
      createdAt: AT,
      completedAt: null,
    })
    expect(item.kind).toBe('review')
    expect(item.title).toBe('Acme MSA')
    expect(item.chip).toBe('v2')
    expect(item.to).toBe('/reviews/con-1/analysis/rev-1')
    expect(item.decisionProgress).toBeNull()
  })

  it('shows an honest placeholder for a contract with no title, without leaking an id', () => {
    const item = reviewItemView({
      id: 'rev-2',
      contractId: 'con-2',
      contractTitle: null,
      contractVersionNumber: 1,
      status: 'ANALYZING',
      createdAt: AT,
      completedAt: null,
    })
    expect(item.title).toBe('Untitled contract')
    expect(item.title).not.toContain('con-2')
  })

  it('never invents decision counts', () => {
    const item = reviewItemView({
      id: 'rev-3',
      contractId: 'con-3',
      contractTitle: 'X',
      contractVersionNumber: 1,
      status: 'COMPLETED',
      createdAt: AT,
      completedAt: null,
      decisionProgress: null,
    })
    expect(item.decisionProgress).toBeNull()
  })
})

describe('buildHomeGroups', () => {
  const items: HomeWorkItem[] = [
    knowledgeItemView({ id: 'a1', artifactType: 'PLAYBOOK', filename: 'done.docx', contentType: '', sizeBytes: 1, sha256: '', ingestStatus: 'EXTRACTED', createdAt: AT }),
    knowledgeItemView({ id: 'a2', artifactType: 'PLAYBOOK', filename: 'busy.docx', contentType: '', sizeBytes: 1, sha256: '', ingestStatus: 'EXTRACTING', createdAt: AT }),
    knowledgeItemView({ id: 'a3', artifactType: 'PLAYBOOK', filename: 'bad.docx', contentType: '', sizeBytes: 1, sha256: '', ingestStatus: 'FAILED', createdAt: AT }),
    publicationItemView({ id: 'p1', organizationId: 'o', publicationNumber: 1, note: '', items: [], createdAt: AT }),
  ]

  it('buckets every item into exactly one real group', () => {
    const groups = buildHomeGroups(items)
    const total = groups.needsAction.length + groups.running.length + groups.failed.length + groups.recentlyCompleted.length
    expect(total).toBe(items.length)
  })

  it('sorts failed work first so it is never hidden', () => {
    const groups = buildHomeGroups(items)
    expect(groups.failed.map((i) => i.title)).toEqual(['bad.docx'])
    expect(groups.needsAction.map((i) => i.title)).toEqual(['done.docx'])
    expect(groups.running.map((i) => i.title)).toEqual(['busy.docx'])
    expect(groups.recentlyCompleted.map((i) => i.title)).toEqual(['Knowledge publication 1'])
  })

  it('produces empty arrays rather than undefined for an empty input', () => {
    const groups = buildHomeGroups([])
    expect(groups).toEqual({ needsAction: [], running: [], failed: [], recentlyCompleted: [] })
  })

  it('counts each bucket independently for the summary line', () => {
    const groups = buildHomeGroups(items)
    expect(counts(groups)).toEqual({ needsAction: 1, running: 1, failed: 1, recentlyCompleted: 1 })
  })
})

describe('group labels', () => {
  it('uses Needs action, never an ownership phrase', () => {
    expect(HOME_GROUP_LABELS.needs_action).toBe('Needs action')
    expect(HOME_GROUP_LABELS.needs_action).not.toMatch(/your|assigned|owner|assigned to/i)
  })

  it('has a label for every group the mapper can produce', () => {
    for (const g of ['needs_action', 'running', 'failed', 'recently_completed'] as const) {
      expect(HOME_GROUP_LABELS[g]).toBeTruthy()
    }
  })
})

function counts(g: ReturnType<typeof buildHomeGroups>) {
  return {
    needsAction: g.needsAction.length,
    running: g.running.length,
    failed: g.failed.length,
    recentlyCompleted: g.recentlyCompleted.length,
  }
}
describe('WorkRow time label', () => {
  it('uses a real calendar date, never a relative guess', () => {
    const item = knowledgeItemView({
      id: 'a1', artifactType: 'PLAYBOOK', filename: 'x.docx', contentType: '', sizeBytes: 1,
      sha256: '', ingestStatus: 'EXTRACTED', createdAt: '2026-10-02T08:40:00Z',
    })
    expect(item.timeLabel).toMatch(/[A-Za-z]{3,}/)
    expect(item.timeLabel).not.toMatch(/ago|now|just now|\d+\s*(m|h|d)\b/i)
  })

  it('degrades to empty rather than printing an invalid timestamp', () => {
    const item = publicationItemView({
      id: 'p1', organizationId: 'o', publicationNumber: 1, note: '', items: [], createdAt: 'not-a-date',
    })
    expect(item.timeLabel).toBe('')
  })
})
