/* global __VIZCORE_DEV_BACKEND_URL__ -- injected by vite.config.ts `define`, declared in vite-env.d.ts */

/**
 * The base URL of the vizcore server this SPA is talking to, as an app on the
 * same machine should address it (#126).
 *
 * - `VITE_VIZCORE_BACKEND_URL`, when set at build time, wins.
 * - In `pnpm dev` the SPA is served by Vite, which proxies `/api` to the real
 *   backend; that proxy target (injected by vite.config.ts) is the address to
 *   give the SDK, since the Vite dev server lives only while `pnpm dev` runs.
 * - Otherwise the page's own origin works: either `java -jar vizcore.jar` serves
 *   the SPA and IS the backend, or the UI image's nginx proxies `/api`, the
 *   ingest WebSocket included, to it (#252).
 */
export function backendBaseUrl(): string {
  const configured = import.meta.env.VITE_VIZCORE_BACKEND_URL
  if (configured) return configured.replace(/\/+$/, '')
  if (import.meta.env.DEV && typeof __VIZCORE_DEV_BACKEND_URL__ === 'string') {
    return __VIZCORE_DEV_BACKEND_URL__.replace(/\/+$/, '')
  }
  return window.location.origin
}
