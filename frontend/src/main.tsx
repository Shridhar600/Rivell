import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router/dom'
import { AppErrorBoundary } from './app/AppErrorBoundary'
import { AppProviders } from './app/AppProviders'
import { router } from './app/router'
import './styles/tokens.css'
import './styles/m10b-tokens.css'
import './styles/m10b.css'

const rootElement = document.getElementById('root')
if (!rootElement) throw new Error('Root element #root not found')

createRoot(rootElement).render(
  <StrictMode>
    <AppProviders>
      <AppErrorBoundary>
        <RouterProvider router={router} />
      </AppErrorBoundary>
    </AppProviders>
  </StrictMode>,
)
