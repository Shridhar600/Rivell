/**
 * Demo build entry. The `@demo-hooks` alias resolves here only for `vite --mode demo`; the
 * normal build resolves `off.ts`, so none of this module, the transport or the capture is in
 * the product bundle. The marker below is what `npm run check:no-demo` searches for.
 */
import './demo.css'
import { installDemoRouteNotifications } from './route'
import { installDemoTransport } from './transport'
import { DemoChrome } from './DemoChrome'

export { DemoChrome }

export const DEMO_TRANSPORT_MARKER = 'rivell-demo-transport'

export async function installDemo(): Promise<void> {
  document.documentElement.dataset.demo = DEMO_TRANSPORT_MARKER
  installDemoRouteNotifications()
  await installDemoTransport()
}
