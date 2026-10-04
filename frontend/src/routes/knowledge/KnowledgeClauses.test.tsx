import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { artifact, candidate, publication } from '../../test/knowledgeFixtures'
import { buildWorld, renderKnowledge, stubKnowledgeApi } from './knowledgeWorld'

afterEach(() => {
  vi.unstubAllGlobals()
  globalThis.localStorage?.clear()
})

describe('Clause library', () => {
  it('lists clauses grouped by their typed legal concept, with their real variants', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/clauses')

    const list = await screen.findByRole('list', { name: 'Clauses' })
    const rows = within(list).getAllByRole('link')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Limitation of liability')
    expect(rows[0]).toHaveTextContent('Mutual, standard, Supplier-favorable')
    expect(rows[0]).toHaveTextContent('2 variants')
    expect(rows[0]).toHaveAttribute('href', '/knowledge/clauses/LIMITATION_OF_LIABILITY')
    // A variant with no concept is a clause of its own, titled by what the source calls it.
    expect(rows[1]).toHaveTextContent('Audit Rights')
    expect(rows[1]).toHaveTextContent('1 variant')
    expect(rows[1]).toHaveAttribute('href', '/knowledge/clauses/candidate-c3')
  })

  it('shows the current publication and its tabs', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/clauses')
    await screen.findByRole('heading', { level: 1, name: 'Clause library' })
    expect(await screen.findByRole('tab', { name: 'Clauses (2)' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('Current')).toBeInTheDocument()
    expect(screen.getByText('Publication 1')).toBeInTheDocument()
  })

  it('shows version history only from real publications and reads an older one as its own snapshot', async () => {
    const world = buildWorld()
    world.candidates.cl?.push(candidate({ id: 'c4', artifactId: 'cl', candidateType: 'CLAUSE_VARIANT', rawLabel: 'Late addition', contentText: 'Added later.', effectiveConceptKey: 'GOVERNING_LAW' }))
    world.publications.push(publication(3, [{ candidateId: 'c4' }], { note: 'Added governing law' }))
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge/clauses')

    await userEvent.click(await screen.findByRole('tab', { name: 'Version history' }))
    const history = screen.getByRole('list', { name: 'Version history' })
    const rows = within(history).getAllByRole('listitem')
    expect(rows.map((row) => row.querySelector('b')?.textContent)).toEqual(['Publication 3 (current)', 'Publication 1'])
    expect(rows[0]).toHaveTextContent('1 clause variant added')

    await userEvent.click(screen.getByRole('button', { name: 'View publication 1' }))
    expect(screen.getByRole('note')).toHaveTextContent('Viewing publication 1, superseded by publication 3')
    await userEvent.click(screen.getByRole('tab', { name: /^Clauses/ }))
    const names = within(screen.getByRole('list', { name: 'Clauses' })).getAllByRole('link').map((row) => row.querySelector('b')?.textContent)
    expect(names).toEqual(['Limitation of liability', 'Audit Rights'])
    // The snapshot keeps its place when a clause is opened.
    expect(within(screen.getByRole('list', { name: 'Clauses' })).getAllByRole('link')[0]).toHaveAttribute(
      'href',
      '/knowledge/clauses/LIMITATION_OF_LIABILITY?publication=1',
    )
  })

  it('says plainly that no clause library is published', async () => {
    stubKnowledgeApi({ ...buildWorld(), artifacts: [], candidates: {}, publications: [] })
    renderKnowledge('/knowledge/clauses')
    expect(await screen.findByText('No clause library is published')).toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
  })
})

describe('Clause detail', () => {
  async function open(path: string) {
    stubKnowledgeApi(buildWorld())
    const view = renderKnowledge(path)
    await screen.findByRole('heading', { level: 1, name: /Limitation of liability|Audit Rights/ })
    return view
  }

  it('shows the exact approved wording and the first variant first', async () => {
    await open('/knowledge/clauses/LIMITATION_OF_LIABILITY')
    expect(screen.getByText('Each party liability is capped at twelve months of fees.')).toBeInTheDocument()
    const variants = screen.getByRole('tablist', { name: 'Variant' })
    expect(within(variants).getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Mutual, standard', 'Supplier-favorable'])
    expect(within(variants).getByRole('tab', { name: 'Mutual, standard' })).toHaveAttribute('aria-selected', 'true')
  })

  it('switches variant and shows that variant\'s own wording and provenance', async () => {
    await open('/knowledge/clauses/LIMITATION_OF_LIABILITY')
    await userEvent.click(screen.getByRole('tab', { name: 'Supplier-favorable' }))
    expect(screen.getByText('Supplier liability is capped at six months of fees.')).toBeInTheDocument()
    expect(screen.queryByText('Each party liability is capped at twelve months of fees.')).not.toBeInTheDocument()
    expect(screen.getByText('Clause Library.xlsx')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open source/ })).toHaveAttribute('href', '/knowledge/sources/cl?position=c2')
  })

  it('opens a variant from the URL', async () => {
    await open('/knowledge/clauses/LIMITATION_OF_LIABILITY?variant=c2')
    expect(screen.getByText('Supplier liability is capped at six months of fees.')).toBeInTheDocument()
  })

  it('shows no variant switcher for a clause with one variant', async () => {
    await open('/knowledge/clauses/candidate-c3')
    expect(screen.queryByRole('tablist', { name: 'Variant' })).not.toBeInTheDocument()
    expect(screen.getByText('Customer may audit once a year.')).toBeInTheDocument()
  })

  it('never links a clause to a playbook position, even on the same legal concept', async () => {
    await open('/knowledge/clauses/LIMITATION_OF_LIABILITY')
    // The playbook publishes a position on this same concept. Shared classification is not a relation.
    expect(screen.queryByText(/same legal concept/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Open position/ })).not.toBeInTheDocument()
    expect(document.querySelector('a[href^="/knowledge/playbooks/"]')).toBeNull()
  })

  it('copies the approved wording', async () => {
    const user = userEvent.setup()
    await open('/knowledge/clauses/candidate-c3')
    await user.click(screen.getByRole('button', { name: 'Copy' }))
    expect(await navigator.clipboard.readText()).toBe('Customer may audit once a year.')
    expect(screen.getByRole('status')).toHaveTextContent('Copied the approved wording')
  })

  it('reads a clause as of an older publication and rejects one it was not part of', async () => {
    const world = buildWorld()
    world.candidates.cl?.push(candidate({ id: 'c4', artifactId: 'cl', candidateType: 'CLAUSE_VARIANT', rawLabel: 'Later', effectiveConceptKey: 'GOVERNING_LAW' }))
    world.publications.push(publication(2, [{ candidateId: 'c4' }]))
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge/clauses/GOVERNING_LAW?publication=1')
    expect(await screen.findByText('This clause is not in the published clause library')).toBeInTheDocument()
    expect(screen.getByText('It is not part of publication 1.')).toBeInTheDocument()
  })

  it('says so for a clause that does not exist', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/clauses/NOT_A_CLAUSE')
    const state = (await screen.findByText('This clause is not in the published clause library')).closest('.rv-state') as HTMLElement
    expect(within(state).getByRole('link', { name: 'Clause library' })).toHaveAttribute('href', '/knowledge/clauses')
  })

  it('shows a clause that was superseded as absent from the current library', async () => {
    const world = buildWorld()
    world.artifacts.push(artifact({ id: 'cl2', artifactType: 'CLAUSE_LIBRARY', filename: 'Newer.xlsx' }))
    world.candidates.cl2 = [candidate({ id: 'n1', artifactId: 'cl2', candidateType: 'CLAUSE_VARIANT', rawLabel: 'Audit v2', effectiveConceptKey: null })]
    world.publications.push(publication(2, [{ candidateId: 'n1', supersedes: 'c3' }]))
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge/clauses/candidate-c3')
    expect(await screen.findByText('This clause is not in the published clause library')).toBeInTheDocument()
  })
})
