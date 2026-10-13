import { Navigate, useParams } from 'react-router'

/** `/reviews/:contractId/evidence` was the v1 evidence inspector; it now opens the contract. */
export function EvidenceInspectorRedirect() {
  const { contractId = '' } = useParams()
  return <Navigate replace to={`/reviews/${encodeURIComponent(contractId)}`} />
}
