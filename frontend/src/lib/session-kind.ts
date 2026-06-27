import type { SessionInfo } from '@/types/api'

/**
 * Client-side LIVE vs DEMO derivation (Phase 08.5, PD-10).
 *
 * There is NO backend field for this distinction — `SessionInfo` is just
 * `{ sessionId, coroutineCount }`. A session is DEMO iff it is scenario-derived,
 * which the scenarios route signals by minting the session with a `scenario-`
 * name prefix (`createSession.mutateAsync(`scenario-${name}`)`). Everything else
 * — including unknown/ambiguous sessions — is treated as LIVE, because the live
 * app is the primary product and a live session must never masquerade as a demo.
 *
 * Pure function so it is unit-testable in isolation.
 */
export function deriveSessionKind(session: SessionInfo): 'live' | 'demo' {
  return session.sessionId.startsWith('scenario-') ? 'demo' : 'live'
}
