import { useParams } from 'react-router'
import { ArtifactWorkspace } from '../components/knowledge/ArtifactWorkspace'
import { ErrorState } from '../components/ErrorState'

export function KnowledgeArtifactPage() {
  const { artifactId } = useParams()

  if (!artifactId) {
    return <ErrorState message="Missing knowledge source id." />
  }

  return <ArtifactWorkspace artifactId={artifactId} />
}
