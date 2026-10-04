import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { buildWorld, renderKnowledge, stubKnowledgeApi } from './knowledgeWorld'

afterEach(() => {
  vi.unstubAllGlobals()
  globalThis.localStorage?.clear()
})

describe('Knowledge overview', () => {
  it('shows real counts and recently published knowledge from the API', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge')

    expect(await screen.findByRole('heading', { level: 1, name: 'Knowledge' })).toHaveFocus()

    const playbooks = (await screen.findByText('Playbooks', { selector: 'b' })).closest('a')
    expect(playbooks).toHaveAttribute('href', '/knowledge/playbooks')
    // One source is a published playbook. The clause library is a different kind of source.
    expect(within(playbooks as HTMLElement).getByText('1')).toBeInTheDocument()

    const clauses = screen.getByText('Clause library', { selector: 'b' }).closest('a') as HTMLElement
    expect(clauses).toHaveAttribute('href', '/knowledge/clauses')
    expect(within(clauses).getByText('2')).toBeInTheDocument() // Liability and Audit Rights
    expect(within(clauses).getByText(/3 variants/)).toBeInTheDocument()

    const sources = screen.getByText('Sources', { selector: 'b' }).closest('a') as HTMLElement
    expect(within(sources).getByText('4')).toBeInTheDocument()
  })

  it('lists recently published knowledge newest first with real publication numbers', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge')

    const recent = await screen.findByRole('heading', { name: 'Recently published' })
    const list = recent.closest('section')?.querySelector('ul') as HTMLElement
    const rows = within(list).getAllByRole('link')
    expect(rows[0]).toHaveTextContent('Commercial MSA Playbook')
    expect(rows[0]).toHaveTextContent('Publication 2')
    expect(rows[0]).toHaveAttribute('href', '/knowledge/playbooks/pb?publication=2')
    expect(rows[1]).toHaveTextContent('Publication 1')
    expect(rows.some((row) => row.getAttribute('href') === '/knowledge/clauses?publication=1')).toBe(true)
  })

  it('shows what is in compilation only from real candidate counts', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge')

    const heading = await screen.findByRole('heading', { name: 'In compilation' })
    const row = within(heading.closest('section') as HTMLElement).getByRole('link')
    expect(row).toHaveAttribute('href', '/knowledge/compilations/draft')
    expect(row).toHaveTextContent('From Draft Playbook.docx, 1 of 2 candidates reviewed')
    expect(row).toHaveTextContent('Awaiting review')
  })

  it('has no In compilation section when nothing is compiling', async () => {
    const world = buildWorld()
    world.artifacts = world.artifacts.filter((entry) => entry.id !== 'draft')
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge')
    await screen.findByRole('heading', { name: 'Recently published' })
    expect(screen.queryByRole('heading', { name: 'In compilation' })).not.toBeInTheDocument()
  })

  it('treats empty published Knowledge as a state, not an error', async () => {
    stubKnowledgeApi({ artifacts: [], candidates: {}, publications: [], units: {}, structure: {} })
    renderKnowledge('/knowledge')

    expect(await screen.findByText(/nothing has been published yet/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getAllByText('0').length).toBeGreaterThanOrEqual(3)
  })

  it('opens Add knowledge on the same page, as a dialog', async () => {
    stubKnowledgeApi(buildWorld())
    const { router } = renderKnowledge('/knowledge')
    await screen.findByRole('heading', { name: 'Recently published' })
    const buttons = screen.getAllByRole('link', { name: 'Add knowledge' })
    expect(buttons.length).toBeGreaterThan(0)
    for (const button of buttons) expect(button).toHaveAttribute('href', '/knowledge?add=1')

    await userEvent.click(buttons[0] as HTMLElement)
    const dialog = await screen.findByRole('dialog', { name: 'Add knowledge' })
    expect(dialog).toBeInTheDocument()
    // The page behind it keeps its place, and the dialog is addressable.
    expect(router.state.location.pathname).toBe('/knowledge')
    expect(router.state.location.search).toBe('?add=1')
    expect(screen.getByRole('heading', { name: 'Recently published' })).toBeInTheDocument()
  })

  it('keeps the old /knowledge/new address working by opening the dialog', async () => {
    stubKnowledgeApi(buildWorld())
    const { router } = renderKnowledge('/knowledge/new?type=clause-library')
    expect(await screen.findByRole('dialog', { name: 'Add knowledge' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/knowledge')
    expect(router.state.location.search).toBe('?add=clause-library')
    expect(screen.getByRole('radio', { name: /Clause library/ })).toBeChecked()
  })
})

describe('freshness after publishing', () => {
  it('refetches when the compilation workspace invalidates the knowledge lists', async () => {
    const world = buildWorld()
    stubKnowledgeApi(world)
    const { client } = renderKnowledge('/knowledge')
    const playbooks = (await screen.findByText('Playbooks', { selector: 'b' })).closest('a') as HTMLElement
    expect(within(playbooks).getByText('1')).toBeInTheDocument()

    // Another source is published, then the workspace invalidates the legacy key prefixes.
    world.artifacts.push({ ...world.artifacts[3]!, id: 'pb3', filename: 'Another.docx' })
    world.candidates.pb3 = [{ ...world.candidates.pb![0]!, id: 'q1', artifactId: 'pb3' }]
    world.publications.push({ ...world.publications[0]!, id: 'pub-9', publicationNumber: 9, items: [{ id: 'i', candidateId: 'q1', supersededCandidateId: null, action: 'ADD' }] })
    await client.invalidateQueries({ queryKey: ['knowledge', 'artifacts'] })
    await client.invalidateQueries({ queryKey: ['knowledge', 'publications'] })

    await waitFor(() => expect(within(screen.getByText('Playbooks', { selector: 'b' }).closest('a') as HTMLElement).getByText('2')).toBeInTheDocument())
  })
})

describe('Knowledge navigation', () => {
  it('offers the four sections inside Knowledge, with the current one marked', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/playbooks')

    const nav = screen.getByRole('navigation', { name: 'Knowledge' })
    const links = within(nav).getAllByRole('link')
    expect(links.map((link) => [link.textContent?.trim(), link.getAttribute('href')])).toEqual([
      ['Overview', '/knowledge'],
      ['Playbooks', '/knowledge/playbooks'],
      ['Clause library', '/knowledge/clauses'],
      ['Sources', '/knowledge/sources'],
    ])
    expect(within(nav).getByRole('link', { name: 'Playbooks' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current')
  })

  it('keeps the three primary destinations and shows the sections only inside Knowledge', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/reviews')
    const primary = screen.getByRole('navigation', { name: 'Primary' })
    expect(within(primary).getAllByRole('link').map((link) => link.textContent?.trim())).toEqual(['Home', 'Reviews', 'Knowledge'])
    expect(screen.queryByRole('navigation', { name: 'Knowledge' })).not.toBeInTheDocument()
  })

  it('marks Overview current on exactly the overview route', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge')
    const nav = screen.getByRole('navigation', { name: 'Knowledge' })
    expect(within(nav).getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page')
  })

  it('shows a breadcrumb with real links on a detail page', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/playbooks/pb')
    await screen.findByRole('heading', { level: 1, name: 'Commercial MSA Playbook' })
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Knowledge' })).toHaveAttribute('href', '/knowledge')
    expect(within(crumbs).getByRole('link', { name: 'Playbooks' })).toHaveAttribute('href', '/knowledge/playbooks')
    expect(within(crumbs).getByText('Commercial MSA Playbook')).toHaveAttribute('aria-current', 'page')
  })
})

describe('loading, error and permission states', () => {
  it('announces loading inside the real page frame without inventing counts', async () => {
    const { release } = stubKnowledgeApi(buildWorld(), { hold: true })
    renderKnowledge('/knowledge')
    expect(await screen.findByRole('status')).toHaveTextContent('Loading Knowledge')
    expect(screen.queryByText('0')).not.toBeInTheDocument()
    release()
    expect(await screen.findByRole('heading', { name: 'Recently published' })).toBeInTheDocument()
  })

  it('recovers from a failed read with retry', async () => {
    const world = buildWorld()
    stubKnowledgeApi(world, { listStatus: 500 })
    renderKnowledge('/knowledge')
    expect(await screen.findByRole('alert')).toHaveTextContent('Knowledge could not be loaded')

    // The backend recovers, and Try again reads it again.
    vi.unstubAllGlobals()
    stubKnowledgeApi(world)
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'Recently published' })).toBeInTheDocument()
  })

  it('does not turn a permission refusal into an error-looking or empty workspace', async () => {
    stubKnowledgeApi(buildWorld(), { listStatus: 403 })
    renderKnowledge('/knowledge')
    const notice = await screen.findByText('Knowledge is not available to you')
    expect(notice.closest('[role="alert"]')).not.toHaveClass('rv-state--error')
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
    expect(screen.queryByText(/could not be loaded/i)).not.toBeInTheDocument()
  })

  it('says so when some sources could not be read, and keeps the rest', async () => {
    stubKnowledgeApi(buildWorld(), { failCandidatesFor: ['draft'] })
    renderKnowledge('/knowledge')
    expect(await screen.findByText(/one source could not be read/i)).toBeInTheDocument()
    expect(screen.getByText('Recently published')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'In compilation' })).not.toBeInTheDocument()
  })
})

describe('what the read surfaces must not be', () => {
  const paths = [
    '/knowledge',
    '/knowledge/playbooks',
    '/knowledge/playbooks/pb',
    '/knowledge/playbooks/pb?tab=history',
    '/knowledge/clauses',
    '/knowledge/clauses/LIMITATION_OF_LIABILITY',
    '/knowledge/sources',
    '/knowledge/sources/pb',
    '/knowledge/sources/draft',
  ]

  it.each(paths)('only reads: %s', async (path) => {
    const { calls } = stubKnowledgeApi(buildWorld())
    renderKnowledge(path)
    await screen.findByRole('heading', { level: 1 })
    await waitFor(() => expect(calls.length).toBeGreaterThan(2))
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(calls.filter((call) => call.method !== 'GET')).toEqual([])
  })

  it.each(paths)('shows no invented identity, search or prototype copy: %s', async (path) => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge(path)
    await screen.findByRole('heading', { level: 1 })
    await waitFor(() => expect(screen.queryByText('Loading Knowledge…')).not.toBeInTheDocument())
    const text = document.body.textContent ?? ''
    for (const forbidden of [
      'Northgate',
      'Priya',
      'Signed-in reviewer',
      'Current organization',
      'one playbook per contract family',
      'DOCX or PDF',
      'Jump to or run',
    ]) {
      expect(text).not.toContain(forbidden)
    }
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /account|profile|sign out|workspace/i })).not.toBeInTheDocument()
  })
})
