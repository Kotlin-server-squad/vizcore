---
phase: 01-foundation-production-readiness
verified: 2026-06-12T13:25:00Z
status: passed
score: 19/19 must-haves verified
human_verification_result: "UAT round 3 PASSED 2026-06-12 (live browser, fresh 0-event gallery session): badge Connected without reload, events live-updated 23→40, Run button transitioned Running→Completed, Threads tab rendered 6 worker lanes with ASSIGNED chips + Dispatchers.Default card (pool size 6, thread IDs 56/65/78/79/81/83); curl on fresh 0-event session printed ': connected' with HTTP 200"
overrides_applied: 0
re_verification:
  previous_status: human_needed
  previous_score: 12/12
  gaps_closed:
    - "UAT round-2 gap 1: Threads tab never renders — FE typed a fictional {threads, dispatcherInfo} shape while the backend returns Map<threadId, ThreadEvent[]> (closed by 01-14)"
    - "UAT round-2 gap 2: SSE fatal-fails on a 0-event session first load — headers never flush, EventSource gets non-200 and never reconnects (closed by 01-15)"
  gaps_remaining: []
  regressions: []
human_verification:
  - test: "Browser UAT re-test of round-2 gap 1: start the full stack, gallery → Run a scenario, open the Threads tab during and after execution."
    expected: "Thread lanes render real thread names and ASSIGNED/RELEASED chips, and DispatcherOverview shows dispatcher cards — no permanent 'No thread activity data available yet' / 'No dispatcher data available' empty states."
    why_human: "The original failure was only observable in a real browser against the real backend wire shape. Unit + integration tests now feed the byte-for-byte UAT payload through the real hook and ThreadTimeline, but end-to-end rendering against the live backend needs browser confirmation."
  - test: "Browser UAT re-test of round-2 gap 2: gallery → Run on a FRESHLY created session (zero stored events). Watch the connection badge and event counter; separately `curl -N http://localhost:8080/api/sessions/{id}/stream` on a fresh 0-event session."
    expected: "Badge goes live without a page reload; event count updates as the scenario runs; Run button returns to runnable on completion. curl prints `: connected` immediately with HTTP 200. If the dev proxy drops the stream, the view recovers within ~1-8s (bounded retry) instead of sticking on 'Connecting…' forever."
    why_human: "The failure mode was a real-network header-flush/proxy interaction (curl HTTP 000, Vite proxy 500, EventSource fatal). The Ktor streaming test proves first-bytes flush in testApplication, but only a real browser through the real proxy proves the gallery → Run flow goes live on first load."
---

# Phase 01: Foundation Production Readiness — Verification Report (Re-verification, cycle 4)

**Phase Goal:** The running server is structurally sound and production-observable — it uses the authoritative core session classes with a bounded event store, exposes health, logging, CORS, and full metrics, and the four high-severity runtime defects from the 2026-06-11 audits are fixed (broken event serialization, validation crash, unreachable FAILED state, broken cancellation demo).
**Verified:** 2026-06-12T13:25:00Z
**Status:** passed (human verification completed in UAT round 3, 2026-06-12)
**Re-verification:** Yes — after gap-closure plans 01-14 (commits 329edb9, e71f476, da338c1, e5d7d24) and 01-15 (commits b5ef92c, f6fc7f7, d73eba2, 6c74c58) targeting the two UAT round-2 gaps.

---

## Scope of This Verification

Prior cycle scored 12/12 with status human_needed; UAT round 2 then found two major gaps in the browser: (1) the Threads tab never renders because the frontend typed a fictional `{threads, dispatcherInfo}` response while `GET /sessions/{id}/threads` actually returns `Map<String, List<ThreadEvent>>`, and (2) SSE fatal-fails on a 0-event session because the backend handler writes no bytes (headers never flush) and the frontend has no recovery from a fatal EventSource error. This cycle performs full multi-level verification of plans 01-14 and 01-15 must-haves against the current code, plus regression checks of 01-13's debounce/max-wait/invalidation fixes and the original 12 must-haves.

---

## Gap Closure Verification — Plan 01-14 (Threads-Tab Wire-Shape Alignment)

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Threads tab renders live thread-lane data when /threads returns the real Map wire shape | VERIFIED | `frontend/src/lib/thread-lanes.ts` (119 lines, pure, no React import) exports `buildThreadLanes(activity: ThreadActivity): ThreadActivityResponse` with full ASSIGNED/RELEASED segment pairing, global-span utilization (zero-span → 0, never NaN), unmatched-RELEASED tolerance. `api-client.ts:137` `getThreadActivity(...): Promise<ThreadActivity>` fetches the wire type. `SessionDetails.test.tsx:446` asserts `queryByText('No thread activity data available yet')` is null when the real hook + real ThreadTimeline receive the byte-for-byte UAT payload. |
| 2 | Dispatcher overview renders cards derived from the wire shape | VERIFIED | `DispatcherOverview.tsx:11` `const { dispatcherInfo, isLoading } = useThreadLanesByDispatcher(sessionId)`; empty-state guard at line 23 is `dispatcherInfo.length === 0`. `useThreadLanesByDispatcher` (use-thread-activity.ts:49-81) derives via `buildThreadLanes` with null-dispatcher lanes grouped under 'Unknown' (`(t.dispatcherId ?? 'Unknown') === dispatcher.id`). |
| 3 | No 'as unknown as' double cast remains in SessionDetails.tsx | VERIFIED | `grep "as unknown as" SessionDetails.tsx` exits 1. Line 408: `<ThreadTimeline threadActivity={threadActivity} />` — direct prop pass, typed end-to-end. `tsc --noEmit` exits 0. |
| 4 | Tests and MSW mocks exercise the real wire shape, not the fictional shape | VERIFIED | `mock-data.ts:138` `generateMockThreadActivityWire`; `mock-data.ts:272` `scenarioData.threadActivity` built from it; `handlers.ts:190` serves it on `/api/sessions/:sessionId/threads`. Fictional generators deleted (no `generateMockThreadActivity\b` producer remains). `thread-lanes.test.ts` (6 exact-value tests incl. `toBe(1)`/`toBe(0.75)` utilization); `use-thread-activity.test.ts` rewritten to wire fixtures. The epoch-vs-nanoTime `Date.now() * 1_000_000` comparison is gone from use-thread-activity.ts. |

### Required Artifacts (3-level + data-flow)

| Artifact | Exists | Substantive | Wired | Data Flows | Status |
|----------|--------|-------------|-------|------------|--------|
| `frontend/src/lib/thread-lanes.ts` | ✓ (119 lines) | ✓ full algorithm | ✓ imported by use-thread-activity.ts:14, used at :53 and :134 | ✓ derives from `useThreadActivity` query data (real fetch) | VERIFIED |
| `frontend/src/lib/api-client.ts` | ✓ | ✓ `Promise<ThreadActivity>` + `fetchJson<ThreadActivity>` (lines 137-138); zero `ThreadActivityResponse` references | ✓ called by useThreadActivity queryFn | ✓ real endpoint `/sessions/{id}/threads` | VERIFIED |
| `frontend/src/hooks/use-thread-activity.ts` | ✓ | ✓ all four hooks rewritten over the adapter | ✓ consumed by SessionDetails, DispatcherOverview, ThreadLanesView | ✓ query → adapter → render | VERIFIED |
| `frontend/src/lib/thread-lanes.test.ts` | ✓ (6 tests) | ✓ exact-value assertions (toEqual full objects, toBe numbers) | ✓ runs in suite | n/a | VERIFIED |

### Key Links

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| api-client.ts | types/api.ts ThreadActivity | getThreadActivity return type | WIRED | `Promise<ThreadActivity>` at line 137 |
| use-thread-activity.ts | thread-lanes.ts | buildThreadLanes import | WIRED | import line 14; uses at lines 53, 134 |
| SessionDetails.tsx | ThreadTimeline | direct prop pass without cast | WIRED | line 408, cast removed, tsc strict clean |

---

## Gap Closure Verification — Plan 01-15 (SSE First-Connect Flush + Retry + Dedup)

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | SSE stream on a zero-event session returns HTTP 200 with headers flushed immediately (`: connected` comment first) | VERIFIED | `SessionRoutes.kt:204` `send(ServerSentEvent(comments = "connected"))` — exactly one occurrence, textually before `coroutineScope {` (line 206) inside the sse handler's try block. `SseStreamTest.kt:282` test `SSE stream flushes headers immediately for a session with zero events` reads the live body via `prepareGet` + `bodyAsChannel` under `withContext(Dispatchers.Default)` + `withTimeout(5_000)`, asserting status OK, `text/event-stream`, and first non-blank line containing 'connected' (lines 298-327). Class run: 7 tests, 0 failures (`TEST-...SseStreamTest.xml`). |
| 2 | Fatal EventSource error triggers bounded exponential-backoff retry | VERIFIED | `use-event-stream.ts:24-38` constants `SSE_MAX_RETRIES=5`, `SSE_RETRY_BASE_DELAY_MS=1000`, `SSE_RETRY_MAX_DELAY_MS=8000`, `EVENTSOURCE_CLOSED=2` (no `EventSource.CLOSED` global reference). `connect()` closure (lines 77-234); fatal path at line 94 checks `readyState === EVENTSOURCE_CLOSED`, computes `Math.min(base * 2 ** (n-1), cap)` → 1000/2000/4000/8000/8000, schedules `setTimeout(connect, delay)`; budget exhaustion → 'Connection lost'; `onopen` resets budget (line 88); transient path (readyState ≠ 2) keeps pre-existing behavior and never creates a second EventSource (lines 113-117); cleanup cancels `retryTimerRef` (lines 240-243). `use-event-stream-retry.test.ts`: 6 exact-value fake-timer tests (retry, backoff doubling + cap + 6-call budget, open-resets-budget, dedup [1,2,3], transient no-recreate, unmount-cancels). |
| 3 | Reconnect replay does not duplicate previously-received events | VERIFIED | `maxSeqRef` high-water guard at the top of the per-event listener (use-event-stream.ts:171-177), before `setEvents` AND before the invalidation debounce; seq-less legacy frames bypass. Reset only on sessionId/enabled change (line 75), not on reconnect. Test 'does not duplicate replayed history after reconnect' asserts `events.map(e => e.seq)` equals exactly `[1, 2, 3]` after a replay of 1, 2, 3. |

### Required Artifacts

| Artifact | Status | Details |
|----------|--------|---------|
| `backend/.../routes/SessionRoutes.kt` | VERIFIED | `comments = "connected"` at line 204; session-not-found branch, gauge, subscribe-before-snapshot ordering, replay loop untouched |
| `frontend/src/hooks/use-event-stream.ts` | VERIFIED | All four constants + four refs (`retryCountRef`, `retryTimerRef`, `maxSeqRef`, `eventSourceRef`) present and used |
| `backend/.../routes/SseStreamTest.kt` | VERIFIED | 7 tests (2 new streaming tests), 0 failures on targeted run |
| `frontend/src/hooks/use-event-stream-retry.test.ts` | VERIFIED | New file, 6 value-asserting tests, passes in suite |

### Key Links

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| backend sse handler | EventSource first-connect success | `send(ServerSentEvent(comments = "connected"))` before replay loop | WIRED | SessionRoutes.kt:204, before coroutineScope at 206 |
| use-event-stream.ts onerror | new EventSource via apiClient.createEventSource | setTimeout-scheduled connect() with exponential backoff | WIRED | `SSE_RETRY_BASE_DELAY_MS` used in delay computation (line 105); connect() recreates via apiClient (line 81) |
| reconnect replay | events state | maxSeqRef guard skipping seq <= max | WIRED | guard before setEvents and before debounce (lines 171-177) |

---

## 01-13 Regression Check (Debounce / Max-Wait / Invalidation)

| Item | Status | Evidence |
|------|--------|----------|
| `INVALIDATION_DEBOUNCE_MS = 400` | VERIFIED | use-event-stream.ts:8 |
| `INVALIDATION_MAX_WAIT_MS = 1000` + firstInvalidationAtRef max-wait logic | VERIFIED | lines 16, 50, 196-211: immediate flush at `elapsed >= MAX_WAIT`, else `Math.min(DEBOUNCE, MAX_WAIT - elapsed)` |
| Both invalidations per flush (`['sessions', sessionId]` + `['thread-activity', sessionId]`) | VERIFIED | lines 190-193, inside flushInvalidation; dedup guard ensures duplicates do not burn invalidations |
| `refetchInterval: isLive ? 5000 : 2000` (no false branch) | VERIFIED | use-thread-activity.ts:37; queryKey `['thread-activity', sessionId]` at line 29 — exact match with the invalidation key |
| `SESSION_REFETCH_DEBOUNCE_MS = 500` / `SESSION_REFETCH_MAX_WAIT_MS = 1500` in SessionDetails.tsx | VERIFIED | lines 35, 43; firstSessionRefetchAtRef at 68; per-event cleanup explicitly does NOT reset the window ref (comment lines 129-132); `[streamEnabled]`-keyed teardown resets it (lines 142-150) |
| Debounce test expectations unchanged after seq fixture migration | VERIFIED | use-event-stream-debounce.test.ts now uses `eventPayload(seq)` with monotonic seqs (line 56); flush-count expectations identical; full suite green |

---

## Regression Checks — Original 12 Must-Haves

| # | Item | Status | Evidence |
|---|------|--------|----------|
| 1 | Terminal event ordering (01-09) | VERIFIED | `VizScope.kt:202, 225` `ctx.jobStateChanged(...)` in both branches; `VizScopeTerminalOrderingTest.kt` present |
| 2 | Timing durations in ms (01-10) | VERIFIED | `TimingAnalyzer.kt:9` `NANOS_PER_MILLI = 1_000_000L` |
| 3 | Event discriminator normalization (01-11) | VERIFIED | `utils.ts:45` `normalizeEvent`; SSE path normalizes at use-event-stream.ts:160 |
| 4 | No polling storm while SSE connected (01-12) | VERIFIED | Debounce + max-wait intact (above); UAT round 2 test 2 PASSED in browser ("the 88-req/3s storm is gone") |
| 5 | Completion-aware scenario controls (01-12) | VERIFIED | `TERMINAL_STATES` SessionDetails.tsx:28; allTerminal at line 81 |
| 6 | Connection badge on first SSE open (01-12) | VERIFIED | onopen → setIsConnected(true) (use-event-stream.ts:84-89); now also reachable on 0-event sessions thanks to 01-15 header flush |
| 7 | Failure Propagation copy states FAILED (01-12) | VERIFIED | unchanged; not in 01-14/01-15 delta |
| 8 | Bounded EventStore + regression tests (FND-01..03) | VERIFIED | `EventStore.kt:26` `maxEvents: Int = 10_000` with eviction at :42; `BoundedStoreWiringTest.kt`, `BoundedStoreRegressionTest.kt` present |
| 9 | Health, logging, CORS, OpenAPI, metrics (PROD-01..05) | VERIFIED | `HealthRoutes.kt`, `logback.xml` + `logback-prod.xml`, `cors:` in application.yaml:11, `MetricsWiring.kt` + `MetricsWiringTest.kt` |
| 10 | Targeted cancellation demo (FIX-04) | VERIFIED | `ScenarioRunner.kt:120` `child1.cancel()`; `CancellationScenarioRegressionTest.kt` present |
| 11 | Validation response shape (FIX-02, 01-02) | VERIFIED | prior cycles; full suites green this cycle |
| 12 | Event serialization (FIX-01, 01-01) | VERIFIED | prior cycles; SseStreamTest replay tests (which depend on working serialization) 7/7 green this cycle |

**No regressions found.**

---

## Requirements Coverage

| Requirement | Source Plan(s) | Status | Evidence |
|-------------|----------------|--------|----------|
| FIX-01 | 01-01, 01-11 | SATISFIED | regression-checked |
| FIX-02 | 01-02, 01-10, 01-12, 01-13, 01-14, 01-15 | SATISFIED | both gap-closure plans declare FIX-02; wire-shape + SSE first-connect fixes verified this cycle |
| FIX-03 | 01-01, 01-09, 01-12 | SATISFIED | regression-checked |
| FIX-04 | 01-01 | SATISFIED | code + test verified; REQUIREMENTS.md:23 checkbox still stale `[ ]` (documentation drift, info) |
| FND-01..03 | 01-01, 01-03, 01-07, 01-08 | SATISFIED | bounded store + wiring/regression tests present |
| PROD-01..05 | 01-04, 01-05, 01-06 | SATISFIED | health/logging/CORS/OpenAPI/metrics artifacts present |

All 12 phase requirement IDs accounted for across plan frontmatter; no orphaned requirements (REQUIREMENTS.md traceability rows 140-147 map FND/PROD to Phase 1 Complete).

---

## Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full frontend suite (single run) | `pnpm test` | 30 files, 269/269 pass | PASS |
| Strict typecheck end-to-end | `pnpm exec tsc --noEmit` | exit 0 | PASS |
| Targeted backend SSE class | `./gradlew :test --tests "...SseStreamTest"` | 7 tests, 0 failures | PASS |
| Double cast removed | `grep "as unknown as" SessionDetails.tsx` | exit 1 | PASS |
| Client speaks wire type only | `grep ThreadActivityResponse api-client.ts` | no hits | PASS |
| Connected comment before replay | grep SessionRoutes.kt | 1 hit at line 204, before coroutineScope (206) | PASS |
| All 9 claimed commits exist | `git log --oneline <hash>` x9 | 329edb9, e71f476, da338c1, e5d7d24, b5ef92c, f6fc7f7, d73eba2, 6c74c58, 2dbe577 all found | PASS |

## Probe Execution

No `scripts/*/tests/probe-*.sh` probes exist in this repository and no plan declares probes. SKIPPED (no probes defined).

---

## Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `frontend/src/hooks/use-event-stream.ts` | 171-177 | Seq watermark dedup assumes monotonic delivery; backend append/seq race can drop out-of-order events (REVIEW WR-13, new) | Warning | Edge-case event loss under parallel emitters; does not break the no-duplication truth; dedup tests only cover monotonic sequences |
| `backend/.../SessionRoutes.kt` | 211-213 | `Channel<VizEvent>(Channel.UNLIMITED)` live buffer removes per-connection backpressure (REVIEW WR-14, new) | Warning | Per-SSE-connection memory path; the bounded EventStore must-have (per-session store, maxEvents=10_000 with eviction) itself remains intact |
| `frontend/src/hooks/use-thread-activity.ts` | 50, 129 | Lane hooks call `useThreadActivity(sessionId)` with default `isLive=false` — DispatcherOverview re-arms the 2s poll on the shared key while live (REVIEW WR-15, new) | Warning | Defeats the 5s slow-poll optimization when the Threads tab is open; freshness truths still hold (more refetching, not less); not a storm (0.5 req/s) |
| `frontend/src/hooks/use-event-stream.ts` | 73-75 | maxSeqRef reset without clearing `events` on sessionId change / re-enable (REVIEW WR-16, new) | Warning | Pre-existing behavior (events were never cleared on these paths before 01-15 either); the within-session retry dedup truth holds |
| `.planning/REQUIREMENTS.md` | 23 | FIX-04 checkbox still `[ ]` despite implementation + regression test | Info | Documentation drift only (carried) |
| `frontend/src/hooks/use-event-stream.ts` | 163 | `(event as any).kind = eventType` fallback (WR-04, carried) | Warning | Pre-existing |
| `frontend/src/components/SessionDetails.tsx` | 153-157 | Auto-enable effect re-enables streaming whenever off (WR-05, carried) | Warning | Pre-existing, out of gap scope |

No `TBD`, `FIXME`, or `XXX` debt markers in any of the 16 files modified by plans 01-14/01-15 (grep exit 1).

---

## Human Verification Required

### 1. Threads Tab Renders Live Data (UAT round-2 gap 1 re-test)

**Test:** Start the full stack; gallery → Run a scenario; open the Threads tab during and after execution.
**Expected:** Thread lanes render real thread names and ASSIGNED/RELEASED chips; DispatcherOverview shows dispatcher cards; no permanent empty states.
**Why human:** The original failure was only observable in a real browser against the live backend wire shape. Tests now feed the byte-for-byte UAT payload through the real hook + ThreadTimeline, but end-to-end rendering against the live backend needs browser confirmation.

### 2. SSE Connects on First Load of a Fresh Session (UAT round-2 gap 2 re-test)

**Test:** Gallery → Run on a freshly created session (zero stored events); watch the badge and event counter. Separately, `curl -N` the fresh session's `/stream`.
**Expected:** Badge goes live without reload; events update during the run; Run button returns to runnable on completion. curl prints `: connected` immediately with HTTP 200. Transient proxy drops self-heal within ~1-8s.
**Why human:** The failure was a real-network header-flush/proxy interaction (curl HTTP 000 → Vite proxy 500 → EventSource fatal). testApplication proves first-bytes flush; only a real browser through the real proxy proves the gallery → Run flow goes live on first load.

---

## Gaps Summary

None. Both UAT round-2 gaps are closed in code with substantive, value-asserting tests:

- **Gap 1 (Threads tab) closed by 01-14:** the client now speaks the real `Map<threadId, ThreadEvent[]>` wire shape end-to-end; a pure `buildThreadLanes` adapter derives the lane/dispatcher view model; the double cast is gone; MSW serves the same shape the backend serializes, so the fictional shape can never ship green again. tsc strict + 269/269 tests confirm every consumer migrated.
- **Gap 2 (SSE first-connect) closed by 01-15:** the backend flushes HTTP 200 + `: connected` as the stream's first bytes even on 0-event sessions (streaming test proves it); the frontend self-heals fatal EventSource errors with bounded backoff (1s/2s/4s/8s/8s, 5 retries, budget reset on open) and a seq high-water-mark guard prevents the full-history replay from duplicating the live view.
- **01-13 not regressed:** all debounce/max-wait constants, both per-flush invalidations, the isLive refetch interval, and the SessionDetails teardown structure are byte-for-byte intact; the debounce test suite migrated to monotonic seqs with unchanged flush-count expectations.

Review warnings WR-13..WR-16 are real engineering debts but none falsifies a must-have truth (analysis in Anti-Patterns). Two real-browser confirmations remain — these are precisely the items only UAT round 3 can close, since both gaps were invisible to the previous automated verification and were caught only in the browser.

---

_Verified: 2026-06-12T12:55:00Z_
_Verifier: Claude (gsd-verifier)_
_Scope: Re-verification of UAT round-2 gap closure (plans 01-14, 01-15) plus 01-13 regression guard and regression checks of all 12 original must-haves and all 12 phase requirement IDs_
