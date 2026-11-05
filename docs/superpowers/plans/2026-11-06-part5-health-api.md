# Part 5a — Health API: live and replay health, ingest state, session facts — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve the core's session health over HTTP: accept the poll watermark at ingest as transport state, keep a sticky
transport status and the app connection state per session, expose `GET /api/sessions/{id}/health[?atSeq=N][&leakThresholdMs=M]`,
add the triage C-3 fields to `GET /api/sessions/{id}`, and forward watermarks on the SSE stream.

**Architecture:** A `PollWatermark` frame is transport state, not a session event: the ingest route hands it to a new
id-keyed registry `IngestStates` (latest watermark, last event receipt time, transport status, clean or unclean close),
which also publishes it to SSE streams. The health route reads that state first, copies the session's durable summaries
under the send lock (`readSummaries { it.snapshot() }`), and runs `SessionHealthDerivation` outside the lock. Replay
(`atSeq`) folds the retained events up to the cursor, seeded from the durable summaries for coroutines whose creation
event was trimmed. Database sessions fold their summaries from the stored events on every rebuild; when the store was
trimmed the health says so ("at least", possibly blocked not evaluated) until Part 5b persists the summaries.

**Tech Stack:** Kotlin 2.4, Ktor 3 (routing, SSE, WebSockets, test host), kotlinx.serialization (`appJson`), Exposed + H2
(tests), JUnit 5 + `kotlin.test`, Gradle (`backend/gradlew`), ktlint, detekt; `openapi-typescript` for `shared/api-types`.

**Specs:**
- `docs/superpowers/specs/2026-10-21-session-triage-design.md` (on `main`): honesty rules H-1..H-4, "Definitions",
  "Health strip states", "Modes", C-1..C-4, C-7 (what the backend must accept), "Delivery" row 2, "Testing" (Backend).
- `docs/superpowers/specs/2026-11-05-session-dock-design.md` (branch `origin/docs/session-dock-design`): Part 5 = triage
  PR 2; S-3 (app connection pill from the ingest state), S-8 (Problems tab from `GET …/health`), S-11 (replay reads bound
  to `atSeq`), S-12 (rounds and find).

---

## Scope decision: Part 5 is split in two

Triage PR 2 is C-3 to C-6 in one go: ingest, the health endpoint with replay, rounds and server-side search, `health/items`,
`hierarchy` round fields, the `asOfSeq` binding with `STALE`, database persistence of summaries and ingest state, checks
persistence and shares. An earlier attempt to plan just its first third (`feat/health-backend`, 27 review rounds, ~9,500
lines, never built) shows one PR cannot hold it. It splits along the seam "what a live page needs to show health" versus
"what makes every other read agree with it":

| PR | Content | Depends on |
|---|---|---|
| **5a (this plan)** | Ingest of the new fields (watermark as transport state, clean and unclean close, malformed frames; origin and provenance already decode), app connection state, `GET …/health` live and with `atSeq` (replay seeded from the durable summaries), the C-3 fields on `GET /api/sessions/{id}`, watermark frames on SSE, database sessions folding their summaries (honest "at least" once trimmed), OpenAPI and `shared/api-types` | triage PR 1 (#248, merged) |
| 5b | `GET …/rounds?cursor=&limit=&q=[&problemsOnly][&atSeq]` (server-side find), `…/health/items`, `hierarchy` C-2 fields + `roundId` + `?roundId=`/`?atSeq=`, `asOfSeq`/`asOfWatermark` binding with `STALE` on follow-up reads, `/metrics` by origin with frozen leak ages; C-4 persistence (summaries and ingest state on the session row, written every 5 s and on close, "ingest open" marker swept at startup, retention folding events into the persisted summaries before it trims); C-5 checks persistence; C-6 shares (share payload under one cut, `GET /api/shared/{token}/health`, `…/health/items`, `…/rounds`, `…/hierarchy`) | 5a |

5b may split again (reads 5b-1, persistence/checks/shares 5b-2) when its plan is written from the code as 5a leaves it.

**Each is shippable alone.** 5a is the API foundation: every response it adds is honest on its own (memory mode, the
default, fully; a database session answers `503 history-trimmed` once its stored events were trimmed, and reads
delivery-uncertain for events this process did not watch arrive). It is not everything the frontend needs: the
Problems tab's first lines, chips, the first 50 incidents and the connection pill (Part 7's S-3/S-8 core) can be built on
it, but "Show" into a round, the C-2 card fields, lists beyond the first 50, the checks freshness and the shared view
need 5b, and Part 7 as specified also depends on Part 6 (rounds). Full Problems activation therefore waits for 5b.

## Decisions this plan takes (spec gaps; the PR lists them for the user)

| # | Gap | Decision |
|---|---|---|
| D-1 | How a watermark travels | Not stored, not numbered, not applied to the summaries: one per second would evict real events from the bounded store (10,000 in memory). The ingest route hands it to `IngestStates`; SSE gets it from there as a named frame `event: watermark`, data `{"appNanos","appEpochMs","receivedEpochMs"}`, no `id` (so `Last-Event-ID` is unaffected). The page uses it only as a "refetch health" signal, so its position relative to event frames does not matter: health is read from the backend, which applied a poll's events before it saw that poll's watermark (same socket, in order). |
| D-2 | Clean vs unclean close (C-3 transport, C-7) | Clean only after a watermark with `final = true` on that connection. Every other close, and every frame the backend cannot decode, makes delivery uncertain for the rest of the session, from that connection's first seq (`sinceSeq` only moves earlier). Consequence today: no shipped SDK sends watermarks yet (triage PR 3), so an attached session reads "possibly blocked: not evaluated (no poll data)" while connected and "events may have been lost" after the app disconnects. That is the spec's honest reading (H-3): nothing says every event arrived. |
| D-1b | Which watermark is "latest" | The newest by app clock: a delayed frame from an overlapping socket cannot move it back. Every frame's loss facts still count. (An SDK creates a new session per start, so one session never mixes two app clocks.) |
| D-3 | `lastIngestEpochMs` | Backend receipt time of the last **event** frame (not watermarks), recorded just before the event is appended, never moving back. |
| D-4 | `lostEvents` across reconnects | The largest cumulative count any watermark reported. |
| D-5 | Session `kind` | From the id vizcore mints for its own runs (`scenario-…`, `auto-<ts>[-<ts>]`, `api-session-<ts>`), the rule the timeline route and `frontend/src/lib/session-kind.ts` already use. Demos are started by `POST /api/sessions?name=scenario-…`, so there is no other signal to record. |
| D-6 | Session `origin` | `REPORTED` for demos or when a tracked coroutine reported `APP`/`VIZCORE`, else `UNKNOWN` (the derivation's rule, made public). `UNKNOWN` also when the summaries are unavailable. |
| D-7 | `captureStartAppNanos` | Smallest app-clock time of any event the summaries saw (so no "time into the capture" is negative). `null` when unknown: no events, summaries unavailable, or a database session whose stored events were trimmed. |
| D-8 | `trimmed` | Memory mode: the summaries saw every event, so `trimmed` = the oldest retained seq is later than the first seq the summaries saw. Database mode (until 5b): the summaries are folded from the stored events, so the only signal is the oldest stored seq being later than 1, the first seq every session gets. A demo whose first two events raced (`VizSession.send` re-stamps) can start at seq 2 and read "trimmed" in database mode; the cost is an "at least" label, never a missed one. |
| D-9 | Database sessions after a trim (until 5b) | Summaries folded from what is left lose the ancestry of coroutines whose parent's creation was trimmed, so one failed round can read as several ungrouped failures: "at least N" would overstate. `GET …/health` answers `503` `{"code":"history-trimmed"}` instead (H-1, H-3); `GET /api/sessions/{id}` still answers with `trimmed: true` and `captureStartAppNanos: null`. Core gains `HealthInput.historyIncomplete` (failures "at least", possibly blocked `NOT_EVALUATED` with the new reason `HISTORY_TRIMMED`, precedence REPLAY, DEMO, EVENTS_DROPPED, HISTORY_TRIMMED, OVERFLOW, NO_WATERMARK, never green, never "no application coroutines") for the one case that can only undercount: a replay whose seed cannot tell which coroutines beyond capacity existed at the cursor. |
| D-9b | Database sessions after a backend restart (until 5b) | Ingest state is process-local. A session row created before this JVM started, or events stored before the first connection this process saw, came through connections nobody here watched end, so such an ATTACHED database session's `transport` reads `deliveryUncertain` since seq 1, even with no stored event (failures "at least", possibly blocked `EVENTS_DROPPED`). 5b replaces this with the persisted transport and "ingest open" marker. |
| D-9c | Order of a health read | The watermark is read **before** the session is resolved: in database mode resolving rebuilds the summaries from the store, and a watermark is recorded only after its poll's events were stored, so the summaries include every event the watermark vouches for. The transport facts are read again **after** the summaries are copied: they only grow, so a frame skipped or a connection lost during the read is never missed. |
| D-10 | Replay of trimmed history | `SummaryFold.foldTo(events, atSeq, durable)` seeds every coroutine whose creation was trimmed from its durable summary (creation, and its terminal when that was trimmed too), in seq order through the table's own creation and terminal bookkeeping, then applies the retained events. Promotion and round anchoring depend only on creations and terminals, so they are exact at the cursor; failures known from the durable summaries count; states between a trimmed creation and the first retained event are not known and not invented. |
| D-10b | Replay time of trimmed history | Inside the trimmed range only the seeded creations and terminals are known, and appends are not in app-time order (a trimmed event can be later in app time than every retained one), so in a replay of a trimmed session `asOf.appNanos` is the latest time known at or before the cursor: `AsOf.lowerBound = true` says so (new field, default `false`). |
| D-11 | `atSeq` out of range | Negative or not a number: `400`. Later than the last event: clamped to it (`asOfSeq` says which seq was used). |
| D-12 | Summaries unavailable | `GET …/health` answers `503` `{"error":…,"code":"summaries-unavailable"}` (the table was abandoned after an apply failure); `GET /api/sessions/{id}` still answers, with `origin: UNKNOWN` and `captureStartAppNanos: null`. |
| D-15 | Share payload | `SessionSnapshotResponse` is also the share payload's `session`, so both are built by one function and the share payload carries the new session fields too (a first piece of C-6; health at load time, the checks result and the one-cut guarantee are 5b). |
| D-13 | `startedAtEpochMs` in database mode | The session row's `created_at`. Today a rebuilt database session's `createdAtMs` is the time of the read; `ExposedSessionStore` now passes the row's time. |
| D-14 | Where `retention` and `asOfWatermark` live | Added to core's `SessionHealth` (defaults `null`), filled by the derivation from `HealthInput`, so the payload is one flat object as C-3 lists it. |

## Not in 5a (each named in the PR body)

- Everything in the 5b row above, including persistence of the watermark, transport and "ingest open" marker: after a
  backend restart a database session has no watermark until its app connects again, and reads delivery-uncertain (D-9b);
  a trimmed database session's health is `503 history-trimmed` (D-9).
- Live SSE in database mode is a pre-existing gap (each read builds a fresh `VizSession`, so the SSE route's bus never
  sees the ingest socket's events). Watermark frames work in both modes because they come from the id-keyed registry.
- The SDK side (triage PR 3): watermark, origin by creation stack, provenance.
- Frontend UI: none. `shared/api-types` is regenerated so Part 7 has the types.

---

## Context for the implementer

- **Worktree:** `/Users/jirihermann/Documents/workspace-vizcore/vizcore-health`, branch `feat/health-api` from
  `origin/main` (0c02dd2f). Gradle runs from `backend/`; `git` from the worktree root (from `backend/`, `git -C ..`).
  `export JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home` in every shell.
- **Gradle:** core tests `./gradlew :coroutine-viz-core:test --tests "<FQN>"`; backend app tests `./gradlew :test --tests "<FQN>"`
  (**qualified `:test`**: an unqualified `test` with a `--tests` filter fails in every subproject where it matches nothing).
- **Lint:** the backend app applies ktlint and detekt (`backend/detekt.yml`, `buildUponDefaultConfig`); core applies neither
  but follows the repo `.editorconfig` (`ktlint_official`, max line 140, trailing commas). Run `./gradlew ktlintFormat`
  before `ktlintCheck`. Never add to the detekt baseline.
- **Test patterns:** memory mode = `SessionManager.clearAll()` in `@BeforeEach`/`@AfterEach` plus `testApplication { application { module() } }`;
  database mode = H2 in memory + `DatabaseFactory.init` + `SessionManager.useStore(ExposedSessionStore(db))` before `module()`
  and `useStore(null)` after (see `routes/SessionDeleteDatabaseModeTest.kt`). `IngestStates` is a process singleton:
  tests call `IngestStates.clear()` in both hooks.
- **How events reach a session:** the SDK opens `/api/sessions/{id}/ingest` (WebSocket), one JSON `VizEvent` per text
  frame. `publishFrame` rewrites `sessionId`, decodes with `appJson`, and calls `VizSession.ingest`, which re-stamps `seq`
  and runs store → snapshot → summaries → bus under the send lock. Frames of one socket are handled in order. Demos run
  in this JVM and call `VizSession.send`.
- **Database mode builds a new `VizSession` on every lookup** (`ExposedSessionStore.getSession` → `rehydrateFromStore()`),
  which is why per-session live state lives in id-keyed registries (`IngestConnections`, `CorrelationRegistry`, and now
  `IngestStates`).
- **What core (#248) gives:** `health/` — `SummaryTable`/`SummaryView`, `CoroutineSummary`, `RoundAnchoring`,
  `SessionHealthDerivation.derive(HealthInput)`, `SessionHealth`, `HealthThresholds`, `Transitions`, `SummaryFold`,
  `SummariesUnavailableException`; `events/PollWatermark.kt`; `CoroutineCreated.origin`; `CoroutineSuspended.provenance`;
  `VizSession(keepSummaries = …)` and `readSummaries { }`. In-memory sessions from `SessionManager.createSession` keep
  summaries; database sessions do not yet (Task 8). Origin and provenance already decode at ingest (they are optional
  fields of the event classes): nothing to add there beyond a test.
- **Defaults:** memory mode keeps 10,000 events per session (`session.maxEvents`), so real apps trim in memory too; the
  durable summary table is unaffected by that trim.

## File map

| File | Change |
|---|---|
| `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/health/SummaryTable.kt` | `firstEventSeq`, `earliestEventAppNanos`; `track` refactor; `seed` |
| `…/health/SummarySnapshot.kt` (new) | `SummaryView.snapshot()` |
| `…/health/SummaryUpdater.kt` | `createdFrom`, `finishedAs` |
| `…/health/SummaryFold.kt` | seeded `foldTo` |
| `…/health/HealthModel.kt` | `HISTORY_TRIMMED`, `RetentionInfo`, `SessionHealth.asOfWatermark`/`retention`, `HealthInput.historyIncomplete`/`retention` |
| `…/health/SessionHealthDerivation.kt` | the above; public `sessionOrigin` |
| `…/session/VizSession.kt` | `lastSeq` |
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionKinds.kt` (new) | `sessionKindOf`, `leakThresholdMs()` |
| `…/routes/IngestStates.kt` (new) | ingest state registry |
| `…/routes/IngestRoutes.kt` | watermarks, skipped frames, close |
| `…/persistence/ExposedSessionStore.kt` | keep summaries, row `created_at` |
| `…/routes/SessionHealthReads.kt` (new) | `readSessionHealth`, `transportOf`, `readSessionFacts`, `RetainedTransitions` |
| `…/routes/SessionHealthRoutes.kt` (new) | `GET /api/sessions/{id}/health` |
| `…/routes/SessionRoutes.kt`, `…/routes/RouteDtos.kt`, `…/share/ShareRoutes.kt` | C-3 fields (one builder for the session read and the share payload); leak parser; SSE watermarks |
| `…/Routing.kt` | register route; forget ingest state on close |
| `backend/src/main/resources/openapi/documentation.yaml`, `shared/api-types/openapi.json`, `shared/api-types/generated.ts`, `docs/guides/API_REFERENCE.md` | API docs and types |

---

### Task 0: Commit the plan

- [ ] **Step 1:** From the worktree root:

```bash
git add docs/superpowers/plans/2026-11-06-part5-health-api.md
git commit -m "docs: plan for Part 5a, the health API

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 1: Core — summary facts, a detached snapshot, and the session's last seq

The health route copies the summaries under the send lock and derives outside it; the session route needs the first seq
the summaries saw and the earliest app time; the ingest route needs the session's last seq.

**Files:**
- Modify: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/health/SummaryTable.kt`
- Create: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/health/SummarySnapshot.kt`
- Modify: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt`
- Test: `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/health/SummaryViewFactsTest.kt`,
  `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/session/VizSessionLastSeqTest.kt`

- [ ] **Step 1: Write the failing tests**

`SummaryViewFactsTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SummaryViewFactsTest {
    @Test
    fun `the table remembers the first seq it saw and the earliest app time`() {
        val script = EventScript()
        script.created("a", at = sec(5))
        // Appends are not in app-time order: a later event can carry an earlier time.
        script.thread("a", at = sec(3), name = "main")
        val table = script.table()

        assertEquals(1L, table.firstEventSeq)
        assertEquals(sec(3), table.earliestEventAppNanos)
        assertEquals(sec(5), table.latestEventAppNanos)
    }

    @Test
    fun `an empty table has no first seq and no earliest time`() {
        val table = SummaryTable()

        assertEquals(0L, table.firstEventSeq)
        assertNull(table.earliestEventAppNanos)
    }

    @Test
    fun `a snapshot keeps what the table held and does not follow it`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        val table = script.table()
        val copy = table.snapshot()

        script.created("b", at = sec(2))
        table.apply(script.events.last())

        assertEquals(listOf("a"), copy.all.map { it.id })
        assertNull(copy.get("b"))
        assertEquals(1L, copy.lastEventSeq)
        assertEquals(1L, copy.firstEventSeq)
        assertEquals(sec(1), copy.latestEventAppNanos)
        assertEquals(sec(1), copy.earliestEventAppNanos)
        assertEquals(0L, copy.untrackedCount)
        assertEquals(2L, table.lastEventSeq)
    }
}
```

`VizSessionLastSeqTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class VizSessionLastSeqTest {
    @Test
    fun `lastSeq is the seq of the last appended event`() {
        val session = VizSession("last-seq")
        assertEquals(0L, session.lastSeq)

        session.ingest(CoroutineStarted("last-seq", 41, 1, "c", "j", null, "s", "c"))
        session.ingest(CoroutineStarted("last-seq", 7, 2, "c", "j", null, "s", "c"))

        // ingest() re-stamps: the client's 41 and 7 are not kept.
        assertEquals(2L, session.lastSeq)
        session.close()
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.SummaryViewFactsTest" --tests "com.jh.proj.coroutineviz.session.VizSessionLastSeqTest"`
Expected: compilation FAILS — `firstEventSeq`, `earliestEventAppNanos`, `snapshot`, `lastSeq` do not exist.

- [ ] **Step 3: Implement**

`SummaryTable.kt` — add two members to `SummaryView` after `latestEventAppNanos`:

```kotlin
    /**
     * Sequence of the first event applied, 0 before any. A table that saw every event of its session (the in-memory
     * table of a live session) tells a trimmed store apart from one that never held more.
     */
    val firstEventSeq: Long

    /** Smallest app-clock time of any event applied, `null` before any: the capture's start (spec C-3 `captureStartAppNanos`). */
    val earliestEventAppNanos: Long?
```

In `SummaryTable`, after `latestEventAppNanos`:

```kotlin
    override var firstEventSeq: Long = 0L
        private set

    override var earliestEventAppNanos: Long? = null
        private set
```

and replace `apply` with:

```kotlin
    fun apply(event: VizEvent) {
        observe(event.seq, event.tsNanos)
        if (event !is CoroutineEvent) return
        if (event is CoroutineCreated) onCreated(event) else onUpdate(event)
    }

    private fun observe(
        seq: Long,
        appNanos: Long,
    ) {
        if (firstEventSeq == 0L) firstEventSeq = seq
        lastEventSeq = seq
        latestEventAppNanos = maxOf(latestEventAppNanos ?: Long.MIN_VALUE, appNanos)
        earliestEventAppNanos = minOf(earliestEventAppNanos ?: Long.MAX_VALUE, appNanos)
    }
```

`SummarySnapshot.kt` (new):

```kotlin
package com.jh.proj.coroutineviz.health

/**
 * A detached copy of this view, safe to read outside the owner's lock: summaries are immutable values, so copying the
 * map is enough. The backend takes it inside `VizSession.readSummaries` and derives health outside the lock.
 */
fun SummaryView.snapshot(): SummaryView = SummarySnapshot(this)

private class SummarySnapshot(
    source: SummaryView,
) : SummaryView {
    private val byId: Map<String, CoroutineSummary> = source.all.associateByTo(LinkedHashMap(source.all.size)) { it.id }

    override fun get(id: String): CoroutineSummary? = byId[id]

    override val all: Collection<CoroutineSummary> = byId.values
    override val untrackedCount: Long = source.untrackedCount
    override val lastEventSeq: Long = source.lastEventSeq
    override val latestEventAppNanos: Long? = source.latestEventAppNanos
    override val firstEventSeq: Long = source.firstEventSeq
    override val earliestEventAppNanos: Long? = source.earliestEventAppNanos
}
```

`VizSession.kt` — after `nextSeq()`:

```kotlin
    /**
     * The seq of the last event appended (0 before any). The ingest route reads it when a socket opens: that
     * socket's events get later seqs.
     */
    val lastSeq: Long get() = synchronized(sendLock) { lastSentSeq }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2, then `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.*" --tests "com.jh.proj.coroutineviz.compat.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core
git commit -m "core: summaries know their first seq and earliest time, and copy out of the lock

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 2: Core — a replay fold seeded from the durable summaries

Replay folds the retained events up to the cursor. A coroutine whose creation event was trimmed would be untracked by
that fold, so its failure would vanish from the replay (spec "Modes": ancestry and failures come from the durable
summaries). The seed recreates exactly what the durable summary knows for trimmed seqs: the creation, and the terminal
transition when that was trimmed too.

**Files:**
- Modify: `…/health/SummaryUpdater.kt`, `…/health/SummaryTable.kt`, `…/health/SummaryFold.kt`
- Test: `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/health/SummaryFoldSeedTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.models.CoroutineState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryFoldSeedTest {
    private fun replay(
        view: SummaryView,
        atSeq: Long,
    ): SessionHealth =
        SessionHealthDerivation.derive(HealthInput(summaries = view, kind = SessionKind.ATTACHED, mode = HealthMode.Replay(atSeq)))

    @Test
    fun `a failure whose ancestry was trimmed keeps its round`() {
        val script = EventScript()
        script.created("req", at = sec(1))
        script.created("a", at = sec(1), parent = "req")
        script.started("a", at = sec(2))
        script.failed("a", at = sec(3))
        val durable = script.table()
        val retained = script.events.drop(2)

        val unseeded = replay(SummaryFold.foldTo(retained, script.lastSeq), script.lastSeq)
        val seeded = replay(SummaryFold.foldTo(retained, script.lastSeq, durable), script.lastSeq)

        assertEquals(0, unseeded.problemCount, "without the seed, a's creation is gone and so is its failure")
        val round = assertIs<FailedRoundIncident>(seeded.incidents.items.single())
        assertEquals("req", round.anchorId)
        assertEquals("java.lang.IllegalStateException", round.failures.items.single().exceptionType)
    }

    @Test
    fun `a trimmed terminal counts only from its own seq`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        val created = script.lastSeq
        script.failed("a", at = sec(2))
        val failed = script.lastSeq
        script.created("b", at = sec(3))
        val durable = script.table()
        val retained = script.events.drop(2)

        assertEquals(0, replay(SummaryFold.foldTo(retained, created, durable), created).problemCount)
        assertEquals(1, replay(SummaryFold.foldTo(retained, failed, durable), failed).problemCount)
        assertEquals(1, replay(SummaryFold.foldTo(retained, script.lastSeq, durable), script.lastSeq).problemCount)
    }

    @Test
    fun `nothing created after the cursor is seeded`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        script.created("b", at = sec(2))
        script.started("b", at = sec(3))
        val durable = script.table()

        val view = SummaryFold.foldTo(script.events.drop(2), atSeq = 1, durable = durable)

        assertTrue(view.get("a") != null)
        assertNull(view.get("b"))
        assertEquals(1L, view.lastEventSeq)
    }

    @Test
    fun `states between a trimmed creation and the retained events are not invented`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        script.suspended("a", at = sec(2))
        script.resumed("a", at = sec(3))
        val durable = script.table()

        val view = SummaryFold.foldTo(script.events.drop(3), atSeq = 2, durable = durable)

        assertEquals(CoroutineState.CREATED, view.get("a")!!.state)
    }

    @Test
    fun `a stub's failure is seeded with its details dropped`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        script.failed("a", at = sec(2))
        script.created("b", at = sec(3))
        val durable = script.table(fullSummaryTarget = 0)

        val health = replay(SummaryFold.foldTo(script.events.drop(2), script.lastSeq, durable), script.lastSeq)

        val failure = assertIs<FailedRoundIncident>(health.incidents.items.single()).failures.items.single()
        assertTrue(failure.detailsDropped)
        assertNull(failure.exceptionType)
    }

    /**
     * Round anchoring and failures depend only on creations and terminals, which the seed keeps: for every cut of the
     * history and every cursor, a seeded fold of what is left anchors and counts exactly like a fold of everything.
     */
    @Test
    fun `a seeded fold anchors and counts like the full history, at every cut and cursor`() {
        val script = EventScript()
        script.created("app", at = sec(1))
        for (i in 1L..4L) {
            script.created("r$i", at = sec(1 + i), parent = "app")
            script.created("r$i-x", at = sec(1 + i), parent = "r$i")
            if (i == 2L) script.failed("r$i-x", at = sec(2 + i)) else script.completed("r$i-x", at = sec(2 + i))
            if (i == 2L) script.cancelled("r$i", at = sec(2 + i)) else script.completed("r$i", at = sec(2 + i))
        }
        script.created("late", at = sec(9), parent = "app")
        script.failed("late", at = sec(10))
        script.cancelled("app", at = sec(11))
        val durable = script.table()
        val ids = durable.all.map { it.id }

        for (cut in 0..script.events.size) {
            val retained = script.events.drop(cut)
            for (cursor in 0..script.lastSeq) {
                val full = SummaryFold.foldTo(script.events, cursor)
                val seeded = SummaryFold.foldTo(retained, cursor, durable)
                val fullAnchors = RoundAnchoring.of(full)
                val seededAnchors = RoundAnchoring.of(seeded)
                for (id in ids) {
                    assertEquals(fullAnchors.anchorOf(id), seededAnchors.anchorOf(id), "anchor of $id, cut $cut, cursor $cursor")
                }
                assertEquals(
                    replay(full, cursor).incidents.items.map { it.id },
                    replay(seeded, cursor).incidents.items.map { it.id },
                    "incidents, cut $cut, cursor $cursor",
                )
            }
        }
    }

    @Test
    fun `a replay of trimmed history says its time is only a lower bound`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        script.suspended("a", at = sec(20))
        script.resumed("a", at = sec(30))
        script.created("b", at = sec(31))
        val durable = script.table()
        val retained = script.events.drop(3)
        val retention = RetentionInfo(oldestRetainedSeq = 4, oldestRetainedAppNanos = sec(31), trimmed = true)

        fun asOfAt(
            seq: Long,
            retention: RetentionInfo = RetentionInfo(oldestRetainedSeq = 4, oldestRetainedAppNanos = sec(31), trimmed = true),
        ) =
            SessionHealthDerivation
                .derive(
                    HealthInput(
                        summaries = SummaryFold.foldTo(retained, seq, durable),
                        kind = SessionKind.ATTACHED,
                        mode = HealthMode.Replay(seq),
                        retention = retention,
                    ),
                ).asOf!!

        val inTrimmed = asOfAt(2)
        assertEquals(sec(1), inTrimmed.appNanos, "only the creation is known at or before seq 2")
        assertTrue(inTrimmed.lowerBound)
        assertEquals(2L, inTrimmed.seq)
        // Appends are not in app-time order: a trimmed event may be later in app time than every retained one.
        assertTrue(asOfAt(4).lowerBound)
        assertEquals(false, asOfAt(4, retention.copy(trimmed = false)).lowerBound)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.SummaryFoldSeedTest"`
Expected: compilation FAILS — no `foldTo(events, atSeq, durable)`.

- [ ] **Step 3: Implement**

`SummaryUpdater.kt` — add to the object:

```kotlin
    /** The summary right after [s]'s creation, from what a durable summary (or stub) keeps of it (replay seed). */
    fun createdFrom(s: CoroutineSummary): CoroutineSummary =
        CoroutineSummary(
            id = s.id,
            parentId = s.parentId,
            origin = s.origin,
            name = s.name,
            creationLocation = s.creationLocation,
            createdAtEpochMs = s.createdAtEpochMs,
            firstObserved = s.firstObserved,
            lastTransition = s.firstObserved,
            lastObservedNanos = s.firstObserved.appNanos,
            lastModifiedSeq = s.firstObserved.seq,
        )

    /** [current] finished as [durable] did (replay seed); a stub's failure keeps no details, and says so. */
    fun finishedAs(
        current: CoroutineSummary,
        durable: CoroutineSummary,
    ): CoroutineSummary {
        val terminal = checkNotNull(durable.terminal) { "${durable.id} has no terminal transition" }
        return current.copy(
            lastTransition = terminal,
            terminal = terminal,
            lastObservedNanos = maxOf(current.lastObservedNanos, terminal.appNanos),
            failed = durable.failed,
            cancelled = durable.cancelled,
            failure = durable.failure,
            reduced = durable.reduced,
            lastModifiedSeq = terminal.seq,
        )
    }
```

`SummaryTable.kt` — replace `onCreated` with a creation path both `apply` and `seed` use:

```kotlin
    private fun onCreated(e: CoroutineCreated) = track(e.coroutineId, e.parentCoroutineId, e.seq) { SummaryUpdater.created(e) }

    private fun track(
        id: String,
        parentId: String?,
        seq: Long,
        create: () -> CoroutineSummary,
    ) {
        if (id in summaries) return
        // Growth counts even for a child beyond capacity: the root is still a container.
        val parent = parentId?.let { summaries[it] }
        if (parent != null && parent.promotedAtSeq != null && !parent.grewAfterPromotion) {
            summaries[parent.id] = parent.copy(grewAfterPromotion = true, lastModifiedSeq = seq)
        }
        if (summaries.size >= trackedCapacity) {
            untrackedCount++
            return
        }
        summaries[id] = create()
        creationOrder[id] = nextCreationOrder++
        fullSummaryCount++
        subtrees.add(id, parentId)
        promoteIfQualified(subtrees.rootOf(id), seq)
        reduceOverTarget(seq)
    }

    /**
     * Replay seed (spec "Modes", Replay; used by [SummaryFold]): every coroutine of [durable] first observed before
     * [retainedFrom] (its creation event was trimmed) and at or before [atSeq] is created again from its summary, and
     * finished at its terminal transition when that is in the same range, all in seq order through the same bookkeeping
     * as [apply]. Promotion and round anchoring depend only on creations and terminals, so they are those of the cursor.
     * Nothing in between is known, so nothing is invented: a seeded coroutine reads as created until a retained event
     * says more. Call it on an empty table, before any [apply].
     */
    internal fun seed(
        durable: SummaryView,
        retainedFrom: Long,
        atSeq: Long,
    ) {
        check(lastEventSeq == 0L && summaries.isEmpty()) { "seed an empty table only" }
        val trimmed = { seq: Long -> seq < retainedFrom && seq <= atSeq }
        val steps = ArrayList<SeedStep>()
        for (s in durable.all) {
            if (!trimmed(s.firstObserved.seq)) continue
            steps += SeedStep(s.firstObserved, s, creation = true)
            s.terminal?.takeIf { trimmed(it.seq) }?.let { steps += SeedStep(it, s, creation = false) }
        }
        steps.sortBy { it.at.seq }
        for (step in steps) {
            val s = step.summary
            observe(step.at.seq, step.at.appNanos)
            if (step.creation) {
                track(s.id, s.parentId, step.at.seq) { SummaryUpdater.createdFrom(s) }
            } else {
                val current = summaries[s.id] ?: continue
                val next = SummaryUpdater.finishedAs(current, s)
                summaries[s.id] = next
                onTerminal(next, step.at.seq)
            }
        }
    }

    private class SeedStep(
        val at: Transition,
        val summary: CoroutineSummary,
        val creation: Boolean,
    )
```

`SummaryFold.kt` — replace the object with:

```kotlin
/**
 * Replay fold (spec "Modes", Replay): transient summaries from the retained events up to [atSeq], with the same updater
 * as the live table. With [durable] (the session's durable summaries), coroutines whose creation event was trimmed are
 * seeded from it first (see [SummaryTable.seed]), so their ancestry and failures known at the cursor are kept. [events]
 * must be in seq order (as every event store returns them): the fold stops at the first event after [atSeq].
 */
object SummaryFold {
    fun foldTo(
        events: Iterable<VizEvent>,
        atSeq: Long,
    ): SummaryView = foldTo(events, atSeq, durable = null)

    fun foldTo(
        events: Iterable<VizEvent>,
        atSeq: Long,
        durable: SummaryView?,
    ): SummaryView {
        val table = SummaryTable()
        if (durable != null) table.seed(durable, retainedFrom = events.firstOrNull()?.seq ?: Long.MAX_VALUE, atSeq = atSeq)
        for (event in events) {
            if (event.seq > atSeq) break
            table.apply(event)
        }
        return table
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.*"`
Expected: PASS (the existing `SummaryFoldTest` and `SummaryTableTest` included).

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core
git commit -m "core: replay seeds trimmed coroutines from the durable summaries

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 3: Core — the health payload carries retention, the bound watermark, and an incomplete history

**Files:**
- Modify: `…/health/HealthModel.kt`, `…/health/SessionHealthDerivation.kt`
- Test: `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/health/HealthPayloadTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthPayloadTest {
    private val watermark = WatermarkObservation(appNanos = sec(20), appEpochMs = 1_792_000_000_000, receivedEpochMs = 1_792_000_000_005)

    private fun appScript(): EventScript =
        EventScript().apply {
            created("a", at = sec(1))
            started("a", at = sec(1))
            suspended("a", at = sec(2))
        }

    @Test
    fun `an incomplete history reads at least and leaves possibly blocked unevaluated`() {
        val health =
            SessionHealthDerivation.derive(
                HealthInput(summaries = appScript().table(), kind = SessionKind.ATTACHED, watermark = watermark, historyIncomplete = true),
            )

        assertTrue(health.failuresAtLeast)
        assertEquals(PossiblyBlockedEvaluation(EvaluationStatus.NOT_EVALUATED, NotEvaluatedReason.HISTORY_TRIMMED), health.possiblyBlocked)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
    }

    @Test
    fun `an incomplete history never claims there are no app coroutines`() {
        // Only vizcore's own coroutine was captured: without the flag that reads "no application coroutines".
        val table = EventScript().apply { created("v", at = sec(1), origin = CoroutineOrigin.VIZCORE) }.table()
        val complete = SessionHealthDerivation.derive(HealthInput(summaries = table, kind = SessionKind.ATTACHED))
        val incomplete = SessionHealthDerivation.derive(HealthInput(summaries = table, kind = SessionKind.ATTACHED, historyIncomplete = true))

        assertEquals(HealthState.NO_APP_COROUTINES, complete.state)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, incomplete.state)
    }

    @Test
    fun `lost events outrank a trimmed history, which outranks a missing watermark`() {
        val table = appScript().table()
        val dropped =
            SessionHealthDerivation.derive(
                HealthInput(
                    summaries = table,
                    kind = SessionKind.ATTACHED,
                    transport = TransportStatus(deliveryUncertain = true, sinceSeq = 1),
                    historyIncomplete = true,
                ),
            )
        val trimmed = SessionHealthDerivation.derive(HealthInput(summaries = table, kind = SessionKind.ATTACHED, historyIncomplete = true))

        assertEquals(NotEvaluatedReason.EVENTS_DROPPED, dropped.possiblyBlocked.reason)
        assertEquals(NotEvaluatedReason.HISTORY_TRIMMED, trimmed.possiblyBlocked.reason)
    }

    @Test
    fun `the watermark the health used is echoed for live attached sessions only`() {
        val table = appScript().table()
        val live = SessionHealthDerivation.derive(HealthInput(summaries = table, kind = SessionKind.ATTACHED, watermark = watermark))
        val replay =
            SessionHealthDerivation.derive(
                HealthInput(summaries = table, kind = SessionKind.ATTACHED, mode = HealthMode.Replay(2), watermark = watermark),
            )
        val demo = SessionHealthDerivation.derive(HealthInput(summaries = table, kind = SessionKind.DEMO, watermark = watermark))

        assertEquals(watermark, live.asOfWatermark)
        assertNull(replay.asOfWatermark)
        assertNull(demo.asOfWatermark)
    }

    @Test
    fun `retention is echoed`() {
        val retention = RetentionInfo(oldestRetainedSeq = 3, oldestRetainedAppNanos = sec(2), trimmed = true)

        val health = SessionHealthDerivation.derive(HealthInput(summaries = appScript().table(), kind = SessionKind.DEMO, retention = retention))

        assertEquals(retention, health.retention)
    }

    @Test
    fun `session origin follows the derivation's rule`() {
        val reported = appScript().table()
        val unknown = EventScript().apply { created("a", at = sec(1), origin = null) }.table()

        assertEquals(SessionOrigin.REPORTED, SessionHealthDerivation.sessionOrigin(reported, SessionKind.ATTACHED))
        assertEquals(SessionOrigin.UNKNOWN, SessionHealthDerivation.sessionOrigin(unknown, SessionKind.ATTACHED))
        assertEquals(SessionOrigin.REPORTED, SessionHealthDerivation.sessionOrigin(unknown, SessionKind.DEMO))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :coroutine-viz-core:test --tests "com.jh.proj.coroutineviz.health.HealthPayloadTest"`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

`HealthModel.kt`:

1. `NotEvaluatedReason` gains `HISTORY_TRIMMED`:

```kotlin
@Serializable
enum class NotEvaluatedReason { NO_WATERMARK, DEMO, REPLAY, OVERFLOW, EVENTS_DROPPED, HISTORY_TRIMMED }
```

2. The `PossiblyBlockedEvaluation` KDoc's precedence line becomes
   `REPLAY, DEMO, EVENTS_DROPPED, HISTORY_TRIMMED, OVERFLOW, NO_WATERMARK (in that order)`.

3. After `TransportStatus`, add:

```kotlin
/**
 * What the session still holds of its events (spec C-3 `retention`): the oldest retained event's seq and app time, and
 * whether events before it were dropped. The backend supplies it; the health echoes it.
 */
@Serializable
data class RetentionInfo(
    val oldestRetainedSeq: Long?,
    val oldestRetainedAppNanos: Long?,
    val trimmed: Boolean,
)
```

4. `SessionHealth` gains two trailing parameters:

```kotlin
    /** The watermark the health was derived with (spec C-3 `asOfWatermark`): live attached sessions only, else `null`. */
    val asOfWatermark: WatermarkObservation? = null,
    /** Echo of [HealthInput.retention]. */
    val retention: RetentionInfo? = null,
```

5. `HealthInput` gains two trailing parameters (after `transitionsFor`):

```kotlin
    /**
     * The summaries may miss failures the session had, though every incident they show is real: a replay seed cannot
     * tell which coroutines beyond capacity existed at the cursor. Failures then read "at least" and possibly blocked is
     * not evaluated (HISTORY_TRIMMED). Never set it when incidents could be overcounted (a lost ancestry splits a round).
     */
    val historyIncomplete: Boolean = false,
    /** The backend's retention facts, echoed in [SessionHealth.retention]. */
    val retention: RetentionInfo? = null,
```

`SessionHealthDerivation.kt`:

1. In `derive`, `failuresAtLeast` becomes

```kotlin
        val failuresAtLeast = view.untrackedCount > 0 || transportLossApplies(input) || input.historyIncomplete
```

   the `noAppCoroutines` argument gains `&& !input.historyIncomplete`, and the `SessionHealth(...)` call ends with

```kotlin
            asOfWatermark = input.watermark?.takeIf { liveWatermarkNanos(input) != null },
            retention = input.retention,
```

2. `AsOf` gains `val lowerBound: Boolean = false` (KDoc: a replay cursor inside trimmed history; `appNanos` is the latest
   time known at or before it), and `resolveAsOf`'s replay branch sets it whenever `input.retention` says events were
   trimmed:

```kotlin
        if (mode is HealthMode.Replay) {
            // Appends are not in app-time order, so once events were trimmed no cursor's latest time is known for sure.
            val trimmed = input.retention?.trimmed == true
            return lastNanos?.let { AsOf(AsOfKind.REPLAY_CURSOR, it, seq = mode.atSeq, lowerBound = trimmed) }
        }
```

   (Its test is the last one of `SummaryFoldSeedTest`, added with this task.)

3. `evaluatePossiblyBlocked` gains a line right after the `EVENTS_DROPPED` one:

```kotlin
                input.historyIncomplete -> NotEvaluatedReason.HISTORY_TRIMMED
```

4. Add a public function and use it in `derive` (`input.reportedOrigin ?: sessionOrigin(view, input.kind)`), removing
   the private `sessionOriginOf`:

```kotlin
    /** The session origin as the derivation reads it (spec "Origin"): demos are REPORTED; otherwise REPORTED once a coroutine reported one. */
    fun sessionOrigin(
        view: SummaryView,
        kind: SessionKind,
    ): SessionOrigin {
        val reported = view.all.any { it.origin == CoroutineOrigin.APP || it.origin == CoroutineOrigin.VIZCORE }
        return if (kind == SessionKind.DEMO || reported) SessionOrigin.REPORTED else SessionOrigin.UNKNOWN
    }
```

   so the first lines of `derive` read:

```kotlin
        val sessionOrigin =
            if (demo) SessionOrigin.REPORTED else input.reportedOrigin ?: sessionOrigin(view, input.kind)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :coroutine-viz-core:test`
Expected: PASS (all core tests).

- [ ] **Step 5: Commit**

```bash
git add backend/coroutine-viz-core
git commit -m "core: health carries retention, its watermark, and an incomplete history

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 4: Backend — one session-kind rule, one leak-threshold parser

**Files:**
- Create: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionKinds.kt`
- Modify: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionKindsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.SessionKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Mirrors `frontend/src/lib/session-kind.ts`: the ids vizcore mints for its own runs are demos. */
class SessionKindsTest {
    @Test
    fun `vizcore-minted ids are demos and every other id is an attached app`() {
        assertEquals(SessionKind.DEMO, sessionKindOf("scenario-order-processing-1792000000000"))
        assertEquals(SessionKind.DEMO, sessionKindOf("auto-1792000000000-1792000000001"))
        assertEquals(SessionKind.DEMO, sessionKindOf("api-session-1792000000000"))
        assertEquals(SessionKind.ATTACHED, sessionKindOf("order-service-1792000000000"))
        assertEquals(SessionKind.ATTACHED, sessionKindOf("my-scenario-1792000000000"))
        assertEquals(SessionKind.ATTACHED, sessionKindOf("auto-x"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.SessionKindsTest"`
Expected: compilation FAILS — `sessionKindOf` does not exist.

- [ ] **Step 3: Implement**

`SessionKinds.kt`:

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.SessionKind
import io.ktor.server.application.ApplicationCall

/**
 * Ids vizcore mints for its own runs (demo scenarios, the scenario runner and API), whose
 * coroutines run in this JVM rather than in a connected app. Mirrors
 * `frontend/src/lib/session-kind.ts`. An app that names itself `scenario-…` is taken for a demo
 * here too; then its timeline keeps an open phase after the app disconnects.
 */
private val VIZCORE_OWNED_SESSION_ID = Regex("^(scenario-|auto-\\d+(-\\d+)?$|api-session-\\d+$)")

/** A session's kind (spec C-3 `kind`), read from the id vizcore minted; demos are started under `scenario-…` ids. */
internal fun sessionKindOf(sessionId: String): SessionKind =
    if (VIZCORE_OWNED_SESSION_ID.containsMatchIn(sessionId)) SessionKind.DEMO else SessionKind.ATTACHED

/**
 * The `?leakThresholdMs=` of `/metrics` and `/health`, so the state bar filter and the health agree: absent or
 * unparseable falls back to the server default; other values are clamped to bounds (T-08-05/V5).
 */
internal fun ApplicationCall.leakThresholdMs(): Long =
    request.queryParameters["leakThresholdMs"]
        ?.toLongOrNull()
        ?.coerceIn(MIN_LEAK_MS, MAX_LEAK_MS)
        ?: DEFAULT_LEAK_MS
```

`SessionRoutes.kt`:
- delete the `VIZCORE_OWNED_SESSION_ID` declaration and its KDoc at the end of the file;
- in the timeline route, replace `VIZCORE_OWNED_SESSION_ID.containsMatchIn(sessionId)` with
  `sessionKindOf(sessionId) == SessionKind.DEMO` (import `com.jh.proj.coroutineviz.health.SessionKind`);
- in the metrics route, replace the `val leakThresholdMs = call.request.queryParameters[...]...?: DEFAULT_LEAK_MS`
  expression (keep its comment) with `val leakThresholdMs = call.leakThresholdMs()`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.SessionKindsTest" --tests "com.jh.proj.coroutineviz.routes.MetricsRouteTest" --tests "com.jh.proj.coroutineviz.routes.TimelineRouteAssembledTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "refactor(backend): one session-kind rule and one leak-threshold parser

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 5: Backend — `IngestStates`, what the backend knows about a session's ingest

**Files:**
- Create: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/IngestStates.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/IngestStatesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.health.TransportStatus
import com.jh.proj.coroutineviz.health.WatermarkObservation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngestStatesTest {
    @BeforeEach
    fun setUp() = IngestStates.clear()

    @AfterEach
    fun tearDown() = IngestStates.clear()

    private fun watermark(
        appNanos: Long,
        lost: Long = 0,
        uncertain: Boolean = false,
        final: Boolean = false,
    ) = PollWatermark("s", 0, appNanos, pollEpochMs = 1_792_000_000_000 + appNanos / 1_000_000, lost, uncertain, final)

    @Test
    fun `a session nobody ingested into has no state`() {
        assertEquals(IngestSnapshot(), IngestStates.snapshot("s"))
    }

    @Test
    fun `the latest watermark and the latest event receipt are kept`() {
        val connection = IngestStates.connectionOpened("s", firstSeq = 1)
        IngestStates.eventReceived(connection, receivedEpochMs = 100)
        IngestStates.eventReceived(connection, receivedEpochMs = 90)
        IngestStates.watermarkReceived(connection, watermark(appNanos = 5_000_000_000), receivedEpochMs = 120)

        val snapshot = IngestStates.snapshot("s")
        assertEquals(100L, snapshot.lastIngestEpochMs, "receipt time never moves back")
        assertEquals(WatermarkObservation(5_000_000_000, 1_792_000_005_000, 120), snapshot.watermark)
        assertEquals(TransportStatus(), snapshot.transport)
        assertEquals(1L, snapshot.firstConnectionSeq)
    }

    @Test
    fun `a delayed watermark from an overlapping socket does not move the watermark back, but its losses count`() {
        val newer = IngestStates.connectionOpened("s", firstSeq = 1)
        val older = IngestStates.connectionOpened("s", firstSeq = 5)
        IngestStates.watermarkReceived(newer, watermark(appNanos = 20_000_000_000), receivedEpochMs = 1)
        IngestStates.watermarkReceived(older, watermark(appNanos = 5_000_000_000, lost = 1), receivedEpochMs = 2)

        val snapshot = IngestStates.snapshot("s")
        assertEquals(20_000_000_000, snapshot.watermark?.appNanos)
        assertEquals(TransportStatus(lostEvents = 1, sinceSeq = 5), snapshot.transport)
        assertEquals(1L, snapshot.firstConnectionSeq, "the first connection this process saw")
    }

    @Test
    fun `a final watermark makes the close clean`() {
        val connection = IngestStates.connectionOpened("s", firstSeq = 1)
        IngestStates.watermarkReceived(connection, watermark(appNanos = 1, final = true), receivedEpochMs = 1)
        IngestStates.connectionClosed(connection)

        assertFalse(IngestStates.snapshot("s").transport.deliveryUncertain)
    }

    @Test
    fun `a close without a final watermark makes delivery uncertain from that connection on, for good`() {
        val first = IngestStates.connectionOpened("s", firstSeq = 1)
        IngestStates.connectionClosed(first)
        val second = IngestStates.connectionOpened("s", firstSeq = 40)
        IngestStates.watermarkReceived(second, watermark(appNanos = 1, final = true), receivedEpochMs = 1)
        IngestStates.connectionClosed(second)

        assertEquals(TransportStatus(deliveryUncertain = true, sinceSeq = 1), IngestStates.snapshot("s").transport)
    }

    @Test
    fun `lost events keep the largest count and the earliest affected seq`() {
        val first = IngestStates.connectionOpened("s", firstSeq = 10)
        IngestStates.watermarkReceived(first, watermark(appNanos = 1, lost = 3, final = true), receivedEpochMs = 1)
        IngestStates.connectionClosed(first)
        val second = IngestStates.connectionOpened("s", firstSeq = 20)
        IngestStates.watermarkReceived(second, watermark(appNanos = 2, lost = 2), receivedEpochMs = 2)

        assertEquals(TransportStatus(lostEvents = 3, sinceSeq = 10), IngestStates.snapshot("s").transport)
    }

    @Test
    fun `a skipped frame makes delivery uncertain`() {
        val connection = IngestStates.connectionOpened("s", firstSeq = 7)
        IngestStates.frameSkipped(connection)

        assertEquals(TransportStatus(deliveryUncertain = true, sinceSeq = 7), IngestStates.snapshot("s").transport)
    }

    @Test
    fun `a forgotten session gets no state back from late frames or a late close`() {
        val connection = IngestStates.connectionOpened("s", firstSeq = 1)
        IngestStates.forget("s")
        IngestStates.eventReceived(connection, receivedEpochMs = 1)
        IngestStates.watermarkReceived(connection, watermark(appNanos = 1), receivedEpochMs = 1)
        IngestStates.frameSkipped(connection)
        IngestStates.connectionClosed(connection)

        assertEquals(IngestSnapshot(), IngestStates.snapshot("s"))
    }

    @Test
    fun `watermarks are published to subscribers`() =
        runBlocking {
            val connection = IngestStates.connectionOpened("s", firstSeq = 1)
            var received: WatermarkObservation? = null
            val subscriber = launch { received = IngestStates.watermarks("s").first() }
            // Let the subscriber reach the shared flow before the watermark is published.
            repeat(10) { yield() }
            IngestStates.watermarkReceived(connection, watermark(appNanos = 9), receivedEpochMs = 3)
            subscriber.join()

            assertEquals(9L, received?.appNanos)
            assertTrue(subscriber.isCompleted)
        }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.IngestStatesTest"`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

`IngestStates.kt`:

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.health.TransportStatus
import com.jh.proj.coroutineviz.health.WatermarkObservation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * What [IngestStates] knows about one session; the defaults are "nothing ingested". [firstConnectionSeq] is the first
 * seq of the first connection this process saw for the session: events before it arrived through a connection nobody
 * here watched.
 */
data class IngestSnapshot(
    val watermark: WatermarkObservation? = null,
    val lastIngestEpochMs: Long? = null,
    val transport: TransportStatus = TransportStatus(),
    val firstConnectionSeq: Long? = null,
)

/**
 * What this backend process knows about each session's ingest (spec 2026-10-21, C-3 and C-7): the latest poll
 * watermark, when the last event arrived, and the transport status, which is sticky for the rest of the session,
 * across reconnects.
 *
 * A watermark is transport state, not a session event: it is neither stored nor numbered, so one per second does not
 * evict real events from the bounded store. Keyed by session id for the same reason as [IngestConnections] (the
 * database store builds a fresh `VizSession` on every read), and process-local like it. Nothing here is persisted yet:
 * after a restart a database session has no ingest state until its app connects again (Part 5b, spec C-4); the health
 * reads treat what this process did not watch as uncertain.
 */
object IngestStates {
    private const val WATERMARK_BUFFER = 16

    private val states = ConcurrentHashMap<String, State>()

    /** Guarded by itself. */
    internal class State {
        var watermark: WatermarkObservation? = null
        var lastIngestEpochMs: Long? = null
        var transport = TransportStatus()
        var firstConnectionSeq: Long? = null

        // Only the newest watermark matters to a stream that fell behind.
        val watermarks =
            MutableSharedFlow<WatermarkObservation>(
                extraBufferCapacity = WATERMARK_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
    }

    /**
     * One ingest socket. Its events get seqs from [firstSeq] on; it closed cleanly only once a final watermark arrived.
     * It writes to the state it was opened with, so a frame that arrives after the session was deleted (and forgotten)
     * cannot bring the session's state back.
     */
    class Connection internal constructor(
        val sessionId: String,
        val firstSeq: Long,
        internal val state: State,
    ) {
        @Volatile
        internal var finalWatermarkReceived = false
    }

    fun connectionOpened(
        sessionId: String,
        firstSeq: Long,
    ): Connection {
        val state = stateOf(sessionId)
        synchronized(state) { if (state.firstConnectionSeq == null) state.firstConnectionSeq = firstSeq }
        return Connection(sessionId, firstSeq, state)
    }

    /** An event frame arrived; with several sockets a frame received earlier can finish later, so the time never moves back. */
    fun eventReceived(
        connection: Connection,
        receivedEpochMs: Long,
    ) {
        val state = connection.state
        synchronized(state) { state.lastIngestEpochMs = maxOf(state.lastIngestEpochMs ?: Long.MIN_VALUE, receivedEpochMs) }
    }

    /**
     * A poll watermark arrived (spec C-7). The newest poll (by app clock) is kept, so a delayed frame from an overlapping
     * socket cannot move it back; every frame's loss facts count. The loss count is cumulative per client, so the largest
     * one is kept; a loss or an uncertain delivery dates from this connection's first seq at the latest.
     */
    fun watermarkReceived(
        connection: Connection,
        watermark: PollWatermark,
        receivedEpochMs: Long,
    ) {
        val state = connection.state
        val observation = WatermarkObservation(watermark.tsNanos, watermark.pollEpochMs, receivedEpochMs)
        synchronized(state) {
            val current = state.watermark
            if (current == null || observation.appNanos >= current.appNanos) state.watermark = observation
            val doubt = watermark.lostEvents > 0 || watermark.deliveryUncertain
            val t = state.transport
            state.transport =
                t.copy(
                    lostEvents = maxOf(t.lostEvents, watermark.lostEvents),
                    deliveryUncertain = t.deliveryUncertain || watermark.deliveryUncertain,
                    sinceSeq = if (doubt) earliest(t.sinceSeq, connection.firstSeq) else t.sinceSeq,
                )
        }
        if (watermark.final) connection.finalWatermarkReceived = true
        state.watermarks.tryEmit(observation)
    }

    /** A frame the backend could not read may have been an event: nothing vouches for this connection's events any more. */
    fun frameSkipped(connection: Connection) = markUncertain(connection)

    /** Without a final watermark nothing says every event of the connection arrived (spec C-7, H-3). */
    fun connectionClosed(connection: Connection) {
        if (!connection.finalWatermarkReceived) markUncertain(connection)
    }

    fun snapshot(sessionId: String): IngestSnapshot {
        val state = states[sessionId] ?: return IngestSnapshot()
        return synchronized(state) {
            IngestSnapshot(state.watermark, state.lastIngestEpochMs, state.transport, state.firstConnectionSeq)
        }
    }

    /** The session's watermarks as they arrive, for its SSE streams; earlier ones are not replayed. */
    fun watermarks(sessionId: String): Flow<WatermarkObservation> = stateOf(sessionId).watermarks

    /** Drops a deleted session's state; its open connections keep writing only to their own, now unreachable, copy. */
    fun forget(sessionId: String) {
        states.remove(sessionId)
    }

    internal fun clear() = states.clear()

    private fun stateOf(sessionId: String): State = states.computeIfAbsent(sessionId) { State() }
}

/** Delivery is uncertain from [connection]'s first seq on; sticky, like every transport fact. */
private fun markUncertain(connection: IngestStates.Connection) {
    val state = connection.state
    synchronized(state) {
        val t = state.transport
        state.transport = t.copy(deliveryUncertain = true, sinceSeq = earliest(t.sinceSeq, connection.firstSeq))
    }
}

private fun earliest(
    current: Long?,
    candidate: Long,
): Long = minOf(current ?: Long.MAX_VALUE, candidate)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.IngestStatesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): IngestStates keeps each session's watermark and transport status

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 6: Backend — the ingest route treats watermarks as transport state

**Files:**
- Modify: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/IngestRoutes.kt`, `backend/src/main/kotlin/com/jh/proj/coroutineviz/Routing.kt`
- Create (test support): `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthTestSupport.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/IngestWatermarkTest.kt`

- [ ] **Step 1: Write the test support and the failing test**

`HealthTestSupport.kt`:

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.fail

/** Events for health tests. Seqs are 0: the session stamps its own. */
internal object HealthEvents {
    fun sec(n: Long): Long = n * 1_000_000_000L

    fun created(
        sessionId: String,
        id: String,
        at: Long,
        parent: String? = null,
        origin: CoroutineOrigin? = CoroutineOrigin.APP,
    ) = CoroutineCreated(
        sessionId = sessionId,
        seq = 0,
        tsNanos = at,
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = parent,
        scopeId = "scope",
        label = id,
        origin = origin,
    )

    fun started(
        sessionId: String,
        id: String,
        at: Long,
        parent: String? = null,
    ) = CoroutineStarted(sessionId, 0, at, id, "job-$id", parent, "scope", id)

    fun suspended(
        sessionId: String,
        id: String,
        at: Long,
        provenance: SuspensionProvenance? = SuspensionProvenance.REAL_FRAME,
    ) = CoroutineSuspended(
        sessionId = sessionId,
        seq = 0,
        tsNanos = at,
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = null,
        scopeId = "scope",
        label = id,
        reason = "delay",
        suspensionPoint = SuspensionPoint(function = "work", fileName = "App.kt", lineNumber = 10, reason = "delay"),
        provenance = provenance,
    )

    fun completed(
        sessionId: String,
        id: String,
        at: Long,
        parent: String? = null,
    ) = CoroutineCompleted(sessionId, 0, at, id, "job-$id", parent, "scope", id)

    fun failed(
        sessionId: String,
        id: String,
        at: Long,
        parent: String? = null,
    ) = CoroutineFailed(
        sessionId = sessionId,
        seq = 0,
        tsNanos = at,
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = parent,
        scopeId = "scope",
        label = id,
        exceptionType = "java.lang.IllegalStateException",
        message = "boom",
        stackTrace = emptyList(),
    )

    fun watermark(
        sessionId: String,
        at: Long,
        lost: Long = 0,
        uncertain: Boolean = false,
        final: Boolean = false,
    ) = PollWatermark(sessionId, 0, at, pollEpochMs = 1_792_000_000_000, lostEvents = lost, deliveryUncertain = uncertain, final = final)
}

internal fun VizEvent.frameText(): String = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), this)

/** The frame an SDK from before triage sends: the same event without the fields it did not know. */
internal fun VizEvent.olderSdkFrameText(vararg newFields: String): String {
    val full = appJson.parseToJsonElement(frameText()).jsonObject
    return JsonObject(full.filterKeys { it !in newFields }).toString()
}

internal suspend fun HttpClient.createSessionNamed(name: String): String {
    val response = post("/api/sessions?name=$name")
    assertEquals(HttpStatusCode.Created, response.status)
    return appJson
        .parseToJsonElement(response.bodyAsText())
        .jsonObject["sessionId"]!!
        .jsonPrimitive.content
}

internal suspend fun HttpClient.getJson(path: String): JsonObject {
    val response: HttpResponse = get(path)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return appJson.parseToJsonElement(response.bodyAsText()).jsonObject
}

/** Polls [check] until it holds: the server handles frames and socket closes asynchronously to the client. */
internal suspend fun eventually(
    timeoutMs: Long = 5_000,
    check: suspend () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!check()) {
        if (System.currentTimeMillis() > deadline) fail("condition not met within $timeoutMs ms")
        delay(20)
    }
}
```

`IngestWatermarkTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ingest socket hands watermarks to [IngestStates] and records how each connection ended (spec C-3, C-7). */
class IngestWatermarkTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(WebSockets) }

    private suspend fun eventCount(
        client: io.ktor.client.HttpClient,
        sessionId: String,
    ): Int =
        com.jh.proj.coroutineviz.appJson
            .parseToJsonElement(client.get("/api/sessions/$sessionId/events").bodyAsText())
            .jsonArray.size

    @Test
    fun `a watermark is transport state, not a stored event`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-app")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(2)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }
            }

            assertEquals(1, eventCount(client, sessionId), "only the coroutine event is stored")
            val state = IngestStates.snapshot(sessionId)
            assertEquals(sec(2), state.watermark?.appNanos)
            assertNotNull(state.lastIngestEpochMs)
        }

    @Test
    fun `a watermark alone does not count as received data`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-only")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(2)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }
            }

            assertNull(IngestStates.snapshot(sessionId).lastIngestEpochMs)
        }

    @Test
    fun `a connection that ends after a final watermark closed cleanly`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-clean")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(2), final = true).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }
            }
            eventually { !IngestConnections.isConnected(sessionId) }

            assertEquals(false, IngestStates.snapshot(sessionId).transport.deliveryUncertain)
        }

    @Test
    fun `a connection that ends without a final watermark makes delivery uncertain from its first seq`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-unclean")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                eventually { eventCount(client, sessionId) == 1 }
            }
            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c2", at = sec(2)).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(3), final = true).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }
            }
            eventually { !IngestConnections.isConnected(sessionId) }

            val transport = IngestStates.snapshot(sessionId).transport
            assertTrue(transport.deliveryUncertain, "the first connection never sent a final watermark")
            assertEquals(1L, transport.sinceSeq)
        }

    @Test
    fun `a frame the backend cannot read makes delivery uncertain`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-garbage")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text("{not json"))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(3), lost = 2, final = true).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }
            }

            val transport = IngestStates.snapshot(sessionId).transport
            assertTrue(transport.deliveryUncertain)
            assertEquals(2L, transport.lostEvents)
            assertEquals(1L, transport.sinceSeq)
        }

    @Test
    fun `a binary data frame makes delivery uncertain, even before a final watermark`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-binary")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Binary(true, HealthEvents.failed(sessionId, "c1", at = sec(2)).frameText().toByteArray()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(3), final = true).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }
            }
            eventually { !IngestConnections.isConnected(sessionId) }

            val transport = IngestStates.snapshot(sessionId).transport
            assertTrue(transport.deliveryUncertain, "the binary frame was not read")
            assertEquals(1L, transport.sinceSeq)
        }

    @Test
    fun `origin and provenance arrive with the events`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-origin")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.started(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.suspended(sessionId, "c1", at = sec(2)).frameText()))
                eventually { eventCount(client, sessionId) == 3 }
            }

            val summary = SessionManager.getSession(sessionId)!!.readSummaries { it.get("c1") }!!
            assertEquals(com.jh.proj.coroutineviz.events.CoroutineOrigin.APP, summary.origin)
            assertEquals(com.jh.proj.coroutineviz.events.SuspensionProvenance.REAL_FRAME, summary.lastSuspensionProvenance)
        }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.IngestWatermarkTest"`
Expected: FAIL — the watermark is stored as an event (2 events), `IngestStates` has no watermark and no transport facts.

- [ ] **Step 3: Implement**

`IngestRoutes.kt`:

1. Imports: `com.jh.proj.coroutineviz.events.PollWatermark`.
2. Right after the `if (!IngestConnections.openedIfPresent(...)) { … }` block, before `try`:

```kotlin
        // This connection's events get seqs after everything appended so far (spec C-3 `transport.sinceSeq`).
        val connection = IngestStates.connectionOpened(sessionId, firstSeq = session.lastSeq + 1)
```

3. In the loop, the `if (frame is Frame.Text) { publishFrame(session, sessionId, …) }` becomes (D-2: a frame the backend
   does not read may have been an event):

```kotlin
                when (frame) {
                    is Frame.Text -> publishFrame(session, connection, frame.readText())
                    // Events travel as text; a binary data frame is not read, and may have been one.
                    is Frame.Binary -> IngestStates.frameSkipped(connection)
                    else -> Unit
                }
```

4. The `finally` becomes:

```kotlin
        } finally {
            IngestConnections.closed(sessionId)
            IngestStates.connectionClosed(connection)
            sessionRemoved.cancel()
            session.ingestSocketClosed()
        }
```

5. Replace `publishFrame` (keep its KDoc and add the two new bullets):

```kotlin
/**
 * Deserialize one text frame and publish it into the SERVER-resolved [session].
 *
 *  - T-07-05: a malformed frame is skipped (logged at debug); the caller keeps
 *    looping so the stream stays open — one bad frame never drops the connection.
 *    It may have been an event, so delivery becomes uncertain ([IngestStates.frameSkipped]).
 *  - T-07-03: the frame's own `sessionId` is REWRITTEN to the server-resolved
 *    [VizSession.sessionId] before the event is decoded ([withSessionId]), so the
 *    stored, replayed and streamed event carries the session the request resolved
 *    to — whatever id the client put in the body, including one naming another
 *    tenant's session or a session that does not exist. Only the target session is
 *    ever written to; the frame body never selects a destination.
 *  - The server also assigns `seq` (`ingest()` never keeps the client's value), so
 *    session order stays strictly increasing.
 *  - A [PollWatermark] is transport state (spec C-7): it goes to [IngestStates], never into the
 *    store. Frames of one socket are handled in order, so the poll's events are applied first.
 */
private fun publishFrame(
    session: VizSession,
    connection: IngestStates.Connection,
    text: String,
) {
    val event =
        runCatching {
            val stamped = appJson.parseToJsonElement(text).withSessionId(session.sessionId)
            appJson.decodeFromJsonElement(PolymorphicSerializer(VizEvent::class), stamped)
        }.getOrElse {
            ingestLogger.debug("Skipped malformed ingest frame for session {}: {}", connection.sessionId, it.message)
            IngestStates.frameSkipped(connection)
            return
        }
    val receivedEpochMs = System.currentTimeMillis()
    if (event is PollWatermark) {
        IngestStates.watermarkReceived(connection, event, receivedEpochMs)
        return
    }
    // Recorded before the append: "last data" is never earlier than an event a health read includes.
    IngestStates.eventReceived(connection, receivedEpochMs)
    session.ingest(event)
}
```

`Routing.kt` — next to the `CorrelationRegistry.evict` hook (same comment style):

```kotlin
    // A deleted session's ingest state (watermark, transport) goes with it.
    SessionManager.addOnSessionClosed { sessionId -> IngestStates.forget(sessionId) }
```

(import `com.jh.proj.coroutineviz.routes.IngestStates`).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.IngestWatermarkTest" --tests "com.jh.proj.coroutineviz.routes.Ingest*"`
Expected: PASS (existing ingest tests included).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): ingest keeps watermarks as transport state and records unclean closes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 7: Backend — database sessions keep summaries and their creation time

**Files:**
- Modify: `backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/ExposedSessionStore.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/persistence/ExposedSessionSummariesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ExposedSessionSummariesTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var store: ExposedSessionStore

    @BeforeEach
    fun setUp() {
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    password = ""
                    maximumPoolSize = 4
                    isAutoCommit = false
                },
            )
        val db: Database = DatabaseFactory.init(dataSource as DataSource)
        store = ExposedSessionStore(db)
    }

    @AfterEach
    fun tearDown() = dataSource.close()

    @Test
    fun `a rebuilt database session folds its summaries from the stored events`() {
        val sessionId = runBlocking { store.createSession("db-summaries") }.sessionId
        store.getSession(sessionId)!!.ingest(CoroutineCreated(sessionId, 0, 5, "c1", "j", null, "s", "c1"))

        val rebuilt = assertNotNull(store.getSession(sessionId))

        assertEquals(listOf("c1"), rebuilt.readSummaries { view -> view.all.map { it.id } })
    }

    @Test
    fun `a rebuilt database session keeps the row's creation time`() {
        val created = runBlocking { store.createSession("db-created") }
        Thread.sleep(5)

        val first = assertNotNull(store.getSession(created.sessionId)).createdAtMs
        Thread.sleep(5)
        val second = assertNotNull(store.getSession(created.sessionId)).createdAtMs

        assertEquals(created.createdAtMs, first)
        assertEquals(first, second)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.persistence.ExposedSessionSummariesTest"`
Expected: FAIL — `SummariesUnavailableException` (no summaries kept), and `createdAtMs` is the read time.

- [ ] **Step 3: Implement**

`ExposedSessionStore.kt` (`selectAll`, `and`, `eq` are already imported):

1. `createSession` — capture the time once and pass it on:

```kotlin
        val now = Clock.System.now()
        transaction(db) {
            SessionsTable.insert {
                it[id] = sessionId
                it[SessionsTable.name] = name
                it[createdAt] = now
                it[scenario] = null
                it[tenantId] = owner
                it[metadata] = null
            }
        }
        logger.info("Created session (db): $sessionId (tenant=${owner ?: "<global>"})")
        return buildSession(sessionId, now.toEpochMilliseconds())
```

2. `getSession`:

```kotlin
    override fun getSession(
        sessionId: String,
        tenant: TenantContext,
    ): VizSession? = createdAtMsOf(sessionId, tenant)?.let { buildSession(sessionId, it) }

    /** The session row's creation time, or null when no row is visible to [tenant]. */
    @OptIn(ExperimentalTime::class)
    private fun createdAtMsOf(
        sessionId: String,
        tenant: TenantContext,
    ): Long? =
        transaction(db) {
            SessionsTable
                .selectAll()
                .where { tenantPredicate(tenant) and (SessionsTable.id eq sessionId) }
                .limit(1)
                .firstOrNull()
                ?.get(SessionsTable.createdAt)
                ?.toEpochMilliseconds()
        }
```

3. `buildSession`:

```kotlin
    /**
     * Build a [VizSession] whose event store is an [ExposedEventStore] scoped to
     * [sessionId]. Uses the injectable event-store-factory seam on VizSession so
     * no DB dependency leaks into coroutine-viz-core. The session keeps summaries, folded
     * from the stored events by the rehydration below: complete while the store was never
     * trimmed (the health route says so once it was; Part 5b persists them).
     */
    private fun buildSession(
        sessionId: String,
        createdAtMs: Long,
    ): VizSession =
        VizSession(
            sessionId = sessionId,
            maxEvents = maxEvents,
            createdAtMs = createdAtMs,
            keepSummaries = true,
            eventStoreFactory = { id -> ExposedEventStore(db, id) },
        ).also {
            // A DB-backed session is reconstructed fresh on every read with empty
            // in-memory read models — replay the persisted events so the coroutine
            // tree / threads / timeline projections are populated (PERS visualization).
            it.rehydrateFromStore()
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.persistence.*" --tests "com.jh.proj.coroutineviz.routes.SessionDeleteDatabaseModeTest" --tests "com.jh.proj.coroutineviz.routes.DemoRunDatabaseModeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): database sessions keep summaries and their row's creation time

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 8: Backend — `GET /api/sessions/{id}/health`, live and with `atSeq`

**Files:**
- Create: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionHealthReads.kt`,
  `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionHealthRoutes.kt`
- Modify: `backend/src/main/kotlin/com/jh/proj/coroutineviz/Routing.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionHealthRouteTest.kt`,
  `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionHealthDatabaseModeTest.kt`

- [ ] **Step 1: Write the failing tests**

`SessionHealthRouteTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.AsOfKind
import com.jh.proj.coroutineviz.health.EvaluationStatus
import com.jh.proj.coroutineviz.health.FailedRoundIncident
import com.jh.proj.coroutineviz.health.HealthState
import com.jh.proj.coroutineviz.health.NotEvaluatedReason
import com.jh.proj.coroutineviz.health.PossiblyBlockedIncident
import com.jh.proj.coroutineviz.health.SessionHealth
import com.jh.proj.coroutineviz.health.SessionOrigin
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionHealthRouteTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    private suspend fun HttpClient.health(path: String): SessionHealth {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(SessionHealth.serializer(), response.bodyAsText())
    }

    /** A demo round: a request whose child failed. Returns the seq of the event before the failure. */
    private fun failingDemo(session: VizSession): Long {
        val id = session.sessionId
        session.send(HealthEvents.created(id, "req", at = sec(1)))
        session.send(HealthEvents.created(id, "child", at = sec(1), parent = "req"))
        session.send(HealthEvents.started(id, "child", at = sec(2), parent = "req"))
        val beforeFailure = session.lastSeq
        session.send(HealthEvents.failed(id, "child", at = sec(3), parent = "req"))
        return beforeFailure
    }

    @Test
    fun `an unknown session is not found`() =
        testApplication {
            application { module() }
            assertEquals(HttpStatusCode.NotFound, client.get("/api/sessions/nope/health").status)
        }

    @Test
    fun `atSeq must be a non-negative number`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-bad-seq")

            assertEquals(HttpStatusCode.BadRequest, client.get("/api/sessions/$sessionId/health?atSeq=-1").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/sessions/$sessionId/health?atSeq=abc").status)
        }

    @Test
    fun `a failed demo round is one problem, and possibly blocked is not evaluated for demos`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-health")
            failingDemo(SessionManager.getSession(sessionId)!!)

            val health = client.health("/api/sessions/$sessionId/health")

            assertEquals(HealthState.PROBLEMS, health.state)
            val round = assertIs<FailedRoundIncident>(health.incidents.items.single())
            assertEquals("req", round.anchorId)
            assertEquals(NotEvaluatedReason.DEMO, health.possiblyBlocked.reason)
            assertEquals(AsOfKind.LAST_EVENT, health.asOf?.kind)
            assertEquals(4L, health.asOfSeq)
            assertEquals(false, health.retention?.trimmed)
            assertEquals(1L, health.retention?.oldestRetainedSeq)
        }

    @Test
    fun `the wire tags incidents by type`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-wire")
            failingDemo(SessionManager.getSession(sessionId)!!)

            val incident =
                client
                    .getJson("/api/sessions/$sessionId/health")["incidents"]!!
                    .jsonObject["items"]!!
                    .let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject }

            assertEquals("FAILED_ROUND", incident["type"]?.jsonPrimitive?.content)
        }

    @Test
    fun `atSeq replays the health at the cursor`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-replay")
            val beforeFailure = failingDemo(SessionManager.getSession(sessionId)!!)

            val before = client.health("/api/sessions/$sessionId/health?atSeq=$beforeFailure")
            val after = client.health("/api/sessions/$sessionId/health?atSeq=99")

            assertEquals(0, before.problemCount)
            assertEquals(beforeFailure, before.asOfSeq)
            assertEquals(AsOfKind.REPLAY_CURSOR, before.asOf?.kind)
            assertEquals(NotEvaluatedReason.REPLAY, before.possiblyBlocked.reason)
            assertEquals(1, after.problemCount)
            assertEquals(4L, after.asOfSeq, "a cursor past the last event is clamped to it")
        }

    @Test
    fun `an attached app without poll data is not evaluated for possibly blocked`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("app-no-watermark")
            val session = SessionManager.getSession(sessionId)!!
            session.ingest(HealthEvents.created(sessionId, "c1", at = sec(1)))
            session.ingest(HealthEvents.started(sessionId, "c1", at = sec(1)))

            val health = client.health("/api/sessions/$sessionId/health")

            assertEquals(NotEvaluatedReason.NO_WATERMARK, health.possiblyBlocked.reason)
            assertEquals(AsOfKind.INGEST, health.asOf?.kind)
            assertNull(health.asOfWatermark)
        }

    @Test
    fun `a coroutine running past the threshold up to the watermark is possibly blocked`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("app-blocked")

            ws.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "worker", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.started(sessionId, "worker", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(12)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }

                // Read while connected: closing without a final watermark makes delivery uncertain.
                val health = ws.health("/api/sessions/$sessionId/health")

                assertEquals(EvaluationStatus.EVALUATED, health.possiblyBlocked.status)
                val blocked = assertIs<PossiblyBlockedIncident>(health.incidents.items.single())
                assertEquals("worker", blocked.coroutineId)
                assertEquals(sec(11), blocked.runningForNanos)
                assertEquals(AsOfKind.WATERMARK, health.asOf?.kind)
                assertEquals(sec(12), health.asOfWatermark?.appNanos)
            }
        }

    @Test
    fun `after an unclean close failures read at least and possibly blocked is not evaluated`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("app-unclean")

            ws.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                eventually { SessionManager.getSession(sessionId)!!.lastSeq == 1L }
            }
            eventually { IngestStates.snapshot(sessionId).transport.deliveryUncertain }

            val health = ws.health("/api/sessions/$sessionId/health")

            assertTrue(health.failuresAtLeast)
            assertEquals(NotEvaluatedReason.EVENTS_DROPPED, health.possiblyBlocked.reason)
            assertEquals(1L, health.transport.sinceSeq)
        }

    @Test
    fun `the leak threshold parameter reaches the long-lived observation`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-leak")
            val session = SessionManager.getSession(sessionId)!!
            session.send(HealthEvents.created(sessionId, "slow", at = sec(1)))
            session.send(HealthEvents.started(sessionId, "slow", at = sec(3)))

            val byDefault = client.health("/api/sessions/$sessionId/health")
            val short = client.health("/api/sessions/$sessionId/health?leakThresholdMs=1000")

            assertEquals(0, byDefault.observations.longLived.total)
            assertEquals(1, short.observations.longLived.total)
        }

    @Test
    fun `a trimmed memory session keeps its failures live and in replay`() =
        testApplication {
            application { module() }
            // The app configured the default on start; a tiny store forces a trim.
            client.get("/api/sessions")
            SessionManager.configure(maxEventsPerSession = 3)
            try {
                val sessionId = client.createSessionNamed("scenario-trim")
                val session = SessionManager.getSession(sessionId)!!
                failingDemo(session)
                session.send(HealthEvents.completed(sessionId, "req", at = sec(4)))

                val live = client.health("/api/sessions/$sessionId/health")
                val replay = client.health("/api/sessions/$sessionId/health?atSeq=4")
                val early = client.health("/api/sessions/$sessionId/health?atSeq=1")

                assertEquals(true, live.retention?.trimmed)
                assertEquals(3L, live.retention?.oldestRetainedSeq)
                assertEquals(1, live.failedRoundCount)
                assertEquals(false, live.failuresAtLeast, "memory summaries saw every event")
                assertEquals(1, replay.failedRoundCount, "the seed keeps the trimmed creations")
                assertEquals(0, early.problemCount)
                assertEquals(1L, early.asOfSeq)
            } finally {
                SessionManager.configure()
            }
        }

    @Test
    fun `an app on an SDK from before triage is evaluated with what it sends`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("app-old-sdk")

            ws.webSocket("/api/sessions/$sessionId/ingest") {
                // No origin, no provenance, no watermark.
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).olderSdkFrameText("origin")))
                send(Frame.Text(HealthEvents.started(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.suspended(sessionId, "c1", at = sec(2)).olderSdkFrameText("provenance")))
                eventually { SessionManager.getSession(sessionId)!!.lastSeq == 3L }

                val health = ws.health("/api/sessions/$sessionId/health")

                assertEquals(SessionOrigin.UNKNOWN, health.sessionOrigin)
                assertEquals(NotEvaluatedReason.NO_WATERMARK, health.possiblyBlocked.reason)
                assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state, "never green without origins")
                assertEquals(1, health.counts.unknown)
            }
        }

    @Test
    fun `a blocked coroutine whose events were trimmed from memory is still detected`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            ws.get("/api/sessions")
            SessionManager.configure(maxEventsPerSession = 3)
            try {
                val sessionId = ws.createSessionNamed("app-trimmed")
                ws.webSocket("/api/sessions/$sessionId/ingest") {
                    send(Frame.Text(HealthEvents.created(sessionId, "worker", at = sec(1)).frameText()))
                    send(Frame.Text(HealthEvents.started(sessionId, "worker", at = sec(1)).frameText()))
                    send(Frame.Text(HealthEvents.created(sessionId, "f1", at = sec(2)).frameText()))
                    send(Frame.Text(HealthEvents.completed(sessionId, "f1", at = sec(2)).frameText()))
                    send(Frame.Text(HealthEvents.created(sessionId, "f2", at = sec(3)).frameText()))
                    send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(12)).frameText()))
                    eventually { IngestStates.snapshot(sessionId).watermark != null }

                    val health = ws.health("/api/sessions/$sessionId/health")

                    assertEquals(true, health.retention?.trimmed)
                    val blocked = assertIs<PossiblyBlockedIncident>(health.incidents.items.single())
                    assertEquals("worker", blocked.coroutineId)
                    assertTrue(blocked.transitionsGap, "its transitions were trimmed")
                }
            } finally {
                SessionManager.configure()
            }
        }

    @Test
    fun `transport facts that arrive while a health is read are not lost`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("app-race")
            val session = SessionManager.getSession(sessionId)!!
            session.ingest(HealthEvents.created(sessionId, "c1", at = sec(1)))
            session.ingest(HealthEvents.started(sessionId, "c1", at = sec(1)))
            session.ingest(HealthEvents.suspended(sessionId, "c1", at = sec(2)))
            val connection = IngestStates.connectionOpened(sessionId, firstSeq = 1)
            IngestStates.watermarkReceived(connection, HealthEvents.watermark(sessionId, at = sec(3)), receivedEpochMs = 1)
            // The cut a read took before resolving the session: clean so far.
            val cut = IngestStates.snapshot(sessionId)
            // Then a frame could not be read before the summaries were copied.
            IngestStates.frameSkipped(connection)

            val health = readSessionHealth(session, cut, atSeq = null, leakThresholdMs = 30_000)

            assertTrue(health.transport.deliveryUncertain)
            assertTrue(health.failuresAtLeast)
            assertEquals(NotEvaluatedReason.EVENTS_DROPPED, health.possiblyBlocked.reason)
        }
}
```

The `503` branch (abandoned summaries) is not route-tested: the only way to abandon a table is core's `internal`
`summaryFailureForTest` hook, which the backend module cannot reach, and widening it for one `catch` is not worth it.

`SessionHealthDatabaseModeTest.kt`:

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.EvaluationStatus
import com.jh.proj.coroutineviz.health.NotEvaluatedReason
import com.jh.proj.coroutineviz.health.PossiblyBlockedIncident
import com.jh.proj.coroutineviz.health.SessionHealth
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.DbRetentionPolicy
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.persistence.tables.SessionsTable
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Database sessions fold their summaries from the stored events on every read. What this process did not watch is not
 * vouched for, and a trimmed history makes the health unavailable until Part 5b persists the summaries (D-9).
 */
@OptIn(ExperimentalTime::class)
class SessionHealthDatabaseModeTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    password = ""
                    maximumPoolSize = 4
                    isAutoCommit = false
                },
            )
        db = DatabaseFactory.init(dataSource as DataSource)
        SessionManager.useStore(ExposedSessionStore(db))
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        IngestStates.clear()
        dataSource.close()
    }

    private suspend fun HttpClient.health(sessionId: String): SessionHealth {
        val response = get("/api/sessions/$sessionId/health")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(SessionHealth.serializer(), response.bodyAsText())
    }

    /** Events stored by a connection this process never saw: a backend restart in between. */
    private fun storedByAnEarlierProcess(sessionId: String) {
        val writer = SessionManager.getSession(sessionId)!!
        writer.ingest(HealthEvents.created(sessionId, "req", at = sec(1)))
        writer.ingest(HealthEvents.created(sessionId, "child", at = sec(1), parent = "req"))
        writer.ingest(HealthEvents.failed(sessionId, "child", at = sec(2), parent = "req"))
    }

    @Test
    fun `events from a connection this process never watched are not vouched for`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("db-restarted")
            storedByAnEarlierProcess(sessionId)

            val health = client.health(sessionId)

            assertEquals(1, health.failedRoundCount)
            assertEquals(false, health.retention?.trimmed)
            assertTrue(health.transport.deliveryUncertain)
            assertEquals(1L, health.transport.sinceSeq)
            assertTrue(health.failuresAtLeast)
            assertEquals(NotEvaluatedReason.EVENTS_DROPPED, health.possiblyBlocked.reason)
        }

    @Test
    fun `a trimmed database session's health is unavailable rather than overcounted`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("db-trimmed")
            storedByAnEarlierProcess(sessionId)

            DbRetentionPolicy(db = db, maxEventsPerSession = 2).cleanup()
            val response = client.get("/api/sessions/$sessionId/health")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("history-trimmed"), response.bodyAsText())
        }

    @Test
    fun `a connected database session's health sees each new watermark`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("db-live")

            ws.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "worker", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.started(sessionId, "worker", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(5)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark?.appNanos == sec(5) }

                val early = ws.health(sessionId)
                assertEquals(EvaluationStatus.EVALUATED, early.possiblyBlocked.status)
                assertEquals(0, early.problemCount)
                assertEquals(sec(5), early.asOfWatermark?.appNanos)

                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(12)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark?.appNanos == sec(12) }

                val later = ws.health(sessionId)
                assertEquals("worker", assertIs<PossiblyBlockedIncident>(later.incidents.items.single()).coroutineId)
                assertEquals(sec(12), later.asOfWatermark?.appNanos)
            }
        }

    @Test
    fun `a session stored before this backend started is not vouched for, even without events`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("db-recovered")
            // As if the row was written by an earlier backend process (crashed before any event was stored).
            transaction(db) {
                SessionsTable.update({ SessionsTable.id eq sessionId }) { it[createdAt] = Instant.fromEpochMilliseconds(0) }
            }

            val health = client.health(sessionId)

            assertTrue(health.transport.deliveryUncertain)
            assertEquals(1L, health.transport.sinceSeq)
        }

    @Test
    fun `a session created by this backend and not yet connected awaits data with a clean transport`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("db-fresh")

            val health = client.health(sessionId)

            assertEquals(false, health.transport.deliveryUncertain)
            assertEquals(com.jh.proj.coroutineviz.health.HealthState.AWAITING_FIRST_DATA, health.state)
        }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.SessionHealthRouteTest" --tests "com.jh.proj.coroutineviz.routes.SessionHealthDatabaseModeTest"`
Expected: FAIL — `/health` answers 404 (no route).

- [ ] **Step 3: Implement**

`SessionHealthReads.kt`:

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.health.HealthInput
import com.jh.proj.coroutineviz.health.HealthMode
import com.jh.proj.coroutineviz.health.RetentionInfo
import com.jh.proj.coroutineviz.health.SessionHealth
import com.jh.proj.coroutineviz.health.SessionHealthDerivation
import com.jh.proj.coroutineviz.health.SessionKind
import com.jh.proj.coroutineviz.health.SessionOrigin
import com.jh.proj.coroutineviz.health.SummariesUnavailableException
import com.jh.proj.coroutineviz.health.SummaryFold
import com.jh.proj.coroutineviz.health.SummaryView
import com.jh.proj.coroutineviz.health.Transition
import com.jh.proj.coroutineviz.health.Transitions
import com.jh.proj.coroutineviz.health.TransportStatus
import com.jh.proj.coroutineviz.health.WatermarkObservation
import com.jh.proj.coroutineviz.health.snapshot
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import java.lang.management.ManagementFactory

/**
 * Whether a session's summaries saw every event it ever had. In memory they are applied on every append and outlive
 * the bounded store's trim; in database mode they are folded from the stored events on every rebuild (until Part 5b
 * persists them), so a trim is invisible to them.
 */
internal fun summariesSeeEveryEvent(): Boolean = SessionManager.backingStore() !is ExposedSessionStore

/**
 * Retention facts (spec C-3 `retention`). [firstSeqEver] is the first seq the session had: the summaries' first seq
 * when they saw every event, else 1, the first seq every session gets (D-8).
 */
internal fun retentionOf(
    events: List<VizEvent>,
    firstSeqEver: Long,
): RetentionInfo {
    val oldest = events.firstOrNull()
    return RetentionInfo(oldest?.seq, oldest?.tsNanos, trimmed = oldest != null && oldest.seq > firstSeqEver)
}

/**
 * A database session whose stored events were trimmed (until Part 5b persists its summaries): its summaries are folded
 * from what is left, and a coroutine whose parent's creation was trimmed loses its round, so one failed round can read
 * as several ungrouped failures. "At least" would then overstate, so the health is unavailable instead (H-1, H-3).
 */
internal class HistoryTrimmedException(
    message: String,
) : IllegalStateException(message)

/**
 * The transport a read reports (spec C-3 `transport`). A database session outlives this process: a row written before
 * this JVM started, or events stored before the first connection this process saw, came through connections nobody
 * here watched end (a crash runs no close handler), so nothing vouches that all their events arrived (H-3). Until Part
 * 5b persists the transport and the "ingest open" marker, such an ATTACHED session reads delivery-uncertain from its
 * first seq.
 */
internal fun transportOf(
    ingest: IngestSnapshot,
    session: VizSession,
    kind: SessionKind,
    lastSeq: Long,
): TransportStatus {
    // A row written before this JVM started may have had a connection that ended in the crash, even with no event stored.
    val recovered = session.createdAtMs < PROCESS_STARTED_AT_MS
    val eventsBeforeWatching = lastSeq > 0 && (ingest.firstConnectionSeq ?: Long.MAX_VALUE) > 1
    val unwatched = !summariesSeeEveryEvent() && kind == SessionKind.ATTACHED && (recovered || eventsBeforeWatching)
    return if (unwatched) ingest.transport.copy(deliveryUncertain = true, sinceSeq = 1) else ingest.transport
}

private val PROCESS_STARTED_AT_MS: Long = ManagementFactory.getRuntimeMXBean().startTime

/**
 * The session's health (spec C-3): live when [atSeq] is null, else the replay fold at the cursor, clamped to the last
 * event. [watermarkCut] must be read before [session] was resolved: in database mode resolving rebuilds the session from the
 * stored events, and a watermark is recorded only after its poll's events were stored, so every event the watermark
 * vouches for is in the rebuilt summaries (spec C-7). The other order could compare a newer watermark with an older run
 * start and report a coroutine that has since suspended.
 *
 * @throws com.jh.proj.coroutineviz.health.SummariesUnavailableException when the session keeps no usable summaries.
 * @throws HistoryTrimmedException for a trimmed database session.
 */
internal fun readSessionHealth(
    session: VizSession,
    watermarkCut: IngestSnapshot,
    atSeq: Long?,
    leakThresholdMs: Long,
): SessionHealth {
    val durable = session.readSummaries { it.snapshot() }
    // Transport facts are sticky and only grow, so the ones read after the copy cover every event in it: a frame skipped
    // or a connection lost while this read ran must not be missed (a newer watermark could not do harm here, a cleaner
    // transport could). The watermark stays the one from before the session was resolved.
    val ingest = IngestStates.snapshot(session.sessionId).copy(watermark = watermarkCut.watermark)
    // Read after the copy: the store holds at least every event the copy saw, possibly newer ones.
    val events = session.store.all()
    val everyEvent = summariesSeeEveryEvent()
    val retention = retentionOf(events, firstSeqEver = durable.firstSeqEver(everyEvent))
    if (retention.trimmed && !everyEvent) throw HistoryTrimmedException("session ${session.sessionId} was trimmed")
    val kind = sessionKindOf(session.sessionId)
    val input =
        HealthInput(
            summaries = durable,
            kind = kind,
            watermark = ingest.watermark,
            lastIngestEpochMs = ingest.lastIngestEpochMs,
            transport = transportOf(ingest, session, kind, durable.lastEventSeq),
            leakThresholdMs = leakThresholdMs,
            retention = retention,
        )
    if (atSeq == null) {
        val transitions = RetainedTransitions(events, upToSeq = durable.lastEventSeq)
        return SessionHealthDerivation.derive(input.copy(transitionsFor = transitions::of))
    }
    val cursor = minOf(atSeq, durable.lastEventSeq)
    return SessionHealthDerivation.derive(
        input.copy(
            summaries = SummaryFold.foldTo(events, cursor, durable),
            mode = HealthMode.Replay(cursor),
            // The seed cannot tell which coroutines beyond capacity existed at the cursor: their failures may be missing.
            historyIncomplete = retention.trimmed && durable.untrackedCount > 0,
        ),
    )
}

/** Retained lifecycle transitions per coroutine up to [upToSeq], indexed on first use (`HealthInput.transitionsFor`). */
internal class RetainedTransitions(
    events: List<VizEvent>,
    upToSeq: Long,
) {
    private val byCoroutine: Map<String, List<Transition>> by lazy {
        val index = HashMap<String, MutableList<Transition>>()
        for (event in events) {
            if (event.seq > upToSeq || event !is CoroutineEvent) continue
            Transitions.of(event)?.let { index.getOrPut(event.coroutineId) { mutableListOf() }.add(it) }
        }
        index
    }

    fun of(coroutineId: String): List<Transition> = byCoroutine[coroutineId].orEmpty()
}

/** The first seq the session had (D-8): the summaries' own when they saw every event, else 1, every session's first. */
internal fun SummaryView.firstSeqEver(everyEvent: Boolean): Long = if (everyEvent) firstEventSeq else 1L
```

`SessionHealthRoutes.kt`:

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.SummariesUnavailableException
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /api/sessions/{id}/health[?atSeq=N][&leakThresholdMs=M]` (spec 2026-10-21, C-3): the session's derived health,
 * live or at a replay cursor. Bounded for a once-per-second refetch: every list is capped with its total. Tenant
 * scoped like every session read.
 */
fun Route.registerSessionHealthRoutes() {
    get("/api/sessions/{id}/health") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing session ID"))
                return@get
            }
        val rawAtSeq = call.request.queryParameters["atSeq"]
        val atSeq = rawAtSeq?.toLongOrNull()?.takeIf { it >= 0 }
        if (rawAtSeq != null && atSeq == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("atSeq must be a non-negative integer"))
            return@get
        }
        // Before the session is resolved: see readSessionHealth.
        val ingest = IngestStates.snapshot(sessionId)
        val session = call.resolveScopedSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Session not found"))
            return@get
        }
        // H-3: unknown is shown as unknown, never as healthy.
        val health =
            try {
                readSessionHealth(session, ingest, atSeq, call.leakThresholdMs())
            } catch (_: SummariesUnavailableException) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Health is unavailable for this session", code = "summaries-unavailable"),
                )
                return@get
            } catch (_: HistoryTrimmedException) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Health is unavailable: this session's early events were trimmed", code = "history-trimmed"),
                )
                return@get
            }
        call.respond(HttpStatusCode.OK, health)
    }
}
```

`Routing.kt` — after `registerSessionRoutes()`:

```kotlin
                registerSessionHealthRoutes()
```

(import `com.jh.proj.coroutineviz.routes.registerSessionHealthRoutes`).

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): GET /api/sessions/{id}/health, live and at a replay cursor

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 9: Backend — `GET /api/sessions/{id}` gains the C-3 session fields

**Files:**
- Modify: `…/routes/SessionHealthReads.kt`, `…/routes/RouteDtos.kt`, `…/routes/SessionRoutes.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionFactsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionFactsTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @Test
    fun `a demo session reports its kind, start, capture start and retention`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-facts")
            val session = SessionManager.getSession(sessionId)!!
            session.send(HealthEvents.created(sessionId, "a", at = sec(7)))
            session.send(HealthEvents.started(sessionId, "a", at = sec(5)))

            val body = client.getJson("/api/sessions/$sessionId")

            assertEquals("DEMO", body["kind"]!!.jsonPrimitive.content)
            assertEquals(session.createdAtMs, body["startedAtEpochMs"]!!.jsonPrimitive.long)
            assertEquals(sec(5), body["captureStartAppNanos"]!!.jsonPrimitive.long)
            assertEquals("REPORTED", body["origin"]!!.jsonPrimitive.content)
            assertEquals(false, body["appConnected"]!!.jsonPrimitive.boolean)
            assertEquals(JsonNull, body["lastWatermark"])
            assertEquals(1L, body["oldestRetainedSeq"]!!.jsonPrimitive.long)
            assertEquals(false, body["trimmed"]!!.jsonPrimitive.boolean)
            // Existing fields are unchanged.
            assertEquals(2, body["eventCount"]!!.jsonPrimitive.content.toInt())
        }

    @Test
    fun `a connected app reports its connection, last data and last watermark`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("facts-app")

            ws.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "a", at = sec(1), origin = null).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(2)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }

                val body = ws.getJson("/api/sessions/$sessionId")

                assertEquals("ATTACHED", body["kind"]!!.jsonPrimitive.content)
                assertEquals(true, body["appConnected"]!!.jsonPrimitive.boolean)
                assertTrue(body["lastIngestEpochMs"]!!.jsonPrimitive.long > 0)
                assertEquals(sec(2), body["lastWatermark"]!!.jsonObject["appNanos"]!!.jsonPrimitive.long)
                assertEquals("UNKNOWN", body["origin"]!!.jsonPrimitive.content, "no coroutine reported an origin")
            }
        }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.SessionFactsTest"`
Expected: FAIL — `kind` is missing from the response.

- [ ] **Step 3: Implement**

`SessionHealthReads.kt` — add:

```kotlin
/** The C-3 session facts of `GET /api/sessions/{id}`, read from the ingest state, the summaries and [events]. */
internal data class SessionFacts(
    val kind: SessionKind,
    val captureStartAppNanos: Long?,
    val origin: SessionOrigin,
    val appConnected: Boolean,
    val lastIngestEpochMs: Long?,
    val lastWatermark: WatermarkObservation?,
    val retention: RetentionInfo,
)

internal fun readSessionFacts(
    session: VizSession,
    events: List<VizEvent>,
): SessionFacts {
    val kind = sessionKindOf(session.sessionId)
    val ingest = IngestStates.snapshot(session.sessionId)
    // Abandoned summaries leave origin and capture start unknown (H-3); the rest of the session still answers.
    val durable =
        try {
            session.readSummaries { it.snapshot() }
        } catch (_: SummariesUnavailableException) {
            null
        }
    val everyEvent = summariesSeeEveryEvent()
    val retention = retentionOf(events, firstSeqEver = durable?.firstSeqEver(everyEvent) ?: 1L)
    return SessionFacts(
        kind = kind,
        // A database session that was trimmed no longer knows when its capture started (D-7).
        captureStartAppNanos = durable?.earliestEventAppNanos?.takeIf { everyEvent || !retention.trimmed },
        origin = durable?.let { SessionHealthDerivation.sessionOrigin(it, kind) } ?: SessionOrigin.UNKNOWN,
        appConnected = IngestConnections.isConnected(session.sessionId),
        lastIngestEpochMs = ingest.lastIngestEpochMs,
        lastWatermark = ingest.watermark,
        retention = retention,
    )
}
```

(imports: `SessionKind`, `SessionOrigin`, `WatermarkObservation`, `SummariesUnavailableException` from `health`.)

`RouteDtos.kt` — `SessionSnapshotResponse` gains, after `coroutines`:

```kotlin
    /** ATTACHED for a connected app's session, DEMO for vizcore's own runs (spec C-3). */
    val kind: SessionKind,
    /** When the session was created (backend wall clock). */
    val startedAtEpochMs: Long,
    /** App-clock time of the earliest event captured, the origin of "time into the capture"; null when unknown. */
    val captureStartAppNanos: Long?,
    /** REPORTED when the producer reports coroutine origins (or for demos), else UNKNOWN. */
    val origin: SessionOrigin,
    /** Whether an app's ingest socket is open on this backend right now (spec T-2, dock S-3). */
    val appConnected: Boolean,
    /** Backend receipt time of the last event frame from the app, null before any. */
    val lastIngestEpochMs: Long?,
    /** The app's latest poll watermark, null before any (spec C-7). */
    val lastWatermark: WatermarkObservation?,
    /** Seq of the oldest event still retained, null when none is. */
    val oldestRetainedSeq: Long?,
    /** Whether older events were dropped (bounded store or retention). */
    val trimmed: Boolean,
```

(imports `com.jh.proj.coroutineviz.health.SessionKind`, `SessionOrigin`, `WatermarkObservation`.)

`SessionRoutes.kt` — in `get("/api/sessions/{id}")`, replace everything from `// One point-in-time copy…` to the
`call.respond` with `call.respond(HttpStatusCode.OK, snapshotResponseOf(session))`, and add (before `sessionSseFrames`):

```kotlin
/**
 * The body of `GET /api/sessions/{id}`, also the `session` of a share payload: the current coroutines plus the
 * C-3 session facts (spec 2026-10-21: kind, start, capture start, origin, app connection, retention).
 */
internal fun snapshotResponseOf(
    session: VizSession,
    events: List<VizEvent> = session.store.all(),
): SessionSnapshotResponse {
    // One point-in-time copy so the count and the list agree while events keep arriving.
    val nodes = session.snapshot.nodes()
    val facts = readSessionFacts(session, events)
    return SessionSnapshotResponse(
        sessionId = session.sessionId,
        coroutineCount = nodes.size,
        eventCount = events.size,
        coroutines =
            nodes.map { node ->
                CoroutineNodeDto(
                    id = node.id,
                    jobId = node.jobId,
                    parentId = node.parentId,
                    scopeId = node.scopeId,
                    label = node.label,
                    state = node.state.toString(),
                )
            },
        kind = facts.kind,
        startedAtEpochMs = session.createdAtMs,
        captureStartAppNanos = facts.captureStartAppNanos,
        origin = facts.origin,
        appConnected = facts.appConnected,
        lastIngestEpochMs = facts.lastIngestEpochMs,
        lastWatermark = facts.lastWatermark,
        oldestRetainedSeq = facts.retention.oldestRetainedSeq,
        trimmed = facts.retention.trimmed,
    )
}
```

`share/ShareRoutes.kt` — the share payload's `session` is the same shape (D-15), so `sharedResponse` becomes (imports:
`com.jh.proj.coroutineviz.routes.snapshotResponseOf` replaces `CoroutineNodeDto` and `SessionSnapshotResponse`):

```kotlin
/**
 * Build the `{session, events}` body for a shared session — the same snapshot
 * shape `GET /api/sessions/{id}` returns (with its session facts) plus the full
 * event history (reusing the [VizSession] store, which the SSE/events paths also read).
 */
private fun sharedResponse(session: VizSession): SharedSessionResponse {
    val events = session.store.all()
    return SharedSessionResponse(session = snapshotResponseOf(session, events), events = events)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.SessionFactsTest" --tests "com.jh.proj.coroutineviz.routes.SessionRoutesTest" --tests "com.jh.proj.coroutineviz.routes.SessionEventsIntegrationTest" --tests "com.jh.proj.coroutineviz.share.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): the session read carries kind, start, origin, connection and retention

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 10: Backend — the SSE stream forwards watermarks

**Files:**
- Modify: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SseWatermarkTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.WatermarkObservation
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SseWatermarkTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    /** Reads lines until one equals `event: [name]`; returns the next `data:` value, or null at end of stream. */
    private suspend fun ByteReadChannel.dataOfNext(name: String): String? {
        var inEvent = false
        var line = readUTF8Line()
        while (line != null && !(inEvent && line.startsWith("data:"))) {
            if (line.trim() == "event: $name") inEvent = true
            line = readUTF8Line()
        }
        return line?.substringAfter(':')?.trim()
    }

    @Test
    fun `a watermark from the app reaches the session's stream as a watermark frame`() =
        testApplication {
            environment { config = MapApplicationConfig("sse.heartbeatSeconds" to "1") }
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("sse-wm")

            withContext(Dispatchers.Default) {
                createClient {}.prepareGet("/api/sessions/$sessionId/stream").execute { response ->
                    val channel = response.bodyAsChannel()
                    // The stream is attached once its first heartbeat arrives.
                    withTimeout(3_000) { channel.dataOfNext("heartbeat") }

                    ws.webSocket("/api/sessions/$sessionId/ingest") {
                        send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(4)).frameText()))
                        val data = assertNotNull(withTimeout(5_000) { channel.dataOfNext("watermark") })
                        val observation = appJson.decodeFromString(WatermarkObservation.serializer(), data)
                        assertEquals(sec(4), observation.appNanos)
                        assertEquals(1_792_000_000_000, observation.appEpochMs)
                    }
                }
            }
        }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.SseWatermarkTest"`
Expected: FAIL — timeout waiting for `event: watermark`.

- [ ] **Step 3: Implement**

`SessionRoutes.kt`:

1. `sessionSseFrames` gains a parameter after `onShedDelta` and a KDoc line ("[watermarks] become `event: watermark`
   frames (spec C-3), merged like the heartbeats; they carry no id, so `Last-Event-ID` and the replay watermark are
   unaffected."):

```kotlin
    watermarks: Flow<WatermarkObservation> = emptyFlow(),
```

2. Inside the inner `coroutineScope`, right after `val heartbeats = launch { … }`:

```kotlin
                        val watermarkFrames = launch { watermarks.collect { out.send(it.toSse()) } }
```

   and where `heartbeats.cancel()` is called, cancel both (update the comment to "Stop the other writers"):

```kotlin
                        heartbeats.cancel()
                        watermarkFrames.cancel()
```

3. Next to `VizEvent.toSse()`:

```kotlin
internal fun WatermarkObservation.toSse(): ServerSentEvent =
    ServerSentEvent(data = appJson.encodeToString(WatermarkObservation.serializer(), this), event = "watermark")
```

4. In the SSE route's call to `sessionSseFrames(...)`, add `watermarks = IngestStates.watermarks(sessionId),`.

Imports: `com.jh.proj.coroutineviz.health.WatermarkObservation`, `kotlinx.coroutines.flow.emptyFlow`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.SseWatermarkTest" --tests "com.jh.proj.coroutineviz.routes.Sse*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): the session stream forwards the app's watermarks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 11: API docs and `shared/api-types`

**Files:**
- Modify: `backend/src/main/resources/openapi/documentation.yaml`, `shared/api-types/openapi.json`,
  `shared/api-types/generated.ts`, `docs/guides/API_REFERENCE.md`

- [ ] **Step 1: `documentation.yaml`**

1. Under `paths`, after `/api/sessions/{id}/metrics`'s block (or after `/api/sessions/{id}/threads` if metrics is not
   documented), add:

```yaml
  /api/sessions/{id}/health:
    get:
      tags: [Sessions]
      summary: The session's derived health, live or at a replay cursor
      description: >
        What the session's captured coroutines show (spec 2026-10-21, C-3): failed rounds, ungrouped failures and possibly
        blocked coroutines, observations, origin counts, transport and retention facts, each list capped at 50 with its
        total. Without atSeq the health is live; with it, it is the fold of the retained events up to the cursor (clamped
        to the last event), seeded from the durable summaries for coroutines whose creation was trimmed.
      operationId: getSessionHealth
      parameters:
        - $ref: "#/components/parameters/SessionIdPath"
        - name: atSeq
          in: query
          required: false
          description: Replay cursor (event seq). Omit for live health.
          schema:
            type: integer
            format: int64
            minimum: 0
        - name: leakThresholdMs
          in: query
          required: false
          description: Long-lived threshold in milliseconds, as on /metrics (default 30000, clamped to 1000..3600000).
          schema:
            type: integer
            format: int64
      responses:
        "200":
          description: The session's health
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/SessionHealth"
        "400":
          description: atSeq is not a non-negative integer
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ErrorResponse"
        "404":
          description: Session not found
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ErrorResponse"
        "503":
          description: The session's summaries are unavailable (code summaries-unavailable)
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ErrorResponse"
```

2. `SessionSnapshotResponse` gets the nine new properties (all in `required`; the nullable ones `nullable: true`):
   `kind` (`$ref: SessionKind`), `startedAtEpochMs` (int64), `captureStartAppNanos` (int64, nullable), `origin`
   (`$ref: SessionOrigin`), `appConnected` (boolean), `lastIngestEpochMs` (int64, nullable), `lastWatermark`
   (`$ref: WatermarkObservation`, nullable via `allOf` + `nullable: true`), `oldestRetainedSeq` (int64, nullable),
   `trimmed` (boolean), each with the one-line description of its Kotlin KDoc.

3. Under `components.schemas`, add the health schemas, mirroring the Kotlin types field for field (names, nullability,
   `required` = every property, since `appJson` encodes defaults): `SessionKind` (enum ATTACHED, DEMO), `SessionOrigin`
   (REPORTED, UNKNOWN), `AsOfKind`, `AsOf`, `EvaluationStatus`, `NotEvaluatedReason` (with HISTORY_TRIMMED),
   `PossiblyBlockedEvaluation`, `HealthState`, `IncidentKind`, `FailureEntry`, `TransitionKind`, `Transition`,
   `ThreadObservation`, `FailedRoundIncident`, `UngroupedFailureIncident`, `PossiblyBlockedIncident` (each with
   `type` as a required string enum of its one tag), `Incident` (`oneOf` the three with `discriminator: {propertyName: type}`),
   `ObservedAge`, `Observations`, `OriginCounts`, `VizcoreConnectionHealth`, `TransportStatus`, `ProblemRound`,
   `WatermarkObservation`, `RetentionInfo`, `SessionHealth`, and the capped lists as named schemas `CappedIncidents`,
   `CappedFailureEntries`, `CappedStrings`, `CappedObservedAges`, `CappedProblemRounds` (`{items: [...], total: integer}`).
   `PossiblyBlockedIncident.lastSuspension` refers to the existing `SuspensionPoint` schema;
   `lastSuspensionProvenance` is an enum REAL_FRAME, CREATION_FALLBACK, STALE, nullable.

- [ ] **Step 2: `openapi.json`**

`shared/api-types/openapi.json` is a curated subset of the API (the frontend's contract), not a conversion of
`documentation.yaml` (checked: different paths and schemas). So both get the same additions from one script, which
defines the schemas once as data and writes them into the JSON (`json.dump(indent=2)` reproduces today's file byte for
byte, so only the additions show in the diff) and, as text, into the YAML (keeping its hand-written style elsewhere):
`python3 $SCRATCH/part5/openapi_health.py` from the worktree root. Check `yaml.safe_load` still parses the YAML and
`git diff --stat` shows only additions plus the extended `SessionSnapshotResponse.required` line.

- [ ] **Step 3: `generated.ts`**

First prove the generator reproduces today's file from today's `openapi.json`, then regenerate:

```bash
git -C .. show origin/main:shared/api-types/openapi.json > "$SCRATCH/part5/openapi.main.json"
npx --yes openapi-typescript@7 "$SCRATCH/part5/openapi.main.json" -o "$SCRATCH/part5/generated.main.ts"
diff -q ../shared/api-types/generated.ts "$SCRATCH/part5/generated.main.ts" && echo REPRODUCES
cd ../shared/api-types && npx --yes openapi-typescript@7 openapi.json -o generated.ts && cd ../../backend
```

Expected: `REPRODUCES`, then only additions in `generated.ts` (the health path, its schemas, the new
`SessionSnapshotResponse` fields).

- [ ] **Step 4: `docs/guides/API_REFERENCE.md`**

Add a `GET /api/sessions/{id}/health` section next to the other session reads (purpose, parameters, the example
response from Task 12's API check trimmed to one incident, the status codes), list the new `GET /api/sessions/{id}`
fields, and the SSE `watermark` frame.

- [ ] **Step 5: Type-check and commit**

```bash
cd ../frontend && pnpm install --frozen-lockfile --prefer-offline && pnpm exec tsc --noEmit -p . && cd ../backend
git -C .. add backend/src/main/resources/openapi/documentation.yaml shared/api-types docs/guides/API_REFERENCE.md
git -C .. commit -m "docs(api): the health endpoint, the new session fields and the watermark frame

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 12: Whole-build verification, API check, review, PR

- [ ] **Step 1: Gates** (from `backend/`):

```bash
./gradlew ktlintFormat
./gradlew ktlintCheck detekt test :coroutine-viz-core:build
```

Expected: BUILD SUCCESSFUL. Then frontend (types changed): `cd ../frontend && pnpm exec tsc --noEmit -p . && pnpm lint && pnpm test --run`.
If `backend/docs/index.html` changed, `git -C .. checkout backend/docs/index.html`.

- [ ] **Step 2: API check on port 18901 only**

```bash
mkdir -p "$SCRATCH/part5-api"   # SCRATCH=/private/tmp/claude-501/-Users-jirihermann-Documents-workspace-vizcore/9e9c5bc5-1b4c-412c-966d-6cf483ca2458/scratchpad
PORT=18901 ./gradlew :run > "$SCRATCH/part5-api/server.log" 2>&1 &
```

Wait for `curl -s localhost:18901/health` (the liveness route) to answer. Then, sessions at least 7 s apart (the
session-create limit is 10 per minute):

1. A failing demo: `POST /api/sessions?name=scenario-order-processing` → id; start the Order Processing demo with
   `?fail=true` into it (the demo route of `DemoCatalog`'s `order-processing` entry, as the frontend's demo picker calls
   it); wait for it to finish; save `GET /api/sessions/{id}/health`, `…/health?atSeq=<a seq before the failure>`,
   `…/health?atSeq=<last seq>` and `GET /api/sessions/{id}` (without the `coroutines` array, via `jq 'del(.coroutines)'`).
2. A succeeding demo the same way without `fail`.
3. An attached session: `POST /api/sessions?name=part5-app`, then a small `websocat` (or a Kotlin script via the test
   client is not available here; use `websocat` if installed, else `python3 -c` with the `websockets` package) that
   sends a `CoroutineCreated`, a `CoroutineStarted` at t=1 s and a `PollWatermark` at t=12 s, keeps the socket open
   while `…/health` is saved, then closes without a final watermark; save `…/health` again (EVENTS_DROPPED).

Each output goes to `$SCRATCH/part5-api/<name>.json`. Stop the server (`kill %1`) and confirm nothing listens on 18901.

- [ ] **Step 3: Codex code review**, up to 3 rounds, fixing P1/P2:

```bash
git -C .. diff origin/main...HEAD > "$SCRATCH/part5-diff.patch"
codex exec -s read-only -C /Users/jirihermann/Documents/workspace-vizcore/vizcore-health --skip-git-repo-check \
  -o "$SCRATCH/codex-part5-code-r1.md" "<review prompt naming the plan, the spec sections and the diff file>" < /dev/null
```

- [ ] **Step 4: Push and open the PR** (`gh pr create --base main`), body: What / Tests / API check (example responses)
  / Codex review / Known, not in this PR (the 5b row, D-2's consequence for today's SDK, database-mode live SSE).
  Do not merge.
