import { useParams } from 'react-router'
import { ContractWorkspace } from '../components/ContractWorkspace'
import { ErrorState } from '../components/ErrorState'

export function ContractPage() {
  const { contractId } = useParams()

  if (!contractId) {
    return <ErrorState message="Missing contract id." />
  }

  return <ContractWorkspace contractId={contractId} />
}
