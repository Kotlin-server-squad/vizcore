# Session Triage PR 1 — Core Health Derivation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put the definition of "a problem" into `coroutine-viz-core`: wire types for origin, suspension provenance and the poll watermark, a durable per-coroutine summary kept by every `VizSession`, round anchoring, and a pure `SessionHealthDerivation` that turns summaries into a `SessionHealth`.

**Architecture:** Every coroutine event that a `VizSession` appends also goes through one `SummaryTable.apply(event)`, which keeps one `CoroutineSummary` per coroutine (C-2) plus an incremental subtree index that decides when a running root becomes a container (`promotedAtSeq`: kept while it runs, cleared when it finishes unless it grew after promotion). `RoundAnchoring` derives round ids from the summaries; `SessionHealthDerivation.derive(HealthInput)` derives incidents, observations, counts and the strip state. Nothing in this PR is served over HTTP yet; PR 2 (backend) exposes it.

**Tech Stack:** Kotlin 2.4, kotlinx.serialization (`appJson`), JUnit 5 + `kotlin.test`, Gradle (`backend/gradlew`), ktlint, detekt.

**Spec:** `docs/superpowers/specs/2026-10-21-session-triage-design.md` — sections "Definitions", "C-1", "C-2", "Delivery" row 1, "Testing" (Core).

**Plans for the other PRs** (written after this one merges, because they build on its actual types): PR 2 backend, PR 3 SDK, PR 4 and PR 5 frontend.

---

## Context for the implementer

- Work in a fresh worktree from `origin/main` (1.2.0 is merged there: #230, #231, #232, #234, #246):
  `git -C ~/Documents/workspace-vizcore/vizcore worktree add -b feat/health-core ../vizcore-health-core origin/main`.
  All Gradle commands below run from `backend/` in that worktree; all `git` commands run from the worktree
  root (from `backend/`, use `git -C ..`).
- The spec and this plan are committed only on the local branch `docs/session-triage-design` (not on
  `origin/main`).
  **First step in the new worktree:** bring them in, so the PR carries its design and Task 15's review can
  read them: `git cherry-pick origin/main..docs/session-triage-design` (it touches only
  `docs/superpowers/`, so it applies cleanly).
- Module: `backend/coroutine-viz-core`. Main package root:
  `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/`; tests mirror it under
  `src/test/kotlin/com/jh/proj/coroutineviz/`.
- **New package** `com.jh.proj.coroutineviz.health` holds everything new except the wire types, which live
  in `events/` next to the other event types.
- Events: every coroutine event implements `CoroutineEvent` (`events/VizEvent.kt`) with `sessionId`,
  `seq`, `tsNanos`, `coroutineId`, `jobId`, `parentCoroutineId`, `scopeId`, `label`. `tsNanos` is the
  producer's monotonic clock: for an attached app it is the app's `System.nanoTime()`, which the spec
  calls "the app clock".
- The DebugProbes synthesizer (`session/source/debugprobes/DebugProbesEventSynthesizer.kt`) emits
  `ThreadAssigned` **after** the `CoroutineStarted`/`CoroutineResumed` of the same poll, so "a thread
  assignment in the current run" is "a `ThreadAssigned` whose `seq` is greater than the latest
  start/resume `seq`".
- `appJson` (`VizEventSerializersModule.kt`) has `ignoreUnknownKeys = true` and `encodeDefaults = true`:
  new optional fields are safe for older readers. A **new event type** is not (an older backend cannot
  decode `PollWatermark`); PR 3 deals with that on the SDK side. Do not change that here.
- `ProblemDerivation` and `RoundGrouping` in `intellij-plugin/` are the reference for the rules ported
  here (`isRealException`, container-root promotion). Do **not** modify the plugin in this PR
  (sub-project 5 adopts the core derivation).
- Gradle gotcha: `--rerun` applies only to the task right before it. To force a re-run of tests, put it
  right after `:coroutine-viz-core:test`.
- Test commands: `./gradlew :coroutine-viz-core:test --tests "<FQN>"`.
- Lint: `coroutine-viz-core` applies **neither** ktlint nor detekt (only the backend app project does), so
  CI does not lint the new files. Still follow the repo style from `backend/.editorconfig`
  (`ktlint_official`, 4-space indent, max line length 140, trailing commas, one-parameter-per-line
  signatures as in the code below) and keep methods short; the code in this plan already does.
- Time in tests: use `sec(n)` / `ms(n)` helpers from `EventScript` (nanoseconds).

### Rules this PR encodes (from the spec)

| Rule | Where |
|---|---|
| Origin `APP` / `VIZCORE` / `UNKNOWN`; `null` on the wire = SDK without origin support | `CoroutineOrigin`, `SessionHealthDerivation.effectiveOrigin` |
| Demo sessions: every coroutine is `APP`; possibly blocked `NOT_EVALUATED(DEMO)`; `asOf` = last event | derivation |
| Session origin `UNKNOWN` when no coroutine reported `APP` or `VIZCORE`: every coroutine `UNKNOWN`, nothing hidden | derivation |
| `FAILED` = terminal failure that is not a `CancellationException` (an unknown type counts as a failure; only a known cancellation type does not) | `HealthThresholds.isCancellation`, `SummaryUpdater` |
| `POSSIBLY_BLOCKED` = running, and watermark − latest start/resume > 10 s (app clock) | `PossiblyBlockedIncidents` |
| Round anchors = genuine roots + direct children of promoted containers; container itself a singleton round; promotion sticky (`promotedAtSeq`) while the root runs; cleared when the root finishes only if it got no new direct child after promotion (see "Spec amendment" below) | `SubtreeIndex`, `SummaryTable`, `RoundAnchoring` |
| Failed round = all `FAILED` of one round; cancellations recorded after its first failure are context | `FailureIncidents` |
| Ungrouped failure = `FAILED` whose ancestry cannot be resolved | `FailureIncidents` |
| Observations (never problems): long-lived (> leak threshold, default 30 s), long-suspended (> 30 s); an active age ends at the later of the latest watermark and the coroutine's own last event (without a watermark, at its own last event) | `HealthParts.observations` |
| Suspension provenance `REAL_FRAME` / `CREATION_FALLBACK` / `STALE`; a frame-less suspension after a known one → `STALE` | `SummaryUpdater` |
| Event-loop clue only from a thread assigned in the current run; prefixes from the spec | `PossiblyBlockedIncidents`, `HealthThresholds` |
| Reduction: over 50,000 full summaries, finished non-failed first, then failed, become stubs; capacity 100,000, then only counted | `SummaryTable` |
| Overflow → `NOT_EVALUATED(OVERFLOW)`, "at least"; lost events / uncertain delivery → `NOT_EVALUATED(EVENTS_DROPPED)`, "at least" | derivation |
| Lists capped at 50 with totals; retained transitions capped at 20 with a gap flag | `Capped`, `PossiblyBlockedIncidents` |

### Spec amendment: promotion is cleared when the root finishes

The spec says a root's `promotedAtSeq` is "durable, never cleared". Taken literally, that splits ordinary
requests: a request root with three or more parallel children qualifies as a container the moment half of
those children finish, and in structured concurrency children always finish before their parent. The
dashboard request of sketch board 1 (four parts) would become five rounds and two failure incidents. The
plugin's per-pass recomputation does not have this problem, because a finished root is never a container.

Clearing it on *every* terminal would break the case promotion exists for: a long-lived app root (the
DebugProbes "one root that never completes" shape) can still get a terminal event, on a graceful shutdown
captured while the SDK polls or when an app scope is cancelled, and all its request rounds would merge
back into one.

This plan therefore keeps promotion sticky **while the root runs** (new active children never demote it)
and, when the root finishes, **clears it only if no direct child was created after `promotedAtSeq`**. A
request's children all exist before it qualifies, so a request is unsplit when it finishes; a server
container keeps getting new children after promotion, so it stays a container. Trade-off: a request that
launches children in waves after qualifying stays split. A root re-keys at most twice. The replay fold uses
the same table, so it behaves identically. The spec's "Rounds" paragraph is amended in the same branch.

**Amended again during implementation (Task 6 review):** keeping promotion for every root that grew after
promotion splits a request that starts its children one after another (`repeat(n) { async { … }.await() }`):
it qualifies at its third child, grows with the fourth, and a failure would show as two incidents. A
finished root therefore keeps its promotion only if it grew after promotion **and** was cancelled (how an app
scope or server shuts down); a request that completes or fails is one round again. `SummaryTable.onTerminal`
and its tests implement this; the spec's "Rounds" paragraph says the same.

### Left to PR 2 on purpose

- **Persistence (C-4).** `VizSession.rehydrateFromStore` rebuilds summaries from the stored events in this
  PR. That is a stopgap: after trimming it loses identities, stubs and failures whose events are gone,
  which C-2 forbids. PR 2 persists the summary table and restores it instead of replaying; it also lets
  the replay fold seed durable stubs for coroutines whose creation event was trimmed.
- **Lock time.** `readSummaries` runs its block under the send lock. PR 2 copies what it needs under the
  lock and derives outside it, so a once-per-second derivation over 100,000 summaries never stalls ingest.
- **Session origin.** `HealthInput.reportedOrigin` lets PR 2/3 state the session's origin from the ingest
  handshake; without it the derivation infers it from the coroutines.
- **Orphans.** A coroutine whose parent chain reaches an untracked parent has no round (spec "Ungrouped
  failure"), unlike the plugin, which anchors the top of a dangling chain. Its failures are ungrouped
  incidents; PR 5's round picker must offer a way to reach non-failed orphans (e.g. "All rounds" in List).
- **Health payload fields.** Spec C-3 lists `asOfWatermark` and `retention` in the health payload; core's
  `SessionHealth` has neither. PR 2 adds them where it serves the payload.

### Left to PR 3 (SDK) on purpose

- Coroutines launched through the wrappers in an attached session send no origin yet, so they count as
  `UNKNOWN` until the SDK sets `APP`.
- `PollWatermark` is not in `StructuralClassifier`'s protected set, so the SDK's sampler and overflow buffer
  may thin or drop it; PR 3 protects it when the SDK starts sending watermarks.

### File map

Create (main, under `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/`):

| File | Responsibility |
|---|---|
| `events/CoroutineOrigin.kt` | `CoroutineOrigin` enum (wire) |
| `events/SuspensionProvenance.kt` | `SuspensionProvenance` enum (wire) |
| `events/PollWatermark.kt` | `PollWatermark` event (wire) |
| `health/HealthThresholds.kt` | thresholds, caps, event-loop prefixes, `isCancellation` |
| `health/CoroutineSummary.kt` | `CoroutineSummary`, `Transition`, `TransitionKind`, `ThreadObservation`, `FailureInfo` |
| `health/Transitions.kt` | event → `Transition` mapping, per-coroutine transition list |
| `health/SummaryUpdater.kt` | pure per-summary updates |
| `health/SubtreeIndex.kt` | incremental open-subtree counts and container qualification |
| `health/SummaryTable.kt` | `SummaryView` + the mutable table: apply, promotion, capacity, reduction |
| `health/RoundAnchoring.kt` | anchors, `anchorOf`, `rounds()`, `RoundSummary` |
| `health/HealthModel.kt` | serializable output types (`SessionHealth`, incidents, …) and `HealthInput` |
| `health/FailureIncidents.kt` | failed-round and ungrouped-failure incidents |
| `health/PossiblyBlockedIncidents.kt` | possibly blocked incidents |
| `health/SessionHealthDerivation.kt` | assembly: origin, asOf, evaluation, incidents, state |
| `health/HealthParts.kt` | observations, origin counts, vizcore connection, problem rounds |
| `health/SummaryFold.kt` | replay fold to a sequence number |

Modify:

| File | Change |
|---|---|
| `events/coroutine/CoroutineCreated.kt` | add `origin: CoroutineOrigin? = null` |
| `events/coroutine/CoroutineSuspended.kt` | add `provenance: SuspensionProvenance? = null` |
| `VizEventSerializersModule.kt` | register `PollWatermark` |
| `session/EventContext.kt` | `coroutineSuspended(..., provenance)` parameter |
| `wrappers/VizScope.kt` | `vizDelay` passes `REAL_FRAME` |
| `wrappers/InstrumentedDeferred.kt` | await suspension passes `REAL_FRAME` |
| `events/SuspensionPoint.kt` | `capturedProvenance()` extension |
| `session/VizSession.kt` | own a `SummaryTable` (unless `keepSummaries = false`), apply on append and rehydrate, `readSummaries` |

Modify (`backend/coroutine-viz-client/src/main/kotlin/com/jh/proj/coroutineviz/client/`):

| File | Change |
|---|---|
| `VizcoreClient.kt` | the SDK's in-app session passes `keepSummaries = false` (it never reads them) |

Tests (under `src/test/kotlin/com/jh/proj/coroutineviz/`): `events/HealthWireTypesTest.kt`,
`events/VizEventSerializersModuleTest.kt` (modify), `wrappers/SuspensionProvenanceTest.kt`,
`health/EventScript.kt` (helper), `health/SummaryUpdaterTest.kt`, `health/SubtreeIndexTest.kt`,
`health/SummaryTableTest.kt`, `health/RoundAnchoringTest.kt`, `health/FailureIncidentsTest.kt`,
`health/PossiblyBlockedTest.kt`, `health/SessionHealthDerivationTest.kt`, `health/SummaryFoldTest.kt`,
`health/SketchBoardsTest.kt`, `session/VizSessionSummariesTest.kt`.

---

### Task 1: Wire types — origin, provenance, poll watermark

**Files:**
- Create: `events/CoroutineOrigin.kt`, `events/SuspensionProvenance.kt`, `events/PollWatermark.kt`
- Modify: `events/coroutine/CoroutineCreated.kt`, `events/coroutine/CoroutineSuspended.kt`, `VizEventSerializersModule.kt`
- Test: `events/HealthWireTypesTest.kt`, `events/VizEventSerializersModuleTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/events/HealthWireTypesTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.events

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthWireTypesTest {
    private val serializer = PolymorphicSerializer(VizEvent::class)

    private fun roundTrip(event: VizEvent): VizEvent = appJson.decodeFromString(serializer, appJson.encodeToString(serializer, event))

    @Test
    fun `PollWatermark round-trips through the shared Json`() {
        val watermark =
            PollWatermark(
                sessionId = "s",
                seq = 7,
                tsNanos = 1_000,
                pollEpochMs = 1_792_000_000_000,
                lostEvents = 3,
                deliveryUncertain = true,
                final = true,
            )
        assertEquals(watermark, roundTrip(watermark))
    }

    @Test
    fun `origin and provenance round-trip`() {
        val created =
            CoroutineCreated(
                sessionId = "s",
                seq = 1,
                tsNanos = 5,
                coroutineId = "c",
                jobId = "j",
                parentCoroutineId = null,
                scopeId = "sc",
                label = "cio-from-nio-reader",
                origin = CoroutineOrigin.VIZCORE,
            )
        val suspended =
            CoroutineSuspended(
                sessionId = "s",
                seq = 2,
                tsNanos = 6,
                coroutineId = "c",
                jobId = "j",
                parentCoroutineId = null,
                scopeId = "sc",
                label = null,
                reason = "suspended",
                suspensionPoint = SuspensionPoint("main", "App.kt", 3, "creation"),
                provenance = SuspensionProvenance.CREATION_FALLBACK,
            )
        assertEquals(created, roundTrip(created))
        assertEquals(suspended, roundTrip(suspended))
    }

    @Test
    fun `events from an older SDK decode with null origin and provenance`() {
        val created =
            """{"type":"CoroutineCreated","sessionId":"s","seq":1,"tsNanos":5,"coroutineId":"c","jobId":"j",""" +
                """"parentCoroutineId":null,"scopeId":"sc","label":null}"""
        val suspended =
            """{"type":"CoroutineSuspended","sessionId":"s","seq":2,"tsNanos":6,"coroutineId":"c","jobId":"j",""" +
                """"parentCoroutineId":null,"scopeId":"sc","label":null,"reason":"delay"}"""
        assertNull((appJson.decodeFromString(serializer, created) as CoroutineCreated).origin)
        assertNull((appJson.decodeFromString(serializer, suspended) as CoroutineSuspended).provenance)
    }
}
```

In `src/test/kotlin/com/jh/proj/coroutineviz/events/VizEventSerializersModuleTest.kt`, add
`PollWatermark::class` to `knownSubclasses` (after `WaitingForChildren::class`, under a comment
`// health (1)`; no import needed, the test is in package `events`), and change the size assertion
`assertEquals(66, knownSubclasses.size, …)` to `67`. Also update the "66" in the KDoc of
`VizEventSerializersModule.kt` and in the comment in
`backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionEventsIntegrationTest.kt` (~line 37) if they
state the count (`git grep -n "66" -- backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/VizEventSerializersModule.kt backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionEventsIntegrationTest.kt`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.events.HealthWireTypesTest" --tests "com.jh.proj.coroutineviz.events.VizEventSerializersModuleTest"`
Expected: compilation FAILS (`PollWatermark`, `CoroutineOrigin`, `SuspensionProvenance`, `origin`, `provenance` unresolved).

- [ ] **Step 3: Implement**

`events/CoroutineOrigin.kt`:

```kotlin
package com.jh.proj.coroutineviz.events

import kotlinx.serialization.Serializable

/**
 * Who launched a coroutine (spec 2026-10-21, "Origin"). The SDK sends it on
 * [com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated]; `null` on the wire
 * means an SDK that predates origin support.
 */
@Serializable
enum class CoroutineOrigin { APP, VIZCORE, UNKNOWN }
```

`events/SuspensionProvenance.kt`:

```kotlin
package com.jh.proj.coroutineviz.events

import kotlinx.serialization.Serializable

/**
 * Where a suspension point came from (spec 2026-10-21, "Suspension-point provenance").
 * [REAL_FRAME]: a suspension frame was read. [CREATION_FALLBACK]: no usable frame, the creation
 * frame was sent instead. [STALE]: set by the summary when a newer suspension arrived without a
 * frame and the older location was kept. Only [REAL_FRAME] is shown as "last suspension point".
 */
@Serializable
enum class SuspensionProvenance { REAL_FRAME, CREATION_FALLBACK, STALE }
```

`events/PollWatermark.kt`:

```kotlin
package com.jh.proj.coroutineviz.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The SDK's poll watermark (spec 2026-10-21, C-7): sent after a poll's events, at most once per
 * second, also when the poll produced none. [tsNanos] is the poll time on the app clock,
 * [pollEpochMs] the app's wall-clock time of that poll. [lostEvents] is the SDK's cumulative count of
 * events lost before sending; [deliveryUncertain] marks a connection that ended while events may have
 * been in flight. [final] marks the close message after `stop()` drained: no producer can add events
 * after it, so its [lostEvents] is final.
 */
@Serializable
@SerialName("PollWatermark")
data class PollWatermark(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val pollEpochMs: Long,
    val lostEvents: Long = 0,
    val deliveryUncertain: Boolean = false,
    val final: Boolean = false,
) : VizEvent {
    override val kind: String get() = "PollWatermark"
}
```

`events/coroutine/CoroutineCreated.kt` — add the last constructor parameter (after `creationPoint`) and
the import `com.jh.proj.coroutineviz.events.CoroutineOrigin`:

```kotlin
    val creationPoint: SuspensionPoint? = null,
    /** Who launched it; `null` from an SDK without origin support (spec 2026-10-21). */
    val origin: CoroutineOrigin? = null,
```

`events/coroutine/CoroutineSuspended.kt` — add after `suspensionPoint`, with the import
`com.jh.proj.coroutineviz.events.SuspensionProvenance`:

```kotlin
    val suspensionPoint: SuspensionPoint? = null,
    /** Where [suspensionPoint] came from; `null` when the producer did not say. */
    val provenance: SuspensionProvenance? = null,
```

`VizEventSerializersModule.kt` — after `subclass(WaitingForChildren::class)`:

```kotlin
            // health (1) — in PollWatermark.kt
            subclass(PollWatermark::class)
```

(the file already imports `com.jh.proj.coroutineviz.events.*`, so no new import is needed).

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command from Step 2.
Expected: PASS (both classes).

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionEventsIntegrationTest.kt
git commit -m "core: wire types for coroutine origin, suspension provenance and the poll watermark"
```

---

### Task 2: In-process wrappers mark their suspension frames as real

Demo sessions use the wrappers; their suspension frames come from `SuspensionPoint.capture`, which is a
real frame. Without this, the page would show "suspension location unavailable" for every demo. The
DebugProbes synthesizer keeps sending `null` provenance here; PR 3 sets it.

**Files:**
- Modify: `session/EventContext.kt` (function `coroutineSuspended`, ~line 146), `wrappers/VizScope.kt` (~line 497), `wrappers/InstrumentedDeferred.kt` (~line 86), `events/SuspensionPoint.kt` (new `capturedProvenance` extension)
- Test: `wrappers/SuspensionProvenanceTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/wrappers/SuspensionProvenanceTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class SuspensionProvenanceTest {
    @Test
    fun `vizDelay marks its suspension frame as real`(): Unit =
        runBlocking {
            val session = VizSession("provenance")
            val viz = VizScope(session)
            viz.vizLaunch("worker") { vizDelay(1) }.join()

            val suspended = session.store.all().filterIsInstance<CoroutineSuspended>()
            assertTrue(suspended.isNotEmpty(), "vizDelay must emit a suspension")
            assertTrue(
                suspended.all { it.provenance == SuspensionProvenance.REAL_FRAME },
                "wrapper frames come from SuspensionPoint.capture: $suspended",
            )
            viz.cancelAndJoin()
            session.close()
        }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.wrappers.SuspensionProvenanceTest"`
Expected: FAIL — provenance is `null`.

- [ ] **Step 3: Implement**

`session/EventContext.kt` — `coroutineSuspended` gains a parameter and passes it on (add the import
`com.jh.proj.coroutineviz.events.SuspensionProvenance`):

```kotlin
fun EventContext.coroutineSuspended(
    reason: String,
    durationMillis: Long? = null,
    suspensionPoint: SuspensionPoint? = null,
    provenance: SuspensionProvenance? = null,
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
    )
```

`wrappers/VizScope.kt` in `vizDelay`:

```kotlin
                ctx.coroutineSuspended(
                    reason = "delay",
                    durationMillis = timeMillis,
                    suspensionPoint = suspensionPoint,
                    provenance = suspensionPoint.capturedProvenance(),
                ),
```

`wrappers/InstrumentedDeferred.kt` — in the `CoroutineSuspended(...)` constructor call add
`provenance = suspensionPoint.capturedProvenance(),` after `suspensionPoint = suspensionPoint,`.

`events/SuspensionPoint.kt` — `capture` returns a placeholder (`function = "unknown"`, no file) when it finds
no frame; only a frame it actually read is `REAL_FRAME`. Add, below the class:

```kotlin
/** Provenance of a point made by [SuspensionPoint.capture]: a frame it read, or `null` for its "unknown" placeholder. */
fun SuspensionPoint.capturedProvenance(): SuspensionProvenance? =
    if (function == "unknown" && fileName == null) null else SuspensionProvenance.REAL_FRAME
```

(`SuspensionPoint.kt` is in package `events`, like `SuspensionProvenance`, so it needs no import. Import
`com.jh.proj.coroutineviz.events.capturedProvenance` in `VizScope.kt` and `InstrumentedDeferred.kt`.)

Add to `SuspensionProvenanceTest`:

```kotlin
    @Test
    fun `capture's unknown placeholder is not a real frame`() {
        assertEquals(null, SuspensionPoint(function = "unknown", reason = "delay").capturedProvenance())
        assertEquals(
            SuspensionProvenance.REAL_FRAME,
            SuspensionPoint(function = "load", fileName = "Repo.kt", lineNumber = 3, reason = "delay").capturedProvenance(),
        )
    }
```

with the imports `com.jh.proj.coroutineviz.events.SuspensionPoint`,
`com.jh.proj.coroutineviz.events.capturedProvenance` and `kotlin.test.assertEquals`.

- [ ] **Step 4: Run it to verify it passes**

Run: the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: wrapper suspensions carry REAL_FRAME provenance"
```

---

### Task 3: Thresholds, summary types, transitions, and the test script helper

**Files:**
- Create: `health/HealthThresholds.kt`, `health/CoroutineSummary.kt`, `health/Transitions.kt`
- Test helper: `health/EventScript.kt` (test source set)
- Test: `health/TransitionsTest.kt`

- [ ] **Step 1: Write the test helper and the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/health/EventScript.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.WaitingForChildren
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned

fun sec(n: Long): Long = n * 1_000_000_000L

fun ms(n: Long): Long = n * 1_000_000L

/** Builds an ordered event list with increasing seqs, the way a session would record it. */
class EventScript(
    private val sessionId: String = "health-test",
) {
    private var seq = 0L
    private val parents = HashMap<String, String?>()
    val events = mutableListOf<VizEvent>()
    val lastSeq: Long get() = seq

    fun created(
        id: String,
        at: Long,
        parent: String? = null,
        label: String? = id,
        origin: CoroutineOrigin? = CoroutineOrigin.APP,
        creationPoint: SuspensionPoint? = null,
    ) {
        parents[id] = parent
        events +=
            CoroutineCreated(
                sessionId = sessionId,
                seq = ++seq,
                tsNanos = at,
                coroutineId = id,
                jobId = "job-$id",
                parentCoroutineId = parent,
                scopeId = "scope",
                label = label,
                creationPoint = creationPoint,
                origin = origin,
            )
    }

    fun started(
        id: String,
        at: Long,
    ) {
        events += CoroutineStarted(sessionId, ++seq, at, id, "job-$id", parents[id], "scope", id)
    }

    fun resumed(
        id: String,
        at: Long,
    ) {
        events += CoroutineResumed(sessionId, ++seq, at, id, "job-$id", parents[id], "scope", id)
    }

    fun suspended(
        id: String,
        at: Long,
        point: SuspensionPoint? = SuspensionPoint(function = "work", fileName = "App.kt", lineNumber = 10, reason = "delay"),
        provenance: SuspensionProvenance? = SuspensionProvenance.REAL_FRAME,
    ) {
        events +=
            CoroutineSuspended(
                sessionId = sessionId,
                seq = ++seq,
                tsNanos = at,
                coroutineId = id,
                jobId = "job-$id",
                parentCoroutineId = parents[id],
                scopeId = "scope",
                label = id,
                reason = "delay",
                suspensionPoint = point,
                provenance = provenance,
            )
    }

    fun bodyCompleted(
        id: String,
        at: Long,
    ) {
        events += CoroutineBodyCompleted(sessionId, ++seq, at, id, "job-$id", parents[id], "scope", id)
    }

    fun completed(
        id: String,
        at: Long,
    ) {
        events += CoroutineCompleted(sessionId, ++seq, at, id, "job-$id", parents[id], "scope", id)
    }

    fun cancelled(
        id: String,
        at: Long,
    ) {
        events += CoroutineCancelled(sessionId, ++seq, at, id, "job-$id", parents[id], "scope", id, cause = null)
    }

    fun failed(
        id: String,
        at: Long,
        type: String? = "java.lang.IllegalStateException",
        message: String? = "boom",
    ) {
        events +=
            CoroutineFailed(
                sessionId = sessionId,
                seq = ++seq,
                tsNanos = at,
                coroutineId = id,
                jobId = "job-$id",
                parentCoroutineId = parents[id],
                scopeId = "scope",
                label = id,
                exceptionType = type,
                message = message,
                stackTrace = emptyList(),
            )
    }

    fun thread(
        id: String,
        at: Long,
        name: String,
        threadId: Long = 1,
        dispatcher: String? = "Dispatchers.Default",
    ) {
        events +=
            ThreadAssigned(
                sessionId = sessionId,
                seq = ++seq,
                tsNanos = at,
                coroutineId = id,
                jobId = "job-$id",
                parentCoroutineId = parents[id],
                scopeId = "scope",
                label = id,
                threadId = threadId,
                threadName = name,
                dispatcherName = dispatcher,
            )
    }

    fun waitingForChildren(
        id: String,
        at: Long,
    ) {
        events +=
            WaitingForChildren(
                sessionId = sessionId,
                seq = ++seq,
                tsNanos = at,
                coroutineId = id,
                jobId = "job-$id",
                parentCoroutineId = parents[id],
                scopeId = "scope",
                label = id,
                activeChildrenCount = 1,
                activeChildrenIds = listOf("child-of-$id"),
            )
    }

    fun watermark(
        at: Long,
        epochMs: Long = 1_792_000_000_000,
        lost: Long = 0,
        uncertain: Boolean = false,
    ) {
        events += PollWatermark(sessionId, ++seq, at, epochMs, lostEvents = lost, deliveryUncertain = uncertain)
    }

    fun table(
        fullSummaryTarget: Int = HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET,
        trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
    ): SummaryTable = SummaryTable(fullSummaryTarget, trackedCapacity).also { table -> events.forEach(table::apply) }
}
```

`src/test/kotlin/com/jh/proj/coroutineviz/health/TransitionsTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TransitionsTest {
    @Test
    fun `maps lifecycle events to transitions and skips others`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.started("a", at = 2)
        script.thread("a", at = 3, name = "main")
        script.suspended("a", at = 4)
        script.resumed("a", at = 5)
        script.bodyCompleted("a", at = 6)
        script.completed("a", at = 7)
        script.created("b", at = 8)

        val transitions = Transitions.forCoroutine(script.events, "a")

        assertEquals(
            listOf(
                TransitionKind.CREATED,
                TransitionKind.STARTED,
                TransitionKind.SUSPENDED,
                TransitionKind.RESUMED,
                TransitionKind.BODY_COMPLETED,
                TransitionKind.COMPLETED,
            ),
            transitions.map { it.kind },
        )
        assertEquals(listOf(1L, 2L, 4L, 5L, 6L, 7L), transitions.map { it.appNanos })
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.TransitionsTest"`
Expected: compilation FAILS (`Transitions`, `TransitionKind`, `SummaryTable`, `HealthThresholds` unresolved).
`SummaryTable` is referenced by `EventScript.table()`; to compile this step, create `health/SummaryTable.kt`
now as a placeholder you replace in Task 6:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.VizEvent

class SummaryTable(
    @Suppress("UnusedPrivateProperty") private val fullSummaryTarget: Int = HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET,
    @Suppress("UnusedPrivateProperty") private val trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
) {
    @Suppress("UnusedParameter")
    fun apply(event: VizEvent) = Unit
}
```

- [ ] **Step 3: Implement**

`health/HealthThresholds.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

/** Core health constants (spec 2026-10-21, "Definitions" and C-2/C-3). */
object HealthThresholds {
    /** Running longer than this since the latest start/resume, up to the watermark, is possibly blocked (#232). */
    const val POSSIBLY_BLOCKED_NANOS: Long = 10_000_000_000L

    /** Suspended longer than this is the long-suspended observation (the plugin's LONG_SUSPENDED_THRESHOLD_NANOS). */
    const val LONG_SUSPENDED_NANOS: Long = 30_000_000_000L

    /** Default leak (long-lived) threshold, as `GET …/metrics` uses today. */
    const val DEFAULT_LEAK_THRESHOLD_MS: Long = 30_000L

    /** A never-finishing genuine root needs at least this many direct children to become a container. */
    const val CONTAINER_MIN_DIRECT_CHILDREN: Int = 3

    /** Every health list is capped at this many entries, with its total. */
    const val LIST_CAP: Int = 50

    /** Retained transitions per possibly blocked coroutine. */
    const val TRANSITIONS_CAP: Int = 20

    /** Best-effort target of full summaries per session; finished ones beyond it become stubs. */
    const val DEFAULT_FULL_SUMMARY_TARGET: Int = 50_000

    /** Hard limit of tracked coroutines (summaries plus stubs) per session. */
    const val DEFAULT_TRACKED_CAPACITY: Int = 100_000

    /** Thread-name prefixes of known event loops, for the clue (spec "Event-loop clue"). */
    val EVENT_LOOP_THREAD_PREFIXES: List<String> =
        listOf(
            "lettuce-nioEventLoop-",
            "lettuce-epollEventLoop-",
            "reactor-http-nio-",
            "reactor-tcp-nio-",
            "nioEventLoopGroup-",
            "epollEventLoopGroup-",
        )

    /**
     * A `CoroutineFailed` whose type is a cancellation is a cancellation, not a failure (plugin
     * ProblemDerivation). Unlike the plugin's isRealException, an unknown (`null`) type is NOT a
     * cancellation: the failure is counted, because nothing says it was cancelled.
     */
    fun isCancellation(exceptionType: String?): Boolean = exceptionType?.endsWith("CancellationException") == true
}
```

`health/CoroutineSummary.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.models.CoroutineState
import kotlinx.serialization.Serializable

@Serializable
enum class TransitionKind { CREATED, STARTED, SUSPENDED, RESUMED, BODY_COMPLETED, COMPLETED, CANCELLED, FAILED }

/** One observed lifecycle transition: its kind, app-clock time and session sequence. */
@Serializable
data class Transition(
    val kind: TransitionKind,
    val appNanos: Long,
    val seq: Long,
)

/** A thread assignment and the sequence it was recorded at. */
@Serializable
data class ThreadObservation(
    val threadId: Long,
    val threadName: String,
    val seq: Long,
)

@Serializable
data class FailureInfo(
    val exceptionType: String?,
    val message: String?,
)

/**
 * The durable per-coroutine summary (spec 2026-10-21, C-2), updated on each event by [SummaryUpdater]
 * and never rebuilt from retained events. A [reduced] summary is the structural stub: identity,
 * ancestry, name, location, times and flags only.
 *
 * [origin] is `null` when the producer did not report one. [failed] is true for a terminal failure
 * that is not a known cancellation and survives reduction. [promotedAtSeq] is set the first time a running
 * genuine root qualifies as a container; new active children never clear it; the root finishing clears it
 * unless the root got a new direct child after promotion, which [grewAfterPromotion] records (on the
 * summary, so a restored table decides the same way).
 */
@Serializable
data class CoroutineSummary(
    val id: String,
    val parentId: String?,
    val origin: CoroutineOrigin?,
    val name: String?,
    val creationLocation: SuspensionPoint?,
    val createdAtEpochMs: Long,
    val firstObserved: Transition,
    val lastTransition: Transition,
    /** App-clock time of the latest event that touched this coroutine (its own last observation). */
    val lastObservedNanos: Long,
    val lastRunStart: Transition? = null,
    val everSuspended: Boolean = false,
    val terminal: Transition? = null,
    val failed: Boolean = false,
    /** Cancelled, or failed with a known cancellation exception; survives reduction. */
    val cancelled: Boolean = false,
    val failure: FailureInfo? = null,
    val lastSuspension: SuspensionPoint? = null,
    val lastSuspensionProvenance: SuspensionProvenance? = null,
    val lastThread: ThreadObservation? = null,
    val dispatcher: String? = null,
    val lastModifiedSeq: Long,
    val promotedAtSeq: Long? = null,
    val grewAfterPromotion: Boolean = false,
    val reduced: Boolean = false,
) {
    val isTerminal: Boolean get() = terminal != null

    val state: CoroutineState
        get() =
            when {
                failed -> CoroutineState.FAILED
                cancelled -> CoroutineState.CANCELLED
                else ->
                    when ((terminal ?: lastTransition).kind) {
                        TransitionKind.CREATED -> CoroutineState.CREATED
                        TransitionKind.STARTED, TransitionKind.RESUMED -> CoroutineState.ACTIVE
                        TransitionKind.SUSPENDED -> CoroutineState.SUSPENDED
                        TransitionKind.BODY_COMPLETED -> CoroutineState.WAITING_FOR_CHILDREN
                        TransitionKind.COMPLETED -> CoroutineState.COMPLETED
                        TransitionKind.CANCELLED -> CoroutineState.CANCELLED
                        TransitionKind.FAILED -> CoroutineState.FAILED
                    }
            }

    /** The structural stub kept after reduction (spec C-2, "Reduction"). */
    fun toStub(): CoroutineSummary =
        copy(
            lastRunStart = null,
            failure = null,
            lastSuspension = null,
            lastSuspensionProvenance = null,
            lastThread = null,
            dispatcher = null,
            reduced = true,
        )
}
```

`health/Transitions.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended

/** Maps lifecycle events to [Transition]s; the backend uses it for a coroutine's retained transitions. */
object Transitions {
    fun of(event: VizEvent): Transition? {
        val kind =
            when (event) {
                is CoroutineCreated -> TransitionKind.CREATED
                is CoroutineStarted -> TransitionKind.STARTED
                is CoroutineSuspended -> TransitionKind.SUSPENDED
                is CoroutineResumed -> TransitionKind.RESUMED
                is CoroutineBodyCompleted -> TransitionKind.BODY_COMPLETED
                is CoroutineCompleted -> TransitionKind.COMPLETED
                is CoroutineCancelled -> TransitionKind.CANCELLED
                is CoroutineFailed -> TransitionKind.FAILED
                else -> return null
            }
        return Transition(kind, event.tsNanos, event.seq)
    }

    /** The transitions of [coroutineId] among [events], in event order. */
    fun forCoroutine(
        events: Iterable<VizEvent>,
        coroutineId: String,
    ): List<Transition> = events.filter { it is CoroutineEvent && it.coroutineId == coroutineId }.mapNotNull(::of)
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: health thresholds, coroutine summary types and transitions"
```

---

### Task 4: `SummaryUpdater` — one summary, one event

**Files:**
- Create: `health/SummaryUpdater.kt`
- Test: `health/SummaryUpdaterTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/health/SummaryUpdaterTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.models.CoroutineState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SummaryUpdaterTest {
    private fun fold(script: EventScript): CoroutineSummary {
        val created = script.events.first() as CoroutineCreated
        return script.events.drop(1).fold(SummaryUpdater.created(created)) { s, e -> SummaryUpdater.update(s, e as CoroutineEvent) }
    }

    @Test
    fun `created records identity, origin and first observation`() {
        val script = EventScript()
        val point = SuspensionPoint(function = "handle", fileName = "Handler.kt", lineNumber = 7, reason = "creation")
        script.created("a", at = 100, parent = "p", label = "GET /x", origin = CoroutineOrigin.VIZCORE, creationPoint = point)

        val s = fold(script)

        assertEquals("p", s.parentId)
        assertEquals(CoroutineOrigin.VIZCORE, s.origin)
        assertEquals("GET /x", s.name)
        assertEquals(point, s.creationLocation)
        assertEquals(Transition(TransitionKind.CREATED, 100, 1), s.firstObserved)
        assertEquals(CoroutineState.CREATED, s.state)
        assertEquals(1, s.lastModifiedSeq)
    }

    @Test
    fun `start and resume are run starts, suspension sets everSuspended`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.started("a", at = 2)
        script.suspended("a", at = 3)
        script.resumed("a", at = 4)

        val s = fold(script)

        assertEquals(Transition(TransitionKind.RESUMED, 4, 4), s.lastRunStart)
        assertTrue(s.everSuspended)
        assertEquals(CoroutineState.ACTIVE, s.state)
    }

    @Test
    fun `a real frame is kept with its provenance, a frame-less suspension marks it stale`() {
        val script = EventScript()
        val frame = SuspensionPoint(function = "load", fileName = "Repo.kt", lineNumber = 42, reason = "delay")
        script.created("a", at = 1)
        script.started("a", at = 2)
        script.suspended("a", at = 3, point = frame, provenance = SuspensionProvenance.REAL_FRAME)
        script.resumed("a", at = 4)
        script.suspended("a", at = 5, point = null, provenance = null)

        val s = fold(script)

        assertEquals(frame, s.lastSuspension)
        assertEquals(SuspensionProvenance.STALE, s.lastSuspensionProvenance)
    }

    @Test
    fun `provenance from an older producer stays unknown`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.suspended("a", at = 2, provenance = null)

        assertNull(fold(script).lastSuspensionProvenance)
    }

    @Test
    fun `thread and dispatcher are recorded with the assignment seq`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.started("a", at = 2)
        script.thread("a", at = 3, name = "lettuce-nioEventLoop-5-1", threadId = 51, dispatcher = "Dispatchers.Unconfined")

        val s = fold(script)

        assertEquals(ThreadObservation(51, "lettuce-nioEventLoop-5-1", 3), s.lastThread)
        assertEquals("Dispatchers.Unconfined", s.dispatcher)
        assertEquals(3, s.lastModifiedSeq)
    }

    @Test
    fun `a real failure is a failure, a cancellation exception is not`() {
        val real = EventScript()
        real.created("a", at = 1)
        real.failed("a", at = 2, type = "java.lang.IllegalStateException", message = "boom")
        val cancelled = EventScript()
        cancelled.created("a", at = 1)
        cancelled.failed("a", at = 2, type = "kotlinx.coroutines.JobCancellationException", message = "parent failed")

        val r = fold(real)
        val c = fold(cancelled)

        assertTrue(r.failed)
        assertEquals(FailureInfo("java.lang.IllegalStateException", "boom"), r.failure)
        assertEquals(CoroutineState.FAILED, r.state)
        assertFalse(c.failed)
        assertTrue(c.cancelled)
        assertEquals(CoroutineState.CANCELLED, c.state)
    }

    @Test
    fun `a failure of unknown type is a failure, not a cancellation`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.failed("a", at = 2, type = null, message = null)

        val s = fold(script)
        assertTrue(s.failed)
        assertFalse(s.cancelled)
    }

    @Test
    fun `any event about a coroutine advances its last observation`() {
        val script = EventScript()
        script.created("a", at = sec(0))
        script.waitingForChildren("a", at = sec(40))

        val s = fold(script)
        assertEquals(sec(40), s.lastObservedNanos)
        assertEquals(2, s.lastModifiedSeq)
        assertEquals(TransitionKind.CREATED, s.lastTransition.kind)
    }

    @Test
    fun `a terminal summary ignores later events`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.completed("a", at = 2)
        val done = fold(script)
        script.thread("a", at = 3, name = "late")

        assertSame(done, SummaryUpdater.update(done, script.events.last() as CoroutineEvent))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.SummaryUpdaterTest"`
Expected: compilation FAILS (`SummaryUpdater` unresolved).

- [ ] **Step 3: Implement**

`health/SummaryUpdater.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.dispatcher.DispatcherSelected
import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned

/** Pure per-summary updates (spec C-2): the one place a summary changes. */
object SummaryUpdater {
    fun created(e: CoroutineCreated): CoroutineSummary {
        val transition = Transition(TransitionKind.CREATED, e.tsNanos, e.seq)
        return CoroutineSummary(
            id = e.coroutineId,
            parentId = e.parentCoroutineId,
            origin = e.origin,
            name = e.label,
            creationLocation = e.creationPoint,
            createdAtEpochMs = e.createdAtEpochMs,
            firstObserved = transition,
            lastTransition = transition,
            lastObservedNanos = e.tsNanos,
            lastModifiedSeq = e.seq,
        )
    }

    /** [s] after [e]; [s] itself only when [s] is terminal (a terminal summary never changes) or [e] is a re-creation. */
    fun update(
        s: CoroutineSummary,
        e: CoroutineEvent,
    ): CoroutineSummary {
        if (s.isTerminal) return s
        return when (e) {
            is ThreadAssigned ->
                s.copy(
                    lastThread = ThreadObservation(e.threadId, e.threadName, e.seq),
                    dispatcher = e.dispatcherName ?: s.dispatcher,
                    lastObservedNanos = maxOf(s.lastObservedNanos, e.tsNanos),
                    lastModifiedSeq = e.seq,
                )
            is DispatcherSelected ->
                s.copy(
                    dispatcher = e.dispatcherName,
                    lastObservedNanos = maxOf(s.lastObservedNanos, e.tsNanos),
                    lastModifiedSeq = e.seq,
                )
            is CoroutineCreated -> s
            // Any other event about the coroutine (join, waiting for children, channel, …) observes it.
            else ->
                Transitions.of(e)?.let { transition(s, e, it) }
                    ?: s.copy(lastObservedNanos = maxOf(s.lastObservedNanos, e.tsNanos), lastModifiedSeq = e.seq)
        }
    }

    private fun transition(
        s: CoroutineSummary,
        e: CoroutineEvent,
        t: Transition,
    ): CoroutineSummary {
        val moved = s.copy(lastTransition = t, lastObservedNanos = maxOf(s.lastObservedNanos, t.appNanos), lastModifiedSeq = t.seq)
        return when (t.kind) {
            TransitionKind.STARTED, TransitionKind.RESUMED -> moved.copy(lastRunStart = t)
            TransitionKind.SUSPENDED -> suspended(moved, e as CoroutineSuspended)
            TransitionKind.COMPLETED -> moved.copy(terminal = t)
            TransitionKind.CANCELLED -> moved.copy(terminal = t, cancelled = true)
            TransitionKind.FAILED -> {
                val failure = e as CoroutineFailed
                val cancellation = HealthThresholds.isCancellation(failure.exceptionType)
                moved.copy(
                    terminal = t,
                    failed = !cancellation,
                    cancelled = cancellation,
                    failure = FailureInfo(failure.exceptionType, failure.message),
                )
            }
            TransitionKind.CREATED, TransitionKind.BODY_COMPLETED -> moved
        }
    }

    private fun suspended(
        s: CoroutineSummary,
        e: CoroutineSuspended,
    ): CoroutineSummary {
        val point = e.suspensionPoint
        return when {
            point != null -> s.copy(everSuspended = true, lastSuspension = point, lastSuspensionProvenance = e.provenance)
            s.lastSuspension != null -> s.copy(everSuspended = true, lastSuspensionProvenance = SuspensionProvenance.STALE)
            else -> s.copy(everSuspended = true)
        }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: the command from Step 2. Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: SummaryUpdater keeps one coroutine's durable summary"
```

---

### Task 5: `SubtreeIndex` — open subtrees and container qualification

A genuine root becomes a container when it never finished, has at least 3 direct children, and at least
half of those children's subtrees are fully finished (plugin `RoundGrouping.isContainerRoot`). Computing
that on every event by walking subtrees would be O(n) per event; this index keeps per-node counts so each
event costs O(depth).

**Files:**
- Create: `health/SubtreeIndex.kt`
- Test: `health/SubtreeIndexTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/health/SubtreeIndexTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtreeIndexTest {
    @Test
    fun `root qualifies once half of at least three child subtrees are finished`() {
        val index = SubtreeIndex()
        index.add("root", null)
        listOf("a", "b", "c").forEach { index.add(it, "root") }
        assertFalse(index.qualifiesAsContainer("root"))

        index.close("a")
        assertFalse(index.qualifiesAsContainer("root"), "1 of 3 finished")

        index.close("b")
        assertTrue(index.qualifiesAsContainer("root"), "2 of 3 finished")
    }

    @Test
    fun `a child subtree counts as finished only when all its descendants are`() {
        val index = SubtreeIndex()
        index.add("root", null)
        listOf("a", "b", "c").forEach { index.add(it, "root") }
        index.add("a1", "a")
        index.close("a")
        index.close("b")
        assertFalse(index.qualifiesAsContainer("root"), "a's child a1 is still open")

        index.close("a1")
        assertTrue(index.qualifiesAsContainer("root"))
    }

    @Test
    fun `fewer than three children never qualify`() {
        val index = SubtreeIndex()
        index.add("root", null)
        index.add("a", "root")
        index.add("b", "root")
        index.close("a")
        index.close("b")
        assertFalse(index.qualifiesAsContainer("root"))
    }

    @Test
    fun `children created before their parent are linked when it arrives`() {
        val index = SubtreeIndex()
        index.add("a", "root")
        index.close("a")
        index.add("b", "root")
        index.add("root", null)
        index.add("c", "root")

        assertEquals("root", index.rootOf("a"))
        assertFalse(index.qualifiesAsContainer("root"), "1 of 3 finished")
        index.close("b")
        assertTrue(index.qualifiesAsContainer("root"), "2 of 3 finished")
    }

    @Test
    fun `rootOf follows tracked parents only`() {
        val index = SubtreeIndex()
        index.add("root", null)
        index.add("a", "root")
        index.add("a1", "a")
        index.add("orphan", "never-seen")

        assertEquals("root", index.rootOf("a1"))
        assertEquals("orphan", index.rootOf("orphan"))
    }

    @Test
    fun `a coroutine naming itself as parent is its own root`() {
        val index = SubtreeIndex()
        index.add("x", "x")
        index.close("x")

        assertEquals("x", index.rootOf("x"))
        assertFalse(index.qualifiesAsContainer("x"))
    }

    @Test
    fun `a parent cycle is not closed`() {
        val index = SubtreeIndex()
        index.add("a", "b")
        index.add("b", "a")
        index.close("a")
        index.close("b")

        assertEquals("a", index.rootOf("a"))
        assertEquals("a", index.rootOf("b"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.SubtreeIndexTest"`
Expected: compilation FAILS (`SubtreeIndex` unresolved).

- [ ] **Step 3: Implement**

`health/SubtreeIndex.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

/**
 * Incremental structure for container-root promotion. For each tracked node it keeps how many nodes in
 * its subtree are still open, and for each parent how many of its direct-child subtrees are fully
 * closed. [add] and [close] cost O(depth). A child created before its parent (the DebugProbes poll gives
 * no parent-first order) waits in [pendingChildren] and is linked when the parent arrives; until then, and
 * forever if the parent never arrives, it is its own root. Malformed ancestry (a self-parent, or a pending
 * child that is already an ancestor of the arriving parent) is never linked, so walks always terminate:
 * the session's send lock is held while they run.
 */
internal class SubtreeIndex {
    private val parentOf = HashMap<String, String>()
    private val directChildren = HashMap<String, Int>()
    private val openInSubtree = HashMap<String, Int>()
    private val closedChildSubtrees = HashMap<String, Int>()
    private val pendingChildren = HashMap<String, MutableList<String>>()

    fun add(
        id: String,
        parentId: String?,
    ) {
        if (id in openInSubtree) return
        openInSubtree[id] = 0
        if (parentId != null && parentId != id) {
            if (parentId in openInSubtree) link(id, parentId) else pendingChildren.getOrPut(parentId) { mutableListOf() } += id
        }
        adjust(id, +1)
        val idRoot = rootOf(id)
        pendingChildren.remove(id)?.forEach { child -> if (child != idRoot) adopt(child, id) }
    }

    fun close(id: String) {
        if (id in openInSubtree) adjust(id, -1)
    }

    fun rootOf(id: String): String {
        var node = id
        while (true) {
            node = parentOf[node] ?: return node
        }
    }

    fun qualifiesAsContainer(id: String): Boolean {
        val children = directChildren[id] ?: 0
        val closed = closedChildSubtrees[id] ?: 0
        return children >= HealthThresholds.CONTAINER_MIN_DIRECT_CHILDREN && closed * 2 >= children
    }

    private fun link(
        child: String,
        parent: String,
    ) {
        parentOf[child] = parent
        directChildren.merge(parent, 1, Int::plus)
        // The new child's subtree starts empty (closed); adjust() in add() opens it.
        closedChildSubtrees.merge(parent, 1, Int::plus)
    }

    /** Links a child that was created before [parent] and carries its open count up the new chain. */
    private fun adopt(
        child: String,
        parent: String,
    ) {
        parentOf[child] = parent
        directChildren.merge(parent, 1, Int::plus)
        val open = openInSubtree.getValue(child)
        if (open == 0) closedChildSubtrees.merge(parent, 1, Int::plus) else adjust(parent, open)
    }

    private fun adjust(
        start: String,
        delta: Int,
    ) {
        var node: String? = start
        while (node != null) {
            val before = openInSubtree.getValue(node)
            val after = before + delta
            openInSubtree[node] = after
            val parent = parentOf[node]
            if (parent != null) {
                if (before == 0 && after > 0) closedChildSubtrees.merge(parent, -1, Int::plus)
                if (before > 0 && after == 0) closedChildSubtrees.merge(parent, 1, Int::plus)
            }
            node = parent
        }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: the command from Step 2. Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: SubtreeIndex decides container roots incrementally"
```

---

### Task 6: `SummaryTable` — apply, sticky promotion, capacity, reduction

**Files:**
- Modify (replace the Task 3 placeholder): `health/SummaryTable.kt`
- Test: `health/SummaryTableTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/health/SummaryTableTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryTableTest {
    /** A never-finishing root "app" with three children; two of them finish. */
    private fun containerScript(): EventScript =
        EventScript().apply {
            created("app", at = sec(0))
            started("app", at = sec(0))
            listOf("r1", "r2", "r3").forEachIndexed { i, id ->
                created(id, at = sec(1L + i), parent = "app")
                started(id, at = sec(1L + i))
            }
            completed("r1", at = sec(5))
            completed("r2", at = sec(6))
        }

    @Test
    fun `a root is promoted the first time it qualifies, at that event's seq`() {
        val script = containerScript()
        val table = script.table()

        val app = assertNotNull(table.get("app"))
        assertEquals(script.lastSeq, app.promotedAtSeq)
        assertEquals(script.lastSeq, app.lastModifiedSeq)
    }

    @Test
    fun `promotion is sticky when new active children arrive`() {
        val script = containerScript()
        val promotedAt = script.table().get("app")?.promotedAtSeq
        listOf("r4", "r5", "r6").forEach { script.created(it, at = sec(7), parent = "app") }

        assertEquals(promotedAt, script.table().get("app")?.promotedAtSeq)
    }

    @Test
    fun `growth after promotion is recorded on the summary and survives reduction`() {
        val script = containerScript()
        assertFalse(script.table().get("app")!!.grewAfterPromotion)
        script.created("r4", at = sec(7), parent = "app")

        val app = assertNotNull(script.table().get("app"))
        assertTrue(app.grewAfterPromotion)
        assertEquals(script.lastSeq, app.lastModifiedSeq)
        assertTrue(app.toStub().grewAfterPromotion)
    }

    @Test
    fun `the latest time is the greatest seen, not the last appended`() {
        val script = EventScript()
        script.created("a", at = sec(61))
        script.created("b", at = sec(59))

        assertEquals(sec(61), script.table().latestEventAppNanos)
    }

    @Test
    fun `events for unknown coroutines are ignored, last event is still tracked`() {
        val script = EventScript()
        script.started("ghost", at = sec(3))
        val table = script.table()

        assertNull(table.get("ghost"))
        assertEquals(1, table.lastEventSeq)
        assertEquals(sec(3), table.latestEventAppNanos)
    }

    @Test
    fun `a watermark advances the last seq and time but tracks no coroutine`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.watermark(at = 2)
        val table = script.table()

        assertEquals(2, table.lastEventSeq)
        assertEquals(2, table.latestEventAppNanos)
        assertEquals(1, table.all.size)
    }

    @Test
    fun `beyond capacity new coroutines are only counted`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.created("b", at = 2)
        script.created("c", at = 3)
        script.started("c", at = 4)
        val table = script.table(trackedCapacity = 2)

        assertEquals(2, table.all.size)
        assertNull(table.get("c"))
        assertEquals(1, table.untrackedCount)
    }

    @Test
    fun `a request root whose children finish first is not left a container`() {
        val script = EventScript()
        script.created("req", at = sec(1))
        listOf("a", "b", "c", "d").forEach { script.created(it, at = sec(1), parent = "req") }
        listOf("a", "b").forEach { script.completed(it, at = sec(2)) }
        assertNotNull(script.table().get("req")?.promotedAtSeq, "half of four children finished: qualifies")

        listOf("c", "d").forEach { script.completed(it, at = sec(3)) }
        script.completed("req", at = sec(4))

        val req = assertNotNull(script.table().get("req"))
        assertNull(req.promotedAtSeq)
        assertEquals(script.lastSeq, req.lastModifiedSeq)
    }

    @Test
    fun `a container that got new children after promotion stays a container when it finishes`() {
        val script = containerScript()
        script.created("r4", at = sec(7), parent = "app")
        script.completed("r3", at = sec(8))
        script.completed("r4", at = sec(8))
        script.cancelled("app", at = sec(9))

        assertNotNull(script.table().get("app")?.promotedAtSeq)
    }

    @Test
    fun `over the target, finished non-failed summaries become stubs before failed ones`() {
        val script = EventScript()
        script.created("ok", at = 1)
        script.created("failed", at = 2)
        script.created("active", at = 3)
        script.failed("failed", at = 4)
        script.completed("ok", at = 5)
        script.created("new", at = 6)
        val table = script.table(fullSummaryTarget = 3)

        assertTrue(table.get("ok")!!.reduced)
        assertEquals(6, table.get("ok")!!.lastModifiedSeq, "reduction is a change, stamped with the triggering seq")
        assertFalse(table.get("failed")!!.reduced, "failed ones are reduced only after the non-failed")
        assertFalse(table.get("active")!!.reduced, "active coroutines keep their full summary")
        assertEquals(3, table.fullSummaryCount)

        script.created("newer", at = 7)
        assertTrue(script.table(fullSummaryTarget = 3).get("failed")!!.reduced)
    }

    @Test
    fun `reduction takes the oldest finished summary, not the first to finish`() {
        val script = EventScript()
        script.created("old", at = 1)
        script.created("new", at = 2)
        script.completed("new", at = 3)
        script.completed("old", at = 4)
        script.created("z", at = 5)
        val table = script.table(fullSummaryTarget = 2)

        assertTrue(table.get("old")!!.reduced)
        assertFalse(table.get("new")!!.reduced)
    }

    @Test
    fun `a reduced failure keeps its failed flag and ancestry`() {
        val script = EventScript()
        script.created("root", at = 1)
        script.created("child", at = 2, parent = "root")
        script.failed("child", at = 3)
        script.created("x", at = 4)
        val table = script.table(fullSummaryTarget = 1)

        val child = assertNotNull(table.get("child"))
        assertTrue(child.reduced)
        assertTrue(child.failed)
        assertEquals("root", child.parentId)
        assertNull(child.failure)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.SummaryTableTest"`
Expected: compilation FAILS (`get`, `lastEventSeq`, `untrackedCount`, … unresolved on the placeholder).

- [ ] **Step 3: Implement**

Replace `health/SummaryTable.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import java.util.TreeMap

/** Read access to a session's summaries, for the derivation and the backend. */
interface SummaryView {
    fun get(id: String): CoroutineSummary?

    /** All tracked summaries (full and stubs), in first-observation order. */
    val all: Collection<CoroutineSummary>

    /** Coroutines created after the tracked capacity was reached (only counted). */
    val untrackedCount: Long

    /** Sequence of the last event applied (any kind, including watermarks), 0 before any. */
    val lastEventSeq: Long

    /**
     * Greatest app-clock time of any event applied, `null` before any. A max, not the last one: appends are
     * not in timestamp order. Attached sessions receive events only through ingest, so all carry the app clock.
     */
    val latestEventAppNanos: Long?
}

/**
 * The per-session summary table (spec C-2): one [CoroutineSummary] per tracked coroutine, updated on
 * every event by [apply] and never rebuilt from retained events. It also records container promotion
 * ([CoroutineSummary.promotedAtSeq]: set the first time a running genuine root qualifies; cleared when
 * that root finishes unless it got a new direct child after promotion), enforces [trackedCapacity], and
 * reduces finished summaries to stubs beyond
 * [fullSummaryTarget] (finished non-failed first, then failed, oldest first), checked on every creation
 * and every terminal event.
 *
 * Not thread-safe: the owner serializes [apply] and reads (VizSession does both under its send lock).
 */
class SummaryTable(
    private val fullSummaryTarget: Int = HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET,
    private val trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
) : SummaryView {
    private val summaries = LinkedHashMap<String, CoroutineSummary>()
    private val subtrees = SubtreeIndex()
    // Finished summaries eligible for reduction, keyed by first-observation seq so the oldest goes first.
    private val finishedOk = TreeMap<Long, String>()
    private val finishedFailed = TreeMap<Long, String>()

    /** Summaries that are not stubs. */
    var fullSummaryCount: Int = 0
        private set

    override var untrackedCount: Long = 0L
        private set

    override var lastEventSeq: Long = 0L
        private set

    override var latestEventAppNanos: Long? = null
        private set

    override fun get(id: String): CoroutineSummary? = summaries[id]

    override val all: Collection<CoroutineSummary> get() = summaries.values

    fun apply(event: VizEvent) {
        lastEventSeq = event.seq
        latestEventAppNanos = maxOf(latestEventAppNanos ?: Long.MIN_VALUE, event.tsNanos)
        if (event !is CoroutineEvent) return
        if (event is CoroutineCreated) onCreated(event) else onUpdate(event)
    }

    private fun onCreated(e: CoroutineCreated) {
        if (e.coroutineId in summaries) return
        // Growth counts even for a child beyond capacity: the root is still a container.
        val parent = e.parentCoroutineId?.let { summaries[it] }
        if (parent != null && parent.promotedAtSeq != null && !parent.grewAfterPromotion) {
            summaries[parent.id] = parent.copy(grewAfterPromotion = true, lastModifiedSeq = e.seq)
        }
        if (summaries.size >= trackedCapacity) {
            untrackedCount++
            return
        }
        summaries[e.coroutineId] = SummaryUpdater.created(e)
        fullSummaryCount++
        subtrees.add(e.coroutineId, e.parentCoroutineId)
        promoteIfQualified(subtrees.rootOf(e.coroutineId), e.seq)
        reduceOverTarget(e.seq)
    }

    private fun onUpdate(event: CoroutineEvent) {
        val current = summaries[event.coroutineId] ?: return
        val next = SummaryUpdater.update(current, event)
        if (next !== current) {
            summaries[event.coroutineId] = next
            if (!current.isTerminal && next.isTerminal) onTerminal(next, event.seq)
        }
    }

    private fun onTerminal(
        s: CoroutineSummary,
        seq: Long,
    ) {
        // A finished root that never grew after promotion was a request, not a container (spec amendment).
        if (s.parentId == null && s.promotedAtSeq != null && !s.grewAfterPromotion) {
            summaries[s.id] = s.copy(promotedAtSeq = null, lastModifiedSeq = seq)
        }
        subtrees.close(s.id)
        (if (s.failed) finishedFailed else finishedOk)[s.firstObserved.seq] = s.id
        promoteIfQualified(subtrees.rootOf(s.id), seq)
        reduceOverTarget(seq)
    }

    private fun promoteIfQualified(
        rootId: String,
        seq: Long,
    ) {
        val root = summaries[rootId] ?: return
        if (root.parentId != null || root.isTerminal || root.promotedAtSeq != null) return
        if (subtrees.qualifiesAsContainer(rootId)) {
            summaries[rootId] = root.copy(promotedAtSeq = seq, lastModifiedSeq = seq)
        }
    }

    /** Reduction changes fields, so the stub's lastModifiedSeq becomes the triggering event's [seq] (C-2). */
    private fun reduceOverTarget(seq: Long) {
        while (fullSummaryCount > fullSummaryTarget) {
            val id = (finishedOk.pollFirstEntry() ?: finishedFailed.pollFirstEntry())?.value ?: return
            summaries[id] = summaries.getValue(id).toStub().copy(lastModifiedSeq = seq)
            fullSummaryCount--
        }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: the command from Step 2, then also `TransitionsTest` to confirm `EventScript.table()` still compiles.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: SummaryTable applies events, promotes containers, caps and reduces summaries"
```

---

### Task 7: `RoundAnchoring` — anchors, rounds, ungroupable coroutines

**Files:**
- Create: `health/RoundAnchoring.kt`
- Test: `health/RoundAnchoringTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/health/RoundAnchoringTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.models.CoroutineState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoundAnchoringTest {
    @Test
    fun `each genuine root anchors its subtree`() {
        val script = EventScript()
        script.created("req1", at = sec(1))
        script.created("req1.a", at = sec(1), parent = "req1")
        script.created("req2", at = sec(2))
        val anchoring = RoundAnchoring.of(script.table())

        assertEquals("req1", anchoring.anchorOf("req1.a"))
        assertEquals("req2", anchoring.anchorOf("req2"))
        assertEquals(listOf("round:req2", "round:req1"), anchoring.rounds().map { it.roundId })
    }

    @Test
    fun `a promoted container anchors each direct child and is a singleton round`() {
        val script = EventScript()
        script.created("app", at = sec(0))
        listOf("r1", "r2", "r3").forEachIndexed { i, id -> script.created(id, at = sec(1L + i), parent = "app") }
        script.created("r3.part", at = sec(3), parent = "r3")
        script.completed("r1", at = sec(4))
        script.completed("r2", at = sec(5))
        val anchoring = RoundAnchoring.of(script.table())

        assertEquals("r3", anchoring.anchorOf("r3.part"))
        assertEquals("app", anchoring.anchorOf("app"))
        val appRound = anchoring.rounds().single { it.anchorId == "app" }
        assertEquals(1, appRound.memberCount)
        val r3 = anchoring.rounds().single { it.anchorId == "r3" }
        assertEquals(2, r3.memberCount)
        assertEquals(true, r3.inProgress)
    }

    @Test
    fun `before promotion the whole tree is one round, after it the children re-key`() {
        val script = EventScript()
        script.created("app", at = sec(0))
        listOf("r1", "r2", "r3").forEachIndexed { i, id -> script.created(id, at = sec(1L + i), parent = "app") }
        script.failed("r1", at = sec(4))
        assertEquals("app", RoundAnchoring.of(script.table()).anchorOf("r1"))

        script.completed("r2", at = sec(5))
        assertEquals("r1", RoundAnchoring.of(script.table()).anchorOf("r1"))
    }

    @Test
    fun `a coroutine whose parent was never tracked has no round`() {
        val script = EventScript()
        script.created("orphan", at = 1, parent = "never-seen")
        script.created("orphan.child", at = 2, parent = "orphan")
        val anchoring = RoundAnchoring.of(script.table())

        assertNull(anchoring.anchorOf("orphan"))
        assertNull(anchoring.anchorOf("orphan.child"))
        assertEquals(emptyList(), anchoring.rounds())
    }

    @Test
    fun `reduced summaries keep anchoring and round order`() {
        val script = EventScript()
        script.created("req1", at = sec(1))
        script.created("req1.a", at = sec(1), parent = "req1")
        script.completed("req1.a", at = sec(2))
        script.completed("req1", at = sec(2))
        script.created("req2", at = sec(3))
        val full = RoundAnchoring.of(script.table())
        val reduced = RoundAnchoring.of(script.table(fullSummaryTarget = 1))

        assertEquals("req1", reduced.anchorOf("req1.a"))
        assertEquals(full.rounds().map { it.roundId }, reduced.rounds().map { it.roundId })
    }

    @Test
    fun `round summaries count members by state`() {
        val script = EventScript()
        script.created("req", at = 1)
        script.started("req", at = 2)
        script.created("a", at = 3, parent = "req")
        script.failed("a", at = 4)
        script.created("b", at = 5, parent = "req")
        script.cancelled("b", at = 6)
        val round = RoundAnchoring.of(script.table()).rounds().single()

        assertEquals(mapOf(CoroutineState.ACTIVE to 1, CoroutineState.FAILED to 1, CoroutineState.CANCELLED to 1), round.countsByState)
        assertEquals(3, round.memberCount)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.RoundAnchoringTest"`
Expected: compilation FAILS (`RoundAnchoring` unresolved).

- [ ] **Step 3: Implement**

`health/RoundAnchoring.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.models.CoroutineState
import kotlinx.serialization.Serializable

/** One round (spec "Rounds"): an anchor and its subtree. */
@Serializable
data class RoundSummary(
    val roundId: String,
    val anchorId: String,
    val anchorName: String?,
    val anchorOrigin: CoroutineOrigin?,
    val anchorCreatedNanos: Long,
    val anchorCreatedSeq: Long,
    val inProgress: Boolean,
    val countsByState: Map<CoroutineState, Int>,
    val memberCount: Int,
)

/**
 * Round anchoring moved from the plugin's RoundGrouping (spec "Rounds"): anchors are every genuine root
 * plus every direct child of a promoted container root; a container is a singleton round. Round ids are
 * derived, never stored. A coroutine whose parent chain reaches an untracked parent has no round
 * ([anchorOf] returns `null`).
 */
class RoundAnchoring private constructor(
    private val view: SummaryView,
    val anchorIds: Set<String>,
) {
    private val resolved = HashMap<String, String>()
    private val unresolvable = HashSet<String>()

    fun roundIdOf(id: String): String? = anchorOf(id)?.let(::roundId)

    fun anchorOf(id: String): String? {
        val chain = ArrayList<String>()
        val limit = view.all.size + 1
        var current: String? = id
        var answer: String? = null
        while (current != null && answer == null && chain.size <= limit) {
            val node: String = current
            when {
                node in unresolvable -> current = null
                node in resolved -> answer = resolved[node]
                else -> {
                    chain += node
                    if (node in anchorIds) answer = node else current = trackedParentOf(node)
                }
            }
        }
        val result = answer
        if (result == null) unresolvable += chain else chain.forEach { resolved[it] = result }
        return result
    }

    private fun trackedParentOf(id: String): String? = view.get(id)?.parentId?.takeIf { view.get(it) != null }

    /** Every round, newest anchor first. */
    fun rounds(): List<RoundSummary> {
        val members = HashMap<String, MutableList<CoroutineSummary>>()
        for (s in view.all) {
            val anchor = anchorOf(s.id) ?: continue
            members.getOrPut(anchor) { mutableListOf() } += s
        }
        return anchorIds
            .mapNotNull { anchorId ->
                val anchor = view.get(anchorId) ?: return@mapNotNull null
                val roundMembers = members[anchorId].orEmpty()
                RoundSummary(
                    roundId = roundId(anchorId),
                    anchorId = anchorId,
                    anchorName = anchor.name,
                    anchorOrigin = anchor.origin,
                    anchorCreatedNanos = anchor.firstObserved.appNanos,
                    anchorCreatedSeq = anchor.firstObserved.seq,
                    inProgress = roundMembers.any { !it.isTerminal },
                    countsByState = roundMembers.groupingBy { it.state }.eachCount(),
                    memberCount = roundMembers.size,
                )
            }.sortedWith(compareByDescending<RoundSummary> { it.anchorCreatedNanos }.thenByDescending { it.anchorCreatedSeq })
    }

    companion object {
        fun roundId(anchorId: String): String = "round:$anchorId"

        fun of(view: SummaryView): RoundAnchoring {
            val anchors = HashSet<String>()
            val containers = HashSet<String>()
            for (s in view.all) {
                if (s.parentId == null) {
                    anchors += s.id
                    if (s.promotedAtSeq != null) containers += s.id
                }
            }
            for (s in view.all) {
                val parent = s.parentId
                if (parent != null && parent in containers) anchors += s.id
            }
            return RoundAnchoring(view, anchors)
        }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: the command from Step 2. Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: RoundAnchoring derives rounds from summaries"
```

---

### Task 8: Health model types

No behaviour, so no failing test of its own: the types are exercised by Tasks 9–11. Their serialization
is covered in Task 11 Step 1 (`health round-trips through appJson`).

**Files:**
- Create: `health/HealthModel.kt`

- [ ] **Step 1: Create the file**

`health/HealthModel.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SessionKind { ATTACHED, DEMO }

@Serializable
enum class SessionOrigin { REPORTED, UNKNOWN }

@Serializable
enum class AsOfKind { WATERMARK, INGEST, LAST_EVENT, REPLAY_CURSOR }

/**
 * The observation a health statement is about (spec H-2, C-3 `asOf`). [seq] is the last event sequence the
 * health reflects; [appNanos] is the latest app-clock time observed in the events up to [seq] (events can
 * be appended out of time order, so it need not be the time of the event at [seq]; it is never earlier
 * than anything the health includes). [appEpochMs] is the app's wall-clock time when known (only a
 * watermark carries it); [receivedEpochMs] the backend's receipt time.
 */
@Serializable
data class AsOf(
    val kind: AsOfKind,
    val appNanos: Long,
    val appEpochMs: Long? = null,
    val receivedEpochMs: Long? = null,
    val seq: Long,
)

/** A list capped at [HealthThresholds.LIST_CAP] entries, with the total before the cap. */
@Serializable
data class Capped<T>(
    val items: List<T>,
    val total: Int,
) {
    companion object {
        fun <T> of(all: List<T>): Capped<T> = Capped(all.take(HealthThresholds.LIST_CAP), all.size)
    }
}

@Serializable
enum class EvaluationStatus { EVALUATED, NOT_EVALUATED }

@Serializable
enum class NotEvaluatedReason { NO_WATERMARK, DEMO, REPLAY, OVERFLOW, EVENTS_DROPPED }

@Serializable
data class PossiblyBlockedEvaluation(
    val status: EvaluationStatus,
    val reason: NotEvaluatedReason? = null,
)

/** The health strip's state (spec "Health strip states"). */
@Serializable
enum class HealthState { AWAITING_FIRST_DATA, PROBLEMS, NO_PROBLEMS, NO_FAILURES_PARTIAL, NO_APP_COROUTINES }

@Serializable
enum class IncidentKind { FAILED, POSSIBLY_BLOCKED }

@Serializable
data class FailureEntry(
    val coroutineId: String,
    val name: String?,
    val exceptionType: String?,
    val message: String?,
    val failedAtSeq: Long,
    /** The summary was reduced to a stub: type and message are no longer known. */
    val detailsDropped: Boolean,
)

@Serializable
sealed interface Incident {
    val id: String
    val kind: IncidentKind

    /** Newest-first ordering key within a kind. */
    val sortSeq: Long
}

@Serializable
@SerialName("FAILED_ROUND")
data class FailedRoundIncident(
    override val id: String,
    val roundId: String,
    val anchorId: String,
    val failures: Capped<FailureEntry>,
    /** Cancellations in this round recorded after its first failure (context, not a cause). */
    val cancelledAfter: Capped<String>,
    val firstFailureSeq: Long,
    override val sortSeq: Long,
) : Incident {
    override val kind: IncidentKind get() = IncidentKind.FAILED
}

@Serializable
@SerialName("UNGROUPED_FAILURE")
data class UngroupedFailureIncident(
    override val id: String,
    val failure: FailureEntry,
    override val sortSeq: Long,
) : Incident {
    override val kind: IncidentKind get() = IncidentKind.FAILED
}

@Serializable
@SerialName("POSSIBLY_BLOCKED")
data class PossiblyBlockedIncident(
    override val id: String,
    val coroutineId: String,
    val name: String?,
    val roundId: String?,
    val anchorId: String?,
    /** The thread assigned in the current run, `null` when none was observed since the latest start/resume. */
    val lastThread: ThreadObservation?,
    val dispatcher: String?,
    val lastSuspension: SuspensionPoint?,
    val lastSuspensionProvenance: SuspensionProvenance?,
    val lastRunStart: Transition,
    val runningForNanos: Long,
    val transitions: List<Transition>,
    /** The retained transitions do not reach back to the first observation. */
    val transitionsGap: Boolean,
    /** No suspension was ever observed for this coroutine. */
    val firstObservedRunning: Boolean,
    /** The matched event-loop prefix, or `null` (a clue, never a verdict). */
    val eventLoopClue: String?,
    override val sortSeq: Long,
) : Incident {
    override val kind: IncidentKind get() = IncidentKind.POSSIBLY_BLOCKED
}

@Serializable
data class ObservedAge(
    val coroutineId: String,
    val name: String?,
    val ageNanos: Long,
)

@Serializable
data class Observations(
    val longLived: Capped<ObservedAge>,
    val longSuspended: Capped<ObservedAge>,
)

@Serializable
data class OriginCounts(
    val app: Int,
    val vizcore: Int,
    val unknown: Int,
    val untracked: Long,
)

@Serializable
data class VizcoreConnectionHealth(
    val captured: Int,
    val failed: Int,
    val failures: Capped<FailureEntry>,
)

/** Transport state the backend supplies (spec C-3 `transport`), echoed in the health. */
@Serializable
data class TransportStatus(
    val lostEvents: Long = 0,
    val deliveryUncertain: Boolean = false,
    val sinceSeq: Long? = null,
)

@Serializable
data class ProblemRound(
    val roundId: String,
    val anchorId: String,
    val anchorCreatedNanos: Long,
    val kinds: List<IncidentKind>,
)

/** The derived health of one session (spec C-3, the parts core computes). */
@Serializable
data class SessionHealth(
    val state: HealthState,
    val asOf: AsOf?,
    val asOfSeq: Long,
    val sessionOrigin: SessionOrigin,
    val problemCount: Int,
    val failedRoundCount: Int,
    val ungroupedFailureCount: Int,
    val possiblyBlockedCount: Int,
    val incidents: Capped<Incident>,
    val possiblyBlocked: PossiblyBlockedEvaluation,
    val observations: Observations,
    val counts: OriginCounts,
    /** Counts may be low: coroutines beyond capacity, or events lost or possibly lost on the way. */
    val failuresAtLeast: Boolean,
    val vizcoreConnection: VizcoreConnectionHealth,
    val transport: TransportStatus,
    val roundCount: Int,
    val problemRoundCount: Int,
    val problemRounds: Capped<ProblemRound>,
)

@Serializable
data class WatermarkObservation(
    val appNanos: Long,
    val appEpochMs: Long,
    val receivedEpochMs: Long,
)

sealed interface HealthMode {
    data object Live : HealthMode

    /** Replay at [atSeq]: the summaries are the fold of the retained events up to it. */
    data class Replay(
        val atSeq: Long,
    ) : HealthMode
}

/** Inputs of [SessionHealthDerivation.derive] (spec C-1). */
data class HealthInput(
    val summaries: SummaryView,
    val kind: SessionKind,
    val mode: HealthMode = HealthMode.Live,
    val watermark: WatermarkObservation? = null,
    val lastIngestEpochMs: Long? = null,
    /** The session's origin as the producer stated it (PR 2/3); `null` = infer it from the coroutines. */
    val reportedOrigin: SessionOrigin? = null,
    val transport: TransportStatus = TransportStatus(),
    val leakThresholdMs: Long = HealthThresholds.DEFAULT_LEAK_THRESHOLD_MS,
    val longSuspendedThresholdNanos: Long = HealthThresholds.LONG_SUSPENDED_NANOS,
    /** Retained transitions of one coroutine, oldest first; used for possibly blocked coroutines only. */
    val transitionsFor: (String) -> List<Transition> = { emptyList() },
)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :coroutine-viz-core:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: session health model types"
```

---

### Task 9: Failure incidents

**Files:**
- Create: `health/FailureIncidents.kt`
- Test: `health/FailureIncidentsTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/health/FailureIncidentsTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FailureIncidentsTest {
    private fun incidents(script: EventScript): List<Incident> {
        val table = script.table()
        return FailureIncidents.build(table.all.toList(), RoundAnchoring.of(table))
    }

    /** req with children a, b, c; a fails, b and c are cancelled; the parent's own terminal per [parentFirst]. */
    private fun failingRequest(parentFirst: Boolean): EventScript =
        EventScript().apply {
            created("req", at = sec(1))
            listOf("a", "b", "c").forEach { created(it, at = sec(1), parent = "req") }
            if (parentFirst) failed("req", at = sec(2))
            failed("a", at = sec(2))
            cancelled("b", at = sec(2))
            cancelled("c", at = sec(2))
            if (!parentFirst) failed("req", at = sec(3))
        }

    @Test
    fun `all failures of a round are one incident, whatever the terminal order`() {
        for (parentFirst in listOf(true, false)) {
            val incident = assertIs<FailedRoundIncident>(incidents(failingRequest(parentFirst)).single())
            assertEquals("round:req", incident.roundId)
            assertEquals(setOf("a", "req"), incident.failures.items.map { it.coroutineId }.toSet())
            assertEquals(listOf("c", "b"), incident.cancelledAfter.items, "newest first")
        }
    }

    @Test
    fun `cancellations before the first failure are not listed after it`() {
        val script = EventScript()
        script.created("req", at = 1)
        script.created("early", at = 1, parent = "req")
        script.created("a", at = 1, parent = "req")
        script.cancelled("early", at = 2)
        script.failed("a", at = 3)

        val incident = assertIs<FailedRoundIncident>(incidents(script).single())
        assertEquals(emptyList(), incident.cancelledAfter.items)
    }

    @Test
    fun `cancellations alone, and cancellation exceptions, are not problems`() {
        val script = EventScript()
        script.created("req", at = 1)
        script.created("a", at = 1, parent = "req")
        script.cancelled("a", at = 2)
        script.failed("req", at = 3, type = "kotlinx.coroutines.JobCancellationException")

        assertEquals(emptyList(), incidents(script))
    }

    @Test
    fun `a failure without resolvable ancestry is ungrouped`() {
        val script = EventScript()
        script.created("orphan", at = 1, parent = "never-seen")
        script.failed("orphan", at = 2, message = "lost")

        val incident = assertIs<UngroupedFailureIncident>(incidents(script).single())
        assertEquals("orphan", incident.failure.coroutineId)
        assertEquals("lost", incident.failure.message)
    }

    @Test
    fun `a reduced failure is still counted, with details dropped`() {
        val script = EventScript()
        script.created("req", at = 1)
        script.failed("req", at = 2)
        script.created("x", at = 3)
        script.created("y", at = 4)
        val table = script.table(fullSummaryTarget = 1)

        val incident = assertIs<FailedRoundIncident>(FailureIncidents.build(table.all.toList(), RoundAnchoring.of(table)).single())
        assertTrue(incident.failures.items.single().detailsDropped)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.FailureIncidentsTest"`
Expected: compilation FAILS (`FailureIncidents` unresolved).

- [ ] **Step 3: Implement**

`health/FailureIncidents.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

/**
 * Failed-round and ungrouped-failure incidents (spec "Incidents and the headline count"). [evaluated]
 * are the coroutines health is about (vizcore's own excluded by the caller). Counting does not depend
 * on the order terminal events were recorded in (#244).
 */
internal object FailureIncidents {
    fun build(
        evaluated: List<CoroutineSummary>,
        anchoring: RoundAnchoring,
    ): List<Incident> {
        val byAnchor = HashMap<String, MutableList<CoroutineSummary>>()
        val ungrouped = ArrayList<Incident>()
        for (s in evaluated) {
            val anchor = anchoring.anchorOf(s.id)
            when {
                anchor != null -> byAnchor.getOrPut(anchor) { mutableListOf() } += s
                s.failed -> ungrouped += UngroupedFailureIncident(id = "failure:${s.id}", failure = entry(s), sortSeq = terminalSeq(s))
            }
        }
        val rounds = byAnchor.mapNotNull { (anchor, members) -> failedRound(anchor, members) }
        return rounds + ungrouped
    }

    fun entry(s: CoroutineSummary): FailureEntry =
        FailureEntry(
            coroutineId = s.id,
            name = s.name,
            exceptionType = s.failure?.exceptionType,
            message = s.failure?.message,
            failedAtSeq = terminalSeq(s),
            detailsDropped = s.reduced,
        )

    private fun failedRound(
        anchor: String,
        members: List<CoroutineSummary>,
    ): FailedRoundIncident? {
        // Every list is newest first (spec C-3).
        val failures = members.filter { it.failed }.sortedByDescending(::terminalSeq)
        if (failures.isEmpty()) return null
        val firstFailureSeq = terminalSeq(failures.last())
        val cancelledAfter =
            members
                .filter { it.cancelled && terminalSeq(it) > firstFailureSeq }
                .sortedByDescending(::terminalSeq)
                .map { it.id }
        val roundId = RoundAnchoring.roundId(anchor)
        return FailedRoundIncident(
            id = "failed:$roundId",
            roundId = roundId,
            anchorId = anchor,
            failures = Capped.of(failures.map(::entry)),
            cancelledAfter = Capped.of(cancelledAfter),
            firstFailureSeq = firstFailureSeq,
            sortSeq = terminalSeq(failures.first()),
        )
    }

    fun terminalSeq(s: CoroutineSummary): Long = s.terminal?.seq ?: s.lastModifiedSeq
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: the command from Step 2. Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: failed-round and ungrouped-failure incidents"
```

---

### Task 10: Possibly blocked incidents

**Files:**
- Create: `health/PossiblyBlockedIncidents.kt`
- Test: `health/PossiblyBlockedTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/health/PossiblyBlockedTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PossiblyBlockedTest {
    private fun blocked(
        script: EventScript,
        watermarkNanos: Long,
    ): List<PossiblyBlockedIncident> {
        val table = script.table()
        return PossiblyBlockedIncidents.build(table.all.toList(), RoundAnchoring.of(table), watermarkNanos) { id ->
            Transitions.forCoroutine(script.events, id)
        }
    }

    private fun runningTask(threadName: String = "lettuce-nioEventLoop-5-1"): EventScript =
        EventScript().apply {
            created("task", at = sec(100), label = "POST /api/tasks")
            started("task", at = sec(100))
            thread("task", at = sec(100), name = "reactor-http-nio-2")
            suspended("task", at = sec(101))
            resumed("task", at = sec(102))
            thread("task", at = sec(102), name = threadName, dispatcher = "Dispatchers.Unconfined")
        }

    @Test
    fun `running for more than 10 s up to the watermark is possibly blocked`() {
        val incident = blocked(runningTask(), watermarkNanos = sec(112) + 1).single()

        assertEquals("task", incident.coroutineId)
        assertEquals(sec(10) + 1, incident.runningForNanos)
        assertEquals("round:task", incident.roundId)
        assertEquals("lettuce-nioEventLoop-", incident.eventLoopClue)
        assertEquals("lettuce-nioEventLoop-5-1", incident.lastThread?.threadName)
        assertEquals("Dispatchers.Unconfined", incident.dispatcher)
        assertFalse(incident.firstObservedRunning)
        assertFalse(incident.transitionsGap)
    }

    @Test
    fun `exactly 10 s is not over the threshold`() {
        assertEquals(emptyList(), blocked(runningTask(), watermarkNanos = sec(112)))
    }

    @Test
    fun `suspended and finished coroutines are never possibly blocked`() {
        val script = runningTask()
        script.suspended("task", at = sec(103))
        assertEquals(emptyList(), blocked(script, watermarkNanos = sec(200)))

        script.resumed("task", at = sec(104))
        script.completed("task", at = sec(105))
        assertEquals(emptyList(), blocked(script, watermarkNanos = sec(200)))
    }

    @Test
    fun `no clue without a thread assignment in the current run`() {
        val script = EventScript()
        script.created("task", at = sec(1))
        script.started("task", at = sec(1))
        script.thread("task", at = sec(1), name = "lettuce-nioEventLoop-5-1")
        script.suspended("task", at = sec(2))
        script.resumed("task", at = sec(3))

        val incident = blocked(script, watermarkNanos = sec(20)).single()
        assertNull(incident.lastThread)
        assertNull(incident.eventLoopClue)
    }

    @Test
    fun `each known event-loop prefix is a clue, other names are not`() {
        for (prefix in HealthThresholds.EVENT_LOOP_THREAD_PREFIXES) {
            assertEquals(prefix, blocked(runningTask("${prefix}1"), watermarkNanos = sec(200)).single().eventLoopClue)
        }
        assertNull(blocked(runningTask("DefaultDispatcher-worker-1"), watermarkNanos = sec(200)).single().eventLoopClue)
    }

    @Test
    fun `first observed running means no suspension was ever observed`() {
        val script = EventScript()
        script.created("loop", at = sec(1))
        script.started("loop", at = sec(1))

        assertTrue(blocked(script, watermarkNanos = sec(20)).single().firstObservedRunning)
    }

    @Test
    fun `transitions keep the 20 most recent and mark the gap`() {
        val script = EventScript()
        script.created("busy", at = 0)
        script.started("busy", at = 1)
        for (i in 1..15L) {
            script.suspended("busy", at = ms(i))
            script.resumed("busy", at = ms(i) + 1)
        }

        val incident = blocked(script, watermarkNanos = sec(30)).single()
        assertEquals(HealthThresholds.TRANSITIONS_CAP, incident.transitions.size)
        assertTrue(incident.transitionsGap)
        assertEquals(TransitionKind.RESUMED, incident.transitions.last().kind)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.PossiblyBlockedTest"`
Expected: compilation FAILS (`PossiblyBlockedIncidents` unresolved).

- [ ] **Step 3: Implement**

`health/PossiblyBlockedIncidents.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

/**
 * Possibly blocked incidents (spec "Problem kinds"): a coroutine whose last observed transition is a
 * start or resume, and whose time from that transition to the watermark (both app clock) is over
 * [HealthThresholds.POSSIBLY_BLOCKED_NANOS]. Only called when possibly blocked is evaluated.
 */
internal object PossiblyBlockedIncidents {
    fun build(
        evaluated: List<CoroutineSummary>,
        anchoring: RoundAnchoring,
        watermarkNanos: Long,
        transitionsFor: (String) -> List<Transition>,
    ): List<PossiblyBlockedIncident> = evaluated.mapNotNull { incident(it, anchoring, watermarkNanos, transitionsFor) }

    private fun incident(
        s: CoroutineSummary,
        anchoring: RoundAnchoring,
        watermarkNanos: Long,
        transitionsFor: (String) -> List<Transition>,
    ): PossiblyBlockedIncident? {
        val run = runStartIfRunning(s) ?: return null
        val runningFor = watermarkNanos - run.appNanos
        return if (runningFor <= HealthThresholds.POSSIBLY_BLOCKED_NANOS) {
            null
        } else {
            incidentFor(s, run, runningFor, anchoring, transitionsFor)
        }
    }

    /** The latest start/resume, when that is the last observed transition of an unfinished coroutine. */
    private fun runStartIfRunning(s: CoroutineSummary): Transition? {
        val running = s.lastTransition.kind == TransitionKind.STARTED || s.lastTransition.kind == TransitionKind.RESUMED
        return s.lastRunStart?.takeIf { running && !s.isTerminal }
    }

    private fun incidentFor(
        s: CoroutineSummary,
        run: Transition,
        runningFor: Long,
        anchoring: RoundAnchoring,
        transitionsFor: (String) -> List<Transition>,
    ): PossiblyBlockedIncident {
        val currentThread = s.lastThread?.takeIf { it.seq > run.seq }
        val clue =
            currentThread?.let { thread ->
                HealthThresholds.EVENT_LOOP_THREAD_PREFIXES.firstOrNull { thread.threadName.startsWith(it) }
            }
        val kept = transitionsFor(s.id).takeLast(HealthThresholds.TRANSITIONS_CAP)
        val anchor = anchoring.anchorOf(s.id)
        return PossiblyBlockedIncident(
            id = "blocked:${s.id}",
            coroutineId = s.id,
            name = s.name,
            roundId = anchor?.let(RoundAnchoring::roundId),
            anchorId = anchor,
            lastThread = currentThread,
            dispatcher = s.dispatcher,
            lastSuspension = s.lastSuspension,
            lastSuspensionProvenance = s.lastSuspensionProvenance,
            lastRunStart = run,
            runningForNanos = runningFor,
            transitions = kept,
            transitionsGap = kept.firstOrNull()?.seq != s.firstObserved.seq,
            firstObservedRunning = !s.everSuspended,
            eventLoopClue = clue,
            sortSeq = run.seq,
        )
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: the command from Step 2. Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: possibly blocked incidents with the event-loop clue"
```

---

### Task 11: `SessionHealthDerivation` — assembly

**Files:**
- Create: `health/SessionHealthDerivation.kt`, `health/HealthParts.kt`
- Test: `health/SessionHealthDerivationTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/health/SessionHealthDerivationTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionHealthDerivationTest {
    private val watermark = WatermarkObservation(appNanos = sec(60), appEpochMs = 1_792_000_060_000, receivedEpochMs = 1_792_000_060_100)

    private fun derive(
        script: EventScript,
        kind: SessionKind = SessionKind.ATTACHED,
        watermark: WatermarkObservation? = this.watermark,
        transport: TransportStatus = TransportStatus(),
        mode: HealthMode = HealthMode.Live,
        trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
    ): SessionHealth =
        SessionHealthDerivation.derive(
            HealthInput(
                summaries = script.table(trackedCapacity = trackedCapacity),
                kind = kind,
                mode = mode,
                watermark = watermark,
                lastIngestEpochMs = 1_792_000_059_000,
                transport = transport,
                transitionsFor = { id -> Transitions.forCoroutine(script.events, id) },
            ),
        )

    private fun quietApp(): EventScript =
        EventScript().apply {
            created("req", at = sec(1))
            started("req", at = sec(1))
            suspended("req", at = sec(2))
        }

    @Test
    fun `nothing captured yet is awaiting first data, without asOf`() {
        val health = derive(EventScript(), watermark = null)
        assertEquals(HealthState.AWAITING_FIRST_DATA, health.state)
        assertNull(health.asOf)
    }

    @Test
    fun `quiet app with a watermark is green, as of the watermark`() {
        val health = derive(quietApp())
        assertEquals(HealthState.NO_PROBLEMS, health.state)
        assertEquals(AsOf(AsOfKind.WATERMARK, sec(60), 1_792_000_060_000, 1_792_000_060_100, seq = 3), health.asOf)
        assertEquals(PossiblyBlockedEvaluation(EvaluationStatus.EVALUATED), health.possiblyBlocked)
    }

    @Test
    fun `an event after the last watermark makes the ingest the latest observation`() {
        val script = quietApp()
        script.created("late", at = sec(61))
        val health = derive(script)

        assertEquals(AsOf(AsOfKind.INGEST, sec(61), receivedEpochMs = 1_792_000_059_000, seq = 4), health.asOf)
        assertEquals(EvaluationStatus.EVALUATED, health.possiblyBlocked.status, "blocking still uses the watermark")
    }

    @Test
    fun `without a watermark possibly blocked is not evaluated and the state is not green`() {
        val health = derive(quietApp(), watermark = null)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
        assertEquals(NotEvaluatedReason.NO_WATERMARK, health.possiblyBlocked.reason)
        assertEquals(AsOfKind.INGEST, health.asOf?.kind)
        assertEquals(1_792_000_059_000, health.asOf?.receivedEpochMs)
    }

    @Test
    fun `demo sessions are app coroutines, not evaluated for blocking, as of the last event`() {
        val script = quietApp()
        script.created("legacy", at = sec(3), origin = null)
        val health = derive(script, kind = SessionKind.DEMO)

        assertEquals(NotEvaluatedReason.DEMO, health.possiblyBlocked.reason)
        assertEquals(AsOfKind.LAST_EVENT, health.asOf?.kind)
        assertEquals(SessionOrigin.REPORTED, health.sessionOrigin)
        assertEquals(2, health.counts.app)
    }

    @Test
    fun `asOf is the latest time observed up to the last seq when times arrive out of order`() {
        val script = EventScript()
        script.created("a", at = sec(61))
        script.created("b", at = sec(59))
        val ingest = AsOf(AsOfKind.INGEST, sec(61), receivedEpochMs = 1_792_000_059_000, seq = 2)

        assertEquals(AsOf(AsOfKind.LAST_EVENT, sec(61), seq = 2), derive(script, kind = SessionKind.DEMO).asOf)
        assertEquals(AsOf(AsOfKind.REPLAY_CURSOR, sec(61), seq = 2), derive(script, mode = HealthMode.Replay(atSeq = 2)).asOf)
        assertEquals(ingest, derive(script).asOf, "the 61 s event is newer than the 60 s watermark")
    }

    @Test
    fun `replay is not evaluated for blocking and is as of the cursor`() {
        val health = derive(quietApp(), mode = HealthMode.Replay(atSeq = 3))
        assertEquals(NotEvaluatedReason.REPLAY, health.possiblyBlocked.reason)
        assertEquals(AsOf(AsOfKind.REPLAY_CURSOR, sec(2), seq = 3), health.asOf)
    }

    @Test
    fun `lost events make failures at least and blocking not evaluated`() {
        val health = derive(quietApp(), transport = TransportStatus(lostEvents = 4, sinceSeq = 2))
        assertEquals(NotEvaluatedReason.EVENTS_DROPPED, health.possiblyBlocked.reason)
        assertTrue(health.failuresAtLeast)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
    }

    @Test
    fun `lost events are reported as such even without a watermark`() {
        val health = derive(quietApp(), watermark = null, transport = TransportStatus(deliveryUncertain = true, sinceSeq = 1))
        assertEquals(NotEvaluatedReason.EVENTS_DROPPED, health.possiblyBlocked.reason)
    }

    @Test
    fun `overflow counts untracked coroutines and is never green`() {
        val script = quietApp()
        script.created("extra", at = sec(3))
        val health = derive(script, trackedCapacity = 1)

        assertEquals(1, health.counts.untracked)
        assertEquals(NotEvaluatedReason.OVERFLOW, health.possiblyBlocked.reason)
        assertTrue(health.failuresAtLeast)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
    }

    @Test
    fun `vizcore coroutines are excluded from problems and counted as the connection`() {
        val script = quietApp()
        script.created("cio-reader", at = sec(1), origin = CoroutineOrigin.VIZCORE)
        script.failed("cio-reader", at = sec(2))
        val health = derive(script)

        assertEquals(0, health.problemCount)
        assertEquals(1, health.vizcoreConnection.captured)
        assertEquals(1, health.vizcoreConnection.failed)
        assertEquals(listOf("cio-reader"), health.vizcoreConnection.failures.items.map { it.coroutineId })
        assertEquals(OriginCounts(app = 1, vizcore = 1, unknown = 0, untracked = 0), health.counts)
    }

    @Test
    fun `only vizcore coroutines captured is the no-app-coroutines state`() {
        val script = EventScript()
        script.created("cio-reader", at = sec(1), origin = CoroutineOrigin.VIZCORE)
        assertEquals(HealthState.NO_APP_COROUTINES, derive(script).state)
    }

    @Test
    fun `a stated origin with only watermarks so far is no app coroutines, not origin unknown`() {
        val script = EventScript()
        script.watermark(at = sec(1))
        val health =
            SessionHealthDerivation.derive(
                HealthInput(
                    summaries = script.table(),
                    kind = SessionKind.ATTACHED,
                    watermark = watermark,
                    reportedOrigin = SessionOrigin.REPORTED,
                ),
            )

        assertEquals(SessionOrigin.REPORTED, health.sessionOrigin)
        assertEquals(HealthState.NO_APP_COROUTINES, health.state)
        assertEquals(AsOf(AsOfKind.WATERMARK, sec(60), 1_792_000_060_000, 1_792_000_060_100, seq = 1), health.asOf)
    }

    @Test
    fun `without a stated or reported origin, nothing captured is not claimed as no app coroutines`() {
        val script = EventScript()
        script.watermark(at = sec(1))
        val health = derive(script)

        assertEquals(SessionOrigin.UNKNOWN, health.sessionOrigin)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
    }

    @Test
    fun `untracked coroutines are never claimed as no app coroutines`() {
        val script = EventScript()
        script.created("cio-reader", at = sec(1), origin = CoroutineOrigin.VIZCORE)
        script.created("req", at = sec(2))
        val health = derive(script, trackedCapacity = 1)

        assertEquals(1, health.counts.untracked)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
    }

    @Test
    fun `without any reported origin every coroutine is unknown and evaluated`() {
        val script = EventScript()
        script.created("a", at = sec(1), origin = null)
        script.failed("a", at = sec(2))
        val health = derive(script)

        assertEquals(SessionOrigin.UNKNOWN, health.sessionOrigin)
        assertEquals(OriginCounts(app = 0, vizcore = 0, unknown = 1, untracked = 0), health.counts)
        assertEquals(1, health.problemCount)
    }

    @Test
    fun `a failed round with a possibly blocked sibling is two incidents in one problem round, failures first`() {
        val script = EventScript()
        script.created("req", at = sec(1))
        script.created("a", at = sec(1), parent = "req")
        script.created("b", at = sec(1), parent = "req")
        script.started("b", at = sec(1))
        script.failed("a", at = sec(2))
        val health = derive(script)

        assertEquals(2, health.problemCount)
        assertIs<FailedRoundIncident>(health.incidents.items[0])
        assertIs<PossiblyBlockedIncident>(health.incidents.items[1])
        val round = ProblemRound("round:req", "req", sec(1), listOf(IncidentKind.FAILED, IncidentKind.POSSIBLY_BLOCKED))
        assertEquals(listOf(round), health.problemRounds.items)
        assertEquals(1, health.roundCount)
    }

    @Test
    fun `long-lived and long-suspended are observations with ages frozen at asOf`() {
        val script = EventScript()
        script.created("stream", at = sec(1))
        script.started("stream", at = sec(1))
        script.suspended("stream", at = sec(20))
        val health = derive(script)

        assertEquals(0, health.problemCount)
        assertEquals(listOf(ObservedAge("stream", "stream", sec(59))), health.observations.longLived.items)
        assertEquals(listOf(ObservedAge("stream", "stream", sec(40))), health.observations.longSuspended.items)
    }

    @Test
    fun `without a watermark an age ends at the coroutine's own last event`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        script.started("a", at = sec(1))
        script.created("b", at = sec(100))
        val health = derive(script, watermark = null)

        assertEquals(emptyList(), health.observations.longLived.items, "b's activity is no evidence about a")
    }

    @Test
    fun `thresholds are inputs`() {
        val script = EventScript()
        script.created("stream", at = sec(1))
        script.suspended("stream", at = sec(50))
        val health =
            SessionHealthDerivation.derive(
                HealthInput(
                    summaries = script.table(),
                    kind = SessionKind.ATTACHED,
                    watermark = watermark,
                    leakThresholdMs = 120_000,
                    longSuspendedThresholdNanos = sec(5),
                ),
            )

        assertEquals(emptyList(), health.observations.longLived.items, "59 s is under a 120 s leak threshold")
        assertEquals(listOf("stream"), health.observations.longSuspended.items.map { it.coroutineId })
    }

    @Test
    fun `lists are capped at 50 with their total`() {
        val script = EventScript()
        for (i in 1..60) {
            script.created("o$i", at = sec(1), parent = "never-seen")
            script.failed("o$i", at = sec(2))
        }
        val health = derive(script)

        assertEquals(60, health.problemCount)
        assertEquals(50, health.incidents.items.size)
        assertEquals(60, health.incidents.total)
    }

    @Test
    fun `health round-trips through appJson`() {
        val script = quietApp()
        script.created("a", at = sec(1), parent = "req")
        script.failed("a", at = sec(2))
        val health = derive(script)

        val json = appJson.encodeToString(SessionHealth.serializer(), health)
        assertEquals(health, appJson.decodeFromString(SessionHealth.serializer(), json))
        assertFalse(health.failuresAtLeast)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.SessionHealthDerivationTest"`
Expected: compilation FAILS (`SessionHealthDerivation` unresolved).

- [ ] **Step 3: Implement**

`health/SessionHealthDerivation.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin

/**
 * The core health derivation (spec C-1): pure, table-tested, used by the backend for live, replay and
 * shared reads. It absorbs the plugin's ProblemDerivation domain (real-exception rule, ordering) and
 * RoundGrouping's anchoring; presentation stays in each client.
 */
object SessionHealthDerivation {
    private val INCIDENT_ORDER: Comparator<Incident> =
        compareBy<Incident> { it.kind.ordinal }.thenByDescending { it.sortSeq }

    fun derive(input: HealthInput): SessionHealth {
        val view = input.summaries
        val demo = input.kind == SessionKind.DEMO
        val sessionOrigin = input.reportedOrigin ?: sessionOriginOf(view, demo)
        val originOf = { s: CoroutineSummary -> effectiveOrigin(s, demo, sessionOrigin) }
        val (vizcore, evaluated) = view.all.partition { originOf(it) == CoroutineOrigin.VIZCORE }
        val anchoring = RoundAnchoring.of(view)
        val asOf = resolveAsOf(input)
        val evaluation = evaluatePossiblyBlocked(input)
        val incidents = incidentsOf(input, evaluated, anchoring, evaluation)
        val failuresAtLeast = view.untrackedCount > 0 || input.transport.lostEvents > 0 || input.transport.deliveryUncertain
        val problemRounds = HealthParts.problemRounds(incidents, view)

        return SessionHealth(
            state =
                stateOf(
                    input = input,
                    problemCount = incidents.size,
                    // Spec: never claimed with origin UNKNOWN, nor when coroutines went untracked.
                    noAppCoroutines =
                        evaluated.isEmpty() && sessionOrigin == SessionOrigin.REPORTED && view.untrackedCount == 0L,
                    // Green needs something evaluated, both kinds evaluated, and complete counts.
                    green = evaluated.isNotEmpty() && evaluation.status == EvaluationStatus.EVALUATED && !failuresAtLeast,
                ),
            asOf = asOf,
            asOfSeq = asOf?.seq ?: 0L,
            sessionOrigin = sessionOrigin,
            problemCount = incidents.size,
            failedRoundCount = incidents.count { it is FailedRoundIncident },
            ungroupedFailureCount = incidents.count { it is UngroupedFailureIncident },
            possiblyBlockedCount = incidents.count { it is PossiblyBlockedIncident },
            incidents = Capped.of(incidents),
            possiblyBlocked = evaluation,
            observations = HealthParts.observations(evaluated, liveWatermarkNanos(input), input),
            counts = HealthParts.originCounts(view, originOf),
            failuresAtLeast = failuresAtLeast,
            vizcoreConnection = HealthParts.vizcoreConnection(vizcore),
            transport = input.transport,
            roundCount = anchoring.anchorIds.count { id -> view.get(id)?.let(originOf) != CoroutineOrigin.VIZCORE },
            problemRoundCount = problemRounds.size,
            problemRounds = Capped.of(problemRounds),
        )
    }

    private fun incidentsOf(
        input: HealthInput,
        evaluated: List<CoroutineSummary>,
        anchoring: RoundAnchoring,
        evaluation: PossiblyBlockedEvaluation,
    ): List<Incident> {
        val watermark = input.watermark
        val blocked =
            if (evaluation.status == EvaluationStatus.EVALUATED && watermark != null) {
                PossiblyBlockedIncidents.build(evaluated, anchoring, watermark.appNanos, input.transitionsFor)
            } else {
                emptyList()
            }
        return (FailureIncidents.build(evaluated, anchoring) + blocked).sortedWith(INCIDENT_ORDER)
    }

    /** The watermark observes every coroutine of a live attached app; demo and replay have none. */
    private fun liveWatermarkNanos(input: HealthInput): Long? =
        input.watermark?.appNanos?.takeIf { input.mode == HealthMode.Live && input.kind == SessionKind.ATTACHED }

    private fun sessionOriginOf(
        view: SummaryView,
        demo: Boolean,
    ): SessionOrigin {
        val reported = view.all.any { it.origin == CoroutineOrigin.APP || it.origin == CoroutineOrigin.VIZCORE }
        return if (demo || reported) SessionOrigin.REPORTED else SessionOrigin.UNKNOWN
    }

    private fun effectiveOrigin(
        s: CoroutineSummary,
        demo: Boolean,
        sessionOrigin: SessionOrigin,
    ): CoroutineOrigin =
        when {
            demo -> CoroutineOrigin.APP
            sessionOrigin == SessionOrigin.UNKNOWN -> CoroutineOrigin.UNKNOWN
            else -> s.origin ?: CoroutineOrigin.UNKNOWN
        }

    private fun resolveAsOf(input: HealthInput): AsOf? {
        val view = input.summaries
        val lastNanos = view.latestEventAppNanos
        val mode = input.mode
        if (mode is HealthMode.Replay) return lastNanos?.let { AsOf(AsOfKind.REPLAY_CURSOR, it, seq = mode.atSeq) }
        val watermark = input.watermark
        return when {
            input.kind == SessionKind.DEMO -> lastNanos?.let { AsOf(AsOfKind.LAST_EVENT, it, seq = view.lastEventSeq) }
            // The watermark is the latest observation unless an event arrived after it (both app clock).
            watermark != null && watermark.appNanos >= (lastNanos ?: Long.MIN_VALUE) ->
                AsOf(AsOfKind.WATERMARK, watermark.appNanos, watermark.appEpochMs, watermark.receivedEpochMs, view.lastEventSeq)
            else -> lastNanos?.let { AsOf(AsOfKind.INGEST, it, receivedEpochMs = input.lastIngestEpochMs, seq = view.lastEventSeq) }
        }
    }

    private fun evaluatePossiblyBlocked(input: HealthInput): PossiblyBlockedEvaluation {
        val reason =
            when {
                input.mode is HealthMode.Replay -> NotEvaluatedReason.REPLAY
                input.kind == SessionKind.DEMO -> NotEvaluatedReason.DEMO
                // Sticky for the rest of the session (spec "Health strip states"), so before NO_WATERMARK.
                input.transport.lostEvents > 0 || input.transport.deliveryUncertain -> NotEvaluatedReason.EVENTS_DROPPED
                input.summaries.untrackedCount > 0 -> NotEvaluatedReason.OVERFLOW
                input.watermark == null -> NotEvaluatedReason.NO_WATERMARK
                else -> null
            }
        return if (reason == null) {
            PossiblyBlockedEvaluation(EvaluationStatus.EVALUATED)
        } else {
            PossiblyBlockedEvaluation(EvaluationStatus.NOT_EVALUATED, reason)
        }
    }

    private fun stateOf(
        input: HealthInput,
        problemCount: Int,
        noAppCoroutines: Boolean,
        green: Boolean,
    ): HealthState {
        val noEvents = input.summaries.all.isEmpty() && input.summaries.lastEventSeq == 0L
        val noData = noEvents && input.watermark == null && input.mode == HealthMode.Live
        return when {
            noData -> HealthState.AWAITING_FIRST_DATA
            problemCount > 0 -> HealthState.PROBLEMS
            noAppCoroutines -> HealthState.NO_APP_COROUTINES
            green -> HealthState.NO_PROBLEMS
            else -> HealthState.NO_FAILURES_PARTIAL
        }
    }
}
```

`health/HealthParts.kt` (the derivation's smaller pieces, kept out of the main object so each stays short):

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin

internal object HealthParts {
    private const val NANOS_PER_MS = 1_000_000L

    /**
     * Long-lived and long-suspended observations (spec "Lifetimes"): an active coroutine's age ends at the
     * latest watermark when there is one ([watermarkNanos], which observes every coroutine of a live app),
     * otherwise at its own last observation, never at another coroutine's activity.
     */
    fun observations(
        evaluated: List<CoroutineSummary>,
        watermarkNanos: Long?,
        input: HealthInput,
    ): Observations {
        val leakNanos = input.leakThresholdMs * NANOS_PER_MS
        val active = evaluated.filter { !it.isTerminal }
        val end = { s: CoroutineSummary -> maxOf(watermarkNanos ?: Long.MIN_VALUE, s.lastObservedNanos) }
        // Newest first (spec C-3): by when the coroutine was first observed / last suspended.
        val longLived =
            active
                .filter { end(it) - it.firstObserved.appNanos > leakNanos }
                .sortedByDescending { it.firstObserved.seq }
                .map { ObservedAge(it.id, it.name, end(it) - it.firstObserved.appNanos) }
        val longSuspended =
            active
                .filter { it.lastTransition.kind == TransitionKind.SUSPENDED }
                .filter { end(it) - it.lastTransition.appNanos > input.longSuspendedThresholdNanos }
                .sortedByDescending { it.lastTransition.seq }
                .map { ObservedAge(it.id, it.name, end(it) - it.lastTransition.appNanos) }
        return Observations(Capped.of(longLived), Capped.of(longSuspended))
    }

    fun originCounts(
        view: SummaryView,
        originOf: (CoroutineSummary) -> CoroutineOrigin,
    ): OriginCounts {
        val byOrigin = view.all.groupingBy(originOf).eachCount()
        return OriginCounts(
            app = byOrigin[CoroutineOrigin.APP] ?: 0,
            vizcore = byOrigin[CoroutineOrigin.VIZCORE] ?: 0,
            unknown = byOrigin[CoroutineOrigin.UNKNOWN] ?: 0,
            untracked = view.untrackedCount,
        )
    }

    fun vizcoreConnection(vizcore: List<CoroutineSummary>): VizcoreConnectionHealth {
        val failed = vizcore.filter { it.failed }.sortedByDescending(FailureIncidents::terminalSeq)
        return VizcoreConnectionHealth(
            captured = vizcore.size,
            failed = failed.size,
            failures = Capped.of(failed.map(FailureIncidents::entry)),
        )
    }

    /** Rounds holding at least one incident, newest anchor first, with the incident kinds they hold. */
    fun problemRounds(
        incidents: List<Incident>,
        view: SummaryView,
    ): List<ProblemRound> {
        val kindsByAnchor = LinkedHashMap<String, MutableSet<IncidentKind>>()
        for (incident in incidents) {
            val anchor =
                when (incident) {
                    is FailedRoundIncident -> incident.anchorId
                    is PossiblyBlockedIncident -> incident.anchorId
                    is UngroupedFailureIncident -> null
                } ?: continue
            kindsByAnchor.getOrPut(anchor) { sortedSetOf() } += incident.kind
        }
        return kindsByAnchor
            .map { (anchor, kinds) ->
                ProblemRound(
                    roundId = RoundAnchoring.roundId(anchor),
                    anchorId = anchor,
                    anchorCreatedNanos = view.get(anchor)?.firstObserved?.appNanos ?: 0L,
                    kinds = kinds.toList(),
                )
            }.sortedWith(
                compareByDescending<ProblemRound> { it.anchorCreatedNanos }
                    .thenByDescending { view.get(it.anchorId)?.firstObserved?.seq ?: 0L },
            )
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: the command from Step 2. Expected: PASS (22 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: SessionHealthDerivation assembles the session health"
```

---

### Task 12: Replay fold

**Files:**
- Create: `health/SummaryFold.kt`
- Test: `health/SummaryFoldTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/health/SummaryFoldTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SummaryFoldTest {
    @Test
    fun `health at a seq sees only the events up to it`() {
        val script = EventScript()
        script.created("req", at = sec(1))
        script.created("a", at = sec(1), parent = "req")
        val beforeFailure = script.lastSeq
        script.failed("a", at = sec(2))

        fun healthAt(seq: Long) =
            SessionHealthDerivation.derive(
                HealthInput(
                    summaries = SummaryFold.foldTo(script.events, seq),
                    kind = SessionKind.ATTACHED,
                    mode = HealthMode.Replay(seq),
                ),
            )

        assertEquals(0, healthAt(beforeFailure).problemCount)
        val after = healthAt(script.lastSeq)
        assertEquals(1, after.problemCount)
        assertIs<FailedRoundIncident>(after.incidents.items.single())
        assertEquals(script.lastSeq, after.asOfSeq)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.SummaryFoldTest"`
Expected: compilation FAILS (`SummaryFold` unresolved).

- [ ] **Step 3: Implement**

`health/SummaryFold.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.VizEvent

/**
 * Replay fold (spec "Modes", Replay): transient summaries from the retained events up to [atSeq], with
 * the same updater as the live table. Ancestry and failures from trimmed events come from the durable
 * summaries in the backend (PR 2), not from here.
 */
object SummaryFold {
    fun foldTo(
        events: Iterable<VizEvent>,
        atSeq: Long,
    ): SummaryTable {
        val table = SummaryTable()
        for (event in events) {
            if (event.seq > atSeq) break
            table.apply(event)
        }
        return table
    }
}
```

The `break` relies on `events` being in seq order, which `EventStoreInterface.all()` guarantees (store
order == seq order, `VizSession` doc).

- [ ] **Step 4: Run it to verify it passes**

Run: the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: replay fold of summaries up to a sequence number"
```

---

### Task 13: The three sketch boards as fixtures

The sketch's boards are the acceptance shapes for the whole redesign (spec "Testing", end to end). These
core fixtures pin what the derivation says for each; PR 2's HTTP tests and PR 4's frontend tests reuse
the same shapes.

**Files:**
- Test: `health/SketchBoardsTest.kt`

- [ ] **Step 1: Write the test**

`src/test/kotlin/com/jh/proj/coroutineviz/health/SketchBoardsTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The three boards of the design canvas "vizcore session page — triage-first sketch". */
class SketchBoardsTest {
    private fun EventScript.vizcoreLeftovers(names: List<String>) {
        names.forEach { name ->
            created(name, at = sec(0), origin = CoroutineOrigin.VIZCORE)
            started(name, at = sec(0))
        }
    }

    private fun derive(
        script: EventScript,
        watermark: WatermarkObservation?,
    ) = SessionHealthDerivation.derive(
        HealthInput(
            summaries = script.table(),
            kind = SessionKind.ATTACHED,
            watermark = watermark,
            lastIngestEpochMs = 1_792_000_000_000,
            transitionsFor = { id -> Transitions.forCoroutine(script.events, id) },
        ),
    )

    @Test
    fun `board 1, a request failed`() {
        val script = EventScript()
        script.vizcoreLeftovers(listOf("cio-from-nio-reader", "cio-to-nio-writer", "nonce-generator"))
        script.created("req", at = sec(10), label = "GET /api/uat/dashboard")
        script.started("req", at = sec(10))
        val parts = listOf("dashboard.tasks", "dashboard.notifications", "dashboard.pets", "dashboard.failing-part")
        parts.forEach { part ->
            script.created(part, at = sec(10), parent = "req")
            script.started(part, at = sec(10))
            script.suspended(part, at = sec(10) + ms(5))
        }
        script.failed("dashboard.failing-part", at = sec(11), message = "dashboard part failed")
        listOf("dashboard.tasks", "dashboard.notifications", "dashboard.pets").forEach {
            script.cancelled(it, at = sec(11) + ms(1))
        }
        script.failed("req", at = sec(11) + ms(2), message = "dashboard part failed")

        val health = derive(script, WatermarkObservation(sec(12), 1_792_000_012_000, 1_792_000_012_050))

        assertEquals(HealthState.PROBLEMS, health.state)
        assertEquals(1, health.problemCount)
        val incident = assertIs<FailedRoundIncident>(health.incidents.items.single())
        assertEquals(listOf("req", "dashboard.failing-part"), incident.failures.items.map { it.coroutineId })
        assertEquals(3, incident.cancelledAfter.total)
        assertEquals(VizcoreConnectionHealth(3, 0, Capped(emptyList(), 0)), health.vizcoreConnection)
        assertEquals(OriginCounts(app = 5, vizcore = 3, unknown = 0, untracked = 0), health.counts)
        assertEquals(emptyList(), health.observations.longLived.items)
    }

    @Test
    fun `board 2, a coroutine may be blocking a thread`() {
        val script = EventScript()
        script.created("task", at = sec(100), label = "POST /api/tasks")
        script.started("task", at = sec(100))
        script.thread("task", at = sec(100), name = "reactor-http-nio-2")
        script.suspended("task", at = sec(101))
        script.resumed("task", at = sec(102))
        script.thread("task", at = sec(102), name = "lettuce-nioEventLoop-5-1", dispatcher = "Dispatchers.Unconfined")
        listOf("req-a", "req-b").forEach { id ->
            script.created(id, at = sec(103))
            script.started(id, at = sec(103))
            script.suspended(id, at = sec(103))
        }

        val health = derive(script, WatermarkObservation(sec(114), 1_792_000_114_000, 1_792_000_114_020))

        assertEquals(1, health.problemCount)
        val incident = assertIs<PossiblyBlockedIncident>(health.incidents.items.single())
        assertEquals("POST /api/tasks", incident.name)
        assertEquals("lettuce-nioEventLoop-", incident.eventLoopClue)
        assertEquals(sec(12), incident.runningForNanos)
        val round = ProblemRound("round:task", "task", sec(100), listOf(IncidentKind.POSSIBLY_BLOCKED))
        assertEquals(listOf(round), health.problemRounds.items)
    }

    @Test
    fun `board 3, no app coroutines captured, app disconnected`() {
        val script = EventScript()
        script.vizcoreLeftovers(listOf("cio-from-nio-reader", "cio-to-nio-writer", "cio-request-timeout", "nonce-generator", "ws-pinger"))

        val health = derive(script, watermark = null)

        assertEquals(HealthState.NO_APP_COROUTINES, health.state)
        assertEquals(0, health.problemCount)
        assertEquals(5, health.vizcoreConnection.captured)
        assertEquals(0, health.vizcoreConnection.failed)
        assertEquals(NotEvaluatedReason.NO_WATERMARK, health.possiblyBlocked.reason)
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.SketchBoardsTest"`
Expected: PASS (3 tests). These pin behaviour already built; if one fails, the derivation and the spec
disagree — fix the derivation, not the fixture, unless the fixture contradicts the spec.

- [ ] **Step 3: Commit**

```bash
git add backend/coroutine-viz-core/src
git commit -m "core: sketch boards as health derivation fixtures"
```

---

### Task 14: `VizSession` keeps the summaries

**Files:**
- Modify: `session/VizSession.kt` (constructor; fields near `applier` ~line 126; `append` ~line 230; `rehydrateFromStore` ~line 260)
- Modify: `backend/coroutine-viz-client/src/main/kotlin/com/jh/proj/coroutineviz/client/VizcoreClient.kt` (~line 349)
- Test: `session/VizSessionSummariesTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jh/proj/coroutineviz/session/VizSessionSummariesTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.health.EventScript
import com.jh.proj.coroutineviz.health.sec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VizSessionSummariesTest {
    private fun script(sessionId: String): EventScript =
        EventScript(sessionId).apply {
            created("req", at = sec(1))
            created("a", at = sec(1), parent = "req")
            failed("a", at = sec(2))
        }

    @Test
    fun `sent events update the summaries`() {
        val session = VizSession("summaries-send")
        script(session.sessionId).events.forEach(session::send)

        assertEquals(true, session.readSummaries { it.get("a")?.failed })
        assertEquals(3, session.readSummaries { it.lastEventSeq })
        session.close()
    }

    @Test
    fun `rehydrating from a store rebuilds the summaries`() {
        val store = EventStore()
        script("summaries-rehydrate").events.forEach(store::record)
        val session = VizSession("summaries-rehydrate", eventStoreFactory = { store })

        session.rehydrateFromStore()

        assertEquals(true, session.readSummaries { it.get("a")?.failed })
        assertEquals("req", session.readSummaries { it.get("a")?.parentId })
        session.close()
    }

    @Test
    fun `a session without summaries keeps no table`() {
        val session = VizSession("summaries-off", keepSummaries = false)
        script(session.sessionId).events.forEach(session::send)

        assertFailsWith<IllegalStateException> { session.readSummaries { it.lastEventSeq } }
        session.close()
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.session.VizSessionSummariesTest"`
Expected: compilation FAILS (`readSummaries` unresolved). If `EventStore.record` has a different name,
use the method `VizSession.append` calls on `store` (`store.record(event)` today).

- [ ] **Step 3: Implement**

In `session/VizSession.kt`:

1. Imports: `com.jh.proj.coroutineviz.health.SummaryTable`, `com.jh.proj.coroutineviz.health.SummaryView`.
2. Add a constructor parameter right **before** `eventStoreFactory` (after `createdAtMs`).
   `eventStoreFactory` must stay last: `SseSubscriptionOrderTest` (backend) passes it as a trailing lambda.
   No caller passes more than the first argument positionally, so the insertion is safe.

```kotlin
    /**
     * Whether to keep the per-coroutine summary table (spec 2026-10-21, C-2). The backend reads it; the
     * SDK's in-app session never does, so it passes `false` and spares the app the memory and the work.
     */
    keepSummaries: Boolean = true,
```

3. Next to `private val applier = EventApplier(snapshot)`:

```kotlin
    // Durable per-coroutine summaries (spec 2026-10-21, C-2); guarded by [sendLock] like the snapshot.
    private val summaryTable: SummaryTable? = if (keepSummaries) SummaryTable() else null
```

`sendLock` is declared below `applier`; declare `summaryTable` after `sendLock` if the compiler
complains about initialization order (it does not read it at construction, so either place compiles).

4. In `append`, right after `applier.apply(event)`:

```kotlin
            summaryTable?.apply(event)
```

5. In `rehydrateFromStore`, change `events.forEach { applier.apply(it) }` to:

```kotlin
            events.forEach {
                applier.apply(it)
                summaryTable?.apply(it)
            }
```

6. Add, next to `nextSeq()`:

```kotlin
    /**
     * Read the session's summaries under the send lock, so a read never sees a half-applied event.
     * Keep [block] short: it holds up event ingestion while it runs.
     *
     * @throws IllegalStateException if the session was created with `keepSummaries = false`.
     */
    fun <T> readSummaries(block: (SummaryView) -> T): T {
        val table = checkNotNull(summaryTable) { "session $sessionId keeps no summaries" }
        return synchronized(sendLock) { block(table) }
    }
```

7. In `VizcoreClient.kt` (`coroutine-viz-client`), the SDK's own session:

```kotlin
            val session = VizSession(sessionId = serverSessionId, keepSummaries = false)
```

- [ ] **Step 4: Run it to verify it passes**

Run: the command from Step 2. Expected: PASS (3 tests). Then
`./gradlew :coroutine-viz-client:test --rerun` (the client compiles against the new parameter; `--rerun`
must stay right after that task). Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core/src backend/coroutine-viz-client/src
git commit -m "core: VizSession keeps durable coroutine summaries"
```

---

### Task 15: Whole-module verification, review, PR

- [ ] **Step 1: Run the full core suite and the dependent modules**

Run (from `backend/`):

```bash
./gradlew :coroutine-viz-core:test --rerun :coroutine-viz-client:test :coroutine-viz-agent:test test
```

Expected: all green. The backend app (`test`) and the SDK modules compile against the new optional
fields; if a test compares a `CoroutineCreated`/`CoroutineSuspended` with `toString()` or a fixed JSON
string, update that expectation to include `"origin":null` / `"provenance":null` (`encodeDefaults = true`).

- [ ] **Step 2: Lint the backend app as CI does**

Run: `./gradlew ktlintCheck detekt`
Expected: BUILD SUCCESSFUL. This covers the backend app project only (core applies neither plugin), so it
mainly confirms the app still lints with the new core types. Review the new core files by eye against
`backend/.editorconfig` (line length 140, `ktlint_official` layout); do not add lint plugins to core in
this PR.

- [ ] **Step 3: The IntelliJ plugin still compiles against core**

Run: `./gradlew -Pvizcore.withPlugin=true :intellij-plugin:compileKotlin`
Expected: BUILD SUCCESSFUL. The plugin is not changed, but it compiles against core; its CI workflow
(`ci-plugin.yml`) only runs on manual dispatch, so this is the only check before merge. The first run
downloads the IntelliJ platform; if that is impossible locally, say so in the PR body.

- [ ] **Step 4: Codex review until clean**

```bash
codex exec -s read-only --ephemeral -C "$PWD/.." -o <scratchpad>/codex-health-core-r1.md \
  "Review the commits on this branch since origin/main (git log origin/main..HEAD, git diff origin/main...HEAD) against docs/superpowers/specs/2026-10-21-session-triage-design.md sections Definitions, C-1 and C-2. Look for rule mismatches with the spec, wrong edge cases (terminal order, promotion stickiness, capacity, reduction, provenance, clue), thread-safety of VizSession.readSummaries, and missing tests. Report concrete findings with file:line, or say 'No findings'." < /dev/null
```

Fix each finding (test first where it is a behaviour), re-run Steps 1–2, and **commit the fix**
(`git add backend docs && git commit -m "core: <what the review found>"`) before the next round, because
the review reads committed changes. Repeat with `-r2`, `-r3`, … until Codex reports no findings.

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin feat/health-core
gh pr create -R Kotlin-server-squad/vizcore --base main --head feat/health-core \
  --title "Core: session health derivation and durable coroutine summaries" \
  --body-file <scratchpad>/pr-health-core.md
```

PR body (neutral wording, public repo): what the core now owns (summaries, rounds, health), that nothing
is served yet (PR 2), the wire additions and their compatibility (optional fields; `PollWatermark` is a
new event type that older backends cannot decode, handled on the SDK side in PR 3), test counts, and
"Part of the session triage redesign (spec docs/superpowers/specs/2026-10-21-session-triage-design.md)".
End with the 🤖 Generated with Claude Code line.

- [ ] **Step 6: Wait for CI**

Run: `gh pr checks <n> -R Kotlin-server-squad/vizcore --watch`
Expected: all checks pass. The user merges.
