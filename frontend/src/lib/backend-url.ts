/* global __VIZCORE_DEV_BACKEND_URL__ -- injected by vite.config.ts `define`, declared in vite-env.d.ts */

/**
 * The base URL of the vizcore server this SPA is talking to, as an app on the
 * same machine should address it (#126).
 *
 * - `VITE_VIZCORE_BACKEND_URL`, when set at build time, wins.
 * - In `pnpm dev` the SPA is served by Vite, which proxies `/api` to the real
 *   backend; that proxy target (injected by vite.config.ts) is the address the
 *   SDK must use, not the Vite dev server.
 * - Otherwise — the normal case, `java -jar vizcore.jar` serving the SPA — the
 *   page's own origin IS the backend.
 */
export function backendBaseUrl(): string {
  const configured = import.meta.env.VITE_VIZCORE_BACKEND_URL
  if (configured) return configured.replace(/\/+$/, '')
  if (import.meta.env.DEV && typeof __VIZCORE_DEV_BACKEND_URL__ === 'string') {
    return __VIZCORE_DEV_BACKEND_URL__.replace(/\/+$/, '')
  }
  return window.location.origin
}
