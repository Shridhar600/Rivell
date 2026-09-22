import { useSystemStatusQuery } from '../api/systemStatus'
import { ErrorState } from './ErrorState'
import { LoadingState } from './LoadingState'

export function SystemStatusPanel() {
  const query = useSystemStatusQuery()

  if (query.isPending) {
    return <LoadingState label="Loading system status" />
  }

  if (query.isError) {
    return (
      <ErrorState
        message={`System status unavailable: ${query.error.message}`}
        onRetry={() => void query.refetch()}
      />
    )
  }

  return (
    <p className="state state--success" data-testid="system-status">
      System {query.data.status === 'UP' ? 'up' : query.data.status.toLowerCase()} —{' '}
      {query.data.status}
    </p>
  )
}
