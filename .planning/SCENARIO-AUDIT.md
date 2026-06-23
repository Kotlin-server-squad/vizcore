# Scenario Logic Audit — vizcore example scenarios

**Date:** 2026-06-11 · **Scope:** all 12 built-in scenarios (3 real-world + 9 basic patterns) in `backend/src/main/kotlin/com/jh/proj/coroutineviz/scenarios/ScenarioRunner.kt`.

**Method.** Three evidence streams per scenario: (1) **code read** of the Kotlin implementation against its UI catalog claim (`frontend/src/routes/scenarios/index.tsx`, `routes/gallery/index.tsx`) and against real kotlinx.coroutines semantics; (2) **live headless run** of every scenario (and every failure param) via REST, asserting the final coroutine states from the session snapshot; (3) **browser verification** of the rendered visualization for the key discrepancies. The question asked of each scenario: *is it logically correct, and does it actually teach what it claims to teach?*

---

## Scoreboard

| # | Scenario | Logic | Pedagogy | Runtime states verified |
|---|---|---|---|---|
| 1 | Nested Coroutines | ✅ correct | ✅ honest | 4/4 COMPLETED, WAITING_FOR_CHILDREN shown |
| 2 | Parallel Execution | ✅ correct | ✅ honest | 6/6 COMPLETED |
| 3 | Cancellation | ❌ **broken** | ❌ teaches the wrong lesson | all 3 CANCELLED incl. `normal-child` |
| 4 | Deep Nesting | ✅ correct | 🟡 off-by-one claim | 6/6 COMPLETED (claim says 5 levels) |
| 5 | Mixed Sequential/Parallel | ✅ correct | 🟡 too fast to observe | 6/6 COMPLETED |
| 6 | Exception Handling | ❌ **broken** | ❌ contradicts on-screen lesson | `failing-child` = CANCELLED, never FAILED |
| 7 | Channel Rendezvous | ✅ correct | 🟡 half the claim + invisible | 3/3 COMPLETED, 70 events |
| 8 | Channel Buffered | ✅ correct | 🟡 invisible in UI | 3/3 COMPLETED, 60 events |
| 9 | Channel Fan-Out | ✅ correct | 🟡 invisible in UI | 5/5 COMPLETED, 130 events |
| 10 | Order Processing | 🟡 happy-path ok | 🟡 failure mode wrong + unreachable | fail=true: payment = CANCELLED not FAILED |
| 11 | User Registration | ❌ **intent not delivered** | ❌ teaches the opposite | failEmail=true: whole registration CANCELLED |
| 12 | Report Generation | 🟡 happy-path ok | ❌ "timeout handling" is false | timeout=true: fake timeout, all CANCELLED |

---

## Systemic findings (cross-cutting, ranked)

### SC-01 — The FAILED state is unreachable. Every failure renders as CANCELLED. (HIGH)

`wrappers/VizScope.kt:182` only emits `coroutineFailed` when **the exception message happens to contain the coroutine's label**:

```kotlin
cause !is CancellationException && cause.message?.contains(ctx.label ?: "unknown") == true -> // FAILED
```

No scenario's exception message contains its label ("Intentional failure for demo" ∌ "failing-child"; "Payment declined: Insufficient funds" ∌ "PaymentService.processPayment"; "SMTP server timeout" ∌ "EmailService.retry-1"; "Analytics API timeout after 8s" ∌ "AnalyticsApi.fetchMetrics"). And because a failed `launch` Job reports `isCancelled == true`, every real failure falls into the CANCELLED branch (line 197).

**Verified live in all four failure paths** (exception scenario, order `fail=true`, registration `failEmail=true`, report `timeout=true`): the throwing coroutine always shows CANCELLED. The browser renders this directly beneath the Structured Concurrency teaching panel that states *"When a child coroutine throws an exception, it enters FAILED state"* — the product visually contradicts its own lesson, and the red FAILED legend color can never appear. The failure-vs-victim distinction — the core insight of structured concurrency — is erased.

**Fix:** in `invokeOnCompletion`, classify by cause type, not message text: `cause == null → Completed`; `cause is CancellationException → Cancelled`; any other `Throwable → Failed`. Delete the label-in-message heuristic. (For *parents* cancelled by a failing child, the cause arrives as a CancellationException wrapper — that branch is already correct.) Add a test: scenario with a throwing child must produce exactly one `CoroutineFailed` event.

### SC-02 — The advertised failure modes are unreachable from the UI. (MED)

The scenario cards say *"Use ?fail=true to simulate payment failure"*, *"Use ?failEmail=true…"*, *"Use ?timeout=true…"*. The backend routes parse these (`ScenarioRunnerRoutes.kt:193,212,231`) and `apiClient.runScenario(scenarioId, sessionId, params)` supports a `params` map — but the **only caller**, `SessionDetails.tsx:104`, passes `{scenarioId, sessionId}` and nothing else. No UI control sets the params; appending `?fail=true` to the *frontend* URL does nothing. The failure demos — arguably the most instructive halves of all three real-world scenarios — are dead UI weight reachable only by hand-crafted curl.

**Fix:** add a toggle ("Simulate failure") to Scenario Controls that forwards the scenario's documented param, or remove the claims from the cards.

### SC-03 — The channel scenarios' signature visualization is invisible (downstream of RT-01). (MED)

`useEventCategories` (→ `useSessionEvents` → the 500-ing `/events` endpoint, RT-01 in VERIFICATION.md) always returns `hasChannels/hasFlowOps/hasSyncPrimitives/hasJobs = false`, so the conditional **Channels/Flow/Sync/Jobs tabs never mount** (`SessionDetails.tsx:367-392`). Verified in the browser: the channel-buffered session (60 channel events) shows only Coroutines/Events/Threads/Validation. The three channel scenarios are logically sound Kotlin, but all the user ever sees is a generic 3–5 node coroutine tree — buffer fill/drain, rendezvous suspension, and worker competition are recorded and discarded. Fixing RT-01 unlocks all of this with no scenario change.

### SC-04 — `VizScope` has no `Job`; roots are orphans; failed runs report success. (MED)

`VizScope.coroutineContext = context + CoroutineName(...)` with `EmptyCoroutineContext` default → no `Job` element. Consequences: (a) every top-level `vizLaunch` creates a root job parented to nothing, so the wrapping `coroutineScope { }` in each scenario does **not** wait for or constrain it; (b) `VizScope.cancel()`/`cancelAndJoin()` resolve `coroutineContext[Job]?` to null — **silent no-ops**; (c) since `join()` on a failed orphan root returns normally, scenario routes return `{"success": true, "message": "… completed"}` even when the run failed — verified: order `fail=true` and report `timeout=true` both returned success. **Fix:** give VizScope a real `Job()` (or `SupervisorJob()`) in its context, and have routes inspect the job's final state before composing the response.

### SC-05 — Duration claims are overstated. (LOW)

Sum of the coded `vizDelay` ranges vs the cards: Order **11–16.5s** actual vs "~15–20 s" claimed; Registration **10.5–14s** vs "~18–25 s"; Report **14–19.5s** vs "~25–35 s". Not harmful, but the cards promise up to 2× the real runtime.

---

## Per-scenario detail

### 1. Nested Coroutines — ✅ correct
`ScenarioRunner.kt:18-52`. Parent body completes instantly while children run 2–5s → genuinely demonstrates WAITING_FOR_CHILDREN; verified live in the browser walkthrough (parent → child-1 → child-1-1, child-2; all COMPLETED in correct order). The flagship demo, and it earns it.

### 2. Parallel Execution — ✅ correct
`:57-82`. Coordinator + 5 workers with randomized 2–3s work, explicit joins. Clean parallelism demo. 6/6 COMPLETED.

### 3. Cancellation — ❌ broken demo
`:87-126`. The scenario's own cast (`child-to-be-cancelled`, `normal-child`) and the gallery claim ("Cancel a running coroutine and watch CancellationException propagate") describe **targeted** cancellation with a surviving sibling. But the targeted cancel is **commented out** (`:117 // child1.cancel()`); instead `:123-124` does `delay(1000); job.cancel()` — killing the **whole tree** at t=1s, while `normal-child` needs 3s. Verified: all three CANCELLED; the "Normal child completed" log line is unreachable. The demo teaches "cancelling a parent kills everything" (true but trivial) instead of the named lesson (a cancelled child does **not** take down its siblings — the precise dual of failure propagation, and the most valuable contrast this catalog could offer). Also: `child1`/`child2` are unused variables. **Fix:** restore `child1.cancel()` (after a short delay so it's visibly mid-flight), drop the root `job.cancel()`, and let `normal-child` complete.

### 4. Deep Nesting — ✅ correct, 🟡 claim off-by-one
`:131-160`. Recursion is sound; parents enter WAITING_FOR_CHILDREN for the 5s leaf. But `depth=5` yields **6** nested coroutines (`level-0..level-4` + `leaf-5`, verified live) vs the gallery's "5 levels". Cosmetic.

### 5. Mixed Sequential/Parallel — ✅ correct, 🟡 invisible pacing
`:165-203`. setup `.join()` → 3 parallel workers → cleanup `.join()` is a faithful phase pattern. But delays are 50–150ms — in a tool whose real-world scenarios deliberately use "2–5 second delays for learning," this one finishes before the eye registers it. Bump to ~1–2s per phase.

### 6. Exception Handling — ❌ broken on two axes
`:205-237`.
- **Display (SC-01):** `failing-child` throws `IllegalStateException` yet renders CANCELLED — verified in browser, directly under the panel teaching FAILED. The one scenario whose only job is to show the FAILED state cannot show it.
- **Code-as-curriculum:** the `try { child1.join(); child2.join() } catch (e: Exception)` is wrong Kotlin pedagogy twice over: `join()` does **not** rethrow a failed child's exception (that's `await()`), so the catch never sees the `IllegalStateException` — what it actually catches is the parent's own `CancellationException` as the child's failure cancels the parent; and catching `CancellationException` without rethrowing is the classic coroutines anti-pattern. The log line "Parent caught exception: …" therefore lies about the mechanism. In a teaching tool, learners will copy this. **Fix:** demonstrate failure propagation honestly (no try/catch — let it propagate and show FAILED/CANCELLED states), or demonstrate *containment* properly with `supervisorScope` + `CoroutineExceptionHandler` as a separate scenario.

### 7–9. Channel scenarios — ✅ logic, 🟡 presentation
`:273-424`. All three are well-constructed: Rendezvous staggers producer/consumer to force suspension-on-send; Buffered (capacity 3, 5 items, consumer delayed 500ms) provably fills the buffer and suspends on the 4th send; Fan-Out (10 tasks, 3 workers, variable 100–300ms work) is a textbook competing-consumers demo. Caveats: (a) Rendezvous claims "suspension on **both** sides" but the consumer is always slower, so receive-side suspension never occurs — slow the producer mid-stream to show it; (b) all channel-level insight is invisible in the UI (SC-03). 70/60/130 events captured per run, all coroutines COMPLETED.

### 10. Order Processing — 🟡
`:441-525`. Happy path is a well-shaped sequential→parallel service flow and runs correctly. Issues: failure mode shows CANCELLED-not-FAILED (SC-01) and is UI-unreachable (SC-02); the `catch` around `paymentJob.join()` catches the parent's CancellationException, so the log "Payment failed: ${e.message}" prints the wrapper's message, not "Payment declined: Insufficient funds"; route reports `success:true` on failure (SC-04); duration overstated (SC-05). Correct detail worth keeping: `Database.saveOrder` and the notifications are never launched after payment fails — right behavior, right lesson.

### 11. User Registration — ❌ stated intent not delivered
`:537-637`. Happy path correct (3 sequential steps → parallel setup ×3 → parallel notifications). Two real defects:
- The code comments *"Don't fail registration if notifications fail"* — but with regular Job parenting, `EmailService.retry-1`'s throw cancels `UserService.register` and collateral-cancels `SlackService.notifyTeam` (both verified live). The try/catch around the joins cannot prevent structured-concurrency propagation; delivering the stated intent requires `supervisorScope` (or `vizAsync` + per-job handling). As written it demonstrates exactly the failure mode the comment claims to avoid.
- The "retry logic" is fake: a single child named `retry-1` that throws and is never retried — no second attempt, no backoff. Naming it "retry" miseducates.

### 12. Report Generation — 🟡 happy path, ❌ "timeout"
`:649-783`. The pipeline shape (parallel fetch ×4 → aggregation with implicit child-waiting → sequential PDF stages → parallel delivery ×3) is correct and the implicit `coroutineScope`-style waiting in `DataProcessor.aggregateData` is actually a nice structured-concurrency touch. But the claim "Includes timeout handling" is false: `shouldTimeout` just delays 8s and throws `IllegalStateException("Analytics API timeout after 8s")` — there is **no** `withTimeout`, no `TimeoutCancellationException`, no handling. Verified: analytics CANCELLED (SC-01 again), entire report cancelled, route says success. **Fix:** wrap the fetch in `withTimeout(3000)` for a genuine timeout demo — it would also exercise a different cancellation cause worth visualizing.

---

## What to fix, in order

1. **SC-01** — classify completion cause by type in `VizScope.invokeOnCompletion`; delete the label-in-message heuristic. Unblocks the core lesson of 4 scenarios. *(Small, contained.)*
2. **Cancellation scenario** — restore `child1.cancel()`, remove root `job.cancel()`. *(2-line change; turns a wrong lesson into the catalog's best contrast.)*
3. **SC-03 / RT-01** — register the `VizEvent` polymorphic serializers (already #0 in VERIFICATION.md sequencing); unlocks Channels/Flow/Sync/Jobs tabs and the Events list with zero scenario changes.
4. **Registration intent** — wrap notifications in `supervisorScope`; make the retry real (2 attempts + succeed-on-second) or rename it.
5. **Report timeout** — use real `withTimeout`.
6. **SC-02** — "Simulate failure" toggle in Scenario Controls passing the documented param.
7. **SC-04** — give `VizScope` a real Job; make scenario routes report failure honestly.
8. **Cosmetics** — Deep Nesting "5 levels"→6 or `depth=4`; Mixed delays → 1–2s; duration claims on cards.
