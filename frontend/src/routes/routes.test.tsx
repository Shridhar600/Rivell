import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../app/AppProviders'
import { appRoutes } from '../app/router'

function renderRoute(path: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  return render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
}

describe('M0 top-level routes', () => {
  it('renders the Reviews workspace heading', () => {
    renderRoute('/reviews')
    expect(
      screen.getByRole('heading', { name: /reviews/i }),
    ).toBeInTheDocument()
  })

  it('renders the Knowledge workspace heading', () => {
    renderRoute('/knowledge')
    expect(
      screen.getByRole('heading', { name: /knowledge/i }),
    ).toBeInTheDocument()
  })

  it('exposes navigation links to both workspaces', () => {
    renderRoute('/reviews')
    expect(
      screen.getByRole('link', { name: /playbooks/i }),
    ).toBeInTheDocument()
  })
})
