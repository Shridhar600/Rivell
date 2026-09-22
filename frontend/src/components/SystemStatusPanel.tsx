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

  const { service, status } = query.data

  return (
    <p className="state state--success" data-testid="system-status">
      System {status ?? 'unknown'}
      {service ? ` — ${service}` : ''}
    </p>
  )
}
