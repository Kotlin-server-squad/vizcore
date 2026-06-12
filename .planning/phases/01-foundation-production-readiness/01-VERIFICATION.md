---
phase: 01-foundation-production-readiness
verified: 2026-06-12T14:00:00Z
status: gaps_found
score: 10/12 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: human_needed
  previous_score: 11/11
  gaps_closed:
    - "Session validation passes on a clean scenario run (NoEventsAfterTerminal — 01-09)"
    - "Validation Timing Report shows durations in correct units (01-10)"
    - "Jobs tab shows job states for sessions with JobStateChanged events (01-11)"
    - "Scenario Controls show completed state after terminal coroutines (01-12)"
    - "Connection badge shows Connected on first SSE connect (01-12)"
    - "Failure Propagation copy states parent becomes FAILED (01-12)"
  gaps_remaining:
    - "Thread-activity query never invalidated during live streaming (CR-01)"
    - "Trailing-edge debounce with no max-wait starves session refresh under sustained event streams (CR-02)"
  regressions: []
gaps:
  - truth: "While SSE is connected, the session page does not poll GET /api/sessions/{id} and /threads at high frequency (no ~88 requests in ~3s)"
    status: partial
    reason: "The per-event invalidation storm is eliminated (400ms trailing-edge debounce in use-event-stream.ts). However two live-mode freshness bugs introduced by plan 01-12 mean the fix is incomplete: (1) CR-01: useThreadActivity disables its 2s poll when isLive=true on the stated premise that SSE invalidations refresh the Threads view, but invalidateQueries in use-event-stream only targets ['sessions', sessionId] — the ['thread-activity', sessionId] key is never invalidated anywhere in the repo (repo-wide grep confirms zero hits). The Threads tab therefore freezes for the entire live session. (2) CR-02: Both new debounces (400ms in use-event-stream, 500ms in SessionDetails) are pure trailing-edge with no max-wait cap — under a sustained stream whose inter-event gap is less than 400ms the timer is perpetually reset so the session snapshot and the completion-aware scenario button refresh never."
    artifacts:
      - path: "frontend/src/hooks/use-event-stream.ts"
        issue: "invalidateQueries targets ['sessions', sessionId] only — ['thread-activity', sessionId] is never invalidated (CR-01). Trailing-edge debounce at line 96 has no max-wait cap (CR-02)."
      - path: "frontend/src/hooks/use-thread-activity.ts"
        issue: "refetchInterval: false when isLive=true (line 30) relies on an invalidation that never fires. Threads tab goes stale for the full live session duration."
      - path: "frontend/src/components/SessionDetails.tsx"
        issue: "500ms trailing-edge debounce (lines 87-104) has no max-wait cap; under sustained event stream the session snapshot refetch never fires."
    missing:
      - "Add queryClient.invalidateQueries({ queryKey: ['thread-activity', sessionId] }) inside the same setTimeout block in use-event-stream.ts (or restore a slow fallback poll: refetchInterval: isLive ? 5000 : 2000 in use-thread-activity.ts)"
      - "Add max-wait cap to both trailing-edge debounces (track firstEventAt, flush immediately when Date.now() - firstEventAt >= MAX_WAIT_MS) so session refresh cannot be starved indefinitely by a sustained event stream"
---

# Phase 01: Foundation Production Readiness — Verification Report (Re-verification)

**Phase Goal:** The running server is structurally sound and production-observable — it uses the authoritative core session classes with a bounded event store, exposes health, logging, CORS, and full metrics, and the four high-severity runtime defects from the 2026-06-11 audits are fixed (broken event serialization, validation crash, unreachable FAILED state, broken cancellation demo).
**Verified:** 2026-06-12T14:00:00Z
**Status:** gaps_found
**Re-verification:** Yes — after gap-closure plans 01-09..01-12. Prior cycle score was 11/11 human_needed (pending browser UAT). This re-verification covers the gap-closure cycle triggered by 01-UAT.md (7 runtime gaps).

---

## Scope of This Verification

Plans 01-01..01-08 were verified in the prior cycle (score 11/11; status human_needed, pending browser UAT). The UAT (01-UAT.md) passed 1/2 browser tests and logged 7 runtime gaps, which drove gap-closure plans 01-09..01-12. A subsequent code review (01-REVIEW.md) found 2 critical and 6 warning findings in those plans. This report verifies all 12 gap-closure must-haves for plans 01-09 through 01-12 and incorporates CR-01 and CR-02 as blockers where they directly contradict must-have truths.

---

## Plan 01-09: VizScope Terminal Event Ordering (FIX-03)

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A clean Exception-Handling scenario run produces zero NoEventsAfterTerminal findings from the app's own instrumentation | VERIFIED | VizScope.kt lines 197-213: JobStateChanged constructed (seq assigned) before coroutineFailed in the Failed branch; same pattern in Cancelled branch (lines 220-236). NoEventsAfterTerminalRule cannot flag self-generated instrumentation events. |
| 2 | For every coroutine, the terminal lifecycle event has the highest seq among that coroutine's events | VERIFIED | Both invokeOnCompletion branches in VizScope.kt now send JobStateChanged before the terminal event. EventContext.nextSeq() is called at construction time via AtomicLong so construction order is seq order. |
| 3 | JobStateChanged for a terminating coroutine is emitted before that coroutine's terminal lifecycle event | VERIFIED | Code reads session.send(ctx.jobStateChanged(...)) then session.send(ctx.coroutineFailed(...)) in Failed branch, equivalently in Cancelled branch. Three regression tests in VizScopeTerminalOrderingTest.kt assert the invariant; all commits confirmed in git log. |

**Score: 3/3**

### Required Artifacts

| Artifact | Status | Details |
|----------|--------|---------|
| `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt` | VERIFIED | Modified — JobStateChanged before terminal event in both Failed and Cancelled branches. vizAsync unchanged (no JobStateChanged there, already terminal-last). |
| `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeTerminalOrderingTest.kt` | VERIFIED | Exists (7.2K). Substantive: imports NoEventsAfterTerminalRule, CoroutineFailed, CoroutineCancelled, JobStateChanged; asserts maxByOrNull { it.seq }.kind and validator returns zero findings. |

### Key Link Verification

| From | To | Via | Status |
|------|----|-----|--------|
| VizScope.invokeOnCompletion Failed/Cancelled branches | terminal event seq ordering | jobStateChanged constructed before coroutineFailed/coroutineCancelled in source order | WIRED |

### Commits Verified

| Commit | Description |
|--------|-------------|
| b8c5ee9 | test(01-09): add failing test for terminal-last event ordering (RED) |
| a7ea515 | feat(01-09): reorder VizScope terminal emission so JobStateChanged precedes terminal event (GREEN) |

**Note on WR-01 (Code Review):** The Failed branch emits isActive=false, isCompleted=false, isCancelled=false — a state no kotlinx Job can occupy (a failed job has isCompleted=true, isCancelled=true). Warning-level semantic defect, not a blocker for the ordering/validator must-haves. Logged as WR-01.

---

## Plan 01-10: Timing Unit Conversion ns to ms (FIX-02)

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | POST /api/validate returns timing durations in milliseconds matching the documented frontend BackendTimingReport contract | VERIFIED | TimingAnalyzer.kt: NANOS_PER_MILLI = 1_000_000L used at every conversion site (coroutineDurations line 64, suspensions line 81, totalDuration line 96). KDoc explicitly states milliseconds. |
| 2 | A ~5s scenario yields a totalDuration on the order of thousands of ms, not ~10^11 | VERIFIED | Magnitude-sanity test (TimingAnalyzerTest.kt line 187): assertEquals(5000L, report.totalDuration) plus guard assertTrue(report.totalDuration < 1_000_000L). |
| 3 | The frontend TimingReportView shows a plausible duration for a short scenario without code changes on the FE side | VERIFIED | No frontend files modified. TimingReport field types unchanged; only values changed from ns to ms. The existing formatMs() in TimingReportView now receives correct ms values. |

**Score: 3/3**

### Required Artifacts

| Artifact | Status | Details |
|----------|--------|---------|
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzer.kt` | VERIFIED | Contains NANOS_PER_MILLI, division by NANOS_PER_MILLI at 3 sites, KDoc documenting milliseconds. |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzerTest.kt` | VERIFIED | Magnitude-sanity test asserts 5000L for 5s scenario and guards against ns-range regression. 6 tests total, all asserting ms-scale expectations. |

### Key Link Verification

| From | To | Via | Status |
|------|----|-----|--------|
| ValidationRoutes POST /api/validate/session/{id} | TimingAnalyzer.analyze | Result serialized directly into response; TimingReport field types unchanged | WIRED |

### Commits Verified

| Commit | Description |
|--------|-------------|
| 6f348b8 | test(01-10): add failing test for ms-scale timing conversion (RED) |
| c643280 | feat(01-10): convert TimingAnalyzer durations from ns to ms at boundary (GREEN) |
| f8defad | test(01-10): add magnitude-sanity test asserting ms-scale durations |

---

## Plan 01-11: Event Discriminator Normalization (Jobs Tab)

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A session whose REST /events payload contains JobStateChanged events shows a Jobs tab with a non-zero count | VERIFIED | normalizeEvent correctly maps {type:'JobStateChanged'} to {kind:'JobStateChanged'} (utils.ts lines 51-55). SessionDetails.jobStates derives from allEvents normalized on both REST and SSE paths. event-discriminator.test.ts confirms JOB_EVENT_KINDS.has(kind) is true. |
| 2 | Every FE consumer of event.kind receives a populated kind for both REST-derived and SSE-derived events | VERIFIED | Audit table in 01-11-SUMMARY.md covers all consumers; all normalize via getSessionEvents → normalizeEvents or useEventStream → normalizeEvent. use-event-stream.ts line 84 calls normalizeEvent on every SSE payload. |
| 3 | normalizeEvent maps the backend discriminator field 'type' to 'kind' for all 17 backend event type values, with no kind left undefined | VERIFIED | event-discriminator.test.ts lines 95-111 iterates EVENT_TYPE_TO_KIND keys and asserts every result has a defined non-empty kind. utils.ts line 38 has a fallback (|| type as VizEventKind) for unmapped types. |

**Score: 3/3**

### Required Artifacts

| Artifact | Status | Details |
|----------|--------|---------|
| `frontend/src/lib/utils.ts` | VERIFIED | normalizeEvent confirmed correct: maps type to kind, deletes type, preserves already-normalized events. |
| `frontend/src/lib/event-discriminator.test.ts` | VERIFIED | Exists (8.1K). Substantive: drives raw {type:'JobStateChanged'} payloads (no kind field) through normalizeEvent and normalizeEvents; asserts kind, JOB_EVENT_KINDS membership, type deletion, all-17-types coverage. |

**Note on IN-07 (Code Review):** The backend has 32+ event types; the test claims "all 17." Unmapped types rely on the identity fallback in eventTypeToKind. The fallback is correct; the description is imprecise. Logged as IN-07; not a blocker.

**Note on WR-04 (Code Review):** (event as any).kind = eventType in use-event-stream.ts:87 uses as any and assigns the raw SSE event name rather than the canonical kebab-case kind. Warning-level; logged as WR-04.

---

## Plan 01-12: Session Page Polish

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | While SSE is connected, the session page does not poll GET /api/sessions/{id} and /threads at high frequency (no ~88 requests in ~3s) | FAILED | The per-event invalidation storm is eliminated (debounce confirmed). However: (a) CR-01: useThreadActivity.ts:30 sets refetchInterval:false when isLive=true; the Threads tab now depends on SSE-triggered cache invalidation for ['thread-activity', sessionId] — but no code in the repo ever invalidates that key. Repo-wide grep of all invalidateQueries calls: zero hits for 'thread-activity'. Threads tab freezes for entire live session. (b) CR-02: Both debounces (use-event-stream.ts:96, SessionDetails.tsx:93) are pure trailing-edge with no max-wait; under sustained event stream the session snapshot never refreshes. No maxWait, MAX_WAIT, firstEvent, leadingEdge, or throttle found in either file. |
| 2 | After all coroutines reach a terminal state, the Scenario Controls show a runnable or explicit Completed state instead of a permanently disabled 'Scenario Running' button | VERIFIED | SessionDetails.tsx: TERMINAL_STATES = Set([COMPLETED, CANCELLED, FAILED]); scenarioState useMemo derives notStarted/running/completed; renders disabled 'Scenario Completed' button (success color) when allTerminal=true. SessionDetails.test.tsx line 262+ asserts button transition. |
| 3 | The connection badge shows Connected once the EventSource is open on the first session load, without requiring a reload | VERIFIED | use-event-stream.ts:34: eventSource.onopen = () => { setIsConnected(true) }. SessionDetails.tsx:222: badge renders 'Connected' when isConnected=true. Competing autoRefresh state removed. |
| 4 | The Structured Concurrency Failure Propagation copy states the parent becomes FAILED (matching the rendered tree), not CANCELLED | VERIFIED | StructuredConcurrencyInfo.tsx:57: "which gets FAILED (completes exceptionally)". StructuredConcurrencyInfo.test.tsx asserts /parent.*which gets FAILED/i present and /which gets CANCELLED/i absent. |

**Score: 1/4 (truth #1 FAILED; truths #2, #3, #4 VERIFIED)**

### Required Artifacts

| Artifact | Status | Details |
|----------|--------|---------|
| `frontend/src/components/SessionDetails.tsx` | PARTIAL | Scenario controls and badge fixes verified. Debounce exists but CR-01 and CR-02 make the polling-storm truth fail. |
| `frontend/src/components/StructuredConcurrencyInfo.tsx` | VERIFIED | Contains "Failure Propagation" heading, "which gets FAILED (completes exceptionally)" wording. |

### Key Link Verification

| From | To | Via | Status |
|------|----|-----|--------|
| use-event-stream onopen | isConnected badge | setIsConnected(true) at eventSource.onopen | WIRED |
| SSE liveEvents arrival | react-query invalidation of sessions query | Debounced invalidateQueries(['sessions', sessionId]) | WIRED |
| SSE liveEvents arrival | thread-activity query refresh | None — ['thread-activity', sessionId] never invalidated anywhere | NOT_WIRED (CR-01 BLOCKER) |
| Sustained event stream | session snapshot refresh guarantee | No max-wait cap; trailing-edge only | PARTIAL (CR-02 BLOCKER) |

### Commits Verified

| Commit | Description |
|--------|-------------|
| 85ac017 | test(01-12): add RED tests for debounced SSE invalidation and polling gate |
| bcf229d | feat(01-12): stop polling storm, fix connection badge (Task 1) |
| 9d56fa7 | test(01-12): add RED tests for completion-aware scenario button and FAILED copy |
| 8023344 | feat(01-12): completion-aware scenario controls, corrected concurrency copy (Task 2) |

---

## Requirements Coverage

| Requirement | Source Plan(s) | Description | Status | Evidence |
|-------------|----------------|-------------|--------|----------|
| FIX-01 | 01-11 | VizEvent subclasses registered in SerializersModule — SSE + GET /events work end-to-end | SATISFIED | Discriminator normalization (01-11) closes Jobs-tab gap; event-discriminator test proves end-to-end mapping; underlying serialization fix confirmed in prior cycle |
| FIX-02 | 01-10, 01-12 | Frontend validation feature consumes real backend response shape — Run Validation renders results and timing | SATISFIED | TimingAnalyzer ns to ms fix (01-10) closes the timing unit bug confirmed in UAT |
| FIX-03 | 01-09 | VizScope classifies terminal state by cause type — throwing coroutine emits CoroutineFailed | SATISFIED | Terminal ordering fix in VizScope.kt; NoEventsAfterTerminalRule produces zero findings for instrumentation's own output |
| FIX-04 | (none) | Cancellation scenario performs targeted child cancel | OPEN | FIX-04 not claimed in any gap plan; still marked [ ] in REQUIREMENTS.md |
| FND-01 | 01-01 | Running backend uses single authoritative coroutine-viz-core session classes | SATISFIED | Prior verification cycle |
| FND-02 | 01-01 | EventStore is bounded (maxEvents) variant | SATISFIED | Prior verification cycle |
| FND-03 | 01-01 | Regression test asserts in-use EventStore is bounded | SATISFIED | Prior verification cycle |
| PROD-01 | 01-03 | GET /api/health returns HealthStatus | SATISFIED | Prior verification cycle |
| PROD-02 | 01-04 | Logging uses env-selectable dev/prod Logback profiles | SATISFIED | Prior verification cycle |
| PROD-03 | 01-05 | CORS origins/methods read from config | SATISFIED | Prior verification cycle |
| PROD-04 | 01-06 | All endpoints have OpenAPI descriptions | SATISFIED | Prior verification cycle |
| PROD-05 | 01-07 | Micrometer exposes full ADR-020 metric set | SATISFIED | Prior verification cycle |

FIX-04 is an outstanding open requirement not addressed by any plan in this phase. It is not an orphan — it is explicitly deferred/unplanned.

---

## Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `frontend/src/hooks/use-thread-activity.ts` | 30 | refetchInterval:false when isLive=true; promised SSE invalidation never fires | BLOCKER (CR-01) | Threads tab frozen for entire live session |
| `frontend/src/hooks/use-event-stream.ts` | 96-99 | Trailing-edge debounce with no max-wait on invalidateQueries | BLOCKER (CR-02) | Session snapshot never refreshes under sustained event stream |
| `frontend/src/components/SessionDetails.tsx` | 87-104 | Trailing-edge debounce with no max-wait on refetch | BLOCKER (CR-02) | Same starvation risk for component-level session refresh |
| `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt` | 201-212 | Failed branch emits isActive=false, isCompleted=false, isCancelled=false — impossible Job state | Warning (WR-01) | Jobs panel renders incoherent final state for failed coroutines |
| `frontend/src/hooks/use-event-stream.ts` | 87 | (event as any).kind = eventType — as any; assigns raw SSE event name not canonical kind | Warning (WR-04) | Consumers filtering on canonical kebab-case kinds may silently miss events via fallback path |
| `frontend/src/components/SessionDetails.tsx` | 107-111 | Auto-enable effect fires on every render where streaming is off — toggle cannot be disabled | Warning (WR-05) | User cannot turn off live streaming; clicking toggle destroys accumulated live events |
| `frontend/src/components/SessionDetails.tsx` | 119 | setTimeout(() => refetch(), 500) untracked — fires after unmount | Info (IN-05) | Memory-leak risk; redundant with mutation invalidation |

No TBD, FIXME, or XXX debt markers found in files modified by plans 01-09..01-12.

---

## Behavioral Spot-Checks

| Behavior | Check | Result |
|----------|-------|--------|
| VizScopeTerminalOrderingTest exists and is substantive | ls + content grep | PASS — 3 substantive tests covering Failed, Cancelled, and validator-clean paths |
| TimingAnalyzerTest magnitude-sanity assertion | Content grep | PASS — assertEquals(5000L, report.totalDuration) plus ns-range guard |
| event-discriminator.test.ts drives raw {type} payloads | Content grep | PASS — exercises real wire shape {type:'JobStateChanged'} without pre-normalization |
| SessionDetails test covers three-state button | Content grep | PASS — tests notStarted / running / completed button states |
| StructuredConcurrencyInfo test asserts FAILED wording | Content grep | PASS — asserts /parent.*which gets FAILED/i and rejects /which gets CANCELLED/i |
| invalidateQueries covers thread-activity key | Repo-wide grep | FAIL — zero hits for invalidateQueries targeting 'thread-activity' anywhere in frontend/src |
| Debounce has max-wait cap | Source grep | FAIL — no maxWait, MAX_WAIT, firstEvent, leadingEdge, or throttle in either debounce file |

---

## Human Verification Required

### 1. Threads Tab Freshness During Live Session (CR-01)

**Test:** Start the full stack; open any scenario session; enable live streaming; run the scenario. Observe the Threads tab (Thread Lanes view) during and after the scenario run.
**Expected:** Thread lane data updates as coroutines move between threads during execution.
**Why human:** The code verifiably never invalidates ['thread-activity', sessionId]. The tab will show the initial snapshot and freeze. This test will confirm CR-01's impact is observable.

### 2. Session Snapshot Freshness Under Sustained Stream (CR-02)

**Test:** Run a scenario that emits events continuously at a high rate (sub-400ms intervals between events). Open Network DevTools; observe whether GET /api/sessions/{id} is called at least every ~500ms during execution.
**Expected:** The session header coroutine count and Scenario Controls update regularly during the run.
**Why human:** Under a sustained event stream the trailing-edge debounce is perpetually reset. Human observation of the Network tab confirming a prolonged period (>1s) with events streaming but no REST refetch would confirm CR-02.

---

## Gaps Summary

Two blockers are introduced by plan 01-12's "polling storm" fix:

**CR-01 (BLOCKER) — Threads tab frozen during live streaming.** `useThreadActivity` disables its 2s poll when `isLive=true` on the stated premise that SSE-driven cache invalidations refresh the Threads view. That premise is false: `invalidateQueries` in `use-event-stream.ts` targets `['sessions', sessionId]` only. The `['thread-activity', sessionId]` key is never invalidated anywhere in the codebase (confirmed by repo-wide grep returning zero hits). Fix: add `queryClient.invalidateQueries({ queryKey: ['thread-activity', sessionId] })` in the same debounced setTimeout block in `use-event-stream.ts`, or restore a slow fallback poll (`refetchInterval: isLive ? 5000 : 2000` in `use-thread-activity.ts`).

**CR-02 (BLOCKER) — Trailing-edge debounce starvation under sustained event streams.** The removed `setInterval` guaranteed a REST snapshot refresh every 500ms while streaming. Its replacement (two independent trailing-edge debounces, 400ms and 500ms) provides no such guarantee: a sustained burst of events with sub-window inter-event gaps perpetually resets both timers, so the session header coroutine count, job states, and the completion-aware scenario button (all derived from the REST session snapshot) can freeze indefinitely — precisely during the long-running scenarios this tool exists to visualize live. Fix: add a max-wait cap to both debounces (track `firstEventAt`, flush immediately when `Date.now() - firstEventAt >= MAX_WAIT_MS`) or adopt leading-edge + trailing-edge throttling.

Plans 01-09, 01-10, and 01-11 are fully verified with no blockers.

---

_Verified: 2026-06-12T14:00:00Z_
_Verifier: Claude (gsd-verifier)_
_Scope: Re-verification covering gap-closure plans 01-09..01-12; prior cycle (01-01..01-08) score was 11/11 human_needed_
