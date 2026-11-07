import { useEffect, useRef } from 'react'

/** A read the stream normally keeps fresh is re-read once it is this old (Part 7, P-1). */
export const QUIET_REFRESH_MS = 15_000
const CHECK_MS = 5_000

export interface QuietRead {
  /** When it was last read (epoch ms, React Query's `dataUpdatedAt`); 0 when never. */
  updatedAt: number
  /** When its read last failed (epoch ms, `errorUpdatedAt`); 0 when never. A read that never succeeded retries from it. */
  failedAt?: number
  refetch: () => void
}

/**
 * While the live stream is on, re-read any of [reads] that nothing refreshed for 15 s. The stream refreshes them after
 * events (and the health after watermark frames); a quiet or disconnected app sends neither — an unclean close sends
 * no frame at all — and the connection pill and "last data" would never move. A busy stream keeps every read fresh,
 * so this costs nothing there; a paused view refreshes nothing.
 */
export function useQuietRefresh({ enabled, reads }: { enabled: boolean; reads: readonly QuietRead[] }) {
  const latest = useRef(reads)
  latest.current = reads
  useEffect(() => {
    if (!enabled) return
    // A read that keeps failing is tried again no more often than a fresh one would be.
    const tried = new Map<number, number>()
    const timer = setInterval(() => {
      const now = Date.now()
      latest.current.forEach((read, i) => {
        const since = Math.max(read.updatedAt, read.failedAt ?? 0, tried.get(i) ?? 0)
        if (since > 0 && now - since >= QUIET_REFRESH_MS) {
          tried.set(i, now)
          read.refetch()
        }
      })
    }, CHECK_MS)
    return () => clearInterval(timer)
  }, [enabled])
}
