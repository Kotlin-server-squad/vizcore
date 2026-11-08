import type { SessionHealth } from '@/types/health'

/** A clean live health, as of 14:02:31 UTC on the app's clock; override what a test is about. */
export function health(over: Partial<SessionHealth> = {}): SessionHealth {
  return {
    state: 'NO_PROBLEMS',
    asOf: { kind: 'WATERMARK', appNanos: 5_000_000_000, appEpochMs: Date.UTC(2026, 10, 7, 14, 2, 31), seq: 40 },
    asOfSeq: 40,
    sessionOrigin: 'REPORTED',
    problemCount: 0,
    failedRoundCount: 0,
    ungroupedFailureCount: 0,
    possiblyBlockedCount: 0,
    incidents: { items: [], total: 0 },
    possiblyBlocked: { status: 'EVALUATED' },
    observations: { longLived: { items: [], total: 0 }, longSuspended: { items: [], total: 0 } },
    counts: { app: 3, vizcore: 0, unknown: 0, untracked: 0 },
    failuresAtLeast: false,
    vizcoreConnection: { captured: 0, failed: 0, failures: { items: [], total: 0 } },
    transport: {},
    roundCount: 1,
    problemRoundCount: 0,
    problemRounds: { items: [], total: 0 },
    ...over,
  }
}
