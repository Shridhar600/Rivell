import { useParams } from 'react-router'
import { PublicationDetail } from '../components/knowledge/PublicationDetail'
import { ErrorState } from '../components/ErrorState'

export function KnowledgePublicationPage() {
  const { publicationId } = useParams()

  if (!publicationId) {
    return <ErrorState message="Missing publication id." />
  }

  return <PublicationDetail publicationId={publicationId} />
}
