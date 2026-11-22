# PR #8 Review: Flow Decorator — Step-by-Step Fix Guide

## Summary

PR #8 adds Flow visualization (cold Flow, SharedFlow, StateFlow) to VizScope.
**3 files changed:** `InstrumentedFlow.kt`, `VizScope.kt`, `flowBackpressureScenario.kt` (new).

**Problem:** The branch was forked before the event package refactoring and EventContext DSL
improvements landed on `main`. As a result, VizScope.kt on the flow branch **regresses** the
main branch improvements. The new flow code also has compile errors in the example scenarios.

**Strategy:** Don't merge the branch as-is. Instead, apply ONLY the new flow additions on top
of current `main`.

---

## Issue 1: VizScope.kt — Regression of EventContext DSL

### What happened
Main uses clean EventContext extension functions:
```kotlin
// main (GOOD)
session.send(ctx.coroutineBodyCompleted())
session.send(ctx.coroutineCancelled(cause.message ?: "CancellationException"))
session.send(ctx.coroutineFailed(cause::class.simpleName, cause.message))
```

Flow branch reverts to verbose raw event construction:
```kotlin
// flow branch (BAD — regresses main)
session.sent(
    CoroutineBodyCompleted(
        sessionId = session.sessionId,
        seq = session.nextSeq(),
        tsNanos = System.nanoTime(),
        coroutineId = coroutineId,
        jobId = jobId,
        parentCoroutineId = parentCoroutineId,
        scopeId = scopeId,
        label = label
    )
)
```

### What to do
**Do NOT merge VizScope.kt from the flow branch.** Instead, keep main's VizScope.kt and
add ONLY the new flow methods (see Issue 5 below).

---

## Issue 2: VizScope.kt — Broken imports (package refactoring)

### What happened
Main moved events into subpackages: `events.coroutine.*`, `events.flow.*`, `events.deferred.*`, `events.dispatcher.*`.
The flow branch still uses old flat imports:

| Flow branch import (WRONG) | Correct import on main |
|---|---|
| `events.CoroutineBodyCompleted` | `events.coroutine.CoroutineBodyCompleted` |
| `events.CoroutineCancelled` | `events.coroutine.CoroutineCancelled` |
| `events.CoroutineCompleted` | `events.coroutine.CoroutineCompleted` |
| `events.CoroutineFailed` | `events.coroutine.CoroutineFailed` |
| `events.CoroutineSuspended` | `events.coroutine.CoroutineSuspended` |
| `events.DeferredValueAvailable` | `events.deferred.DeferredValueAvailable` |
| `events.FlowCreated` | `events.flow.FlowCreated` |
| `events.ThreadAssigned` | `events.dispatcher.ThreadAssigned` |
| `events.InstrumentedDeferred` | `wrappers.InstrumentedDeferred` |

### What to do
These broken imports are another reason NOT to merge VizScope.kt from the flow branch.
When adding flow methods on top of main, use correct imports.

---

## Issue 3: VizScope.kt — Lost nested scope logic

### What happened
Main has correct nesting support:
```kotlin
// main (GOOD) — children launch in parent's scope for proper structured concurrency
val targetScope = currentCoroutineContext()[VizCoroutineElement]?.let {
    CoroutineScope(currentCoroutineContext())
} ?: this

val job = targetScope.launch(context + vizElement) { ... }
```

Flow branch always launches on VizScope:
```kotlin
// flow branch (BAD) — nested vizLaunch doesn't respect parent scope
val job = this.launch(context + vizElement) { ... }
```

### Why it matters
Without `targetScope`, nested `vizLaunch` calls don't create proper parent-child
Job relationships. Cancelling a parent won't propagate to children correctly.

### What to do
Already fixed by keeping main's VizScope.kt.

---

## Issue 4: VizScope.kt — Lost improved invokeOnCompletion

### What happened
Main has better failure detection in `invokeOnCompletion`:
```kotlin
// main (GOOD) — distinguishes failure vs cancellation properly
when {
    cause == null -> session.send(ctx.coroutineCompleted())

    cause !is CancellationException &&
    cause.message?.contains(ctx.label ?: "unknown") == true -> {
        session.send(ctx.coroutineFailed(cause::class.simpleName, cause.message))
    }

    cause is CancellationException || job.isCancelled -> {
        session.send(ctx.coroutineCancelled(cause.message ?: "CancellationException"))
    }

    else -> throw IllegalArgumentException("It is not correct state")
}
```

Flow branch has simpler logic that may misclassify errors:
```kotlin
// flow branch (WORSE)
when {
    cause == null -> { /* completed */ }
    cause is CancellationException -> { /* cancelled */ }
    else -> { /* failed */ }
}
```

### What to do
Already fixed by keeping main's VizScope.kt.

---

## Issue 5: VizScope.kt — New flow methods need cleanup

### What to add to main's VizScope.kt

The flow branch adds 4 new methods. They need cleanup before adding:

#### 5a. Remove unnecessary try-catch

**Current (flow branch):**
```kotlin
val coroutineId = try {
    coroutineContext[VizCoroutineElement]?.coroutineId
} catch (e: Exception) {
    null
} ?: scopeId
```

**Fixed:**
```kotlin
val coroutineId = coroutineContext[VizCoroutineElement]?.coroutineId ?: scopeId
```

**Why:** Accessing `coroutineContext[VizCoroutineElement]` never throws. The try-catch adds
unnecessary noise.

#### 5b. SharedFlow/StateFlow vizWrap returns unwrapped delegate

**Current (flow branch):**
```kotlin
fun <T> MutableSharedFlow<T>.vizWrap(label: String? = null): MutableSharedFlow<T> {
    // ... emits FlowCreated event ...
    return this  // ← Returns UNWRAPPED! No runtime tracking!
}
```

**Problem:** Only a `FlowCreated` event is emitted. No emissions, subscriptions, or value
changes will be tracked at runtime.

**Two options:**

**Option A (Honest):** Document the limitation clearly:
```kotlin
/**
 * Registers a SharedFlow for visualization. Currently only tracks creation.
 * Collection is tracked when using .collect() through the Flow<T> interface.
 * Individual emissions and subscriber changes are NOT tracked.
 */
fun <T> MutableSharedFlow<T>.vizWrap(label: String? = null): MutableSharedFlow<T> {
    val flowId = "sharedflow-${session.nextSeq()}"
    val coroutineId = coroutineContext[VizCoroutineElement]?.coroutineId ?: scopeId

    session.eventBus.send(FlowCreated(
        sessionId = session.sessionId,
        seq = session.nextSeq(),
        tsNanos = System.nanoTime(),
        coroutineId = coroutineId,
        flowId = flowId,
        flowType = "SharedFlow",
        label = label,
        scopeId = scopeId
    ))

    return this
}
```

**Option B (Full tracking, future PR):** Create `InstrumentedSharedFlow<T>` wrapper that
delegates `MutableSharedFlow<T>` and tracks `emit()`/`tryEmit()` and subscriptions.
This is more complex and should be a separate PR.

**Recommendation:** Use Option A now, plan Option B for later.

#### 5c. Use EventContext pattern for FlowCreated events

To stay consistent with main's style, create a `flowCreated()` extension:

**Add to `EventContext.kt` (or a new `FlowEventContext.kt`):**
```kotlin
fun EventContext.flowCreated(
    flowId: String,
    flowType: String,
    label: String? = null
): FlowCreated = FlowCreated(
    sessionId = sessionId,
    seq = nextSeq(),
    tsNanos = timestamp(),
    coroutineId = coroutineId,
    flowId = flowId,
    flowType = flowType,
    label = label,
    scopeId = scopeId
)
```

Then in VizScope:
```kotlin
fun <T> vizFlow(label: String? = null, block: suspend FlowCollector<T>.() -> Unit): Flow<T> {
    val flowId = "flow-${session.nextSeq()}"
    val coroutineId = coroutineContext[VizCoroutineElement]?.coroutineId ?: scopeId
    val ctx = EventContext(session, coroutineId, "n/a", null, scopeId, label)

    session.eventBus.send(ctx.flowCreated(flowId, "Cold", label))

    return InstrumentedFlow(flow(block), session, flowId, "Cold", label)
}
```

This is optional but keeps codebase consistency.

---

## Issue 6: InstrumentedFlow.kt — Bad logger import

### What happened
Flow branch imports logger from an example class:
```kotlin
import com.jh.proj.coroutineviz.session.VizEventMain.Companion.logger
```

But InstrumentedFlow already has its own companion logger:
```kotlin
companion object {
    private val logger = LoggerFactory.getLogger(InstrumentedFlow::class.java)
}
```

### What to do
On main, this is already correct — the import doesn't exist. The flow branch diff for
InstrumentedFlow.kt only changes one line (return type). **Just apply the return type change.**

**In `InstrumentedFlow.kt`, line ~134:**

Change:
```kotlin
fun <T> Flow<T>.vizInstrumented(
    session: VizSession,
    flowType: String = "Cold",
    label: String? = null
): Flow<T> {
```

To:
```kotlin
fun <T> Flow<T>.vizInstrumented(
    session: VizSession,
    flowType: String = "Cold",
    label: String? = null
): InstrumentedFlow<T> {
```

**Why:** Returning `InstrumentedFlow<T>` preserves the concrete type, allowing chaining
of `viz*` extension functions if we add them in the future.

---

## Issue 7: flowBackpressureScenario.kt — Compile errors

### 7a. SharedFlow loses emit() after wrapping

**Current:**
```kotlin
val sharedFlow = MutableSharedFlow<String>(replay = 1).vizInstrumented(session, "event-bus")
// ...
sharedFlow.emit(message)  // COMPILE ERROR: InstrumentedFlow has no emit()
```

**Why:** `vizInstrumented()` wraps `MutableSharedFlow` into `InstrumentedFlow<T>` which only
implements `Flow<T>` (read-only). The `emit()` method from `MutableSharedFlow` is lost.

**Fix:**
```kotlin
val rawSharedFlow = MutableSharedFlow<String>(replay = 1)

// For emission tracking (using scope's vizWrap to get FlowCreated event)
scope.vizLaunch(label = "producer") {
    vizDelay(100)
    repeat(5) { i ->
        val message = "Event-$i"
        rawSharedFlow.emit(message)  // emit on the raw SharedFlow
        vizDelay(200)
    }
}

// For collection tracking (wrap as Flow when collecting)
scope.vizLaunch(label = "fast-collector") {
    rawSharedFlow
        .vizInstrumented(session, label = "fast-collector-flow")
        .collect { value ->
            vizDelay(100)
        }
}
```

**Pattern:** Keep `MutableSharedFlow` unwrapped for emitting, wrap with `vizInstrumented`
at the collection point.

### 7b. StateFlow vizWrap called outside VizScope

**Current:**
```kotlin
suspend fun stateFlowScenario(session: VizSession) = coroutineScope {
    val scope = VizScope(session, scopeId = "state-flow")
    val stateFlow = MutableStateFlow("Initial").vizWrap("app-state")  // COMPILE ERROR
```

**Why:** `MutableStateFlow<T>.vizWrap()` is a member extension function inside `VizScope` class.
It can only be called within a VizScope receiver (e.g., inside `vizLaunch { }`).

**Fix:**
```kotlin
suspend fun stateFlowScenario(session: VizSession) = coroutineScope {
    val scope = VizScope(session, scopeId = "state-flow")
    val stateFlow = MutableStateFlow("Initial")

    // Register as flow creation (outside scope, use event bus directly)
    session.eventBus.send(FlowCreated(
        sessionId = session.sessionId,
        seq = session.nextSeq(),
        tsNanos = System.nanoTime(),
        coroutineId = scope.scopeId,
        flowId = "stateflow-${session.nextSeq()}",
        flowType = "StateFlow",
        label = "app-state",
        scopeId = scope.scopeId
    ))

    // OR: Use vizWrap inside vizLaunch:
    scope.vizLaunch(label = "state-observer") {
        stateFlow
            .vizWrap("state-observer-flow")  // Now valid - inside VizScope
            .collect { value -> vizDelay(100) }
    }

    scope.vizLaunch(label = "state-updater") {
        vizDelay(200)
        listOf("Loading", "Success", "Error", "Idle").forEach { state ->
            stateFlow.value = state
            vizDelay(300)
        }
    }
}
```

### 7c. vizWrap() on collector side wraps same flow twice

Each `.vizWrap("...")` creates a NEW `InstrumentedFlow` with a new `flowId`. In the SharedFlow
example, both collector1 and collector2 wrap the same underlying SharedFlow with different IDs.
This is actually fine for visualization (shows two separate collection streams), but the comment
"Creates an instrumented SharedFlow" is misleading — it creates an instrumented cold Flow
view of the SharedFlow.

**Fix:** Update the comment:
```kotlin
/**
 * Wraps a SharedFlow for collection tracking. Each call creates a new
 * InstrumentedFlow view, so different collectors get separate flow IDs
 * in the visualization.
 */
```

---

## Issue 8: Commented-out / dead code in flow branch VizScope

Flow branch has:
- `bodyTerminalEventEmitted` AtomicBoolean in `vizAsync` — declared but never `set(true)`
- `toVizJob()` call commented out
- `Dispatchers` import unused
- `java.util.concurrent.atomic.*` wildcard import

**What to do:** These won't be an issue since we're keeping main's VizScope.kt.

---

## Step-by-Step Implementation Plan

### Step 1: Create a new branch from main
```bash
git checkout main
git pull origin main
git checkout -b feature/flow-clean
```

### Step 2: Apply the InstrumentedFlow return type change
**File:** `src/main/kotlin/.../wrappers/InstrumentedFlow.kt`

Change line ~134: `): Flow<T> {` → `): InstrumentedFlow<T> {`

This is the ONLY change needed in this file.

### Step 3: Add flow imports to VizScope.kt
**File:** `src/main/kotlin/.../wrappers/VizScope.kt`

Add these imports at the top:
```kotlin
import com.jh.proj.coroutineviz.events.flow.FlowCreated
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
```

### Step 4: Add flow methods to VizScope.kt
Add these methods at the end of the `VizScope` class (before the closing `}`):

```kotlin
    // ========================================================================
    // Flow Builders
    // ========================================================================

    /**
     * Creates an instrumented cold Flow that emits FlowCreated on creation
     * and tracks collection lifecycle via InstrumentedFlow.
     */
    fun <T> vizFlow(
        label: String? = null,
        block: suspend FlowCollector<T>.() -> Unit
    ): Flow<T> {
        val flowId = "flow-${session.nextSeq()}"
        val coroutineId = coroutineContext[VizCoroutineElement]?.coroutineId ?: scopeId

        session.eventBus.send(
            FlowCreated(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                coroutineId = coroutineId,
                flowId = flowId,
                flowType = "Cold",
                label = label,
                scopeId = scopeId
            )
        )

        return InstrumentedFlow(flow(block), session, flowId, "Cold", label)
    }

    /**
     * Wraps an existing Flow with instrumentation for collection tracking.
     *
     * Usage: `someFlow.vizWrap("my-label")`
     */
    fun <T> Flow<T>.vizWrap(label: String? = null): Flow<T> {
        val flowId = "flow-${session.nextSeq()}"
        val coroutineId = coroutineContext[VizCoroutineElement]?.coroutineId ?: scopeId

        session.eventBus.send(
            FlowCreated(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                coroutineId = coroutineId,
                flowId = flowId,
                flowType = "Cold",
                label = label,
                scopeId = scopeId
            )
        )

        return InstrumentedFlow(this, session, flowId, "Cold", label)
    }

    /**
     * Registers a SharedFlow for visualization. Currently only tracks creation.
     * Collection is tracked when collectors use .collect() through the Flow<T> interface.
     * Individual emissions and subscriber lifecycle changes are NOT yet tracked.
     */
    fun <T> MutableSharedFlow<T>.vizWrap(label: String? = null): MutableSharedFlow<T> {
        val flowId = "sharedflow-${session.nextSeq()}"
        val coroutineId = coroutineContext[VizCoroutineElement]?.coroutineId ?: scopeId

        session.eventBus.send(
            FlowCreated(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                coroutineId = coroutineId,
                flowId = flowId,
                flowType = "SharedFlow",
                label = label,
                scopeId = scopeId
            )
        )

        return this
    }

    /**
     * Registers a StateFlow for visualization. Currently only tracks creation.
     * Collection is tracked when observers use .collect() through the Flow<T> interface.
     * Value changes are NOT yet tracked.
     */
    fun <T> MutableStateFlow<T>.vizWrap(label: String? = null): MutableStateFlow<T> {
        val flowId = "stateflow-${session.nextSeq()}"
        val coroutineId = coroutineContext[VizCoroutineElement]?.coroutineId ?: scopeId

        session.eventBus.send(
            FlowCreated(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                coroutineId = coroutineId,
                flowId = flowId,
                flowType = "StateFlow",
                label = label,
                scopeId = scopeId
            )
        )

        return this
    }
```

### Step 5: Add the flow backpressure scenario (fixed)
**File:** `src/main/kotlin/.../examples/flowBackpressureScenario.kt` (new)

```kotlin
package com.jh.proj.coroutineviz.examples

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import org.slf4j.LoggerFactory

/**
 * Demonstrates Flow backpressure with fast producer and slow consumer.
 */
suspend fun flowBackpressureScenario(session: VizSession) = coroutineScope {
    val logger = LoggerFactory.getLogger("FlowBackpressureScenario")
    logger.info("Starting Flow Backpressure Scenario for session: ${session.sessionId}")

    val scope = VizScope(session, scopeId = "flow-backpressure")

    scope.vizLaunch(label = "flow-demo") {
        val fastFlow = vizFlow<Int>(label = "fast-producer") {
            repeat(10) { i ->
                logger.info("Emitting value: $i")
                emit(i)
                vizDelay(100)
            }
            logger.info("Producer finished")
        }

        logger.info("Collecting with NO buffer (backpressure applies)")
        fastFlow.collect { value ->
            logger.info("Collecting value: $value")
            vizDelay(500)
            logger.info("Processed value: $value")
        }
    }.join()

    logger.info("Flow Backpressure Scenario Complete")
}

/**
 * Demonstrates buffered Flow to show the difference.
 */
suspend fun flowBufferedScenario(session: VizSession) = coroutineScope {
    val logger = LoggerFactory.getLogger("FlowBufferedScenario")
    logger.info("Starting Flow Buffered Scenario")

    val scope = VizScope(session, scopeId = "flow-buffered")

    scope.vizLaunch(label = "buffered-flow-demo") {
        val fastFlow = vizFlow<Int>(label = "fast-producer-2") {
            repeat(10) { i ->
                logger.info("Emitting value: $i")
                emit(i)
                vizDelay(100)
            }
            logger.info("Producer finished")
        }

        logger.info("Collecting with buffer(3)")
        fastFlow
            .buffer(capacity = 3)
            .vizWrap(label = "buffered-flow")
            .collect { value ->
                logger.info("Collecting value: $value")
                vizDelay(500)
                logger.info("Processed value: $value")
            }
    }.join()

    logger.info("Flow Buffered Scenario Complete")
}

/**
 * Demonstrates SharedFlow with multiple collectors.
 *
 * Pattern: Keep MutableSharedFlow unwrapped for emitting.
 * Wrap with vizWrap() at the collection point for tracking.
 */
suspend fun sharedFlowScenario(session: VizSession) = coroutineScope {
    val logger = LoggerFactory.getLogger("SharedFlowScenario")
    logger.info("Starting SharedFlow Scenario")

    val scope = VizScope(session, scopeId = "shared-flow")
    val sharedFlow = MutableSharedFlow<String>(replay = 1)

    // Fast collector — wraps at collection point
    val collector1 = scope.vizLaunch(label = "fast-collector") {
        sharedFlow
            .vizWrap("fast-collector-flow")
            .collect { value ->
                logger.info("Fast collector received: $value")
                vizDelay(100)
            }
    }

    // Slow collector — wraps at collection point
    val collector2 = scope.vizLaunch(label = "slow-collector") {
        sharedFlow
            .vizWrap("slow-collector-flow")
            .collect { value ->
                logger.info("Slow collector received: $value")
                vizDelay(400)
            }
    }

    // Producer — emits on raw MutableSharedFlow (not wrapped)
    val producer = scope.vizLaunch(label = "producer") {
        vizDelay(100)  // Let collectors start
        repeat(5) { i ->
            val message = "Event-$i"
            logger.info("Broadcasting: $message")
            sharedFlow.emit(message)
            vizDelay(200)
        }
    }

    producer.join()
    scope.vizDelay(1000)  // Let collectors finish
    collector1.cancel()
    collector2.cancel()

    logger.info("SharedFlow Scenario Complete")
}

/**
 * Demonstrates StateFlow with observer and updater.
 *
 * Pattern: Keep MutableStateFlow unwrapped for value updates.
 * Wrap with vizWrap() inside vizLaunch for collection tracking.
 */
suspend fun stateFlowScenario(session: VizSession) = coroutineScope {
    val logger = LoggerFactory.getLogger("StateFlowScenario")
    logger.info("Starting StateFlow Scenario")

    val scope = VizScope(session, scopeId = "state-flow")
    val stateFlow = MutableStateFlow("Initial")

    // Observer — wraps inside vizLaunch where VizScope methods are available
    val observer = scope.vizLaunch(label = "state-observer") {
        stateFlow
            .vizWrap("state-observer-flow")
            .collect { value ->
                logger.info("State changed to: $value")
                vizDelay(100)
            }
    }

    // Updater — writes to raw MutableStateFlow
    val updater = scope.vizLaunch(label = "state-updater") {
        vizDelay(200)
        listOf("Loading", "Success", "Error", "Idle").forEach { state ->
            logger.info("Updating state to: $state")
            stateFlow.value = state
            vizDelay(300)
        }
    }

    updater.join()
    scope.vizDelay(500)
    observer.cancel()

    logger.info("StateFlow Scenario Complete")
}
```

### Step 6: Verify it compiles
```bash
cd backend
./gradlew compileKotlin
```

### Step 7: Test manually
Run the flow scenarios and verify events are emitted correctly:
- FlowCreated appears for each vizFlow/vizWrap call
- FlowCollectionStarted/FlowValueEmitted/FlowCollectionCompleted appear during collection
- FlowCollectionCancelled appears when collector is cancelled

### Step 8: Commit and create clean PR
```bash
git add -A
git commit -m "Add Flow visualization: vizFlow, vizWrap for Flow/SharedFlow/StateFlow"
git push -u origin feature/flow-clean
```

Close old PR #8 and reference it in the new PR.

---

## Checklist

- [ ] New branch from `main` (not from flow branch)
- [ ] `InstrumentedFlow.kt`: return type `InstrumentedFlow<T>` in `vizInstrumented()`
- [ ] `VizScope.kt`: add 4 flow methods with correct imports
- [ ] `VizScope.kt`: no try-catch around `coroutineContext[VizCoroutineElement]`
- [ ] `VizScope.kt`: no EventContext regression (keep main's version)
- [ ] `VizScope.kt`: no `targetScope` regression (keep main's nested scope logic)
- [ ] `flowBackpressureScenario.kt`: SharedFlow emitter uses raw `MutableSharedFlow`
- [ ] `flowBackpressureScenario.kt`: StateFlow `.vizWrap()` called inside `vizLaunch`
- [ ] `flowBackpressureScenario.kt`: no `vizInstrumented` on SharedFlow for emit path
- [ ] Compiles with `./gradlew compileKotlin`
- [ ] All 4 example scenarios run without errors

---

## Future Improvements (separate PRs)

1. **InstrumentedSharedFlow** — full wrapper tracking `emit()`, `tryEmit()`, subscriber lifecycle
2. **InstrumentedStateFlow** — full wrapper tracking `value` changes, `compareAndSet()`
3. **Operator chain tracking** — `vizMap`, `vizFilter`, `vizFlatMap`, etc. with FlowOperatorApplied events
4. **Flow EventContext** — dedicated FlowEventContext helper like the coroutine EventContext DSL
5. **Unit tests** — collection lifecycle, cancellation, nested flows, backpressure behavior
