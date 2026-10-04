import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { PolicyPositionView } from '../../lib/positionComparison'
import { PolicyPosition } from './PolicyPosition'

const policyEntry = (evidenceId: string, text: string) => ({ evidenceId, authority: 'Policy', publication: 2, text, facts: [] })

const view: PolicyPositionView = {
  columns: [
    {
      key: 'contract',
      label: 'Contract',
      entries: [
        {
          evidenceId: 'c-1',
          authority: 'Current contract',
          publication: null,
          text: null,
          facts: [{ title: 'Liability facts', rows: [{ field: 'liabilityCapMultiple', label: 'Liability cap', value: '3×' }] }],
        },
      ],
    },
    { key: 'preferred', label: 'Preferred', entries: [policyEntry('p-1', 'Cap at 12 months of fees.')] },
    { key: 'fallback', label: 'Fallback', entries: [policyEntry('p-2', 'Cap at 24 months of fees.')] },
  ],
  escalations: [{ evidenceId: 'p-1', publication: 2, trigger: 'Uncapped liability' }],
}

describe('PolicyPosition', () => {
  it('is headed "Policy position", never "Against your position"', () => {
    render(<PolicyPosition view={view} openable={new Set()} onOpenSource={() => undefined} />)
    expect(screen.getByText('Policy position')).toBeInTheDocument()
    expect(screen.queryByText('Against your position')).not.toBeInTheDocument()
  })

  it('draws one cell per slot with its own content', () => {
    render(<PolicyPosition view={view} openable={new Set()} onOpenSource={() => undefined} />)
    expect(screen.getByRole('group', { name: 'Contract' })).toHaveTextContent('3×Liability cap')
    expect(screen.getByRole('group', { name: 'Preferred' })).toHaveTextContent('Cap at 12 months of fees.')
    expect(screen.getByRole('group', { name: 'Fallback' })).toHaveTextContent('Cap at 24 months of fees.')
    expect(screen.getByText(/Escalate: Uncapped liability/)).toBeInTheDocument()
  })

  it('opens the exact source behind an item', async () => {
    const open = vi.fn()
    render(<PolicyPosition view={view} openable={new Set(['p-2'])} onOpenSource={open} />)
    await userEvent.click(within(screen.getByRole('group', { name: 'Fallback' })).getByRole('button', { name: 'Open source: Policy, publication 2' }))
    expect(open).toHaveBeenCalledWith('p-2')
  })

  it('offers no link to a source the finding does not hold', () => {
    render(<PolicyPosition view={view} openable={new Set()} onOpenSource={() => undefined} />)
    expect(screen.queryByRole('button', { name: /Open source/ })).not.toBeInTheDocument()
  })

  it('draws no grid when only an escalation condition exists', () => {
    render(<PolicyPosition view={{ columns: [], escalations: view.escalations }} openable={new Set()} onOpenSource={() => undefined} />)
    expect(screen.queryByRole('group')).not.toBeInTheDocument()
    expect(screen.getByText(/Escalate: Uncapped liability/)).toBeInTheDocument()
  })
})
