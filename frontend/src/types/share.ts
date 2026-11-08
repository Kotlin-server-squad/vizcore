/**
 * Share DTOs — the typed surface Plan 06 (sharing UI) consumes. Mirrors the
 * backend `share/ShareDtos.kt` wire shapes (Plan 04) exactly. Defined here so
 * the api-client (the single /api choke point) carries the share methods and
 * Plan 06 does not have to re-open the client for a second concern.
 */

import type { SessionSnapshot, VizEvent } from '@/types/api'
import type { ChecksStatus, SessionHealth } from '@/types/health'

/** Allowed share-expiry codes (D-11). `never` → a null `expiresAt` server-side. */
export type ShareExpiry = '1d' | '7d' | '30d' | 'never'

/** `POST /api/sessions/{id}/share` 201 response. `expiresAt` is null when never-expiring. */
export interface CreateShareResponse {
  token: string
  url: string
  expiresAt: string | null
}

/** One row in the `GET /api/sessions/{id}/shares` owner listing. */
export interface ShareSummary {
  token: string
  expiresAt: string | null
  accessCount: number
  lastAccessedAt: string | null
}

/** `GET /api/shared/{token}` 200 body: read-only snapshot + full event history, all under one cut (spec C-6). */
export interface SharedSessionResponse {
  session: SessionSnapshot
  events: VizEvent[]
  /** The health at load: the shared view's reads bind to its `asOfSeq` (Part 8 M-11); the Problems tab presents it (Part 7). */
  health?: SessionHealth | null
  /** Why there is no health (`summaries-unavailable`, `history-trimmed`). */
  healthUnavailable?: string | null
  /** The health's cut id: the shared view's follow-up reads pass it as `asOfCut`. */
  healthCut?: string | null
  /** The last checks run at load (triage C-6). */
  checks?: ChecksStatus | null
}

/**
 * Typed result of fetching a shared session. The shared view (Plan 06) branches
 * on `status` rather than catching raw errors, so the 410/404/429 status matrix
 * (D-12, ADR-019) is explicit and exhaustive.
 */
export type SharedSessionResult =
  | { status: 'ok'; data: SharedSessionResponse }
  | { status: 'expired' } // 410 — the link has passed its expiry
  | { status: 'not-found' } // 404 — unknown OR revoked (indistinguishable by design)
  | { status: 'rate-limited' } // 429 — per-IP bucket exceeded
  | { status: 'server-error'; httpStatus: number } // 5xx — the server failed, not the link
  | { status: 'network-error' } // no response at all
  | { status: 'unauthorized' } // 401/403 — the server wants credentials for a public link
