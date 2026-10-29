import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router/dom'
import { DemoChrome, installDemo } from '@demo-hooks'
import { AppErrorBoundary } from './app/AppErrorBoundary'
import { AppProviders } from './app/AppProviders'
import { router } from './app/router'
import './styles/m10b-tokens.css'
import './styles/m10b.css'
import './styles/m10b-lifecycle.css'
import './styles/m10b-knowledge.css'
import './styles/m10b-dialog.css'
import './styles/m10b-compile.css'

const found = document.getElementById('root')
if (!found) throw new Error('Root element #root not found')
const rootElement: HTMLElement = found

// The demo build installs its captured transport before the first request; the normal build's
// `installDemo` is a no-op and its `DemoChrome` renders nothing.
async function start() {
  await installDemo()
  createRoot(rootElement).render(
    <StrictMode>
      <AppProviders>
        <AppErrorBoundary>
          <RouterProvider router={router} />
          <DemoChrome />
        </AppErrorBoundary>
      </AppProviders>
    </StrictMode>,
  )
}

void start()
