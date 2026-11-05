# Triage PR 3 — SDK: watermark, origin, provenance — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the SDK send what the session-triage spec (`docs/superpowers/specs/2026-10-21-session-triage-design.md`,
"Definitions" and C-7) defines, so the health API that Part 5a (#295) put on the server reads attached apps correctly:
a poll watermark at most once per second, a final watermark on a clean `stop()`, the origin of each coroutine, and the
provenance of each suspension point.

**Architecture:** Core's DebugProbes path computes origin (creation stack) and provenance (which frame it found), skips
coroutines a `VizScope` of the same session already reports, and reports each completed poll through a callback. The
client turns each poll into a `PollWatermark` on the local session's bus (so it follows that poll's events in bus
order), and the outbound buffer — the one ordered path to the socket — forwards at most one per second, stamped with
the cumulative loss count. `stop()` sets a capture cutoff (a marker on the same bus), lets the buffer drain, sends a
`final` watermark and closes, within a deadline. Watermarks go only to servers that serve session health, because a
1.2 server knows the type but would store every watermark as an event.

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
- A connection closes cleanly only when a watermark with `final = true` arrived on it (D-2); otherwise delivery is
  uncertain from that connection's first seq on, and possibly blocked reads `NOT_EVALUATED (EVENTS_DROPPED)`.
- `CoroutineCreated.origin` (`APP` | `VIZCORE` | `UNKNOWN`, absent from older SDKs) and
  `CoroutineSuspended.provenance` (`REAL_FRAME` | `CREATION_FALLBACK`; `STALE` is the backend's) already decode.
- Possibly blocked: the time from a coroutine's latest start/resume to the latest watermark, both `System.nanoTime()`
  on the app (the clock `EventContext.timestamp()` uses), is over 10 s.

## Decisions

| # | Topic | Decision |
|---|---|---|
| P-1 | The leftovers (spec "Origin": "PR 3 starts by verifying this on the leftovers") | Verified with a scratch dump of `DebugProbes.dumpCoroutinesInfo()` after `VizcoreClient.start` against `FakeBackend` (2026-11-06, output kept in the PR notes): `nonce-generator`'s creation stack contains `com.jh.proj.coroutineviz.client.IngestTransportKt$connectAndDrain…` → **`VIZCORE`**. `cio-from-nio-reader` and `cio-to-nio-writer` are launched from Ktor's `Endpoint.connect` while a coroutine is resumed on a worker thread; the thread stack holds no SDK frame → they stay **`APP`**, as the spec accepts ("they only appear in Long-lived (n)"). `cio-request-timeout` was not present in the dump (it exists only while a request with a timeout runs); it is launched the same way inside the CIO engine, so it stays `APP` too. The rule is applied as written: any frame whose class starts with `com.jh.proj.coroutineviz.client.`; the wrapper package does not count. |
| P-2 | No creation stacks | A coroutine whose creation stack is empty (DebugProbes installed without creation stacks, e.g. by IntelliJ first, #217) is `UNKNOWN`. Core already makes the session origin `REPORTED` only once a coroutine reported `APP`/`VIZCORE`, so a session with no stacks reads "origin not available" and hides nothing. |
| P-3 | Wrapper coroutines captured once | Verified: today the DebugProbes path does **not** skip `VizCoroutineElement` coroutines (only `VizcoreInternal` and the transport dispatcher), so a `VizScope(client.session).vizLaunch { }` coroutine would also appear as `dp-…`. `VizCoroutineElement` gains two internal fields, `owner` (the `VizSession` of the `VizScope` that launched it) and `ownerJob` (the coroutine it was created for). The DebugProbes source of session S drops a coroutine whose element's `owner === S` and whose `ownerJob` is its own Job (or not yet set: the wrapper itself, before its body runs). Children the wrapper body launches with plain kotlinx builders inherit the element but have another Job, so they stay captured. A `VizScope` of another session is not skipped. Wrapper `CoroutineCreated` events carry `origin = APP` (the public wrapper API is app code by the spec's rule). |
| P-4 | Poll time | Taken right before the dump (`System.nanoTime()` and `System.currentTimeMillis()`). The dump observed the app no earlier than that, so "running until the watermark" never overstates (H-1). Events of the same poll carry later timestamps, so a coroutine first seen running is never flagged by its own poll's watermark. |
| P-5 | Watermark path and ordering | Core's `DebugProbesSource.onPollCompleted(pollNanos, pollEpochMs)` runs on the poll loop after every poll whose events were all sent (also when it found no change; never after a failed poll or once stopped). The client's callback sends a `PollWatermark` with `session.bus.send(…)`, bypassing the local store (one per poll would evict the local store's events). Bus order is send order, the feed is the bus's single outbound collector, so the watermark follows that poll's events into the buffer and onto the socket. The feed forwards at most one per second (by app clock; the first at once), stamped with the counts at that moment. A watermark that does not fit a full buffer is skipped, not counted as lost: it is not an event, and the next one carries the counts. |
| P-6 | `lostEvents` | Cumulative per client: outbound-buffer overflow drops plus the events the session bus evicted for the feed while it lagged (new per-subscriber `EventBus.stream(onSubscribed, onMissed)`; the bus-wide `onEvicted` would also count other subscribers' lag). Kept across a replacement session (#129): it can overstate for the new session, never understate. |
| P-7 | SDK `deliveryUncertain` | Sticky once a socket ended any way other than the buffer closing at `stop()` after it had taken at least one event (not counting watermarks): that event may not have arrived (no acknowledgement). The server already marks such a connection uncertain on its side; the flag keeps the SDK's own statement complete (C-7). |
| P-8 | Capture cutoff | `stop()` sends a private cutoff marker on the session bus. Bus order is the capture order: everything sent before the marker is handed to the buffer, everything after it is outside the capture and is neither forwarded nor counted as lost. Producers are not rejected at `VizSession.send` (the local session still records them); the effect on what is sent is the spec's, without a new `VizSession` API. |
| P-9 | Final watermark | When the feed reaches the marker it queues `final = true` carrying the newest poll it saw before the marker (also one the rate limit held back) and the final counts, then closes the buffer. No later producer can add to the counts. If no poll completed yet, it carries the cutoff time. The drain sends it last; the socket then closes normally. |
| P-10 | `stop()` deadline | `stop()` = `stop(DEFAULT_STOP_TIMEOUT_MILLIS)` (5 s); new `stop(timeoutMillis)`. It waits (blocking the caller, bounded) only when a socket is open at the cutoff: for the buffer to drain, the final watermark and the close. It never reconnects during shutdown. A missed deadline logs one WARN and closes without the final watermark, which the server reads as delivery uncertain (C-7: "if any step misses the deadline, the close reports delivery uncertain"). Without an open socket `stop()` returns at once, as today (the server already marked the last connection's end). |
| P-11 | Older servers (compatibility) | A 1.2.0 server registers `PollWatermark` as an event type (#248 is in rc3/rc4) but its ingest stores every decoded frame, so watermarks would fill its bounded store and show up as events. Before each connection the client asks `GET /api/sessions/{id}/health`: `200`, or `503` with a health code (`summaries-unavailable`, `history-trimmed`), means the server takes watermarks as transport state; `404` means an older server; anything else (no answer, an auth or proxy error) means "not known" and that connection sends none. Origin and provenance are new JSON keys: 1.1 servers ignore unknown keys (`ignoreUnknownKeys = true`), 1.2 servers know both fields and all their values. |
| P-12 | Older SDKs with the new server | Unchanged (5a D-2): no watermark → "not evaluated (no poll data)" while connected, "events may have been lost" after. |
| P-13 | Server code | None. One backend **test** runs the real SDK against the real backend module. |
| P-14 | Version | The client stays `0.3.1`; the version bump and publication belong to the release PR. |

## File map

**Core (`backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/…`)**
- `session/source/debugprobes/SourceAttribution.kt` — `origin(creationStack)` and `SDK_CLIENT_PACKAGE`.
- `session/source/debugprobes/CoroutineSnapshot.kt` — `origin` field (+ hidden 0.3.x constructor).
- `session/source/debugprobes/CoroutineInfoAdapter.kt` — fills `origin`.
- `session/source/debugprobes/DebugProbesEventSynthesizer.kt` — `origin` on `CoroutineCreated`, `provenance` on `CoroutineSuspended`.
- `session/source/debugprobes/WrapperOwnedCoroutines.kt` (new) — `excludeWrapperOwned`.
- `session/source/debugprobes/DebugProbesSource.kt` — wrapper filter in the default dump; `onPollCompleted`.
- `wrappers/VizCoroutineElement.kt` — `owner`, `ownerJob`.
- `wrappers/VizScope.kt` — sets them; `origin = APP` on wrapper `CoroutineCreated`.
- `session/EventBus.kt` — `stream(onSubscribed, onMissed)`.

**Client (`backend/coroutine-viz-client/src/main/kotlin/com/jh/proj/coroutineviz/client/…`)**
- `OutboundBuffer.kt` — watermark forwarding, loss counts, delivery uncertainty, cutoff and final.
- `IngestTransport.kt` — `takesWatermarks` probe; `sendWatermarks` per socket; `SocketLifetime.isOpen`.
- `VizcoreClient.kt` — poll → watermark wiring, probe before connect, `stop(timeoutMillis)`.

**Tests** (core, client, backend) — listed per task. **Docs** — `README.md` (SDK limits), `CHANGELOG.md`.
**Sample** — `examples/spring-vizcore-demo` gets an optional busy coroutine for the end-to-end check.

## Commands

All from `backend/` with `export JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home`.
- Core tests: `./gradlew :coroutine-viz-core:test --tests '<pattern>'`
- Client tests: `./gradlew :coroutine-viz-client:test --tests '<pattern>'`
- Backend tests: `./gradlew test --tests '<pattern>'`
- Full gate (end): `./gradlew ktlintCheck detekt test checkBytecode build :coroutine-viz-core:build :coroutine-viz-client:test`;
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
  `…/CoroutineInfoAdapterTest.kt`, `…/DebugProbesEventSynthesizerTest.kt`, new `…/DebugProbesOriginTest.kt`,
  `coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/compat/BinaryCompatibilityTest.kt`

- [ ] **Step 1: Write the failing tests**

`SourceAttributionTest.kt` (add):
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
(import `com.jh.proj.coroutineviz.events.CoroutineOrigin`.)

`CoroutineInfoAdapterTest.kt` (add):
```kotlin
    @Test
    fun `the snapshot carries the origin of its creation stack`() {
        val adapter = CoroutineInfoAdapter()
        val sdk = StackTraceElement("com.jh.proj.coroutineviz.client.IngestTransportKt", "connect", "IngestTransport.kt", 9)
        fun raw(stack: List<StackTraceElement>) = CoroutineInfoAdapter.RawInfo(CoroState.RUNNING, Job(), EmptyCoroutineContext, stack, emptyList())
        assertEquals(CoroutineOrigin.VIZCORE, adapter.toSnapshot(raw(listOf(sdk))).origin)
        assertEquals(CoroutineOrigin.APP, adapter.toSnapshot(raw(creationStack)).origin)
        assertEquals(CoroutineOrigin.UNKNOWN, adapter.toSnapshot(raw(emptyList())).origin)
    }
```
(`creationStack` is the test class's existing app-frame fixture.)

`DebugProbesEventSynthesizerTest.kt` (add; the local `snap` helper gains `origin: CoroutineOrigin? = null` passed through):
```kotlin
    @Test
    fun `CoroutineCreated carries the snapshot's origin`() {
        val s = session()
        val vizcore = synthesizer.synthesize(CoroutineDelta.Appeared(snap("a", CoroState.CREATED, origin = CoroutineOrigin.VIZCORE)), s)
        val unset = synthesizer.synthesize(CoroutineDelta.Appeared(snap("b", CoroState.RUNNING)), s)
        assertEquals(CoroutineOrigin.VIZCORE, (vizcore.first() as CoroutineCreated).origin)
        assertNull((unset.first() as CoroutineCreated).origin)
    }
```

New `DebugProbesOriginTest.kt` (real DebugProbes, like `DebugProbesFailureCaptureTest`):
```kotlin
package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** Origin from real creation stacks (spec "Origin"): a test coroutine is the app's; without creation stacks it is unknown. */
class DebugProbesOriginTest {
    @BeforeEach
    fun reset() {
        DebugProbesInstall.resetForTest()
        SessionManager.clearSessionListeners()
    }

    @AfterEach
    fun cleanup() {
        DebugProbesInstall.resetForTest()
        DebugProbes.enableCreationStackTraces = true
        SessionManager.clearSessionListeners()
    }

    private fun originOf(label: String): CoroutineOrigin? {
        val session = VizSession("origin")
        val source = DebugProbesSource(session = session, pollInterval = 20.milliseconds)
        val app = Job()
        try {
            source.start()
            CoroutineScope(Dispatchers.Default + app).launch(CoroutineName(label)) { awaitCancellation() }
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                session.store.all().filterIsInstance<CoroutineCreated>().firstOrNull { it.label == label }?.let { return it.origin }
                Thread.sleep(20)
            }
            error("$label was never captured")
        } finally {
            app.cancel()
            source.stop()
            session.close()
        }
    }

    @Test
    fun `a coroutine launched outside the SDK client is the app's`() {
        assertEquals(CoroutineOrigin.APP, originOf("app-origin"))
    }

    @Test
    fun `without creation stacks every coroutine is unknown`() {
        // As when another party (IntelliJ's debugger, #217) installed DebugProbes first without creation stacks:
        // DebugProbesInstall then leaves the install as it is.
        DebugProbes.enableCreationStackTraces = false
        DebugProbes.install()
        try {
            assertEquals(CoroutineOrigin.UNKNOWN, originOf("no-stack-origin"))
        } finally {
            runCatching { DebugProbes.uninstall() }
        }
    }
}
```

`BinaryCompatibilityTest.kt` (add): pin the `CoroutineSnapshot` constructors 0.3.1 published. Recorded with
`javap -p` on the `origin/main` classes before the change: the two public constructors are
`(String, CoroState, String, String, String, String, Integer, String, List, String, Long, String, DefaultConstructorMarker)`
and the same twelve plus `(int, DefaultConstructorMarker)` (the inline `CoroKey` parameters compile to `String`; the
marker comes from them):
```kotlin
    @Test
    fun `CoroutineSnapshot keeps its 0_3_x constructors`() {
        val str = String::class.java
        val v03 =
            arrayOf<Class<*>?>(
                str, CoroState::class.java, str, str, str, str, Integer::class.java, str, List::class.java, str,
                java.lang.Long::class.java, str,
            )
        CoroutineSnapshot::class.java.getDeclaredConstructor(*v03, marker)
        CoroutineSnapshot::class.java.getDeclaredConstructor(*v03, Int::class.javaPrimitiveType, marker)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :coroutine-viz-core:test --tests '*SourceAttributionTest*' --tests '*CoroutineInfoAdapterTest*' --tests '*DebugProbesEventSynthesizerTest*' --tests '*DebugProbesOriginTest*' --tests '*BinaryCompatibilityTest*'`
Expected: compilation fails (`origin` unresolved on `SourceAttribution`/`CoroutineSnapshot`).

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

`CoroutineSnapshot.kt`: add a last property and keep the old constructor:
```kotlin
    /** Who launched it, from the creation stack ([SourceAttribution.origin]); `null` when not determined. */
    val origin: CoroutineOrigin? = null,
) {
    /** The 0.3.x JVM constructor, kept for binary compatibility; hidden from source resolution. */
    @Deprecated("binary compatibility with 0.3.x", level = DeprecationLevel.HIDDEN)
    constructor(
        key: CoroKey,
        state: CoroState,
        label: String? = null,
        dispatcherName: String? = null,
        function: String? = null,
        fileName: String? = null,
        lineNumber: Int? = null,
        reason: String? = null,
        lastObservedStackTrace: List<StackTraceElement>? = null,
        parentKey: CoroKey? = null,
        threadId: Long? = null,
        threadName: String? = null,
    ) : this(key, state, label, dispatcherName, function, fileName, lineNumber, reason, lastObservedStackTrace, parentKey, threadId, threadName, null)
}
```

`CoroutineInfoAdapter.toSnapshot(raw: RawInfo)`: add `origin = SourceAttribution.origin(raw.creationStackTrace),` to the
`CoroutineSnapshot(...)` call.

`DebugProbesEventSynthesizer.appearedEvents`:
```kotlin
        val created = ctx.coroutineCreated(creationPoint = creationPointOf(snapshot)).copy(origin = snapshot.origin)
```
and add a KDoc line: "`origin` comes from the creation stack ([SourceAttribution.origin])".

- [ ] **Step 4: Run the tests to verify they pass**

Same command as Step 2. Expected: PASS.

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

### Task 3: Core — a wrapper coroutine is captured once, as the app's

**Files:**
- Modify: `coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizCoroutineElement.kt`, `…/wrappers/VizScope.kt`
- Create: `…/session/source/debugprobes/WrapperOwnedCoroutines.kt`
- Modify: `…/session/source/debugprobes/DebugProbesSource.kt` (default `dump`)
- Test: new `…/debugprobes/WrapperOwnedCoroutinesTest.kt`, new `…/debugprobes/WrapperCapturedOnceTest.kt`

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

    private fun kept(vararg raws: CoroutineInfoAdapter.RawInfo) = excludeWrapperOwned(raws.toList(), session).map { it.context[CoroutineName]!!.name }

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
    fun `a plain child of a wrapper inherits the element and is kept`() {
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

`WrapperCapturedOnceTest.kt` (real DebugProbes):
```kotlin
package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A coroutine launched through `VizScope.vizLaunch`/`vizAsync` into the session the DebugProbes source records is captured
 * once, by its wrapper events, as the app's; a plain child it launches is captured by DebugProbes (spec "Origin").
 */
class WrapperCapturedOnceTest {
    @BeforeEach
    fun reset() {
        DebugProbesInstall.resetForTest()
        SessionManager.clearSessionListeners()
    }

    @AfterEach
    fun cleanup() {
        DebugProbesInstall.resetForTest()
        SessionManager.clearSessionListeners()
    }

    @Test
    fun `vizLaunch and vizAsync are captured once and are the app's`() {
        val session = VizSession("once")
        val source = DebugProbesSource(session = session, pollInterval = 20.milliseconds)
        val scope = VizScope(session)
        try {
            source.start()
            runBlocking {
                scope.vizLaunch(label = "wrapped-launch", context = CoroutineName("wrapped-launch")) {
                    launch(CoroutineName("plain-child")) { awaitCancellation() }
                    awaitCancellation()
                }
                scope.vizAsync(label = "wrapped-async", context = CoroutineName("wrapped-async")) { awaitCancellation() }
            }
            val created = { session.store.all().filterIsInstance<CoroutineCreated>() }
            val deadline = System.currentTimeMillis() + 5_000
            while (created().none { it.label == "plain-child" } && System.currentTimeMillis() < deadline) Thread.sleep(20)
            Thread.sleep(200) // several more polls

            val byLabel = created().groupBy { it.label }
            assertEquals(1, byLabel["wrapped-launch"]?.size, "vizLaunch coroutine captured once: ${byLabel["wrapped-launch"]}")
            assertEquals(1, byLabel["wrapped-async"]?.size, "vizAsync coroutine captured once: ${byLabel["wrapped-async"]}")
            assertTrue(byLabel.getValue("wrapped-launch").single().coroutineId.startsWith("coroutine-"))
            assertEquals(CoroutineOrigin.APP, byLabel.getValue("wrapped-launch").single().origin)
            assertEquals(CoroutineOrigin.APP, byLabel.getValue("wrapped-async").single().origin)
            assertTrue(byLabel.getValue("plain-child").single().coroutineId.startsWith("dp-"), "the plain child stays captured")
        } finally {
            scope.cancel()
            source.stop()
            session.close()
        }
    }
}
```
(`VizScope` is a `CoroutineScope`; `scope.cancel()` is `kotlinx.coroutines.cancel` — import it.)

- [ ] **Step 2: Run to verify they fail** — `./gradlew :coroutine-viz-core:test --tests '*WrapperOwnedCoroutinesTest*' --tests '*WrapperCapturedOnceTest*'`; expected: compilation fails (`owner`, `excludeWrapperOwned` unresolved).

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
     * The coroutine this element was made for. Children launched with plain kotlinx builders inherit the element but
     * are other coroutines, and DebugProbes still captures them. `null` until the wrapper records it.
     */
    @Volatile
    internal var ownerJob: Job? = null
```

`VizScope.vizLaunch`: `val vizElement = VizCoroutineElement(...).also { it.owner = session }`; right after
`val job = targetScope.launch(mergedContext, start = CoroutineStart.LAZY) { … }` add
`vizElement.ownerJob = job // before job.start(): no child exists yet`; and
`session.send(ctx.coroutineCreated().copy(origin = CoroutineOrigin.APP))` (the public wrapper API is app code).

`VizScope.vizAsync`: `.also { it.owner = session }` on the element; first statement of the `async` body after
`val currentJob = …`: `vizElement.ownerJob = currentJob // before the body can launch a child`; and
`session.send(ctx.coroutineCreated().copy(origin = CoroutineOrigin.APP))`.

`WrapperOwnedCoroutines.kt`:
```kotlin
package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizCoroutineElement

/**
 * Leaves out of a dump the coroutines a [com.jh.proj.coroutineviz.wrappers.VizScope] of [session] launched: their wrapper
 * events already describe them, so each is captured once (spec 2026-10-21, "Origin"). A coroutine whose element was
 * made for it ([VizCoroutineElement.ownerJob] is its Job, or not recorded yet: the wrapper before its body runs) is the
 * wrapper's; a plain child inherits the element with its parent's Job and stays. A `VizScope` of another session is not
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
- [ ] **Step 5: Commit** — `git commit -am "core: a VizScope coroutine is captured once, as the app's (triage PR 3)"` (add the new files first).

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
            var tick = 0
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { if (tick++ == 0) listOf(snap("a", CoroState.RUNNING)) else listOf(snap("a", CoroState.RUNNING)) },
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
            assertEquals(true, slowMissed > 0)
            slow.cancel()
            fast.cancel()
        }
}
```

- [ ] **Step 2: Run** — `./gradlew :coroutine-viz-core:test --tests '*EventBusMissedTest*'`; expected: compilation fails.

- [ ] **Step 3: Implement** — move the body of `stream(onSubscribed)` into a private `stream(onSubscribed, onMissed: ((Long) -> Unit)?)`-style helper and add:
```kotlin
    /**
     * [stream] for a subscriber that must know what it missed: [onMissed] gets the number of events evicted for THIS
     * subscriber while it lagged ([onEvicted] still hears every subscriber's). The SDK's outbound feed counts its own
     * losses with it (spec 2026-10-21, C-7).
     */
    fun stream(
        onSubscribed: (suspend () -> Unit)?,
        onMissed: (Long) -> Unit,
    ): Flow<VizEvent> = indexedStream(onSubscribed, onMissed)

    fun stream(onSubscribed: (suspend () -> Unit)? = null): Flow<VizEvent> = indexedStream(onSubscribed, null)

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
(The existing KDoc of `stream(onSubscribed)` stays on that function.)

- [ ] **Step 4: Run** — same command plus `--tests '*BusSubscriptionRaceTest*'`; expected PASS.
- [ ] **Step 5: Commit** — `git commit -am "core: EventBus.stream can report one subscriber's evictions (triage PR 3)"` (add the test file).

---

### Task 6: Client — the outbound buffer forwards watermarks, counts losses, and closes with a final watermark

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

    private fun poll(nanos: Long) = PollWatermark(sessionId = "s", seq = 0, tsNanos = nanos, pollEpochMs = 1_000 + nanos / 1_000_000)

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
            session.bus.send(poll(5 * second))
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
    fun `at most one watermark a second, the first at once`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            listOf(0L, 500_000_000L, 1_000_000_000L, 1_600_000_000L, 2_100_000_000L).forEach { session.bus.send(poll(it)) }
            val sent = mutableListOf<Long>()
            val drain = scope.launch { buffer.drain { sent += (it as PollWatermark).tsNanos } }
            assertEquals(listOf(0L, 1_000_000_000L, 2_100_000_000L), sent)
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
            session.bus.send(poll(0)) // does not fit either: skipped, not counted
            assertEquals(1, buffer.lost)
            val sent = mutableListOf<VizEvent>()
            val drain = scope.launch { buffer.drain { sent += it } }
            session.bus.send(poll(2 * second))
            assertEquals(listOf("c0", "c1", "wm@2"), sent.map { it.label })
            assertEquals(1, (sent.last() as PollWatermark).lostEvents)
            drain.cancel()
            scope.cancel()
            session.close()
        }

    @Test
    fun `events the session bus evicted before the feed read them count as lost`() {
        val feedThread = Executors.newSingleThreadExecutor()
        val feedDispatcher = feedThread.asCoroutineDispatcher()
        val session = VizSession("s")
        val scope = CoroutineScope(feedDispatcher + Job())
        val buffer = OutboundBuffer(20_000)
        try {
            runBlocking { buffer.feed(session, scope) }
            val release = CountDownLatch(1)
            feedThread.execute { release.await() } // the feed cannot run while the bus fills
            repeat(10_010) { session.send(event(it)) }
            release.countDown()
            runBlocking { withTimeout(10_000) { while (buffer.offered < 10_000) delay(10) } }
            assertEquals(10, buffer.lost, "the bus keeps 10,000 for a lagging subscriber; the 10 oldest were evicted")
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
            session.bus.send(poll(0))
            runCatching { buffer.drain { throw IOException("socket dropped") } }
            assertFalse(buffer.deliveryUncertain, "only a watermark was in flight")
            session.send(event(0))
            runCatching { buffer.drain { throw IOException("socket dropped") } }
            assertTrue(buffer.deliveryUncertain)
            session.bus.send(poll(5 * second))
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
            session.bus.send(poll(1 * second))
            session.send(event(0))
            session.bus.send(poll(1 * second + 300_000_000L)) // held back by the rate limit
            buffer.cutoff(session)
            session.send(event(1)) // after the cutoff: outside the capture
            session.bus.send(poll(9 * second))
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
}
```

- [ ] **Step 2: Run** — `./gradlew :coroutine-viz-client:test --tests '*OutboundBufferWatermarkTest*'`; expected: compilation fails (`lost`, `cutoff`, `deliveryUncertain`).

- [ ] **Step 3: Implement** (in `OutboundBuffer`; imports `PollWatermark`, `kotlinx.coroutines.flow.takeWhile`):

Constructor: `internal constructor(capacity: Int, reportIntervalMillis: Long, clock: () -> Long, watermarkIntervalNanos: Long = DEFAULT_WATERMARK_INTERVAL_NANOS)` as the primary (the public `@JvmOverloads constructor(capacity)` unchanged).

State:
```kotlin
    private val evictedCount = AtomicLong(0)

    @Volatile
    private var uncertain = false

    // Feed-only state: one collector reads and writes it.
    private var latestPoll: PollWatermark? = null
    private var lastForwardedNanos: Long? = null
    private var sessionId = ""

    // Sent on the session bus by cutoff(); recognised by identity, never forwarded.
    private val cutoffMarker = PollWatermark(sessionId = "", seq = -1, tsNanos = Long.MIN_VALUE, pollEpochMs = 0)

    /**
     * Events lost before sending, cumulative for the client (spec C-7): dropped because the buffer was full, or evicted
     * from the session bus before the feed read them. A skipped watermark is not an event and does not count.
     */
    internal val lost: Long get() = droppedCount.get() + evictedCount.get()

    /** A socket ended, other than by [close] at the end of a drain, after taking an event that may not have arrived. Sticky. */
    internal val deliveryUncertain: Boolean get() = uncertain
```

`feed`:
```kotlin
        sessionId = session.sessionId
        val job =
            scope.launch {
                session.bus
                    .stream(onSubscribed = null, onMissed = { evictedCount.addAndGet(it) })
                    .takeWhile { it !== cutoffMarker }
                    .collect { offer(it) }
                // The bus never completes, so only the cutoff ends the collection: nothing after it is sent, and the counts are final.
                finish()
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

    private fun stamped(poll: PollWatermark, final: Boolean): PollWatermark =
        poll.copy(lostEvents = lost, deliveryUncertain = uncertain, final = final)

    /**
     * Sets the capture cutoff (spec C-7): everything [session] sent on its bus before this call is handed to the buffer;
     * nothing after it is, and none of it counts as lost. The feed then queues a final watermark and closes the buffer,
     * so the drain sends it last and returns.
     */
    internal fun cutoff(session: VizSession) {
        session.bus.send(cutoffMarker)
    }

    /** The close message: the newest poll before the cutoff (the cutoff time when none completed) with the final counts. */
    private suspend fun finish() {
        val poll = latestPoll ?: PollWatermark(sessionId, 0, System.nanoTime(), System.currentTimeMillis())
        try {
            channel.send(stamped(poll, final = true))
            channel.close()
        } catch (_: ClosedSendChannelException) {
            // close() came first: the client is not waiting for a final watermark.
        }
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
            // There is no delivery acknowledgement: an event this socket took may not have arrived (spec C-7).
            if (!closed && tookEvent) uncertain = true
        }
    }
```
Companion: `const val DEFAULT_WATERMARK_INTERVAL_NANOS = 1_000_000_000L // at most one watermark a second (spec C-7)`.
Update the class KDoc with a "Watermarks and losses (spec C-7)" paragraph.

- [ ] **Step 4: Run** — `./gradlew :coroutine-viz-client:test --tests '*OutboundBuffer*'`; expected PASS (existing `OutboundBufferTest` too).
- [ ] **Step 5: Commit** — `git commit -am "client: the outbound buffer forwards watermarks with the loss count and ends with a final one (triage PR 3)"` (add the test).

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
import io.ktor.server.routing.Route
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
import kotlin.test.assertTrue

/** P-11: a 1.2 server would store every watermark as an event, so watermarks go only to servers that serve health. */
class WatermarkSupportTest {
    private fun Route.ingest(received: MutableList<VizEvent>) {
        webSocket("/api/sessions/{id}/ingest") {
            for (frame in incoming) {
                if (frame is Frame.Text) received += appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), frame.readText())
            }
        }
    }

    @Test
    fun `the health answer says whether the server takes watermarks`() =
        testApplication {
            routing {
                get("/api/sessions/new/health") { call.respondText("{}") }
                get("/api/sessions/trimmed/health") {
                    call.respondText("""{"error":"x","code":"history-trimmed"}""", status = HttpStatusCode.ServiceUnavailable)
                }
                get("/api/sessions/proxy/health") { call.respondText("<html>bad gateway</html>", status = HttpStatusCode.ServiceUnavailable) }
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
                routing { ingest(received) }
            }
            val http = createClient { install(WebSockets) }
            val session = VizSession("old")
            val scope = CoroutineScope(SupervisorJob())
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            session.send(CoroutineCreated("old", 1, 1, "c1", "j1", null, "sc", "e1"))
            session.bus.send(PollWatermark("old", 0, 1, 1))
            buffer.cutoff(session)
            val transport = scope.launch { stream(http, "", "old", "t", buffer, IngestTimeouts(), sendWatermarks = false) }
            pollFor { received.takeIf { it.isNotEmpty() } }
            transport.join()
            assertEquals(listOf("CoroutineCreated"), received.map { it.kind })
            assertTrue(received.none { it is PollWatermark })
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
                HttpStatusCode.ServiceUnavailable -> response.bodyAsText().let { body -> HEALTH_UNAVAILABLE_CODES.any { it in body } }.takeIf { it }
                else -> null
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }
```
(`withTimeoutOrNull` returns `null` both on timeout and for a `null` answer — both mean "not known".)

The internal `stream(...)` gains `sendWatermarks: Boolean = true` (after `lifetime`), passed to `connectAndDrain`; the
drain lambda becomes:
```kotlin
                            buffer.drain { event ->
                                // An older server would store a watermark as an event (P-11): skipped, not counted.
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
- [ ] **Step 5: Commit** — `git commit -am "client: send watermarks only to a server that serves session health (triage PR 3)"` (add the test).

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `stop()` drains and closes with a final watermark (spec 2026-10-21, C-7). */
class VizcoreClientStopTest {
    private class FakeSource : InstrumentationSource {
        override val sourceId = "fake"
        private val started = AtomicBoolean(false)
        override val isRunning: Boolean get() = started.get()

        override fun start() = started.set(true)

        override fun stop() = started.set(false)
    }

    private fun event(n: Int): VizEvent = CoroutineCreated("s", n.toLong(), n.toLong(), "c$n", "j$n", null, "sc", "e$n")

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
                            if (frame is Frame.Text) received += appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), frame.readText())
                        }
                        closed.set(true)
                    }
                }
            }
            val local = VizSession("s1")
            val source = FakeSource()
            val client =
                VizcoreClient(
                    httpClient = createClient { install(WebSockets) },
                    backendUrl = "",
                    sessionId = "s1",
                    token = "t",
                    session = local,
                    source = source,
                    scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                )
            client.start()
            pollFor { if (source.isRunning && connected.get()) Unit else null }
            local.bus.send(PollWatermark("s1", 0, 1_000, 1))
            repeat(500) { local.send(event(it)) }
            local.bus.send(PollWatermark("s1", 0, 2_000, 2))

            val started = System.nanoTime()
            withContext(Dispatchers.IO) { client.stop() }
            assertTrue(System.nanoTime() - started < 5_000_000_000L, "within the deadline")

            pollFor { if (closed.get()) Unit else null }
            val events = received.filterNot { it is PollWatermark }
            assertEquals(500, events.size, "every event captured before stop() arrives")
            val last = received.last() as PollWatermark
            assertTrue(last.final, "the close message comes last")
            assertEquals(2_000, last.tsNanos, "it carries the newest poll")
            assertEquals(0, last.lostEvents)
        }

    @Test
    fun `stop without an open socket returns at once`() {
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
        val client =
            VizcoreClient(
                httpClient = HttpClient(),
                backendUrl = "",
                sessionId = "s",
                token = "t",
                session = VizSession("s"),
                source = FakeSource(),
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                transport = { _, lifetime ->
                    lifetime.opened() // a socket that never takes anything
                    awaitCancellation()
                },
            )
        client.start()
        Thread.sleep(200)
        val started = System.nanoTime()
        client.stop(300)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs in 250..2_000, "waited for the 300 ms deadline, took $tookMs ms")
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
    // The reconnect loop, joined by stop() while it finishes the last socket.
    @Volatile
    private var loopJob: Job? = null

    // The current connection attempt's socket; stop() drains only through an open one.
    private val liveSocket = AtomicReference<SocketLifetime?>(null)
```
`start()`: `loopJob = scope.launch { reconnectLoop() }`.
`streamOnce`: `liveSocket.set(lifetime)` before `transport(…)`, and `liveSocket.compareAndSet(lifetime, null)` in a
`finally` around the `try`/`catch`.
`createFirstSession`'s `LinkageError` branch: `stop(0)` (it runs on the loop itself, which must not wait for itself).

`stop`:
```kotlin
    /** [stop] with the default deadline, [DEFAULT_STOP_TIMEOUT_MILLIS]. */
    fun stop() = stop(DEFAULT_STOP_TIMEOUT_MILLIS)

    /**
     * Stop capturing and close the connection (spec 2026-10-21, C-7). Sets the capture cutoff first: everything the app's
     * coroutines produced before it is sent, nothing after it. With a socket open, it then waits (blocking the caller, at
     * most [timeoutMillis]) while the buffer drains, sends a final watermark carrying the final loss count, and closes the
     * socket normally: the backend reads a clean close. If the deadline passes first, or no socket is open, it closes
     * without that message, and the backend reads delivery as uncertain. It never reconnects to finish. Then the source,
     * the buffer, the HTTP client and the scope are released, as before. Idempotent.
     *
     * @throws IllegalArgumentException when [timeoutMillis] is negative.
     */
    fun stop(timeoutMillis: Long) {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative, was $timeoutMillis" }
        val socketOpen =
            synchronized(lifecycleLock) {
                if (!running) return
                running = false
                source.stop()
                liveSocket.get()?.isOpen == true
            }
        if (socketOpen && timeoutMillis > 0) finishDelivery(timeoutMillis)
        buffer.close()
        httpClient.close()
        scope.cancel()
    }

    /** Cut the capture off and wait for the open socket to drain, send the final watermark and close. */
    private fun finishDelivery(timeoutMillis: Long) {
        buffer.cutoff(session)
        val loop = loopJob ?: return
        val finished = runBlocking { withTimeoutOrNull(timeoutMillis) { loop.join() } } != null
        if (!finished) {
            logger.warn(
                "vizcore could not send every event within {} ms of stop(); the session will say events may have been lost",
                timeoutMillis,
            )
        }
    }
```
Companion: `/** How long [stop] waits for the last events to be sent. */ const val DEFAULT_STOP_TIMEOUT_MILLIS: Long = 5_000`.

Factory `start(…httpClient…)`: after `probes` is built, `probes.onPollCompleted = pollWatermarks(local)`; add at file level:
```kotlin
/**
 * Puts each completed poll's watermark on [session]'s bus (spec 2026-10-21, C-7), after that poll's events and outside
 * the local store; the outbound buffer decides which ones to send.
 */
internal fun pollWatermarks(session: VizSession): (Long, Long) -> Unit =
    { pollNanos, pollEpochMs ->
        session.bus.send(PollWatermark(sessionId = session.sessionId, seq = 0, tsNanos = pollNanos, pollEpochMs = pollEpochMs))
    }
```
Update the class KDoc (stop drains; watermarks) and the `stop()` sentence in the companion `start` KDoc if any.

- [ ] **Step 4: Run** — `./gradlew :coroutine-viz-client:test`; expected PASS. Where an existing test now waits for a
  drain inside `testApplication` (its server shares the test thread), move only its `stop()` call off the thread with
  `withContext(Dispatchers.IO) { client.stop() }`; no assertion changes.
- [ ] **Step 5: Commit** — `git commit -am "client: watermark every poll, and stop() drains and closes with a final watermark (triage PR 3)"` (add the test).

---

### Task 9: Client — origin end to end in an app (the leftovers)

**Files:**
- Modify: `coroutine-viz-client/src/test/kotlin/com/jh/proj/coroutineviz/sdkapp/FakeBackend.kt` (record `origin`, all frame kinds; answer `GET …/health`)
- Create: `coroutine-viz-client/src/test/kotlin/com/jh/proj/coroutineviz/sdkapp/SdkOriginTest.kt`

- [ ] **Step 1: Write the failing test**

`FakeBackend`: `Created` gains `val origin: CoroutineOrigin?` and `val coroutineId: String`; the frame callback records
`events[event.coroutineId] = Created(event.label, event.creationPoint?.fileName, event.origin, event.coroutineId)` and
appends every decoded frame's `kind` to a new `val kinds: List<String>`; a new branch answers
`GET /api/sessions/$sessionId/health` with `200 {}` (`Connection: close`).

`SdkOriginTest.kt`:
```kotlin
package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Origin as an app sees it (spec "Origin", P-1): the app's coroutines are APP, one launched through `VizScope` into the
 * client's session is APP and captured once, Ktor's process-wide nonce generator is VIZCORE (created by the SDK's
 * connection), and the socket reader and writer stay APP (no SDK frame on their creation stack). Lives outside the
 * SDK's packages, as an app does.
 */
class SdkOriginTest {
    @Test
    fun `the app's coroutines are APP, the SDK's leftovers as verified`() {
        DebugProbesInstall.acquire()
        var server: FakeBackend? = null
        var client: VizcoreClient? = null
        val app = Job()
        var scope: VizScope? = null
        try {
            val backend = FakeBackend().also { server = it }
            val started = VizcoreClient.start(appName = "origin", backendUrl = "http://localhost:${backend.port}", token = "t")
            client = started
            CoroutineScope(Dispatchers.Default + app).launch(CoroutineName("app-work")) { awaitCancellation() }
            scope = VizScope(started.session)
            runBlocking { scope!!.vizLaunch(label = "wrapped", context = CoroutineName("wrapped")) { awaitCancellation() } }

            backend.awaitLabel("app-work")
            backend.awaitLabel("wrapped")
            Thread.sleep(1_500) // more polls, and a watermark

            val created = backend.created
            assertEquals(CoroutineOrigin.APP, created.single { it.label == "app-work" }.origin)
            val wrapped = created.filter { it.label == "wrapped" }
            assertEquals(1, wrapped.size, "captured once: $wrapped")
            assertEquals(CoroutineOrigin.APP, wrapped.single().origin)
            assertTrue(created.filter { it.label == "nonce-generator" }.all { it.origin == CoroutineOrigin.VIZCORE })
            assertTrue(
                created.filter { it.label in setOf("cio-from-nio-reader", "cio-to-nio-writer") }.all { it.origin == CoroutineOrigin.APP },
            )
            assertTrue("PollWatermark" in backend.kinds, "a server that serves health gets watermarks")
        } finally {
            scope?.cancel()
            app.cancel()
            client?.stop()
            server?.close()
            DebugProbesInstall.release()
        }
    }
}
```
(The leftover assertions are `all { … }`: whether a given leftover is captured at all depends on whether this JVM
created it before DebugProbes were installed; when it is captured, its origin is the verified one.)

- [ ] **Step 2: Run** — `./gradlew :coroutine-viz-client:test --tests '*SdkOriginTest*' --tests '*SdkOwnCoroutinesTest*'`; expected: compiles after the `FakeBackend` change; passes only with Tasks 1–8 in (it guards the integration).
- [ ] **Step 3: Implement** — nothing beyond `FakeBackend`; fix any integration gap the test exposes.
- [ ] **Step 4: Run** — same command; expected PASS.
- [ ] **Step 5: Commit** — `git commit -am "client: an app's coroutines carry their origin end to end (triage PR 3)"` (add the test).

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
 * Triage PR 3 end to end in one JVM: [VizcoreClient] against the real backend [module] (API keys, so the SDK's
 * `GET …/health` capability check runs with a runner key). While connected the SDK's watermarks make possibly blocked
 * evaluated and origins reported; after `stop()` the close was clean, so nothing reads "events may have been lost".
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
                environment = {
                    config =
                        MapApplicationConfig(
                            "auth.keys.size" to "1",
                            "auth.keys.0.name" to "sdk",
                            "auth.keys.0.hash" to ApiKeyStore.sha256Hex(apiKey),
                            "auth.keys.0.role" to "runner",
                        )
                }.let { applicationEnvironment(it) },
                configure = {
                    connector {
                        host = "127.0.0.1"
                        port = 0
                    }
                },
                module = Application::module,
            ).start(wait = false)
        server = started
        return "http://127.0.0.1:${runBlocking { started.engine.resolvedConnectors().first().port }}"
    }

    private fun KtorClient.json(url: String): JsonObject =
        runBlocking { kotlinx.serialization.json.Json.parseToJsonElement(get(url) { header("X-API-Key", apiKey) }.bodyAsText()).jsonObject }

    @Test
    fun `possibly blocked is evaluated while connected and the close is clean`() {
        val backendUrl = startBackend()
        val started = VizcoreClient.start(appName = "sdk-health", backendUrl = backendUrl, token = apiKey)
        client = started
        CoroutineScope(Dispatchers.Default + app).launch(CoroutineName("health-app-work")) { awaitCancellation() }
        val id = started.currentSessionId
        KtorClient(CIO).use { http ->
            val health = "$backendUrl/api/sessions/$id/health"
            var connected = JsonObject(emptyMap())
            runBlocking {
                withTimeout(15_000) {
                    while (true) {
                        connected = http.json(health)
                        val evaluated = connected["possiblyBlocked"]!!.jsonObject["status"]!!.jsonPrimitive.content == "EVALUATED"
                        if (evaluated && connected["counts"]!!.jsonObject["app"]!!.jsonPrimitive.int > 0) break
                        delay(100)
                    }
                }
            }
            assertEquals("REPORTED", connected["sessionOrigin"]!!.jsonPrimitive.content)
            assertEquals("WATERMARK", connected["asOf"]!!.jsonObject["kind"]!!.jsonPrimitive.content)

            started.stop()
            client = null
            val session = http.json("$backendUrl/api/sessions/$id")
            assertFalse(session["appConnected"]!!.jsonPrimitive.boolean)
            val after = http.json(health)
            val transport = after["transport"]!!.jsonObject
            assertFalse(transport["deliveryUncertain"]!!.jsonPrimitive.boolean, "a clean close: $transport")
            assertEquals(0L, transport["lostEvents"]!!.jsonPrimitive.long)
            assertFalse(after["failuresAtLeast"]!!.jsonPrimitive.boolean)
            assertEquals("EVALUATED", after["possiblyBlocked"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            assertTrue(after["counts"]!!.jsonObject["app"]!!.jsonPrimitive.int > 0)
        }
    }
}
```
(Mirror `SdkBackendAuthTest.startBackend` exactly for the server construction; the snippet above shows the config.)

- [ ] **Step 2: Run** — `./gradlew test --tests '*SdkSessionHealthTest*'`; expected PASS with Tasks 1–8 in. To confirm it
  tests the change: with `probes.onPollCompleted = …` commented out it fails (`NOT_EVALUATED`); restore.
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
			// Blocks a Default thread without suspending: the Problems tab's "possibly blocked" (over 10 s running).
			CoroutineScope(Dispatchers.Default).launch(CoroutineName("busy-worker")) {
				logger.info("busy-worker blocks its thread for {} ms", busyMs)
				Thread.sleep(busyMs)
			}
		}
```
  (imports `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.launch`.)

- [ ] **Step 2: README** — replace the `stop()` bullet ("drops events that have not been sent yet … #207") with:
  "`stop()` sends what the app produced before it, then a closing message, and only then disconnects; it waits at most
  5 s (`stop(timeoutMillis)` to change that), so call it from a shutdown hook. Without that message, or after a lost
  connection, the session says events may have been lost." Extend the #225 bullet: "Of those, the session marks Ktor's
  `nonce-generator` as vizcore's (the "vizcore connection" count) when the SDK created it; the socket reader and writer
  are created where nothing names the SDK, so they count as the app's (they only show as long-lived)." Add a bullet:
  "Each poll also tells the backend when it looked (at most once a second), which is what lets the session say a
  coroutine may be blocked; servers before 1.3 are not sent these."

- [ ] **Step 3: CHANGELOG** — under `[Unreleased]` / `### Added`:
  "- SDK: the session can now judge an attached app. Each poll tells the backend when it looked (at most once a second),
  so a coroutine running for over 10 s reads as possibly blocked; `stop()` sends the remaining events and a closing
  message within 5 s (`stop(timeoutMillis)`), so a clean shutdown no longer reads \"events may have been lost\"; every
  coroutine says whether it is the app's or vizcore's own connection (Ktor's `nonce-generator`), or unknown when
  DebugProbes run without creation stacks; suspension points say whether the frame was observed or is the creation
  site; a coroutine launched through `VizScope` into the client's session is reported once. Servers before 1.3 are not
  sent the poll messages."

- [ ] **Step 4: Build the sample** — `cd backend && ./gradlew :coroutine-viz-client:jar :coroutine-viz-core:jar && cd ../examples/spring-vizcore-demo && ./gradlew bootJar -q` (expected: BUILD SUCCESSFUL).
- [ ] **Step 5: Commit** — `git commit -am "docs: the SDK's watermark, clean stop and origin; sample busy worker (triage PR 3)"`

---

### Task 12: Gates and end-to-end check

- [ ] **Step 1: Full gate** — from `backend/`:
  `./gradlew ktlintCheck detekt test checkBytecode build :coroutine-viz-core:build :coroutine-viz-client:test`
  Expected: BUILD SUCCESSFUL; record the test counts (`find . -path '*/build/test-results/test/*.xml' | xargs grep -ho 'tests="[0-9]*"' | …`).
  Revert `docs/index.html` if regenerated. Fix ktlint with `./gradlew ktlintFormat` and detekt findings in the code (no baseline additions).

- [ ] **Step 2: End to end** (port **18951** only). Outputs to
  `/private/tmp/claude-501/-Users-jirihermann-Documents-workspace-vizcore/9e9c5bc5-1b4c-412c-966d-6cf483ca2458/scratchpad/pr3-e2e/`.
  1. Backend: `./gradlew installDist` (or `run`) with `PORT=18951` / `-Dktor.deployment.port=18951`, auth off, memory mode; log to `backend.log`.
  2. Sample: `java -jar examples/spring-vizcore-demo/build/libs/spring-vizcore-demo-*.jar --vizcore.backend-url=http://localhost:18951 --vizcore.busy-ms=15000 --vizcore.token= > app.log`.
  3. Session id from `GET /api/sessions` (app name `spring-vizcore-demo`); at ~5 s and ~13 s save
     `GET /api/sessions/{id}/health` → `health-connected-5s.json`, `health-connected-13s.json`: at 13 s
     `possiblyBlocked.status = EVALUATED`, a `POSSIBLY_BLOCKED` incident for `busy-worker` with `runningForNanos` > 10 s,
     `asOf.kind = WATERMARK`; `counts` with `vizcore` ≥ 1 (`nonce-generator`) and `app` the rest; save
     `GET /api/sessions/{id}` → `session-connected.json`.
  4. `kill -TERM <app pid>` (shutdown hook → `stop()`); save `health-after-stop.json` and `session-after-stop.json`:
     `appConnected = false`, `transport.deliveryUncertain = false`, `lostEvents = 0`, `failuresAtLeast = false`.
  5. Contrast (optional, documents the old behaviour): the same against an app killed with `kill -9` →
     `transport.deliveryUncertain = true`.
  6. Stop the backend; `lsof -i :18951` shows nothing. Summarise in `pr3-e2e/README.md`.

---

## Compatibility evidence to collect for the PR

- `BinaryCompatibilityTest` (core) green, with the new `CoroutineSnapshot` 0.3.x constructor pins.
- `ClientBinaryCompatibilityTest` green, with `stop(long)` added and every older signature unchanged.
- `checkBytecode` green (JVM 17 class files, no Ktor in core).
- `HealthWireTypesTest` (from #248) still decodes an older SDK's events (no origin/provenance).
- `WatermarkSupportTest`: no watermark frames to a server without session health; events unchanged.
- An older SDK with the new server is #295's `IngestWatermarkTest`/D-2 behaviour, unchanged.

## Known, not in this PR

- `cio-from-nio-reader`, `cio-to-nio-writer` and `cio-request-timeout` stay `APP` (P-1); they show only as long-lived.
- `lostEvents` is per client, so a replacement session (#129) inherits the earlier session's count (overstates, P-6).
- A session that dropped its connection reconnects with delivery uncertain for the rest of the session (server rule, D-2).
- `stop()` blocks the caller up to its deadline; an app that must not block calls `stop(0)` (old behaviour).
- The SDK version bump and publication (release PR); the Problems tab and the "vizcore connection (n)" toggle (Parts 6/7).
