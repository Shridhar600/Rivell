import { useParams } from 'react-router'
import { ErrorState } from '../components/ErrorState'
import { EvidenceInspector } from '../components/evidence/EvidenceInspector'

export function EvidenceInspectorPage() {
  const { contractId = '' } = useParams()

  // Same guard ContractPage already uses. The contract read is inert without an id, so without this
  // the page would sit on a loading state for a request that can never succeed.
  if (!contractId) {
    return <ErrorState message="Missing contract id." />
  }

  return <EvidenceInspector contractId={contractId} />
}
