# Part 2 · Waits recorded as suspension — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A coroutine that waits for a `VizMutex`, a `VizSemaphore` permit or an `InstrumentedChannel` send or
receive is recorded as SUSPENDED (with what it waits for), never left ACTIVE. Also: semaphore permits are
counted per holder with an honest "holder unknown", and failed coroutines send their stack trace.

**Spec:** [`docs/superpowers/specs/2026-11-05-session-dock-design.md`](../specs/2026-11-05-session-dock-design.md),
S-7, "Wire contract for S-7", Part 2 in "Building it", Part 2 in "Testing".

**Architecture:** One internal helper, `VizSession.recordWait(kind, resourceId) { wait }`, sends
`CoroutineSuspended` (with the new optional `waitKind` and `resourceId`) before a wait and `CoroutineResumed`
after it, except when the coroutine's own job is being cancelled. Each wrapper tries first without suspending
(`tryLock`, `tryAcquire`, `trySend`, `tryReceive`) and calls `recordWait` only when that fails. `VizSemaphore`
counts permits per holder; so that a release can be attributed, its non-suspending `release` and `tryAcquire`
find their coroutine through a new thread-local, `VizCoroutineElement.current()`. The wrappers keep publishing
events the way they do today (sent directly). The new fields go through the per-coroutine timeline API and the
frontend types; nothing in the UI changes yet (Part 4 shows them).

**Known limitation, not in Part 2:** under concurrent handoff a mutex or semaphore *snapshot* event
(`MutexQueueChanged`, `SemaphoreStateChanged`, the order of `MutexUnlocked` and the next `MutexLockAcquired`) can
momentarily be stale or out of order, because the wrappers update their bookkeeping and send events without a
common lock. This is how they work today; the S-7 contract does not promise atomic snapshots. It is tracked as a
follow-up issue. The tests below do not assert snapshot atomicity; the suspension facts they assert (Suspended,
then Resumed, per coroutine) are recorded by the waiting coroutine itself and are ordered.

**Tech Stack:** Kotlin 2.3 / kotlinx.coroutines 1.10.2 / kotlinx.serialization (core module
`:coroutine-viz-core`), Ktor server (root project `:`), JUnit 5 + kotlin.test, React/TypeScript + vitest
(frontend), OpenAPI (`documentation.yaml`, `shared/api-types`).

---

## Before you start

- **Branch:** create a worktree from `origin/main` (at least `0c02dd2f`, which has the Channels and Flow pacing,
  #287 and #288): `git worktree add ../vizcore-waits -b feat/waits-suspended origin/main`, then work only in
  `../vizcore-waits`.
- **Where commands run:** every command block starts from an explicit directory. Set once per shell:
  `export WT=/Users/jirihermann/Documents/workspace-vizcore/vizcore-waits` and
  `export JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home`.
  "Run: `./gradlew …`" lines mean `cd "$WT/backend" && ./gradlew …`; `pnpm` lines mean `cd "$WT/frontend" && pnpm …`;
  `git` commands run in `$WT`.
- **Checks that really run:** ktlint and detekt are applied only to the root project (`backend/src`), not to
  `:coroutine-viz-core`, where most of this work is. For core the gate is `./gradlew :coroutine-viz-core:build`
  (compile + tests) and `./gradlew checkBytecode` (JVM 17). Write core code in the style the root's ktlint enforces
  anyway (a function with two or more parameters puts each on its own line; trailing commas), as the existing
  core files do. Root-project test files (Task 10) do go through `./gradlew ktlintCheck detekt`.
- **Commits:** end every commit message with the attribution lines the session gives you.
- **Pacing audit files:** `export PACING=/private/tmp/claude-501/-Users-jirihermann-Documents-workspace-vizcore/9e9c5bc5-1b4c-412c-966d-6cf483ca2458/scratchpad/pacing`
  holds `demo_pacing_audit.py` and the earlier results (`channels-pacing.json`, `demo-pacing.json`,
  `demo-pacing-2.json`), used in Task 10.
- **Tests and dispatchers.** `VizScope` launches on `Dispatchers.Default`, so most of these tests use `runBlocking`
  and poll the event store (`awaitEvent`, Task 4; see `TerminalEventAwait.kt`). Where order would otherwise depend
  on real time — a wait ended by `withTimeout`, and the Combined demo's contention — the test gives `VizScope` a
  virtual-time dispatcher (`VirtualScope`, Task 4; `runTest` in Task 10), which `VizScope` lets override Default.
  Contention is made certain with gates (a holder keeps the resource until every waiter has recorded its wait),
  never left to chance; joins are bounded with `withTimeout`, and every new test class carries `@Timeout(30)` so a
  regression fails instead of hanging.

### Decisions this plan makes (read once)

1. **`waitKind` is an enum, `WaitKind`,** with the `reason` string each kind sends (`mutex`, `semaphore`,
   `channel-send`, `channel-receive`), so readers that ignore the new field still see the kind (spec). Like
   `SuspensionProvenance`, a new constant later (for `select`) is a breaking wire change for readers that know the
   enum; that is noted on the enum.
2. **Binary compatibility:** every event class that gains a field keeps its current JVM constructor as a
   `@Deprecated(level = HIDDEN)` secondary constructor (the pattern `CoroutineSuspended` already uses for 0.2.1),
   and changed `EventContext` helpers keep a hidden overload. `BinaryCompatibilityTest` pins them.
3. **"Unless the tracked job is cancelling":** checked on every exit from the wait, normal return included (a
   wait can return inside `NonCancellable` or just as the job is cancelled). The job is looked up by coroutine id
   in `RuntimeSnapshot` (new O(1) `jobOf`). The current job in the context is not enough: inside `withTimeout` it is
   the timeout's job, which *is* cancelled although the coroutine survives.
4. **A semaphore release knows its coroutine.** Today the non-suspending wrapper calls look their coroutine up with
   `runBlocking { currentCoroutineContext()[VizCoroutineElement] }`, which always returns `null` (runBlocking
   starts a new context) — that is why `VizSemaphore.release` falls back to an arbitrary holder.
   `VizCoroutineElement` becomes a `ThreadContextElement` and offers `VizCoroutineElement.current()`. Part 2 uses
   it only where release attribution needs it: `VizSemaphore.release` and `VizSemaphore.tryAcquire` (the holder
   recorded by `tryAcquire` must be the coroutine that later releases). `VizMutex.unlock`/`tryLock` and the
   channels' `trySend`/`tryReceive` are left as they are.
5. **Semaphore holders:** a coroutine is listed once with a permit count. A release by a coroutine that holds no
   permit sends `holderUnknown = true` and names no holder; `SemaphoreStateChanged` gains `activeHolderPermits`
   (count per listed holder) and `unknownHolderPermits` (listed permits beyond those in use, i.e. released by
   others). Holdings live in a `ConcurrentHashMap` updated with `merge`/`computeIfPresent`. When a release leaves
   every permit free, the holder list is emptied (best effort, see "Known limitation").
   `SemaphoreValidator.verifyPermitBounds` counts permits in use (`sum(activeHolderPermits) -
   unknownHolderPermits`) instead of listed holders when the counts are present, so a valid history with an
   unknown release is not rejected; older events keep the holder count.
6. **Frontend projection:** `projectCoroutines` must deep-equal the server snapshot (`CoroutineNode` has no wait
   fields), so a separate pure reducer, `projectWaits(events)`, gives each coroutine's current wait (kind, resource,
   since when); the event and timeline types carry the fields. Part 4 shows them.
7. **Out of scope, noted for later parts:** `select` clauses stay ACTIVE (spec "Later").

## File map

| File | Change |
|---|---|
| `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/WaitKind.kt` | **new** enum |
| `…/events/coroutine/CoroutineSuspended.kt` | + `waitKind`, `resourceId`; hidden 0.3.x constructor |
| `…/session/EventContext.kt` | `coroutineSuspended` gains the fields (+ hidden 0.3.x overload); `coroutineFailed` gains `stackTrace` (+ hidden overload); `stackTraceLines()`, `MAX_STACK_TRACE_FRAMES` |
| `…/wrappers/VizCoroutineElement.kt` | `ThreadContextElement`, `current()` |
| `…/models/RuntimeSnapshot.kt` | `jobOf(coroutineId)` (reverse map) |
| `…/wrappers/WaitSuspension.kt` | **new** `recordWait` |
| `…/events/SuspensionPoint.kt` | skip the wait wrappers' frames |
| `…/wrappers/VizMutex.kt` | try-first, `recordWait` |
| `…/events/SemaphoreEvents.kt` | `holderUnknown`; `activeHolderPermits`, `unknownHolderPermits`; hidden constructors |
| `…/wrappers/VizSemaphore.kt` | rewritten: try-first, per-holder counts, unknown releases |
| `…/checksystem/SyncValidators.kt` | `verifyPermitBounds` counts permits in use |
| `…/wrappers/InstrumentedChannel.kt` | rewritten: try-first send/receive/receiveCatching/iteration |
| `…/wrappers/VizScope.kt` | pass the stack trace to `coroutineFailed` |
| `…/models/CoroutineTimeline.kt`, `…/session/ProjectionService.kt` | timeline rows carry the fields |
| `backend/src/main/resources/openapi/documentation.yaml`, `shared/api-types/openapi.json`, `shared/api-types/generated.ts` | `TimelineEventSummary` fields |
| `shared/api-types/events.ts`, `frontend/src/types/api.ts` | event and timeline types |
| `frontend/src/lib/projections/project-waits.ts` | **new** `projectWaits` |
| Tests (core): `events/WaitKindWireTest.kt`, `events/SemaphoreHolderWireTest.kt`, `compat/BinaryCompatibilityTest.kt`, `wrappers/WaitTestSupport.kt`, `wrappers/VizCoroutineElementCurrentTest.kt`, `wrappers/RecordWaitTest.kt`, `wrappers/MutexWaitTest.kt`, `wrappers/SemaphoreWaitTest.kt`, `wrappers/ChannelWaitTest.kt`, `wrappers/FailedStackTraceTest.kt`, `session/CoroutineTimelineWaitTest.kt` | new / extended |
| Tests (server): `scenarios/WaitsAsSuspensionDemoTest.kt`, `scenarios/ChannelsDemoPacingTest.kt` | new / extended |
| Tests (core, existing): `checksystem/SyncValidatorsTest.kt` | extended |
| Tests (frontend): `frontend/src/lib/event-discriminator.test.ts` (extended), `frontend/src/lib/projections/project-waits.test.ts` (new) | |
| `CHANGELOG.md` | Fixed |

Paths starting `…/` are under `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/`; core tests are
under `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/`; server tests under
`backend/src/test/kotlin/com/jh/proj/coroutineviz/`.

---

### Task 1: Worktree and a green baseline

- [ ] **Step 1: Create the worktree**

```bash
export WT=/Users/jirihermann/Documents/workspace-vizcore/vizcore-waits
export JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home
cd /Users/jirihermann/Documents/workspace-vizcore/vizcore
git fetch origin
git worktree add "$WT" -b feat/waits-suspended origin/main
```

- [ ] **Step 2: Run the core and server tests once**

Run: `cd "$WT/backend" && ./gradlew :coroutine-viz-core:test :test`
Expected: BUILD SUCCESSFUL. If anything fails on a clean `main`, stop and report it; do not start Task 2 on a red
baseline.

---

### Task 2: `WaitKind` and the two new `CoroutineSuspended` fields

**Files:**
- Create: `…/events/WaitKind.kt`
- Modify: `…/events/coroutine/CoroutineSuspended.kt`
- Modify: `…/session/EventContext.kt:147-174`
- Test: `events/WaitKindWireTest.kt` (new), `compat/BinaryCompatibilityTest.kt`

- [ ] **Step 1: Write the failing wire test**

Create `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/events/WaitKindWireTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.events

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.EventContext
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.coroutineSuspended
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The S-7 wire contract (spec 2026-11-05): a recorded wait says what it waited for; older events stay valid. */
class WaitKindWireTest {
    private val serializer = PolymorphicSerializer(VizEvent::class)

    @Test
    fun `a wait's kind and resource round-trip through the shared Json`() {
        val suspended =
            CoroutineSuspended(
                sessionId = "s",
                seq = 4,
                tsNanos = 9,
                coroutineId = "c",
                jobId = "j",
                parentCoroutineId = null,
                scopeId = "sc",
                label = "order-7",
                reason = WaitKind.MUTEX.reason,
                waitKind = WaitKind.MUTEX,
                resourceId = "mutex-3",
            )
        val json = appJson.encodeToString(serializer, suspended)

        assertTrue(json.contains("\"waitKind\":\"MUTEX\""), json)
        assertTrue(json.contains("\"resourceId\":\"mutex-3\""), json)
        assertEquals(suspended, appJson.decodeFromString(serializer, json))
    }

    @Test
    fun `an event from an older producer has no wait kind`() {
        val old =
            """{"type":"CoroutineSuspended","sessionId":"s","seq":1,"tsNanos":2,"coroutineId":"c","jobId":"j",""" +
                """"parentCoroutineId":null,"scopeId":"sc","label":null,"reason":"delay"}"""
        val decoded = appJson.decodeFromString(serializer, old) as CoroutineSuspended

        assertNull(decoded.waitKind)
        assertNull(decoded.resourceId)
    }

    @Test
    fun `each wait kind sends the same kind as its reason`() {
        assertEquals(listOf("mutex", "semaphore", "channel-send", "channel-receive"), WaitKind.entries.map { it.reason })
    }

    @Test
    fun `the context helper carries the wait`() {
        val session = VizSession("wait-helper")
        try {
            val ctx = EventContext(session, "c", "j", null, "sc", "order-7")
            val event = ctx.coroutineSuspended("mutex", waitKind = WaitKind.MUTEX, resourceId = "mutex-3")

            assertEquals(WaitKind.MUTEX, event.waitKind)
            assertEquals("mutex-3", event.resourceId)
        } finally {
            session.close()
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.events.WaitKindWireTest"`
Expected: compilation FAILS with `Unresolved reference 'WaitKind'` (and `waitKind`).

- [ ] **Step 3: Pin the 0.3.x signatures before changing them**

In `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/compat/BinaryCompatibilityTest.kt` add the
import `import com.jh.proj.coroutineviz.events.SuspensionProvenance` and these two tests at the end of the class:

```kotlin
    @Test
    fun `CoroutineSuspended keeps its 0_3_x constructors`() {
        val long = Long::class.javaPrimitiveType
        val str = String::class.java
        val v03 =
            arrayOf<Class<*>?>(
                str, long, long, str, str, str, str, str, str, java.lang.Long::class.java, SuspensionPoint::class.java,
                SuspensionProvenance::class.java,
            )
        val ctor = CoroutineSuspended::class.java.getDeclaredConstructor(*v03)
        CoroutineSuspended::class.java.getDeclaredConstructor(*v03, Int::class.javaPrimitiveType, marker)
        val event = ctor.newInstance("s", 1L, 2L, "c", "j", null, "sc", "l", "await", null, null, SuspensionProvenance.REAL_FRAME)
        assertEquals(SuspensionProvenance.REAL_FRAME, event.provenance)
        assertNull(event.waitKind)
        assertNull(event.resourceId)
    }

    @Test
    fun `EventContext coroutineSuspended keeps its 0_3_x JVM methods`() {
        val helpers = Class.forName("com.jh.proj.coroutineviz.session.EventContextKt")
        val v03 =
            arrayOf(
                EventContext::class.java,
                String::class.java,
                java.lang.Long::class.java,
                SuspensionPoint::class.java,
                SuspensionProvenance::class.java,
            )
        assertNotNull(helpers.getDeclaredMethod("coroutineSuspended", *v03))
        assertNotNull(
            helpers.getDeclaredMethod("coroutineSuspended\$default", *v03, Int::class.javaPrimitiveType, Any::class.java),
        )
    }
```

(`event.waitKind` does not compile yet; that is expected until Step 5.)

- [ ] **Step 4: Add `WaitKind`**

Create `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/WaitKind.kt`:

```kotlin
package com.jh.proj.coroutineviz.events

import kotlinx.serialization.Serializable

/**
 * What a coroutine waited for when a vizcore wrapper recorded the wait as a suspension (spec 2026-11-05, S-7):
 * a [com.jh.proj.coroutineviz.wrappers.VizMutex], a [com.jh.proj.coroutineviz.wrappers.VizSemaphore] permit, or
 * an [com.jh.proj.coroutineviz.wrappers.InstrumentedChannel] send or receive. [reason] is the
 * `CoroutineSuspended.reason` sent with it, so a reader that ignores the field still sees the kind.
 * Adding a constant is a breaking wire change: `appJson` does not coerce unknown enum values, so a reader that
 * knows an older set fails the whole event.
 */
@Serializable
enum class WaitKind(
    val reason: String,
) {
    MUTEX("mutex"),
    SEMAPHORE("semaphore"),
    CHANNEL_SEND("channel-send"),
    CHANNEL_RECEIVE("channel-receive"),
}
```

- [ ] **Step 5: Add the fields and the hidden 0.3.x constructor**

In `…/events/coroutine/CoroutineSuspended.kt` add `import com.jh.proj.coroutineviz.events.WaitKind`, add two
properties after `provenance`, and a second hidden constructor after the existing 0.2.1 one:

```kotlin
    /** Where [suspensionPoint] came from; `null` when the producer did not say. */
    val provenance: SuspensionProvenance? = null,
    /** What a mutex, semaphore or channel wrapper waited for; `null` for other suspensions and older producers. */
    val waitKind: WaitKind? = null,
    /** The `mutexId`, `semaphoreId` or `channelId` waited for, with [waitKind]. */
    val resourceId: String? = null,
) : CoroutineEvent {
```

```kotlin
    /**
     * The 0.3.x JVM constructor (no [waitKind] / [resourceId]), kept for binary compatibility like the one above.
     */
    @Deprecated("binary compatibility with 0.3.x", level = DeprecationLevel.HIDDEN)
    constructor(
        sessionId: String,
        seq: Long,
        tsNanos: Long,
        coroutineId: String,
        jobId: String,
        parentCoroutineId: String?,
        scopeId: String,
        label: String?,
        reason: String,
        durationMillis: Long? = null,
        suspensionPoint: SuspensionPoint? = null,
        provenance: SuspensionProvenance? = null,
    ) : this(
        sessionId, seq, tsNanos, coroutineId, jobId, parentCoroutineId, scopeId, label,
        reason, durationMillis, suspensionPoint, provenance, null, null,
    )
```

Also add to the class KDoc: ` * @property waitKind What a wrapper waited for (spec 2026-11-05, S-7); null otherwise`.
The existing 0.2.1 constructor keeps delegating with 12 arguments; that call now resolves to the primary
constructor (hidden constructors are not candidates), with `waitKind` and `resourceId` defaulting to `null`.

- [ ] **Step 6: Extend the context helper, keeping the 0.3.x JVM method**

In `…/session/EventContext.kt` add `import com.jh.proj.coroutineviz.events.WaitKind` and replace the current
visible `coroutineSuspended` (lines 147–166) with:

```kotlin
fun EventContext.coroutineSuspended(
    reason: String,
    durationMillis: Long? = null,
    suspensionPoint: SuspensionPoint? = null,
    provenance: SuspensionProvenance? = null,
    waitKind: WaitKind? = null,
    resourceId: String? = null,
): CoroutineSuspended =
    CoroutineSuspended(
        sessionId = sessionId,
        seq = nextSeq(),
        tsNanos = timestamp(),
        coroutineId = coroutineId,
        jobId = jobId,
        parentCoroutineId = parentCoroutineId,
        scopeId = scopeId,
        label = label,
        reason = reason,
        durationMillis = durationMillis,
        suspensionPoint = suspensionPoint,
        provenance = provenance,
        waitKind = waitKind,
        resourceId = resourceId,
    )

/** Kept so code compiled against 0.3.x still links (its JVM signature has no `waitKind` or `resourceId`). */
@Deprecated("binary compatibility with 0.3.x", level = DeprecationLevel.HIDDEN)
fun EventContext.coroutineSuspended(
    reason: String,
    durationMillis: Long? = null,
    suspensionPoint: SuspensionPoint? = null,
    provenance: SuspensionProvenance? = null,
): CoroutineSuspended = coroutineSuspended(reason, durationMillis, suspensionPoint, provenance, waitKind = null)
```

Leave the existing hidden 0.2.x overload (`reason, durationMillis, suspensionPoint`) as it is.

- [ ] **Step 7: Run the tests**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.events.WaitKindWireTest" --tests "com.jh.proj.coroutineviz.compat.BinaryCompatibilityTest" --tests "com.jh.proj.coroutineviz.events.EventSerializationTest" --tests "com.jh.proj.coroutineviz.events.HealthWireTypesTest"`
Expected: PASS (all).

- [ ] **Step 8: Commit**

```bash
cd "$WT/backend" && ./gradlew :coroutine-viz-core:build
cd "$WT" && git add backend/coroutine-viz-core
git commit -m "feat(core): CoroutineSuspended can say what a wrapper waited for (waitKind, resourceId)"
```

---

### Task 3: `VizCoroutineElement.current()` for a semaphore release

**Files:**
- Modify: `…/wrappers/VizCoroutineElement.kt`
- Test: `wrappers/VizCoroutineElementCurrentTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizCoroutineElementCurrentTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Code that cannot suspend (`unlock`, `release`, `trySend`) finds the instrumented coroutine it runs in through
 * [VizCoroutineElement.current]; `runBlocking { currentCoroutineContext() }` could not (it starts a new context).
 */
@Timeout(30)
class VizCoroutineElementCurrentTest {
    private val session = VizSession("current-element")
    private val viz = VizScope(session)

    @AfterEach
    fun close() = session.close()

    private fun currentLabel(): String? = VizCoroutineElement.current()?.label

    @Test
    fun `non-suspending code in a viz coroutine sees its element, also after a dispatcher switch`() =
        runBlocking {
            val before = CompletableDeferred<String?>()
            val after = CompletableDeferred<String?>()
            viz.vizLaunch("reader") {
                before.complete(currentLabel())
                withContext(Dispatchers.IO) { after.complete(currentLabel()) }
            }.join()

            assertEquals("reader", before.await())
            assertEquals("reader", after.await())
            assertNull(currentLabel(), "runBlocking is not an instrumented coroutine")
        }

    @Test
    fun `a child sees its own element`() =
        runBlocking {
            val seen = CompletableDeferred<String?>()
            viz.vizLaunch("parent") { vizLaunch("child") { seen.complete(currentLabel()) } }.join()

            assertEquals("child", seen.await())
        }

    @Test
    fun `a thread that ran a viz coroutine does not keep its element`() =
        runBlocking {
            val pool = Executors.newSingleThreadExecutor()
            try {
                viz.vizLaunch("visitor", pool.asCoroutineDispatcher()) { currentLabel() }.join()

                assertNull(pool.submit<String?> { currentLabel() }.get())
            } finally {
                pool.shutdown()
            }
        }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.VizCoroutineElementCurrentTest"`
Expected: compilation FAILS with `Unresolved reference 'current'`.

- [ ] **Step 3: Make the element a `ThreadContextElement`**

Replace the class in `…/wrappers/VizCoroutineElement.kt` (keep the KDoc, add the paragraph shown):

```kotlin
package com.jh.proj.coroutineviz.wrappers

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * (existing KDoc unchanged, then add:)
 *
 * Code that cannot suspend finds the element of the coroutine running on its thread with [current]: the element
 * is a [ThreadContextElement], set on the thread while its coroutine runs and restored when it leaves.
 */
data class VizCoroutineElement(
    val coroutineId: String,
    val jobId: String,
    val parentCoroutineId: String?,
    val scopeId: String,
    val label: String?,
) : ThreadContextElement<VizCoroutineElement?> {
    companion object Key : CoroutineContext.Key<VizCoroutineElement> {
        private val onThisThread = ThreadLocal<VizCoroutineElement?>()

        /**
         * The element of the instrumented coroutine running on this thread, or `null` outside one. For code that
         * cannot suspend (`unlock`, `release`, `trySend`), where `currentCoroutineContext()` is not available.
         */
        fun current(): VizCoroutineElement? = onThisThread.get()
    }

    override val key: CoroutineContext.Key<*> = Key

    override fun updateThreadContext(context: CoroutineContext): VizCoroutineElement? =
        onThisThread.get().also { onThisThread.set(this) }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: VizCoroutineElement?,
    ) = onThisThread.set(oldState)
}
```

- [ ] **Step 4: Run the test, then the whole core suite**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.VizCoroutineElementCurrentTest"`
Expected: PASS.
Run: `./gradlew :coroutine-viz-core:test`
Expected: PASS (the element is in every instrumented coroutine; nothing else may change).

- [ ] **Step 5: Commit**

```bash
cd "$WT/backend" && ./gradlew :coroutine-viz-core:build
cd "$WT" && git add backend/coroutine-viz-core
git commit -m "feat(core): VizCoroutineElement.current() names the coroutine in code that cannot suspend (for release attribution)"
```

---

### Task 4: `recordWait` — the shared suspension recorder

**Files:**
- Create: `…/wrappers/WaitSuspension.kt`
- Modify: `…/models/RuntimeSnapshot.kt` (add `jobOf`)
- Modify: `…/events/SuspensionPoint.kt:24-29` (skip list)
- Test: `wrappers/WaitTestSupport.kt` (new helpers), `wrappers/RecordWaitTest.kt` (new)

- [ ] **Step 1: Add the shared test helpers**

Create `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/WaitTestSupport.kt`:

```kotlin
package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.fail

/** The suspension and resume events of the coroutine labelled [label], oldest first. */
internal fun VizSession.suspensionsOf(label: String): List<CoroutineEvent> =
    store.all().filterIsInstance<CoroutineEvent>().filter {
        it.label == label && (it is CoroutineSuspended || it is CoroutineResumed)
    }

/** The kinds of [suspensionsOf], e.g. `[CoroutineSuspended, CoroutineResumed]`. */
internal fun VizSession.suspensionKindsOf(label: String): List<String> = suspensionsOf(label).map { it.kind }

/** Every wait of [label] is a suspension followed by its resume. */
internal fun VizSession.waitsAreBalanced(label: String): Boolean =
    suspensionKindsOf(label).chunked(2).all { it == listOf("CoroutineSuspended", "CoroutineResumed") }

/** The id of the coroutine labelled [label]. */
internal fun VizSession.idOf(label: String): String =
    store.all().filterIsInstance<CoroutineCreated>().single { it.label == label }.coroutineId

/** Polls the store until an event matches [predicate] and returns it; fails after [timeoutMs]. */
internal suspend fun VizSession.awaitEvent(
    timeoutMs: Long = 5_000,
    predicate: (VizEvent) -> Boolean,
): VizEvent {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        store.all().firstOrNull(predicate)?.let { return it }
        delay(10)
    }
    fail("no matching event within $timeoutMs ms")
}

/** Waits until the coroutine labelled [label] has recorded a suspension, and returns it. */
internal suspend fun VizSession.awaitSuspension(label: String): CoroutineSuspended =
    awaitEvent { it is CoroutineSuspended && it.label == label } as CoroutineSuspended

/**
 * A [VizScope] on a virtual-time dispatcher, for tests whose order must not depend on real time (a wait that a
 * `withTimeout` ends): [runCurrent] runs everything that is ready, [advancePast] moves virtual time on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class VirtualScope(
    session: VizSession,
) {
    private val scheduler = TestCoroutineScheduler()
    val scope = VizScope(session, context = StandardTestDispatcher(scheduler))

    fun runCurrent() = scheduler.runCurrent()

    fun advancePast(millis: Long) {
        scheduler.advanceTimeBy(millis + 1)
        scheduler.runCurrent()
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/RecordWaitTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals

/** The S-7 rules every wrapper relies on: when a wait sends Suspended, and when it sends Resumed. */
@Timeout(30)
class RecordWaitTest {
    private val session = VizSession("record-wait")
    private val viz = VizScope(session)

    @AfterEach
    fun close() = session.close()

    @Test
    fun `outside an instrumented coroutine it only waits`() =
        runBlocking {
            assertEquals(7, session.recordWait(WaitKind.MUTEX, "mutex-1") { 7 })
            assertEquals(emptyList(), session.store.all().filterIsInstance<CoroutineSuspended>())
        }

    @Test
    fun `a wait that returns is a suspension, then a resume`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val waiter = viz.vizLaunch("waiter") { session.recordWait(WaitKind.CHANNEL_RECEIVE, "channel-9") { gate.await() } }
            val suspended = session.awaitSuspension("waiter")

            assertEquals(WaitKind.CHANNEL_RECEIVE, suspended.waitKind)
            assertEquals("channel-9", suspended.resourceId)
            assertEquals("channel-receive", suspended.reason)
            assertEquals(SuspensionProvenance.REAL_FRAME, suspended.provenance)
            assertEquals("RecordWaitTest.kt", suspended.suspensionPoint?.fileName)

            gate.complete(Unit)
            waiter.join()
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
        }

    @Test
    fun `a wait ended by an exception the coroutine survives records the resume`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val survivor =
                viz.vizLaunch("survivor") {
                    runCatching { session.recordWait(WaitKind.CHANNEL_RECEIVE, "channel-9") { gate.await() } }
                }
            session.awaitSuspension("survivor")

            gate.completeExceptionally(IllegalStateException("closed"))
            survivor.join()

            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("survivor"))
        }

    @Test
    fun `a wait ended by cancelling the coroutine records no resume`() =
        runBlocking {
            val doomed = viz.vizLaunch("doomed") { session.recordWait(WaitKind.SEMAPHORE, "semaphore-3") { awaitCancellation() } }
            session.awaitSuspension("doomed")

            doomed.cancelAndJoin()
            session.awaitEvent { it is CoroutineCancelled && it.label == "doomed" }

            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("doomed"))
        }

    @Test
    fun `a wait that returns while its coroutine is being cancelled records no resume`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val doomed =
                viz.vizLaunch("doomed") {
                    session.recordWait(WaitKind.MUTEX, "mutex-1") { withContext(NonCancellable) { gate.await() } }
                }
            session.awaitSuspension("doomed")

            doomed.cancel()
            gate.complete(Unit)
            doomed.join()
            session.awaitEvent { it is CoroutineCancelled && it.label == "doomed" }

            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("doomed"))
        }
}
```

- [ ] **Step 3: Run it to make sure it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.RecordWaitTest"`
Expected: compilation FAILS with `Unresolved reference 'recordWait'`.

- [ ] **Step 4: Add `jobOf` to `RuntimeSnapshot`**

In `…/models/RuntimeSnapshot.kt` keep a reverse map next to `jobToCoroutineId`, fill it in `registerJob`, and add
`jobOf` after `getCoroutineIdFromJob`:

```kotlin
    private val jobToCoroutineId = ConcurrentHashMap<Job, String>()
    private val jobByCoroutineId = ConcurrentHashMap<String, Job>()
```

```kotlin
    fun registerJob(
        job: Job,
        coroutineId: String,
    ) {
        jobToCoroutineId[job] = coroutineId
        jobByCoroutineId[coroutineId] = job
    }
```

```kotlin
    /** The job registered for [coroutineId], or `null` (a coroutine not started by `vizLaunch` / `vizAsync`). */
    fun jobOf(coroutineId: String): Job? = jobByCoroutineId[coroutineId]
```

- [ ] **Step 5: Add `recordWait`**

Create `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/WaitSuspension.kt`:

```kotlin
package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.session.EventContext
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.coroutineResumed
import com.jh.proj.coroutineviz.session.coroutineSuspended
import kotlinx.coroutines.currentCoroutineContext

/**
 * Records a wait that could not be satisfied at once as a suspension (spec 2026-11-05, S-7): `CoroutineSuspended`
 * with [kind] and [resourceId] before [wait], `CoroutineResumed` when the wait ends — by returning, or by an
 * exception the coroutine may survive (a closed channel, a `withTimeout` around the wait). When the coroutine's
 * own job is being cancelled no Resumed is sent, however the wait ended: its cancellation event ends the
 * suspension. The job is looked up by coroutine id, not taken from the context, because inside `withTimeout` the
 * context's job is the timeout's, which is cancelled although the coroutine goes on. Outside an instrumented
 * coroutine it only waits.
 *
 * Callers try first without suspending and call this only when they must wait.
 */
internal suspend fun <T> VizSession.recordWait(
    kind: WaitKind,
    resourceId: String,
    wait: suspend () -> T,
): T {
    val element = currentCoroutineContext()[VizCoroutineElement] ?: return wait()
    val ctx = EventContext(this, element.coroutineId, element.jobId, element.parentCoroutineId, element.scopeId, element.label)
    val (point, provenance) = SuspensionPoint.captureWithProvenance(kind.reason)
    send(
        ctx.coroutineSuspended(
            reason = kind.reason,
            suspensionPoint = point,
            provenance = provenance,
            waitKind = kind,
            resourceId = resourceId,
        ),
    )
    try {
        return wait()
    } finally {
        // However the wait ended, no resume for a coroutine whose own job is being cancelled: its cancellation
        // event ends the suspension. A wait can also return normally while that happens (inside NonCancellable).
        if (snapshot.jobOf(element.coroutineId)?.isCancelled != true) send(ctx.coroutineResumed())
    }
}
```

- [ ] **Step 6: Point the suspension frame at the caller, not the wrapper**

In `…/events/SuspensionPoint.kt` extend `SKIPPED_CLASSES` (the `$`/`Kt` matching already covers their lambdas
and the `WaitSuspensionKt` file class):

```kotlin
        private val SKIPPED_CLASSES =
            listOf(
                SuspensionPoint::class.java.name,
                "com.jh.proj.coroutineviz.wrappers.VizScope",
                "com.jh.proj.coroutineviz.wrappers.InstrumentedDeferred",
                // The wrappers that record a wait (spec 2026-11-05, S-7) and the helper they share.
                "com.jh.proj.coroutineviz.wrappers.VizMutex",
                "com.jh.proj.coroutineviz.wrappers.VizSemaphore",
                "com.jh.proj.coroutineviz.wrappers.InstrumentedChannel",
                "com.jh.proj.coroutineviz.wrappers.WaitSuspension",
            )
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.RecordWaitTest" --tests "*SuspensionPoint*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
cd "$WT/backend" && ./gradlew :coroutine-viz-core:build
cd "$WT" && git add backend/coroutine-viz-core
git commit -m "feat(core): recordWait records a wrapper's wait as a suspension, ended by a resume unless cancelled"
```

---

### Task 5: `VizMutex` — try first, record the wait

**Files:**
- Modify: `…/wrappers/VizMutex.kt` (`lock` and imports; new `waitInQueue`)
- Test: `wrappers/MutexWaitTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/MutexWaitTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.MutexLockAcquired
import com.jh.proj.coroutineviz.events.MutexQueueChanged
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** S-7 for VizMutex: only a lock that is really held makes a coroutine wait, and the wait is a suspension. */
@Timeout(30)
class MutexWaitTest {
    private val session = VizSession("mutex-wait")
    private val viz = VizScope(session)
    private val release = CompletableDeferred<Unit>()

    @AfterEach
    fun close() {
        release.complete(Unit)
        session.close()
    }

    /** Starts "holder", which keeps [mutex] until [release] completes; returns once it holds it. */
    private suspend fun holding(mutex: VizMutex): Job =
        viz.vizLaunch("holder") { mutex.withLock { release.await() } }.also {
            session.awaitEvent { e -> e is MutexLockAcquired && e.acquirerLabel == "holder" }
        }

    @Test
    fun `an uncontended lock records no suspension`() =
        runBlocking {
            val mutex = VizMutex(session, "free-lock")
            viz.vizLaunch("solo") { mutex.withLock { } }.join()

            assertEquals(emptyList(), session.suspensionKindsOf("solo"))
        }

    @Test
    fun `a contended lock is a suspension naming the mutex, then a resume`() =
        runBlocking {
            val mutex = VizMutex(session, "inventory-lock")
            val holder = holding(mutex)
            val waiter = viz.vizLaunch("waiter") { mutex.withLock { } }
            val suspended = session.awaitSuspension("waiter")

            assertEquals(WaitKind.MUTEX, suspended.waitKind)
            assertEquals(mutex.mutexId, suspended.resourceId)
            assertEquals("mutex", suspended.reason)
            assertEquals("MutexWaitTest.kt", suspended.suspensionPoint?.fileName)

            release.complete(Unit)
            withTimeout(10.seconds) { joinAll(holder, waiter) }
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
            assertEquals(0, mutex.getWaitQueueSize())
        }

    @Test
    fun `a waiter cancelled in the queue leaves it and records no resume`() =
        runBlocking {
            val mutex = VizMutex(session, "inventory-lock")
            holding(mutex)
            val doomed = viz.vizLaunch("doomed") { mutex.withLock { } }
            session.awaitSuspension("doomed")

            doomed.cancelAndJoin()
            session.awaitEvent { it is CoroutineCancelled && it.label == "doomed" }

            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("doomed"))
            assertEquals(0, mutex.getWaitQueueSize())
            // Nothing else touches the mutex meanwhile: the holder still waits for `release`.
            assertEquals(emptyList(), session.store.all().filterIsInstance<MutexQueueChanged>().last().waitingCoroutineIds)
        }

    @Test
    fun `a wait ended by a timeout the coroutine survives records the resume and leaves the queue`() =
        runBlocking {
            val mutex = VizMutex(session, "inventory-lock")
            val virtual = VirtualScope(session)
            virtual.scope.vizLaunch("holder") { mutex.withLock { release.await() } }
            val impatient = virtual.scope.vizLaunch("impatient") { assertNull(withTimeoutOrNull(100) { mutex.withLock { } }) }
            // Virtual time: the holder takes the lock and "impatient" starts waiting before any timeout can fire.
            virtual.runCurrent()
            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("impatient"))

            virtual.advancePast(100)

            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("impatient"))
            assertTrue(impatient.isCompleted && !impatient.isCancelled, "impatient went on after the timeout")
            assertEquals(0, mutex.getWaitQueueSize())
        }

    @Test
    fun `workers handed the lock in a loop record balanced waits`() =
        runBlocking {
            val mutex = VizMutex(session, "hot-lock")
            val holder = holding(mutex)
            val workers = (1..5).map { n -> viz.vizLaunch("worker-$n") { repeat(30) { mutex.withLock { yield() } } } }
            // Every worker has queued behind the holder before anyone gets the lock: contention is certain.
            for (n in 1..5) session.awaitSuspension("worker-$n")
            release.complete(Unit)
            withTimeout(20.seconds) { (workers + holder).joinAll() }

            for (n in 1..5) {
                assertTrue(session.waitsAreBalanced("worker-$n"), "worker-$n: ${session.suspensionKindsOf("worker-$n")}")
            }
            assertEquals(0, mutex.getWaitQueueSize())
            assertTrue(!mutex.isLocked)
        }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.MutexWaitTest"`
Expected: FAIL — the contended, cancelled, timeout and loop tests find no `CoroutineSuspended` (the
`awaitSuspension` helper fails with "no matching event").

- [ ] **Step 3: Rewrite `lock`, add `waitInQueue`**

In `…/wrappers/VizMutex.kt`: remove `import kotlinx.coroutines.CancellationException`, add
`import com.jh.proj.coroutineviz.events.WaitKind`, and replace `lock` (lines 86–154) with the following. Events
are sent directly, as today; `unlock`, `tryLock`, `withLock` and `emitQueueChanged` are unchanged.

```kotlin
    /**
     * Acquire the mutex lock. Tries first; only when the lock is held does the coroutine queue and wait, and that
     * wait is recorded as a suspension (spec 2026-11-05, S-7).
     */
    suspend fun lock(owner: Any? = null) {
        val coroutineElement = currentCoroutineContext()[VizCoroutineElement]
        val coroutineId = coroutineElement?.coroutineId ?: "unknown-${System.nanoTime()}"
        val coroutineLabel = coroutineElement?.label

        // Try first, so whether to wait is never decided from `isLocked`, which another coroutine can change.
        val acquiredAtOnce = delegate.tryLock(owner)

        session.send(
            MutexLockRequested(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                mutexId = mutexId,
                mutexLabel = label,
                requesterId = coroutineId,
                requesterLabel = coroutineLabel,
                isLocked = !acquiredAtOnce,
                queuePosition = if (acquiredAtOnce) 0 else waitQueue.size + 1,
            ),
        )

        val startWait = System.nanoTime()
        if (!acquiredAtOnce) waitInQueue(WaiterInfo(coroutineId, coroutineLabel, startWait), owner)
        val waitDuration = System.nanoTime() - startWait

        currentOwnerId = coroutineId
        currentOwnerLabel = coroutineLabel
        holdStartTimes[coroutineId] = System.nanoTime()

        session.send(
            MutexLockAcquired(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                mutexId = mutexId,
                mutexLabel = label,
                acquirerId = coroutineId,
                acquirerLabel = coroutineLabel,
                waitDurationNanos = waitDuration,
            ),
        )

        if (!acquiredAtOnce) emitQueueChanged()
    }

    /** Queues [waiter] and waits for the lock, recorded as a suspension; the waiter leaves the queue however the wait ends. */
    private suspend fun waitInQueue(
        waiter: WaiterInfo,
        owner: Any?,
    ) {
        waitQueue.add(waiter)
        emitQueueChanged()
        var acquired = false
        try {
            session.recordWait(WaitKind.MUTEX, mutexId) { delegate.lock(owner) }
            acquired = true
        } finally {
            // Without the lock (cancelled, or a timeout around the wait) the waiter no longer waits; left in the
            // queue it would show waiting for ever. With the lock, `lock` reports the new queue after Acquired.
            if (waitQueue.remove(waiter) && !acquired) emitQueueChanged()
        }
    }
```

- [ ] **Step 4: Run the mutex tests and the existing sync tests**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.MutexWaitTest"`
Expected: PASS.
Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.sync.*"`
Expected: PASS (`SyncPrimitivesTest` asserts `queuePosition == 1` and `isLocked == true` for a waiter; both still hold).

- [ ] **Step 5: Commit**

```bash
cd "$WT/backend" && ./gradlew :coroutine-viz-core:build
cd "$WT" && git add backend/coroutine-viz-core
git commit -m "fix(core): a coroutine waiting for a VizMutex is recorded as suspended, not active"
```

---

### Task 6: `VizSemaphore` — per-holder permits, unknown releases, recorded waits

**Files:**
- Modify: `…/events/SemaphoreEvents.kt` (`SemaphorePermitReleased`, `SemaphoreStateChanged`)
- Modify (rewrite): `…/wrappers/VizSemaphore.kt`
- Modify: `…/checksystem/SyncValidators.kt:188` (`verifyPermitBounds`)
- Test: `events/SemaphoreHolderWireTest.kt` (new), `compat/BinaryCompatibilityTest.kt`, `wrappers/SemaphoreWaitTest.kt` (new), `checksystem/SyncValidatorsTest.kt`

- [ ] **Step 1: Write the failing wire and compatibility tests**

Create `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/events/SemaphoreHolderWireTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.events

import com.jh.proj.coroutineviz.appJson
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The semaphore part of the S-7 contract: permits per holder, and a release whose holder is unknown. */
class SemaphoreHolderWireTest {
    private val serializer = PolymorphicSerializer(VizEvent::class)

    @Test
    fun `the new fields round-trip`() {
        val released =
            SemaphorePermitReleased(
                sessionId = "s",
                seq = 1,
                tsNanos = 2,
                semaphoreId = "semaphore-1",
                semaphoreLabel = "pool",
                releaserId = "c-2",
                releaserLabel = "stranger",
                newAvailablePermits = 2,
                holdDurationNanos = 0,
                holderUnknown = true,
            )
        val state =
            SemaphoreStateChanged(
                sessionId = "s",
                seq = 3,
                tsNanos = 4,
                semaphoreId = "semaphore-1",
                semaphoreLabel = "pool",
                availablePermits = 2,
                totalPermits = 3,
                activeHolders = listOf("c-1"),
                activeHolderLabels = listOf("owner"),
                waitingCoroutines = emptyList(),
                waitingLabels = emptyList(),
                activeHolderPermits = listOf(2),
                unknownHolderPermits = 1,
            )

        assertEquals(released, appJson.decodeFromString(serializer, appJson.encodeToString(serializer, released)))
        assertEquals(state, appJson.decodeFromString(serializer, appJson.encodeToString(serializer, state)))
    }

    @Test
    fun `older events read as attributed, with no counts`() {
        val oldRelease =
            """{"type":"SemaphorePermitReleased","sessionId":"s","seq":1,"tsNanos":2,"semaphoreId":"x",""" +
                """"semaphoreLabel":null,"releaserId":"c","releaserLabel":null,"newAvailablePermits":1,"holdDurationNanos":5}"""
        val oldState =
            """{"type":"SemaphoreStateChanged","sessionId":"s","seq":3,"tsNanos":4,"semaphoreId":"x","semaphoreLabel":null,""" +
                """"availablePermits":1,"totalPermits":2,"activeHolders":["c"],"activeHolderLabels":[null],""" +
                """"waitingCoroutines":[],"waitingLabels":[]}"""

        assertFalse((appJson.decodeFromString(serializer, oldRelease) as SemaphorePermitReleased).holderUnknown)
        val state = appJson.decodeFromString(serializer, oldState) as SemaphoreStateChanged
        assertEquals(emptyList(), state.activeHolderPermits)
        assertEquals(0, state.unknownHolderPermits)
    }
}
```

In `compat/BinaryCompatibilityTest.kt` add imports `com.jh.proj.coroutineviz.events.SemaphorePermitReleased`,
`com.jh.proj.coroutineviz.events.SemaphoreStateChanged`, and:

```kotlin
    @Test
    fun `semaphore events keep their 0_3_x constructors`() {
        val long = Long::class.javaPrimitiveType
        val int = Int::class.javaPrimitiveType
        val str = String::class.java
        val list = List::class.java
        SemaphorePermitReleased::class.java.getDeclaredConstructor(str, long, long, str, str, str, str, int, long)
        SemaphoreStateChanged::class.java.getDeclaredConstructor(str, long, long, str, str, int, int, list, list, list, list)
    }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.events.SemaphoreHolderWireTest"`
Expected: compilation FAILS (`No parameter with name 'holderUnknown'`).

- [ ] **Step 3: Add the event fields with hidden 0.3.x constructors**

In `…/events/SemaphoreEvents.kt` replace `SemaphorePermitReleased` with:

```kotlin
@Serializable
@SerialName("SemaphorePermitReleased")
data class SemaphorePermitReleased(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    override val semaphoreId: String,
    override val semaphoreLabel: String?,
    val releaserId: String,
    val releaserLabel: String?,
    val newAvailablePermits: Int,
    val holdDurationNanos: Long,
    /**
     * True when [releaserId] held no permit of this semaphore, or no coroutine was known: a permit came back, but
     * whose is unknown, so no holder is named and [holdDurationNanos] is 0 (spec 2026-11-05, S-7). False on older
     * events, which named the releaser as the holder.
     */
    val holderUnknown: Boolean = false,
) : SemaphoreEvent {
    /** The 0.3.x JVM constructor, kept for binary compatibility. Hidden from source resolution. */
    @Deprecated("binary compatibility with 0.3.x", level = DeprecationLevel.HIDDEN)
    constructor(
        sessionId: String,
        seq: Long,
        tsNanos: Long,
        semaphoreId: String,
        semaphoreLabel: String?,
        releaserId: String,
        releaserLabel: String?,
        newAvailablePermits: Int,
        holdDurationNanos: Long,
    ) : this(
        sessionId, seq, tsNanos, semaphoreId, semaphoreLabel, releaserId, releaserLabel, newAvailablePermits,
        holdDurationNanos, false,
    )

    override val kind: String get() = "SemaphorePermitReleased"
}
```

and `SemaphoreStateChanged` with:

```kotlin
@Serializable
@SerialName("SemaphoreStateChanged")
data class SemaphoreStateChanged(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    override val semaphoreId: String,
    override val semaphoreLabel: String?,
    val availablePermits: Int,
    val totalPermits: Int,
    /** Coroutines holding permits they acquired, each listed once. */
    val activeHolders: List<String>,
    val activeHolderLabels: List<String?>,
    val waitingCoroutines: List<String>,
    val waitingLabels: List<String?>,
    /** How many permits each of [activeHolders] acquired and has not released itself; empty on older events. */
    val activeHolderPermits: List<Int> = emptyList(),
    /**
     * Of the permits counted in [activeHolderPermits], how many are no longer in use: another coroutine released
     * them, so which holder lost one is unknown (spec 2026-11-05, S-6/S-7).
     */
    val unknownHolderPermits: Int = 0,
) : SemaphoreEvent {
    /** The 0.3.x JVM constructor, kept for binary compatibility. Hidden from source resolution. */
    @Deprecated("binary compatibility with 0.3.x", level = DeprecationLevel.HIDDEN)
    constructor(
        sessionId: String,
        seq: Long,
        tsNanos: Long,
        semaphoreId: String,
        semaphoreLabel: String?,
        availablePermits: Int,
        totalPermits: Int,
        activeHolders: List<String>,
        activeHolderLabels: List<String?>,
        waitingCoroutines: List<String>,
        waitingLabels: List<String?>,
    ) : this(
        sessionId, seq, tsNanos, semaphoreId, semaphoreLabel, availablePermits, totalPermits, activeHolders,
        activeHolderLabels, waitingCoroutines, waitingLabels, emptyList(), 0,
    )

    override val kind: String get() = "SemaphoreStateChanged"
}
```

Keep each class's existing KDoc above it.

- [ ] **Step 4: Run the wire tests**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.events.SemaphoreHolderWireTest" --tests "com.jh.proj.coroutineviz.compat.BinaryCompatibilityTest" --tests "com.jh.proj.coroutineviz.events.EventSerializationTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing wrapper test**

Create `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/SemaphoreWaitTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.SemaphorePermitAcquired
import com.jh.proj.coroutineviz.events.SemaphorePermitReleased
import com.jh.proj.coroutineviz.events.SemaphoreStateChanged
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** S-7 for VizSemaphore: recorded waits, permits counted per holder, and releases whose holder is unknown. */
@Timeout(30)
class SemaphoreWaitTest {
    private val session = VizSession("semaphore-wait")
    private val viz = VizScope(session)
    private val release = CompletableDeferred<Unit>()

    @AfterEach
    fun close() {
        release.complete(Unit)
        session.close()
    }

    private fun states() = session.store.all().filterIsInstance<SemaphoreStateChanged>()

    private fun releases() = session.store.all().filterIsInstance<SemaphorePermitReleased>()

    /** Starts "holder", which keeps a permit of [semaphore] until [release] completes; returns once it holds it. */
    private suspend fun holding(semaphore: VizSemaphore): Job =
        viz.vizLaunch("holder") { semaphore.withPermit { release.await() } }.also {
            session.awaitEvent { e -> e is SemaphorePermitAcquired && e.acquirerLabel == "holder" }
        }

    @Test
    fun `a free permit records no suspension`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 1, label = "gateway")
            viz.vizLaunch("solo") { semaphore.withPermit { } }.join()

            assertEquals(emptyList(), session.suspensionKindsOf("solo"))
        }

    @Test
    fun `waiting for a permit is a suspension naming the semaphore, then a resume`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 1, label = "gateway")
            val holder = holding(semaphore)
            val waiter = viz.vizLaunch("waiter") { semaphore.withPermit { } }
            val suspended = session.awaitSuspension("waiter")

            assertEquals(WaitKind.SEMAPHORE, suspended.waitKind)
            assertEquals(semaphore.semaphoreId, suspended.resourceId)
            assertEquals("semaphore", suspended.reason)
            assertEquals("SemaphoreWaitTest.kt", suspended.suspensionPoint?.fileName)

            release.complete(Unit)
            joinAll(holder, waiter)
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
            assertEquals(0, semaphore.getWaitQueueSize())
        }

    @Test
    fun `a waiter cancelled in the queue leaves it and records no resume`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 1, label = "gateway")
            holding(semaphore)
            val doomed = viz.vizLaunch("doomed") { semaphore.withPermit { } }
            session.awaitSuspension("doomed")

            doomed.cancelAndJoin()
            session.awaitEvent { it is CoroutineCancelled && it.label == "doomed" }

            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("doomed"))
            assertEquals(0, semaphore.getWaitQueueSize())
            assertEquals(emptyList(), states().last().waitingCoroutines)
        }

    @Test
    fun `a wait ended by a timeout the coroutine survives records the resume and leaves the queue`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 1, label = "gateway")
            val virtual = VirtualScope(session)
            virtual.scope.vizLaunch("holder") { semaphore.withPermit { release.await() } }
            val impatient = virtual.scope.vizLaunch("impatient") { assertNull(withTimeoutOrNull(100) { semaphore.withPermit { } }) }
            // Virtual time: the holder takes the permit and "impatient" starts waiting before any timeout can fire.
            virtual.runCurrent()
            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("impatient"))

            virtual.advancePast(100)

            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("impatient"))
            assertTrue(impatient.isCompleted && !impatient.isCancelled, "impatient went on after the timeout")
            assertEquals(0, semaphore.getWaitQueueSize())
        }

    @Test
    fun `workers handed permits in a loop record balanced waits`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 2, label = "pool")
            val holders = (1..2).map { n -> viz.vizLaunch("holder-$n") { semaphore.withPermit { release.await() } } }
            session.awaitEvent { it is SemaphoreStateChanged && it.availablePermits == 0 }
            val workers = (1..5).map { n -> viz.vizLaunch("worker-$n") { repeat(30) { semaphore.withPermit { yield() } } } }
            // Every worker has queued behind the two holders before anyone gets a permit: contention is certain.
            for (n in 1..5) session.awaitSuspension("worker-$n")
            release.complete(Unit)
            withTimeout(20.seconds) { (workers + holders).joinAll() }

            for (n in 1..5) {
                assertTrue(session.waitsAreBalanced("worker-$n"), "worker-$n: ${session.suspensionKindsOf("worker-$n")}")
            }
            // Live state once everyone is done, not a snapshot event (their order under handoff is not guaranteed).
            assertEquals(2, semaphore.availablePermits)
            assertEquals(0, semaphore.getWaitQueueSize())
            assertEquals(0, semaphore.getActiveHolderCount())
        }

    @Test
    fun `a coroutine holding two permits is listed once, with both`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 3, label = "pool")
            viz.vizLaunch("double") {
                semaphore.acquire()
                semaphore.acquire()
                semaphore.release()
                semaphore.release()
            }.join()

            assertEquals(listOf(listOf(1), listOf(2), listOf(1), emptyList()), states().map { it.activeHolderPermits })
            assertEquals(listOf(listOf("double"), listOf("double"), listOf("double"), emptyList()), states().map { it.activeHolderLabels })
            assertEquals(listOf(false, false), releases().map { it.holderUnknown })
            assertTrue(releases().all { it.holdDurationNanos > 0 })
        }

    @Test
    fun `a permit released by a coroutine that holds none has an unknown holder`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 3, label = "pool")
            val owner =
                viz.vizLaunch("owner") {
                    semaphore.acquire()
                    semaphore.acquire()
                    release.await()
                    semaphore.release()
                }
            session.awaitEvent { it is SemaphoreStateChanged && it.activeHolderPermits == listOf(2) }
            viz.vizLaunch("stranger") { semaphore.release() }.join()

            val byStranger = releases().single()
            assertEquals(session.idOf("stranger"), byStranger.releaserId)
            assertTrue(byStranger.holderUnknown)
            assertEquals(0L, byStranger.holdDurationNanos)
            val afterStranger = states().last()
            assertEquals(listOf(session.idOf("owner")), afterStranger.activeHolders)
            assertEquals(listOf(2), afterStranger.activeHolderPermits)
            assertEquals(1, afterStranger.unknownHolderPermits)

            release.complete(Unit)
            owner.join()
            assertFalse(releases().last().holderUnknown)
            val idle = states().last()
            assertEquals(3, idle.availablePermits)
            assertEquals(emptyList(), idle.activeHolders, "with every permit free, nobody holds one")
            assertEquals(0, idle.unknownHolderPermits)
        }

    @Test
    fun `a release outside any coroutine has an unknown holder`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 2, label = "slots", acquiredPermits = 2)
            semaphore.release()

            val released = releases().single()
            assertEquals("unknown", released.releaserId)
            assertTrue(released.holderUnknown)
        }
}
```

- [ ] **Step 6: Run it to make sure it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.SemaphoreWaitTest"`
Expected: FAIL — no `CoroutineSuspended` for waiters, `activeHolderPermits` empty, `holderUnknown` false.

- [ ] **Step 7: Rewrite `VizSemaphore`**

Replace `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizSemaphore.kt` with the
following. The public API (constructor, `availablePermits`, `getActiveHolderCount`, `getWaitQueueSize`,
`getUtilization`, `acquire`, `release`, `tryAcquire`, `withPermit`, `HolderInfo`, `WaiterInfo`, `vizSemaphore`)
is unchanged. Events are sent directly, as today (see "Known limitation").

```kotlin
package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.SemaphoreAcquireRequested
import com.jh.proj.coroutineviz.events.SemaphoreCreated
import com.jh.proj.coroutineviz.events.SemaphorePermitAcquired
import com.jh.proj.coroutineviz.events.SemaphorePermitReleased
import com.jh.proj.coroutineviz.events.SemaphoreStateChanged
import com.jh.proj.coroutineviz.events.SemaphoreTryAcquireFailed
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Instrumented Semaphore wrapper that emits visualization events.
 *
 * This wrapper provides full transparency into semaphore behavior:
 * - Permit acquisition and release timing
 * - Active holder tracking, with the number of permits each holds
 * - Wait queue management; a wait for a permit is recorded as a suspension (spec 2026-11-05, S-7)
 * - Utilization metrics
 *
 * A permit released by a coroutine that holds none (or from outside any coroutine) is reported with
 * `holderUnknown`: whose permit came back cannot be known, so none is named.
 *
 * Real-world use cases:
 * - Connection pool limiting (max N connections)
 * - Rate limiting (max N concurrent API calls)
 * - Resource throttling
 * - Batch processing control
 *
 * @param session The visualization session for emitting events
 * @param permits Number of permits (concurrent access limit)
 * @param label Human-readable label for the semaphore
 */
class VizSemaphore(
    private val session: VizSession,
    val permits: Int,
    val label: String? = null,
    // F10: number of permits already held at construction. Lets a counting semaphore start with
    // fewer-than-`permits` available (e.g. a producer-consumer "full slots" semaphore that starts
    // empty: permits = capacity, acquiredPermits = capacity → 0 available). kotlinx requires
    // permits >= 1, so `Semaphore(0)` is illegal — express "0 available" via acquiredPermits instead.
    acquiredPermits: Int = 0,
) {
    val semaphoreId: String = "semaphore-${session.nextSeq()}"
    private val delegate = Semaphore(permits, acquiredPermits)
    private val totalPermits = permits

    // The permits each coroutine acquired and has not released itself, oldest first; a coroutine can hold several.
    // Each holder's list is replaced, never mutated, through merge/computeIfPresent.
    private val holders = ConcurrentHashMap<String, List<HolderInfo>>()

    // Wait queue tracking
    private val waitQueue = ConcurrentLinkedQueue<WaiterInfo>()

    data class HolderInfo(
        val coroutineId: String,
        val label: String?,
        val acquireTime: Long,
    )

    data class WaiterInfo(
        val coroutineId: String,
        val label: String?,
        val requestTime: Long,
        val permitsRequested: Int,
    )

    init {
        session.send(
            SemaphoreCreated(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                semaphoreId = semaphoreId,
                semaphoreLabel = label,
                totalPermits = totalPermits,
            ),
        )
    }

    /**
     * Get number of available permits
     */
    val availablePermits: Int get() = delegate.availablePermits

    /**
     * Number of coroutines holding at least one permit they acquired
     */
    fun getActiveHolderCount(): Int = holders.size

    /**
     * Get number of coroutines waiting for permits
     */
    fun getWaitQueueSize(): Int = waitQueue.size

    /**
     * Calculate current utilization (0.0 to 1.0)
     */
    fun getUtilization(): Double {
        return (totalPermits - availablePermits).toDouble() / totalPermits
    }

    /**
     * Acquire a permit. Tries first; only when no permit is free does the coroutine queue and wait, and that wait
     * is recorded as a suspension (spec 2026-11-05, S-7).
     */
    suspend fun acquire() {
        val coroutineElement = currentCoroutineContext()[VizCoroutineElement]
        val coroutineId = coroutineElement?.coroutineId ?: "unknown-${System.nanoTime()}"
        val coroutineLabel = coroutineElement?.label

        // Try first, so whether to wait is never decided from `availablePermits`, which another coroutine can change.
        val available = delegate.availablePermits
        val acquiredAtOnce = delegate.tryAcquire()

        session.send(
            SemaphoreAcquireRequested(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                semaphoreId = semaphoreId,
                semaphoreLabel = label,
                requesterId = coroutineId,
                requesterLabel = coroutineLabel,
                availablePermits = if (acquiredAtOnce) maxOf(available, 1) else 0,
                permitsRequested = 1,
            ),
        )

        val startWait = System.nanoTime()
        if (!acquiredAtOnce) waitInQueue(WaiterInfo(coroutineId, coroutineLabel, startWait, 1))
        val waitDuration = System.nanoTime() - startWait

        addHolder(HolderInfo(coroutineId, coroutineLabel, System.nanoTime()))

        session.send(
            SemaphorePermitAcquired(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                semaphoreId = semaphoreId,
                semaphoreLabel = label,
                acquirerId = coroutineId,
                acquirerLabel = coroutineLabel,
                remainingPermits = delegate.availablePermits,
                waitDurationNanos = waitDuration,
            ),
        )

        emitStateChanged()
    }

    /** Queues [waiter] and waits for a permit, recorded as a suspension; the waiter leaves the queue however the wait ends. */
    private suspend fun waitInQueue(waiter: WaiterInfo) {
        waitQueue.add(waiter)
        emitStateChanged()
        var acquired = false
        try {
            session.recordWait(WaitKind.SEMAPHORE, semaphoreId) { delegate.acquire() }
            acquired = true
        } finally {
            // Without a permit (cancelled, or a timeout around the wait) the waiter no longer waits; left in the
            // queue it would show waiting for ever. With a permit, `acquire` reports the new state after Acquired.
            if (waitQueue.remove(waiter) && !acquired) emitStateChanged()
        }
    }

    /**
     * Release a permit back to the pool. It counts as the releasing coroutine's own (its oldest) when that
     * coroutine holds one; otherwise whose permit came back is unknown, and the event says so (`holderUnknown`)
     * instead of naming a holder.
     */
    fun release() {
        val coroutineElement = VizCoroutineElement.current()
        val released = coroutineElement?.let { takeOldestPermit(it.coroutineId) }

        delegate.release()
        // With every permit free nobody holds one: forget holders whose permits other coroutines released.
        if (delegate.availablePermits == totalPermits) holders.clear()

        session.send(
            SemaphorePermitReleased(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                semaphoreId = semaphoreId,
                semaphoreLabel = label,
                releaserId = coroutineElement?.coroutineId ?: "unknown",
                releaserLabel = coroutineElement?.label,
                newAvailablePermits = delegate.availablePermits,
                holdDurationNanos = released?.let { System.nanoTime() - it.acquireTime } ?: 0L,
                holderUnknown = released == null,
            ),
        )

        emitStateChanged()
    }

    /**
     * Try to acquire a permit without suspending
     *
     * @return true if permit was acquired, false if none available
     */
    fun tryAcquire(): Boolean {
        // The holder must be the real coroutine, or its own release would count as another's (holder unknown).
        val coroutineElement = VizCoroutineElement.current()
        val coroutineId = coroutineElement?.coroutineId ?: "unknown-${System.nanoTime()}"
        val coroutineLabel = coroutineElement?.label

        val acquired = delegate.tryAcquire()

        if (acquired) {
            addHolder(HolderInfo(coroutineId, coroutineLabel, System.nanoTime()))

            session.send(
                SemaphorePermitAcquired(
                    sessionId = session.sessionId,
                    seq = session.nextSeq(),
                    tsNanos = System.nanoTime(),
                    semaphoreId = semaphoreId,
                    semaphoreLabel = label,
                    acquirerId = coroutineId,
                    acquirerLabel = coroutineLabel,
                    remainingPermits = delegate.availablePermits,
                    waitDurationNanos = 0,
                ),
            )

            emitStateChanged()
        } else {
            session.send(
                SemaphoreTryAcquireFailed(
                    sessionId = session.sessionId,
                    seq = session.nextSeq(),
                    tsNanos = System.nanoTime(),
                    semaphoreId = semaphoreId,
                    semaphoreLabel = label,
                    requesterId = coroutineId,
                    requesterLabel = coroutineLabel,
                    availablePermits = delegate.availablePermits,
                    permitsRequested = 1,
                ),
            )
        }

        return acquired
    }

    /**
     * Execute block while holding a permit
     */
    suspend fun <T> withPermit(action: suspend () -> T): T {
        acquire()
        try {
            return action()
        } finally {
            release()
        }
    }

    private fun addHolder(holder: HolderInfo) {
        holders.merge(holder.coroutineId, listOf(holder)) { held, added -> held + added }
    }

    /** Removes and returns the oldest permit [coroutineId] holds, or `null` when it holds none. */
    private fun takeOldestPermit(coroutineId: String): HolderInfo? {
        var taken: HolderInfo? = null
        holders.computeIfPresent(coroutineId) { _, held ->
            taken = held.first()
            held.drop(1).ifEmpty { null }
        }
        return taken
    }

    private fun emitStateChanged() {
        // Atomic snapshots: Collection.toList() has a size==1 fast path that calls
        // iterator().next(), which races a concurrent remove() on these concurrent
        // collections and throws NoSuchElementException. toMutableList() copies via
        // ArrayList(this) → toArray(), which is a race-safe snapshot.
        val waiters = waitQueue.toMutableList()
        val held = holders.values.toMutableList()
        val available = delegate.availablePermits
        // Listed permits beyond those in use were released by other coroutines: their holder is unknown.
        val unknownHolderPermits = (held.sumOf { it.size } - (totalPermits - available)).coerceAtLeast(0)

        session.send(
            SemaphoreStateChanged(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                semaphoreId = semaphoreId,
                semaphoreLabel = label,
                availablePermits = available,
                totalPermits = totalPermits,
                activeHolders = held.map { it.first().coroutineId },
                activeHolderLabels = held.map { it.first().label },
                waitingCoroutines = waiters.map { it.coroutineId },
                waitingLabels = waiters.map { it.label },
                activeHolderPermits = held.map { it.size },
                unknownHolderPermits = unknownHolderPermits,
            ),
        )
    }
}

// ============================================================================
// Extension function for creating VizSemaphore in VizScope
// ============================================================================

/**
 * Create a new instrumented Semaphore within this VizScope
 *
 * Example:
 * ```kotlin
 * val connectionPool = vizSemaphore("db-connections", permits = 3)
 * connectionPool.withPermit {
 *     // Use connection
 * }
 * ```
 */
fun VizScope.vizSemaphore(
    label: String? = null,
    permits: Int,
    acquiredPermits: Int = 0,
): VizSemaphore {
    return VizSemaphore(session, permits, label, acquiredPermits)
}
```

- [ ] **Step 8: Let the permit-bounds check count permits, not listed holders**

A valid history now lists more holders than permits: with 2 permits, A and B acquire, a coroutine holding none
releases one, then C acquires — A, B and C are listed (one of A's or B's permits is unknown). In
`backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/checksystem/SyncValidatorsTest.kt` give the
`semaphoreStateChanged` helper two parameters, `activeHolderPermits: List<Int> = emptyList()` and
`unknownHolderPermits: Int = 0`, passed through to the event, and add:

```kotlin
    @Test
    fun `verifyPermitBounds counts permits in use when holders carry counts`() {
        val recorder = EventRecorder()
        // A and B acquired, a coroutine holding none released one, C acquired: 3 listed, 2 in use.
        recorder.record(
            semaphoreStateChanged(
                "s-1",
                availablePermits = 0,
                totalPermits = 2,
                activeHolders = listOf("c-A", "c-B", "c-C"),
                activeHolderPermits = listOf(1, 1, 1),
                unknownHolderPermits = 1,
            ),
        )

        SemaphoreValidator(recorder).verifyPermitBounds("s-1", 2) // Should not throw
    }

    @Test
    fun `verifyPermitBounds fails when counted permits exceed the limit`() {
        val recorder = EventRecorder()
        recorder.record(
            semaphoreStateChanged(
                "s-1",
                availablePermits = 0,
                totalPermits = 2,
                activeHolders = listOf("c-A"),
                activeHolderPermits = listOf(3),
            ),
        )

        val error = assertFailsWith<AssertionError> { SemaphoreValidator(recorder).verifyPermitBounds("s-1", 2) }
        assertTrue(error.message!!.contains("3 permits held"))
    }
```

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.checksystem.SyncValidatorsTest"`
Expected: FAIL — the first new test throws "permit limit exceeded: 3 holders"; the second finds no "3 permits held".

In `…/checksystem/SyncValidators.kt`, `verifyPermitBounds`, replace the holder check with:

```kotlin
            // Holders are listed once with their permit counts, and some counted permits may have been released by
            // a coroutine that held none (unknownHolderPermits). Older events carry no counts: one permit each.
            val held =
                if (state.activeHolderPermits.isEmpty()) {
                    state.activeHolders.size
                } else {
                    state.activeHolderPermits.sum() - state.unknownHolderPermits
                }
            if (held > maxPermits) {
                throw AssertionError(
                    "Semaphore $semaphoreId permit limit exceeded: " +
                        "$held permits held by ${state.activeHolders.size} holders but only $maxPermits permits",
                )
            }
```

The existing `verifyPermitBounds fails with too many holders` test still passes (its message reads
"3 permits held by 3 holders but only 2 permits").

- [ ] **Step 9: Run the semaphore tests and the existing sync tests**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.SemaphoreWaitTest" --tests "com.jh.proj.coroutineviz.wrappers.VizSemaphoreTest" --tests "com.jh.proj.coroutineviz.checksystem.SyncValidatorsTest"`
Expected: PASS.
Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.sync.*"`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
cd "$WT/backend" && ./gradlew :coroutine-viz-core:build
cd "$WT" && git add backend/coroutine-viz-core
git commit -m "fix(core): VizSemaphore records waits as suspensions, counts permits per holder, reports unknown holders"
```

---

### Task 7: `InstrumentedChannel` — send, receive, receiveCatching and iteration

**Files:**
- Modify: `…/wrappers/InstrumentedChannel.kt`
- Test: `wrappers/ChannelWaitTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/ChannelWaitTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.channel.ChannelReceiveCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** S-7 for channels: send, receive, receiveCatching and iteration record their waits; select is not covered. */
@Timeout(30)
class ChannelWaitTest {
    private val session = VizSession("channel-wait")
    private val viz = VizScope(session)

    @AfterEach
    fun close() = session.close()

    /** Starts [label] doing [operation], which must wait; cancels it while it waits: no resume may be recorded. */
    private suspend fun cancelledWhileWaiting(
        label: String,
        operation: suspend () -> Unit,
    ) {
        val job = viz.vizLaunch(label) { operation() }
        session.awaitSuspension(label)

        job.cancelAndJoin()
        session.awaitEvent { it is CoroutineCancelled && it.label == label }

        assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf(label), label)
    }

    /**
     * Runs [operation], which must wait, under a timeout the coroutine survives: the resume must be recorded.
     * Virtual time makes the order certain: the wait starts before the timeout can fire.
     */
    private suspend fun survivesTimeout(
        label: String,
        operation: suspend () -> Unit,
    ) {
        val virtual = VirtualScope(session)
        val job = virtual.scope.vizLaunch(label) { assertNull(withTimeoutOrNull(100) { operation() }) }
        virtual.runCurrent()
        assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf(label), label)

        virtual.advancePast(100)

        assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf(label), label)
        assertTrue(job.isCompleted && !job.isCancelled, "$label went on after the timeout")
    }

    @Test
    fun `a send that finds room and a receive that finds an element record no suspension`() =
        runBlocking {
            val tray = viz.vizChannel<Int>(1, "tray")
            viz.vizLaunch("cook") { tray.send(1) }.join()
            viz.vizLaunch("waiter") { tray.receive() }.join()

            assertEquals(emptyList(), session.suspensionKindsOf("cook"))
            assertEquals(emptyList(), session.suspensionKindsOf("waiter"))
        }

    @Test
    fun `a send nobody takes yet is a suspension naming the channel, then a resume`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            val cook = viz.vizLaunch("cook") { pass.send(1) }
            val suspended = session.awaitSuspension("cook")

            assertEquals(WaitKind.CHANNEL_SEND, suspended.waitKind)
            assertEquals(pass.channelId, suspended.resourceId)
            assertEquals("channel-send", suspended.reason)
            assertEquals("ChannelWaitTest.kt", suspended.suspensionPoint?.fileName)

            viz.vizLaunch("waiter") { pass.receive() }.join()
            cook.join()
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("cook"))
        }

    @Test
    fun `a receive on an empty channel is a suspension, then a resume`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            val waiter = viz.vizLaunch("waiter") { pass.receive() }
            val suspended = session.awaitSuspension("waiter")

            assertEquals(WaitKind.CHANNEL_RECEIVE, suspended.waitKind)
            assertEquals(pass.channelId, suspended.resourceId)

            viz.vizLaunch("cook") { pass.send(1) }.join()
            waiter.join()
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
        }

    @Test
    fun `receiveCatching waits, and closing the channel ends the wait`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            val closed = CompletableDeferred<Boolean>()
            val waiter = viz.vizLaunch("waiter") { closed.complete(pass.receiveCatching().isClosed) }
            val suspended = session.awaitSuspension("waiter")
            assertEquals(WaitKind.CHANNEL_RECEIVE, suspended.waitKind)
            assertEquals(pass.channelId, suspended.resourceId)

            pass.close()
            waiter.join()

            assertTrue(closed.await())
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
        }

    @Test
    fun `iterating waits for each element and names the receiver`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            val received = mutableListOf<Int>()
            val waiter = viz.vizLaunch("waiter") { for (plate in pass) received += plate }
            val suspended = session.awaitSuspension("waiter")
            assertEquals(WaitKind.CHANNEL_RECEIVE, suspended.waitKind)
            assertEquals(pass.channelId, suspended.resourceId)

            pass.send(1)
            pass.send(2)
            pass.close()
            withTimeout(10.seconds) { waiter.join() }

            assertEquals(listOf(1, 2), received)
            assertTrue(session.waitsAreBalanced("waiter"), "${session.suspensionKindsOf("waiter")}")
            assertEquals(
                setOf(session.idOf("waiter")),
                session.store.all().filterIsInstance<ChannelReceiveCompleted>().map { it.coroutineId }.toSet(),
            )
        }

    @Test
    fun `a receive ended by closing the channel, caught by the coroutine, records the resume`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            val waiter =
                viz.vizLaunch("waiter") {
                    try {
                        pass.receive()
                    } catch (_: ClosedReceiveChannelException) {
                        // the kitchen closed: go home
                    }
                }
            session.awaitSuspension("waiter")

            pass.close()
            waiter.join()
            session.awaitEvent { it is CoroutineCompleted && it.label == "waiter" }

            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
        }

    @Test
    fun `a send to a closed channel fails at once, without a recorded wait`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            pass.close()
            val failure = CompletableDeferred<Throwable?>()
            viz.vizLaunch("late") { failure.complete(runCatching { pass.send(1) }.exceptionOrNull()) }.join()

            assertTrue(failure.await() is ClosedSendChannelException)
            assertEquals(emptyList(), session.suspensionKindsOf("late"))
        }

    @Test
    fun `a wait cancelled in send, receive, receiveCatching or a for loop records no resume`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            cancelledWhileWaiting("sender") { pass.send(1) }
            cancelledWhileWaiting("receiver") { pass.receive() }
            cancelledWhileWaiting("catcher") { pass.receiveCatching() }
            cancelledWhileWaiting("looper") { pass.iterator().hasNext() }
        }

    @Test
    fun `a wait in send, receive, receiveCatching or a for loop ended by a timeout records the resume`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            survivesTimeout("sender") { pass.send(1) }
            survivesTimeout("receiver") { pass.receive() }
            survivesTimeout("catcher") { pass.receiveCatching() }
            survivesTimeout("looper") { pass.iterator().hasNext() }
        }

    @Test
    fun `receiveCatching and a for loop over ready elements record no suspension`() =
        runBlocking {
            val tray = viz.vizChannel<Int>(3, "tray")
            tray.send(1)
            tray.send(2)
            tray.send(3)
            tray.close()
            viz.vizLaunch("catcher") { check(tray.receiveCatching().isSuccess) }.join()
            viz.vizLaunch("looper") { for (plate in tray) check(plate > 0) }.join()

            assertEquals(emptyList(), session.suspensionKindsOf("catcher"))
            assertEquals(emptyList(), session.suspensionKindsOf("looper"))
        }

    @Test
    fun `next after the end of a for loop fails as the channel's own iterator does`() =
        runBlocking {
            val closed = viz.vizChannel<Int>(1, "closed")
            closed.close()
            val ended = closed.iterator()
            assertFalse(ended.hasNext())
            assertFailsWith<ClosedReceiveChannelException> { ended.next() }

            val failed = viz.vizChannel<Int>(1, "failed")
            failed.close(IllegalStateException("kitchen fire"))
            val failing = failed.iterator()
            assertEquals("kitchen fire", assertFailsWith<IllegalStateException> { failing.hasNext() }.message)
            assertEquals("kitchen fire", assertFailsWith<IllegalStateException> { failing.next() }.message)

            assertFailsWith<IllegalStateException> { viz.vizChannel<Int>(1, "fresh").iterator().next() }
        }

    @Test
    fun `senders and receivers handing off in a loop record balanced waits`() =
        runBlocking {
            val belt = viz.vizChannel<Int>(Channel.RENDEZVOUS, "belt")
            val senders = (1..3).map { n -> viz.vizLaunch("sender-$n") { repeat(20) { belt.send(it) } } }
            // A rendezvous channel with no receiver yet: every sender waits before the receivers start.
            for (n in 1..3) session.awaitSuspension("sender-$n")
            val receivers = (1..2).map { n -> viz.vizLaunch("receiver-$n") { repeat(30) { belt.receive() } } }
            withTimeout(20.seconds) { (senders + receivers).joinAll() }

            for (label in (1..3).map { "sender-$it" } + (1..2).map { "receiver-$it" }) {
                assertTrue(session.waitsAreBalanced(label), "$label: ${session.suspensionKindsOf(label)}")
            }
        }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.ChannelWaitTest"`
Expected: FAIL — no `CoroutineSuspended` for any wait; the iteration test also finds receiver id `"iterator"`.

- [ ] **Step 3: Change the imports**

The snippets below are written against `origin/main`'s `InstrumentedChannel.kt` (after #287), which reports buffer
sizes through `reportedSize(...)`; keep that call wherever the buffer size is reported.


In `…/wrappers/InstrumentedChannel.kt` remove `import com.jh.proj.coroutineviz.events.channel.ChannelReceiveCompleted`
(iteration now uses the `channelReceiveCompleted` helper) and add `import com.jh.proj.coroutineviz.events.WaitKind`
and `import kotlinx.coroutines.channels.ClosedReceiveChannelException`.
Add to the class KDoc: ` * - Records a send or receive that has to wait as a suspension (spec 2026-11-05, S-7); select clauses are not recorded`.

- [ ] **Step 4: Rewrite `send`** (`trySend` stays as it is)

```kotlin
    override suspend fun send(element: T) {
        val coroutineId = currentCoroutineContext()[VizCoroutineElement]?.coroutineId ?: "unknown"
        val valueDesc = element?.toString()?.take(200) ?: "null"

        session.send(ctx.channelSendStarted(coroutineId, valueDesc))

        // Try first: only a send that no buffer slot or receiver can take now makes this coroutine wait.
        val attempt = delegate.trySend(element)
        when {
            attempt.isSuccess -> Unit
            // A closed channel fails at once, with the channel's own exception and no recorded wait.
            attempt.isClosed -> delegate.send(element)
            else -> session.recordWait(WaitKind.CHANNEL_SEND, channelId) { delegate.send(element) }
        }

        val currentSize = reportedSize(bufferCount.incrementAndGet())
        session.send(ctx.channelBufferStateChanged(currentSize))
        session.send(ctx.channelSendCompleted(coroutineId, valueDesc))

        logger.trace("Channel send completed: channelId=$channelId, value=$valueDesc")
    }
```

- [ ] **Step 5: Rewrite `receive`, `iterator`, `receiveCatching`; add `receiveOrClose`** (`tryReceive` stays as it is)

```kotlin
    override suspend fun receive(): T {
        val coroutineId = currentCoroutineContext()[VizCoroutineElement]?.coroutineId ?: "unknown"

        session.send(ctx.channelReceiveStarted(coroutineId))

        // Try first: only an empty channel makes this coroutine wait.
        val attempt = delegate.tryReceive()
        val value =
            when {
                attempt.isSuccess -> attempt.getOrThrow()
                // A closed channel fails at once, with the channel's own exception and no recorded wait.
                attempt.isClosed -> delegate.receive()
                else -> session.recordWait(WaitKind.CHANNEL_RECEIVE, channelId) { delegate.receive() }
            }

        val currentSize = reportedSize(bufferCount.decrementAndGet())
        session.send(ctx.channelBufferStateChanged(currentSize))

        val valueDesc = value?.toString()?.take(200) ?: "null"
        session.send(ctx.channelReceiveCompleted(coroutineId, valueDesc))

        logger.trace("Channel receive completed: channelId=$channelId, value=$valueDesc")
        return value
    }
```

Keep `tryReceive`, `cancel`, the deprecated `cancel(Throwable?)`, `onReceive` and `onReceiveCatching` unchanged. Replace
`iterator()` and `receiveCatching()`:

```kotlin
    /** Iterates by [receiveOrClose], so a wait for the next element is recorded like a receive. */
    override fun iterator(): ChannelIterator<T> =
        object : ChannelIterator<T> {
            // What hasNext() fetched for next(), and the coroutine that fetched it.
            private var fetched: ChannelResult<T>? = null
            private var receiverId = "unknown"

            override suspend fun hasNext(): Boolean {
                val result =
                    fetched ?: run {
                        receiverId = currentCoroutineContext()[VizCoroutineElement]?.coroutineId ?: "unknown"
                        receiveOrClose().also { fetched = it }
                    }
                // A channel closed with a cause fails the iteration with it, as the delegate's iterator does.
                result.exceptionOrNull()?.let { throw it }
                return result.isSuccess
            }

            override fun next(): T {
                val result = fetched ?: error("`hasNext` must be called before `next`")
                // After the end, fail as the delegate's iterator does: with the close cause, or ClosedReceiveChannelException.
                if (!result.isSuccess) throw result.exceptionOrNull() ?: ClosedReceiveChannelException("Channel was closed")
                fetched = null
                val value = result.getOrThrow()
                val currentSize = reportedSize(bufferCount.decrementAndGet())
                session.send(ctx.channelBufferStateChanged(currentSize))
                val valueDesc = value?.toString()?.take(200) ?: "null"
                session.send(ctx.channelReceiveCompleted(receiverId, valueDesc))
                return value
            }
        }

    override suspend fun receiveCatching(): ChannelResult<T> {
        val coroutineId = currentCoroutineContext()[VizCoroutineElement]?.coroutineId ?: "unknown"
        session.send(ctx.channelReceiveStarted(coroutineId))

        val result = receiveOrClose()

        if (result.isSuccess) {
            val currentSize = reportedSize(bufferCount.decrementAndGet())
            session.send(ctx.channelBufferStateChanged(currentSize))
            val valueDesc = result.getOrNull()?.toString()?.take(200) ?: "null"
            session.send(ctx.channelReceiveCompleted(coroutineId, valueDesc))
        } else if (result.isClosed) {
            session.send(ctx.channelClosed(result.exceptionOrNull()?.message))
        }

        return result
    }

    /** An element, or the close, at once when there is one; otherwise waits for either, recorded as a suspension. */
    private suspend fun receiveOrClose(): ChannelResult<T> {
        val attempt = delegate.tryReceive()
        return if (attempt.isSuccess || attempt.isClosed) {
            attempt
        } else {
            session.recordWait(WaitKind.CHANNEL_RECEIVE, channelId) { delegate.receiveCatching() }
        }
    }
```

- [ ] **Step 6: Run the channel tests (core and server)**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.ChannelWaitTest"`
Expected: PASS.
Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.wrappers.InstrumentedChannelTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd "$WT/backend" && ./gradlew :coroutine-viz-core:build
cd "$WT" && git add backend/coroutine-viz-core
git commit -m "fix(core): a coroutine waiting at a channel (send, receive, receiveCatching, for-loop) is recorded as suspended"
```

---

### Task 8: Failed coroutines send their stack trace

**Files:**
- Modify: `…/session/EventContext.kt:125-141` (`coroutineFailed`)
- Modify: `…/wrappers/VizScope.kt:285` and `:436` (call sites), imports
- Test: `wrappers/FailedStackTraceTest.kt` (new), `compat/BinaryCompatibilityTest.kt`

- [ ] **Step 1: Write the failing test**

Create `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/FailedStackTraceTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.session.MAX_STACK_TRACE_FRAMES
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.stackTraceLines
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The common emitter used to send every failure with an empty stack trace (spec 2026-11-05, Part 2). */
@Timeout(30)
class FailedStackTraceTest {
    @Test
    fun `a failed coroutine sends the stack trace of its exception`() =
        runBlocking {
            val session = VizSession("failed-stack")
            try {
                withTimeout(5.seconds) { VizScope(session, context = SupervisorJob()).vizLaunch("thrower") { failHere() }.join() }
                val failed = session.awaitEvent { it is CoroutineFailed && it.label == "thrower" } as CoroutineFailed

                assertTrue(failed.stackTrace.any { "failHere" in it }, failed.stackTrace.joinToString("\n"))
                assertTrue(failed.stackTrace.size <= MAX_STACK_TRACE_FRAMES)
            } finally {
                session.close()
            }
        }

    @Test
    fun `a deep stack trace is cut to the frame limit, innermost frame first`() {
        val deep = assertNotNull(runCatching { recurse(200) }.exceptionOrNull())

        assertEquals(MAX_STACK_TRACE_FRAMES, deep.stackTraceLines().size)
        assertTrue("recurse" in deep.stackTraceLines().first())
    }

    private fun failHere(): Nothing = throw IllegalStateException("boom")

    private fun recurse(depth: Int): Int = if (depth == 0) throw IllegalStateException("deep") else recurse(depth - 1) + 1
}
```

Also in `compat/BinaryCompatibilityTest.kt` add:

```kotlin
    @Test
    fun `EventContext coroutineFailed keeps its 0_3_x JVM method`() {
        val helpers = Class.forName("com.jh.proj.coroutineviz.session.EventContextKt")
        assertNotNull(helpers.getDeclaredMethod("coroutineFailed", EventContext::class.java, String::class.java, String::class.java))
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.FailedStackTraceTest"`
Expected: compilation FAILS (`Unresolved reference 'MAX_STACK_TRACE_FRAMES'`).

- [ ] **Step 3: Let `coroutineFailed` carry the trace**

In `…/session/EventContext.kt` replace `coroutineFailed` with:

```kotlin
/** At most this many frames of a failure's stack trace are sent with [CoroutineFailed]. */
internal const val MAX_STACK_TRACE_FRAMES = 50

/** The innermost [MAX_STACK_TRACE_FRAMES] frames of this throwable, one `StackTraceElement.toString()` each. */
internal fun Throwable.stackTraceLines(): List<String> = stackTrace.take(MAX_STACK_TRACE_FRAMES).map { it.toString() }

fun EventContext.coroutineFailed(
    exceptionType: String?,
    message: String?,
    stackTrace: List<String> = emptyList(),
): CoroutineFailed =
    CoroutineFailed(
        sessionId = sessionId,
        seq = nextSeq(),
        tsNanos = timestamp(),
        coroutineId = coroutineId,
        jobId = jobId,
        parentCoroutineId = parentCoroutineId,
        scopeId = scopeId,
        label = label,
        exceptionType = exceptionType,
        stackTrace = stackTrace,
        message = message,
    )

/** Kept so code compiled against 0.3.x still links (its JVM signature has no `stackTrace`). */
@Deprecated("binary compatibility with 0.3.x", level = DeprecationLevel.HIDDEN)
fun EventContext.coroutineFailed(
    exceptionType: String?,
    message: String?,
): CoroutineFailed = coroutineFailed(exceptionType, message, emptyList())
```

- [ ] **Step 4: Pass the trace where coroutines fail**

In `…/wrappers/VizScope.kt` add `import com.jh.proj.coroutineviz.session.stackTraceLines` and change both call
sites (lines 285 and 436):

```kotlin
session.send(ctx.coroutineFailed(cause::class.simpleName, cause.message, cause.stackTraceLines()))
```

(`DebugProbesEventSynthesizer` keeps calling the two-argument form; it has no throwable.)

- [ ] **Step 5: Run the tests**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.FailedStackTraceTest" --tests "com.jh.proj.coroutineviz.compat.BinaryCompatibilityTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd "$WT/backend" && ./gradlew :coroutine-viz-core:build
cd "$WT" && git add backend/coroutine-viz-core
git commit -m "fix(core): a failed coroutine's CoroutineFailed carries its stack trace (up to 50 frames)"
```

---

### Task 9: The per-coroutine timeline, the frontend types and a wait projection carry the fields

**Files:**
- Modify: `…/models/CoroutineTimeline.kt` (`TimelineEventSummary`)
- Modify: `…/session/ProjectionService.kt:406-415` (`toSummary`)
- Modify: `backend/src/main/resources/openapi/documentation.yaml` (~line 1992), `shared/api-types/openapi.json` (~line 1421), `shared/api-types/generated.ts` (~line 526)
- Modify: `shared/api-types/events.ts`, `frontend/src/types/api.ts`
- Create: `frontend/src/lib/projections/project-waits.ts`
- Test: `session/CoroutineTimelineWaitTest.kt` (new), `frontend/src/lib/event-discriminator.test.ts`, `frontend/src/lib/projections/project-waits.test.ts` (new)

- [ ] **Step 1: Write the failing backend test**

Create `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/session/CoroutineTimelineWaitTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.models.TimelineEventSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** The per-coroutine timeline response carries what a recorded wait waited for (spec 2026-11-05, S-7). */
@Timeout(30)
class CoroutineTimelineWaitTest {
    @Test
    fun `a suspension row carries the wait's kind and resource`() {
        val session = VizSession("s")
        try {
            // The bus does not replay: wait until both projections subscribe (see CoroutineTimelineOpenPhaseTest).
            runBlocking { withTimeout(5.seconds) { session.eventBus.subscriptionCount.first { it >= 2 } } }
            session.send(CoroutineCreated("s", 1, 1_000, "c", "job-c", null, "io", "order-7", createdAtEpochMs = 1))
            session.send(CoroutineStarted("s", 2, 2_000, "c", "job-c", null, "io", "order-7"))
            session.send(
                CoroutineSuspended(
                    "s", 3, 3_000, "c", "job-c", null, "io", "order-7",
                    reason = "mutex", waitKind = WaitKind.MUTEX, resourceId = "mutex-4",
                ),
            )
            session.send(CoroutineSuspended("s", 4, 4_000, "c", "job-c", null, "io", "order-7", reason = "delay", durationMillis = 5))

            // The projection builds its node asynchronously: poll the timeline response itself.
            val deadline = System.currentTimeMillis() + 5_000
            var rows = emptyList<TimelineEventSummary>()
            while (System.currentTimeMillis() < deadline && rows.size < 2) {
                rows = session.projectionService.getCoroutineTimeline("c")?.events.orEmpty().filter { it.kind == "coroutine.suspended" }
                if (rows.size < 2) Thread.sleep(10)
            }

            assertEquals(2, rows.size)

            assertEquals(WaitKind.MUTEX, rows[0].waitKind)
            assertEquals("mutex-4", rows[0].resourceId)
            assertNull(rows[1].waitKind)
            assertNull(rows[1].resourceId)
        } finally {
            session.close()
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.session.CoroutineTimelineWaitTest"`
Expected: compilation FAILS (`Unresolved reference 'waitKind'` on `TimelineEventSummary`).

- [ ] **Step 3: Add the fields to the timeline rows**

In `…/models/CoroutineTimeline.kt` add `import com.jh.proj.coroutineviz.events.WaitKind` and two properties at the
end of `TimelineEventSummary`:

```kotlin
    // Source code location where a coroutine suspended (only populated for suspend rows)
    val suspensionPoint: SuspensionPoint? = null,
    // What a mutex, semaphore or channel wrapper waited for, and which one (suspend rows only; spec 2026-11-05, S-7)
    val waitKind: WaitKind? = null,
    val resourceId: String? = null,
)
```

In `…/session/ProjectionService.kt`, `toSummary`, the `CoroutineSuspended` branch becomes:

```kotlin
            is CoroutineSuspended ->
                TimelineEventSummary(
                    seq = event.seq,
                    tsNanos = event.tsNanos,
                    kind = "coroutine.suspended",
                    reason = event.reason,
                    suspensionPoint = event.suspensionPoint,
                    waitKind = event.waitKind,
                    resourceId = event.resourceId,
                )
```

- [ ] **Step 4: Run the backend test**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.session.CoroutineTimelineWaitTest" --tests "com.jh.proj.coroutineviz.session.CoroutineTimelineOpenPhaseTest"`
Expected: PASS.

- [ ] **Step 5: Update the OpenAPI contract (three files, by hand as in #232)**

`backend/src/main/resources/openapi/documentation.yaml`, in `TimelineEventSummary`, after
`suspensionPoint: $ref: "#/components/schemas/SuspensionPoint"`:

```yaml
        waitKind:
          type: string
          nullable: true
          enum: [MUTEX, SEMAPHORE, CHANNEL_SEND, CHANNEL_RECEIVE]
          description: "On a suspension recorded by a mutex, semaphore or channel wrapper: what the coroutine waited for."
        resourceId:
          type: string
          nullable: true
          description: "On such a suspension: the mutexId, semaphoreId or channelId waited for."
```

`shared/api-types/openapi.json`, in `TimelineEventSummary.properties`, replace

```json
          "suspensionPoint": {
            "$ref": "#/components/schemas/SuspensionPoint"
          }
        }
      },
      "ThreadEvent": {
```

with

```json
          "suspensionPoint": {
            "$ref": "#/components/schemas/SuspensionPoint"
          },
          "waitKind": {
            "type": "string",
            "nullable": true,
            "enum": [
              "MUTEX",
              "SEMAPHORE",
              "CHANNEL_SEND",
              "CHANNEL_RECEIVE"
            ],
            "description": "On a suspension recorded by a mutex, semaphore or channel wrapper: what the coroutine waited for."
          },
          "resourceId": {
            "type": "string",
            "nullable": true,
            "description": "On such a suspension: the mutexId, semaphoreId or channelId waited for."
          }
        }
      },
      "ThreadEvent": {
```

`shared/api-types/generated.ts`, in `TimelineEventSummary`, after
`suspensionPoint?: components["schemas"]["SuspensionPoint"];`:

```ts
            /**
             * @description On a suspension recorded by a mutex, semaphore or channel wrapper: what the coroutine waited for.
             * @enum {string|null}
             */
            waitKind?: "MUTEX" | "SEMAPHORE" | "CHANNEL_SEND" | "CHANNEL_RECEIVE" | null;
            /** @description On such a suspension: the mutexId, semaphoreId or channelId waited for. */
            resourceId?: string | null;
```

- [ ] **Step 6: Write the failing frontend test**

In `frontend/src/lib/event-discriminator.test.ts` add `import type { CoroutineSuspendedEvent } from '@/types/api'`
and, inside `describe('normalizeEvent — raw backend {type} payload', …)`:

```ts
  it('keeps what a recorded wait waited for (S-7)', () => {
    const event = normalizeEvent({
      type: 'CoroutineSuspended',
      sessionId: 's',
      seq: 3,
      tsNanos: 3,
      coroutineId: 'c',
      jobId: 'j',
      parentCoroutineId: null,
      scopeId: 'sc',
      label: 'order-7',
      reason: 'mutex',
      waitKind: 'MUTEX',
      resourceId: 'mutex-4',
    }) as CoroutineSuspendedEvent

    expect(event.kind).toBe('coroutine.suspended')
    expect(event.waitKind).toBe('MUTEX')
    expect(event.resourceId).toBe('mutex-4')
  })
```

Run: `cd "$WT/frontend" && pnpm exec tsc --noEmit`
Expected: FAIL — `Property 'waitKind' does not exist on type 'CoroutineSuspendedEvent'`.

- [ ] **Step 7: Add the types**

`shared/api-types/events.ts`: before `export interface CoroutineSuspended`, add the type, and extend three interfaces:

```ts
/** What a coroutine waited for when a mutex, semaphore or channel wrapper recorded the wait (spec 2026-11-05, S-7). */
export type WaitKind = "MUTEX" | "SEMAPHORE" | "CHANNEL_SEND" | "CHANNEL_RECEIVE";

export interface CoroutineSuspended extends BaseCoroutineEvent {
  kind: "CoroutineSuspended";
  reason: string;
  durationMillis: number | null;
  suspensionPoint: SuspensionPoint | null;
  /** Absent on other suspensions and on events from older producers. */
  waitKind?: WaitKind | null;
  /** The mutexId, semaphoreId or channelId waited for. */
  resourceId?: string | null;
}
```

In `SemaphorePermitReleased` add after `holdDurationNanos: number;`:

```ts
  /** True when the releasing coroutine held no permit: whose permit came back is unknown. Absent on older events. */
  holderUnknown?: boolean;
```

In `SemaphoreStateChanged` add after `waitingLabels: (string | null)[];`:

```ts
  /** Permits each of `activeHolders` holds; absent on older events. */
  activeHolderPermits?: number[];
  /** Listed permits that other coroutines released: their holder is unknown. */
  unknownHolderPermits?: number;
```

`frontend/src/types/api.ts`: add `WaitKind,` after `CoroutineFailed,` in both the `import type {…} from
'@vizcor/api-types'` list and the `export type {…}` list (the line `  CoroutineFailed,` followed by
`  // Job events` appears in both; edit both). Then extend:

```ts
// Enhanced Coroutine Suspended event with suspension point
export interface CoroutineSuspendedEvent extends CoroutineEvent {
  kind: 'coroutine.suspended'
  suspensionPoint?: SuspensionPoint | null
  /** What a mutex, semaphore or channel wrapper waited for; absent on other suspensions and older events (S-7). */
  waitKind?: WaitKind | null
  /** The mutexId, semaphoreId or channelId waited for. */
  resourceId?: string | null
}
```

and in `TimelineEvent` after `suspensionPoint?: SuspensionPoint | null`:

```ts
  waitKind?: WaitKind | null
  resourceId?: string | null
```

- [ ] **Step 8: A projection of each coroutine's current wait**

`projectCoroutines` must keep deep-equalling the server snapshot, so the wait facts get their own pure reducer.
Create `frontend/src/lib/projections/project-waits.test.ts`:

```ts
/**
 * Unit tests for projectWaits — what each coroutine waits for right now, from the suspensions the mutex,
 * semaphore and channel wrappers record with `waitKind` and `resourceId` (spec 2026-11-05, S-7).
 */
import { describe, it, expect } from 'vitest'
import type { VizEvent } from '@/types/api'
import { projectWaits } from './project-waits'

let seq = 0

function event(kind: string, coroutineId: string, extra: Record<string, unknown> = {}): VizEvent {
  seq += 1
  return {
    sessionId: 'session-1',
    seq,
    tsNanos: seq * 1_000,
    kind,
    coroutineId,
    jobId: `job-${coroutineId}`,
    parentCoroutineId: null,
    scopeId: 'scope-1',
    label: coroutineId,
    ...extra,
  } as unknown as VizEvent
}

const waitForLock = (id: string) => event('coroutine.suspended', id, { reason: 'mutex', waitKind: 'MUTEX', resourceId: 'mutex-4' })

describe('projectWaits', () => {
  it('a wrapper suspension is a wait with its kind, resource and start', () => {
    const suspended = waitForLock('order-7')

    expect(projectWaits([event('coroutine.started', 'order-7'), suspended]).get('order-7')).toEqual({
      waitKind: 'MUTEX',
      resourceId: 'mutex-4',
      sinceSeq: suspended.seq,
      sinceTsNanos: suspended.tsNanos,
    })
  })

  it('a resume ends the wait', () => {
    expect(projectWaits([waitForLock('order-7'), event('coroutine.resumed', 'order-7')]).has('order-7')).toBe(false)
  })

  it('cancellation ends the wait', () => {
    expect(projectWaits([waitForLock('order-7'), event('coroutine.cancelled', 'order-7')]).has('order-7')).toBe(false)
  })

  it('a suspension without waitKind (a delay, or an older producer) is no wrapper wait', () => {
    const delay = event('coroutine.suspended', 'order-7', { reason: 'delay', durationMillis: 500 })
    const older = event('coroutine.suspended', 'order-8', { reason: 'mutex' })

    expect(projectWaits([delay, older]).size).toBe(0)
  })

  it('keeps each coroutine apart, and a later wait replaces an earlier one', () => {
    const later = event('coroutine.suspended', 'order-7', { reason: 'semaphore', waitKind: 'SEMAPHORE', resourceId: 'semaphore-2' })
    const waits = projectWaits([waitForLock('order-7'), waitForLock('order-8'), event('coroutine.resumed', 'order-7'), later])

    expect(waits.get('order-7')?.waitKind).toBe('SEMAPHORE')
    expect(waits.get('order-8')?.waitKind).toBe('MUTEX')
  })
})
```

Run: `cd "$WT/frontend" && pnpm test -- src/lib/projections/project-waits.test.ts`
Expected: FAIL — `Failed to resolve import "./project-waits"`.

Create `frontend/src/lib/projections/project-waits.ts`:

```ts
/**
 * Pure client-side reducer: VizEvent[] -> what each coroutine waits for right now (spec 2026-11-05, S-7).
 *
 * A wait starts at a `coroutine.suspended` event that a mutex, semaphore or channel wrapper recorded with
 * `waitKind` (and `resourceId`), and ends at the coroutine's next resume, suspension or terminal event. A
 * suspension without `waitKind` (a delay, an await, or an event from an older producer) is not a wrapper wait.
 *
 * Kept apart from projectCoroutines, whose nodes must deep-equal the server snapshot. Framework-free and
 * side-effect-free, so it is safe to call inside a useMemo over the replay `visibleEvents` slice.
 */

import type { CoroutineSuspendedEvent, VizEvent, WaitKind } from '@/types/api'

export interface WaitFact {
  waitKind: WaitKind
  /** The mutexId, semaphoreId or channelId waited for; null when the producer did not say. */
  resourceId: string | null
  /** seq and tsNanos of the suspension that started the wait. */
  sinceSeq: number
  sinceTsNanos: number
}

/** Event kinds that end a coroutine's current wait (a suspension may start the next one). */
const ENDS_WAIT: ReadonlySet<string> = new Set([
  'coroutine.suspended',
  'coroutine.resumed',
  'coroutine.completed',
  'coroutine.cancelled',
  'coroutine.failed',
])

/**
 * @param events - VizEvents in seq order (typically the replay `visibleEvents` slice)
 * @returns coroutineId -> its current wait, for coroutines waiting on a wrapper now
 */
export function projectWaits(events: VizEvent[]): Map<string, WaitFact> {
  const waits = new Map<string, WaitFact>()

  for (const event of events) {
    if (!ENDS_WAIT.has(event.kind) || !('coroutineId' in event)) continue
    waits.delete(event.coroutineId)

    if (event.kind === 'coroutine.suspended') {
      const suspended = event as CoroutineSuspendedEvent
      if (suspended.waitKind) {
        waits.set(suspended.coroutineId, {
          waitKind: suspended.waitKind,
          resourceId: suspended.resourceId ?? null,
          sinceSeq: suspended.seq,
          sinceTsNanos: suspended.tsNanos,
        })
      }
    }
  }

  return waits
}
```

- [ ] **Step 9: Run the frontend checks**

Run: `cd "$WT/frontend" && pnpm exec tsc --noEmit && pnpm test -- src/lib/event-discriminator.test.ts src/lib/projections/project-waits.test.ts && pnpm lint`
Expected: all PASS.

- [ ] **Step 10: Commit**

```bash
cd "$WT/backend" && ./gradlew :coroutine-viz-core:build
cd "$WT" && git add backend shared frontend/src
git commit -m "feat: the coroutine timeline API, the frontend types and projectWaits carry waitKind and resourceId"
```

---

### Task 10: The demos, the pacing tests, the changelog, the full gate

**Files:**
- Create: `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/WaitsAsSuspensionDemoTest.kt`
- Modify: `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/ChannelsDemoPacingTest.kt` (on `main` since #287)
- Modify: `CHANGELOG.md`

- [ ] **Step 1: The Combined demo shows its waits**

Create `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/WaitsAsSuspensionDemoTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.SemaphoreCreated
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * In the Combined demo (E-commerce orders) the orders share a payment gateway with two permits. Each order that
 * waits for it is shown as a suspension naming the gateway, and goes on afterwards.
 *
 * The demo runs on one virtual-time thread: every order reaches the gateway before any payment (a `delay`) ends,
 * so orders 1 and 2 take both permits and the other orders that reach payment must wait. Contention is certain,
 * not a matter of timing.
 */
class WaitsAsSuspensionDemoTest {
    private fun VizEvent.endsCoroutine(id: String) =
        (this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed) &&
            (this as CoroutineEvent).coroutineId == id

    @Test
    fun `an order waiting for the payment gateway is suspended, then resumed before it ends`() =
        runTest {
            val session = VizSession("combined-waits")
            try {
                SyncScenarios.ecommerceOrderProcessing(VizScope(session, context = StandardTestDispatcher(testScheduler)))

                val events = session.store.all()
                val gateway = events.filterIsInstance<SemaphoreCreated>().single { it.semaphoreLabel == "payment-gateway" }.semaphoreId
                val waits = events.filterIsInstance<CoroutineSuspended>().filter { it.waitKind == WaitKind.SEMAPHORE }
                assertTrue(waits.size >= 2, "only ${waits.size} orders waited for the payment gateway")
                for (wait in waits) {
                    assertTrue(wait.resourceId == gateway, "${wait.label} waited for ${wait.resourceId}")
                    val end = assertNotNull(events.firstOrNull { it.endsCoroutine(wait.coroutineId) }, "${wait.label} never ended")
                    assertTrue(
                        events.any { it is CoroutineResumed && it.coroutineId == wait.coroutineId && it.seq > wait.seq && it.seq < end.seq },
                        "${wait.label} stays suspended until it ends",
                    )
                }
            } finally {
                session.close()
            }
        }
}
```

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.scenarios.WaitsAsSuspensionDemoTest"`
Expected: PASS. (Inventory: orders 1–5 reach payment, 6 and 7 lack stock, so at least three orders wait. If the
Combined demo has been paced meanwhile and its semaphore renamed, use the new label.)

- [ ] **Step 2: The Channels demos show their waits**

In `ChannelsDemoPacingTest.kt` add imports `com.jh.proj.coroutineviz.events.WaitKind` and
`com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed` (`CoroutineEvent`, `CoroutineCompleted`,
`CoroutineCancelled`, `CoroutineFailed` and `CoroutineSuspended` are imported already) and:

```kotlin
    @ParameterizedTest(name = "{0}")
    @MethodSource("channelIds")
    fun `a coroutine waiting at a channel is shown suspended until it goes on`(id: String) {
        val events = run(id).events
        val waits =
            events.filterIsInstance<CoroutineSuspended>().filter {
                it.waitKind == WaitKind.CHANNEL_SEND || it.waitKind == WaitKind.CHANNEL_RECEIVE
            }

        assertTrue(waits.isNotEmpty(), "$id records no wait at a channel")
        for (wait in waits) {
            val next =
                events.firstOrNull {
                    it.seq > wait.seq &&
                        (it is CoroutineResumed || it is CoroutineCompleted || it is CoroutineCancelled || it is CoroutineFailed) &&
                        (it as CoroutineEvent).coroutineId == wait.coroutineId
                }
            assertTrue(next is CoroutineResumed || next is CoroutineCancelled, "$id: ${wait.label} stays suspended at the channel")
        }
    }
```

- [ ] **Step 3: Re-run the pacing tests**

Run: `./gradlew :test --tests "*DemoPacingTest"`
Expected: PASS for Basics, Channels and (if merged) Flow. The step test filters `reason == "delay"`, so channel
waits do not count as steps; the "separate moments" test now sees more events — if it fails, report the numbers
instead of loosening the test.

- [ ] **Step 4: Re-measure the Channels and Combined demos with the pacing audit**

The pacing audit is the script the earlier demo work used: it runs demos through the real API the way the UI
does, 7 s between new sessions (the server allows 10 per minute), and reports per demo the duration, the
half-second moments that carry events and the busiest moment's share. It and the earlier results are in
`$PACING` (see "Before you start"; if they are gone, stop and ask the user — do not rewrite the audit). With the
branch's backend and frontend running as in Step 7 (ports 18391 / 13391, `DEMO_TIME_SCALE=1`):

```bash
python3 -I "$PACING/demo_pacing_audit.py" http://localhost:13391 "$PACING/waits-pacing.json" \
  channel-rendezvous,channel-buffered,channel-fan-out,sync/combined/ecommerce
```

Compare `duration_s`, `moments` and `busiest_share` with the earlier runs: Channels in `channels-pacing.json`
(rendezvous 17.1 s / 12 / 0.17, buffered 23.0 / 17 / 0.13, fan-out 12.7 / 21 / 0.14) and Combined in
`demo-pacing-2.json` (0.5 s / 2 / 0.89 — not paced yet; its pacing comes with the Semaphore + Combined group).
Durations should not change; moments and busiest share may rise slightly from the added Suspended/Resumed
events. Put the before/after table in the PR description; if a Channels demo now breaks the 10–30 s or the
"no single burst" rule, report it instead of changing the demo here.

- [ ] **Step 5: Changelog**

In `CHANGELOG.md`, under `## [Unreleased]` → `### Fixed`, add at the top:

```markdown
- A coroutine waiting for a `VizMutex`, a `VizSemaphore` permit, or a channel send or receive (`send`, `receive`, `receiveCatching`, `for (x in channel)`) is shown Suspended instead of Active. The wrappers try first and, only when they must wait, send `CoroutineSuspended` with the new optional `waitKind` and `resourceId`, then `CoroutineResumed` when the wait ends, also when a closed channel or a timeout ends it. `select` clauses are not covered yet. The coroutine timeline API (`GET /api/sessions/{id}/coroutines/{coroutineId}/timeline`) carries both fields
- `VizSemaphore` counts the permits each coroutine holds. A permit released by a coroutine that holds none is reported with `holderUnknown` instead of being charged to an arbitrary holder, and `SemaphoreStateChanged` gains `activeHolderPermits` and `unknownHolderPermits`
- `VizSemaphore.release` and `tryAcquire` name the calling coroutine; they could not find it before. A `for` loop over a channel names its coroutine instead of `iterator`
- `CoroutineFailed` carries the exception's stack trace (up to 50 frames); it was always empty
```

- [ ] **Step 6: The full gate**

```bash
cd "$WT/backend" && ./gradlew ktlintCheck detekt test checkBytecode build
cd "$WT/frontend" && pnpm exec tsc --noEmit && pnpm lint && pnpm test
```

Expected: all green. `checkBytecode` guards that core still targets JVM 17.

- [ ] **Step 7: Browser check on free ports**

Start the backend on `18391` and the frontend on `13391` (never 3000, 5173, 8080, 13001, 18081, 13291, 18291):

```bash
(cd "$WT/backend" && PORT=18391 CORS_ALLOWED_ORIGINS=http://localhost:13391 DEMO_TIME_SCALE=1 ./gradlew :run -q) &
(cd "$WT/frontend" && VITE_PROXY_TARGET=http://localhost:18391 pnpm dev --port 13391 --strictPort) &
```

Open `http://localhost:13391`, run a Channels demo and the E-commerce demo. While a coroutine waits at the pass
or for the payment gateway, its node reads **Suspended**, not Active; it returns to Active when it goes on.
Stop both servers afterwards.

- [ ] **Step 8: Commit**

```bash
cd "$WT" && git add backend/src/test CHANGELOG.md
git commit -m "test: the Combined and Channels demos show their waits as suspensions; changelog"
```

Then open the PR (title: "Waits for a mutex, a semaphore or a channel show as Suspended"), run the Codex review
rounds until clean, and merge only when the user says "go".
