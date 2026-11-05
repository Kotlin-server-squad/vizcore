# Triage PR 3 — SDK: watermark, origin, provenance — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the SDK send what the session-triage spec (`docs/superpowers/specs/2026-10-21-session-triage-design.md`,
"Definitions" and C-7) defines, so the health API that Part 5a (#295) put on the server reads attached apps correctly:
a poll watermark at most once per second, a final watermark on a clean `stop()`, the origin of each coroutine, and the
provenance of each suspension point.

**Architecture:** Core's DebugProbes path computes origin (creation stack) and provenance (which frame it found), skips
coroutines a `VizScope` of the same session already reports, and reports each completed poll through a callback. The
client numbers each poll's `PollWatermark` and puts it on the local session's bus (so it follows that poll's events in
bus order); the outbound buffer — the one ordered path to the socket — forwards at most one per second, stamped with
the exact cumulative loss count. `stop()` sets a capture cutoff (a numbered marker on the same bus), lets the buffer
drain, sends a `final` watermark and closes, all within a deadline. Watermarks go only to servers that serve session
health, because a 1.2 server knows the type but would store every watermark as an event.

**Tech Stack:** Kotlin 2.3 (language/API 2.2), JVM 17 bytecode, kotlinx-coroutines 1.10 (DebugProbes), Ktor 3 client
(CIO, WebSockets), kotlinx-serialization, JUnit 5, `kotlinx-coroutines-test`, `ktor-server-test-host`.

---

## Where this sits

| Triage PR | Content | State |
|---|---|---|
| 1 | Core wire types (`PollWatermark`, `CoroutineCreated.origin`, `CoroutineSuspended.provenance`), summaries, `SessionHealthDerivation` | merged (#248) |
| 2 (5a) | Server: watermark as ingest transport state, clean/unclean close, `GET …/health`, C-3 session fields, SSE watermarks | merged (#295) |
| 2 (5b) | Server: rounds, search, persistence, checks, shares | in progress elsewhere (`feat/health-api-b`) |
| **3 (this plan)** | **SDK: watermark, origin, provenance (C-7)** | |

What the server expects (from #295 and its plan `2026-11-06-part5-health-api.md`):
- A `PollWatermark` text frame on the ingest socket is transport state (`IngestStates.watermarkReceived`), never stored.
  The newest by app clock wins (D-1b); the largest cumulative `lostEvents` and any `deliveryUncertain` are sticky (D-4).
  Its `seq` is not read.
- A connection closes cleanly only when a watermark with `final = true` arrived on it (D-2); otherwise delivery is
  uncertain from that connection's first seq on, and possibly blocked reads `NOT_EVALUATED (EVENTS_DROPPED)`.
- `CoroutineCreated.origin` (`APP` | `VIZCORE` | `UNKNOWN`, absent from older SDKs) and
  `CoroutineSuspended.provenance` (`REAL_FRAME` | `CREATION_FALLBACK`; `STALE` is the backend's) already decode.
- Possibly blocked: the time from a coroutine's latest start/resume to the latest watermark, both `System.nanoTime()`
  on the app (the clock `EventContext.timestamp()` uses), is over 10 s.

## Decisions

| # | Topic | Decision |
|---|---|---|
| P-1 | The leftovers (spec "Origin": "PR 3 starts by verifying this on the leftovers") | Verified with a scratch dump of `DebugProbes.dumpCoroutinesInfo()` after `VizcoreClient.start` against `FakeBackend` (2026-11-06): `nonce-generator`'s creation stack contains `com.jh.proj.coroutineviz.client.IngestTransportKt$connectAndDrain…` → **`VIZCORE`**. `cio-from-nio-reader` and `cio-to-nio-writer` are launched from Ktor's `Endpoint.connect` while a coroutine is resumed on a worker thread; the thread stack holds no SDK frame → they stay **`APP`**, as the spec accepts ("they only appear in Long-lived (n)"). `cio-request-timeout` was not in the dump (it exists only while a request with a timeout runs); it is launched the same way inside the CIO engine, so it stays `APP` too. The rule is applied as written: any frame whose class starts with `com.jh.proj.coroutineviz.client.`; the wrapper package does not count. A fresh-JVM test (Task 9) pins the verified classification. |
| P-2 | No creation stacks | A coroutine whose creation stack is empty (DebugProbes installed without creation stacks, e.g. by IntelliJ first, #217) is `UNKNOWN`. Core already makes the session origin `REPORTED` only once a coroutine reported `APP`/`VIZCORE`, so a session with no stacks reads "origin not available" and hides nothing. |
| P-2b | Where origin rides inside core | `CoroutineSnapshot` gets an **`internal var origin`** in its body, set by the adapter, read by the synthesizer. Not a constructor property: that would change the public data class's JVM constructors and its mangled `copy-…` methods (inline `CoroKey` parameters), breaking binaries compiled against 0.3.x. The public API of `CoroutineSnapshot` stays byte-identical. |
| P-3 | Wrapper coroutines captured once | Verified: today the DebugProbes path does **not** skip `VizCoroutineElement` coroutines (only `VizcoreInternal` and the transport dispatcher), so a `VizScope(client.session).vizLaunch { }` coroutine would also appear as `dp-…`. `VizCoroutineElement` gains two internal fields, `owner` (the `VizSession` of the `VizScope` that launched it) and `ownerJob` (the coroutine it was created for). The DebugProbes source of session S drops a coroutine whose element's `owner === S` and whose `ownerJob` is its own Job (or not yet set: the wrapper itself, before its body runs). A coroutine that inherits the element (launched from the wrapper's body via `coroutineScope { launch { } }` or `CoroutineScope(currentCoroutineContext())`) has another Job and stays captured. (A bare `launch` in a `vizLaunch` block runs on the `VizScope` receiver and carries no element at all.) A `VizScope` of another session is not skipped. Wrapper `CoroutineCreated` events carry `origin = APP`. |
| P-4 | Poll time | Taken right before the dump (`System.nanoTime()` and `System.currentTimeMillis()`). The dump observed the app no earlier than that, so "running until the watermark" never overstates (H-1). Events of the same poll carry later timestamps, so a coroutine first seen running is never flagged by its own poll's watermark. |
| P-5 | Watermark path and ordering | Core's `DebugProbesSource.onPollCompleted(pollNanos, pollEpochMs)` runs on the poll loop after every poll whose events were all sent (also when it found no change; never after a failed poll or once stopped). The client's callback calls `OutboundBuffer.poll(...)`, which puts a `PollWatermark` on the session's bus with `bus.send` (not `session.send`: one per poll would evict the local store's events). Bus order is send order and the feed is the bus's single outbound collector, so the watermark follows that poll's events into the buffer and onto the socket. The feed forwards at most one per second (by app clock; the first at once), stamped with the counts at that moment. A watermark that does not fit a full buffer is skipped, not counted: it is not an event, and the next one carries the counts. |
| P-6 | `lostEvents` is exact | Cumulative per client: outbound-buffer overflow drops plus the **events** the session bus evicted for the feed while it lagged (new per-subscriber `EventBus.stream(onSubscribed, onMissed)`; the bus-wide `onEvicted` would also count other subscribers' lag). The bus also carries control items (watermarks, the cutoff marker), so each is numbered under one lock as it goes on the bus (`PollWatermark.seq`, which the server does not read; the stamped copy sends `seq = 0`). When the feed receives control item `n` after `p`, `n - p - 1` control items were among the evicted entries; evicted events = evicted entries − evicted control items, exact at every stamp because every stamp is taken on a received control item. Kept across a replacement session (#129): it can overstate for the new session, never understate. |
| P-7 | SDK `deliveryUncertain` | Sticky once a socket ended any way other than the buffer closing at `stop()` after it had taken at least one event (not counting watermarks): that event may not have arrived (no acknowledgement). The server already marks such a connection uncertain on its side; the flag keeps the SDK's own statement complete (C-7). |
| P-8 | Capture cutoff | `OutboundBuffer.cutoff(session)` runs under the same control lock as `poll(...)`: after it, no watermark goes on the bus. If the feed has subscribed (recorded under that lock from the bus's `onSubscribed`, i.e. once its slot is allocated), a numbered cutoff marker goes on the bus: everything sent before it is handed to the buffer, everything after it is outside the capture, neither forwarded nor counted. If the feed has not subscribed yet, nothing was captured (the source starts only after the feed subscribed) and the buffer closes at once. If the bus evicts entries for the feed after the cutoff was requested, the marker may be among them: the feed then ends the capture without a final watermark (losses already happened, so the server's "delivery uncertain" is the honest reading). Producers are not rejected at `VizSession.send` (the local session still records them); the effect on what is sent is the spec's, without a new `VizSession` API. |
| P-9 | Final watermark | When the feed reaches the marker it queues `final = true` carrying the newest completed poll it saw before the marker (also one the rate limit held back) and the final counts, then closes the buffer. No later producer can add to the counts. **If no poll completed, there is no final watermark** (a watermark is an observation, H-1/H-3): the buffer just closes and the server reads delivery uncertain. The drain sends the final watermark last; the socket then closes normally. |
| P-10 | `stop()` | `stop()` = `stop(DEFAULT_STOP_TIMEOUT_MILLIS)` (5 s); new `stop(timeoutMillis)`. Order: take the deadline on entry; under the lifecycle lock mark the client **stopped for good** (`start()` never runs again: the scope is cancelled at the end anyway, so a client never restarted) and note whether a socket is open; set the cutoff; if a socket is open, wait for the reconnect loop to finish that socket (drain, final, close) with what is left of the deadline — it never reconnects, because the client is no longer running; then, in a `finally`, stop the source (cancel the poll loop, release DebugProbes — the release can take its own time, as before, and comes after the delivery wait), close the buffer and the HTTP client and cancel the scope. A missed deadline logs one WARN and closes without the final watermark (C-7: "the close reports delivery uncertain"). An interrupt during the wait ends the wait, keeps the thread's interrupt status, and still releases everything. Without an open socket `stop()` does not wait. |
| P-11 | Older servers (compatibility) | A 1.2.0 server registers `PollWatermark` as an event type (#248 is in rc3/rc4) but its ingest stores every decoded frame, so watermarks would fill its bounded store and show up as events. Before each connection the client asks `GET /api/sessions/{id}/health`: `200`, or `503` with a health code (`summaries-unavailable`, `history-trimmed`), means the server takes watermarks as transport state; `404` means an older server; anything else (no answer, an auth or proxy error) means "not known" and that connection sends none. Origin and provenance are new JSON keys: 1.1 servers ignore unknown keys (`ignoreUnknownKeys = true`), 1.2 servers know both fields and all their values. |
| P-12 | Older SDKs with the new server | Unchanged (5a D-2): no watermark → "not evaluated (no poll data)" while connected, "events may have been lost" after. |
| P-13 | Server code | None. One backend **test** runs the real SDK against the real backend module. |
| P-14 | Version | The client stays `0.3.1`; the version bump and publication belong to the release PR. |
| P-15 | Real-DebugProbes tests | They live in the client module (no JaCoCo agent there, and `SdkOwnCoroutinesTest` already runs real probes in its default `test`). Those that depend on process-wide state (which JVM-wide coroutines exist, the creation-stack flag) are tagged `fresh-jvm` and run in a new `freshJvmTest` task with `forkEvery = 1`, wired into `check`; the default `test` excludes them. |

## File map

**Core (`backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/…`)**
- `session/source/debugprobes/SourceAttribution.kt` — `origin(creationStack)` and `SDK_CLIENT_PACKAGE`.
- `session/source/debugprobes/CoroutineSnapshot.kt` — `internal var origin`.
- `session/source/debugprobes/CoroutineInfoAdapter.kt` — sets it (also on the `parentKey` copies).
- `session/source/debugprobes/DebugProbesEventSynthesizer.kt` — `origin` on `CoroutineCreated`, `provenance` on `CoroutineSuspended`.
- `session/source/debugprobes/WrapperOwnedCoroutines.kt` (new) — `excludeWrapperOwned`.
- `session/source/debugprobes/DebugProbesSource.kt` — wrapper filter in the default dump; `onPollCompleted`.
- `wrappers/VizCoroutineElement.kt` — `owner`, `ownerJob`.
- `wrappers/VizScope.kt` — sets them; `origin = APP` on wrapper `CoroutineCreated`.
- `session/EventBus.kt` — `stream(onSubscribed, onMissed)`.

**Client (`backend/coroutine-viz-client/…`)**
- `src/main/…/client/OutboundBuffer.kt` — numbered control items, watermark forwarding, exact loss counts, delivery uncertainty, cutoff and final.
- `src/main/…/client/IngestTransport.kt` — `takesWatermarks` probe; `sendWatermarks` per socket; `SocketLifetime.isOpen`.
- `src/main/…/client/VizcoreClient.kt` — poll → watermark wiring, probe before connect, `stop(timeoutMillis)`.
- `build.gradle.kts` — `kotlinx-coroutines-debug` for tests; `freshJvmTest` task.

**Tests** (core, client, backend) — listed per task. **Docs** — `README.md` (SDK limits), `CHANGELOG.md`.
**Sample** — `examples/spring-vizcore-demo` gets an optional busy coroutine for the end-to-end check.

## Commands

All from `backend/` with `export JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home`.
- Core tests: `./gradlew :coroutine-viz-core:test --tests '<pattern>'`
- Client tests: `./gradlew :coroutine-viz-client:test --tests '<pattern>'`; fresh-JVM ones: `./gradlew :coroutine-viz-client:freshJvmTest`
- Backend tests: `./gradlew :test --tests '<pattern>'` (the root project; an unqualified `test` applies the filter to every subproject)
- Full gate (end): `./gradlew ktlintCheck detekt test checkBytecode build :coroutine-viz-core:build :coroutine-viz-client:test :coroutine-viz-client:freshJvmTest`;
  revert `backend/docs/index.html` if the build regenerated it (`git checkout -- docs/index.html`).

Every commit ends with the trailer:
```
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG
```

---

### Task 1: Core — origin from the creation stack

**Files:**
- Modify: `coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/source/debugprobes/SourceAttribution.kt`
- Modify: `…/debugprobes/CoroutineSnapshot.kt`, `…/debugprobes/CoroutineInfoAdapter.kt`, `…/debugprobes/DebugProbesEventSynthesizer.kt`
- Test: `coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/session/source/debugprobes/SourceAttributionTest.kt`,
  `…/CoroutineInfoAdapterTest.kt`, `…/DebugProbesEventSynthesizerTest.kt`

- [ ] **Step 1: Write the failing tests**

`SourceAttributionTest.kt` (add; import `com.jh.proj.coroutineviz.events.CoroutineOrigin`):
```kotlin
    private fun frame(cls: String) = StackTraceElement(cls, "run", "X.kt", 1)

    @Test
    fun `a creation stack through the SDK client package is vizcore's`() {
        val stack = listOf(frame("kotlinx.coroutines.BuildersKt"), frame("com.jh.proj.coroutineviz.client.IngestTransportKt"))
        assertEquals(CoroutineOrigin.VIZCORE, SourceAttribution.origin(stack))
    }

    @Test
    fun `any other creation stack is the app's, the wrapper package included`() {
        assertEquals(CoroutineOrigin.APP, SourceAttribution.origin(listOf(frame("com.acme.Service"))))
        assertEquals(CoroutineOrigin.APP, SourceAttribution.origin(listOf(frame("com.jh.proj.coroutineviz.wrappers.VizScope"))))
        assertEquals(CoroutineOrigin.APP, SourceAttribution.origin(listOf(frame("com.jh.proj.coroutineviz.clientele.X"))))
    }

    @Test
    fun `no creation stack means the origin is unknown`() {
        assertEquals(CoroutineOrigin.UNKNOWN, SourceAttribution.origin(emptyList()))
    }
```

`CoroutineInfoAdapterTest.kt` (add):
```kotlin
    @Test
    fun `the snapshot carries the origin of its creation stack, parent links included`() {
        val adapter = CoroutineInfoAdapter()
        val sdk = StackTraceElement("com.jh.proj.coroutineviz.client.IngestTransportKt", "connect", "IngestTransport.kt", 9)
        fun raw(stack: List<StackTraceElement>, job: Job = Job()) =
            CoroutineInfoAdapter.RawInfo(CoroState.RUNNING, job, EmptyCoroutineContext, stack, emptyList())
        assertEquals(CoroutineOrigin.VIZCORE, adapter.toSnapshot(raw(listOf(sdk))).origin)
        assertEquals(CoroutineOrigin.APP, adapter.toSnapshot(raw(creationStack)).origin)
        assertEquals(CoroutineOrigin.UNKNOWN, adapter.toSnapshot(raw(emptyList())).origin)
        val parent = Job()
        val child = adapter.toSnapshots(listOf(raw(creationStack, parent), raw(listOf(sdk), Job(parent))))[1]
        assertTrue(child.parentKey != null)
        assertEquals(CoroutineOrigin.VIZCORE, child.origin, "the parentKey copy keeps the origin")
    }
```
(`creationStack` is the test class's existing app-frame fixture.)

`DebugProbesEventSynthesizerTest.kt` (add):
```kotlin
    @Test
    fun `CoroutineCreated carries the snapshot's origin`() {
        val s = session()
        val vizcore = snap("a", CoroState.CREATED).also { it.origin = CoroutineOrigin.VIZCORE }
        val created = synthesizer.synthesize(CoroutineDelta.Appeared(vizcore), s).first() as CoroutineCreated
        val unset = synthesizer.synthesize(CoroutineDelta.Appeared(snap("b", CoroState.RUNNING)), s).first() as CoroutineCreated
        assertEquals(CoroutineOrigin.VIZCORE, created.origin)
        assertNull(unset.origin)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :coroutine-viz-core:test --tests '*SourceAttributionTest*' --tests '*CoroutineInfoAdapterTest*' --tests '*DebugProbesEventSynthesizerTest*'`
Expected: compilation fails (`origin` unresolved).

- [ ] **Step 3: Implement**

`SourceAttribution.kt` (inside the object; import `com.jh.proj.coroutineviz.events.CoroutineOrigin`):
```kotlin
    /** The SDK client's package: a coroutine created with one of its frames on the stack is vizcore's (spec "Origin"). */
    const val SDK_CLIENT_PACKAGE: String = "com.jh.proj.coroutineviz.client."

    /**
     * Who launched a coroutine, read from its creation stack (spec 2026-10-21, "Origin"): [CoroutineOrigin.VIZCORE] when
     * any frame belongs to the SDK client package, [CoroutineOrigin.APP] otherwise (the public wrapper API is app code),
     * and [CoroutineOrigin.UNKNOWN] without a creation stack (DebugProbes installed without creation stacks, #217).
     */
    fun origin(creationStackTrace: List<StackTraceElement>): CoroutineOrigin =
        when {
            creationStackTrace.isEmpty() -> CoroutineOrigin.UNKNOWN
            creationStackTrace.any { it.className.startsWith(SDK_CLIENT_PACKAGE) } -> CoroutineOrigin.VIZCORE
            else -> CoroutineOrigin.APP
        }
```

`CoroutineSnapshot.kt` — a body property (the primary constructor is unchanged, P-2b):
```kotlin
data class CoroutineSnapshot(
    …unchanged…
) {
    /**
     * Who launched it, from the creation stack ([SourceAttribution.origin]); `null` when not determined. Set by
     * [CoroutineInfoAdapter], read by [DebugProbesEventSynthesizer]. Kept out of the constructor so the published
     * constructors and `copy` keep their JVM signatures; not part of equality, and `copy` does not carry it.
     */
    internal var origin: CoroutineOrigin? = null
}
```

`CoroutineInfoAdapter`:
- `toSnapshot(raw: RawInfo)`: `return CoroutineSnapshot(…).also { it.origin = SourceAttribution.origin(raw.creationStackTrace) }`.
- `toSnapshots(raws)` second pass: `snap.copy(parentKey = …).also { it.origin = snap.origin }`.

`DebugProbesEventSynthesizer.appearedEvents`:
```kotlin
        val created = ctx.coroutineCreated(creationPoint = creationPointOf(snapshot)).copy(origin = snapshot.origin)
```
and a KDoc line: "`origin` comes from the creation stack ([SourceAttribution.origin])".

- [ ] **Step 4: Run the tests to verify they pass** — same command as Step 2, plus `--tests '*BinaryCompatibilityTest*'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add coroutine-viz-core
git commit -m "core: DebugProbes coroutines carry their origin from the creation stack (triage PR 3)"
```

---

### Task 2: Core — suspension-point provenance

**Files:**
- Modify: `…/debugprobes/DebugProbesEventSynthesizer.kt`
- Test: `…/debugprobes/DebugProbesEventSynthesizerTest.kt`

- [ ] **Step 1: Write the failing tests**
```kotlin
    private fun suspendedOf(snapshot: CoroutineSnapshot): CoroutineSuspended =
        synthesizer.synthesize(CoroutineDelta.StateChanged(CoroState.RUNNING, CoroState.SUSPENDED, snapshot), session())
            .single() as CoroutineSuspended

    @Test
    fun `a suspension frame read from the observed stack is a real frame`() {
        val observed = listOf(StackTraceElement("com.acme.Repo", "load", "Repo.kt", 42))
        val suspended = suspendedOf(snap("a", CoroState.SUSPENDED, function = "main", lastObservedStackTrace = observed))
        assertEquals("load", suspended.suspensionPoint?.function)
        assertEquals(SuspensionProvenance.REAL_FRAME, suspended.provenance)
    }

    @Test
    fun `the creation frame sent for want of a suspension frame says so`() {
        val suspended = suspendedOf(snap("a", CoroState.SUSPENDED, function = "main", fileName = "App.kt", lineNumber = 3))
        assertEquals("main", suspended.suspensionPoint?.function)
        assertEquals(SuspensionProvenance.CREATION_FALLBACK, suspended.provenance)
    }

    @Test
    fun `no frame at all has no provenance`() {
        val suspended = suspendedOf(snap("a", CoroState.SUSPENDED))
        assertNull(suspended.suspensionPoint)
        assertNull(suspended.provenance)
    }
```

- [ ] **Step 2: Run to verify they fail** — `./gradlew :coroutine-viz-core:test --tests '*DebugProbesEventSynthesizerTest*'`; expected: the two provenance assertions fail (`null`).

- [ ] **Step 3: Implement** — `suspensionPointOf` returns the point with its provenance:
```kotlin
    private fun suspensionPointOf(snapshot: CoroutineSnapshot): Pair<SuspensionPoint, SuspensionProvenance>? {
        val observed = snapshot.lastObservedStackTrace?.let { SourceAttribution.fromStack(it) }
        val observedFunction = observed?.function
        if (observedFunction != null) {
            val point = SuspensionPoint(observedFunction, observed.fileName, observed.lineNumber, snapshot.reason ?: DEFAULT_REASON)
            return point to SuspensionProvenance.REAL_FRAME
        }
        // Fallback: creation-derived fields (the pre-15-08 behavior), marked as such (spec "Suspension-point provenance").
        val function = snapshot.function ?: return null
        val point = SuspensionPoint(function, snapshot.fileName, snapshot.lineNumber, snapshot.reason ?: DEFAULT_REASON)
        return point to SuspensionProvenance.CREATION_FALLBACK
    }

    private fun suspendedEvents(ctx: EventContext, snapshot: CoroutineSnapshot): List<VizEvent> {
        val point = suspensionPointOf(snapshot)
        return listOf(
            ctx.coroutineSuspended(
                reason = snapshot.reason ?: DEFAULT_REASON,
                suspensionPoint = point?.first,
                provenance = point?.second,
            ),
        )
    }
```
Update the class KDoc bullet on suspension points: "… with `provenance` `REAL_FRAME` or `CREATION_FALLBACK`".

- [ ] **Step 4: Run** — same command; expected PASS (existing synthesizer tests stay green).
- [ ] **Step 5: Commit** — `git commit -am "core: DebugProbes suspensions say where their frame came from (triage PR 3)"`

---

### Task 3: Core — a wrapper coroutine is left to its wrapper events, as the app's

**Files:**
- Modify: `coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizCoroutineElement.kt`, `…/wrappers/VizScope.kt`
- Create: `…/session/source/debugprobes/WrapperOwnedCoroutines.kt`
- Modify: `…/session/source/debugprobes/DebugProbesSource.kt` (default `dump`)
- Test: new `…/debugprobes/WrapperOwnedCoroutinesTest.kt`; new `coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeOwnershipTest.kt`
  (the real-DebugProbes "captured once" test is in Task 9, P-15)

- [ ] **Step 1: Write the failing tests**

`WrapperOwnedCoroutinesTest.kt`:
```kotlin
package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizCoroutineElement
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals

/** Which coroutines of a dump [excludeWrapperOwned] leaves to the wrapper events (spec "Origin": captured once). */
class WrapperOwnedCoroutinesTest {
    private val session = VizSession("wrapped")

    private fun element(owner: VizSession?, ownerJob: Job?) =
        VizCoroutineElement("coroutine-1", "job-1", null, "scope", "w").also {
            it.owner = owner
            it.ownerJob = ownerJob
        }

    private fun raw(name: String, job: Job, context: CoroutineContext) =
        CoroutineInfoAdapter.RawInfo(CoroState.RUNNING, job, context + CoroutineName(name), emptyList(), emptyList())

    private fun kept(vararg raws: CoroutineInfoAdapter.RawInfo) =
        excludeWrapperOwned(raws.toList(), session).map { it.context[CoroutineName]!!.name }

    @Test
    fun `the coroutine a VizScope of this session launched is left to its wrapper events`() {
        val job = Job()
        assertEquals(emptyList(), kept(raw("wrapper", job, element(session, job))))
    }

    @Test
    fun `a wrapper whose job is not recorded yet is the wrapper itself`() {
        assertEquals(emptyList(), kept(raw("wrapper", Job(), element(session, null))))
    }

    @Test
    fun `a coroutine that inherited a wrapper's element is kept`() {
        val wrapperJob = Job()
        val e = element(session, wrapperJob)
        assertEquals(listOf("child"), kept(raw("wrapper", wrapperJob, e), raw("child", Job(wrapperJob), e)))
    }

    @Test
    fun `a wrapper of another session is kept`() {
        val job = Job()
        assertEquals(listOf("other"), kept(raw("other", job, element(VizSession("other"), job))))
    }

    @Test
    fun `coroutines without the element are kept`() {
        assertEquals(listOf("app"), kept(raw("app", Job(), CoroutineName("app"))))
    }
}
```

`VizScopeOwnershipTest.kt`:
```kotlin
package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** A wrapper coroutine records whose it is (P-3), and its CoroutineCreated says it is the app's. */
class VizScopeOwnershipTest {
    @Test
    fun `vizLaunch records its session and its own job, an inheriting child has another job`() =
        runBlocking {
            val session = VizSession("own")
            val scope = VizScope(session)
            val seen = CompletableDeferred<Pair<Pair<VizCoroutineElement, Job>, Pair<VizCoroutineElement, Job>>>()
            val job =
                scope.vizLaunch(label = "w") {
                    val mine = currentCoroutineContext()[VizCoroutineElement]!! to currentCoroutineContext()[Job]!!
                    coroutineScope {
                        launch {
                            seen.complete(mine to (currentCoroutineContext()[VizCoroutineElement]!! to currentCoroutineContext()[Job]!!))
                            awaitCancellation()
                        }
                    }
                }
            val (wrapper, child) = withTimeout(5_000) { seen.await() }
            assertSame(session, wrapper.first.owner)
            assertSame(wrapper.second, wrapper.first.ownerJob)
            assertSame(job, wrapper.second)
            assertSame(wrapper.first, child.first, "the child inherits the wrapper's element")
            assertNotSame(child.first.ownerJob, child.second, "but is another coroutine")
            scope.cancel()
            session.close()
        }

    @Test
    fun `vizLaunch and vizAsync coroutines are the app's`() =
        runBlocking {
            val session = VizSession("origin")
            val scope = VizScope(session)
            scope.vizLaunch(label = "l") { }.join()
            scope.vizAsync(label = "a") { 1 }.await()
            val created = session.store.all().filterIsInstance<CoroutineCreated>()
            assertEquals(listOf("l", "a"), created.map { it.label })
            assertEquals(listOf(CoroutineOrigin.APP, CoroutineOrigin.APP), created.map { it.origin })
            scope.cancel()
            session.close()
        }
}
```
(If `InstrumentedDeferred` exposes `await` differently, use its existing API; `vizAsync` returns it.)

- [ ] **Step 2: Run to verify they fail** — `./gradlew :coroutine-viz-core:test --tests '*WrapperOwnedCoroutinesTest*' --tests '*VizScopeOwnershipTest*'`; expected: compilation fails (`owner`, `excludeWrapperOwned` unresolved).

- [ ] **Step 3: Implement**

`VizCoroutineElement.kt` (class body; imports `kotlinx.coroutines.Job`, `com.jh.proj.coroutineviz.session.VizSession`):
```kotlin
    /**
     * The session of the [VizScope] that launched the coroutine this element was made for. Its wrapper events describe
     * that coroutine, so the DebugProbes source of the same session leaves it out (captured once, spec "Origin").
     * Not part of equality.
     */
    @Volatile
    internal var owner: VizSession? = null

    /**
     * The coroutine this element was made for. A coroutine launched from its body with the body's context inherits the
     * element but is another coroutine, which DebugProbes still captures. `null` until the wrapper records it.
     */
    @Volatile
    internal var ownerJob: Job? = null
```

`VizScope.vizLaunch`: `val vizElement = VizCoroutineElement(...).also { it.owner = session }`; right after
`val job = targetScope.launch(mergedContext, start = CoroutineStart.LAZY) { … }` add
`vizElement.ownerJob = job // before job.start(): the body, and so any child, runs after this`; and replace
`session.send(ctx.coroutineCreated())` with `session.send(ctx.coroutineCreated().copy(origin = CoroutineOrigin.APP))`
(comment: "the public wrapper API is app code (spec "Origin")").

`VizScope.vizAsync`: `.also { it.owner = session }` on the element; first statement of the `async` body after
`val currentJob = …`: `vizElement.ownerJob = currentJob // before the body can launch a child`; and the same
`copy(origin = CoroutineOrigin.APP)` on its `coroutineCreated()`.

`WrapperOwnedCoroutines.kt`:
```kotlin
package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizCoroutineElement

/**
 * Leaves out of a dump the coroutines a [com.jh.proj.coroutineviz.wrappers.VizScope] of [session] launched: their wrapper
 * events already describe them, so each is captured once (spec 2026-10-21, "Origin"). A coroutine whose element was
 * made for it ([VizCoroutineElement.ownerJob] is its Job, or not recorded yet: the wrapper before its body runs) is the
 * wrapper's; a coroutine that inherited the element has another Job and stays. A `VizScope` of another session is not
 * this session's to describe and stays too.
 */
internal fun excludeWrapperOwned(
    raws: List<CoroutineInfoAdapter.RawInfo>,
    session: VizSession,
): List<CoroutineInfoAdapter.RawInfo> =
    raws.filterNot { raw ->
        val element = raw.context[VizCoroutineElement] ?: return@filterNot false
        val ownerJob = element.ownerJob
        element.owner === session && (ownerJob == null || ownerJob === raw.job)
    }
```

`DebugProbesSource` default `dump`:
```kotlin
        val raws = excludeSdkOwned(DebugProbesImplBridge.dumpRawInfos(), internalDispatchers, transportRoots)
        adapter.toSnapshots(excludeWrapperOwned(raws, session))
```

- [ ] **Step 4: Run** — same command, plus `./gradlew :coroutine-viz-core:test --tests '*VizScope*' --tests '*Wrapper*'`; expected PASS.
- [ ] **Step 5: Commit** — `git add` the new files, then `git commit -am "core: a VizScope coroutine is left to its wrapper events, as the app's (triage PR 3)"`.

---

### Task 4: Core — the source reports each completed poll

**Files:**
- Modify: `…/debugprobes/DebugProbesSource.kt`
- Test: `…/debugprobes/DebugProbesSourceTest.kt`

- [ ] **Step 1: Write the failing tests** (append to `DebugProbesSourceTest`):
```kotlin
    @Test
    fun `every completed poll is reported after its events, quiet polls included`() =
        runTest {
            val session = VizSession("polls")
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { listOf(snap("a", CoroState.RUNNING)) },
                )
            val polls = mutableListOf<Pair<Long, Int>>()
            source.onPollCompleted = { nanos, _ -> polls += nanos to session.store.all().size }
            source.start()
            runCurrent()
            advanceTimeBy(interval)
            runCurrent()
            source.stop()

            assertEquals(2, polls.size, "one report per poll, the quiet second one too")
            assertEquals(2, polls[0].second, "the first poll's Created and Started were sent before it was reported")
            val firstEvent = session.store.all().first()
            assertTrue(polls[0].first <= firstEvent.tsNanos, "the poll time is taken before the dump")
        }

    @Test
    fun `a failed poll and a stopped source report nothing`() =
        runTest {
            val session = VizSession("failed")
            var tick = 0
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { if (tick++ == 0) error("dump failed") else emptyList() },
                )
            var reports = 0
            source.onPollCompleted = { _, _ -> reports++ }
            source.start()
            runCurrent()
            assertEquals(0, reports, "the failed poll is not reported")
            advanceTimeBy(interval)
            runCurrent()
            assertEquals(1, reports)
            source.stop()
            advanceTimeBy(interval * 3)
            runCurrent()
            assertEquals(1, reports, "nothing after stop()")
        }
```

- [ ] **Step 2: Run** — `./gradlew :coroutine-viz-core:test --tests '*DebugProbesSourceTest*'`; expected: compilation fails (`onPollCompleted`).

- [ ] **Step 3: Implement** (in `DebugProbesSource`):
```kotlin
    /**
     * Called after every poll whose events were all sent, also when the poll found no change, with the poll's time on
     * the app clock ([System.nanoTime], the clock events carry) and its wall-clock time, both taken right before the
     * dump: the poll observed the app no earlier than that. Runs on the poll loop after the poll's last `send`, so what
     * it sends on the session's bus follows those events (the SDK's watermark, spec 2026-10-21 C-7). Not called for a
     * failed poll or once the source stopped.
     */
    @Volatile
    var onPollCompleted: ((pollNanos: Long, pollEpochMs: Long) -> Unit)? = null

    // True between start() and stop(); a poll still running when stop() returns reports nothing.
    @Volatile
    private var polling = false
```
`start()`: set `polling = true` before `loop = scope.launch { … }`. `stop()`: set `polling = false` first.
`pollTick()`: first lines `val pollNanos = System.nanoTime()` and `val pollEpochMs = System.currentTimeMillis()`;
after `prev = next` add `if (polling) onPollCompleted?.invoke(pollNanos, pollEpochMs)`. (A throwing callback falls
into the existing per-tick catch, which logs and keeps `prev` — equal to `next` at that point.)

- [ ] **Step 4: Run** — same command; expected PASS.
- [ ] **Step 5: Commit** — `git commit -am "core: DebugProbesSource reports each completed poll with its time (triage PR 3)"`

---

### Task 5: Core — a bus subscriber can count its own evictions

**Files:**
- Modify: `coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/EventBus.kt`
- Test: new `coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/session/EventBusMissedTest.kt`

- [ ] **Step 1: Write the failing test**
```kotlin
package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusMissedTest {
    private fun event(n: Int) = CoroutineCreated("s", n.toLong(), n.toLong(), "c$n", "j$n", null, "sc", null)

    @Test
    fun `onMissed reports the events evicted for that subscriber only`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = EventBus(capacity = 4)
            val gate = CompletableDeferred<Unit>()
            var slowMissed = 0L
            var slowReceived = 0
            var fastMissed = 0L
            var fastReceived = 0
            val slow = launch { bus.stream(null) { slowMissed += it }.collect { gate.await(); slowReceived++ } }
            val fast = launch { bus.stream(null) { fastMissed += it }.collect { fastReceived++ } }
            repeat(10) { bus.send(event(it)) }
            gate.complete(Unit)
            assertEquals(10, fastReceived)
            assertEquals(0L, fastMissed)
            assertEquals(10L, slowReceived + slowMissed, "every event is either received or reported missed")
            assertTrue(slowMissed > 0)
            slow.cancel()
            fast.cancel()
        }
}
```

- [ ] **Step 2: Run** — `./gradlew :coroutine-viz-core:test --tests '*EventBusMissedTest*'`; expected: compilation fails.

- [ ] **Step 3: Implement** — both public `stream` overloads delegate to one private function:
```kotlin
    /** (existing KDoc) */
    fun stream(onSubscribed: (suspend () -> Unit)? = null): Flow<VizEvent> = indexedStream(onSubscribed, null)

    /**
     * [stream] for a subscriber that must know what it missed: [onMissed] gets the number of entries evicted for THIS
     * subscriber while it lagged, right before the next entry it receives ([onEvicted] still hears every subscriber's).
     * The SDK's outbound feed counts its own losses with it (spec 2026-10-21, C-7).
     */
    fun stream(
        onSubscribed: (suspend () -> Unit)?,
        onMissed: (Long) -> Unit,
    ): Flow<VizEvent> = indexedStream(onSubscribed, onMissed)

    private fun indexedStream(
        onSubscribed: (suspend () -> Unit)?,
        onMissed: ((Long) -> Unit)?,
    ): Flow<VizEvent> =
        flow {
            var seen = 0L
            shared
                .onSubscription {
                    seen = synchronized(emitLock) { lastIndex }
                    onSubscribed?.invoke()
                }.collect { indexed ->
                    val missed = indexed.idx - seen - 1
                    if (missed > 0) {
                        logger.warn("Slow bus subscriber missed {} evicted events", missed)
                        onEvicted?.invoke(missed)
                        onMissed?.invoke(missed)
                    }
                    if (indexed.idx > seen) seen = indexed.idx
                    emit(indexed.event)
                }
        }
```

- [ ] **Step 4: Run** — same command plus `--tests '*BusSubscriptionRaceTest*'`; expected PASS.
- [ ] **Step 5: Commit** — `git add` the test, `git commit -am "core: EventBus.stream can report one subscriber's evictions (triage PR 3)"`.

---

### Task 6: Client — the outbound buffer: numbered watermarks, exact losses, cutoff and final

**Files:**
- Modify: `coroutine-viz-client/src/main/kotlin/com/jh/proj/coroutineviz/client/OutboundBuffer.kt`
- Test: new `coroutine-viz-client/src/test/kotlin/com/jh/proj/coroutineviz/client/OutboundBufferWatermarkTest.kt`

- [ ] **Step 1: Write the failing tests**
```kotlin
package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The SDK's watermark and loss reporting through the outbound buffer (spec 2026-10-21, C-7). */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboundBufferWatermarkTest {
    private val second = 1_000_000_000L

    private fun event(n: Int): VizEvent = CoroutineCreated("s", n.toLong(), n.toLong(), "c$n", "j$n", null, "scope", "e$n")

    private fun childScope(context: CoroutineContext) = CoroutineScope(context + Job(context.job))

    private val VizEvent.label: String
        get() = if (this is PollWatermark) "wm@${tsNanos / second}${if (final) "!" else ""}" else (this as CoroutineCreated).coroutineId

    @Test
    fun `a watermark follows the events its poll sent, even when the socket drains slowly`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            session.send(event(0))
            session.send(event(1))
            buffer.poll(session, 5 * second, 5_000)
            session.send(event(2))
            val sent = mutableListOf<String>()
            val drain = scope.launch { buffer.drain { delay(10); sent += it.label } }
            withTimeout(5_000) { while (sent.size < 4) delay(5) }
            assertEquals(listOf("c0", "c1", "wm@5", "c2"), sent)
            drain.cancel()
            scope.cancel()
            session.close()
        }

    @Test
    fun `at most one watermark a second, the first at once, sent with seq 0`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            listOf(0L, 500_000_000L, 1_000_000_000L, 1_600_000_000L, 2_100_000_000L).forEach { buffer.poll(session, it, 1) }
            val sent = mutableListOf<PollWatermark>()
            val drain = scope.launch { buffer.drain { sent += it as PollWatermark } }
            assertEquals(listOf(0L, 1_000_000_000L, 2_100_000_000L), sent.map { it.tsNanos })
            assertTrue(sent.all { it.seq == 0L })
            drain.cancel()
            scope.cancel()
            session.close()
        }

    @Test
    fun `a watermark carries the events dropped on overflow, and a dropped watermark is not one`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer(2)
            buffer.feed(session, scope)
            repeat(3) { session.send(event(it)) } // the third does not fit
            buffer.poll(session, 0, 1) // does not fit either: skipped, not counted
            assertEquals(1, buffer.lost)
            val sent = mutableListOf<VizEvent>()
            val drain = scope.launch { buffer.drain { sent += it } }
            buffer.poll(session, 2 * second, 1)
            assertEquals(listOf("c0", "c1", "wm@2"), sent.map { it.label })
            assertEquals(1, (sent.last() as PollWatermark).lostEvents)
            drain.cancel()
            scope.cancel()
            session.close()
        }

    @Test
    fun `evicted bus entries count as lost only when they were events`() {
        val feedThread = Executors.newSingleThreadExecutor()
        val session = VizSession("s")
        val scope = CoroutineScope(feedThread.asCoroutineDispatcher() + Job())
        val buffer = OutboundBuffer(20_000)
        try {
            runBlocking { buffer.feed(session, scope) }
            val release = CountDownLatch(1)
            feedThread.execute { release.await() } // the feed cannot run while the bus fills
            repeat(20) { buffer.poll(session, it.toLong(), 1) } // the 20 oldest entries: watermarks
            repeat(10_000) { session.send(event(it)) }
            buffer.poll(session, 10 * second, 1) // 10,021 entries: the bus keeps 10,000, the 21 oldest are evicted
            release.countDown()
            val sent = mutableListOf<VizEvent>()
            runBlocking {
                val drain = scope.launch { buffer.drain { sent += it } }
                withTimeout(10_000) { while (sent.lastOrNull() !is PollWatermark) delay(10) }
                drain.cancel()
            }
            assertEquals(9_999, sent.count { it !is PollWatermark })
            assertEquals(1, (sent.last() as PollWatermark).lostEvents, "20 evicted watermarks and 1 evicted event")
        } finally {
            scope.cancel()
            session.close()
            feedThread.shutdownNow()
        }
    }

    @Test
    fun `a socket that ends after taking an event leaves delivery uncertain, sticky`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            buffer.poll(session, 0, 1)
            runCatching { buffer.drain { throw IOException("socket dropped") } }
            assertFalse(buffer.deliveryUncertain, "only a watermark was in flight")
            session.send(event(0))
            runCatching { buffer.drain { throw IOException("socket dropped") } }
            assertTrue(buffer.deliveryUncertain)
            buffer.poll(session, 5 * second, 1)
            val sent = mutableListOf<VizEvent>()
            val drain = scope.launch { buffer.drain { sent += it } }
            assertTrue((sent.last() as PollWatermark).deliveryUncertain)
            drain.cancel()
            scope.cancel()
            session.close()
        }

    @Test
    fun `the cutoff ends the capture with a final watermark carrying the newest poll`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            buffer.poll(session, 1 * second, 1)
            session.send(event(0))
            buffer.poll(session, 1 * second + 300_000_000L, 1) // held back by the rate limit
            buffer.cutoff(session)
            session.send(event(1)) // after the cutoff: outside the capture
            buffer.poll(session, 9 * second, 1) // after the cutoff: not even put on the bus
            val sent = mutableListOf<VizEvent>()
            withTimeout(5_000) { buffer.drain { sent += it } } // returns: the buffer closed after the final watermark
            assertEquals(listOf("wm@1", "c0", "wm@1!"), sent.map { it.label })
            val final = sent.last() as PollWatermark
            assertEquals(1 * second + 300_000_000L, final.tsNanos, "the newest poll before the cutoff")
            assertEquals(0, final.lostEvents)
            assertEquals(0, buffer.lost, "nothing after the cutoff counts as lost")
            scope.cancel()
            session.close()
        }

    @Test
    fun `without a completed poll the capture ends with no final watermark`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            session.send(event(0))
            buffer.cutoff(session)
            val sent = mutableListOf<VizEvent>()
            withTimeout(5_000) { buffer.drain { sent += it } }
            assertEquals(listOf("c0"), sent.map { it.label }, "no watermark is made up (H-1)")
            scope.cancel()
            session.close()
        }

    @Test
    fun `a cutoff before the feed subscribed closes the buffer at once`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val buffer = OutboundBuffer()
            buffer.cutoff(session)
            withTimeout(1_000) { buffer.drain { error("nothing was captured") } }
            session.close()
        }

    @Test
    fun `entries evicted after the cutoff end the capture without a final watermark`() {
        val feedThread = Executors.newSingleThreadExecutor()
        val session = VizSession("s")
        val scope = CoroutineScope(feedThread.asCoroutineDispatcher() + Job())
        val buffer = OutboundBuffer(20_000)
        try {
            runBlocking { buffer.feed(session, scope) }
            val release = CountDownLatch(1)
            feedThread.execute { release.await() }
            buffer.poll(session, 1, 1)
            buffer.cutoff(session) // the marker is the 2nd entry …
            repeat(10_000) { session.send(event(it)) } // … and is evicted by what follows it
            release.countDown()
            val sent = mutableListOf<VizEvent>()
            runBlocking { withTimeout(10_000) { buffer.drain { sent += it } } }
            assertTrue(sent.none { it is PollWatermark && it.final }, "no clean close after a loss: $sent")
            assertTrue(sent.none { it is CoroutineCreated }, "nothing after the cutoff is sent")
        } finally {
            scope.cancel()
            session.close()
            feedThread.shutdownNow()
        }
    }
}
```

- [ ] **Step 2: Run** — `./gradlew :coroutine-viz-client:test --tests '*OutboundBufferWatermarkTest*'`; expected: compilation fails (`poll`, `lost`, `cutoff`, `deliveryUncertain`).

- [ ] **Step 3: Implement** (in `OutboundBuffer`; imports `PollWatermark`, `kotlinx.coroutines.CompletableDeferred`, `kotlinx.coroutines.flow.takeWhile`):

Primary constructor: `internal constructor(capacity: Int, reportIntervalMillis: Long, clock: () -> Long, watermarkIntervalNanos: Long = DEFAULT_WATERMARK_INTERVAL_NANOS)` (the public `@JvmOverloads constructor(capacity)` is unchanged).

State:
```kotlin
    // Control items (poll watermarks and the cutoff marker) are numbered under controlLock as they go on the session
    // bus, so the feed can tell how many of the entries the bus evicted for it were control items, not events (P-6).
    private val controlLock = Any()
    private var controlSeq = 0L // guarded by controlLock
    private var subscribed = false // guarded by controlLock
    private var cutoffRequested = false // guarded by controlLock

    @Volatile
    private var cutoffMarker: PollWatermark? = null

    // Written by the feed, read when stamping and by tests.
    private val missedEntries = AtomicLong(0)
    private val missedControl = AtomicLong(0)

    @Volatile
    private var uncertain = false

    // Feed-only state: one collector reads and writes it.
    private var lastControlSeq = 0L
    private var latestPoll: PollWatermark? = null
    private var lastForwardedNanos: Long? = null

    @Volatile
    private var abandoned = false

    /**
     * Events lost before sending, cumulative for the client (spec C-7): dropped because the buffer was full, or evicted
     * from the session bus before the feed read them. Skipped or evicted watermarks are not events and do not count.
     * Exact whenever the feed has just received a control item, which is when it stamps a watermark.
     */
    internal val lost: Long get() = droppedCount.get() + missedEntries.get() - missedControl.get()

    /** A socket ended, other than by the buffer closing at the end of a drain, after taking an event that may not have arrived. Sticky. */
    internal val deliveryUncertain: Boolean get() = uncertain
```

`feed` (replaces the `subscriptionCount` wait by the bus's own subscription callback):
```kotlin
    suspend fun feed(
        session: VizSession,
        scope: CoroutineScope,
    ): Job {
        val subscription = CompletableDeferred<Unit>()
        val job =
            scope.launch {
                var reachedCutoff = false
                session.bus
                    .stream(
                        onSubscribed = {
                            synchronized(controlLock) { subscribed = true }
                            subscription.complete(Unit)
                        },
                        onMissed = { missed ->
                            missedEntries.addAndGet(missed)
                            // The marker may be among them: end without a clean close (P-8).
                            if (synchronized(controlLock) { cutoffRequested }) abandoned = true
                        },
                    ).takeWhile { entry ->
                        if (abandoned) return@takeWhile false
                        if (entry is PollWatermark) countControl(entry.seq)
                        (entry !== cutoffMarker).also { if (!it) reachedCutoff = true }
                    }.collect { offer(it) }
                // The bus never completes: only the cutoff, or a loss after it, ends the collection.
                if (reachedCutoff) finish() else close()
            }
        subscription.await()
        return job
    }

    private fun countControl(seq: Long) {
        if (seq <= lastControlSeq) return
        missedControl.addAndGet(seq - lastControlSeq - 1)
        lastControlSeq = seq
    }
```
(The KDoc of `feed` keeps its contract: it returns once the feed's own collector is registered; say it is now
signalled by the bus's `onSubscribed`, which runs once the collector's slot exists.)

`poll` and `cutoff`:
```kotlin
    /**
     * Puts a completed poll's watermark on [session]'s bus (spec C-7), numbered as a control item. Called on the poll loop
     * right after the poll's events, so the watermark follows them. Nothing after the cutoff.
     */
    internal fun poll(
        session: VizSession,
        pollNanos: Long,
        pollEpochMs: Long,
    ) {
        synchronized(controlLock) {
            if (cutoffRequested) return
            session.bus.send(PollWatermark(session.sessionId, ++controlSeq, pollNanos, pollEpochMs))
        }
    }

    /**
     * Sets the capture cutoff (spec C-7, P-8): what [session] sent on its bus before this call is handed to the buffer,
     * nothing after it is, and none of that counts as lost. The feed then queues the final watermark and closes the
     * buffer, so a drain sends it last and returns. Before the feed subscribed nothing was captured: the buffer closes now.
     */
    internal fun cutoff(session: VizSession) {
        synchronized(controlLock) {
            if (cutoffRequested) return
            cutoffRequested = true
            if (!subscribed) {
                close()
                return
            }
            val marker = PollWatermark(session.sessionId, ++controlSeq, Long.MIN_VALUE, 0)
            cutoffMarker = marker
            session.bus.send(marker)
        }
    }
```

`offer` first line: `if (event is PollWatermark) return offerWatermark(event)`, plus:
```kotlin
    /**
     * Keeps the newest poll for the close message and forwards at most one watermark per interval (by app clock, the
     * first at once), stamped with the counts as of now. One that does not fit a full buffer is skipped: it is not an
     * event, and the next one carries the counts.
     */
    private fun offerWatermark(poll: PollWatermark) {
        latestPoll = poll
        val last = lastForwardedNanos
        if (last != null && poll.tsNanos - last < watermarkIntervalNanos) return
        if (channel.trySend(stamped(poll, final = false)).isSuccess) lastForwardedNanos = poll.tsNanos
    }

    // seq = 0: the numbering is the bus's business; the server does not read a watermark's seq.
    private fun stamped(
        poll: PollWatermark,
        final: Boolean,
    ): PollWatermark = poll.copy(seq = 0, lostEvents = lost, deliveryUncertain = uncertain, final = final)

    /**
     * The close message: the newest completed poll before the cutoff with the final counts. Without a completed poll there
     * is none (a watermark is an observation, H-1): the buffer just closes.
     */
    private suspend fun finish() {
        val poll = latestPoll
        try {
            if (poll != null) channel.send(stamped(poll, final = true))
        } catch (_: ClosedSendChannelException) {
            // close() came first: nobody is waiting for the final watermark.
        }
        close()
    }
```

`drain`:
```kotlin
    suspend fun drain(send: suspend (VizEvent) -> Unit) {
        var tookEvent = false
        var closed = false
        try {
            for (event in channel) {
                // Watermarks are transport state: not events for the counters below.
                if (event is PollWatermark) {
                    send(event)
                    continue
                }
                tookEvent = true
                dequeuedCount.incrementAndGet()
                if (overflowing) reportRecoveryIfDrained()
                send(event)
                sentCount.incrementAndGet()
            }
            closed = true
        } finally {
            // There is no delivery acknowledgement: an event this socket took may not have arrived (spec C-7, P-7).
            if (!closed && tookEvent) uncertain = true
        }
    }
```
Companion: `const val DEFAULT_WATERMARK_INTERVAL_NANOS = 1_000_000_000L // at most one watermark a second (spec C-7)`.
Class KDoc: add a "Watermarks, losses and the cutoff (spec C-7)" paragraph summarising P-5…P-9.

- [ ] **Step 4: Run** — `./gradlew :coroutine-viz-client:test --tests '*OutboundBuffer*'`; expected PASS (existing `OutboundBufferTest` too).
- [ ] **Step 5: Commit** — `git add` the test, `git commit -am "client: the outbound buffer forwards numbered watermarks with exact losses and ends with a final one (triage PR 3)"`.

---

### Task 7: Client — watermarks only to a server that takes them

**Files:**
- Modify: `coroutine-viz-client/src/main/kotlin/com/jh/proj/coroutineviz/client/IngestTransport.kt`
- Test: new `coroutine-viz-client/src/test/kotlin/com/jh/proj/coroutineviz/client/WatermarkSupportTest.kt`

- [ ] **Step 1: Write the failing tests**
```kotlin
package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** P-11: a 1.2 server would store every watermark as an event, so watermarks go only to servers that serve health. */
class WatermarkSupportTest {
    @Test
    fun `the health answer says whether the server takes watermarks`() =
        testApplication {
            routing {
                get("/api/sessions/new/health") { call.respondText("{}") }
                get("/api/sessions/trimmed/health") {
                    call.respondText("""{"error":"x","code":"history-trimmed"}""", status = HttpStatusCode.ServiceUnavailable)
                }
                get("/api/sessions/proxy/health") {
                    call.respondText("<html>bad gateway</html>", status = HttpStatusCode.ServiceUnavailable)
                }
                get("/api/sessions/denied/health") { call.respondText("", status = HttpStatusCode.Forbidden) }
            }
            val http = createClient { }
            assertEquals(true, takesWatermarks(http, "", "new", "t", 5_000))
            assertEquals(true, takesWatermarks(http, "", "trimmed", "t", 5_000))
            assertEquals(false, takesWatermarks(http, "", "old", "t", 5_000), "404: a server before session health")
            assertNull(takesWatermarks(http, "", "proxy", "t", 5_000))
            assertNull(takesWatermarks(http, "", "denied", "t", 5_000))
        }

    @Test
    fun `a socket to an older server carries the events and no watermark`() =
        testApplication {
            val received = CopyOnWriteArrayList<VizEvent>()
            application {
                install(ServerWebSockets)
                routing {
                    webSocket("/api/sessions/{id}/ingest") {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                received += appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), frame.readText())
                            }
                        }
                    }
                }
            }
            val http = createClient { install(WebSockets) }
            val session = VizSession("old")
            val scope = CoroutineScope(SupervisorJob())
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            buffer.poll(session, 1, 1)
            session.send(CoroutineCreated("old", 1, 1, "c1", "j1", null, "sc", "e1"))
            buffer.poll(session, 2_000_000_000, 2)
            buffer.cutoff(session)
            val transport = scope.launch { stream(http, "", "old", "t", buffer, IngestTimeouts(), sendWatermarks = false) }
            transport.join() // the drain ends at the cutoff, the socket closes normally
            pollFor { received.takeIf { it.isNotEmpty() } }
            assertEquals(listOf("CoroutineCreated"), received.map { it.kind }, "the final watermark is held back too")
            scope.cancel()
            session.close()
        }
}
```

- [ ] **Step 2: Run** — `./gradlew :coroutine-viz-client:test --tests '*WatermarkSupportTest*'`; expected: compilation fails.

- [ ] **Step 3: Implement** (in `IngestTransport.kt`; imports `PollWatermark`, `io.ktor.client.statement.bodyAsText`):
```kotlin
/** The 503 codes a session-health route answers with; a 503 without one is a proxy's, and says nothing. */
private val HEALTH_UNAVAILABLE_CODES = listOf("summaries-unavailable", "history-trimmed")

/**
 * Whether the backend takes poll watermarks as transport state (spec 2026-10-21, C-7): it serves session health
 * (`GET …/health`, vizcore 1.3). A 1.2 server knows the watermark type but stores every decoded frame as a session
 * event, so a watermark a second would fill its bounded store. `true` for `200`, or `503` with a health code; `false`
 * for `404` (a server without session health); `null` when the answer says nothing (no answer within [timeoutMillis],
 * a refused or failed request, an auth or proxy error), so that connection sends no watermark and the next one asks
 * again.
 */
@Suppress("TooGenericExceptionCaught") // an answer that cannot be read says nothing
internal suspend fun takesWatermarks(
    httpClient: HttpClient,
    backendUrl: String,
    sessionId: String,
    token: String,
    timeoutMillis: Long,
): Boolean? =
    withTimeoutOrNull(timeoutMillis) {
        try {
            val response =
                httpClient.get {
                    url {
                        if (backendUrl.isNotBlank()) takeFrom(backendUrl)
                        appendPathSegments("api", "sessions", sessionId, "health")
                    }
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            when (response.status) {
                HttpStatusCode.OK -> true
                HttpStatusCode.NotFound -> false
                HttpStatusCode.ServiceUnavailable -> true.takeIf { HEALTH_UNAVAILABLE_CODES.any { it in response.bodyAsText() } }
                else -> null
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }
```
(`withTimeoutOrNull` yields `null` on timeout and for a `null` answer alike — both mean "not known". A timeout inside
is the block's own `TimeoutCancellationException`, which `withTimeoutOrNull` turns into `null`; an outer cancellation
propagates.)

The internal `stream(...)` gains `sendWatermarks: Boolean = true` (after `lifetime`), passed to `connectAndDrain`; the
drain lambda becomes:
```kotlin
                            buffer.drain { event ->
                                // An older server would store a watermark as an event (P-11): held back, not counted.
                                if (event is PollWatermark && !sendWatermarks) return@drain
                                send(Frame.Text(appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)))
                            }
```
`SocketLifetime` gains:
```kotlin
    /** Whether the socket opened and has not ended. */
    val isOpen: Boolean get() = openedAt != UNSET && endedAt == UNSET
```

- [ ] **Step 4: Run** — `./gradlew :coroutine-viz-client:test --tests '*WatermarkSupportTest*' --tests '*IngestTransport*'`; expected PASS.
- [ ] **Step 5: Commit** — `git add` the test, `git commit -am "client: send watermarks only to a server that serves session health (triage PR 3)"`.

---

### Task 8: Client — poll watermarks, and a `stop()` that drains and closes cleanly

**Files:**
- Modify: `coroutine-viz-client/src/main/kotlin/com/jh/proj/coroutineviz/client/VizcoreClient.kt`
- Test: new `coroutine-viz-client/src/test/kotlin/com/jh/proj/coroutineviz/client/VizcoreClientStopTest.kt`,
  `…/ClientBinaryCompatibilityTest.kt`; existing tests that call `stop()` inside `testApplication` call it off the test
  thread (`withContext(Dispatchers.IO) { client.stop() }`) where they now wait for a drain.

- [ ] **Step 1: Write the failing tests**

`ClientBinaryCompatibilityTest` (`VizcoreClient keeps its 0_2_x members`) add: `client.getMethod("stop", long)`.

`VizcoreClientStopTest.kt`:
```kotlin
package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.source.InstrumentationSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `stop()` drains and closes with a final watermark (spec 2026-10-21, C-7; P-10). */
class VizcoreClientStopTest {
    private class FakeSource : InstrumentationSource {
        override val sourceId = "fake"
        private val started = AtomicBoolean(false)
        override val isRunning: Boolean get() = started.get()

        override fun start() = started.set(true)

        override fun stop() = started.set(false)
    }

    private fun event(n: Int): VizEvent = CoroutineCreated("s", n.toLong(), n.toLong(), "c$n", "j$n", null, "sc", "e$n")

    /** A client whose transport opens a socket that never takes anything; [opened] counts down when it is open. */
    private fun stalledClient(opened: CountDownLatch, scopeJob: Job, source: FakeSource = FakeSource()) =
        VizcoreClient(
            httpClient = HttpClient(),
            backendUrl = "",
            sessionId = "s",
            token = "t",
            session = VizSession("s"),
            source = source,
            scope = CoroutineScope(Dispatchers.Default + scopeJob),
            transport = { _, lifetime ->
                lifetime.opened()
                opened.countDown()
                awaitCancellation()
            },
        )

    @Test
    fun `stop delivers every captured event and closes with the final watermark last`() =
        testApplication {
            val received = CopyOnWriteArrayList<VizEvent>()
            val connected = AtomicBoolean(false)
            val closed = AtomicBoolean(false)
            application {
                install(ServerWebSockets)
                routing {
                    get("/api/sessions/s1/health") { call.respondText("{}") }
                    webSocket("/api/sessions/{id}/ingest") {
                        connected.set(true)
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                received += appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), frame.readText())
                            }
                        }
                        closed.set(true)
                    }
                }
            }
            val local = VizSession("s1")
            val source = FakeSource()
            val buffer = OutboundBuffer()
            val client =
                VizcoreClient(
                    httpClient = createClient { install(WebSockets) },
                    backendUrl = "",
                    sessionId = "s1",
                    token = "t",
                    session = local,
                    source = source,
                    scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                    buffer = buffer,
                )
            client.start()
            pollFor { if (source.isRunning && connected.get()) Unit else null }
            buffer.poll(local, 1_000, 1)
            repeat(500) { local.send(event(it)) }
            buffer.poll(local, 2_000, 2)

            val started = System.nanoTime()
            withContext(Dispatchers.IO) { client.stop() }
            assertTrue(System.nanoTime() - started < 5_000_000_000L, "within the deadline")

            pollFor { if (closed.get()) Unit else null }
            assertEquals(500, received.count { it !is PollWatermark }, "every event captured before stop() arrives")
            val last = received.last() as PollWatermark
            assertTrue(last.final, "the close message comes last")
            assertEquals(2_000, last.tsNanos, "it carries the newest poll")
            assertEquals(0, last.lostEvents)
        }

    @Test
    fun `stop without an open socket does not wait`() {
        val client =
            VizcoreClient(
                httpClient = HttpClient(),
                backendUrl = "",
                sessionId = "s",
                token = "t",
                session = VizSession("s"),
                source = FakeSource(),
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                transport = { _, _ -> awaitCancellation() }, // never opens a socket
            )
        client.start()
        val started = System.nanoTime()
        client.stop()
        assertTrue(System.nanoTime() - started < 1_000_000_000L)
    }

    @Test
    fun `stop gives up after its deadline when the socket does not drain`() {
        val opened = CountDownLatch(1)
        val scopeJob = SupervisorJob()
        val client = stalledClient(opened, scopeJob)
        client.start()
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        client.stop(300)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs in 250..2_000, "waited for the 300 ms deadline, took $tookMs ms")
        assertFalse(scopeJob.isActive)
    }

    @Test
    fun `an interrupt ends the wait, keeps the interrupt, and still releases everything`() {
        val opened = CountDownLatch(1)
        val scopeJob = SupervisorJob()
        val source = FakeSource()
        val client = stalledClient(opened, scopeJob, source)
        client.start()
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        var interruptKept = false
        val stopper =
            thread {
                client.stop(30_000)
                interruptKept = Thread.currentThread().isInterrupted
            }
        Thread.sleep(200)
        stopper.interrupt()
        stopper.join(5_000)
        assertFalse(stopper.isAlive, "stop() returned")
        assertTrue(interruptKept)
        assertFalse(source.isRunning)
        assertFalse(scopeJob.isActive)
    }

    @Test
    fun `start after stop does nothing`() {
        val source = FakeSource()
        val client =
            VizcoreClient(
                httpClient = HttpClient(),
                backendUrl = "",
                sessionId = "s",
                token = "t",
                session = VizSession("s"),
                source = source,
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                transport = { _, _ -> awaitCancellation() },
            )
        client.start()
        client.stop()
        client.start()
        Thread.sleep(100)
        assertFalse(source.isRunning)
    }
}
```

- [ ] **Step 2: Run** — `./gradlew :coroutine-viz-client:test --tests '*VizcoreClientStopTest*' --tests '*ClientBinaryCompatibilityTest*'`; expected: compilation fails (`stop(Long)`).

- [ ] **Step 3: Implement** (in `VizcoreClient`):

Default `transport`:
```kotlin
    private val transport: suspend (sessionId: String, lifetime: SocketLifetime) -> Unit = { id, lifetime ->
        val timeouts = IngestTimeouts()
        // Asked before every connection (P-11): a server upgraded or replaced meanwhile answers anew.
        val watermarks = takesWatermarks(httpClient, backendUrl, id, token, timeouts.handshakeMillis) == true
        stream(httpClient, backendUrl, id, token, buffer, timeouts, lifetime, sendWatermarks = watermarks)
    },
```
Fields:
```kotlin
    // Set for good by stop(): the scope is cancelled then, so a client never runs again.
    @Volatile
    private var stopped = false

    // The reconnect loop, joined by stop() while it finishes the open socket.
    @Volatile
    private var loopJob: Job? = null

    // The current connection attempt's socket; stop() waits for delivery only through an open one.
    private val liveSocket = AtomicReference<SocketLifetime?>(null)
```
`start()`: `if (running || stopped) return` inside the lock; `loopJob = scope.launch { reconnectLoop() }`.
`streamOnce`: `liveSocket.set(lifetime)` before `transport(…)`, and `liveSocket.compareAndSet(lifetime, null)` in a
`finally` around the `try`/`catch`.
`createFirstSession`'s `LinkageError` branch: `stop(0)` (it runs on the loop itself, which must not wait for itself).

`stop`:
```kotlin
    /** [stop] with the default deadline, [DEFAULT_STOP_TIMEOUT_MILLIS]. */
    fun stop() = stop(DEFAULT_STOP_TIMEOUT_MILLIS)

    /**
     * Stop capturing and close the connection (spec 2026-10-21, C-7). Sets the capture cutoff first: everything the app's
     * coroutines produced before it is sent, nothing after it. With a socket open, it then waits — blocking the caller,
     * at most [timeoutMillis] from the call — while the buffer drains, a final watermark with the final loss count goes
     * out, and the socket closes normally: the backend reads a clean close. It never reconnects to finish. If the
     * deadline passes first, the calling thread is interrupted, or no socket is open, it closes without that message,
     * and the backend reads delivery as uncertain. Then it stops the source (releasing DebugProbes takes its own time,
     * as before), closes the buffer and the HTTP client and cancels the scope. Idempotent; a stopped client never starts
     * again. An app that must not wait passes 0.
     *
     * @throws IllegalArgumentException when [timeoutMillis] is negative.
     */
    fun stop(timeoutMillis: Long) {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative, was $timeoutMillis" }
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        val socketOpen =
            synchronized(lifecycleLock) {
                if (!running) return
                running = false
                stopped = true
                liveSocket.get()?.isOpen == true
            }
        try {
            buffer.cutoff(session)
            if (socketOpen) awaitDelivery(deadline, timeoutMillis)
        } finally {
            synchronized(lifecycleLock) { source.stop() }
            buffer.close()
            httpClient.close()
            scope.cancel()
        }
    }

    /** Wait until [deadlineNanos] for the reconnect loop to finish the open socket: drain, final watermark, close. */
    private fun awaitDelivery(
        deadlineNanos: Long,
        timeoutMillis: Long,
    ) {
        val loop = loopJob ?: return
        val remainingMillis = (deadlineNanos - System.nanoTime()) / NANOS_PER_MILLI
        val delivered =
            try {
                remainingMillis > 0 && runBlocking { withTimeoutOrNull(remainingMillis) { loop.join() } } != null
            } catch (_: InterruptedException) {
                // The caller wants out: keep its interrupt and release everything without waiting.
                Thread.currentThread().interrupt()
                false
            }
        if (!delivered) {
            logger.warn(
                "vizcore could not send every event within {} ms of stop(); the session will say events may have been lost",
                timeoutMillis,
            )
        }
    }
```
Companion: `/** How long [stop] waits for the last events to be sent. */ const val DEFAULT_STOP_TIMEOUT_MILLIS: Long = 5_000`;
file-private `private const val NANOS_PER_MILLI = 1_000_000L`.

Factory `start(…httpClient…)`: create `val outbound = OutboundBuffer()`, pass `buffer = outbound` to the constructor,
and after `probes` is built: `probes.onPollCompleted = { nanos, epochMs -> outbound.poll(local, nanos, epochMs) }`
(comment: "each completed poll's watermark, after that poll's events (spec C-7)").
Update the class KDoc (stop drains; watermarks).

- [ ] **Step 4: Run** — `./gradlew :coroutine-viz-client:test`; expected PASS. Where an existing test now waits for a
  drain inside `testApplication` (its server shares the test thread), move only its `stop()` call off the thread with
  `withContext(Dispatchers.IO) { client.stop() }`; no assertion changes.
- [ ] **Step 5: Commit** — `git add` the test, `git commit -am "client: watermark every poll, and stop() drains and closes with a final watermark (triage PR 3)"`.

---

### Task 9: Client — origin and capture-once with real DebugProbes, each in a fresh JVM

**Files:**
- Modify: `coroutine-viz-client/build.gradle.kts` (test dependency `kotlinx-coroutines-debug`; `freshJvmTest`)
- Modify: `coroutine-viz-client/src/test/kotlin/com/jh/proj/coroutineviz/sdkapp/FakeBackend.kt` (record `origin`,
  `coroutineId`, every frame kind; answer `GET …/health`)
- Create: `…/sdkapp/SdkOriginTest.kt`, `…/sdkapp/NoCreationStacksTest.kt`, `…/sdkapp/WrapperCapturedOnceTest.kt`

- [ ] **Step 1: Build wiring** (`coroutine-viz-client/build.gradle.kts`):
```kotlin
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.10.2")
…
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("fresh-jvm") }
}

// Tests that read process-wide state — which JVM-wide coroutines exist (Ktor's nonce generator), DebugProbes'
// creation-stack flag — each get a JVM of their own (P-15).
val freshJvmTest by tasks.registering(Test::class) {
    description = "Runs the @Tag(\"fresh-jvm\") tests, one JVM per test class."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("fresh-jvm") }
    forkEvery = 1
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
tasks.named("check") { dependsOn(freshJvmTest) }
```

- [ ] **Step 2: Write the tests**

`FakeBackend`: `Created` becomes `Created(val label: String?, val fileName: String?, val origin: CoroutineOrigin?, val coroutineId: String)`;
the frame callback records `events[event.coroutineId] = Created(event.label, event.creationPoint?.fileName, event.origin, event.coroutineId)`
and appends every decoded frame's `kind` to a new `val kinds: List<String>` (a `CopyOnWriteArrayList`); a new branch
answers `GET /api/sessions/$sessionId/health` with `200 {}` (`Content-Length: 2`, `Connection: close`).

`SdkOriginTest.kt`:
```kotlin
package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Origin as an app sees it (spec "Origin", P-1), in a JVM of its own with DebugProbes installed before Ktor runs, so the
 * SDK's leftovers are created — and captured — by this client: Ktor's process-wide nonce generator is VIZCORE (created
 * by the SDK's connection), the socket reader and writer stay APP (no SDK frame on their creation stack), and the app's
 * coroutine is APP. Lives outside the SDK's packages, as an app does.
 */
@Tag("fresh-jvm")
class SdkOriginTest {
    @Test
    fun `the app's coroutines are APP, the SDK's leftovers as verified`() {
        DebugProbesInstall.acquire()
        var server: FakeBackend? = null
        var client: VizcoreClient? = null
        val app = Job()
        try {
            val backend = FakeBackend().also { server = it }
            val started = VizcoreClient.start(appName = "origin", backendUrl = "http://localhost:${backend.port}", token = "t")
            client = started
            CoroutineScope(Dispatchers.Default + app).launch(CoroutineName("app-work")) { awaitCancellation() }
            listOf("app-work", "nonce-generator", "cio-from-nio-reader", "cio-to-nio-writer").forEach(backend::awaitLabel)
            backend.awaitKind("PollWatermark")

            val byLabel = backend.created.groupBy { it.label }
            assertEquals(CoroutineOrigin.APP, byLabel.getValue("app-work").single().origin)
            assertEquals(setOf(CoroutineOrigin.VIZCORE), byLabel.getValue("nonce-generator").map { it.origin }.toSet())
            assertEquals(setOf(CoroutineOrigin.APP), byLabel.getValue("cio-from-nio-reader").map { it.origin }.toSet())
            assertEquals(setOf(CoroutineOrigin.APP), byLabel.getValue("cio-to-nio-writer").map { it.origin }.toSet())
            assertTrue(backend.created.none { it.origin == null }, "every DebugProbes coroutine says its origin")
        } finally {
            app.cancel()
            client?.stop()
            server?.close()
            DebugProbesInstall.release()
        }
    }
}
```
(`FakeBackend.awaitKind(kind)` waits like `awaitLabel` on `kinds`.)

`NoCreationStacksTest.kt`:
```kotlin
package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Without creation stacks (another party installed DebugProbes first without them, #217) a coroutine's origin is
 * UNKNOWN (P-2). DebugProbes read the flag at each coroutine's creation, so turning it off after the install is that case.
 */
@Tag("fresh-jvm")
class NoCreationStacksTest {
    @Test
    fun `a coroutine created without a creation stack is unknown`() {
        DebugProbesInstall.acquire()
        var server: FakeBackend? = null
        var client: VizcoreClient? = null
        val app = Job()
        try {
            val backend = FakeBackend().also { server = it }
            client = VizcoreClient.start(appName = "no-stacks", backendUrl = "http://localhost:${backend.port}", token = "t")
            DebugProbes.enableCreationStackTraces = false
            CoroutineScope(Dispatchers.Default + app).launch(CoroutineName("stackless")) { awaitCancellation() }
            backend.awaitLabel("stackless")
            assertEquals(CoroutineOrigin.UNKNOWN, backend.created.single { it.label == "stackless" }.origin)
        } finally {
            DebugProbes.enableCreationStackTraces = true
            app.cancel()
            client?.stop()
            server?.close()
            DebugProbesInstall.release()
        }
    }
}
```

`WrapperCapturedOnceTest.kt`:
```kotlin
package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A coroutine an app launches through `VizScope` into the client's session is captured once, by its wrapper events, as
 * the app's; a coroutine its body launches with the body's context inherits the wrapper's element and is captured by
 * DebugProbes (spec "Origin", P-3).
 */
@Tag("fresh-jvm")
class WrapperCapturedOnceTest {
    @Test
    fun `vizLaunch and vizAsync are captured once and are the app's`() {
        DebugProbesInstall.acquire()
        var server: FakeBackend? = null
        var client: VizcoreClient? = null
        var scope: VizScope? = null
        try {
            val backend = FakeBackend().also { server = it }
            val started = VizcoreClient.start(appName = "once", backendUrl = "http://localhost:${backend.port}", token = "t")
            client = started
            val viz = VizScope(started.session).also { scope = it }
            runBlocking {
                viz.vizLaunch(label = "wrapped-launch", context = CoroutineName("wrapped-launch")) {
                    coroutineScope {
                        launch(CoroutineName("inheriting-child")) { awaitCancellation() }
                    }
                }
                viz.vizAsync(label = "wrapped-async", context = CoroutineName("wrapped-async")) { awaitCancellation() }
            }
            listOf("wrapped-launch", "wrapped-async", "inheriting-child").forEach(backend::awaitLabel)
            Thread.sleep(500) // several more polls: a dp- duplicate would have arrived by now

            val byLabel = backend.created.groupBy { it.label }
            for (label in listOf("wrapped-launch", "wrapped-async")) {
                val captured = byLabel.getValue(label)
                assertEquals(1, captured.size, "$label captured once: $captured")
                assertTrue(captured.single().coroutineId.startsWith("coroutine-"), "by its wrapper events")
                assertEquals(CoroutineOrigin.APP, captured.single().origin)
            }
            assertTrue(byLabel.getValue("inheriting-child").single().coroutineId.startsWith("dp-"))
        } finally {
            scope?.cancel()
            client?.stop()
            server?.close()
            DebugProbesInstall.release()
        }
    }
}
```

- [ ] **Step 3: Run** — `./gradlew :coroutine-viz-client:freshJvmTest :coroutine-viz-client:test --tests '*SdkOwnCoroutinesTest*'`;
  expected PASS with Tasks 1–8 in (these guard the integration). If `awaitLabel` for a leftover times out, record
  which and why (the plan's P-1 verification says all three exist in a fresh JVM) before changing the assertion.
- [ ] **Step 4: Commit** — `git add` the new tests and build file, `git commit -am "client: origin and capture-once with real DebugProbes, each in a fresh JVM (triage PR 3)"`.

---

### Task 10: Backend — the real SDK against the real backend: evaluated while connected, clean after stop

**Files:**
- Create: `backend/src/test/kotlin/com/jh/proj/coroutineviz/SdkSessionHealthTest.kt` (test only; no server code)

- [ ] **Step 1: Write the test**
```kotlin
package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.auth.ApiKeyStore
import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.routes.IngestStates
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.HttpClient as KtorClient

/**
 * Triage PR 3 end to end in one JVM: [VizcoreClient] against the real backend [module], secured with an API key (so the
 * SDK's `GET …/health` capability check runs with a runner key). While connected the SDK's watermarks make possibly
 * blocked evaluated and origins reported; after `stop()` the close was clean, so nothing reads "events may have been lost".
 */
class SdkSessionHealthTest {
    private val apiKey = "sdk-health-test-key"
    private var server: EmbeddedServer<*, *>? = null
    private var client: VizcoreClient? = null
    private val app = Job()

    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        app.cancel()
        client?.stop()
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
        SessionManager.clearAll()
        IngestStates.clear()
    }

    private fun startBackend(): String {
        val started =
            embeddedServer(
                Netty,
                environment =
                    applicationEnvironment {
                        config =
                            MapApplicationConfig(
                                "auth.keys.size" to "1",
                                "auth.keys.0.name" to "sdk",
                                "auth.keys.0.hash" to ApiKeyStore.sha256Hex(apiKey),
                                "auth.keys.0.role" to "runner",
                            )
                    },
                configure = {
                    connector {
                        host = "127.0.0.1"
                        port = 0
                    }
                },
                module = Application::module,
            ).start(wait = false)
        server = started
        val port = runBlocking { started.engine.resolvedConnectors().first().port }
        return "http://127.0.0.1:$port"
    }

    private suspend fun KtorClient.json(url: String): JsonObject =
        Json.parseToJsonElement(get(url) { header("X-API-Key", apiKey) }.bodyAsText()).jsonObject

    private fun JsonObject.str(vararg path: String): String? {
        var node: JsonObject = this
        path.dropLast(1).forEach { node = node[it]?.jsonObject ?: return null }
        return node[path.last()]?.jsonPrimitive?.content
    }

    @Test
    fun `possibly blocked is evaluated while connected and the close is clean`() {
        val backendUrl = startBackend()
        val started = VizcoreClient.start(appName = "sdk-health", backendUrl = backendUrl, token = apiKey)
        client = started
        CoroutineScope(Dispatchers.Default + app).launch(CoroutineName("health-app-work")) { awaitCancellation() }
        val id = started.currentSessionId
        val health = "$backendUrl/api/sessions/$id/health"
        KtorClient(CIO).use { http ->
            runBlocking {
                // The exact state asserted below: a watermark has been applied and an app coroutine counted.
                val connected =
                    withTimeout(15_000) {
                        while (true) {
                            val h = http.json(health)
                            val ready =
                                h.str("possiblyBlocked", "status") == "EVALUATED" && h.str("asOf", "kind") == "WATERMARK" &&
                                    (h["counts"]!!.jsonObject["app"]!!.jsonPrimitive.int > 0)
                            if (ready) return@withTimeout h
                            delay(100)
                        }
                        @Suppress("UNREACHABLE_CODE")
                        error("unreachable")
                    }
                assertEquals("REPORTED", connected.str("sessionOrigin"))

                started.stop()
                client = null
                assertFalse(http.json("$backendUrl/api/sessions/$id")["appConnected"]!!.jsonPrimitive.boolean)
                val after = http.json(health)
                val transport = after["transport"]!!.jsonObject
                assertFalse(transport["deliveryUncertain"]!!.jsonPrimitive.boolean, "a clean close: $transport")
                assertEquals(0L, transport["lostEvents"]!!.jsonPrimitive.long)
                assertFalse(after["failuresAtLeast"]!!.jsonPrimitive.boolean)
                assertEquals("EVALUATED", after.str("possiblyBlocked", "status"))
                assertTrue(after["counts"]!!.jsonObject["app"]!!.jsonPrimitive.int > 0)
            }
        }
    }
}
```
(If the module reads `appConnected` asynchronously after the socket closes, poll the session read for
`appConnected == false` with the same 15 s bound before the `after` assertions.)

- [ ] **Step 2: Run** — `./gradlew :test --tests '*SdkSessionHealthTest*'`; expected PASS with Tasks 1–8 in. To confirm
  it tests the change: with the factory's `probes.onPollCompleted = …` line commented out it fails (never
  `EVALUATED`); restore.
- [ ] **Step 3: Commit** — `git add src/test/kotlin/com/jh/proj/coroutineviz/SdkSessionHealthTest.kt && git commit -m "test: the SDK against the backend reads evaluated while connected and clean after stop (triage PR 3)"`

---

### Task 11: Sample app, docs, changelog

**Files:**
- Modify: `examples/spring-vizcore-demo/src/main/kotlin/com/jh/vizcore/demo/SpringVizcoreDemoApplication.kt`
- Modify: `README.md` ("Instrument your own app" limits), `CHANGELOG.md` (`[Unreleased]` → `### Added`)

- [ ] **Step 1: Sample** — `VizcoreDemoRunner` gets `@param:Value("\${vizcore.busy-ms:0}") private val busyMs: Long`; when
  `busyMs > 0`, right after the client starts:
```kotlin
		if (busyMs > 0) {
			// Holds a Default thread without suspending: what the Problems tab calls "possibly blocked" (over 10 s running).
			CoroutineScope(Dispatchers.Default).launch(CoroutineName("busy-worker")) {
				logger.info("busy-worker blocks its thread for {} ms", busyMs)
				Thread.sleep(busyMs)
			}
		}
```
  (imports `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.launch`.)

- [ ] **Step 2: README** — replace the `stop()` bullet ("drops events that have not been sent yet … #207") with:
  "`stop()` sends what the app produced before it, then a closing message, and only then disconnects; it waits at most
  5 s (`stop(timeoutMillis)` to change that; `stop(0)` does not wait), so call it from a shutdown hook. Without that
  message, or after a lost connection, the session says events may have been lost." Extend the #225 bullet: "Of those,
  the session counts Ktor's `nonce-generator` as vizcore's own connection when the SDK created it; the socket reader
  and writer are created where nothing names the SDK, so they count as the app's (they only show as long-lived)." Add:
  "Each poll also tells the backend when it looked (at most once a second), which is what lets the session say a
  coroutine may be blocked; a server without session health (before vizcore 1.3) is not sent these."

- [ ] **Step 3: CHANGELOG** — under `[Unreleased]` / `### Added`:
  "- SDK: the session can now judge an attached app. Each poll tells the backend when it looked (at most once a second),
  so a coroutine running for over 10 s reads as possibly blocked; `stop()` sends the remaining events and a closing
  message within 5 s (`stop(timeoutMillis)`), so a clean shutdown no longer reads \"events may have been lost\"; every
  coroutine says whether it is the app's or vizcore's own connection (Ktor's `nonce-generator`), or unknown when
  DebugProbes run without creation stacks; suspension points say whether the frame was observed or is the creation
  site; a coroutine launched through `VizScope` into the client's session is reported once. Servers without session
  health are not sent the poll messages."

- [ ] **Step 4: Build the sample** — `cd backend && ./gradlew :coroutine-viz-client:jar :coroutine-viz-core:jar && cd ../examples/spring-vizcore-demo && ./gradlew bootJar -q` (expected: BUILD SUCCESSFUL).
- [ ] **Step 5: Commit** — `git commit -am "docs: the SDK's watermark, clean stop and origin; sample busy worker (triage PR 3)"`

---

### Task 12: Gates and end-to-end check

- [ ] **Step 1: Full gate** — from `backend/`:
  `./gradlew ktlintCheck detekt test checkBytecode build :coroutine-viz-core:build :coroutine-viz-client:test :coroutine-viz-client:freshJvmTest`
  Expected: BUILD SUCCESSFUL; record the test counts from `build/test-results/**.xml`. Revert `docs/index.html` if
  regenerated. Fix ktlint with `./gradlew ktlintFormat` and detekt findings in the code (no baseline additions).

- [ ] **Step 2: End to end** (port **18951** only). Outputs to
  `/private/tmp/claude-501/-Users-jirihermann-Documents-workspace-vizcore/9e9c5bc5-1b4c-412c-966d-6cf483ca2458/scratchpad/pr3-e2e/`.
  1. Backend on 18951, auth off, memory mode; log to `backend.log`.
  2. Sample: `java -jar examples/spring-vizcore-demo/build/libs/spring-vizcore-demo-*.jar --vizcore.backend-url=http://localhost:18951 --vizcore.busy-ms=15000 > app.log`.
  3. Session id from `GET /api/sessions` (app name `spring-vizcore-demo`); at ~5 s and ~13 s save
     `GET /api/sessions/{id}/health` → `health-connected-5s.json`, `health-connected-13s.json`: at 13 s
     `possiblyBlocked.status = EVALUATED`, a `POSSIBLY_BLOCKED` incident for `busy-worker` with `runningForNanos` > 10 s,
     `asOf.kind = WATERMARK`; `counts.vizcore` ≥ 1 (`nonce-generator`) and `counts.app` the rest; save
     `GET /api/sessions/{id}` → `session-connected.json`.
  4. `kill -TERM <app pid>` (shutdown hook → `stop()`); save `health-after-stop.json` and `session-after-stop.json`:
     `appConnected = false`, `transport.deliveryUncertain = false`, `lostEvents = 0`, `failuresAtLeast = false`.
  5. Contrast: the same against an app killed with `kill -9` → `transport.deliveryUncertain = true`.
  6. Stop the backend; `lsof -i :18951` shows nothing. Summarise in `pr3-e2e/README.md`.

---

## Compatibility evidence to collect for the PR

- `BinaryCompatibilityTest` (core) green; `CoroutineSnapshot`'s public API unchanged (P-2b; `javap` before/after).
- `ClientBinaryCompatibilityTest` green, with `stop(long)` added and every older signature unchanged.
- `checkBytecode` green (JVM 17 class files, no Ktor in core).
- `HealthWireTypesTest` (from #248) still decodes an older SDK's events (no origin/provenance).
- `WatermarkSupportTest`: no watermark frames to a server without session health; events unchanged.
- An older SDK with the new server is #295's `IngestWatermarkTest`/D-2 behaviour, unchanged.

## Known, not in this PR

- `cio-from-nio-reader`, `cio-to-nio-writer` and `cio-request-timeout` stay `APP` (P-1); they show only as long-lived.
- `lostEvents` is per client, so a replacement session (#129) inherits the earlier session's count (overstates, P-6).
- A session that dropped its connection reads delivery uncertain for the rest of the session (server rule, D-2).
- If the bus evicts entries for the feed after `stop()` began, the close is not clean (P-8): losses happened anyway.
- `stop()` blocks the caller up to its deadline; an app that must not block calls `stop(0)`.
- The SDK version bump and publication (release PR); the Problems tab and the "vizcore connection (n)" toggle (Parts 6/7).
