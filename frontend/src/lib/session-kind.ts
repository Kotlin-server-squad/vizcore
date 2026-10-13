import type { SessionInfo } from '@/types/api'

/**
 * Session ids vizcore mints for its OWN runs (#145) — never a user's app:
 * - `scenario-<name>-<ts>`: a demo session created from the demo picker;
 * - `auto-<ts>`: a session the scenario runner/builder creates when a run is
 *   started without one (ScenarioRunnerRoutes);
 * - `api-session-<ts>`: a one-off session of the scenario API (VizScenarioRoutes).
 */
const VIZCORE_OWNED_ID = /^(scenario-|auto-\d+$|api-session-\d+$)/

/**
 * Client-side app vs demo derivation (Phase 08.5, PD-10).
 *
 * There is NO backend field for this distinction — `SessionInfo` is just
 * `{ sessionId, coroutineCount }` — so it is read from the id vizcore minted.
 * Everything else, including unknown/ambiguous ids, is treated as the user's
 * app ('live' for historical reasons: it means "a real app", not "streaming
 * right now"), because a real app must never masquerade as a demo.
 *
 * Pure function so it is unit-testable in isolation.
 */
export function deriveSessionKind(session: SessionInfo): 'live' | 'demo' {
  return VIZCORE_OWNED_ID.test(session.sessionId) ? 'demo' : 'live'
}

/** Trailing `-<epoch millis>` the backend appends to a named session's id. */
const TIMESTAMP_SUFFIX = /-\d{10,}$/

/**
 * A human name for a session, for headings and list rows (#145): the app or
 * scenario name the id was minted from, without the backend's timestamp
 * suffix. `order-service-1728383000000` → `order-service`.
 */
export function sessionDisplayName(sessionId: string): string {
  if (/^auto-\d+$/.test(sessionId) || /^api-session-\d+$/.test(sessionId)) return 'Scenario run'
  const base = sessionId.replace(TIMESTAMP_SUFFIX, '')
  if (base.startsWith('scenario-')) return base.slice('scenario-'.length).replace(/-/g, ' ') || 'Demo'
  return base || sessionId
}
