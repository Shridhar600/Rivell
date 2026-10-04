import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { buildWorld, renderKnowledge, stubKnowledgeApi } from './knowledgeWorld'

afterEach(() => {
  vi.unstubAllGlobals()
  globalThis.localStorage?.clear()
})

describe('Sources list', () => {
  it('lists every source newest first with type, format, date and a real state', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/sources')

    const list = await screen.findByRole('list', { name: 'Sources' })
    const rows = within(list).getAllByRole('link')
    expect(rows.map((row) => row.querySelector('b')?.textContent)).toEqual([
      'Broken.docx',
      'Draft Playbook.docx',
      'Clause Library.xlsx',
      'Commercial MSA Playbook.docx',
    ])
    expect(rows[0]).toHaveTextContent('Extraction failed')
    expect(rows[1]).toHaveTextContent('Awaiting review')
    expect(rows[1]).toHaveTextContent('Compilation in review, 1 of 2 candidates reviewed')
    expect(rows[2]).toHaveTextContent('Clause library, XLSX')
    expect(rows[2]).toHaveTextContent('3 clause variants published in publication 1')
    expect(rows[3]).toHaveTextContent('Published')
    expect(rows[3]).toHaveTextContent('4 positions published in publications 1, 2')
    expect(rows[3]).toHaveAttribute('href', '/knowledge/sources/pb')
  })

  it('names the type from the source, never from a prototype', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/sources')
    await screen.findByRole('list', { name: 'Sources' })
    expect(screen.queryByText(/DOCX \/ PDF|DOCX or PDF/)).not.toBeInTheDocument()
  })

  it('shows an empty state with Add knowledge', async () => {
    stubKnowledgeApi({ ...buildWorld(), artifacts: [], candidates: {}, publications: [] })
    renderKnowledge('/knowledge/sources')
    expect(await screen.findByText('No sources yet')).toBeInTheDocument()
  })

  it('shows a source that is still extracting as compiling', async () => {
    const world = buildWorld()
    world.artifacts[0] = { ...world.artifacts[0]!, ingestStatus: 'EXTRACTING' }
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge/sources')
    const row = (await screen.findByText('Broken.docx')).closest('a') as HTMLElement
    expect(row).toHaveTextContent('Compiling')
    expect(row).toHaveTextContent('Compiling candidates')
  })
})

describe('Source detail and lineage', () => {
  async function open(id: string, query = '', content = true) {
    stubKnowledgeApi(buildWorld())
    const view = renderKnowledge(`/knowledge/sources/${id}${query}`)
    await screen.findByRole('heading', { level: 1 })
    await screen.findByLabelText('Lineage: source, compilation, published knowledge')
    // The extracted original loads after the lineage. Wait for it so a test never reads half a page.
    if (content) await screen.findByRole('heading', { name: 'Original content' })
    return view
  }

  it('shows Source, Compilation and Published knowledge from real relationships', async () => {
    await open('pb')
    const lineage = screen.getByLabelText('Lineage: source, compilation, published knowledge')
    expect(within(lineage).getByText('Commercial MSA Playbook.docx')).toBeInTheDocument()
    expect(within(lineage).getByText('Compiled and reviewed')).toBeInTheDocument()
    expect(within(lineage).getByText('4 published, 0 rejected')).toBeInTheDocument()
    const published = within(lineage).getByRole('link', { name: /Published knowledge/ })
    expect(published).toHaveAttribute('href', '/knowledge/playbooks/pb')
    expect(published).toHaveTextContent('Publications 1, 2')
    expect(published).toHaveTextContent('3 positions in use')
    expect(screen.getByText('Immutable original')).toBeInTheDocument()
    // The canonical lineage has no download control, so none is offered even though the endpoint exists.
    expect(screen.queryByRole('link', { name: /Download/ })).not.toBeInTheDocument()
    expect(document.querySelector('a[href*="/original"]')).toBeNull()
  })

  it('highlights exactly the source nodes the extraction recorded for published knowledge', async () => {
    await open('pb')
    const highlighted = screen.getAllByRole('button', { pressed: false }).filter((button) => button.classList.contains('rv-k-hl'))
    expect(highlighted.map((b) => b.textContent)).toEqual(['Limitation of Liability', 'A cap at twelve months of fees is preferred.', 'Net 45.'])
    // A node no unit recorded is shown, unhighlighted.
    expect(screen.getByText('Unrelated source text.').closest('button')).toBeNull()
  })

  it('claims node-level lineage only, and no page number', async () => {
    await open('pb')
    expect(screen.getByText('Highlights mark the exact source nodes')).toBeInTheDocument()
    expect(screen.queryByText(/page \d/i)).not.toBeInTheDocument()
  })

  it('selects a produced position, marks its passage and links to it', async () => {
    await open('pb')
    const produced = screen.getByRole('list', { name: 'Produced positions' })
    await userEvent.click(within(produced).getByRole('button', { name: 'Limitation of Liability' }))
    expect(within(produced).getByRole('button', { name: 'Limitation of Liability' })).toHaveAttribute('aria-pressed', 'true')
    const marked = screen.getAllByRole('button', { pressed: true }).filter((b) => b.classList.contains('rv-k-hl'))
    expect(marked.map((b) => b.textContent)).toEqual(['Limitation of Liability', 'A cap at twelve months of fees is preferred.'])
    expect(screen.getByRole('link', { name: /Open position/ })).toHaveAttribute('href', '/knowledge/playbooks/pb?position=p1')

    await userEvent.click(within(produced).getByRole('button', { name: 'Limitation of Liability' }))
    expect(screen.queryByRole('link', { name: /Open position/ })).not.toBeInTheDocument()
  })

  it('selects a position from the URL, and a passage click selects it too', async () => {
    await open('pb', '?position=p2')
    const produced = screen.getByRole('list', { name: 'Produced positions' })
    expect(within(produced).getByRole('button', { name: 'Payment Terms' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(screen.getAllByRole('button', { name: 'A cap at twelve months of fees is preferred.' })[0] as HTMLElement)
    expect(within(produced).getByRole('button', { name: 'Limitation of Liability' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('does not claim lineage for a source with nothing published', async () => {
    await open('draft', '', false)
    const lineage = screen.getByLabelText('Lineage: source, compilation, published knowledge')
    expect(within(lineage).getByText('2 candidates, 1 reviewed')).toBeInTheDocument()
    expect(within(lineage).getByText('None yet')).toBeInTheDocument()
    expect(within(lineage).getByRole('link', { name: /Continue review/ })).toHaveAttribute('href', '/knowledge/compilations/draft')
    expect(screen.queryByRole('list', { name: 'Produced positions' })).not.toBeInTheDocument()
  })

  it('shows a failed extraction without pretending there is content', async () => {
    await open('broken', '', false)
    expect(screen.getByText(/could not be extracted/i)).toBeInTheDocument()
    const lineage = screen.getByLabelText('Lineage: source, compilation, published knowledge')
    expect(within(lineage).getAllByText('Extraction failed').length).toBeGreaterThan(0)
    expect(within(lineage).getByText('None yet')).toBeInTheDocument()
  })

  it('links a clause library source to its produced clauses', async () => {
    await open('cl', '?position=c2')
    const produced = screen.getByRole('list', { name: 'Produced clauses' })
    expect(within(produced).getAllByRole('button')).toHaveLength(3)
    expect(screen.getByRole('link', { name: /Open clause/ })).toHaveAttribute('href', '/knowledge/clauses/LIMITATION_OF_LIABILITY?variant=c2')
    const lineage = screen.getByLabelText('Lineage: source, compilation, published knowledge')
    expect(within(lineage).getByRole('link', { name: /Published knowledge/ })).toHaveAttribute('href', '/knowledge/clauses')
  })

  it('shows a workbook as sheet rows with the produced rows highlighted', async () => {
    await open('cl')
    expect(screen.getByRole('heading', { level: 4, name: 'Clauses' })).toBeInTheDocument()
    const highlighted = screen.getAllByRole('button').filter((b) => b.classList.contains('rv-k-hl'))
    expect(highlighted.map((b) => b.textContent)).toEqual([
      'Mutual, standard | Each party liability is capped at twelve months of fees.',
      'Supplier-favorable | Supplier liability is capped at six months of fees.',
      'Audit Rights | Customer may audit once a year.',
    ])
  })

  it('does not fake content when the extraction recorded none', async () => {
    const world = buildWorld()
    world.structure.pb = []
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge/sources/pb')
    expect(await screen.findByText(/extracted content could not be shown/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Original content' })).not.toBeInTheDocument()
  })

  it('says so when the source does not exist', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/sources/unknown')
    expect(await screen.findByText('This source could not be found')).toBeInTheDocument()
  })
})
