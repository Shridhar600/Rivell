import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { CommandPalette, type PaletteItem } from './CommandPalette'

function reviews(count: number): PaletteItem[] {
  return Array.from({ length: count }, (_, n) => ({ group: 'Reviews', label: `Contract ${n + 1}`, icon: null, run: () => {} }))
}

describe('the search menu', () => {
  it('says how many entries a group holds back, and that typing narrows them', () => {
    render(<CommandPalette open onClose={() => {}} items={reviews(9)} />)

    expect(screen.getAllByRole('option')).toHaveLength(6)
    expect(screen.getByText('3 more, type to narrow')).toBeInTheDocument()
  })

  it('shows no hint when everything fits', async () => {
    render(<CommandPalette open onClose={() => {}} items={reviews(9)} />)
    await userEvent.type(screen.getByRole('textbox', { name: 'Search' }), 'Contract')

    expect(screen.getAllByRole('option')).toHaveLength(9)
    expect(screen.queryByText(/more, type to narrow/)).not.toBeInTheDocument()
  })

  it('says which lists could not be loaded', () => {
    render(<CommandPalette open onClose={() => {}} items={reviews(2)} unavailable={['Playbooks and clauses']} />)

    expect(screen.getByRole('status')).toHaveTextContent('Playbooks and clauses could not be loaded. Try again later.')
  })
})
