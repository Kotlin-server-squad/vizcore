/**
 * JWT auth store (D-06): localStorage at rest + an in-memory copy as the
 * source of truth. The in-memory `token` is rehydrated from localStorage once
 * at module init so a page reload restores the session without re-reading
 * storage on every call. Framework-agnostic (no React import) so the
 * api-client choke point can consume it directly.
 *
 * Threat note (T-03-18): storing the JWT in localStorage is an accepted
 * XSS-vs-UX tradeoff (D-06, ASVS V3). The token is short-lived and is never
 * logged.
 */

const STORAGE_KEY = 'vizcore.jwt'

function readFromStorage(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    // localStorage may be unavailable (private mode, SSR). Treat as no token.
    return null
  }
}

// In-memory source of truth, rehydrated from localStorage at module init (D-06).
let token: string | null = readFromStorage()

/** Returns the current JWT, or null when auth is off / not signed in. */
export function getToken(): string | null {
  return token
}

/** Persists the JWT to both the in-memory copy and localStorage. */
export function setToken(jwt: string): void {
  token = jwt
  try {
    localStorage.setItem(STORAGE_KEY, jwt)
  } catch {
    // Storage write failure is non-fatal: the in-memory copy still authorizes
    // the current session; only persistence across reloads is lost.
  }
}

/** Clears the JWT from both the in-memory copy and localStorage. */
export function clearToken(): void {
  token = null
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Ignore — clearing the in-memory copy already de-authorizes the session.
  }
}
