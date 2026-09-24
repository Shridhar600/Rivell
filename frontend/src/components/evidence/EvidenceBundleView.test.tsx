import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { EvidenceBundleView } from './EvidenceBundleView'
import {
  fixtureBundle,
  fixtureCompatiblePrecedent,
  fixtureIncompatiblePrecedent,
  fixturePolicyItem,
  fixtureRegionLabels,
} from './testFixtures'

function renderBundle(
  overrides: Parameters<typeof fixtureBundle>[0] = {},
  props: Partial<Parameters<typeof EvidenceBundleView>[0]> = {},
) {
  const onNavigateToNode = vi.fn()
  const onOpenClauseSemantics = vi.fn()
  render(
    <MemoryRouter>
      <EvidenceBundleView
        bundle={fixtureBundle(overrides)}
        regionLabels={fixtureRegionLabels}
        onNavigateToNode={onNavigateToNode}
        onOpenClauseSemantics={onOpenClauseSemantics}
        {...props}
      />
    </MemoryRouter>,
  )
  return { onNavigateToNode, onOpenClauseSemantics }
}

describe('authority lanes', () => {
  it('renders lanes in authority order with distinct prominence', () => {
    renderBundle()

    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    expect(headings).toEqual([
      'Evidence sufficiency',
      'Policy',
      'Approved drafting',
      'Current contract',
      'Precedents',
    ])

    const precedentLane = screen.getByRole('region', { name: 'Precedents' })
    expect(precedentLane.className).toContain('evidence-lane--historical')
    expect(within(precedentLane).getAllByText('Precedent').length).toBeGreaterThan(0)

    const policyLane = screen.getByRole('region', { name: 'Policy' })
    expect(policyLane.className).toContain('evidence-lane--authoritative')
    expect(policyLane.textContent).toContain('Playbook rule')
    expect(policyLane.textContent).toContain('Published in v2')
  })

  it('keeps policy missing explicit while precedents remain precedents', () => {
    renderBundle({
      sufficiency: 'POLICY_MISSING',
      items: [fixtureCompatiblePrecedent, fixtureIncompatiblePrecedent],
    })

    const policyLane = screen.getByRole('region', { name: 'Policy' })
    expect(
      within(policyLane).getByText(
        'No published policy applies to this concept and profile.',
      ),
    ).toBeInTheDocument()
    expect(
      within(policyLane).getByText(
        'Historical precedent does not establish organizational policy.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('Policy evidence missing')).toBeInTheDocument()

    const precedentLane = screen.getByRole('region', { name: 'Precedents' })
    expect(within(precedentLane).getAllByText('Precedent').length).toBe(2)
    expect(screen.queryByText('Policy rule')).not.toBeInTheDocument()
  })

  it('renders all applicable policies without choosing one', () => {
    renderBundle({
      sufficiency: 'AMBIGUOUS_POLICY',
      items: [
        fixturePolicyItem,
        { ...fixturePolicyItem, evidenceId: 'e-policy-2', text: 'Liability must be uncapped.' },
      ],
    })

    expect(
      screen.getByText('More than one published policy applies; none was chosen.'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Liability must be capped at fees paid in the preceding 12 months.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Liability must be uncapped.')).toBeInTheDocument()
  })

  it('shows a policy-incompatible precedent honestly', () => {
    renderBundle()

    const precedentLane = screen.getByRole('region', { name: 'Precedents' })
    expect(
      within(precedentLane).getByText('Not compatible with current published policy'),
    ).toBeInTheDocument()
    expect(
      within(precedentLane).getByText('Compatible with current published policy'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/safe|unsafe|acceptable/i)).not.toBeInTheDocument()
  })

  it('keeps degraded bundles usable with quality notes', () => {
    renderBundle({ sufficiency: 'PARTIAL', degradationWarnings: ['INDEX_INCOMPLETE'] })

    expect(screen.getByText('Evidence partially complete')).toBeInTheDocument()
    expect(
      screen.getByText('Some precedent index entries are not currently searchable.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Precedents' })).toBeInTheDocument()
  })

  it('orders policy positions canonically regardless of response order', () => {
    renderBundle()

    const policyLane = screen.getByRole('region', { name: 'Policy' })
    expect(within(policyLane).getByText('Preferred, Fallback')).toBeInTheDocument()
  })
})

describe('evidence text and provenance', () => {
  it('shows precedent text behind a disclosure', () => {
    renderBundle()

    const precedentLane = screen.getByRole('region', { name: 'Precedents' })
    const summary = within(precedentLane).getAllByText('Show exact text')[0]
    const disclosure = summary.closest('details')

    expect(disclosure).not.toBeNull()
    expect(disclosure).not.toHaveAttribute('open')
    expect(
      within(disclosure as HTMLElement).getByText(/aggregate liability of either party/),
    ).toBeInTheDocument()
  })

  it('shows approved drafting wording verbatim', () => {
    renderBundle()

    const draftingLane = screen.getByRole('region', { name: 'Approved drafting' })
    expect(
      within(draftingLane).getByText(
        'The aggregate liability of each party shall not exceed the fees paid in the twelve months preceding the claim.',
      ),
    ).toBeInTheDocument()
  })

  it('links publication and artifact provenance', () => {
    renderBundle()

    const publicationLinks = screen.getAllByRole('link', { name: /Open publication v2/ })
    expect(publicationLinks.length).toBeGreaterThan(0)
    expect(publicationLinks[0]).toHaveAttribute('href', '/knowledge/publications/pub-2')

    expect(
      screen.getByRole('link', { name: /Open source artifact: Playbook rule/ }),
    ).toHaveAttribute('href', '/knowledge/artifacts/a-playbook')
  })
})

describe('current-contract navigation', () => {
  it('navigates contract evidence to its source region and clause semantics', async () => {
    const { onNavigateToNode, onOpenClauseSemantics } = renderBundle()

    const contractLane = screen.getByRole('region', { name: 'Current contract' })
    await userEvent.click(
      within(contractLane).getByRole('button', { name: /View 6. Limitation of Liability in document/ }),
    )
    expect(onNavigateToNode).toHaveBeenCalledWith('node-6')

    await userEvent.click(
      within(contractLane).getAllByRole('button', { name: 'Open clause semantics' })[0],
    )
    expect(onOpenClauseSemantics).toHaveBeenCalledWith('cl-6')

    expect(within(contractLane).getByText('3. Fees')).toBeInTheDocument()
    expect(
      within(contractLane).getByText(/Semantic relationship: depends on/),
    ).toBeInTheDocument()
  })
})

describe('decision-boundary copy', () => {
  it('never uses decision or risk vocabulary', () => {
    renderBundle()

    const text = document.body.textContent ?? ''
    expect(text).not.toMatch(/severity|recommend|redline|acceptab|unsafe|\brisk\b/i)
  })
})
