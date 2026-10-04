import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Candidate } from '../../api/knowledge'
import { SRC, publishedWorld, renderApp, requests, reviewWorld, startFakeServer, type Fake } from './compileWorld'

afterEach(() => vi.unstubAllGlobals())

const decisionBar = () => document.querySelector('.rv-kc-decide') as HTMLElement
const rail = () => screen.getByRole('navigation', { name: 'Candidate list' })
const panel = () => screen.getByRole('complementary', { name: 'Candidates' })
const topbar = () => document.querySelector('.rv-kc-top') as HTMLElement
const publishButton = () => within(topbar()).getByRole('button', { name: /Publish|Published|Nothing to publish/ })

async function open(world: Fake, query = '') {
  startFakeServer(world)
  const view = renderApp(`/knowledge/compilations/${SRC}${query}`)
  await screen.findByRole('heading', { level: 1 })
  return { world, ...view }
}

/** Every candidate decided by a person, with the server holding the outcomes. */
function decided(world: Fake, reject: string[] = ['duplicate']): Fake {
  world.candidates[SRC] = (world.candidates[SRC] ?? []).map((entry): Candidate => ({
    ...entry,
    reviewStatus: reject.includes(entry.id) ? 'REJECTED' : 'APPROVED',
  }))
  return world
}

describe('grouped safe review', () => {
  it('holds exactly the candidates the server accepts for group approval', async () => {
    await open(reviewWorld(), '?view=group')
    expect(screen.getByRole('heading', { level: 1, name: '3 routine candidates' })).toBeInTheDocument()
    const main = within(screen.getByRole('main'))
    for (const label of ['Notices', 'Counterparts', 'Survival']) expect(main.getByRole('button', { name: new RegExp(label) })).toBeInTheDocument()
    // Candidates with a condition are never in the group.
    for (const label of ['Assignment', 'Indemnification', 'Subcontracting', 'Confidentiality Term']) {
      expect(main.queryByRole('button', { name: new RegExp(label) })).not.toBeInTheDocument()
    }
  })

  it('does not offer Approve all until every row in the group has been opened', async () => {
    const { world } = await open(reviewWorld(), '?view=group')
    const approveAll = within(decisionBar()).getByRole('button', { name: 'Approve all 3' })
    expect(approveAll).toBeDisabled()
    expect(within(decisionBar()).getByText('Reviewed 0 of 3 routine candidates')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Notices/ }))
    await userEvent.click(screen.getByRole('button', { name: /Counterparts/ }))
    // Two of three: still not available, and a click does nothing.
    expect(within(decisionBar()).getByText('Reviewed 2 of 3 routine candidates')).toBeInTheDocument()
    expect(approveAll).toBeDisabled()
    await userEvent.click(approveAll)
    expect(requests(world, 'POST', /bulk-approve/)).toHaveLength(0)

    await userEvent.click(screen.getByRole('button', { name: /Survival/ }))
    expect(approveAll).toBeEnabled()
  })

  it('opens every row with Review all, which is how the whole group is inspected', async () => {
    await open(reviewWorld(), '?view=group')
    await userEvent.click(screen.getByRole('button', { name: 'Review all' }))
    expect(within(decisionBar()).getByRole('button', { name: 'Approve all 3' })).toBeEnabled()
    expect(screen.getAllByRole('region', { name: 'Source passage' })).toHaveLength(3)
    expect(screen.getAllByText('Reviewed').length).toBeGreaterThanOrEqual(3)
  })

  it('approves exactly the candidates in the group, and reports what the server did', async () => {
    const { world } = await open(reviewWorld(), '?view=group')
    await userEvent.click(screen.getByRole('button', { name: 'Review all' }))
    await userEvent.click(within(decisionBar()).getByRole('button', { name: 'Approve all 3' }))
    await waitFor(() => expect(requests(world, 'POST', /bulk-approve/)).toHaveLength(1))
    expect(requests(world, 'POST', /bulk-approve/)[0]?.body).toEqual({ candidateIds: ['r1', 'r2', 'r3'] })
    // Nothing was approved one by one.
    expect(requests(world, 'POST', /\/approve$/)).toHaveLength(0)
    expect(await screen.findByText(/candidates approved by the server/)).toBeInTheDocument()
    // Re-read from the server: the three now sit under Reviewed, and the group is gone.
    await waitFor(() => expect(within(rail()).queryByRole('button', { name: /straightforward/ })).not.toBeInTheDocument())
    expect(within(rail()).getByRole('heading', { name: /Reviewed/ })).toHaveTextContent('3')
  })

  it('respects the server when it refuses a candidate the screen thought was eligible', async () => {
    const world = reviewWorld()
    const { world: same } = await open(world, '?view=group')
    await userEvent.click(screen.getByRole('button', { name: 'Review all' }))
    // The server's answer changes after the screen last read it: one is no longer routine.
    same.candidates[SRC] = (same.candidates[SRC] ?? []).map((entry) => (entry.id === 'r2' ? { ...entry, reviewCategory: 'ELEVATED' as const } : entry))
    await userEvent.click(within(decisionBar()).getByRole('button', { name: 'Approve all 3' }))
    expect(await screen.findByText('The server did not approve 1:')).toBeInTheDocument()
    expect(screen.getByText(/Counterparts: review-risk category ELEVATED/)).toBeInTheDocument()
    // Counterparts was not approved, and is shown as the server holds it.
    await waitFor(() => expect(within(rail()).getAllByText('Approved', { selector: '.rv-kc-row__sub' })).toHaveLength(2))
    expect(requests(world, 'POST', /\/approve$/)).toHaveLength(0)
  })

  it('has no checkbox selection and no way to approve a few of the rows', async () => {
    await open(reviewWorld(), '?view=group')
    await userEvent.click(screen.getByRole('button', { name: 'Review all' }))
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
    expect(screen.queryByRole('button', { name: /Approve selected|Approve inspected/ })).not.toBeInTheDocument()
  })

  it('takes a candidate out of the group to review it alone', async () => {
    const { world } = await open(reviewWorld(), '?view=group')
    await userEvent.click(screen.getByRole('button', { name: /Counterparts/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Review individually instead' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Counterparts' })).toBeInTheDocument()
    expect(within(rail()).getByRole('button', { name: /2 straightforward candidates/ })).toBeInTheDocument()
    expect(within(rail()).getByRole('button', { name: /Counterparts/ })).toBeInTheDocument()
    expect(requests(world, 'POST', /bulk-approve/)).toHaveLength(0)
  })

  it('does not call a single routine candidate a group', async () => {
    const world = reviewWorld()
    world.candidates[SRC] = (world.candidates[SRC] ?? []).filter((entry) => !['r2', 'r3'].includes(entry.id))
    await open(world)
    expect(within(rail()).queryByRole('heading', { name: /Grouped review/ })).not.toBeInTheDocument()
    expect(within(rail()).getByRole('button', { name: /Notices/ })).toBeInTheDocument()
  })
})

describe('publish readiness and confirmation', () => {
  it('is not available while anything awaits a decision, and says how many', async () => {
    const { world } = await open(reviewWorld())
    expect(publishButton()).toBeDisabled()
    expect(publishButton()).toHaveTextContent('Publish (7 to review)')
    expect(screen.getByText('0 of 7 reviewed')).toBeInTheDocument()
    expect(within(panel()).getByText('Compilation review')).toBeInTheDocument()
    await userEvent.click(publishButton())
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(requests(world, 'POST', /publications/)).toHaveLength(0)
  })

  it('is ready only when the server holds a human outcome for every candidate', async () => {
    await open(decided(reviewWorld()))
    expect(publishButton()).toBeEnabled()
    expect(publishButton()).toHaveTextContent('Publish playbook')
    expect(await screen.findByText('Compilation review complete')).toBeInTheDocument()
    expect(screen.getByText('7 of 7 reviewed')).toBeInTheDocument()
    expect(within(panel()).getByText('Ready to publish')).toBeInTheDocument()
  })

  it('never offers to publish when every candidate was rejected', async () => {
    const world = decided(reviewWorld(), ['conflict', 'uncertain', 'duplicate', 'supersede', 'r1', 'r2', 'r3'])
    await open(world)
    expect(publishButton()).toBeDisabled()
    expect(publishButton()).toHaveTextContent('Nothing to publish')
    expect(publishButton()).toHaveAttribute('title', 'Every candidate was rejected, so there is nothing to publish.')
  })

  it('asks for explicit confirmation and explains what publishing does', async () => {
    const { world } = await open(decided(reviewWorld()))
    await userEvent.click(publishButton())
    const dialog = await screen.findByRole('dialog', { name: 'Publish this playbook?' })
    expect(dialog).toHaveTextContent(/new, numbered, immutable organizational publication/)
    expect(dialog).toHaveTextContent(/cannot be edited/)
    expect(within(dialog).getByText('6 reviewed candidates enter the new publication')).toBeInTheDocument()
    expect(within(dialog).getByText('1 rejected candidate stays out of published knowledge')).toBeInTheDocument()
    expect(within(dialog).getByText('Earlier publications remain reproducible')).toBeInTheDocument()
    expect(within(dialog).getByText('The uploaded original stays unchanged')).toBeInTheDocument()
    // Opening the dialog publishes nothing.
    expect(requests(world, 'POST', /publications/)).toHaveLength(0)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Keep reviewing' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(requests(world, 'POST', /publications/)).toHaveLength(0)
  })

  it('publishes the approved candidates, after reading them from the server again', async () => {
    const { world } = await open(decided(reviewWorld()))
    await userEvent.click(publishButton())
    const dialog = await screen.findByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Publication note, optional'), 'Q4 playbook')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(requests(world, 'POST', /publications/)).toHaveLength(1))
    const body = requests(world, 'POST', /publications/)[0]?.body as { candidateIds: string[]; note: string; supersededCandidates?: unknown }
    expect([...body.candidateIds].sort()).toEqual(['conflict', 'r1', 'r2', 'r3', 'supersede', 'uncertain'])
    expect(body.candidateIds).not.toContain('duplicate')
    expect(body.note).toBe('Q4 playbook')
    expect(body.supersededCandidates).toBeUndefined()
    // The publish request was preceded by a fresh read of the candidates.
    const publishAt = world.calls.findIndex((call) => call.method === 'POST' && /publications/.test(call.path))
    const lastReadBefore = world.calls.slice(0, publishAt).filter((call) => call.method === 'GET' && call.path.endsWith(`/artifacts/${SRC}/candidates`))
    expect(lastReadBefore.length).toBeGreaterThan(1)
  })

  it('does not publish when the server no longer holds everything as approved', async () => {
    const world = decided(reviewWorld())
    await open(world)
    await userEvent.click(publishButton())
    const dialog = await screen.findByRole('dialog')
    // Someone else sends a candidate back between the screen's last read and the click.
    world.candidates[SRC] = (world.candidates[SRC] ?? []).map((entry) => (entry.id === 'r1' ? { ...entry, reviewStatus: 'EDITED' as const } : entry))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/This compilation changed\. 1 candidate is still to review\./)
    expect(requests(world, 'POST', /publications/)).toHaveLength(0)
    // The screen reads the server again and now says so itself.
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Publish' })).toBeDisabled())
  })

  it('shows what the server refused when it refuses the publication', async () => {
    const world = decided(reviewWorld())
    world.fail['POST publish'] = 409
    await open(world)
    await userEvent.click(publishButton())
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/Only candidates that have been approved can be published/)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(world.publications).toHaveLength(1)
  })

  it('replaces published knowledge only when the reviewer chose that, and says so', async () => {
    const world = reviewWorld()
    const { world: same, client } = await open(world, '?candidate=supersede')
    const choices = screen.getByRole('group', { name: 'When I publish this' })
    await userEvent.click(await within(choices).findByRole('radio', { name: /Replace Published Liability Cap/ }))
    // The rest is decided elsewhere, and this screen reads the server's answer.
    decided(same, ['duplicate'])
    await client.invalidateQueries()
    await waitFor(() => expect(publishButton()).toBeEnabled())
    await userEvent.click(publishButton())
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/Replaces earlier published knowledge/)).toHaveTextContent('Published Liability Cap')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(requests(world, 'POST', /publications/)).toHaveLength(1))
    const sent = requests(world, 'POST', /publications/)[0]
    expect(sent?.body).toMatchObject({ supersededCandidates: { supersede: 'peer1' } })
  })

  it('says plainly when a possible replacement was not chosen', async () => {
    await open(decided(reviewWorld()))
    await userEvent.click(publishButton())
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/may replace published knowledge, and you did not choose that/)).toBeInTheDocument()
    expect(within(dialog).queryByText(/Replaces earlier published knowledge/)).not.toBeInTheDocument()
  })
})

describe('after publishing', () => {
  async function publish(world: Fake) {
    const view = await open(world)
    await userEvent.click(publishButton())
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    return view
  }

  it('takes the reviewer to the published playbook, with no reload, and says what happened', async () => {
    const { router } = await publish(decided(reviewWorld()))
    await screen.findByRole('heading', { level: 1, name: 'Commercial MSA Playbook v4' })
    expect(router.state.location.pathname).toBe(`/knowledge/playbooks/${SRC}`)
    const notice = (await screen.findByText('Published as publication 2.')).closest('p') as HTMLElement
    expect(notice).toHaveAttribute('role', 'status')
    expect(notice).toHaveTextContent('6 positions published, 1 rejected and left out')
    expect(notice).toHaveTextContent(/original source is unchanged and earlier publications remain reproducible/)
    // The read model shows the new publication at once, from the server's own records.
    expect(await screen.findByText('Publication 2')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Positions (6)' })).toBeInTheDocument()
  })

  it('takes a clause library to the clause library', async () => {
    const world = decided(reviewWorld())
    world.artifacts = [{ ...world.artifacts[0]!, artifactType: 'CLAUSE_LIBRARY', filename: 'Clauses.xlsx' }]
    world.candidates[SRC] = (world.candidates[SRC] ?? []).map((entry) => ({ ...entry, candidateType: 'CLAUSE_VARIANT' as const, positions: [], variantRole: 'PREFERRED' as const }))
    const { router } = await publish(world)
    await screen.findByRole('heading', { level: 1, name: 'Clause library' })
    expect(router.state.location.pathname).toBe('/knowledge/clauses')
    // The page frame is replaced once the published read model arrives, so wait for its content first.
    await screen.findByRole('tab', { name: /Clauses \(/ })
    expect(screen.getByText(/Published as publication 2\./)).toBeInTheDocument()
  })

  it('does not show the notice again once it is dismissed', async () => {
    await publish(decided(reviewWorld()))
    await screen.findByRole('tab', { name: /Positions \(/ })
    await userEvent.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByText(/Published as publication/)).not.toBeInTheDocument()
  })
})

describe('a published compilation', () => {
  it('shows an immutable record derived from the server, with no way back to editing', async () => {
    await open(publishedWorld())
    const record = await screen.findByRole('heading', { level: 1, name: 'Commercial MSA Playbook v4' })
    expect(record).toBeInTheDocument()
    expect(screen.getByText('Published, publication 2')).toBeInTheDocument()
    expect(screen.getByText('Published candidates').nextSibling).toHaveTextContent('6')
    expect(screen.getByText('Rejected candidates').nextSibling).toHaveTextContent('1')
    expect(screen.getByRole('link', { name: 'Open published playbook' })).toHaveAttribute('href', `/knowledge/playbooks/${SRC}`)
    expect(screen.getByRole('link', { name: 'View retained source' })).toHaveAttribute('href', `/knowledge/sources/${SRC}`)
    // The prototype's "Back to compilation" would have reopened a published snapshot as editable.
    expect(screen.queryByRole('button', { name: /Back to compilation|Reopen|Edit/ })).not.toBeInTheDocument()
    expect(publishButton()).toBeDisabled()
    expect(publishButton()).toHaveTextContent('Published')
    expect(within(panel()).getByText('Published, immutable')).toBeInTheDocument()
  })

  it('shows a published candidate read-only, and offers no decision', async () => {
    const world = publishedWorld()
    const target = world.candidates[SRC]?.[1]?.id ?? ''
    await open(world, `?candidate=${target}`)
    expect(screen.getByText(/Published in publication 2\. Published knowledge is immutable\./)).toBeInTheDocument()
    expect(within(decisionBar()).queryByRole('button', { name: /Approve|Reject|Edit/ })).not.toBeInTheDocument()
    expect(within(decisionBar()).getByRole('link', { name: 'Open in the source' })).toHaveAttribute('href', expect.stringContaining(`/knowledge/sources/${SRC}?position=`))
  })

  it('closes an open correction form when the server reports the candidate as published', async () => {
    const world = reviewWorld()
    const { world: same, client } = await open(world, '?candidate=uncertain')
    await userEvent.click(within(decisionBar()).getByRole('button', { name: /Edit/ }))
    expect(await screen.findByRole('form', { name: 'Correct the structured candidate' })).toBeInTheDocument()
    same.candidates[SRC] = (same.candidates[SRC] ?? []).map((entry) => (entry.id === 'uncertain' ? { ...entry, reviewStatus: 'PUBLISHED' as const } : entry))
    await client.invalidateQueries()
    await waitFor(() => expect(screen.queryByRole('form', { name: 'Correct the structured candidate' })).not.toBeInTheDocument())
    expect(screen.getByText(/Published knowledge is immutable/)).toBeInTheDocument()
    expect(within(decisionBar()).queryByRole('button', { name: /Save correction|Approve|Reject/ })).not.toBeInTheDocument()
  })
})
