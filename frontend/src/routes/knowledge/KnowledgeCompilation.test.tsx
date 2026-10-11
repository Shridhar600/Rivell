import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SRC, renderApp, reviewWorld, requests, startFakeServer, type Fake } from './compileWorld'

afterEach(() => vi.unstubAllGlobals())

async function open(world: Fake = reviewWorld(), query = '') {
  startFakeServer(world)
  const view = renderApp(`/knowledge/compilations/${SRC}${query}`)
  await screen.findByRole('heading', { level: 1 })
  return { world, ...view }
}

const rail = () => screen.getByRole('navigation', { name: 'Candidate list' })
const heading = () => screen.getByRole('heading', { level: 1 })
const decisionBar = () => document.querySelector('.rv-kc-decide') as HTMLElement

describe('compilation workspace', () => {
  it('opens on what needs attention first, with the source beside what Rivell made of it', async () => {
    await open()
    expect(heading()).toHaveTextContent('Assignment')
    const source = screen.getByRole('region', { name: 'Source passage' })
    expect(within(source).getByText('Assignment is allowed with consent.')).toBeInTheDocument()
    const interpretation = screen.getByRole('region', { name: 'Structured interpretation' })
    expect(within(interpretation).getByText('Limitation of liability')).toBeInTheDocument()
    expect(screen.getByText('Candidate 1 of 7')).toBeInTheDocument()
  })

  it('groups the list the way a reviewer works', async () => {
    await open()
    const list = rail()
    expect(within(list).getByRole('heading', { name: /Needs attention/ })).toHaveTextContent('4')
    expect(within(list).getByRole('heading', { name: /Grouped review/ })).toBeInTheDocument()
    expect(within(list).getByRole('button', { name: /3 straightforward candidates/ })).toBeInTheDocument()
    // The routine candidates are only in the group, not listed one by one.
    expect(within(list).queryByRole('button', { name: /Notices/ })).not.toBeInTheDocument()
  })

  it('keeps conditions apart from the review outcome', async () => {
    await open()
    const list = rail()
    const duplicate = within(list).getByRole('button', { name: /Subcontracting/ })
    // Why it needs attention.
    expect(duplicate).toHaveTextContent('Possible duplicate')
    // What a human decided: nothing yet.
    expect(duplicate).toHaveTextContent('To review')
    await userEvent.click(duplicate)
    await screen.findByRole('heading', { level: 1, name: 'Subcontracting' })
    const bar = decisionBar()
    expect(within(bar).getByText(/Does this match the playbook source/)).toBeInTheDocument()
    // A duplicate is a reason to look. The app says it is not rejected for the reviewer.
    expect(screen.getByText(/not rejected for you/i)).toBeInTheDocument()
  })

  it('shows a condition callout only while the candidate is open', async () => {
    const { world } = await open()
    await userEvent.click(within(rail()).getByRole('button', { name: /Subcontracting/ }))
    await screen.findByRole('group', { name: 'Possible duplicate' })
    await userEvent.click(within(decisionBar()).getByRole('button', { name: /Approve/ }))
    await waitFor(() => expect(requests(world, 'POST', /\/approve$/)).toHaveLength(1))
    await userEvent.click(await within(rail()).findByRole('button', { name: /Subcontracting/ }))
    expect(screen.queryByRole('group', { name: 'Possible duplicate' })).not.toBeInTheDocument()
  })

  it('shows a source passage from the extraction unit, with no page number', async () => {
    await open()
    const source = screen.getByRole('region', { name: 'Source passage' })
    // The heading node carries the numbering label the extraction recorded.
    expect(within(source).getByText('1.1')).toBeInTheDocument()
    expect(within(source).getByText('Assignment')).toBeInTheDocument()
    expect(source.textContent).not.toMatch(/page \d/i)
    expect(document.body.textContent).not.toMatch(/page \d/i)
    expect(within(source).getByText('Original retained')).toBeInTheDocument()
  })

  it('opens the source in context and returns to the candidate', async () => {
    await open()
    await userEvent.click(screen.getByRole('button', { name: 'View source context' }))
    const sheet = await screen.findByRole('dialog', { name: 'Assignment' })
    expect(within(sheet).getByText('Source and provenance')).toBeInTheDocument()
    expect(within(sheet).getByText('Original retained unchanged')).toBeInTheDocument()
    // Neighbouring source is shown, and the candidate's own passage is the marked one.
    expect(within(sheet).getByText('Subcontracting')).toBeInTheDocument()
    expect(within(sheet).getByText('Assignment is allowed with consent.').closest('mark')).not.toBeNull()
    expect(within(sheet).getByText(/does not record page numbers or sub-paragraph spans/)).toBeInTheDocument()
    expect(within(sheet).getByText(/Extraction unit/)).toBeInTheDocument()

    await userEvent.click(within(sheet).getByRole('button', { name: 'Back to the candidate' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(heading()).toHaveTextContent('Assignment')
  })

  it('labels every candidate with the record kind its unit was compiled as', async () => {
    await open()
    const list = rail()
    expect(within(list).getByRole('button', { name: /Assignment/ })).toHaveTextContent('Position')
    expect(within(list).getByRole('button', { name: /Subcontracting/ })).toHaveTextContent('Position')
  })

  it('keeps a unit that became no candidate visible, secondary, with its record kind', async () => {
    await open()
    const list = rail()
    const title = within(list).getByText('How to use this playbook')
    const row = title.closest('.rv-kc-unit') as HTMLElement
    expect(row).not.toBeNull()
    expect(row).toHaveTextContent('Guidance')
    expect(row).toHaveTextContent('Heading section')
    // It is reference material, not a candidate: no control to select or decide it.
    expect(within(row).queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows no record kind for data compiled before record kinds existed', async () => {
    const world = reviewWorld()
    world.units[SRC] = (world.units[SRC] ?? []).map((entry) => ({ ...entry, recordKind: null }))
    await open(world)
    const list = rail()
    expect(within(list).getByRole('button', { name: /Assignment/ })).not.toHaveTextContent('Position')
    expect(within(list).queryByText('How to use this playbook')).not.toBeInTheDocument()
  })

  it('does not copy the prototype: no search, identity, or sample files', async () => {
    await open()
    expect(screen.queryByText(/Jump to or run/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Use a sample file/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Priya|Northgate|Signed-in/)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Download/ })).not.toBeInTheDocument()
  })
})

describe('approve and reject', () => {
  it('approves, then re-reads the candidate from the server and moves on', async () => {
    const { world } = await open(reviewWorld(), '?candidate=uncertain')
    expect(heading()).toHaveTextContent('Indemnification')
    await userEvent.click(within(decisionBar()).getByRole('button', { name: /Approve/ }))
    await waitFor(() => expect(requests(world, 'POST', /\/candidates\/uncertain\/approve$/)).toHaveLength(1))
    // The rail now shows the outcome the server returned.
    const reviewed = await within(rail()).findByRole('heading', { name: /Reviewed/ })
    expect(reviewed).toHaveTextContent('1')
    expect(within(rail()).getByRole('button', { name: /Indemnification/ })).toHaveTextContent('Approved')
    // The candidates were read again after the change.
    const reads = world.calls.filter((call) => call.method === 'GET' && call.path.endsWith(`/artifacts/${SRC}/candidates`))
    expect(reads.length).toBeGreaterThan(1)
    // And the reviewer is taken to the next thing that needs attention.
    await waitFor(() => expect(heading()).not.toHaveTextContent('Indemnification'))
  })

  it('rejects without publishing anything, and keeps the candidate in the list as rejected', async () => {
    const { world } = await open(reviewWorld(), '?candidate=uncertain')
    await userEvent.click(within(decisionBar()).getByRole('button', { name: /Reject/ }))
    await waitFor(() => expect(requests(world, 'POST', /\/reject$/)).toHaveLength(1))
    expect(requests(world, 'POST', /publications/)).toHaveLength(0)
    await userEvent.click(await within(rail()).findByRole('button', { name: /Indemnification/ }))
    expect(screen.getByText(/Rejected. A rejected candidate can be brought back only by correcting it/)).toBeInTheDocument()
    // The server refuses to approve a rejected candidate, so the screen does not offer it.
    expect(within(decisionBar()).getByRole('button', { name: /Approve/ })).toBeDisabled()
    expect(within(decisionBar()).getByRole('button', { name: /Edit/ })).toBeEnabled()
  })

  it('shows what the server refused, and reads the candidate again', async () => {
    const world = reviewWorld()
    world.fail['POST approve uncertain'] = 409
    await open(world, '?candidate=uncertain')
    const before = world.calls.filter((call) => call.path.endsWith('/candidates') && call.method === 'GET').length
    await userEvent.click(within(decisionBar()).getByRole('button', { name: /Approve/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/does not apply in the current state/i)
    await waitFor(() => expect(world.calls.filter((call) => call.path.endsWith('/candidates') && call.method === 'GET').length).toBeGreaterThan(before))
    // Nothing changed locally.
    expect(within(rail()).getByRole('button', { name: /Indemnification/ })).toHaveTextContent('To review')
  })

  it('works from the keyboard on the open candidate, from anywhere inside the workspace', async () => {
    const { world } = await open(reviewWorld(), '?candidate=uncertain')
    within(rail()).getByRole('button', { name: /Subcontracting/ }).focus()
    await userEvent.keyboard('r')
    await waitFor(() => expect(requests(world, 'POST', /\/candidates\/uncertain\/reject$/)).toHaveLength(1))
  })

  it('ignores the shortcuts while typing', async () => {
    const { world } = await open(reviewWorld(), '?candidate=uncertain')
    await userEvent.click(within(decisionBar()).getByRole('button', { name: /Edit/ }))
    const wording = await screen.findByLabelText('Wording')
    await userEvent.click(wording)
    await userEvent.keyboard('arj')
    expect(wording).toHaveValue('Mutual and capped.arj')
    expect(requests(world, 'POST', /\/(approve|reject)$/)).toHaveLength(0)
  })
})

describe('conflict', () => {
  it('does not let one click make a possible conflict publishable', async () => {
    const { world } = await open()
    expect(heading()).toHaveTextContent('Assignment')
    const approve = within(decisionBar()).getByRole('button', { name: /Approve/ })
    expect(approve).toBeDisabled()
    expect(screen.getByText(/does not block approving a possible conflict/i)).toBeInTheDocument()
    await userEvent.click(approve)
    expect(requests(world, 'POST', /\/approve$/)).toHaveLength(0)

    await userEvent.click(screen.getByRole('checkbox', { name: /I compared this with the published knowledge/ }))
    expect(approve).toBeEnabled()
    await userEvent.click(approve)
    await waitFor(() => expect(requests(world, 'POST', /\/candidates\/conflict\/approve$/)).toHaveLength(1))
  })

  it('compares with the published knowledge using the real id the server gave', async () => {
    const { world } = await open()
    const section = screen.getByRole('region', { name: 'Published knowledge this was compared with' })
    expect(await within(section).findByText('Published Liability Cap')).toBeInTheDocument()
    await userEvent.click(within(section).getByRole('button', { name: 'Compare' }))
    expect(await within(section).findByText('A published cap at twelve months.')).toBeInTheDocument()
    expect(world.calls.some((call) => call.method === 'GET' && call.path === '/api/knowledge/candidates/peer1')).toBe(true)
  })

  it('clears the conflict only when the server says so, after a legal concept correction', async () => {
    const { world } = await open()
    await userEvent.click(within(decisionBar()).getByRole('button', { name: /Edit/ }))
    const form = await screen.findByRole('form', { name: 'Correct the structured candidate' })
    await userEvent.selectOptions(within(form).getByLabelText('Legal concept'), 'GOVERNING_LAW')
    // Nothing is cleared by choosing a value.
    expect(screen.getByRole('group', { name: 'Conflicts with published knowledge' })).toBeInTheDocument()
    await userEvent.click(within(decisionBar()).getByRole('button', { name: 'Save correction' }))

    await waitFor(() => expect(requests(world, 'POST', /\/candidates\/conflict\/concept$/)).toHaveLength(1))
    expect(requests(world, 'POST', /\/candidates\/conflict\/concept$/)[0]?.body).toEqual({ primary: 'GOVERNING_LAW' })
    // The server answered with no conflict, so now the condition and the gate are gone.
    await waitFor(() => expect(screen.queryByRole('group', { name: 'Conflicts with published knowledge' })).not.toBeInTheDocument())
    expect(screen.queryByText(/does not block approving a possible conflict/i)).not.toBeInTheDocument()
    expect(await screen.findByText('Corrected by you')).toBeInTheDocument()
    expect(screen.getByText('Rivell classified: Limitation of liability')).toBeInTheDocument()
  })

  it('keeps the conflict if the server still reports it after an edit', async () => {
    await open()
    await userEvent.click(within(decisionBar()).getByRole('button', { name: /Edit/ }))
    const form = await screen.findByRole('form', { name: 'Correct the structured candidate' })
    await userEvent.clear(within(form).getByLabelText('Wording'))
    await userEvent.type(within(form).getByLabelText('Wording'), 'Assignment needs written consent.')
    await userEvent.click(within(decisionBar()).getByRole('button', { name: 'Save correction' }))
    await screen.findByText('Corrected by you')
    // Editing wording does not re-run conflict analysis, so the server's conflict stands, and so does the gate.
    expect(screen.getByRole('group', { name: 'Conflicts with published knowledge' })).toBeInTheDocument()
    expect(within(decisionBar()).getByRole('button', { name: /Approve correction/ })).toBeDisabled()
  })
})

describe('supersession and duplicates', () => {
  it('offers a replacement as a choice, and replaces nothing by default', async () => {
    await open(reviewWorld(), '?candidate=supersede')
    const callout = screen.getByRole('group', { name: 'Supersession proposed' })
    expect(callout).toHaveTextContent(/Nothing is replaced unless you choose it/)
    const choices = screen.getByRole('group', { name: 'When I publish this' })
    expect(within(choices).getByRole('radio', { name: /Do not replace anything/ })).toBeChecked()
    expect(await within(choices).findByRole('radio', { name: /Replace Published Liability Cap/ })).not.toBeChecked()
    expect(choices).toHaveTextContent(/not deleted: earlier publications still show it/)
  })

  it('does not treat a duplicate as rejected or as published', async () => {
    await open(reviewWorld(), '?candidate=duplicate')
    expect(screen.getByRole('group', { name: 'Possible duplicate' })).toHaveTextContent(/not rejected for you/)
    expect(within(decisionBar()).getByRole('button', { name: /Approve/ })).toBeEnabled()
  })
})

describe('provenance and states', () => {
  it('shows a failed extraction as failed, with no review and nothing published', async () => {
    const world = reviewWorld()
    world.artifacts = [{ ...world.artifacts[0]!, ingestStatus: 'FAILED' }]
    startFakeServer(world)
    renderApp(`/knowledge/compilations/${SRC}`)
    expect(await screen.findByRole('heading', { name: 'Rivell could not extract this file' })).toBeInTheDocument()
    expect(screen.getByText(/Nothing was compiled and nothing was published/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Approve/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Publish/ })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the source' })).toHaveAttribute('href', `/knowledge/sources/${SRC}`)
  })

  it('says a source is being extracted, without a percentage or an estimate', async () => {
    const world = reviewWorld()
    world.artifacts = [{ ...world.artifacts[0]!, ingestStatus: 'EXTRACTING' }]
    startFakeServer(world)
    renderApp(`/knowledge/compilations/${SRC}`)
    expect(await screen.findByRole('heading', { name: 'Rivell is extracting this file' })).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/\d+\s?%|minutes|seconds|ETA/i)
  })

  it('does not publish an empty compilation', async () => {
    const world = reviewWorld()
    world.candidates[SRC] = []
    startFakeServer(world)
    renderApp(`/knowledge/compilations/${SRC}`)
    expect(await screen.findByRole('heading', { name: 'No candidates were compiled' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Publish/ })).not.toBeInTheDocument()
  })

  it('states a permission failure plainly and does not call it an empty compilation', async () => {
    const world = reviewWorld()
    world.fail[`GET artifact ${SRC}`] = 403
    startFakeServer(world)
    renderApp(`/knowledge/compilations/${SRC}`)
    expect(await screen.findByRole('heading', { name: 'Knowledge is not available to you' })).toBeInTheDocument()
    expect(screen.queryByText(/No candidates/)).not.toBeInTheDocument()
  })

  it('states a permission failure on the candidates too', async () => {
    const world = reviewWorld()
    world.fail[`GET candidates ${SRC}`] = 403
    startFakeServer(world)
    renderApp(`/knowledge/compilations/${SRC}`)
    expect(await screen.findByRole('heading', { name: 'Knowledge is not available to you' })).toBeInTheDocument()
  })

  it('offers a retry when the candidates cannot be loaded, and shows no stack trace', async () => {
    const world = reviewWorld()
    world.fail[`GET candidates ${SRC}`] = 500
    startFakeServer(world)
    renderApp(`/knowledge/compilations/${SRC}`)
    expect(await screen.findByRole('heading', { name: 'The candidates could not be loaded' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/at \w+\.|Exception|stack/i)
  })

  it('says when a source cannot be found', async () => {
    startFakeServer(reviewWorld())
    renderApp('/knowledge/compilations/missing')
    expect(await screen.findByRole('heading', { name: 'This source could not be found' })).toBeInTheDocument()
  })

  it('keeps the old compilation address working, with the candidate it named', async () => {
    startFakeServer(reviewWorld())
    const { router } = renderApp(`/knowledge/artifacts/${SRC}?candidate=uncertain`)
    await screen.findByRole('heading', { level: 1, name: 'Indemnification' })
    expect(router.state.location.pathname).toBe(`/knowledge/compilations/${SRC}`)
    expect(router.state.location.search).toBe('?candidate=uncertain')
  })

  it('says a candidate that is not part of this source is not, and shows the next one', async () => {
    await open(reviewWorld(), '?candidate=nope')
    expect(screen.getByText(/That candidate is not part of this source/)).toBeInTheDocument()
    expect(heading()).toHaveTextContent('Assignment')
  })

  it('stays on the candidate being corrected while the server moves it between groups', async () => {
    const { router } = await open()
    // The default is kept in the address, so a change in the server's answers cannot move it.
    await waitFor(() => expect(router.state.location.search).toBe('?candidate=conflict'))
    await userEvent.click(within(decisionBar()).getByRole('button', { name: /Edit/ }))
    const form = await screen.findByRole('form', { name: 'Correct the structured candidate' })
    await userEvent.selectOptions(within(form).getByLabelText('Legal concept'), 'GOVERNING_LAW')
    await userEvent.click(within(decisionBar()).getByRole('button', { name: 'Save correction' }))
    await screen.findByText('Corrected by you')
    expect(heading()).toHaveTextContent('Assignment')
  })
})
