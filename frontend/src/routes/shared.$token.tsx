import { useEffect, useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { Spinner, Chip } from '@heroui/react'
import { FiEye } from 'react-icons/fi'
import { SessionDetails } from '@/components/SessionDetails'
import { EmptyState } from '@/components/EmptyState'
import { apiClient } from '@/lib/api-client'
import type { SharedSessionResult } from '@/types/share'

/**
 * `/shared/$token` — the public, read-only shared-session shell (D-09/D-10).
 *
 * This is the OPPOSITE of the authenticated `/sessions/$sessionId` route: a
 * STANDALONE shell with NO `Layout`/Navbar nav chrome. The route token is the
 * only credential — `getSharedSession` attaches no Bearer (Plan 05). The shared
 * `{session, events}` payload is seeded into the React Query cache under the
 * same keys the viewer hooks read (`['sessions', id]`, `['sessions', id,
 * 'events']`), so `SessionDetails` renders read-only with NO protected fetch —
 * REUSED behind the `readOnly` prop, never forked (D-10).
 *
 * The typed `SharedSessionResult` status branches drive the ADR-019 status
 * matrix copy (D-12): 410 expired / 404 not-found(or revoked) → the
 * "no longer available" empty state; 429 → the "Too many requests" copy.
 */

// UI-SPEC copy (locked) — see 03-UI-SPEC.md §`/shared/:token viewer shell`.
const COPY = {
  banner: 'Read-only shared view',
  unavailableHeading: 'This link is no longer available',
  unavailableBody: 'The share link may have expired or been revoked.',
  rateLimitedHeading: 'Too many requests',
  rateLimitedBody:
    'This shared link is receiving a lot of traffic. Try again in a minute.',
} as const

export const Route = createFileRoute('/shared/$token')({
  // Registered OUTSIDE the Layout nav chrome (standalone shell, D-10).
  component: SharedSessionPage,
})

export function SharedSessionPage() {
  const { token } = Route.useParams()
  const queryClient = useQueryClient()
  // `null` = still loading; otherwise the typed status result drives the branch.
  const [result, setResult] = useState<SharedSessionResult | null>(null)

  useEffect(() => {
    let cancelled = false

    async function load() {
      const res = await apiClient.getSharedSession(token)
      if (cancelled) return
      // On a valid result, seed the viewer hooks' cache from the public payload
      // so SessionDetails renders without any protected (Bearer-bearing) fetch.
      if (res.status === 'ok') {
        const sessionId = res.data.session.sessionId
        queryClient.setQueryData(['sessions', sessionId], res.data.session)
        queryClient.setQueryData(['sessions', sessionId, 'events'], res.data.events)
      }
      setResult(res)
    }

    void load()
    return () => {
      cancelled = true
    }
  }, [token, queryClient])

  if (result === null) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spinner size="lg" />
      </div>
    )
  }

  if (result.status === 'rate-limited') {
    return (
      <div className="container-custom py-16 pt-20">
        <EmptyState title={COPY.rateLimitedHeading} description={COPY.rateLimitedBody} />
      </div>
    )
  }

  if (result.status === 'expired' || result.status === 'not-found') {
    // 410 (expired) and 404 (unknown/revoked) collapse to the same generic
    // "no longer available" copy — no oracle distinguishing them (ADR-019).
    return (
      <div className="container-custom py-16 pt-20">
        <EmptyState title={COPY.unavailableHeading} description={COPY.unavailableBody} />
      </div>
    )
  }

  // Valid: render the full read-only viewer. No Layout/Navbar — standalone shell.
  // The subtle "Read-only shared view" banner sits above the viewer, subordinate
  // to the visualization canvas (UI-SPEC focal hierarchy).
  return (
    <div className="container-custom py-8 pt-20">
      <div className="mb-4 flex justify-end">
        <Chip color="default" variant="flat" startContent={<FiEye />}>
          {COPY.banner}
        </Chip>
      </div>
      <SessionDetails sessionId={result.data.session.sessionId} readOnly />
    </div>
  )
}
