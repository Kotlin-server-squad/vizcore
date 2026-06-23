---
phase: 01-foundation-production-readiness
plan: 15
subsystem: api
tags: [sse, eventsource, ktor, react, retry, backoff, dedup, vitest, junit]

# Dependency graph
requires:
  - phase: 01-13
    provides: max-wait-capped SSE invalidation debounce in use-event-stream.ts (preserved byte-for-byte in behavior)
provides:
  - Backend SSE handler flushes status/headers immediately via a `: connected` comment frame (zero-event sessions get HTTP 200 on the wire)
  - Frontend useEventStream self-heals fatal EventSource errors with bounded exponential backoff (1s, 2s, 4s, 8s, 8s; SSE_MAX_RETRIES=5)
  - Seq high-water-mark replay dedup so full-history replay on reconnect (REVIEW WR-01) never duplicates the live view
  - Streaming SSE test pattern for Ktor testApplication (prepareGet + bodyAsChannel + readUTF8Line under Dispatchers.Default real-time withTimeout)
affects: [01-UAT re-test, phase-03 auth (SSE route), phase-04 sampling/batching]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "SSE comment frame on stream open to force header flush (invisible to browser EventSource listeners)"
    - "Numeric EVENTSOURCE_CLOSED=2 instead of EventSource global static (jsdom-safe)"
    - "Real-time withTimeout in testApplication via withContext(Dispatchers.Default) for live-stream reads"

key-files:
  created:
    - frontend/src/hooks/use-event-stream-retry.test.ts
  modified:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SseStreamTest.kt
    - frontend/src/hooks/use-event-stream.ts
    - frontend/src/hooks/use-event-stream-debounce.test.ts

key-decisions:
  - "Backend SSE stream tests read the live body via prepareGet + bodyAsChannel under withContext(Dispatchers.Default) — testApplication's virtual-time dispatcher fires withTimeout before the wall-clock server writes bytes (same convention as MetricsWiringTest)"
  - "Fatal-vs-transient split on readyState===2 only; undefined/0 readyState keeps the pre-existing 'Connection lost' contract and defers to native EventSource auto-reconnect (no double connections)"
  - "Retry budget resets on successful onopen, not per effect run — a flaky proxy gets 5 fresh attempts after every recovery"

patterns-established:
  - "SSE header flush: send(ServerSentEvent(comments = ...)) before any replay loop in long-lived SSE handlers"
  - "Replay dedup: maxSeqRef guard at the top of the per-event listener, before setEvents and before invalidation debounce"

requirements-completed: [FIX-02]

# Metrics
duration: ~14min
completed: 2026-06-12
---

# Phase 01 Plan 15: SSE First-Connect Flush + Fatal-Error Retry Summary

**Zero-event SSE streams now flush HTTP 200 + `: connected` immediately, and useEventStream self-heals fatal EventSource errors with bounded exponential backoff plus seq high-water-mark replay dedup (closes UAT round-2 gap 2)**

## Performance

- **Duration:** ~14 min
- **Started:** 2026-06-12T12:23:40Z
- **Completed:** 2026-06-12T12:37:30Z
- **Tasks:** 3
- **Files modified:** 5 (1 created)

## Accomplishments

- Backend: `send(ServerSentEvent(comments = "connected"))` as the first statement in the stream handler's try block forces the 200 status line and `text/event-stream` headers onto the wire even when the replay loop has nothing to write — the exact UAT failure (curl HTTP 000 → Vite proxy 500 → EventSource fatal, no reconnect) can no longer occur.
- Frontend: fatal EventSource errors (readyState CLOSED=2, terminal after a non-200) now retry via a `connect()` closure with delays 1000/2000/4000/8000/8000 ms (SSE_MAX_RETRIES=5, capped at SSE_RETRY_MAX_DELAY_MS=8000); a successful open resets the budget; transient errors keep today's behavior and never create a second connection.
- Replay integrity: `maxSeqRef` drops any replayed event with seq ≤ high-water mark before `setEvents` and before the invalidation debounce, so the backend's full-history replay per reconnect (REVIEW WR-01) cannot duplicate the live view or burn invalidations; seq-less legacy kebab-case frames bypass the guard.
- 01-13 behavior verifiably preserved: INVALIDATION_DEBOUNCE_MS=400, INVALIDATION_MAX_WAIT_MS=1000, firstInvalidationAtRef max-wait logic, and both queryKey invalidations untouched (grep gates + debounce suite green).
- Full regression: frontend 269/269 tests across 30 files; backend `./gradlew test` BUILD SUCCESSFUL (no HealthRoutesTest heap-pressure flake surfaced this run).

## Task Commits

Each task was committed atomically (TDD tasks have test + feat commits):

1. **Task 1: Backend — flush SSE headers immediately** — `b5ef92c` (test, RED) + `f6fc7f7` (feat, GREEN)
2. **Task 2: Frontend — bounded backoff reconnect + seq replay dedup** — `d73eba2` (test, RED) + `6c74c58` (feat, GREEN)
3. **Task 3: Full-suite regression run (both stacks)** — verification only, no commit

## Files Created/Modified

- `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt` — connected comment frame sent before the replay loop; session-not-found branch, gauge, subscribe-before-snapshot ordering, replay loop, and toSse() untouched
- `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SseStreamTest.kt` — two new streaming tests (7 total): zero-event header flush (status 200, text/event-stream, first non-blank line `: connected`) and comment-precedes-replay ordering
- `frontend/src/hooks/use-event-stream.ts` — connect() closure restructure; SSE_MAX_RETRIES / SSE_RETRY_BASE_DELAY_MS / SSE_RETRY_MAX_DELAY_MS / EVENTSOURCE_CLOSED constants; retryCountRef / retryTimerRef / maxSeqRef / eventSourceRef refs; cleanup cancels pending retry
- `frontend/src/hooks/use-event-stream-retry.test.ts` — six value-asserting fake-timer tests (retry after base delay, backoff doubling + cap + 6-call budget with before/after boundary assertions, open resets budget, replay dedup [1,2,3], transient no-recreate, unmount cancels retry)
- `frontend/src/hooks/use-event-stream-debounce.test.ts` — EVENT_PAYLOAD constant replaced with eventPayload(seq) helper; every simulated event now carries a unique monotonic seq; flush-count expectations unchanged

## Decisions Made

- Backend streaming tests wrap `withTimeout` in `withContext(Dispatchers.Default)` — `testApplication`'s virtual-time dispatcher advances past the timeout while the server streams on wall-clock time, producing false timeouts (and masking the real assertion failure in the ordering test). This follows the convention already documented in MetricsWiringTest.
- The "retrying" error state uses the exact string `'Connection lost — retrying'`; exhausted-budget and transient paths keep the pre-existing `'Connection lost'` contract so use-event-stream.test.ts passes unmodified.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Plan's test mechanics produced false timeouts under testApplication's virtual-time dispatcher**
- **Found during:** Task 1 (RED step)
- **Issue:** The plan prescribed `withTimeout(5_000)` around the streaming read, but `testApplication` runs the test body on a virtual-time dispatcher: the timeout fired before the wall-clock server wrote any bytes, making even the stored-event ordering test time out (wrong failure reason for RED).
- **Fix:** Wrapped both streaming reads in `withContext(Dispatchers.Default)` per the repo's MetricsWiringTest convention; RED then failed for the genuine reasons (zero-event timeout = no bytes flushed; ordering = missing comment).
- **Files modified:** backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SseStreamTest.kt
- **Verification:** RED failures matched the intended defects; GREEN run 7/7
- **Committed in:** b5ef92c (Task 1 RED commit)

**2. [Rule 1 - Bug] Acceptance grep `EventSource.CLOSED` matched a doc comment**
- **Found during:** Task 2 (GREEN step)
- **Issue:** The acceptance gate requires `grep -q "EventSource.CLOSED"` to exit 1; the constant's doc comment contained the literal phrase, which would fail the verifier despite the code correctly using the numeric constant.
- **Fix:** Reworded the comment ("The CLOSED readyState as a numeric literal…").
- **Files modified:** frontend/src/hooks/use-event-stream.ts
- **Verification:** grep exits 1; tests unaffected
- **Committed in:** 6c74c58 (Task 2 GREEN commit)

---

**Total deviations:** 2 auto-fixed (both Rule 1)
**Impact on plan:** Test-mechanics corrections only; no behavior or scope change.

## Issues Encountered

- `./gradlew test --tests ...` initially failed with "No tests found" because the filter also applied to the `coroutine-viz-core` subproject; scoped to `:test` for the root module.
- Strict TS (`noUncheckedIndexedAccess`) rejected the `latest()` array-index helper in the retry tests; switched to `.at(-1)` with an explicit throw.

## Known Stubs

None — no placeholder values, empty data sources, or TODO markers introduced.

## Threat Flags

None beyond the plan's threat model: the comment frame is a static string (T-01-15-02 accept), client reconnects are bounded by SSE_MAX_RETRIES + 8s cap (T-01-15-01 mitigate, implemented), and no new dependencies were installed.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- UAT gap 2 ready for human re-test: gallery → Run on a fresh session should go live without reload; `curl -N` on a 0-event session's `/stream` should print `: connected` immediately with HTTP 200.
- Server-side Last-Event-ID replay (avoiding full-history replay per reconnect) remains deferred work outside this gap (T-01-15-03).
- Phase 01 plan 15 of 15 complete — phase ready for re-verification.

## Self-Check: PASSED

- All created/modified files exist on disk (use-event-stream-retry.test.ts, SUMMARY.md verified)
- All four task commits found in git log: b5ef92c, f6fc7f7, d73eba2, 6c74c58

---
*Phase: 01-foundation-production-readiness*
*Completed: 2026-06-12*
