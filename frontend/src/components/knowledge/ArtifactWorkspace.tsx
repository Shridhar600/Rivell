import { useArtifactQuery, useKnowledgeStructureQuery } from '../../api/knowledge'
import { useHeadingFocus } from '../../lib/useHeadingFocus'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'
import { ArtifactAssessmentPanel } from './ArtifactAssessmentPanel'
import { ArtifactHeader } from './ArtifactHeader'
import { ArtifactManifest } from './ArtifactManifest'
import { CandidateReviewWorkspace } from './CandidateReviewWorkspace'
import { ExtractionUnitsPanel } from './ExtractionUnitsPanel'
import { IngestionNotice } from './IngestionNotice'
import { SourceStructureView } from './SourceStructureView'

function SourceStructureSection({ artifactId }: { artifactId: string }) {
  const query = useKnowledgeStructureQuery(artifactId)

  return (
    <details className="disclosure">
      <summary>Source structure</summary>
      <div className="disclosure__body">
        {query.isPending ? (
          <LoadingState label="Loading source structure…" />
        ) : query.isError ? (
          <ErrorState
            message={query.error.message || 'Source structure unavailable.'}
            onRetry={() => void query.refetch()}
          />
        ) : (
          <SourceStructureView nodes={query.data} />
        )}
      </div>
    </details>
  )
}

export function ArtifactWorkspace({ artifactId }: { artifactId: string }) {
  const artifactQuery = useArtifactQuery(artifactId)
  const artifact = artifactQuery.data
  const headingRef = useHeadingFocus<HTMLHeadingElement>(artifact?.id)

  if (artifactQuery.isPending) {
    return <LoadingState label="Loading knowledge source…" />
  }

  if (artifactQuery.isError || !artifact) {
    return (
      <ErrorState
        message={artifactQuery.error?.message ?? 'Knowledge source unavailable.'}
        onRetry={() => void artifactQuery.refetch()}
      />
    )
  }

  const extracted = artifact.ingestStatus === 'EXTRACTED'

  return (
    <section aria-labelledby="artifact-heading" className="workspace">
      <ArtifactHeader artifact={artifact} headingRef={headingRef} />
      <ArtifactAssessmentPanel artifact={artifact} />
      <IngestionNotice
        status={artifact.ingestStatus}
        warnings={artifact.parseWarnings}
      />
      <ArtifactManifest artifact={artifact} />
      {extracted ? <CandidateReviewWorkspace artifact={artifact} /> : null}
      {extracted ? <SourceStructureSection artifactId={artifactId} /> : null}
      {extracted ? (
        <details className="disclosure">
          <summary>Extraction units</summary>
          <div className="disclosure__body">
            <ExtractionUnitsPanel artifactId={artifactId} />
          </div>
        </details>
      ) : null}
    </section>
  )
}
