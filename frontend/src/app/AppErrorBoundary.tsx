import { Component, type ErrorInfo, type ReactNode } from 'react'

type Props = { children: ReactNode }
type State = { error: Error | null }

export class AppErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Only the error's own class name is logged. A message here can carry anything a thrown value
    // carried, and an unhandled error is not a place to widen what reaches the console.
    console.error('Unhandled application error', { name: error.name, componentStack: info.componentStack })
  }

  render() {
    if (this.state.error) {
      return (
        <main role="alert" className="rv-state rv-state--error">
          <h1 className="rv-state__title">Something went wrong</h1>
          <p className="rv-state__body">
            Rivell could not continue because of an unexpected error. Reload the page; if it keeps
            happening, contact an administrator.
          </p>
          <button type="button" className="rv-btn rv-btn--compact" onClick={() => window.location.reload()}>
            Reload
          </button>
        </main>
      )
    }
    return this.props.children
  }
}
