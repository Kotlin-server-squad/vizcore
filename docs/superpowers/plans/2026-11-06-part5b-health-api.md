# Part 5b — Health API: rounds, items, bound reads, persistence, checks, shares — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish triage PR 2 on top of Part 5a (#295): the follow-up reads a health view needs (rounds with server-side
search, `health/items`, hierarchy with C-2 fields and round ids, the per-coroutine timeline), all bound to the health's
`asOfSeq` and answering `STALE` instead of mixing states; then C-4 persistence (summaries survive the database trim,
ingest state and the "ingest open" marker survive a restart), C-5 checks persistence, C-6 shares, and `/metrics` by origin.

**Architecture:** Core gains one pure piece per concern: `SessionHealthDerivation.deriveFull` (uncapped lists for
paging), `SummaryBinding.bindAt` (the summaries at a past seq, each coroutine marked EXACT / STATE_ONLY / UNKNOWN), and
`SummaryTableState` (a serializable checkpoint of the summary table that restores to an identical table). The backend
adds one parameter parser and one read skeleton (`readView`) used by every follow-up read, live and shared; the database
store restores the checkpoint the retention trim writes before it deletes events, and an id-keyed persistence hook keeps
the ingest state on the session row.

**Tech Stack:** Kotlin 2.4, Ktor 3 (routing, SSE, WebSockets, test host), kotlinx.serialization (`appJson`), Exposed +
Flyway + H2 (tests), JUnit 5 + `kotlin.test`, Gradle (`backend/gradlew`), ktlint, detekt; `openapi-typescript` 7 for
`shared/api-types`.

**Specs:**
- `docs/superpowers/specs/2026-10-21-session-triage-design.md`: H-1..H-4, Definitions (Rounds, Incidents, Lifetimes),
  "Health strip states", "Modes" (Live, Replay, Shared), C-3 (items, rounds, hierarchy, `asOfSeq`/`asOfWatermark`,
  `STALE`, metrics by origin), C-4, C-5, C-6, Testing (Backend).
- `docs/superpowers/specs/2026-11-05-session-dock-design.md`: Part 5 row; S-8 (Problems), S-11 (cursor-bound reads,
  summary-only inspector), S-12 (round picker, find in all rounds, find past the cap).
- `docs/superpowers/plans/2026-11-06-part5-health-api.md` (5a): its decisions D-1..D-15 stay in force except where this
  plan replaces them (D-9, D-9b; marked below).

---

## Scope decision: 5b ships as two stacked PRs

5a's own estimate ("5b may split again") holds: 5b touches every read route and the persistence layer. The seam is
"what a view reads" versus "what survives and is shared":

| PR | Content | Base |
|---|---|---|
| **5b-1** (branch `feat/health-api-b`) | Tasks 1–9: the two tests 5a's reviews asked for; `TransportStatus.revision`; core `deriveFull` and `SummaryBinding`; the binding parameters and `STALE`; `GET …/rounds`, `GET …/health/items`, `GET …/hierarchy` (C-2 fields, `roundId`, `?roundId=`, `?atSeq=`/`?asOfSeq=`), the timeline bound to a seq; API docs and types | `main` |
| **5b-2** (branch `feat/health-api-b2`) | Tasks 10–17: core `SummaryTableState`; migration V2; summary checkpoints written by the trim and restored on every database read (lifts 5a's `503 history-trimmed` for every session trimmed from now on); persisted ingest state and "ingest open" marker with the startup sweep (replaces D-9b); C-5 checks persistence; `/metrics` by origin; C-6 shares under one cut and the shared read routes; API docs and types | `feat/health-api-b` (retarget to `main` once 5b-1 merges) |

Each is shippable alone: 5b-1 makes every follow-up read of a live or replay view consistent with its health (database
sessions keep 5a's limits until 5b-2); 5b-2 adds durability and the shared view on top.

## Decisions this plan takes (spec gaps; the PRs list them)

| # | Gap | Decision |
|---|---|---|
| E-1 | What "its state at `asOfSeq` can be rebuilt from retained events" means | `SummaryBinding.bindAt(durable, retained, s)` folds the retained events to `s`, seeded from the durable summaries (5a's `SummaryFold`), and rates each coroutine first observed at or before `s`: **EXACT** when its summary did not change after `s` (`lastModifiedSeq ≤ s`, the durable summary is then used as is), when its creation is retained (every event it had up to `s` is), or when it had finished by `s` (a finished summary changes later only by reduction, which drops details and says so); **STATE_ONLY** when its creation was trimmed but a lifecycle transition of its own at or before `s` is retained: then its last transition, run start, terminal and last observation are exact (every event from the oldest retained one up to `s` is retained), while thread, dispatcher and suspension point may be older than the retained window; **UNKNOWN** otherwise. A property test checks every claim against a fold of the untrimmed history. |
| E-2 | Which coroutines a read "needs" | `rounds` and `health/items` derive from the whole view, so any UNKNOWN coroutine makes them `STALE`; a `POSSIBLY_BLOCKED` entry they return needs EXACT (it shows thread, dispatcher, suspension point). `hierarchy` needs EXACT for each summary-only node it returns (E-6). The timeline needs at least STATE_ONLY for its coroutine. |
| E-3 | Live versus replay follow-ups | Two bindings, exclusive (both → `400`): `asOfSeq=S[&asOfWatermark=W][&asOfTransport=R]` binds a live or shared view to the health it fetched and is **strict** (`STALE` per E-2); `atSeq=N` is the replay view and is **lenient**, as spec "Modes"/Replay asks: nothing is `STALE`, a node that cannot be rebuilt is returned as the durable summary with `partial: true`, possibly blocked is not evaluated (REPLAY). Neither: the current state. `asOfSeq`/`atSeq` later than the last event are clamped to it (as 5a's D-11). `asOfWatermark`/`asOfTransport` without `asOfSeq` → `400`. |
| E-4 | Wire form of `STALE` | `409 Conflict` with `ErrorResponse` code `stale` ("this view has moved on"). |
| E-5 | A transport change between the health read and a follow-up read (possibly blocked turns not evaluated) | The transport facts are sticky and cannot be rebuilt at a past seq, so they get a counter: `TransportStatus.revision` (core field, default 0), bumped by `IngestStates` on every change and echoed in the health. A follow-up read may pass `asOfTransport=<revision>`; a different current revision → `STALE`. Not in the spec's parameter list; the only way to stay exact without guessing. |
| E-6 | Hierarchy at a past seq | The projection (`HierarchyNode`) is rebuilt from the retained events up to the seq in a throwaway in-memory `VizSession`, so its fields are those of that moment. A coroutine the summaries know but the rebuilt projection lacks (its creation was trimmed, or the database store never had it) becomes a **summary-only node** built from its summary (`summaryOnly: true`; `scopeId`, `jobId`, `dispatcherId` null). The current (unbound) hierarchy uses the live projection plus summary-only nodes for what it lacks. Every node gains `roundId` and `summary` (the C-2 `CoroutineSummary`, `null` for a coroutine beyond capacity). The response stays a JSON array; new fields are additive. |
| E-7 | Timeline at a past seq | `…/timeline?atSeq=` or `?asOfSeq=`: the throwaway session's timeline (events up to the seq). A coroutine whose creation is not retained answers `404` code `events-trimmed` (the page shows "events unavailable (trimmed)" with the node's summary). The open-phase rule of today's route is unchanged. |
| E-8 | Rounds list details | Order: problem rounds first, then anchor creation time (app clock) newest first, then anchor seq, then anchor id. Each entry: core `RoundSummary` fields + `problem` + `problemKinds`, and with `q` the matching coroutine ids (`matches`, capped at 50 with total). Rounds anchored by a `VIZCORE` coroutine without a problem are left out unless `includeVizcore=true` (same rule as `SessionHealth.roundCount`). `q`: case-insensitive substring of a member's name, creation function or creation file; blank `q` = no filter; at most 200 characters (`400` beyond). |
| E-9 | Paging | `limit` 1..200, default 50 (`400` outside). Keyset cursor over each list's sort key `(group, a, b, id)` — group ascending, `a` and `b` descending, id ascending — encoded as `g.a.b.<base64url(id)>`; `400` for a cursor that does not parse. Incidents: (kind, sortSeq, 0, incident id); round members: (role, terminal seq, 0, id); long-lived: (0, first-observed seq, 0, id); long-suspended: (0, last-transition seq, 0, id); rounds: (problem ? 0 : 1, anchor nanos, anchor seq, anchor id). Every page carries `asOfSeq`, `total` and `nextCursor` (null on the last page). |
| E-10 | `health/items` shapes | `list=incidents[&kind=FAILED\|POSSIBLY_BLOCKED]` → `Incident` items (the derivation's own, so they equal the health's first 50); `list=round-members&incidentId=` → `RoundMember` (coroutine, name, role `FAILED`\|`CANCELLED_AFTER`, type, message, terminal seq, details dropped), failures first; `404` code `incident-not-found` when the incident is not a failed round in this view; `list=long-lived`, `list=long-suspended` → `ObservedAge`. Unknown `list` or `kind` → `400`. |
| E-11 | `deriveFull` | `SessionHealthDerivation.deriveFull(input)` returns the health plus the uncapped incident, problem-round, long-lived and long-suspended lists, the anchoring and the effective-origin function; `derive(input)` is `deriveFull(input).health`, unchanged in output. |
| E-12 | Summary checkpoint (C-4) | The database trim folds the events it is about to delete into a `SummaryTableState` checkpoint on the session row (columns `summaries`, `summaries_seq`) in the same transaction as the delete. A database read restores the checkpoint and applies the stored events after `summaries_seq`, reading the events **before** the checkpoint (so a trim that commits between the two reads is seen whole: either the old checkpoint with the not yet deleted events, or the new checkpoint). A restored table is identical to the in-memory one (property test), so a checkpointed session reads as memory mode does: `trimmed` exact, `captureStartAppNanos` known, health and replay available. **Replaces D-9 for every session trimmed from now on.** A session trimmed before 5b-2 (no checkpoint, oldest stored seq > 1) gets no checkpoint (folding what is left would lose ancestry) and keeps answering `503 history-trimmed`. |
| E-13 | Persisted ingest state (C-4 watermark column, C-3 "ingest open" marker) | Columns `ingest_state` (JSON: watermark, last ingest time, transport with revision, first connection seq) and `ingest_open_since` (seq). `IngestStates` takes a persistence hook in database mode: a connection's state is seeded from the row; the row is written when a connection opens (marker = earliest open connection's first seq), at most every 5 s while frames arrive, and when a connection closes (marker cleared with the last one). Reads use this process's state when it has one, else the row. At startup, every row whose marker is set is marked delivery-uncertain since that seq and its marker cleared (a crash ran no close handler). New rows start with an empty state. **Replaces D-9b** for rows that have a state; a row from before 5b-2 (`ingest_state` null) keeps D-9b's rule. |
| E-14 | Checks persistence (C-5) | `POST /api/validate/session/{id}` also stores the run (checked seq = the last event validated, run time, per rule: name, passed, failure count); `GET /api/validate/session/{id}` answers `{sessionId, lastRun, newEventsSince}` (`lastRun` null when never run; `newEventsSince` = the session's last seq − checked seq). Memory mode: an id-keyed registry dropped on delete; database mode: column `checks`. |
| E-15 | `/metrics` by origin | `MetricsResponse` gains `byOrigin` (per origin: captured, active, long-lived) from the summaries, and each `leaks[]` entry its `origin`; leak ages become the core long-lived rule's (frozen at the last observation or the live watermark, the same threshold parameter), so the state bar filter and the health's "Long-lived" agree. When the summaries are unavailable (or a database session's trimmed history is not checkpointed), `leaks` keep today's wall-clock ages, `origin` null, `byOrigin` null. |
| E-16 | Shares under one cut (C-6) | The share payload is taken from one cut: under the session's send lock the summaries are copied, the coroutine nodes read and the last seq noted; the events served are the stored ones up to that seq. It adds `health` (or `healthUnavailable` with the 503 code) and `checks` (E-14's body). Shared reads `GET /api/shared/{token}/health`, `…/health/items`, `…/rounds`, `…/hierarchy`, `…/coroutines/{coroutineId}/timeline` take the same parameters as the session routes and share their handlers; they do not count as share accesses (`ShareService.resolve(token, countAccess = false)`). |

## Not in 5b (each named in the PR bodies)

- The SDK side (triage PR 3, built in parallel): watermark, origin by creation stack, provenance.
- Live SSE in database mode (pre-existing, 5a's list).
- Frontend UI (Parts 6–8). `shared/api-types` is regenerated so they have the types.
- Several backend instances behind one database: `IngestStates`, `IngestConnections` and the checks registry stay
  process-local, as in 5a.

---

## Context for the implementer

- **Worktree:** `/Users/jirihermann/Documents/workspace-vizcore/vizcore-health-b`, branch `feat/health-api-b` from
  `origin/main` (fc89ef72). 5b-2 continues on `feat/health-api-b2`, branched from `feat/health-api-b` after Task 9.
  Gradle runs from `backend/`; `git` from the worktree root (from `backend/`, `git -C ..`).
  `export JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home` in every shell.
- **Gradle:** core tests `./gradlew :coroutine-viz-core:test --tests "<FQN>"`; backend app tests `./gradlew :test --tests "<FQN>"`
  (**qualified `:test`**). Run `./gradlew ktlintFormat` before `ktlintCheck`. Never add to the detekt baseline.
- **Commit trailer** on every commit:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG
  ```
- **Test patterns** (see `routes/HealthTestSupport.kt`, `routes/SessionHealthRouteTest.kt`,
  `routes/SessionHealthDatabaseModeTest.kt`): memory mode = `SessionManager.clearAll()` + `IngestStates.clear()` in both
  hooks and `testApplication { application { module() } }`; database mode = H2 in memory, `DatabaseFactory.init`,
  `SessionManager.useStore(ExposedSessionStore(db))`. `HealthEvents` builds events; `createSessionNamed`,
  `frameText()` exist. A session's events are sent with `SessionManager.getSession(id)!!.send(event)` in memory mode, or
  through the ingest socket (`client.webSocket("/api/sessions/$id/ingest") { send(Frame.Text(e.frameText())) }`).
  In memory mode a session keeps 10,000 events (`SessionManager.configure(maxEventsPerSession = n)` to trim in tests).
- **Core test helpers:** `coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/health/EventScript.kt` builds
  event histories; `SummaryFoldSeedTest` has the cut × cursor property test 5a added, the model for Task 4's and Task 10's.
- **What 5a gives:** `routes/SessionHealthReads.kt` (`readSessionHealth`, `transportOf`, `retentionOf`,
  `summariesSeeEveryEvent`, `RetainedTransitions`, `HistoryTrimmedException`), `routes/SessionHealthRoutes.kt`,
  `routes/IngestStates.kt`, `routes/SessionKinds.kt` (`sessionKindOf`, `leakThresholdMs()`), the C-3 fields in
  `snapshotResponseOf`. Core `RoundAnchoring.rounds()` already lists every round with counts by state.
- **Read order (5a D-9c), kept by every new read:** the ingest snapshot (watermark) is read before the session is
  resolved; the summaries are copied (`readSummaries { it.snapshot() }`); the transport is read again after the copy;
  the events are read after the copy.

## File map

| File | Change | PR |
|---|---|---|
| `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/health/HealthModel.kt` | `TransportStatus.revision`; `RoundMember`, `MemberRole` | 5b-1 |
| `…/health/SessionHealthDerivation.kt`, `…/health/HealthParts.kt`, `…/health/FailureIncidents.kt` | `deriveFull`, `DerivedHealth`, uncapped lists, round members | 5b-1 |
| `…/health/SummaryBinding.kt` (new) | `bindAt`, `RebuildLevel`, `BoundSummaries` | 5b-1 |
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/IngestStates.kt` | revision; test seam; (5b-2) persistence hook | both |
| `…/routes/ViewBinding.kt` (new) | parameter parsing, `Keyset` cursors, `StaleViewException`, `respondReadErrors` | 5b-1 |
| `…/routes/SessionViewReads.kt` (new) | `readView` skeleton (current / replay / bound), the per-read STALE checks | 5b-1 |
| `…/routes/SessionRoundsReads.kt` (new) | rounds listing | 5b-1 |
| `…/routes/HealthItemsReads.kt` (new) | items listing | 5b-1 |
| `…/routes/HierarchyReads.kt` (new) | hierarchy nodes, projection at a seq, bound timeline | 5b-1 |
| `…/routes/SessionHealthRoutes.kt` | register `rounds`, `health/items`; shared handlers | 5b-1 |
| `…/routes/SessionRoutes.kt` | hierarchy and timeline routes call the new reads | 5b-1 |
| `…/health/SummaryTableState.kt` (new, core) | checkpoint state, `toState`, `SummaryTable.restore` | 5b-2 |
| `…/session/VizSession.kt` (core) | `rehydrateFromStore(seed)`, `summarySeedSeq`, `readCut` | 5b-2 |
| `backend/src/main/resources/db/migration/common/V2__health_persistence.sql` (new) | five columns on `sessions` | 5b-2 |
| `…/persistence/tables/SessionsTable.kt`, `…/persistence/ExposedSessionStore.kt` | columns; restore checkpoint; ingest/checks rows | 5b-2 |
| `…/persistence/SummaryCheckpoints.kt` (new), `…/persistence/DbRetentionPolicy.kt` | fold before trim | 5b-2 |
| `…/persistence/ExposedIngestStatePersistence.kt` (new), `…/Application.kt` | hook, startup sweep | 5b-2 |
| `…/routes/ChecksResults.kt` (new), `…/routes/ValidationRoutes.kt` | C-5 | 5b-2 |
| `…/routes/SessionRoutes.kt` (metrics), `…/routes/RouteDtos.kt` | E-15 | 5b-2 |
| `…/share/ShareRoutes.kt`, `…/share/ShareDtos.kt`, `…/share/ShareService.kt`, `…/Routing.kt` | C-6 | 5b-2 |
| `backend/src/main/resources/openapi/documentation.yaml`, `shared/api-types/openapi.json`, `shared/api-types/generated.ts`, `docs/guides/API_REFERENCE.md` | API docs and types | both |

---

# PR 5b-1 — bound reads

### Task 0: Commit the plan

- [ ] **Step 1:** From the worktree root:

```bash
git add docs/superpowers/plans/2026-11-06-part5b-health-api.md
git commit -m "docs: plan for Part 5b, the rest of the health API

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1v63uErFTcSoWqE152KLG"
```

---

### Task 1: The two tests 5a's reviews asked for

**Files:**
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionHealthUnavailableTest.kt` (new)
- Modify: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/IngestStates.kt` (test seam)
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/IngestStateCleanupTest.kt` (new)

These cover behaviour 5a shipped, so they pass once written. "Fail first" is shown by mutation: each step names the
line to break and the test that must then fail; restore it before committing.

- [ ] **Step 1: The `summaries-unavailable` 503, through a store seam (no production change).** `SessionManager.useStore`
accepts any `SessionStoreInterface`; a test store that hands out sessions built with `keepSummaries = false` makes
`readSummaries` throw `SummariesUnavailableException`, the exact branch the route maps.

```kotlin
package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.models.SessionInfo
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.SessionStoreInterface
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sessions without summaries (the state a table is in once abandoned) answer 503, never a health (H-3). */
class SessionHealthUnavailableTest {
    /** A store whose sessions keep no summaries. */
    private class NoSummariesStore : SessionStoreInterface {
        val sessions = ConcurrentHashMap<String, VizSession>()

        override suspend fun createSession(name: String?): VizSession =
            VizSession(name ?: "s-${sessions.size}", keepSummaries = false).also { sessions[it.sessionId] = it }

        override fun getSession(sessionId: String): VizSession? = sessions[sessionId]

        override fun listSessions(): List<SessionInfo> = emptyList()

        override fun deleteSession(sessionId: String): Boolean = sessions.remove(sessionId) != null

        override fun clearAll() = sessions.clear()
    }

    private val store = NoSummariesStore()

    @BeforeEach
    fun setUp() {
        SessionManager.useStore(store)
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        IngestStates.clear()
    }

    @Test
    fun `a session whose summaries are unavailable answers 503 summaries-unavailable, and its facts still answer`() =
        testApplication {
            application { module() }
            kotlinx.coroutines.runBlocking { store.createSession("app-1") }
            val health = client.get("/api/sessions/app-1/health")
            assertEquals(HttpStatusCode.ServiceUnavailable, health.status)
            assertTrue(health.bodyAsText().contains("\"code\":\"summaries-unavailable\""), health.bodyAsText())
            val session = client.get("/api/sessions/app-1")
            assertEquals(HttpStatusCode.OK, session.status)
            assertTrue(session.bodyAsText().contains("\"origin\":\"UNKNOWN\""), session.bodyAsText())
        }
}
```

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.SessionHealthUnavailableTest"` → PASS. Mutation: in
`SessionHealthRoutes.kt` change the `catch (_: SummariesUnavailableException)` to `catch (_: HistoryTrimmedException)`
twice-removed (delete that catch block) → the test fails (500); restore.

(If `module()` replaces the store in memory mode, check `Application.kt`: it calls `useStore` only for
`storage.type=database`; the test config is memory, so the test store stays. If not, set the store after `module()`
inside `application { module(); SessionManager.useStore(store) }`.)

- [ ] **Step 2: A test seam for the delete/socket interleaving.** In `IngestStates`, add:

```kotlin
    /** Test seam: runs right after a session's state is allocated, so a test can delete the session at that moment. */
    @Volatile
    internal var onStateAllocatedForTest: ((String) -> Unit)? = null

    internal fun hasState(sessionId: String): Boolean = states.containsKey(sessionId)
```

and change `stateOf`:

```kotlin
    private fun stateOf(sessionId: String): State {
        states[sessionId]?.let { return it }
        var allocated = false
        val state = states.computeIfAbsent(sessionId) { allocated = true; State() }
        if (allocated) onStateAllocatedForTest?.invoke(sessionId)
        return state
    }
```

`clear()` also resets the seam: `onStateAllocatedForTest = null`.

- [ ] **Step 3: The interleaving tests.**

```kotlin
package com.jh.proj.coroutineviz.routes

// imports: module, SessionManager, testApplication, WebSockets client plugin, webSocket, sse client plugin,
// HealthEvents, frameText, Frame, runBlocking, assertFalse, BeforeEach/AfterEach/Test

/**
 * A socket or stream that allocates ingest state while its session is being deleted must drop that state again when it
 * ends (5a's cleanup in IngestRoutes' and the SSE route's `finally`). The seam deletes the session at the exact moment
 * the state is allocated, which is the window the cleanup exists for.
 */
class IngestStateCleanupTest {
    @BeforeEach fun setUp() { SessionManager.clearAll(); IngestStates.clear() }
    @AfterEach fun tearDown() { SessionManager.clearAll(); IngestStates.clear() }

    @Test
    fun `an ingest socket whose session is deleted as its state is allocated leaves no state behind`() =
        testApplication {
            application { module() }
            val ws = createClient { install(io.ktor.client.plugins.websocket.WebSockets) }
            val id = client.createSessionNamed("cleanup-ws")
            IngestStates.onStateAllocatedForTest = { sid -> if (sid == id) SessionManager.closeSession(sid) }
            runCatching {
                ws.webSocket("/api/sessions/$id/ingest") {
                    send(Frame.Text(HealthEvents.created(id, "a", HealthEvents.sec(1)).frameText()))
                    // The server closes the socket (session not found); drain until it does.
                    for (frame in incoming) Unit
                }
            }
            eventually { !IngestStates.hasState(id) }
        }

    @Test
    fun `an SSE stream whose session is deleted as its state is allocated leaves no state behind`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("cleanup-sse")
            IngestStates.onStateAllocatedForTest = { sid -> if (sid == id) SessionManager.closeSession(sid) }
            // The stream subscribes to watermarks (allocating state), sees the session gone and ends.
            runCatching { client.get("/api/sessions/$id/stream").bodyAsText() }
            eventually { !IngestStates.hasState(id) }
        }
}
```

`eventually` polls the condition every 20 ms for up to 5 s and fails with a message otherwise; add it to
`HealthTestSupport.kt` if no such helper exists there (check first; 5a's tests may already have one under another name —
reuse it).

Run: `./gradlew :test --tests "com.jh.proj.coroutineviz.routes.IngestStateCleanupTest"` → PASS. Mutation 1: in
`IngestRoutes.kt` remove `|| !sessionStillExists(sessionId)` from the final `if` → the first test fails. Mutation 2: in
`SessionRoutes.kt` remove the `runCatching { if (!sessionStillExists(sessionId)) IngestStates.forget(sessionId) }` line →
the second fails. Restore both. If the SSE request does not end on its own (the stream keeps heartbeating), bound it
with `withTimeout(5_000)` around the `get` and note it.

- [ ] **Step 4: Gate and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck detekt :test --tests "com.jh.proj.coroutineviz.routes.*"
git -C .. add backend/src
git -C .. commit -m "test(health): the summaries-unavailable 503 and the delete/allocate cleanup interleavings

<trailer>"
```

---

### Task 2: `TransportStatus.revision` (E-5)

**Files:**
- Modify: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/health/HealthModel.kt`
- Modify: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/IngestStates.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/IngestStatesTest.kt` (extend)

- [ ] **Step 1: Failing tests** (append to `IngestStatesTest`; reuse its connection helpers):

```kotlin
    @Test
    fun `every transport change bumps the revision, a clean watermark does not`() {
        val c = IngestStates.connectionOpened("s", firstSeq = 1)
        assertEquals(0, IngestStates.snapshot("s").transport.revision)
        IngestStates.watermarkReceived(c, watermark(at = 1), receivedEpochMs = 1) // no loss: no change
        assertEquals(0, IngestStates.snapshot("s").transport.revision)
        IngestStates.watermarkReceived(c, watermark(at = 2, lost = 3), receivedEpochMs = 2)
        assertEquals(1, IngestStates.snapshot("s").transport.revision)
        IngestStates.watermarkReceived(c, watermark(at = 3, lost = 3), receivedEpochMs = 3) // same facts
        assertEquals(1, IngestStates.snapshot("s").transport.revision)
        IngestStates.frameSkipped(c)
        assertEquals(2, IngestStates.snapshot("s").transport.revision)
        IngestStates.connectionClosed(c) // already uncertain since seq 1: no change
        assertEquals(2, IngestStates.snapshot("s").transport.revision)
    }
```

(`watermark(...)` = the test's existing `PollWatermark` builder; if there is none, use `HealthEvents.watermark("s", at)`.)

- [ ] **Step 2: Run, expect a compile failure** (`revision` unknown): `./gradlew :test --tests "*IngestStatesTest"`.

- [ ] **Step 3: Implement.** In `TransportStatus` add, last:

```kotlin
    /**
     * Bumped by the backend on every change of the facts above, so a follow-up read can tell that the transport moved on
     * since the health it is bound to (they are sticky and cannot be rebuilt at a past seq).
     */
    val revision: Long = 0,
```

In `IngestStates`, route every transport write through one helper that bumps the revision only when the facts change:

```kotlin
/** Replaces [state]'s transport with [next] (facts only), bumping the revision when they differ. Caller holds the state lock. */
private fun IngestStates.State.updateTransport(next: TransportStatus) {
    val current = transport
    val changed =
        next.lostEvents != current.lostEvents || next.deliveryUncertain != current.deliveryUncertain || next.sinceSeq != current.sinceSeq
    if (changed) transport = next.copy(revision = current.revision + 1)
}
```

and use it in `watermarkReceived` and `markUncertain` instead of assigning `state.transport`.

- [ ] **Step 4: Run** `./gradlew :coroutine-viz-core:test :test --tests "*IngestStatesTest" --tests "*SessionHealth*"` → PASS
(health JSON now carries `transport.revision`; 5a's payload tests compare fields, not raw JSON — if one compares raw
JSON, add `"revision":0`).

- [ ] **Step 5: Commit** `feat(health): the transport carries a revision`.

---

### Task 3: Core — `deriveFull` and round members (E-10, E-11)

**Files:**
- Modify: `…/health/SessionHealthDerivation.kt`, `…/health/HealthParts.kt`, `…/health/FailureIncidents.kt`, `…/health/HealthModel.kt`
- Test: `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/health/DeriveFullTest.kt` (new)

- [ ] **Step 1: Failing test.** Build with `EventScript` a session of 60 failed rounds (60 roots, each with one failing
child), 55 long-lived active coroutines and one failed round with 3 failures and 4 cancellations after the first failure.
Assert:

```kotlin
val full = SessionHealthDerivation.deriveFull(input)
assertEquals(SessionHealthDerivation.derive(input), full.health)            // derive unchanged
assertEquals(full.health.incidents.total, full.incidents.size)             // uncapped
assertEquals(full.incidents.take(50), full.health.incidents.items)         // same order, same first 50
assertEquals(full.health.problemRounds.total, full.problemRounds.size)
assertEquals(full.health.observations.longLived.total, full.longLived.size)
assertEquals(full.longLived.take(50), full.health.observations.longLived.items)
val members = full.roundMembers("failed:round:big")!!
assertEquals(listOf(MemberRole.FAILED, MemberRole.FAILED, MemberRole.FAILED), members.take(3).map { it.role })
assertEquals(4, members.count { it.role == MemberRole.CANCELLED_AFTER })
assertNull(full.roundMembers("failure:x"))                                 // not a failed round
```

Run: `./gradlew :coroutine-viz-core:test --tests "*DeriveFullTest"` → compile failure.

- [ ] **Step 2: Implement.**

In `HealthModel.kt`:

```kotlin
@Serializable
enum class MemberRole { FAILED, CANCELLED_AFTER }

/** One member of a failed round's incident (spec C-3 `health/items?list=round-members`). */
@Serializable
data class RoundMember(
    val coroutineId: String,
    val name: String?,
    val role: MemberRole,
    val exceptionType: String?,
    val message: String?,
    val terminalSeq: Long,
    val detailsDropped: Boolean,
)
```

In `HealthParts`, split `observations` into `longLivedAll(...)`/`longSuspendedAll(...)` returning the full sorted
`List<ObservedAge>` (same filters, same newest-first sort, ages computed for all), and keep `observations` as
`Observations(Capped.of(longLived), Capped.of(longSuspended))` built from them (`Capped.of` takes the first 50 — same
result as today's `cappedAges`).

In `FailureIncidents`, extract the member selection of `failedRound` into

```kotlin
    /** Failures newest first, then the cancellations recorded after the first failure, newest first. */
    fun members(members: List<CoroutineSummary>): Pair<List<CoroutineSummary>, List<CoroutineSummary>>?
```

used by `failedRound` and by `roundMembers` below.

In `SessionHealthDerivation`:

```kotlin
/** The health plus the uncapped lists behind its capped ones, for paging (spec C-3 `health/items`, `rounds`). */
class DerivedHealth internal constructor(
    val health: SessionHealth,
    val incidents: List<Incident>,
    val problemRounds: List<ProblemRound>,
    val longLived: List<ObservedAge>,
    val longSuspended: List<ObservedAge>,
    val anchoring: RoundAnchoring,
    /** The effective origin the derivation used for each coroutine (demo → APP, UNKNOWN session → UNKNOWN). */
    val originOf: (CoroutineSummary) -> CoroutineOrigin,
    private val view: SummaryView,
    private val evaluatedIds: Set<String>,
) {
    /** The members of the failed round incident [incidentId], or null when it is not one in this health. */
    fun roundMembers(incidentId: String): List<RoundMember>? {
        val incident = incidents.firstOrNull { it.id == incidentId } as? FailedRoundIncident ?: return null
        val inRound = view.all.filter { it.id in evaluatedIds && anchoring.anchorOf(it.id) == incident.anchorId }
        val (failures, cancelled) = FailureIncidents.members(inRound) ?: return emptyList()
        return failures.map { it.member(MemberRole.FAILED) } + cancelled.map { it.member(MemberRole.CANCELLED_AFTER) }
    }
}

private fun CoroutineSummary.member(role: MemberRole) =
    RoundMember(id, name, role, failure?.exceptionType, failure?.message, FailureIncidents.terminalSeq(this), reduced)
```

`derive(input)` becomes `deriveFull(input).health`; `deriveFull` is today's body building the uncapped lists first and the
`SessionHealth` from them (`incidents = Capped.of(incidents)`, `observations = Observations(Capped.of(longLived), Capped.of(longSuspended))`,
`problemRounds = Capped.of(problemRounds)`).

- [ ] **Step 3: Run** `./gradlew :coroutine-viz-core:test` → all PASS (every existing derivation test unchanged).

- [ ] **Step 4: Commit** `feat(health): deriveFull with the uncapped lists and failed-round members`.

---

### Task 4: Core — `SummaryBinding.bindAt` (E-1)

**Files:**
- Create: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/health/SummaryBinding.kt`
- Test: `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/health/SummaryBindingTest.kt`

- [ ] **Step 1: Failing tests.**

Table tests (each with `EventScript`, durable = a `SummaryTable` fed every event, retained = events with seq ≥ T):
1. Nothing trimmed, `s` < last: every coroutine EXACT; `bound.view.get(id) == fold(all events, s).get(id)` for all;
   a coroutine created after `s` is absent.
2. `s` ≥ durable.lastEventSeq: `bound.view` is `durable` (same instance) and nothing is rated below EXACT.
3. Creation trimmed, unchanged since `s` (`lastModifiedSeq ≤ s`): EXACT and equal to the durable summary.
4. Creation trimmed, finished by `s`, reduced to a stub after `s`: EXACT.
5. Creation trimmed, active, suspended at a retained seq ≤ `s`, resumed after `s`: STATE_ONLY; `state == SUSPENDED`.
6. Creation trimmed, active, its only events ≤ `s` trimmed, changed after `s`: UNKNOWN, listed in `unknownIds`.

Property test (the model is `SummaryFoldSeedTest`'s): for 200 random histories (seeded `Random`), every trim point T and
every cursor s, with `truth = SummaryFold.foldTo(allEvents, s)`:

```kotlin
val bound = SummaryBinding.bindAt(durable, retained, s)
assertEquals(truth.all.map { it.id }.toSet(), bound.view.all.map { it.id }.toSet())
for (t in truth.all) {
    val b = bound.view.get(t.id)!!
    when (bound.levelOf(t.id)) {
        RebuildLevel.EXACT -> assertEquals(t, b)
        RebuildLevel.STATE_ONLY -> {
            assertEquals(t.state, b.state); assertEquals(t.lastTransition, b.lastTransition)
            assertEquals(t.lastRunStart, b.lastRunStart); assertEquals(t.terminal, b.terminal)
            assertEquals(t.lastObservedNanos, b.lastObservedNanos)
        }
        RebuildLevel.UNKNOWN -> Unit
    }
}
// Structure is exact whatever the levels (5a D-10): same rounds.
assertEquals(RoundAnchoring.of(truth).anchorIds, RoundAnchoring.of(bound.view).anchorIds)
```

Histories stay below the reduction target (no reduction differences). If the EXACT equality fails only on
`lastModifiedSeq` of a root (promotion bookkeeping), stop and report: E-1's claim would be wrong and the plan needs
revisiting, not the test.

Run: `./gradlew :coroutine-viz-core:test --tests "*SummaryBindingTest"` → compile failure.

- [ ] **Step 2: Implement.**

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent

/** How much of a coroutine's summary at a past seq is known (spec C-3 `STALE`, plan E-1). */
enum class RebuildLevel { EXACT, STATE_ONLY, UNKNOWN }

/** The summaries at a past seq and how well each one is known; see [SummaryBinding.bindAt]. */
class BoundSummaries internal constructor(
    val view: SummaryView,
    private val levels: Map<String, RebuildLevel>,
) {
    fun levelOf(id: String): RebuildLevel = levels[id] ?: RebuildLevel.EXACT

    val unknownIds: Set<String> = levels.filterValues { it == RebuildLevel.UNKNOWN }.keys
}

object SummaryBinding {
    /**
     * The summaries as they were at [atSeq]: [durable] itself when nothing came after it, else the replay fold of
     * [retained] (in seq order) seeded from [durable], with each coroutine rated (plan E-1): EXACT when its durable
     * summary did not change after [atSeq] (used as is), its creation is retained, or it had finished by [atSeq];
     * STATE_ONLY when only a retained transition of its own at or before [atSeq] vouches for its state; UNKNOWN otherwise.
     */
    fun bindAt(
        durable: SummaryView,
        retained: List<VizEvent>,
        atSeq: Long,
    ): BoundSummaries {
        if (atSeq >= durable.lastEventSeq) return BoundSummaries(durable, emptyMap())
        val fold = SummaryFold.foldTo(retained, atSeq, durable)
        val oldestRetained = retained.firstOrNull()?.seq ?: Long.MAX_VALUE
        val withRetainedTransition = HashSet<String>()
        for (e in retained) {
            if (e.seq > atSeq) break
            if (e is CoroutineEvent && Transitions.of(e) != null) withRetainedTransition += e.coroutineId
        }
        val overrides = HashMap<String, CoroutineSummary>()
        val levels = HashMap<String, RebuildLevel>()
        for (f in fold.all) {
            val d = durable.get(f.id) ?: continue
            when {
                f.firstObserved.seq >= oldestRetained -> Unit
                d.lastModifiedSeq <= atSeq -> overrides[f.id] = d
                d.terminal?.let { it.seq <= atSeq } == true -> Unit
                f.id in withRetainedTransition -> levels[f.id] = RebuildLevel.STATE_ONLY
                else -> levels[f.id] = RebuildLevel.UNKNOWN
            }
        }
        return BoundSummaries(if (overrides.isEmpty()) fold else OverlaidView(fold, overrides), levels)
    }
}

/** [base] with some summaries replaced, in [base]'s order. */
private class OverlaidView(
    private val base: SummaryView,
    private val overrides: Map<String, CoroutineSummary>,
) : SummaryView by base {
    override fun get(id: String): CoroutineSummary? = overrides[id] ?: base.get(id)

    override val all: Collection<CoroutineSummary> = base.all.map { overrides[it.id] ?: it }
}
```

Note: a creation-trimmed, terminal-by-`s` coroutine is EXACT through the fold's seeded or retained terminal and
`finishedAs` (final failure details). If case 4 shows the fold's summary differing from truth only by `reduced`/details,
use the durable summary for it as well (add it to `overrides`) — the durable one is final.

- [ ] **Step 3: Run** the test → PASS; then `./gradlew :coroutine-viz-core:test`.

- [ ] **Step 4: Commit** `feat(health): summaries bound to a past seq, each rated exact, state only or unknown`.

---

### Task 5: Backend — the binding parameters, the read skeleton, `STALE` (E-3, E-4, E-9)

**Files:**
- Create: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ViewBinding.kt`
- Create: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionViewReads.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/ViewBindingTest.kt`, `…/SessionViewReadsTest.kt`

- [ ] **Step 1: Failing tests.**

`ViewBindingTest` (pure; build an `io.ktor.http.Parameters` with `parametersOf(...)` and call
`parseViewBinding(params)`):
- no params → `ViewBinding.Current`
- `atSeq=5` → `Replay(5)`; `asOfSeq=7&asOfWatermark=12000&asOfTransport=2` → `Bound(7, 12000, 2)`
- `atSeq=5&asOfSeq=5`, `atSeq=-1`, `asOfSeq=x`, `asOfWatermark=1` (without `asOfSeq`) → `BadReadParameter` with a message
- `Keyset`: `Keyset.Key(0, 10, 3, "co:1").encode()` decodes to the same key; `Keyset.decode("nope")` → `BadReadParameter`;
  `Keyset.page(sorted, keyOf, cursor = null, limit = 2)` returns the first two and a cursor that yields the rest.
- `pageLimit(params)`: absent → 50, `0`/`201`/`x` → `BadReadParameter`.

`SessionViewReadsTest` (memory mode, `SessionManager` sessions built directly; call `readView` with a fake ingest
snapshot):
- `Current` → `view.view` equals the durable snapshot (same ids), `strict == false`.
- `Bound(s < last)` with a trimmed store (configure `maxEventsPerSession = 5`) and an UNKNOWN coroutine →
  `readView(...).requireNoUnknown()` throws `StaleViewException`.
- `Bound` with `asOfTransport` different from the current revision → `StaleViewException` from `readView`.
- `Replay(n)` → mode `HealthMode.Replay(n)`, `requireNoUnknown()` does not throw (lenient).

Run → compile failure.

- [ ] **Step 2: `ViewBinding.kt`.**

```kotlin
package com.jh.proj.coroutineviz.routes

/** How a follow-up read is bound (plan E-3). */
internal sealed interface ViewBinding {
    /** The session as it is now. */
    data object Current : ViewBinding

    /** A replay view at [atSeq]: lenient, possibly blocked not evaluated. */
    data class Replay(val atSeq: Long) : ViewBinding

    /** A live or shared view bound to the health it fetched: strict, `STALE` when it cannot be answered exactly. */
    data class Bound(val asOfSeq: Long, val watermarkNanos: Long?, val transportRevision: Long?) : ViewBinding
}

/** A query parameter that does not parse; answered with 400. */
internal class BadReadParameter(message: String) : IllegalArgumentException(message)

/** The view has moved on since [ViewBinding.Bound.asOfSeq] (spec C-3 `STALE`); answered with 409, code `stale`. */
internal class StaleViewException(message: String) : IllegalStateException(message)

internal fun parseViewBinding(params: io.ktor.http.Parameters): ViewBinding {
    fun seq(name: String): Long? =
        params[name]?.let { raw -> raw.toLongOrNull()?.takeIf { it >= 0 } ?: throw BadReadParameter("$name must be a non-negative integer") }
    val atSeq = seq("atSeq")
    val asOfSeq = seq("asOfSeq")
    val watermark = params["asOfWatermark"]?.let { it.toLongOrNull() ?: throw BadReadParameter("asOfWatermark must be an integer") }
    val transport = seq("asOfTransport")
    if (atSeq != null && asOfSeq != null) throw BadReadParameter("atSeq and asOfSeq are exclusive")
    if (asOfSeq == null && (watermark != null || transport != null)) {
        throw BadReadParameter("asOfWatermark and asOfTransport need asOfSeq")
    }
    return when {
        atSeq != null -> ViewBinding.Replay(atSeq)
        asOfSeq != null -> ViewBinding.Bound(asOfSeq, watermark, transport)
        else -> ViewBinding.Current
    }
}

internal const val DEFAULT_PAGE_LIMIT = 50
internal const val MAX_PAGE_LIMIT = 200

internal fun pageLimit(params: io.ktor.http.Parameters): Int =
    params["limit"]?.let { raw -> raw.toIntOrNull()?.takeIf { it in 1..MAX_PAGE_LIMIT } ?: throw BadReadParameter("limit must be 1..$MAX_PAGE_LIMIT") }
        ?: DEFAULT_PAGE_LIMIT

/** Keyset paging over a list sorted by [Key] (plan E-9): group ascending, a and b descending, id ascending. */
internal object Keyset {
    data class Key(val group: Long, val a: Long, val b: Long, val id: String) : Comparable<Key> {
        override fun compareTo(other: Key): Int =
            compareValuesBy(this, other, { it.group }, { -it.a }, { -it.b }, { it.id })

        fun encode(): String =
            "$group.$a.$b." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray())
    }

    fun decode(cursor: String): Key {
        val parts = cursor.split('.', limit = 4)
        val id =
            parts.getOrNull(3)?.let { runCatching { String(java.util.Base64.getUrlDecoder().decode(it)) }.getOrNull() }
        val numbers = parts.take(3).map { it.toLongOrNull() }
        if (parts.size != 4 || id == null || numbers.any { it == null }) throw BadReadParameter("cursor does not parse")
        return Key(numbers[0]!!, numbers[1]!!, numbers[2]!!, id)
    }

    data class Page<T>(val items: List<T>, val nextCursor: String?)

    /** [sorted] must be in [Key] order; the page starts strictly after [cursor]. */
    fun <T> page(sorted: List<T>, keyOf: (T) -> Key, cursor: String?, limit: Int): Page<T> {
        val after = cursor?.let(::decode)
        val start = if (after == null) 0 else sorted.indexOfFirst { keyOf(it) > after }.let { if (it < 0) sorted.size else it }
        val items = sorted.subList(start, minOf(sorted.size, start + limit))
        val next = if (start + items.size < sorted.size) items.lastOrNull()?.let { keyOf(it).encode() } else null
        return Page(items.toList(), next)
    }
}
```

Note `-it.a` overflows for `Long.MIN_VALUE`; keys are seqs and nanos (never `MIN_VALUE`), but write the comparator with
`compareValues(other.a, a)` instead of negation to be safe.

`respondReadErrors`, used by every new route (and by the shared routes in 5b-2):

```kotlin
/** Runs [block], answering the read errors of this API: 400, 409 `stale`, 503 `summaries-unavailable` / `history-trimmed`. */
internal suspend fun io.ktor.server.application.ApplicationCall.respondReadErrors(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: BadReadParameter) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad parameter"))
    } catch (_: StaleViewException) {
        respond(HttpStatusCode.Conflict, ErrorResponse("This view has moved on; fetch the health again", code = "stale"))
    } catch (_: SummariesUnavailableException) {
        respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Health is unavailable for this session", code = "summaries-unavailable"))
    } catch (_: HistoryTrimmedException) {
        respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponse("Health is unavailable: this session's early events were trimmed", code = "history-trimmed"),
        )
    }
}
```

- [ ] **Step 3: `SessionViewReads.kt`** — the skeleton every follow-up read uses; mirrors `readSessionHealth`'s order.

```kotlin
/** One follow-up read's view of a session (plan E-1..E-3). */
internal class SessionView(
    val session: VizSession,
    /** The stored events read after the summaries were copied. */
    val events: List<VizEvent>,
    val binding: ViewBinding,
    /** The seq the view is at: the last event (Current), the clamped cursor (Replay, Bound). */
    val seq: Long,
    val bound: BoundSummaries,
    val input: HealthInput,
) {
    val strict: Boolean get() = binding is ViewBinding.Bound

    fun derive(): DerivedHealth = SessionHealthDerivation.deriveFull(input)

    /** Strict reads cannot answer while a coroutine's state at [seq] is unknown (E-2). */
    fun requireNoUnknown() {
        if (strict && bound.unknownIds.isNotEmpty()) throw StaleViewException("${bound.unknownIds.size} coroutines changed since $seq")
    }

    /** Strict reads cannot return details of [id] unless they are exact at [seq] (E-2). */
    fun requireExact(id: String) {
        if (strict && bound.levelOf(id) != RebuildLevel.EXACT) throw StaleViewException("$id changed since $seq")
    }

    fun requireStateKnown(id: String) {
        if (strict && bound.levelOf(id) == RebuildLevel.UNKNOWN) throw StaleViewException("$id changed since $seq")
    }
}

/**
 * Reads [session] for a follow-up read. [ingestCut] must be read before [session] was resolved (5a D-9c).
 * @throws SummariesUnavailableException, HistoryTrimmedException (database session trimmed without a checkpoint),
 *   StaleViewException (Bound, transport revision moved on).
 */
internal fun readView(
    session: VizSession,
    ingestCut: IngestSnapshot,
    binding: ViewBinding,
    leakThresholdMs: Long,
): SessionView {
    val durable = session.readSummaries { it.snapshot() }
    val ingest = IngestStates.snapshot(session.sessionId).copy(watermark = ingestCut.watermark)
    val events = session.store.all()
    val everyEvent = summariesSeeEveryEvent()
    val retention = retentionOf(events, firstSeqEver = durable.firstSeqEver(everyEvent))
    if (retention.trimmed && !everyEvent) throw HistoryTrimmedException("session ${session.sessionId} was trimmed")
    val kind = sessionKindOf(session.sessionId)
    val transport = transportOf(ingest, session, kind, durable.lastEventSeq)
    val last = durable.lastEventSeq
    val base =
        HealthInput(
            summaries = durable,
            kind = kind,
            watermark = ingest.watermark,
            lastIngestEpochMs = ingest.lastIngestEpochMs,
            transport = transport,
            leakThresholdMs = leakThresholdMs,
            retention = retention,
        )
    return when (binding) {
        ViewBinding.Current ->
            SessionView(session, events, binding, last, SummaryBinding.bindAt(durable, events, last),
                base.copy(transitionsFor = RetainedTransitions(events, upToSeq = last)::of))
        is ViewBinding.Replay -> {
            val cursor = minOf(binding.atSeq, last)
            val bound = SummaryBinding.bindAt(durable, events, cursor)
            // Replay derives from the plain fold (5a), as GET …/health?atSeq does, so the two agree.
            val fold = SummaryFold.foldTo(events, cursor, durable)
            SessionView(session, events, binding, cursor, bound,
                base.copy(summaries = fold, mode = HealthMode.Replay(cursor), historyIncomplete = retention.trimmed && durable.untrackedCount > 0))
        }
        is ViewBinding.Bound -> {
            if (binding.transportRevision != null && binding.transportRevision != transport.revision) {
                throw StaleViewException("the transport changed since the health")
            }
            val s = minOf(binding.asOfSeq, last)
            val bound = SummaryBinding.bindAt(durable, events, s)
            val watermark = binding.watermarkNanos?.let { w ->
                ingest.watermark?.takeIf { it.appNanos == w } ?: WatermarkObservation(w, appEpochMs = 0, receivedEpochMs = 0)
            }
            SessionView(session, events, binding, s, bound,
                base.copy(summaries = bound.view, watermark = watermark,
                    historyIncomplete = s < last && retention.trimmed && durable.untrackedCount > 0,
                    transitionsFor = RetainedTransitions(events, upToSeq = s)::of))
        }
    }
}
```

(Replay's `bound` is used only for the `partial` flags of E-3; its `view` is not the derivation input — the plain fold is,
to stay identical to `GET …/health?atSeq`.) A Bound view without `asOfWatermark` derives with no watermark: possibly
blocked is not evaluated, as the health it is bound to said.

- [ ] **Step 4: Run** both tests → PASS. **Step 5: Commit** `feat(health): read binding (atSeq, asOfSeq) and STALE`.

---

### Task 6: `GET /api/sessions/{id}/rounds` (E-8)

**Files:**
- Create: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoundsReads.kt`
- Modify: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionHealthRoutes.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionRoundsRouteTest.kt`

- [ ] **Step 1: Failing route tests** (memory mode, events sent with `send`):
1. Three rounds (roots `r1`, `r2`, `r3` created at 1, 2, 3 s; `r2` has a failing child): `GET …/rounds` → items in
   order `r2` (problem first), `r3`, `r1`; `r2.problem == true`, `problemKinds == ["FAILED"]`; `countsByState` of `r2`
   is `{FAILED: 2}` (child failed, root failed? — use a child failure and a root completion: `{FAILED:1, COMPLETED:1}`);
   `total == 3`; `nextCursor == null`; `asOfSeq` = last seq.
2. `?limit=1` → one item and a cursor; following the cursor twice yields `r3`, `r1` then `nextCursor == null`.
3. `?q=R3` matches `r3` (case-insensitive name); `?q=App.kt` matches rounds whose member creation file is `App.kt`
   (give `r1`'s child a `creationPoint` with `fileName = "App.kt"`), with `matches.items == ["r1-child"]`.
4. `?problemsOnly=true` → only `r2`.
5. A `VIZCORE`-origin root without a problem is absent, present with `?includeVizcore=true`.
6. `?atSeq=<seq before r3 existed>` → `r3` absent.
7. Strict: trim (`SessionManager.configure(maxEventsPerSession = 6)`) so a long-lived root's creation is trimmed, take
   `asOfSeq` from `GET …/health`, then send one event that changes that root (a suspension) **with no retained earlier
   transition of its own** → `GET …/rounds?asOfSeq=<old>` → `409`, code `stale`. Without the new event → `200`.
8. `?cursor=bad` → 400; `?limit=0` → 400; `?atSeq=1&asOfSeq=1` → 400; unknown session → 404.

Run → 404 for the route (fails).

- [ ] **Step 2: Implement.**

```kotlin
@Serializable
data class RoundEntry(
    val roundId: String,
    val anchorId: String,
    val anchorName: String?,
    val anchorOrigin: CoroutineOrigin?,
    val anchorCreatedNanos: Long,
    val anchorCreatedSeq: Long,
    val inProgress: Boolean,
    val countsByState: Map<CoroutineState, Int>,
    val memberCount: Int,
    val problem: Boolean,
    val problemKinds: List<IncidentKind>,
    /** With `q`: the matching members (coroutine ids, newest first), else null. */
    val matches: Capped<String>? = null,
)

@Serializable
data class RoundsPage(val asOfSeq: Long, val total: Int, val items: List<RoundEntry>, val nextCursor: String?)

internal const val MAX_QUERY_LENGTH = 200

internal fun listRounds(view: SessionView, params: Parameters): RoundsPage {
    view.requireNoUnknown()
    val q = params["q"]?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (it.length > MAX_QUERY_LENGTH) throw BadReadParameter("q is longer than $MAX_QUERY_LENGTH characters")
    }?.lowercase()
    val problemsOnly = params["problemsOnly"] == "true"
    val includeVizcore = params["includeVizcore"] == "true"
    val limit = pageLimit(params)
    val derived = view.derive()
    val kindsByAnchor = derived.problemRounds.associate { it.anchorId to it.kinds }
    val summaries = view.input.summaries
    val membersByAnchor = summaries.all.groupBy { derived.anchoring.anchorOf(it.id) }
    val entries =
        derived.anchoring.rounds().mapNotNull { r ->
            val kinds = kindsByAnchor[r.anchorId].orEmpty()
            val anchor = summaries.get(r.anchorId)
            val vizcoreAnchor = anchor != null && derived.originOf(anchor) == CoroutineOrigin.VIZCORE
            if (problemsOnly && kinds.isEmpty()) return@mapNotNull null
            if (!includeVizcore && vizcoreAnchor && kinds.isEmpty()) return@mapNotNull null
            val matches =
                q?.let { needle ->
                    membersByAnchor[r.anchorId].orEmpty().filter { it.matches(needle) }
                        .sortedByDescending { it.firstObserved.seq }.map { it.id }
                }
            if (matches != null && matches.isEmpty()) return@mapNotNull null
            RoundEntry(r.roundId, r.anchorId, r.anchorName, r.anchorOrigin, r.anchorCreatedNanos, r.anchorCreatedSeq,
                r.inProgress, r.countsByState, r.memberCount, kinds.isNotEmpty(), kinds, matches?.let { Capped.of(it) })
        }.sortedBy(::roundKey)
    val page = Keyset.page(entries, ::roundKey, params["cursor"], limit)
    return RoundsPage(view.seq, entries.size, page.items, page.nextCursor)
}

private fun roundKey(r: RoundEntry) = Keyset.Key(if (r.problem) 0 else 1, r.anchorCreatedNanos, r.anchorCreatedSeq, r.anchorId)

private fun CoroutineSummary.matches(needle: String): Boolean =
    name?.lowercase()?.contains(needle) == true ||
        creationLocation?.function?.lowercase()?.contains(needle) == true ||
        creationLocation?.fileName?.lowercase()?.contains(needle) == true
```

Route (in `SessionHealthRoutes.kt`; extract the shared "missing id → 400 / resolve → 404" preamble into a small helper
`withSessionView(call, block)` used by rounds, items, hierarchy and timeline):

```kotlin
    get("/api/sessions/{id}/rounds") {
        call.respondReadErrors {
            val binding = parseViewBinding(call.request.queryParameters)
            val sessionId = call.parameters["id"] ?: throw BadReadParameter("Missing session ID")
            val ingest = IngestStates.snapshot(sessionId)
            val session = call.resolveScopedSession(sessionId)
                ?: return@respondReadErrors call.respond(HttpStatusCode.NotFound, ErrorResponse("Session not found"))
            val view = readView(session, ingest, binding, call.leakThresholdMs())
            call.respond(HttpStatusCode.OK, listRounds(view, call.request.queryParameters))
        }
    }
```

- [ ] **Step 3: Run** → PASS. **Step 4: Commit** `feat(health): GET /api/sessions/{id}/rounds with server-side find`.

---

### Task 7: `GET /api/sessions/{id}/health/items` (E-10)

**Files:**
- Create: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HealthItemsReads.kt`
- Modify: `…/routes/SessionHealthRoutes.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthItemsRouteTest.kt`

- [ ] **Step 1: Failing tests:**
1. 60 failed rounds: `GET …/health` has `incidents.total == 60`, 50 items; `GET …/health/items?list=incidents&asOfSeq=S`
   (S from the health) → first page of 50 equal to the health's items (decode both as `List<Incident>`), then the
   cursor → the remaining 10; `total == 60`.
2. `?list=incidents&kind=POSSIBLY_BLOCKED` on an attached session with a watermark 12 s after a run start (5a's
   attached fixture) and `asOfWatermark` = that watermark's appNanos → one item; without `asOfWatermark` → zero items
   (no watermark bound: not evaluated).
3. `?list=round-members&incidentId=failed:round:<anchor>` → failures first, then cancellations after; an unknown id →
   404 code `incident-not-found`.
4. `?list=long-lived&leakThresholdMs=1000` and `?list=long-suspended` → `ObservedAge` items newest first.
5. `?list=nope` → 400; `?list=incidents&kind=NOPE` → 400; missing `list` → 400.
6. Strict: a `POSSIBLY_BLOCKED` coroutine whose creation was trimmed and that is STATE_ONLY at S → `409 stale` for
   `list=incidents` (it shows details); `list=long-lived` at the same S → 200.

Run → fails (404 route).

- [ ] **Step 2: Implement.**

```kotlin
@Serializable
data class IncidentsPage(val list: String = "incidents", val asOfSeq: Long, val total: Int, val items: List<Incident>, val nextCursor: String?)

@Serializable
data class RoundMembersPage(val list: String = "round-members", val asOfSeq: Long, val total: Int, val items: List<RoundMember>, val nextCursor: String?)

@Serializable
data class ObservedAgesPage(val list: String, val asOfSeq: Long, val total: Int, val items: List<ObservedAge>, val nextCursor: String?)
```

(`appJson` encodes defaults, so `list` is always present; check `Serialization.kt`'s `encodeDefaults` — if it is false,
make `list` a plain constructor parameter without a default.)

```kotlin
/** One page of a health list (spec C-3 `health/items`); responds itself because the page type depends on `list`. */
internal suspend fun ApplicationCall.respondHealthItems(view: SessionView) {
    val params = request.queryParameters
    val limit = pageLimit(params)
    val cursor = params["cursor"]
    view.requireNoUnknown()
    val derived = view.derive()
    when (params["list"]) {
        "incidents" -> {
            val kind = params["kind"]?.let { k -> IncidentKind.entries.firstOrNull { it.name == k } ?: throw BadReadParameter("unknown kind $k") }
            val all = derived.incidents.filter { kind == null || it.kind == kind }
            val page = Keyset.page(all, ::incidentKey, cursor, limit)
            page.items.filterIsInstance<PossiblyBlockedIncident>().forEach { view.requireExact(it.coroutineId) }
            respond(IncidentsPage(asOfSeq = view.seq, total = all.size, items = page.items, nextCursor = page.nextCursor))
        }
        "round-members" -> {
            val incidentId = params["incidentId"] ?: throw BadReadParameter("incidentId is required")
            val members = derived.roundMembers(incidentId)
                ?: return respond(HttpStatusCode.NotFound, ErrorResponse("No failed round $incidentId", code = "incident-not-found"))
            val page = Keyset.page(members, { Keyset.Key(it.role.ordinal.toLong(), it.terminalSeq, 0, it.coroutineId) }, cursor, limit)
            respond(RoundMembersPage(asOfSeq = view.seq, total = members.size, items = page.items, nextCursor = page.nextCursor))
        }
        "long-lived" -> respondAges("long-lived", view, derived.longLived, { view.input.summaries.get(it.coroutineId)?.firstObserved?.seq ?: 0 }, cursor, limit)
        "long-suspended" -> respondAges("long-suspended", view, derived.longSuspended, { view.input.summaries.get(it.coroutineId)?.lastTransition?.seq ?: 0 }, cursor, limit)
        else -> throw BadReadParameter("list must be incidents, round-members, long-lived or long-suspended")
    }
}

private fun incidentKey(i: Incident) = Keyset.Key(i.kind.ordinal.toLong(), i.sortSeq, 0, i.id)
```

`respondAges` pages with key `(0, seqOf(it), 0, it.coroutineId)` and responds `ObservedAgesPage(list, …)`. The derived
lists are already newest first by the same seq, so their order is the key order; assert it in a test rather than
re-sorting.

Route: `get("/api/sessions/{id}/health/items")` with the same preamble as rounds, ending in `call.respondHealthItems(view)`.

- [ ] **Step 3: Run** → PASS. **Step 4: Commit** `feat(health): GET /api/sessions/{id}/health/items pages every health list`.

---

### Task 8: Hierarchy with C-2 fields and rounds, the timeline at a seq (E-6, E-7)

**Files:**
- Create: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HierarchyReads.kt`
- Modify: `…/routes/SessionRoutes.kt` (hierarchy and timeline handlers)
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HierarchyReadsTest.kt`

- [ ] **Step 1: Failing tests:**
1. `GET …/hierarchy` on a demo-like session: every node has `roundId` (`round:<anchor>`) and `summary.state`; nodes keep
   every existing field (decode as `JsonObject` and assert `scopeId`, `jobId`, `state`, `children` present).
2. `?roundId=round:r2` → only `r2`'s subtree; `?roundId=round:none` → `[]`.
3. `?atSeq=<seq before r2's child failed>` → the child's `state` is `RUNNING` (projection string) and its
   `summary.state` is `ACTIVE`; nodes created later are absent.
4. Memory mode, trimmed (`maxEventsPerSession = 6`), a root whose creation was trimmed: unbound `…/hierarchy` returns it
   as a full node (live projection); `?atSeq=last` returns it as `summaryOnly: true` with `state` from its summary,
   `scopeId == null`; `?asOfSeq=S` where it is UNKNOWN at S → 409 `stale`; `?atSeq=S` → 200 with `partial: true`.
5. A coroutine beyond capacity is not testable cheaply (100,000); skip, covered by E-6's text.
6. Timeline: `…/coroutines/c/timeline?atSeq=N` lists only events with seq ≤ N; `state` is the state at N. For the
   trimmed root: `…/timeline?atSeq=last` → 404 code `events-trimmed`; `?asOfSeq=S` with the coroutine UNKNOWN → 409.
   Without params: unchanged (today's tests stay green).

Run → fails.

- [ ] **Step 2: Implement.**

```kotlin
/** A hierarchy node (spec C-3): today's projection fields, the derived round and the C-2 summary (plan E-6). */
@Serializable
data class HierarchyNodeDto(
    val id: String,
    val parentId: String?,
    val children: List<String>,
    val name: String,
    val scopeId: String?,
    val state: String,
    val createdAtNanos: Long,
    val completedAtNanos: Long?,
    val dispatcherId: String?,
    val dispatcherName: String?,
    val currentThreadId: Long?,
    val currentThreadName: String?,
    val jobId: String?,
    val exceptionType: String?,
    val exceptionMessage: String?,
    val activeChildrenIds: List<String>,
    val activeChildrenCount: Int,
    val creationPoint: SuspensionPoint?,
    val lastSuspensionPoint: SuspensionPoint?,
    val roundId: String?,
    val summary: CoroutineSummary?,
    /** Built from the summary only: the events that created it are not retained (or were never in this store). */
    val summaryOnly: Boolean = false,
    /** Replay only: this coroutine's state at the cursor is not fully known (plan E-1); open it from its summary. */
    val partial: Boolean = false,
)
```

(Check the projection's `HierarchyNode` field list and copy it exactly; the DTO only makes `scopeId`, `jobId` nullable.)

```kotlin
/** The projection as it was at [seq]: a throwaway in-memory session fed the retained events up to it. */
internal fun <T> projectionAt(sessionId: String, events: List<VizEvent>, seq: Long, read: (VizSession) -> T): T {
    val upTo = events.filter { it.seq <= seq }
    val probe = VizSession("$sessionId#at$seq", maxEvents = maxOf(1, upTo.size), eventStoreFactory = { EventStore(maxOf(1, upTo.size)) })
    try {
        upTo.forEach(probe.store::record)
        probe.projectionService.rebuildFrom(upTo)
        return read(probe)
    } finally {
        probe.close()
    }
}

internal fun hierarchyOf(view: SessionView, scopeId: String?, roundId: String?): List<HierarchyNodeDto> {
    val nodes =
        if (view.binding == ViewBinding.Current) {
            view.session.projectionService.getHierarchyTree(scopeId)
        } else {
            projectionAt(view.session.sessionId, view.events, view.seq) { it.projectionService.getHierarchyTree(scopeId) }
        }
    val summaries = view.input.summaries // Replay: the plain fold; Bound: the bound view; Current: durable
    val anchoring = RoundAnchoring.of(summaries)
    val inRound = { id: String -> roundId == null || anchoring.roundIdOf(id) == roundId }
    val known = nodes.mapTo(HashSet()) { it.id }
    val full = nodes.filter { inRound(it.id) }.map { it.toDto(summaries.get(it.id), anchoring.roundIdOf(it.id)) }
    val summaryOnly =
        summaries.all
            .filter { it.id !in known && inRound(it.id) && (scopeId == null) }
            .map { s ->
                view.requireExact(s.id)
                s.toSummaryOnlyDto(anchoring.roundIdOf(s.id), partial = view.binding is ViewBinding.Replay && view.bound.levelOf(s.id) != RebuildLevel.EXACT)
            }
    return (full + summaryOnly).sortedBy { it.createdAtNanos }
}
```

Summary-only nodes are left out when `scopeId` is given (a summary has no scope). `toSummaryOnlyDto`: `name = s.name ?: s.id`,
`state = projectionState(s.state)` (`ACTIVE` → `"RUNNING"`, otherwise the enum name, matching `ProjectionService`'s
strings), `createdAtNanos = s.firstObserved.appNanos`, `completedAtNanos = s.terminal?.appNanos`,
`currentThreadId/Name = s.lastThread` (null when terminal), `dispatcherName = s.dispatcher`,
`exceptionType/Message = s.failure`, `creationPoint = s.creationLocation`, `lastSuspensionPoint = s.lastSuspension`,
empty children lists, `summaryOnly = true`. In Replay the nodes' `summary` comes from the fold (the cursor's view).

Hierarchy route: parse the binding, keep `scopeId`, add `roundId`, wrap in `respondReadErrors`, respond
`hierarchyOf(view, scopeId, roundId)`. **When the summaries are unavailable** the unbound route must keep answering as
today (the hierarchy is not a health read): catch `SummariesUnavailableException`/`HistoryTrimmedException` only for
`ViewBinding.Current` and answer the projection nodes with `roundId = null, summary = null`.

Timeline route: `ViewBinding.Current` → unchanged code path. Otherwise:

```kotlin
val view = readView(session, ingest, binding, call.leakThresholdMs())
view.requireStateKnown(coroutineId)
val summary = view.input.summaries.get(coroutineId)
val timeline = projectionAt(sessionId, view.events, view.seq) { it.projectionService.getCoroutineTimeline(coroutineId) }
when {
    timeline != null -> respond(openPhaseRule(timeline))  // the same live/demo open-phase rule as today
    summary != null -> respond(HttpStatusCode.NotFound, ErrorResponse("This coroutine's events are not retained", code = "events-trimmed"))
    else -> respond(HttpStatusCode.NotFound, ErrorResponse("Coroutine not found"))
}
```

- [ ] **Step 3: Run** the new test and the existing `SessionRoutesTest`, `TimelineRouteAssembledTest` → PASS.
- [ ] **Step 4: Commit** `feat(health): hierarchy with rounds and summaries, hierarchy and timeline at a seq`.

---

### Task 9: API docs and types (5b-1), verification, review, PR

**Files:** `backend/src/main/resources/openapi/documentation.yaml`, `shared/api-types/openapi.json`,
`shared/api-types/generated.ts`, `docs/guides/API_REFERENCE.md`

- [ ] **Step 1: Schemas and paths.** As 5a's Task 11: one script (`$SCRATCH/part5b/openapi_5b1.py`, modelled on
`$SCRATCH/part5/openapi_health.py`) adds to both `documentation.yaml` and `openapi.json`:
paths `/api/sessions/{id}/rounds`, `/api/sessions/{id}/health/items`; the new query parameters on
`/api/sessions/{id}/hierarchy` and `…/coroutines/{coroutineId}/timeline` (`atSeq`, `asOfSeq`, `asOfWatermark`,
`asOfTransport`, `roundId`); schemas `RoundEntry`, `RoundsPage`, `RoundMember`, `MemberRole`, `IncidentsPage`,
`RoundMembersPage`, `ObservedAgesPage`, `HierarchyNodeDto`, `CoroutineSummary` (+ `FailureInfo`), `TransportStatus.revision`;
response codes 400/404/409 (`stale`)/503 with `ErrorResponse`. `SCRATCH=/private/tmp/claude-501/-Users-jirihermann-Documents-workspace-vizcore/9e9c5bc5-1b4c-412c-966d-6cf483ca2458/scratchpad`.
- [ ] **Step 2: `generated.ts`** — prove the generator reproduces today's file, then regenerate (5a Task 11 Step 3).
- [ ] **Step 3: `API_REFERENCE.md`** — rounds, items, hierarchy/timeline parameters, the binding rules (E-3) and `STALE`.
- [ ] **Step 4: Gates.** `./gradlew ktlintFormat && ./gradlew ktlintCheck detekt test :coroutine-viz-core:build`; frontend
`pnpm exec tsc --noEmit -p . && pnpm lint && pnpm test --run`. Revert `backend/docs/index.html` if regenerated. Commit
`docs(api): rounds, health items and bound reads`.
- [ ] **Step 5: API check on port 18901 only** (memory mode), outputs to `$SCRATCH/part5b-api/5b1-*.json`: a failing demo
(`rounds`, `rounds?q=payment`, `rounds?problemsOnly=true`, `health/items?list=incidents&asOfSeq=…`,
`health/items?list=round-members&incidentId=…`, `hierarchy?roundId=…`, `hierarchy?atSeq=…`,
`coroutines/{c}/timeline?atSeq=…`), and the 409 path (an attached session trimmed with a low
`session.maxEvents` if configurable via env, else covered by tests only — say so). Stop the server; confirm nothing
listens on 18901.
- [ ] **Step 6: Codex code review** up to 3 rounds + one confirmation round on the last fix
(`codex exec -s read-only -C <worktree> --skip-git-repo-check -o $SCRATCH/codex-part5b1-code-rN.md "<prompt>" < /dev/null`).
- [ ] **Step 7: Merge `origin/main`, push, open PR 5b-1** to `main` in #295's structure. Do not merge.

---

# PR 5b-2 — persistence, checks, shares, metrics by origin

Branch `feat/health-api-b2` from `feat/health-api-b` (`git switch -c feat/health-api-b2`).

### Task 10: Core — `SummaryTableState` and `SummaryTable.restore`

**Files:**
- Create: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/health/SummaryTableState.kt`
- Modify: `…/health/SummaryTable.kt` (restore entry point), `…/health/SubtreeIndex.kt` (none expected)
- Test: `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/health/SummaryTableStateTest.kt`

- [ ] **Step 1: Failing property test.** For 200 random histories and every split k: table A applies all events; table B
applies events[0, k), is turned into a state, JSON round-tripped (`Json.encodeToString`/`decodeFromString` with
`SummaryTableState.serializer()`), restored, and applies events[k, n). Assert `A.all == B.all` (same order, equal
values), and the five scalar fields equal. Include histories that exceed a small reduction target and capacity: build
both tables with `SummaryTable(fullSummaryTarget = 5, trackedCapacity = 30)` and restore with the same limits, so stubs,
the reduction queues and untracked counting are exercised across the split. Also a table test: a root promoted before
the split, a new child after it → `grewAfterPromotion` and promotion clearing on cancel behave as in A.

Run → compile failure.

- [ ] **Step 2: Implement.**

```kotlin
/** A checkpoint of a [SummaryTable] (spec C-4): restores to a table that behaves exactly as the original. */
@Serializable
data class SummaryTableState(
    val version: Int = 1,
    /** In first-observation order (the table's order). */
    val summaries: List<CoroutineSummary>,
    val untrackedCount: Long,
    val lastEventSeq: Long,
    val latestEventAppNanos: Long?,
    val firstEventSeq: Long,
    val earliestEventAppNanos: Long?,
)

fun SummaryView.toState(): SummaryTableState =
    SummaryTableState(
        summaries = all.toList(),
        untrackedCount = untrackedCount,
        lastEventSeq = lastEventSeq,
        latestEventAppNanos = latestEventAppNanos,
        firstEventSeq = firstEventSeq,
        earliestEventAppNanos = earliestEventAppNanos,
    )
```

In `SummaryTable`:

```kotlin
    companion object {
        /**
         * The table [state] was taken from, ready for more events. Rebuilds the bookkeeping in the original order: the
         * subtree index from each summary's parent (adds in first-observation order, then closes for finished ones), the
         * creation order and reduction queues from the full (non-stub) summaries.
         */
        fun restore(
            state: SummaryTableState,
            fullSummaryTarget: Int = HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET,
            trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
        ): SummaryTable = SummaryTable(fullSummaryTarget, trackedCapacity).also { it.load(state) }
    }

    private fun load(state: SummaryTableState) {
        check(summaries.isEmpty() && lastEventSeq == 0L) { "restore into an empty table only" }
        require(state.version == 1) { "unknown summary state version ${state.version}" }
        for (s in state.summaries) {
            summaries[s.id] = s
            subtrees.add(s.id, s.parentId)
            if (!s.reduced) {
                creationOrder[s.id] = nextCreationOrder++
                fullSummaryCount++
            }
        }
        for (s in state.summaries) {
            if (!s.isTerminal) continue
            subtrees.close(s.id)
            if (!s.reduced) (if (s.failed) finishedFailed else finishedOk)[creationOrder.getValue(s.id)] = s.id
        }
        untrackedCount = state.untrackedCount
        lastEventSeq = state.lastEventSeq
        latestEventAppNanos = state.latestEventAppNanos
        firstEventSeq = state.firstEventSeq
        earliestEventAppNanos = state.earliestEventAppNanos
    }
```

If the property test finds a difference in reduction order (a full summary's creation order index), keep a
`creationIndex` in the state rather than inventing a rule; report it in the commit message.

- [ ] **Step 3: Run** → PASS. **Step 4: Commit** `feat(health): the summary table checkpoints and restores exactly`.

---

### Task 11: Core — `VizSession` rehydrates from a checkpoint; one-cut reads

**Files:**
- Modify: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt`
- Test: `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/session/VizSessionCheckpointTest.kt`

- [ ] **Step 1: Failing tests:**
1. A session A (keepSummaries) fed 20 events. Session B with a store holding only events 11..20 and
   `rehydrateFromStore { stateOfFirst10 }` → `B.readSummaries { it.toState() } == A.readSummaries { it.toState() }`;
   `B.summarySeedSeq == 10`.
2. Seed covering events the store still holds (seed at 15, store 11..20): events ≤ 15 are not applied twice (same equality).
3. `rehydrateFromStore { null }` behaves as `rehydrateFromStore()` (`summarySeedSeq == null`).
4. The seed supplier is called **after** the store was read (record the call order with a store wrapper).
5. `readCut { table -> … }` runs under the send lock and passes `null` when summaries are unavailable.

- [ ] **Step 2: Implement.** Make `summaryTable` a `private var` (still guarded by `sendLock`). Add:

```kotlin
    /** The last seq of the checkpoint the summaries were restored from, `null` when they were folded from the store alone. */
    @Volatile
    var summarySeedSeq: Long? = null
        private set

    /**
     * [rehydrateFromStore] with the summaries restored from a checkpoint first (spec C-4). [seed] is called after the
     * store was read, so a trim that commits in between is seen whole (plan E-12). Events up to the checkpoint's last seq
     * rebuild the snapshot and projections only.
     */
    fun rehydrateFromStore(seed: () -> SummaryTableState?) {
        val events = store.all()
        val state = if (summaryTable != null) seed() else null
        synchronized(sendLock) {
            if (state != null) {
                summaryTable = SummaryTable.restore(state)
                summarySeedSeq = state.lastEventSeq
            }
            val after = state?.lastEventSeq ?: Long.MIN_VALUE
            events.forEach {
                applier.apply(it)
                if (it.seq > after) applyToSummaries(it)
            }
            val maxSeq = maxOf(events.maxOfOrNull { it.seq } ?: 0L, state?.lastEventSeq ?: 0L)
            if (maxSeq > lastSentSeq) {
                lastSentSeq = maxSeq
                seqGenerator.set(maxSeq)
            }
        }
        if (events.isNotEmpty()) {
            projectionService.rebuildFrom(events)
            metricsProjection.rebuildFrom(events)
        }
    }

    /** Runs [block] under the send lock with the live summary table (`null` when unavailable): one consistent cut. */
    fun <T> readCut(block: (SummaryView?) -> T): T =
        synchronized(sendLock) { block(summaryTable?.takeUnless { summariesDegraded }) }
```

and make the old `rehydrateFromStore()` call `rehydrateFromStore { null }` (keep its empty-store early return
semantics: with no events and no seed nothing changes). Restoring when the table was degraded is not possible (fresh
instance).

- [ ] **Step 3: Run** `./gradlew :coroutine-viz-core:test` → PASS. **Step 4: Commit** `feat(core): a session rehydrates its summaries from a checkpoint`.

---

### Task 12: Migration V2 and the summary checkpoint written by the trim (E-12)

**Files:**
- Create: `backend/src/main/resources/db/migration/common/V2__health_persistence.sql`
- Modify: `…/persistence/tables/SessionsTable.kt`, `…/persistence/ExposedSessionStore.kt`, `…/persistence/DbRetentionPolicy.kt`
- Create: `…/persistence/SummaryCheckpoints.kt`
- Modify: `…/routes/SessionHealthReads.kt` (`summariesSeeEveryEvent(session)`)
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/persistence/SummaryCheckpointTest.kt`, extend
  `routes/SessionHealthDatabaseModeTest.kt`, `persistence/MigrationLayoutTest.kt` (V2 lives in `common/` only)

- [ ] **Step 1: Migration.**

```sql
-- V2: health persistence (Part 5b, spec 2026-10-21 C-4/C-5). Portable: runs unchanged on H2 and PostgreSQL.
alter table sessions add column summaries text;
alter table sessions add column summaries_seq bigint;
alter table sessions add column ingest_state text;
alter table sessions add column ingest_open_since bigint;
alter table sessions add column checks text;
```

`SessionsTable`: `val summaries = text("summaries").nullable()`, `summariesSeq = long("summaries_seq").nullable()`,
`ingestState = text("ingest_state").nullable()`, `ingestOpenSince = long("ingest_open_since").nullable()`,
`checks = text("checks").nullable()`. Run `PostgresMigrationTest` only if `VIZCORE_PG_URL` is set (CI runs it).

- [ ] **Step 2: Failing tests.** `SummaryCheckpointTest` (H2, `DbRetentionPolicy(db, maxEventsPerSession = 10)`):
1. A session with 30 events (a container root, rounds, one failed round whose root's creation is in the first 10
   events): `cleanup()` trims to the newest 10; the row's `summaries_seq` = the newest trimmed seq; a freshly built
   session's `readSummaries { it.toState() }` equals that of a memory table fed all 30 events.
2. Trimming twice (40 more events, `cleanup()` again) folds the second batch onto the first checkpoint (same equality).
3. A legacy-trimmed session (events start at seq 5, no checkpoint): `cleanup()` writes no checkpoint and still trims.

Extend `SessionHealthDatabaseModeTest`: the 5a test asserting `503 history-trimmed` after a trim now asserts **200**
with the failed round intact (`failedRoundCount == 1`, no ungrouped failure), `retention.trimmed == true`, and
`GET /api/sessions/{id}` gives `captureStartAppNanos` = the first event's time; keep a second test for the legacy case
(delete the first events by SQL without a checkpoint) → still `503 history-trimmed`. Replay `…/health?atSeq=<trimmed seq>`
→ 200 with the failure known from the checkpoint.

Run → fails.

- [ ] **Step 3: Implement.**

`SummaryCheckpoints.kt`:

```kotlin
/** Summary checkpoints on the session row (spec C-4, plan E-12). Call inside a transaction. */
internal object SummaryCheckpoints {
    private val logger = LoggerFactory.getLogger(SummaryCheckpoints::class.java)

    fun read(sessionId: String): SummaryTableState? =
        SessionsTable.select(SessionsTable.summaries).where { SessionsTable.id eq sessionId }.firstOrNull()
            ?.get(SessionsTable.summaries)?.let { appJson.decodeFromString(SummaryTableState.serializer(), it) }

    /**
     * Folds the session's stored events below [keepFromSeq] into its checkpoint, so they can be deleted without losing
     * what the summaries know. Returns false (no checkpoint written) for a session trimmed before checkpoints existed
     * (oldest stored seq > 1 and no checkpoint: folding what is left would lose ancestry) or when an event cannot be
     * applied; the trim still happens and the session's health stays unavailable (H-3).
     */
    fun foldBelow(sessionId: String, keepFromSeq: Long): Boolean {
        val current = read(sessionId)
        val after = current?.lastEventSeq ?: 0L
        val events = EventsTable.selectAll()
            .where { (EventsTable.sessionId eq sessionId) and (EventsTable.seq greater after) and (EventsTable.seq less keepFromSeq) }
            .orderBy(EventsTable.seq to SortOrder.ASC)
            .map { decodeEvent(it[EventsTable.payload]) }
        if (events.isEmpty()) return current != null
        if (current == null && events.first().seq > 1) return false
        val table = current?.let(SummaryTable::restore) ?: SummaryTable()
        return try {
            events.forEach(table::apply)
            val state = table.toState()
            SessionsTable.update({ SessionsTable.id eq sessionId }) {
                it[summaries] = appJson.encodeToString(SummaryTableState.serializer(), state)
                it[summariesSeq] = state.lastEventSeq
            }
            true
        } catch (e: RuntimeException) {
            logger.error("Summary checkpoint of session {} failed; its health stays unavailable", sessionId, e)
            false
        }
    }
}
```

(`decodeEvent` = the `ExposedEventStore` decoder; make its `decode` an `internal` top-level function both use.) A
failed checkpoint must not leave a half-written row: the update is one statement after the fold succeeded.

`DbRetentionPolicy.trimEvents`: before the `deleteWhere`, call `SummaryCheckpoints.foldBelow(sessionId, keepCutoffSeq)`
(same transaction). `ExposedSessionStore.buildSession`: `it.rehydrateFromStore { transaction(db) { SummaryCheckpoints.read(sessionId) } }`.

`summariesSeeEveryEvent()` becomes `summariesSeeEveryEvent(session: VizSession)`: memory store → true; else
`session.summarySeedSeq != null`. Update the callers in `SessionHealthReads.kt` and `SessionViewReads.kt`
(`firstSeqEver` then comes from the restored table's `firstEventSeq`).

- [ ] **Step 4: Run** the new tests, `SessionHealthDatabaseModeTest`, `MigrationLayoutTest`,
`SessionDeleteDatabaseModeTest`, `DemoRunDatabaseModeTest` → PASS.
- [ ] **Step 5: Commit** `feat(persistence): the trim folds events into a summary checkpoint first`.

---

### Task 13: Persisted ingest state and the "ingest open" marker (E-13)

**Files:**
- Modify: `…/routes/IngestStates.kt` (persistence hook, open counter, throttle)
- Create: `…/persistence/ExposedIngestStatePersistence.kt`
- Modify: `…/persistence/ExposedSessionStore.kt` (initial state on create), `…/Application.kt` (hook + startup sweep),
  `…/routes/SessionHealthReads.kt` (`transportOf`)
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/IngestStatePersistenceTest.kt`

- [ ] **Step 1: Failing tests** (H2, database mode; "restart" = `IngestStates.clear()` + a new `ExposedSessionStore`
on the same `db` + `IngestStates.usePersistence(ExposedIngestStatePersistence(db))` + `ExposedIngestStatePersistence(db).sweepOpenMarkers()`):
1. App connects, sends events and a watermark at 12 s, closes with a `final` watermark → restart → `GET …/health`:
   `asOf.kind == WATERMARK`, `transport.deliveryUncertain == false` (5a's D-9b said uncertain), `GET /api/sessions/{id}`
   `lastWatermark.appNanos == 12 s`.
2. App connects, sends a watermark, and the process "crashes" (restart without closing the socket: call
   `IngestStates.connectionOpened` + `watermarkReceived` directly, then restart) → `transport.deliveryUncertain == true`,
   `sinceSeq` = that connection's first seq; the row's `ingest_open_since` is null after the sweep.
3. A loss reported before the restart (`lostEvents = 3`) is still 3 after it (sticky across restarts), revision kept.
4. A row with `ingest_state` null (set by SQL, as a pre-5b row) and stored events → still delivery-uncertain since 1
   (D-9b's rule for legacy rows).
5. Throttle: 10 watermarks within 1 s write the row at most twice (open + first frame); use an injectable clock on
   `IngestStates` (`internal var clock: () -> Long = System::currentTimeMillis`, reset by `clear()`).
6. Memory mode (no persistence hook): unchanged (5a's `IngestStatesTest` stays green).

- [ ] **Step 2: Implement.**

```kotlin
/** Where `IngestStates` keeps what must survive a restart (database mode only; spec C-3 marker, C-4). */
interface IngestStatePersistence {
    fun load(sessionId: String): PersistedIngestState?
    fun save(sessionId: String, state: PersistedIngestState)
    /** Sets or clears the "ingest open" marker (the earliest open connection's first seq). */
    fun setOpenMarker(sessionId: String, sinceSeq: Long?)
}

@Serializable
data class PersistedIngestState(
    val watermark: WatermarkObservation? = null,
    val lastIngestEpochMs: Long? = null,
    val transport: TransportStatus = TransportStatus(),
    val firstConnectionSeq: Long? = null,
)
```

`IngestStates` changes:
- `@Volatile private var persistence: IngestStatePersistence? = null`; `fun usePersistence(p: IngestStatePersistence?)`;
  `clear()` resets it and the clock.
- `State` gains `var openConnections = 0`, `var lastSavedAtMs = Long.MIN_VALUE`, and is created seeded from
  `persistence?.load(id)` (in `stateOf`).
- `connectionOpened`: under the state lock `openConnections++`; then `persistence?.setOpenMarker(id, minOpenFirstSeq)`
  (keep the open connections' first seqs in a small list on the state) and `save`.
- `eventReceived`/`watermarkReceived`/`frameSkipped`: after the update, `saveIfDue(state)` (≥ 5,000 ms since the last
  save by `clock`).
- `connectionClosed`: after marking, `openConnections--`, remove its first seq; `save` (always); marker = the earliest
  remaining open first seq, or null.
- `snapshot(id)`: the in-memory state when present, else `persistence?.load(id)` converted (not cached), else empty.
- `forget(id)` unchanged (the row is gone with the session).

Persistence calls run outside the state lock (copy the state under the lock, write after) and never throw into the
ingest loop: wrap them in `runCatching { … }.onFailure { logger.warn(…) }` — a failed write leaves the marker set, so
the sweep errs on the uncertain side.

`ExposedIngestStatePersistence(db)`: `load` reads `ingest_state` (null → null), `save` updates it, `setOpenMarker`
updates `ingest_open_since`; `sweepOpenMarkers()` selects rows with `ingest_open_since` not null and, for each,
saves the state with `transport.deliveryUncertain = true`, `sinceSeq = min(sinceSeq, marker)`, revision + 1 when that
changed, and clears the marker — all in one transaction, before the routes start (call it in `Application.kt` right
after `SessionManager.useStore(ExposedSessionStore(db, …))`, then `IngestStates.usePersistence(...)`).

`ExposedSessionStore.createSession` writes `ingest_state = "{}"` (an empty `PersistedIngestState`) so a new row is
told apart from a pre-5b one. `transportOf` keeps its D-9b branch only when the backing store reports the row as legacy:
add `ExposedSessionStore.hasIngestState(sessionId): Boolean` and use
`unwatched = … && !hasIngestState && (recovered || eventsBeforeWatching)`; for rows with a state,
`eventsBeforeWatching` alone still applies (events stored with no connection ever recorded).

- [ ] **Step 3: Run** the new test, `IngestStatesTest`, `IngestWatermarkTest`, `SessionHealthDatabaseModeTest`,
`SessionDeleteDatabaseModeTest`, `IngestRoutesTest` → PASS. **Step 4: Commit**
`feat(persistence): ingest state and the ingest-open marker survive a restart`.

---

### Task 14: Checks persistence (E-14, C-5)

**Files:**
- Create: `…/routes/ChecksResults.kt`
- Modify: `…/routes/ValidationRoutes.kt`, `…/persistence/ExposedSessionStore.kt` (`saveChecks`, `loadChecks`), `…/Routing.kt` (forget on close)
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/ChecksPersistenceTest.kt` (memory and H2)

- [ ] **Step 1: Failing tests:** `GET /api/validate/session/{id}` before any run → `200 {"sessionId":…,"lastRun":null,"newEventsSince":null}`;
after `POST` → `lastRun.checkedSeq` = the last seq, `rules` one per rule name with `passed`, `failures`;
`failedRuleCount` = rules with `passed == false`; send 3 more events → `newEventsSince == 3`. Database mode: the run
survives a new store instance (restart). Unknown session → 404. A deleted session's run is gone (memory registry).

- [ ] **Step 2: Implement.**

```kotlin
@Serializable data class RuleOutcome(val name: String, val passed: Boolean, val failures: Int)

@Serializable
data class ChecksRun(val checkedSeq: Long, val ranAtEpochMs: Long, val rules: List<RuleOutcome>, val failedRuleCount: Int)

@Serializable
data class ChecksStatus(val sessionId: String, val lastRun: ChecksRun?, val newEventsSince: Long?)

/** The last checks run per session (spec C-5, T-8): a column in database mode, an id-keyed map in memory. */
internal object ChecksResults { fun save(sessionId: String, run: ChecksRun); fun load(sessionId: String): ChecksRun?; fun forget(sessionId: String) }
```

`ChecksRun.of(results, events, now)`: group `ValidationResult`s by `ruleName` in first-seen order; a rule passed when
none of its results is a failure subtype (read `checksystem/ValidationResult.kt` for the subtypes; anything not `Pass`
counts as a failure); `failures` = its non-`Pass` results. The `POST` handler saves it after computing the response
(response unchanged). `GET` resolves the session (404), loads the run, `newEventsSince = session.lastSeq - checkedSeq`
(clamped at 0) when there is a run. `ChecksResults` delegates to `ExposedSessionStore.saveChecks/loadChecks` when that
is the backing store, else a `ConcurrentHashMap`; `Routing.kt` adds `SessionManager.addOnSessionClosed { ChecksResults.forget(it) }`.

- [ ] **Step 3: Run** → PASS (also `ChecksLiveSessionTest`). **Step 4: Commit** `feat(checks): the last run is kept and served per session`.

---

### Task 15: `/metrics` by origin (E-15)

**Files:**
- Modify: `…/routes/SessionRoutes.kt` (metrics handler), `…/routes/RouteDtos.kt`; core `…/health/SessionHealthDerivation.kt` (`longLivedByOrigin`)
- Test: extend `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/MetricsRouteTest.kt`; core `DeriveFullTest`

- [ ] **Step 1: Failing tests:** an attached session with an `APP` coroutine and a `VIZCORE` coroutine both created at 1 s
and suspended, last event at 45 s, then idle: `GET …/metrics?leakThresholdMs=30000` → `byOrigin.app.captured == 1`,
`byOrigin.vizcore.captured == 1`, both `active == 1`; `leaks` has two entries with `origin` `APP`/`VIZCORE`, each
`aliveMs` = 44,000 (frozen at its last event, not wall-clock now); a second call 1 s later gives the same ages. A
session with unavailable summaries (Task 1's store seam) → `byOrigin == null`, `leaks` from the projection with
`origin == null`. Existing threshold-echo tests stay green.

- [ ] **Step 2: Implement.** Core: `fun longLivedByOrigin(input: HealthInput): List<Pair<CoroutineOrigin, ObservedAge>>`
in `SessionHealthDerivation` — the long-lived rule of `HealthParts` over **all** coroutines (vizcore included), with
the effective origin. `RouteDtos`: `LeakDto` gains `val origin: CoroutineOrigin? = null`; `MetricsResponse` gains
`val byOrigin: OriginMetrics? = null` with

```kotlin
@Serializable data class OriginSlice(val captured: Int, val active: Int, val longLived: Int)
@Serializable data class OriginMetrics(val app: OriginSlice, val vizcore: OriginSlice, val unknown: OriginSlice, val untracked: Long)
```

The handler reads the summaries through `readView(session, ingest, ViewBinding.Current, leakThresholdMs)` (catching the
503 exceptions → today's projection leaks), builds `byOrigin` from `view.input.summaries` with `derived.originOf`, and
`leaks` from `longLivedByOrigin` (`aliveMs = ageNanos / 1_000_000`). `active`, `peak`, `throughputPerSec`,
`dispatcherUtilization` stay from the projection.

- [ ] **Step 3: Run** → PASS (`MetricsRouteTest`, frontend types later). **Step 4: Commit** `feat(metrics): split by origin, leak ages frozen at the last observation`.

---

### Task 16: Shares under one cut and the shared reads (E-16, C-6)

**Files:**
- Modify: `…/share/ShareRoutes.kt`, `…/share/ShareDtos.kt`, `…/share/ShareService.kt` (`resolve(token, countAccess)`),
  `…/routes/SessionRoutes.kt` (`snapshotResponseOf` from a cut), `…/routes/SessionHealthReads.kt` (health from a cut)
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/share/SharedHealthRoutesTest.kt` (H2)

- [ ] **Step 1: Failing tests** (database mode, auth off, a failing session shared with `POST …/share`):
1. `GET /api/shared/{token}` → `health.failedRoundCount == 1`, `health.asOfSeq == max(events.seq)`,
   `session.eventCount == events.size`, `checks.lastRun == null`; after a `POST /api/validate/session/{id}`,
   `checks.lastRun` is set.
2. One cut: send events from a second coroutine while loading 20 times in a loop (in memory mode the share service is
   absent, so this uses database mode with a concurrent ingest socket) — every response has
   `health.asOfSeq == events.last().seq` and `session.eventCount == events.size`.
3. `GET /api/shared/{token}/health?atSeq=N`, `/health/items?list=incidents&asOfSeq=S`, `/rounds?asOfSeq=S`,
   `/hierarchy?roundId=…&asOfSeq=S`, `/coroutines/{c}/timeline?asOfSeq=S` → 200 with the same bodies as the session
   routes; an expired share → 410; unknown → 404; `access_count` of the share is incremented by the payload load only.
4. A session whose health is unavailable (legacy trimmed) → payload `health == null`, `healthUnavailable == "history-trimmed"`.

- [ ] **Step 2: Implement.**
- `ShareService.resolve(token: String, countAccess: Boolean = true)`.
- `SharedSessionResponse` gains `val health: SessionHealth? = null`, `val healthUnavailable: String? = null`,
  `val checks: ChecksStatus? = null`.
- A cut: `session.readCut { table -> Cut(table?.snapshot(), session.snapshot.nodes(), session.lastSeq) }` (the snapshot
  nodes and `lastSeq` are read under the same lock); events = `session.store.all().filter { it.seq <= cut.seq }`.
  `snapshotResponseOf(session, events, nodes, durable)` gains the two optional parameters (the session route keeps
  calling it as today). Health from the cut: refactor `readSessionHealth` so its body after the summary copy is
  `healthFrom(session, durable, ingest, events, atSeq, leakThresholdMs)`, and call that with the cut's copy and events
  (unavailable → `healthUnavailable` = the 503 code).
- Shared read routes in `registerSharedPublicRoute` (all under the shared rate limit): a helper
  `withSharedSession(call) { session -> … }` resolving the token with `countAccess = false` (410/404 as the payload
  route), then exactly the session routes' bodies (`readView` + `listRounds` / `respondHealthItems` / `hierarchyOf` /
  timeline / health). Extract each session route's body into a function taking `(call, session, ingestCut)` so both
  route sets call the same code.

- [ ] **Step 3: Run** → PASS (and the existing share tests). **Step 4: Commit** `feat(share): health and checks under one cut, shared health reads`.

---

### Task 17: API docs and types (5b-2), verification, review, PR

- [ ] **Step 1:** Docs and types as Task 9 (`$SCRATCH/part5b/openapi_5b2.py`): `GET /api/validate/session/{id}`
(`ChecksStatus`, `ChecksRun`, `RuleOutcome`), `MetricsResponse.byOrigin` (`OriginMetrics`, `OriginSlice`),
`LeakDto.origin`, `SharedSessionResponse.health/healthUnavailable/checks`, the five shared read paths; regenerate
`generated.ts`; `API_REFERENCE.md` (persistence notes: summaries survive the trim, ingest state survives a restart,
checks, shares). Gates as Task 9 Step 4. Commit `docs(api): checks, metrics by origin, shared health reads`.
- [ ] **Step 2: API check on 18901**, memory mode (checks, metrics by origin) **and database mode**
(`STORAGE_TYPE=database` with an H2 file under `$SCRATCH/part5b-api/db` if `application.yaml` reads it from env; check
the config keys first): create an attached session, ingest events and a watermark, close cleanly, restart the server,
`GET …/health` (watermark kept, not uncertain); share it and save `GET /api/shared/{token}` and the shared rounds/items.
Outputs to `$SCRATCH/part5b-api/5b2-*.json`. Persistence across the trim is proven by `SummaryCheckpointTest` and
`SessionHealthDatabaseModeTest` (the retention loop is hourly). Stop the server; confirm nothing listens on 18901.
- [ ] **Step 3: Codex code review** up to 3 rounds + one confirmation round (`codex-part5b2-code-rN.md`).
- [ ] **Step 4: Push `feat/health-api-b2`, open PR 5b-2** with base `feat/health-api-b` (stacked; retarget to `main`
after 5b-1 merges), #295's structure. Do not merge.
