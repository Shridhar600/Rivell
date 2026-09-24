import { useParams } from 'react-router'
import { EvidenceInspector } from '../components/evidence/EvidenceInspector'

export function EvidenceInspectorPage() {
  const { contractId = '' } = useParams()
  return <EvidenceInspector contractId={contractId} />
}
