import { Link, useParams } from 'react-router'
import { isKnowledgeForbiddenError, KnowledgeApiError, useArtifactQuery, type ArtifactDetail } from '../../api/knowledge'
import { CompilationWorkspace } from '../../components/knowledge/compile/CompilationWorkspace'
import { CompileLayout, CompileTopBar } from '../../components/knowledge/compile/CompileLayout'
import { LinkButton } from '../../components/ui/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/ui/State'
import { artifactName } from '../../lib/knowledgeCatalog'
import { useHeadingFocus } from '../../lib/useHeadingFocus'
import { warningLabel } from '../../lib/warnings'

function Frame({ artifact, children }: { artifact?: ArtifactDetail; children: React.ReactNode }) {
  return (
    <CompileLayout top={<CompileTopBar type={artifact?.artifactType ?? null} name={artifact ? artifactName(artifact) : 'Knowledge draft'} />}>
      <div className="rv-kc-center">{children}</div>
    </CompileLayout>
  )
}

function FailedExtraction({ artifact }: { artifact: ArtifactDetail }) {
  const heading = useHeadingFocus<HTMLHeadingElement>(artifact.id)
  return (
    <Frame artifact={artifact}>
      <div role="alert" className="rv-state rv-state--error">
        <h2 className="rv-state__title" tabIndex={-1} ref={heading}>Rivell could not read this file</h2>
        <p className="rv-state__body">
          Nothing was published. The original is unchanged. Add a corrected file.
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

function ReadingSource({ artifact }: { artifact: ArtifactDetail }) {
  const heading = useHeadingFocus<HTMLHeadingElement>(artifact.id)
  return (
    <Frame artifact={artifact}>
      <div className="rv-state" role="status">
        <h2 className="rv-state__title" tabIndex={-1} ref={heading}>Rivell is reading this file</h2>
        <p className="rv-state__body">Entries appear when Rivell finishes reading the file. This page updates itself.</p>
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
        <LoadingState label="Loading the source" focus />
      </Frame>
    )
  }
  if (query.isError || !query.data) {
    const error = query.error
    if (isKnowledgeForbiddenError(error)) {
      return (
        <Frame>
          <ErrorState focus tone="neutral" title="Knowledge is not available to you" message="Your account does not have permission to review knowledge. Ask an administrator for access." />
        </Frame>
      )
    }
    if (error instanceof KnowledgeApiError && error.status === 404) {
      return (
        <Frame>
          <EmptyState focus title="This source could not be found" message="It may have been opened from an old link." action={<LinkButton to="/knowledge/sources">All sources</LinkButton>} />
        </Frame>
      )
    }
    return (
      <Frame>
        <ErrorState focus title="The source could not be loaded" message={error?.message ?? 'Try again.'} onRetry={() => void query.refetch()} />
      </Frame>
    )
  }

  const artifact = query.data
  if (artifact.ingestStatus === 'FAILED') return <FailedExtraction artifact={artifact} />
  if (artifact.ingestStatus !== 'EXTRACTED') {
    return <ReadingSource artifact={artifact} />
  }
  return <CompilationWorkspace key={artifact.id} artifact={artifact} />
}
