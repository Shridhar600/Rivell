/**
 * The normal build's demo hooks. The `@demo-hooks` alias resolves here unless Vite runs in
 * `demo` mode, so the product bundle contains no demo transport, data or chrome.
 */
export async function installDemo(): Promise<void> {
  // Nothing to install.
}

export function DemoChrome(): null {
  return null
}
