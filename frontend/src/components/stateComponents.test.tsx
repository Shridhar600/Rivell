import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EmptyState } from './EmptyState'
import { ErrorState } from './ErrorState'
import { LoadingState } from './LoadingState'

describe('shared state components', () => {
  it('announces loading status', () => {
    render(<LoadingState label="Loading system status" />)
    expect(screen.getByRole('status')).toHaveTextContent(
      'Loading system status',
    )
  })

  it('renders empty state message', () => {
    render(
      <EmptyState title="No reviews" message="Upload a contract to begin." />,
    )
    expect(
      screen.getByRole('heading', { name: 'No reviews' }),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Upload a contract to begin.'),
    ).toBeInTheDocument()
  })

  it('renders error state and invokes retry', async () => {
    const onRetry = vi.fn()
    render(<ErrorState message="Status unavailable" onRetry={onRetry} />)
    expect(screen.getByRole('alert')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
