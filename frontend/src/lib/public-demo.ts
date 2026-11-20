import { getToken } from '@/lib/auth-store'

/** Where a visitor of a public demo is sent to run vizcore themselves. */
export const RUN_YOUR_OWN_URL = 'https://github.com/Kotlin-server-squad/vizcore#quickstart'

/** The `role` claim of a vizcore JWT, or null when the token cannot be read. */
function roleOf(jwt: string): string | null {
  try {
    const payload = jwt.split('.')[1]
    if (!payload) return null
    const json: unknown = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
    const role = (json as { role?: unknown } | null)?.role
    return typeof role === 'string' ? role : null
  } catch {
    return null
  }
}

/**
 * Whether the page should offer only what a public demo's visitor can do
 * (`publicDemo` from /health, PUBLIC_DEMO_MODE on the server): no Connect, no
 * custom scenarios, no Reset, Clear or Share, which the server refuses with 403.
 *
 * Someone signed in as an admin keeps the whole page; the server decides in the
 * end, this only keeps the page from offering what would fail. False while the
 * capabilities are still loading, so a normal install never loses a button.
 */
export function isPublicDemoVisitor(capabilities: { publicDemo?: boolean } | undefined): boolean {
  if (capabilities?.publicDemo !== true) return false
  const token = getToken()
  return token === null || roleOf(token) !== 'ADMIN'
}
