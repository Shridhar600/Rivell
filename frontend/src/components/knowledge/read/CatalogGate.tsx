import type { ReactNode } from 'react'
import type { KnowledgeCatalogState } from '../../../api/knowledgeRead'
import type { Catalog } from '../../../lib/knowledgeCatalog'
import { ErrorState, LoadingState, UnavailableNotice } from '../../ui/State'
import { KnowledgeFrame, type Crumb } from './KnowledgeFrame'

interface CatalogGateProps {
  state: KnowledgeCatalogState
  crumbs: Crumb[]
  title: string
  children: (catalog: Catalog) => ReactNode
}

/**
 * Loading, failure and permission states shared by every read surface. They sit inside the real
 * page frame, so the shell and the page title stay put while the data arrives or fails.
 */
export function CatalogGate({ state, crumbs, title, children }: CatalogGateProps) {
  if (state.status === 'ready' && state.catalog) {
    return (
      <>
        {children(state.catalog)}
      </>
    )
  }
  return (
    <KnowledgeFrame crumbs={crumbs} title={title}>
      {state.status === 'pending' ? <LoadingState label="Loading Knowledge" /> : null}
      {state.status === 'error' && state.forbidden ? (
        <ErrorState
          tone="neutral"
          title="Knowledge is not available to you"
          message="Your account does not have permission to read published knowledge. Ask an administrator for access."
        />
      ) : null}
      {state.status === 'error' && !state.forbidden ? (
        <ErrorState
          title="Knowledge could not be loaded"
          message={state.error?.message ?? 'Try again.'}
          onRetry={state.refetch}
        />
      ) : null}
    </KnowledgeFrame>
  )
}

/** Shown above the content of a ready page when part of what was read may be missing. */
export function CatalogNotices({ state, catalog }: { state: KnowledgeCatalogState; catalog: Catalog }) {
  return (
    <>
      {catalog.unreadable.length > 0 ? (
        <UnavailableNotice title="Some sources could not be read.">
          {catalog.unreadable.length === 1 ? 'One source' : `${catalog.unreadable.length} sources`} could not be read, so
          what {catalog.unreadable.length === 1 ? 'it' : 'they'} published may be missing here.
          <button type="button" className="rv-lnk" onClick={state.refetch}>
            {' '}
            Try again
          </button>
        </UnavailableNotice>
      ) : null}
      {state.truncated ? (
        <p className="rv-note">Showing the newest 100 sources and versions.</p>
      ) : null}
    </>
  )
}
