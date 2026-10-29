import { describe, expect, it } from 'vitest'
import { guideFor, PREPARED_RESULT_NOTICE } from './guide'
import type { DemoIds } from './transport'

const ids: DemoIds = {
  c5: { reviewId: 'r5', contractId: 'c5' },
  k2ArtifactId: 'k2',
  orderForm: { reviewId: 'r2', contractId: 'c2' },
  msa: { reviewId: 'r1', contractId: 'c1' },
  vantage: { reviewId: 'r4', contractId: 'c4' },
}

function guide(pathname: string, stage: Parameters<typeof guideFor>[0]['stage'], search = '') {
  return guideFor({ pathname, search, stage, ids })
}

describe('demo guide', () => {
  it('points Home at Knowledge', () => {
    const step = guide('/', 'base')
    expect(step.title).toBe('This is the Bramber workspace')
    expect(step.link).toEqual({ label: 'Go to Knowledge', to: '/knowledge' })
  })

  it('offers Add knowledge on Knowledge', () => {
    const step = guide('/knowledge', 'base')
    expect(step.title).toBe('Add the NDA playbook')
    expect(step.link?.to).toBe('/knowledge?add=playbook')
  })

  it('offers the prepared playbook while the add dialog is open', () => {
    const step = guide('/knowledge', 'base', '?add=playbook')
    expect(step.action).toBe('prepared-file')
    expect(step.link).toBeUndefined()
  })

  it('walks the compilation through approve and publish', () => {
    expect(guide('/knowledge/compilations/k2', 'tour-k2-compiled').title)
      .toBe('Approve the positions')
    expect(guide('/knowledge/compilations/k2', 'tour-k2-approved').title)
      .toBe('Publish the playbook')
  })

  it('sends a published tour to the NDA review', () => {
    expect(guide('/knowledge/playbooks/k2', 'tour-k2-published').link)
      .toEqual({ label: 'Open the NDA review', to: '/reviews/c5/analysis/r5' })
  })

  it('asks for finalize after the decisions and before the redline', () => {
    expect(guide('/reviews/c5/analysis/r5', 'tour-c5-decided').title)
      .toBe('Finalize the review')
    expect(guide('/reviews/c5/analysis/r5', 'tour-c5-finalized').link)
      .toEqual({ label: 'Open the lifecycle', to: '/reviews/c5/overview/r5' })
    expect(guide('/reviews/c5/analysis/r5', 'tour-c5-finalized').note)
      .toBe(PREPARED_RESULT_NOTICE)
  })

  it('gives the lifecycle the redline and download steps', () => {
    expect(guide('/reviews/c5/overview/r5', 'tour-c5-finalized').title)
      .toBe('Produce the redline')
    expect(guide('/reviews/c5/overview/r5', 'tour-c5-done').title)
      .toBe('Download the redline')
    expect(guide('/reviews/c5/overview/r5', 'tour-c5-done').note)
      .toBe(PREPARED_RESULT_NOTICE)
  })

  it('closes the tour away from the NDA', () => {
    expect(guide('/', 'tour-c5-done').title).toBe('That is the tour')
    expect(guide('/', 'tour-c5-done').note).toBe(PREPARED_RESULT_NOTICE)
    expect(guide('/', 'tour-c5-finalized').note).toBe(PREPARED_RESULT_NOTICE)
  })

  it('hints at the other stages when the visitor roams', () => {
    expect(guide('/reviews/c2/analysis/r2', 'base').title)
      .toBe('A finished redline is waiting')
    expect(guide('/reviews/c1/analysis/r1', 'base').title)
      .toBe('Items left for a person')
    expect(guide('/reviews/c4/analysis/r4', 'base').title).toBe('Ready to finalize')
    expect(guide('/reviews/c4/analysis/r4', 'base').text)
      .toBe('Every finding is decided. Finalizing this review is not part of the demo.')
  })
})
