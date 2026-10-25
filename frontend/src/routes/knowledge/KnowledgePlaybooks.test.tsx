import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { artifact, candidate, publication } from '../../test/knowledgeFixtures'
import { buildWorld, renderKnowledge, stubKnowledgeApi } from './knowledgeWorld'

afterEach(() => {
  vi.unstubAllGlobals()
  globalThis.localStorage?.clear()
})

describe('Playbooks list', () => {
  it('lists published playbooks with their real publication and position count', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/playbooks')

    const list = await screen.findByRole('list', { name: 'Published playbooks' })
    const row = within(list).getByRole('link')
    expect(row).toHaveAttribute('href', '/knowledge/playbooks/pb')
    expect(row).toHaveTextContent('Commercial MSA Playbook')
    expect(row).toHaveTextContent('Applies to MSA')
    expect(row).toHaveTextContent('Knowledge version 2')
    expect(row).toHaveTextContent('3 positions')
    // A clause library and a draft are not published playbooks.
    expect(within(list).queryByText(/Clause Library/)).not.toBeInTheDocument()
    expect(within(list).queryByText(/Draft Playbook/)).not.toBeInTheDocument()
  })

  it('names a playbook by the title its document gives itself', async () => {
    const world = buildWorld()
    world.artifacts = world.artifacts.map((entry) =>
      entry.id === 'pb' ? { ...entry, documentTitle: 'Mutual NDA Negotiation Guide' } : entry,
    )
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge/playbooks')

    const list = await screen.findByRole('list', { name: 'Published playbooks' })
    const row = within(list).getByRole('link')
    expect(row).toHaveTextContent('Mutual NDA Negotiation Guide')
    expect(row).not.toHaveTextContent('Commercial MSA Playbook')
  })

  it('shows a document title in the detail header and breadcrumb', async () => {
    const world = buildWorld()
    world.artifacts = world.artifacts.map((entry) =>
      entry.id === 'pb' ? { ...entry, documentTitle: 'Mutual NDA Negotiation Guide' } : entry,
    )
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge/playbooks/pb')

    await screen.findByRole('heading', { level: 1, name: 'Mutual NDA Negotiation Guide' })
    const breadcrumbs = screen.getByRole('navigation', { name: /Breadcrumb/i })
    expect(within(breadcrumbs).getByText('Mutual NDA Negotiation Guide')).toBeInTheDocument()
  })

  it('states an all-types playbook instead of leaving its scope out', async () => {
    const world = buildWorld()
    world.candidates.pb = (world.candidates.pb ?? []).map((entry) => ({
      ...entry,
      declaredContractTypes: [],
    }))
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge/playbooks')

    const list = await screen.findByRole('list', { name: 'Published playbooks' })
    const row = within(list).getByRole('link')
    expect(row).toHaveTextContent('Applies to All contract types.')
  })

  it('names an all-types playbook in its detail header', async () => {
    const world = buildWorld()
    world.candidates.pb = (world.candidates.pb ?? []).map((entry) => ({
      ...entry,
      declaredContractTypes: [],
    }))
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge/playbooks/pb')
    await screen.findByRole('heading', { level: 1, name: 'Commercial MSA Playbook' })
    expect(screen.getAllByText('All contract types').length).toBeGreaterThan(0)
  })

  it('shows a playbook that is still in compilation separately, never as published', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/playbooks')
    const compiling = await screen.findByRole('list', { name: 'Playbook drafts' })
    const row = within(compiling).getByRole('link')
    expect(row).toHaveAttribute('href', '/knowledge/compilations/draft')
    expect(row).toHaveTextContent('Nothing is used in reviews until you publish it')
  })

  it('states the empty case plainly with one Add action', async () => {
    stubKnowledgeApi({ artifacts: [], candidates: {}, publications: [], units: {}, structure: {} })
    renderKnowledge('/knowledge/playbooks')
    expect(await screen.findByText('No playbook is published')).toBeInTheDocument()
    const actions = screen.getAllByRole('link', { name: /^Add (knowledge|playbook)$/ })
    expect(actions).toHaveLength(1)
    expect(actions[0]).toHaveAttribute('href', '/knowledge/playbooks?add=playbook')
  })
})

describe('Playbook detail', () => {
  async function open(path = '/knowledge/playbooks/pb') {
    stubKnowledgeApi(buildWorld())
    const view = renderKnowledge(path)
    await screen.findByRole('heading', { level: 1, name: 'Commercial MSA Playbook' })
    return view
  }

  it('shows the current published positions, newest snapshot, with real metadata', async () => {
    await open()
    expect(screen.getByRole('tab', { name: 'Positions (3)' })).toHaveAttribute('aria-selected', 'true')
    const list = screen.getByRole('list', { name: 'Positions' })
    expect(within(list).getAllByRole('button').map((b) => b.querySelector('b')?.textContent)).toEqual([
      'Limitation of Liability',
      'Payment Terms',
      'Termination for Convenience',
    ])
    expect(screen.getByText('Current')).toBeInTheDocument()
    expect(screen.getByText('Knowledge version 2')).toBeInTheDocument()
    expect(screen.getAllByText('MSA').length).toBeGreaterThan(0)
  })

  it('shows a passage that is both preferred and fallback once, and the escalation trigger in its own column', async () => {
    await open()
    const first = within(screen.getByRole('list', { name: 'Positions' })).getAllByRole('button')[0] as HTMLElement
    expect(within(first).getAllByText('A cap at twelve months of fees is preferred.')).toHaveLength(1)
    expect(first).toHaveTextContent('Preferred and fallback:')
    expect(first).toHaveTextContent('Escalate: Any cap above two times annual fees')
    const second = within(screen.getByRole('list', { name: 'Positions' })).getAllByRole('button')[1] as HTMLElement
    expect(second).toHaveTextContent('Preferred: Net 45.')
    expect(second).toHaveTextContent('No fallback position')
    expect(second).toHaveTextContent('No escalation trigger')
  })

  it('selects the first position and fills the details panel from the selected one', async () => {
    await open()
    const details = screen.getByRole('complementary', { name: 'Details' })
    expect(within(details).getByRole('heading', { level: 2, name: 'Limitation of Liability' })).toBeInTheDocument()
    expect(within(details).getByText('Preferred, Fallback')).toBeInTheDocument()
    expect(within(details).getByText('Topic').nextElementSibling).toHaveTextContent('Limitation of liability')
    expect(within(details).getByText(/^Knowledge version 1, /)).toBeInTheDocument()

    await userEvent.click(within(screen.getByRole('list', { name: 'Positions' })).getByRole('button', { name: /Payment Terms/ }))
    expect(within(details).getByRole('heading', { level: 2, name: 'Payment Terms' })).toBeInTheDocument()
    expect(within(details).queryByText('Topic')).not.toBeInTheDocument()
  })

  it('shows the typed stances and the approval rule with its source level', async () => {
    const world = buildWorld()
    world.candidates.pb = (world.candidates.pb ?? []).map((entry) =>
      entry.id === 'p1'
        ? {
            ...entry,
            preferredText: 'Cap at twelve months of fees.',
            fallbackTexts: ['Cap at eighteen months for Tier 1 deals.'],
            prohibitedText: 'Unlimited liability.',
            authorityRules: [
              {
                approver: 'Commercial Manager',
                stances: ['FALLBACK'],
                condition: 'above the preferred position',
                source: 'MATRIX',
                quote: 'the Commercial Manager approves fallbacks',
              },
            ],
            authorityConflicts: [
              { approver: 'General Counsel', source: 'ENTRY', stance: 'FALLBACK' },
            ],
          }
        : entry,
    )
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge/playbooks/pb')

    const details = await screen.findByRole('complementary', { name: 'Details' })
    expect(details).toHaveTextContent('Cap at twelve months of fees.')
    expect(details).toHaveTextContent('Cap at eighteen months for Tier 1 deals.')
    expect(details).toHaveTextContent('Unlimited liability.')
    const table = within(details).getByRole('table')
    expect(table).toHaveTextContent('Situation')
    expect(table).toHaveTextContent('Approver')
    expect(table).toHaveTextContent('Source')
    expect(table).toHaveTextContent('Fallback')
    expect(table).toHaveTextContent('Commercial Manager')
    expect(table).toHaveTextContent('Approval matrix')
    expect(table).toHaveTextContent('If above the preferred position')
    expect(details).toHaveTextContent('General Counsel (Entry, fallback)')
  })

  it('shows provenance from the extraction record and links to the original source position', async () => {
    await open()
    const details = screen.getByRole('complementary', { name: 'Details' })
    expect(await within(details).findByText(/Source, 4\.2/)).toBeInTheDocument()
    expect(within(details).getByText('A cap at twelve months of fees is preferred.')).toBeInTheDocument()
    expect(within(details).getByRole('link', { name: /Open in original source/ })).toHaveAttribute('href', '/knowledge/sources/pb?position=p1')
  })

  it('never links a playbook position to a clause, even on the same legal concept', async () => {
    await open()
    const details = screen.getByRole('complementary', { name: 'Details' })
    // The concept is shown as classification. The API has no playbook-position to clause relation.
    expect(within(details).getByText('Limitation of liability')).toBeInTheDocument()
    expect(within(details).queryByRole('link', { name: /clause/i })).not.toBeInTheDocument()
    expect(within(details).queryByText(/same legal concept/i)).not.toBeInTheDocument()
    expect(details.querySelector('a[href^="/knowledge/clauses/"]')).toBeNull()
  })

  it('selects a position from the URL', async () => {
    await open('/knowledge/playbooks/pb?position=p2')
    const details = screen.getByRole('complementary', { name: 'Details' })
    expect(within(details).getByRole('heading', { level: 2, name: 'Payment Terms' })).toBeInTheDocument()
  })

  it('lists real publications in version history, newest first, with what each changed', async () => {
    await open()
    await userEvent.click(screen.getByRole('tab', { name: 'Version history' }))
    const history = screen.getByRole('list', { name: 'Version history' })
    const rows = within(history).getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Knowledge version 2 (current)')
    expect(rows[0]).toHaveTextContent('1 superseded')
    expect(rows[0]).toHaveTextContent('Termination update')
    expect(rows[0]).toHaveTextContent('Viewing')
    expect(rows[1]).toHaveTextContent('Knowledge version 1')
    expect(rows[1]).toHaveTextContent('3 positions added')
    expect(rows[1]).toHaveTextContent('Initial publication')
    expect(rows[1]).toHaveTextContent('Source: Commercial MSA Playbook.docx')
    // History has no position detail, so the page takes the panel's column.
    expect(screen.queryByRole('complementary', { name: 'Details' })).not.toBeInTheDocument()
  })

  it('opens an older publication as its own immutable snapshot and returns to the current one', async () => {
    await open()
    await userEvent.click(screen.getByRole('tab', { name: 'Version history' }))
    await userEvent.click(screen.getByRole('button', { name: 'View knowledge version 1' }))

    const banner = screen.getByRole('note')
    expect(banner).toHaveTextContent('You are viewing knowledge version 1. Version 2 is current.')
    expect(screen.getByText('Superseded')).toBeInTheDocument()
    // The snapshot has the position that publication 2 later superseded, not the one that replaced it.
    await userEvent.click(screen.getByRole('tab', { name: /^Positions/ }))
    const names = within(screen.getByRole('list', { name: 'Positions' })).getAllByRole('button').map((b) => b.querySelector('b')?.textContent)
    expect(names).toEqual(['Limitation of Liability', 'Payment Terms', 'Termination'])
    expect(names).not.toContain('Termination for Convenience')

    await userEvent.click(within(banner).getByRole('button', { name: 'Back to current' }))
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
    const current = within(screen.getByRole('list', { name: 'Positions' })).getAllByRole('button').map((b) => b.querySelector('b')?.textContent)
    expect(current).toContain('Termination for Convenience')
    expect(current).not.toContain('Termination')
  })

  it('moves between tabs with the arrow keys, keeping one tab stop', async () => {
    await open()
    const positions = screen.getByRole('tab', { name: /^Positions/ })
    const history = screen.getByRole('tab', { name: 'Version history' })
    expect(positions).toHaveAttribute('tabindex', '0')
    expect(history).toHaveAttribute('tabindex', '-1')

    positions.focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(history).toHaveFocus()
    expect(history).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel')).toHaveAccessibleName('Version history')
    expect(screen.getByRole('list', { name: 'Version history' })).toBeInTheDocument()

    await userEvent.keyboard('{Home}')
    expect(screen.getByRole('tab', { name: /^Positions/ })).toHaveFocus()
    expect(screen.getByRole('list', { name: 'Positions' })).toBeInTheDocument()
  })

  it('opens an older publication from a link', async () => {
    await open('/knowledge/playbooks/pb?publication=1')
    expect(screen.getByRole('note')).toHaveTextContent('You are viewing knowledge version 1')
    expect(screen.getByRole('tab', { name: 'Positions (3)' })).toBeInTheDocument()
  })

  it('ignores a publication that never touched this playbook and shows the current one', async () => {
    await open('/knowledge/playbooks/pb?publication=99')
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
    expect(screen.getByText('Knowledge version 2')).toBeInTheDocument()
  })

  it('says so when the source has nothing published', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/playbooks/draft')
    expect(await screen.findByText('Nothing from this source is published')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the source' })).toHaveAttribute('href', '/knowledge/sources/draft')
  })

  it('says so when the playbook does not exist', async () => {
    stubKnowledgeApi(buildWorld())
    renderKnowledge('/knowledge/playbooks/unknown')
    expect(await screen.findByText('This playbook could not be found')).toBeInTheDocument()
  })

  it('opens a source from the page header', async () => {
    await open()
    expect(screen.getByRole('link', { name: 'Source' })).toHaveAttribute('href', '/knowledge/sources/pb')
  })

  it('keeps an empty snapshot honest when every position was superseded', async () => {
    const world = buildWorld()
    world.artifacts.push(artifact({ id: 'pb2', filename: 'Replacement Playbook.docx' }))
    world.candidates.pb = (world.candidates.pb ?? []).filter((c) => c.id !== 'p4')
    world.candidates.pb2 = ['x1', 'x2', 'x3'].map((id) => candidate({ id, artifactId: 'pb2' }))
    world.publications = [
      world.publications[0]!,
      publication(2, [
        { candidateId: 'x1', supersedes: 'p1' },
        { candidateId: 'x2', supersedes: 'p2' },
        { candidateId: 'x3', supersedes: 'p3' },
      ]),
    ]
    stubKnowledgeApi(world)
    renderKnowledge('/knowledge/playbooks/pb')
    await screen.findByRole('heading', { level: 1, name: 'Commercial MSA Playbook' })
    expect(screen.getByText('No positions in this version')).toBeInTheDocument()
    expect(screen.getByText('Superseded')).toBeInTheDocument()
    expect(screen.queryByText('Current')).not.toBeInTheDocument()
  })
})
