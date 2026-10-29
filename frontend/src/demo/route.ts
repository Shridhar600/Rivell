/**
 * Tells the demo chrome when the router has moved. React Router's browser history does not
 * fire `popstate` for pushState navigation, so the demo build wraps the two methods that move
 * it. Demo-only: nothing imports this outside `install.ts`.
 */
export const DEMO_ROUTE_EVENT = 'rivell-demo-route'

let installed = false

export function installDemoRouteNotifications(): void {
  if (installed || typeof window === 'undefined') return
  installed = true
  const notify = () => {
    window.dispatchEvent(new Event(DEMO_ROUTE_EVENT))
  }
  const push = window.history.pushState.bind(window.history)
  const replace = window.history.replaceState.bind(window.history)
  window.history.pushState = (data, unused, url) => {
    push(data, unused, url)
    notify()
  }
  window.history.replaceState = (data, unused, url) => {
    replace(data, unused, url)
    notify()
  }
  window.addEventListener('popstate', notify)
}

export function subscribeDemoRoute(listener: () => void): () => void {
  if (typeof window === 'undefined') return () => undefined
  window.addEventListener(DEMO_ROUTE_EVENT, listener)
  window.addEventListener('popstate', listener)
  return () => {
    window.removeEventListener(DEMO_ROUTE_EVENT, listener)
    window.removeEventListener('popstate', listener)
  }
}
