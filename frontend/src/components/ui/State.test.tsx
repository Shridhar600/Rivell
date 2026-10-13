import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LoadingState } from './State'

describe('LoadingState', () => {
  it('ends its label with one ellipsis', () => {
    render(<LoadingState label="Loading findings" />)
    expect(screen.getByRole('status')).toHaveTextContent(/^Loading findings…$/)
  })

  it('never doubles an ellipsis the caller already wrote', () => {
    render(<LoadingState label="Loading review…" />)
    expect(screen.getByRole('status')).toHaveTextContent(/^Loading review…$/)
  })
})
