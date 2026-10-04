import { Link, useParams } from 'react-router'
import { isKnowledgeForbiddenError, KnowledgeApiError, useArtifactQuery, type ArtifactDetail } from '../../api/knowledge'
import { CompilationWorkspace } from '../../components/knowledge/compile/CompilationWorkspace'
import { CompileLayout, CompileTopBar } from '../../components/knowledge/compile/CompileLayout'
import { LinkButton } from '../../components/ui/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/ui/State'
import { warningLabel } from '../../lib/warnings'

function Frame({ artifact, children }: { artifact?: ArtifactDetail; children: React.ReactNode }) {
  return (
    <CompileLayout top={<CompileTopBar type={artifact?.artifactType ?? null} filename={artifact?.filename ?? 'Knowledge compilation'} />}>
      <div className="rv-kc-center">{children}</div>
    </CompileLayout>
  )
}

function FailedExtraction({ artifact }: { artifact: ArtifactDetail }) {
  return (
    <Frame artifact={artifact}>
      <div role="alert" className="rv-state rv-state--error">
        <h2 className="rv-state__title">Rivell could not extract this file</h2>
        <p className="rv-state__body">
          Nothing was compiled and nothing was published. The original is stored unchanged. Add a corrected file as new knowledge.
        </p>
        {artifact.parseWarnings.length > 0 ? (
          <ul className="rv-kc-warnings">
            {artifact.parseWarnings.map((warning) => (
              <li key={warning}>{warningLabel(warning)}</li>
            ))}
          </ul>
        ) : null}
        <LinkButton to={`/knowledge/sources/${artifact.id}`}>Open the source</LinkButton>{' '}
        <Link className="rv-lnk" to="/knowledge/sources?add=1">
          Add another file
        </Link>
      </div>
    </Frame>
  )
}

/**
 * The compilation route. It resolves what the source is before it shows a workspace: still being
 * extracted, failed (which is not published knowledge and offers no review), or ready to review.
 */
export function CompilationPage() {
  const { artifactId = '' } = useParams()
  const query = useArtifactQuery(artifactId)

  if (query.isPending) {
    return (
      <Frame>
        <LoadingState label="Loading the source" />
      </Frame>
    )
  }
  if (query.isError || !query.data) {
    const error = query.error
    if (isKnowledgeForbiddenError(error)) {
      return (
        <Frame>
          <ErrorState tone="neutral" title="Knowledge is not available to you" message="Your account does not have permission to review Knowledge. Ask an administrator for access." />
        </Frame>
      )
    }
    if (error instanceof KnowledgeApiError && error.status === 404) {
      return (
        <Frame>
          <EmptyState title="This source could not be found" message="It may have been opened from an old link." action={<LinkButton to="/knowledge/sources">All sources</LinkButton>} />
        </Frame>
      )
    }
    return (
      <Frame>
        <ErrorState title="The source could not be loaded" message={error?.message ?? 'Try again.'} onRetry={() => void query.refetch()} />
      </Frame>
    )
  }

  const artifact = query.data
  if (artifact.ingestStatus === 'FAILED') return <FailedExtraction artifact={artifact} />
  if (artifact.ingestStatus !== 'EXTRACTED') {
    return (
      <Frame artifact={artifact}>
        <div className="rv-state" role="status">
          <h2 className="rv-state__title">Rivell is extracting this file</h2>
          <p className="rv-state__body">Candidates appear here when extraction finishes. This page checks again by itself.</p>
        </div>
      </Frame>
    )
  }
  return <CompilationWorkspace key={artifact.id} artifact={artifact} />
}
