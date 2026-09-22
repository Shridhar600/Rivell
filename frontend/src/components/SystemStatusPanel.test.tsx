import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AppProviders } from '../app/AppProviders'
import { SystemStatusPanel } from './SystemStatusPanel'

function renderPanel() {
  return render(
    <AppProviders>
      <SystemStatusPanel />
    </AppProviders>,
  )
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const readyBody = { service: 'rivell-backend', status: 'ready' }

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('SystemStatusPanel', () => {
  it('shows loading state while the query is pending', async () => {
    let resolveFetch!: (value: Response) => void
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            resolveFetch = resolve
          }),
      ),
    )

    renderPanel()
    expect(screen.getByRole('status')).toHaveTextContent(/loading/i)
    resolveFetch(jsonResponse(readyBody))
    await waitFor(() =>
      expect(screen.getByText(/system ready/i)).toBeInTheDocument(),
    )
  })

  it('shows success status from the API', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(readyBody))),
    )

    renderPanel()
    expect(await screen.findByText(/system ready/i)).toBeInTheDocument()
    expect(screen.getByText(/rivell-backend/)).toBeInTheDocument()
  })

  it('shows error state and retries the query', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ message: 'boom' }, 500),
      )
      .mockResolvedValueOnce(jsonResponse(readyBody))
    vi.stubGlobal('fetch', fetchMock)

    renderPanel()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/unavailable|failed|error|boom/i)

    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(await screen.findByText(/system ready/i)).toBeInTheDocument()
  })
})
