# Part 5b — Health API: rounds, items, bound reads, persistence, checks, shares — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish triage PR 2 on top of Part 5a (#295): the follow-up reads a health view needs (rounds with server-side
search, `health/items`, hierarchy with C-2 fields and round ids, the per-coroutine timeline), all bound to the health's
`asOfSeq` and answering `STALE` instead of mixing states; then C-4 persistence (summaries survive the database trim,
ingest state and the "ingest open" marker survive a restart), C-5 checks persistence, C-6 shares, and `/metrics` by origin.

**Architecture:** Core gains one pure piece per concern: `SessionHealthDerivation.deriveFull` (uncapped lists for
paging), `SummaryBinding.bindAt` (the summaries at a past seq, each coroutine known or unknown), a short-lived cache of the exact cuts health responses used, and
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
| **5b-1** (branch `feat/health-api-b`) | Tasks 1–9: the two tests 5a's reviews asked for; `TransportStatus.revision`; core `deriveFull` and `SummaryBinding`; the binding parameters, kept health cuts and `STALE`; `GET …/rounds`, `GET …/health/items`, `GET …/hierarchy` (C-2 fields, `roundId`, `?roundId=`, `?atSeq=`/`?asOfSeq=`), the timeline bound to a seq; API docs and types | `main` |
| **5b-2** (branch `feat/health-api-b2`) | Tasks 10–17: core `SummaryTableState`; migration V2; summary checkpoints written by the trim and restored on every database read (lifts 5a's `503 history-trimmed` for every session trimmed from now on); persisted ingest state and "ingest open" marker with the startup sweep (replaces D-9b); C-5 checks persistence; `/metrics` by origin; C-6 shares under one cut and the shared read routes; API docs and types | `feat/health-api-b` (retarget to `main` once 5b-1 merges) |

Each is shippable alone: 5b-1 makes every follow-up read of a live or replay view consistent with its health (database
sessions keep 5a's limits until 5b-2); 5b-2 adds durability and the shared view on top.

## Decisions this plan takes (spec gaps; the PRs list them)

| # | Gap | Decision |
|---|---|---|
| E-1 | What "its state at `asOfSeq` can be rebuilt from retained events" means | `SummaryBinding.bindAt(durable, retained, s)` folds the retained events to `s`, seeded from the durable summaries (5a's `SummaryFold`), and rates each coroutine first observed at or before `s` **EXACT** or **UNKNOWN**. EXACT, with the value used: the **durable** summary when it did not change after `s` (`lastModifiedSeq ≤ s`), or when the coroutine had finished by `s` (after its terminal a summary changes only by reduction to a stub, which drops details and says so with `reduced`); the **fold's** summary when its creation is retained (every event it had up to `s` is retained). UNKNOWN otherwise (creation trimmed, still active at `s`, changed since): its fold value is a guess and is never presented as known. The view's observation times take the overrides into account (`latestEventAppNanos` = max with their last observations, `earliestEventAppNanos` = min with their first observations), so "as of" never trails a summary the view shows; in a trimmed replay it stays a lower bound (5a's D-10b). A property test checks every EXACT value against a fold of the untrimmed history, including across reduction. (Round 1 of the plan review showed that a retained transition alone does not rebuild run start or last observation: no intermediate level.) |
| E-2 | Which coroutines a read "needs" | `rounds` and `health/items` derive from the whole view, so in a bound read any UNKNOWN coroutine makes them `STALE`. `hierarchy` needs EXACT for every node it returns; the timeline for its coroutine. A bound read that hits a cached cut (E-17) has no UNKNOWN coroutine, so `STALE` happens only when the cut is gone (expired, another process). |
| E-3 | Live versus replay follow-ups | Two bindings, exclusive (both → `400`): `asOfSeq=S[&asOfWatermark=W][&asOfTransport=R]` binds a live or shared view to the health it fetched and is **strict** (`STALE` per E-2); `atSeq=N` is the replay view and is **lenient**, as spec "Modes"/Replay asks: nothing is `STALE`. Replay derives from the same bound view it rates (E-1's overlay: durable where exact, fold elsewhere) — `GET …/health?atSeq` switches from 5a's plain fold to it, so health, rounds, items and hierarchy of one cursor agree and are never less exact than before — and says what it does not know: hierarchy nodes carry `partial: true`, round entries `unknownMembers`, every page `unknownAtSeq` (0 outside replay). Possibly blocked is not evaluated (REPLAY). Neither binding: the current state. `asOfSeq`/`atSeq` later than the last event are clamped to it (as 5a's D-11). `asOfWatermark`/`asOfTransport` without `asOfSeq` → `400`. |
| E-4 | Wire form of `STALE` | `409 Conflict` with `ErrorResponse` code `stale` ("this view has moved on"). |
| E-5 | A transport change between the health read and a follow-up read (possibly blocked turns not evaluated) | A cached cut (E-17) carries the transport the health used, so a hit needs nothing more. For a miss the transport facts, sticky and not rebuildable at a past seq, get a counter: `TransportStatus.revision` (core field, default 0), bumped by `IngestStates` on every change and echoed in the health; a follow-up may pass `asOfTransport=<revision>`, and a different current revision → `STALE`. Not in the spec's parameter list. |
| E-6 | Hierarchy, current or at a seq | The projection (`HierarchyNode`) updates asynchronously through the bus, so it cannot be paired with a summary copy. Every hierarchy read therefore rebuilds the projection from the read's event cut (the retained events up to the view's seq) in a throwaway in-memory `VizSession`, so nodes and summaries describe one moment. A coroutine the summaries know but the rebuilt projection lacks (its creation is not retained) is a **summary-only node** built from its summary (`summaryOnly: true`; `scopeId`, `jobId`, `dispatcherId` null). Every node gains `roundId`, `summary` (the C-2 `CoroutineSummary`, `null` beyond capacity) and `summaryState` (`CoroutineSummary.state` is computed and not serialized). `state` stays the projection's string (as today: a `CoroutineFailed` with a cancellation exception reads `FAILED`, `JobStateChanged` applies); `summaryState` is the health's classification (cancellation → `CANCELLED`) and is what problem counts use. The two can differ by design; the DTO documents both. The response stays a JSON array of the new `HierarchyNodeDto`; new fields are additive, `scopeId`/`jobId` become nullable. Without summaries (unavailable, or a database session trimmed without a checkpoint) the current hierarchy still answers, with the rebuilt projection only. |
| E-7 | Timeline, current or at a seq | `…/timeline?atSeq=` or `?asOfSeq=`: the throwaway session's timeline (events up to the seq). A coroutine whose creation is not retained but whose summary is known gets a timeline from a creation synthesized from its summary plus its retained events (none → empty list), with `eventsTrimmed: true` and the three durations null (unknown). A bound or replay timeline describes its moment, not now: no open phase and no `observedAtEpochMs`, so a client cannot extrapolate past the bound observation; its `state` is the summary's at the seq, and in replay a `partial: true` field says when that state is not known (E-1 UNKNOWN: the value is the fold's guess); strict reads answer `STALE` instead. The current timeline (no binding) keeps today's behaviour. |
| E-8 | Rounds list details | Order: problem rounds first, then anchor creation time (app clock) newest first, then anchor seq, then anchor id. Each entry: core `RoundSummary` fields + `problem` + `problemKinds`, and with `q` the matching coroutine ids (`matches`, capped at 50 with total). Rounds anchored by a `VIZCORE` coroutine without a problem are left out unless `includeVizcore=true` (same rule as `SessionHealth.roundCount`). `q`: case-insensitive substring of a member's name, creation function or creation file; blank `q` = no filter; at most 200 characters (`400` beyond). |
| E-9 | Paging | `limit` 1..200, default 50 (`400` outside). Keyset cursor over each list's sort key `(group, a, b, id)` — group ascending, `a` and `b` descending, id ascending — encoded as `g.a.b.<base64url(id)>`; `400` for a cursor that does not parse. Incidents: (kind, sortSeq, 0, incident id); round members: (role, terminal seq, 0, id); long-lived: (0, first-observed seq, 0, id); long-suspended: (0, last-transition seq, 0, id); rounds: (problem ? 0 : 1, anchor nanos, anchor seq, anchor id). The core lists are sorted by **the same complete comparators** (each gains its id tie-break: the incident order, the long-lived/long-suspended order, the failed-round member order, `RoundAnchoring.rounds()` and the problem rounds), so a page boundary inside a run of equal seqs (legacy duplicate seqs) neither skips nor repeats an item. Every page carries `asOfSeq`, `total` and `nextCursor` (null on the last page). |
| E-10 | `health/items` shapes | `list=incidents[&kind=FAILED\|POSSIBLY_BLOCKED]` → `Incident` items (the derivation's own, so they equal the health's first 50); `list=round-members&incidentId=` → `RoundMember` (coroutine, name, role `FAILED`\|`CANCELLED_AFTER`, type, message, terminal seq, details dropped), failures first; `404` code `incident-not-found` when the incident is not a failed round in this view; `list=long-lived`, `list=long-suspended` → `ObservedAge`. Unknown `list` or `kind` → `400`. |
| E-11 | `deriveFull` | `SessionHealthDerivation.deriveFull(input)` returns the health plus the uncapped incident, problem-round, long-lived and long-suspended lists, the anchoring and the effective-origin function; `derive(input)` is `deriveFull(input).health`, unchanged in output. |
| E-12 | Summary checkpoint (C-4) | The database trim folds the events it is about to delete into a `SummaryTableState` checkpoint on the session row (columns `summaries`, `summaries_seq`) in the same transaction as the delete. A database read reads the events, then the checkpoint, and retries (up to 3 times, then `SummariesUnavailableException`) while the checkpoint is newer than the newest event read: so summaries, snapshot and projections are rebuilt from one cut, and a trim committing in between is never half seen. A restored table is identical to the in-memory one (property test), so a checkpointed session reads as memory mode does: `trimmed` exact, `captureStartAppNanos` known, health and replay available. **Replaces D-9 for every session trimmed from now on.** When the fold fails (an event that does not decode or apply), the trim clears the checkpoint in the same transaction before deleting, so the session reads as trimmed without one and answers `503 history-trimmed` (never a health that silently misses the deleted range). A session trimmed before 5b-2 (no checkpoint, oldest stored seq > 1) gets no checkpoint and keeps answering `503 history-trimmed`. |
| E-13 | Persisted ingest state (C-4 watermark column, C-3 "ingest open" marker) | Columns `ingest_state` (JSON: watermark, last ingest time, transport with revision, first connection seq) and `ingest_open_since` (seq). `IngestStates` takes a persistence hook in database mode. Every write is one `UPDATE` setting both columns, made **under the session's state lock**, so writes land in the order the state changed. A connection's state is seeded from the row; opening writes state + marker (the earliest open connection's first seq) and, if that write fails, the socket is refused (closed with `TRY_AGAIN_LATER`) before any event is accepted, and every registration the route made before it (socket counter, `IngestConnections`, the deletion watcher) is undone — opening moves inside the route's `try/finally`; frames write at most every 5 s; closing writes the final state and the marker of the remaining connections (null after the last) in one statement — if it fails, the old marker stays and the startup sweep marks the session uncertain. Reads use this process's state when it has one, else the row. At startup every row whose marker is set is marked delivery-uncertain since that seq (revision + 1) and its marker cleared, in one statement per row. New rows start with an empty state. **Replaces D-9b** for rows that have a state; a row from before 5b-2 (`ingest_state` null) keeps D-9b's rule. |
| E-14 | Checks persistence (C-5) | `POST /api/validate/session/{id}` also stores the run (checked seq = the last event validated, run time, per rule: name, passed, failure count); `GET /api/validate/session/{id}` answers `{sessionId, lastRun, newEventsSince}` (`lastRun` null when never run; `newEventsSince` = the session's last seq − checked seq). Memory mode: an id-keyed registry dropped on delete; database mode: column `checks`. |
| E-15 | `/metrics` by origin | `MetricsResponse` gains `byOrigin` (per effective origin — demos read all as `APP`, a session whose origin is `UNKNOWN` all as unknown, as the derivation does — with `captured`, `active`, `dispatcherUtilization` and `longLived` from **one summary cut**, the same the long-lived rule reads; `peak` and `throughputPerSec` from per-origin buckets the core `MetricsProjection` keeps, `peak` raised to at least `active`), `retainedOnly` (the projection figures come from retained events only: a database session rebuilt after a trim) with `oldestRetainedAppNanos`; each `leaks[]` entry gets its `origin`. The per-origin throughput window uses one clock: the backend's receipt time of each event as the live session applies it (an attached app's clock is unrelated to the backend's); a rebuilt session has no receipt times, so its per-origin throughput is `null`. Today's total `throughputPerSec` is left as is (pre-existing; noted in the PR). Leak ages become the core long-lived rule's (frozen at the last observation or the live watermark, the same threshold), so the state bar filter and the health's "Long-lived" agree. When the summaries are unavailable, `leaks` is empty, `leaksAvailable: false`, `byOrigin` null — never ages that keep growing on the wall clock (H-1, H-3). |
| E-16 | Shares under one cut (C-6) | The share load reads, in this order: the ingest snapshot and the stored checks run (both before the session is resolved, so neither is newer than the cut: 5a D-9c, and a run's checked seq is at most the cut's), the session, then the cut under the session's send lock (summaries copy, coroutine nodes, last seq). Events served = the stored ones up to that seq; the C-3 session facts and the health are built from the cut and that ingest snapshot. It adds `health` (or `healthUnavailable` with the 503 code) and `checks` (E-14's body), and caches the cut (E-17) so the shared view's follow-up reads are exact. Shared reads `GET /api/shared/{token}/health`, `…/health/items`, `…/rounds`, `…/hierarchy`, `…/coroutines/{coroutineId}/timeline` take the same parameters as the session routes and share their handlers; they do not count as share accesses (`ShareService.resolve(token, countAccess = false)`). |
| E-17 | Bound reads on a busy app (no livelock) | `HealthCuts`, an id-keyed in-memory registry, keeps the exact cut each live health response and share load used: the summaries copy, the ingest facts (watermark, last ingest, the transport it reported), retention, seq, and the **transition evidence the derivation read** (`transitionsFor` is recorded while the health is derived), so a follow-up's possibly blocked items equal the health's even after a trim. A cut is identified by its full binding — seq, watermark app time (null included) and transport revision — so two healths at one seq that differ by a quiet poll or a transport change are different cuts, and a bound read matches the one its parameters name (a read that omits `asOfTransport` matches the newest cut with its seq and watermark). Up to 8 cuts per session, least recently used dropped first; a hit refreshes a cut's 60 s lifetime; a health identical to a kept cut refreshes it instead of adding one; at most 256 in all. A bound read that matches a cut is exact by construction; only a miss (more than 8 other cuts of the session used since, expired, or another process) falls back to E-1's rating and may be `STALE` — so progress is guaranteed for a view that follows up within 60 s while fewer than 8 other cuts of the session are taken, not unconditionally. Process-local like `IngestStates`. |

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
| `…/routes/HealthCuts.kt` (new) | the kept health cuts (E-17) | 5b-1 |
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

## Plan review round 2: amendments to the tasks below

Where a task below and this list disagree, this list wins.

- **Task 3** (done, `be0ad600`) **+ Task 6 follow-up:** every core list gets its id tie-break (E-9): `INCIDENT_ORDER`
  `.thenBy { it.id }`; long-lived/long-suspended `sortedWith(compareByDescending(seqOf).thenBy { it.id })`; failed-round
  failures and cancellations `compareByDescending(terminalSeq).thenBy { it.id }`; `RoundAnchoring.rounds()` and
  `HealthParts.problemRounds` `.thenBy { it.anchorId }`. Test: duplicate-seq fixtures (events with equal seqs, as legacy
  database logs hold) paged one item at a time through every list visit each item exactly once.
- **Task 4** (done, `bcaa9f0e`) **follow-up:** `OverlaidView` overrides `latestEventAppNanos` (max of the fold's and the
  overrides' `lastObservedNanos`) and `earliestEventAppNanos` (min with their `firstObserved.appNanos`). Test: round 2's
  case (creation at 1 s, start at 100 s, suspension at 3 s, another coroutine's creation retained alone, bound before it)
  → `latestEventAppNanos == 100 s`; through HTTP, `GET …/health?atSeq` reports `asOf.appNanos == 100 s` with
  `lowerBound: true`.
- **Task 5** (done, `ef76ee82`) **follow-up — `HealthCuts` (E-17):** `HealthCut` gains `transitions: Map<String, List<Transition>>`
  (recorded: `readSessionHealth` derives with a recording `transitionsFor` and stores what it was asked); a cut hit's
  `transitionsFor` reads that map. Identity: `find(sessionId, seq, watermarkNanos, transportRevision?)` matches seq and
  watermark app time (null = null) and, when given, the revision; `PER_SESSION = 8`, a hit moves the cut to the newest
  position and refreshes `createdAtMs`; `remember` of a cut equal in (seq, watermark, revision) to a kept one replaces
  it. Tests: two healths at one seq around a quiet poll that makes a coroutine possibly blocked → a follow-up with the
  first health's watermark gets no possibly blocked item, with the second's gets one; a transport change without an
  event → the earlier binding still matches its own cut; **competing readers**: health A, then 5 other healths of the
  session (new events between each), then A's follow-up → a hit; a cut's possibly blocked items equal the health's
  after a trim removed the coroutine's earliest transitions.
- **Task 8:** drop the "state agrees with summaryState" test; instead every node of a 20-read run under a background
  writer has a summary whose `lastModifiedSeq ≤` the response's seq (the response sets header `X-Viz-As-Of-Seq`), and a
  fixture with a `CoroutineFailed(CancellationException)` and a `JobStateChanged` shows `state == "FAILED"`,
  `summaryState == CANCELLED`. The bound/replay timeline gets `"partial": <isPartial(coroutineId)>`; test: a
  creation-trimmed coroutine running at the cursor and changed since → replay timeline `partial: true`, strict → 409.
- **Task 12, test 6:** append **15** events (more than the 10 retained) between the two reads so the new checkpoint
  is newer than every event read (a retry); a second test appends 3 (checkpoint within the read range) and asserts the
  first read's cut is accepted without a retry (count the seam's calls).
- **Task 13:** `connectionOpened` moves inside the ingest route's `try` (the `finally` closes the connection only when
  it was opened); test 8 also asserts `IngestConnections.isConnected == false`, the session's open-socket counter back
  to 0 and no watcher left (`SessionManager.onSessionClosedListenerCount()` unchanged). 5a's
  `SessionHealthDatabaseModeTest` case that backdates a new row and expects uncertainty sets `ingest_state` to null
  first (a legacy row); add: a new row that never had a connection reads clean after a restart.
- **Task 15:** `MetricsProjection` per-origin buckets keep peak and a receipt-time window only (`System.nanoTime()` when
  the live bus delivers the event; `rebuildFrom` leaves the window empty and marks the buckets rebuilt →
  `throughputPerSec = null`). `captured`, `active`, `dispatcherUtilization` (by `summary.dispatcher ?: "unknown"`) and
  `longLived` come from the summary copy of the same `readView`. Tests: an app clock offset by hours from the backend's
  still gives a non-zero live per-origin throughput; a database session's per-origin throughput is null; `peak ≥ active`
  per origin under a projection subscriber held back by a latch.

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

**Done in `def27e80`** (as below, with the seam firing *before* the allocation — `beforeStateAllocatedForTest` — so the
delete's own `forget` runs first and only the route's cleanup can remove the state; `IngestStates.states` is `internal`
for the assertion instead of a `hasState` function, which detekt's object function limit refused). Each test fails
with its cleanup removed. These cover behaviour 5a shipped, so they pass once written. "Fail first" is shown by mutation: each step names the
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

**Done in `80f148be`.** The test uses the file's `watermark(appNanos = …)` builder; the four existing exact
`TransportStatus(...)` assertions now expect `revision = 1`.

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

- [ ] **Step 1: Failing tests.** Table tests first (each with `EventScript`; durable = a `SummaryTable` fed every
event; retained = the events with seq ≥ T; truth = `SummaryFold.foldTo(allEvents, s)`):
1. Nothing trimmed, `s` < last: every coroutine EXACT and `bound.view.get(id) == truth.get(id)`; a coroutine created
   after `s` is absent.
2. `s` ≥ durable.lastEventSeq: `bound.view` is `durable` (same instance), `unknownIds` empty.
3. Creation trimmed, unchanged since `s`: EXACT, equal to the durable summary and to truth.
4. Creation trimmed, failed before `s`, reduced to a stub after `s` (build with `SummaryTable(fullSummaryTarget = 1)`
   for durable and truth alike): EXACT, equal to the durable stub; truth differs only in the fields `toStub()` drops
   (assert `truth.toStub().copy(lastModifiedSeq = …) == bound` field by field except `lastModifiedSeq`).
5. Creation trimmed, started at app time 100 (trimmed), suspended at app time 3 (retained, ≤ `s`), resumed after `s`:
   UNKNOWN (round 1's counterexample: neither run start nor last observation is recoverable).
6. Creation trimmed, active, only thread assignments retained after `s`: UNKNOWN, listed in `unknownIds`.

Property test (model: `SummaryFoldSeedTest`): 200 seeded random histories, every trim point T and cursor s, with a small
reduction target so stubs occur (same target for durable, truth and the fold):

```kotlin
val bound = SummaryBinding.bindAt(durable, retained, s)
assertEquals(truth.all.map { it.id }.toSet(), bound.view.all.map { it.id }.toSet())
for (t in truth.all) {
    if (bound.levelOf(t.id) != RebuildLevel.EXACT) continue
    val b = bound.view.get(t.id)!!
    if (b.reduced && !t.reduced) assertEquals(t.toStub().copy(lastModifiedSeq = b.lastModifiedSeq), b)
    else assertEquals(t, b)
}
assertEquals(RoundAnchoring.of(truth).anchorIds, RoundAnchoring.of(bound.view).anchorIds) // structure exact (5a D-10)
```

If the EXACT equality fails for a fold-valued coroutine (creation retained), stop and report: E-1's claim would be
wrong and needs revisiting, not the test.

Run: `./gradlew :coroutine-viz-core:test --tests "*SummaryBindingTest"` → compile failure.

- [ ] **Step 2: Implement.**

```kotlin
package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.VizEvent

/** Whether a coroutine's summary at a past seq is known (spec C-3 `STALE`, plan E-1). */
enum class RebuildLevel { EXACT, UNKNOWN }

/** The summaries at a past seq and which of them are known; see [SummaryBinding.bindAt]. */
class BoundSummaries internal constructor(
    val view: SummaryView,
    val unknownIds: Set<String>,
) {
    fun levelOf(id: String): RebuildLevel = if (id in unknownIds) RebuildLevel.UNKNOWN else RebuildLevel.EXACT

    companion object {
        /** Every summary of [view] known (a cached cut, or the current state). */
        fun exact(view: SummaryView): BoundSummaries = BoundSummaries(view, emptySet())
    }
}

object SummaryBinding {
    /**
     * The summaries as they were at [atSeq] (plan E-1): [durable] itself when nothing came after it, else the replay fold
     * of [retained] (in seq order) seeded from [durable], where the durable summary replaces the fold's for every
     * coroutine whose summary did not change after [atSeq] or that had finished by then (after its terminal a summary
     * changes only by reduction, which says so). A coroutine whose creation is retained keeps its fold value (exact).
     * Every other coroutine is UNKNOWN: its fold value is a guess.
     */
    fun bindAt(
        durable: SummaryView,
        retained: List<VizEvent>,
        atSeq: Long,
    ): BoundSummaries {
        if (atSeq >= durable.lastEventSeq) return BoundSummaries.exact(durable)
        val fold = SummaryFold.foldTo(retained, atSeq, durable)
        val oldestRetained = retained.firstOrNull()?.seq ?: Long.MAX_VALUE
        val overrides = HashMap<String, CoroutineSummary>()
        val unknown = HashSet<String>()
        for (f in fold.all) {
            val d = durable.get(f.id) ?: continue
            when {
                d.lastModifiedSeq <= atSeq -> overrides[f.id] = d
                d.terminal?.let { it.seq <= atSeq } == true -> overrides[f.id] = d
                f.firstObserved.seq >= oldestRetained -> Unit
                else -> unknown += f.id
            }
        }
        return BoundSummaries(if (overrides.isEmpty()) fold else OverlaidView(fold, overrides), unknown)
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

(The durable override comes first even for retained creations: for those the two agree, and the durable one is the
same object the current health uses.)

- [ ] **Step 3: Run** the test → PASS; then `./gradlew :coroutine-viz-core:test`.

- [ ] **Step 4: Commit** `feat(health): summaries bound to a past seq, each known or unknown`.

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

- [ ] **Step 3: `HealthCuts.kt`** (E-17), test first in `HealthCutsTest`: `remember` then `find(id, seq)` returns the
cut; a third cut for one session drops the oldest; a cut older than 60 s (injectable `clock`) is not found; the 257th
cut overall drops the oldest of all; `forget(id)` drops a session's cuts; `clear()` resets all and the clock.

```kotlin
/** The exact inputs one health response used (spec C-3 `asOfSeq`), kept briefly so its follow-up reads are exact (E-17). */
internal class HealthCut(
    val sessionId: String,
    val seq: Long,
    val summaries: SummaryView,
    val watermark: WatermarkObservation?,
    val lastIngestEpochMs: Long?,
    val transport: TransportStatus,
    val retention: RetentionInfo,
    val createdAtMs: Long,
)

internal object HealthCuts {
    const val PER_SESSION = 2
    const val MAX_TOTAL = 256
    const val TTL_MS = 60_000L

    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    private val cuts = LinkedHashMap<String, ArrayDeque<HealthCut>>() // guarded by itself

    fun remember(cut: HealthCut) { /* append; per-session cap; global cap by oldest createdAtMs */ }
    fun find(sessionId: String, seq: Long): HealthCut? { /* newest matching seq, not older than TTL */ }
    fun forget(sessionId: String) { … }
    internal fun clear() { … }
}
```

`Routing.kt` adds `SessionManager.addOnSessionClosed { HealthCuts.forget(it) }`. The live `GET …/health` (no `atSeq`)
remembers its cut: refactor `readSessionHealth` so the live branch builds a `HealthCut` from the values it already has
(`durable`, `ingest.watermark`, `ingest.lastIngestEpochMs`, the transport it passes, `retention`, `durable.lastEventSeq`)
and calls `HealthCuts.remember` before deriving. A test in `SessionHealthRouteTest`: after `GET …/health`,
`HealthCuts.find(id, health.asOfSeq)` is not null.

- [ ] **Step 4: `SessionViewReads.kt`** — the skeleton every follow-up read uses; mirrors `readSessionHealth`'s order.

```kotlin
/** One follow-up read's view of a session (plan E-1..E-3, E-17). */
internal class SessionView(
    val session: VizSession,
    /** The stored events read after the summaries were copied, up to [seq]. */
    val events: List<VizEvent>,
    val binding: ViewBinding,
    /** The seq the view is at: the last event (Current), the clamped cursor (Replay, Bound). */
    val seq: Long,
    val bound: BoundSummaries,
    val input: HealthInput,
) {
    val strict: Boolean get() = binding is ViewBinding.Bound

    /** 0 outside replay; in replay, how many coroutines' state at the cursor is not known (E-3). */
    val unknownAtSeq: Int get() = if (binding is ViewBinding.Replay) bound.unknownIds.size else 0

    fun derive(): DerivedHealth = SessionHealthDerivation.deriveFull(input)

    /** A strict read cannot answer while any coroutine's state at [seq] is unknown (E-2). */
    fun requireNoUnknown() {
        if (strict && bound.unknownIds.isNotEmpty()) throw StaleViewException("${bound.unknownIds.size} coroutines changed since $seq")
    }

    /** A strict read cannot return [id] unless it is known at [seq] (E-2). */
    fun requireKnown(id: String) {
        if (strict && bound.levelOf(id) == RebuildLevel.UNKNOWN) throw StaleViewException("$id changed since $seq")
    }
}

/**
 * Reads [session] for a follow-up read. [ingestCut] must be read before [session] was resolved (5a D-9c).
 * @throws SummariesUnavailableException, HistoryTrimmedException (database session trimmed without a checkpoint),
 *   StaleViewException (Bound miss whose transport revision moved on).
 */
internal fun readView(
    session: VizSession,
    ingestCut: IngestSnapshot,
    binding: ViewBinding,
    leakThresholdMs: Long,
): SessionView {
    val durable = session.readSummaries { it.snapshot() }
    val ingest = IngestStates.snapshot(session.sessionId).copy(watermark = ingestCut.watermark)
    val allEvents = session.store.all()
    val everyEvent = summariesSeeEveryEvent()
    val retention = retentionOf(allEvents, firstSeqEver = durable.firstSeqEver(everyEvent))
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
    fun eventsUpTo(seq: Long) = allEvents.filter { it.seq <= seq }
    return when (binding) {
        ViewBinding.Current ->
            SessionView(session, eventsUpTo(last), binding, last, BoundSummaries.exact(durable),
                base.copy(transitionsFor = RetainedTransitions(allEvents, upToSeq = last)::of))
        is ViewBinding.Replay -> {
            val cursor = minOf(binding.atSeq, last)
            val bound = SummaryBinding.bindAt(durable, allEvents, cursor)
            SessionView(session, eventsUpTo(cursor), binding, cursor, bound,
                base.copy(summaries = bound.view, mode = HealthMode.Replay(cursor),
                    historyIncomplete = retention.trimmed && durable.untrackedCount > 0))
        }
        is ViewBinding.Bound -> {
            val s = minOf(binding.asOfSeq, last)
            val cut = HealthCuts.find(session.sessionId, s)
            if (cut != null) {
                SessionView(session, eventsUpTo(s), binding, s, BoundSummaries.exact(cut.summaries),
                    base.copy(summaries = cut.summaries, watermark = cut.watermark, lastIngestEpochMs = cut.lastIngestEpochMs,
                        transport = cut.transport, retention = cut.retention,
                        transitionsFor = RetainedTransitions(allEvents, upToSeq = s)::of))
            } else {
                if (binding.transportRevision != null && binding.transportRevision != transport.revision) {
                    throw StaleViewException("the transport changed since the health")
                }
                val bound = SummaryBinding.bindAt(durable, allEvents, s)
                val watermark = binding.watermarkNanos?.let { w ->
                    ingest.watermark?.takeIf { it.appNanos == w } ?: WatermarkObservation(w, appEpochMs = 0, receivedEpochMs = 0)
                }
                SessionView(session, eventsUpTo(s), binding, s, bound,
                    base.copy(summaries = bound.view, watermark = watermark,
                        historyIncomplete = s < last && retention.trimmed && durable.untrackedCount > 0,
                        transitionsFor = RetainedTransitions(allEvents, upToSeq = s)::of))
            }
        }
    }
}
```

A Bound miss without `asOfWatermark` derives with no watermark: possibly blocked is not evaluated, as the health it is
bound to said. **Replay health:** `readSessionHealth`'s `atSeq` branch switches to `SummaryBinding.bindAt(...).view`
instead of the plain `SummaryFold.foldTo` (E-3); its existing replay tests must stay green (they describe states the
overlay keeps or improves); add one where a creation-trimmed running coroutine unchanged since the cursor reads as its
durable state at `?atSeq=last`, not `CREATED`.

Tests for `SessionViewReadsTest` (memory mode, sessions built directly, a fake `IngestSnapshot`):
- `Current` → `view.bound.unknownIds` empty, `view.events` all ≤ `view.seq`.
- `Bound(s)` with a cut kept for `s` → answers from the cut even after more events and a trim (summaries equal the cut's).
- `Bound(s < last)` without a cut, trimmed store (`SessionManager.configure(maxEventsPerSession = 5)`), an UNKNOWN
  coroutine → `requireNoUnknown()` throws `StaleViewException`.
- `Bound` miss with `asOfTransport` ≠ current revision → `StaleViewException`.
- `Replay(n)` → mode `HealthMode.Replay(n)`, `requireNoUnknown()` does not throw, `unknownAtSeq` = the unknown count.
- **Sustained writer** (round 1 #10): a coroutine sends events every 5 ms from a background job into a trimmed session
  (`maxEventsPerSession = 50`); 20 times in a row `GET …/health` then `…/rounds?asOfSeq=<its asOfSeq>` →
  every rounds call is 200 (placed in `SessionRoundsRouteTest` once the route exists, Task 6).

- [ ] **Step 5: Run** the tests → PASS. **Step 6: Commit** `feat(health): read binding (atSeq, asOfSeq), kept health cuts and STALE`.

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
   `asOfSeq` from `GET …/health`, send one event that changes that root (a suspension), then `HealthCuts.clear()`
   (a miss: the cut expired) → `GET …/rounds?asOfSeq=<old>` → `409`, code `stale`. With the cut kept → `200` with the
   root's state at the old seq. In replay (`?atSeq=<old>`) after the clear → `200`, `unknownAtSeq == 1`, the root's
   round has `unknownMembers == 1`.
7b. The sustained-writer test of Task 5.
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
    /** Replay only: members whose state at the cursor is not known (E-3); their counted state is a guess. */
    val unknownMembers: Int = 0,
)

@Serializable
data class RoundsPage(val asOfSeq: Long, val total: Int, val items: List<RoundEntry>, val nextCursor: String?, val unknownAtSeq: Int)

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
            val unknownMembers = membersByAnchor[r.anchorId].orEmpty().count { it.id in view.bound.unknownIds }
            RoundEntry(r.roundId, r.anchorId, r.anchorName, r.anchorOrigin, r.anchorCreatedNanos, r.anchorCreatedSeq,
                r.inProgress, r.countsByState, r.memberCount, kinds.isNotEmpty(), kinds, matches?.let { Capped.of(it) }, unknownMembers)
        }.sortedBy(::roundKey)
    val page = Keyset.page(entries, ::roundKey, params["cursor"], limit)
    return RoundsPage(view.seq, entries.size, page.items, page.nextCursor, view.unknownAtSeq)
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
6. Strict miss (`HealthCuts.clear()` after the health): an UNKNOWN coroutine at S → `409 stale` for every list; with the
   cut kept → 200 and the items equal the health's.

Run → fails (404 route).

- [ ] **Step 2: Implement.**

```kotlin
@Serializable
data class IncidentsPage(val list: String = "incidents", val asOfSeq: Long, val total: Int, val items: List<Incident>, val nextCursor: String?, val unknownAtSeq: Int)

@Serializable
data class RoundMembersPage(val list: String = "round-members", val asOfSeq: Long, val total: Int, val items: List<RoundMember>, val nextCursor: String?, val unknownAtSeq: Int)

@Serializable
data class ObservedAgesPage(val list: String, val asOfSeq: Long, val total: Int, val items: List<ObservedAge>, val nextCursor: String?, val unknownAtSeq: Int)
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
            respond(IncidentsPage(asOfSeq = view.seq, total = all.size, items = page.items, nextCursor = page.nextCursor, unknownAtSeq = view.unknownAtSeq))
        }
        "round-members" -> {
            val incidentId = params["incidentId"] ?: throw BadReadParameter("incidentId is required")
            val members = derived.roundMembers(incidentId)
                ?: return respond(HttpStatusCode.NotFound, ErrorResponse("No failed round $incidentId", code = "incident-not-found"))
            val page = Keyset.page(members, { Keyset.Key(it.role.ordinal.toLong(), it.terminalSeq, 0, it.coroutineId) }, cursor, limit)
            respond(RoundMembersPage(asOfSeq = view.seq, total = members.size, items = page.items, nextCursor = page.nextCursor, unknownAtSeq = view.unknownAtSeq))
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
1. `GET …/hierarchy`: every node has `roundId` (`round:<anchor>`), `summaryState` and `summary`; nodes keep every
   existing field (decode as `JsonObject`; assert `scopeId`, `jobId`, `state`, `children` present for normal nodes).
2. `?roundId=round:r2` → only `r2`'s subtree; `?roundId=round:none` → `[]`.
3. `?atSeq=<seq before r2's child failed>` → the child's `state` is `RUNNING` (projection string) and `summaryState`
   `ACTIVE`; nodes created later are absent.
4. One cut (round 1 #11): every node's `state` agrees with its `summaryState` (map `RUNNING`↔`ACTIVE`) and every node
   has a summary, on a session receiving events from a background job during 20 consecutive reads.
5. Memory mode, trimmed (`maxEventsPerSession = 6`), a root whose creation was trimmed: `…/hierarchy` returns it as
   `summaryOnly: true` with `state` from its summary and `scopeId == null`; after a change to it and
   `HealthCuts.clear()`, `?asOfSeq=<old>` → 409 `stale`, `?atSeq=<old>` → 200 with `partial: true`.
6. Without summaries (Task 1's store seam): `…/hierarchy` → 200, nodes with `roundId == null`, `summary == null`.
7. Timeline: `…/coroutines/c/timeline?atSeq=N` lists only events with seq ≤ N, `state` is the state at N, no
   `openPhase`, no `observedAtEpochMs`; a second read 1.1 s later is identical (round 1 #13). For the trimmed root:
   `?atSeq=last` → 200, `eventsTrimmed: true`, `totalDuration`/`activeDuration`/`suspendedDuration` null, its retained
   events listed; `?asOfSeq=<old>` with it UNKNOWN (cut cleared) → 409. Without params: unchanged (today's tests green).

Run → fails.

- [ ] **Step 2: Implement.**

```kotlin
/** A hierarchy node (spec C-3): the projection's fields, the derived round and the C-2 summary (plan E-6). */
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
    /** `summary.state`, which is computed and not serialized with the summary. */
    val summaryState: CoroutineState?,
    /** Built from the summary only: the events that created it are not retained. */
    val summaryOnly: Boolean = false,
    /** Replay only: this coroutine's state at the cursor is not known (plan E-3); open it from its summary. */
    val partial: Boolean = false,
)
```

(Check `HierarchyNode`'s field list and copy it exactly; the DTO only makes `scopeId`, `jobId` nullable.)

```kotlin
/**
 * A throwaway in-memory session fed [events] (in seq order, already cut at the view's seq): its projection and timeline
 * are those of that moment. [synthesized] are creations made up from summaries, for coroutines whose creation is not
 * retained (E-7); they are merged in seq order.
 */
internal fun <T> projectionOf(sessionId: String, events: List<VizEvent>, synthesized: List<VizEvent> = emptyList(), read: (VizSession) -> T): T {
    val all = (events + synthesized).sortedBy { it.seq }
    val capacity = maxOf(1, all.size)
    val probe = VizSession("$sessionId#cut", maxEvents = capacity, eventStoreFactory = { EventStore(capacity) })
    try {
        all.forEach(probe.store::record)
        probe.projectionService.rebuildFrom(all)
        return read(probe)
    } finally {
        probe.close()
    }
}

internal fun hierarchyOf(view: SessionView, scopeId: String?, roundId: String?): List<HierarchyNodeDto> {
    val summaries = view.input.summaries
    val anchoring = RoundAnchoring.of(summaries)
    val inRound = { id: String -> roundId == null || anchoring.roundIdOf(id) == roundId }
    val nodes = projectionOf(view.session.sessionId, view.events) { it.projectionService.getHierarchyTree(scopeId) }
    val known = nodes.mapTo(HashSet()) { it.id }
    val full = nodes.filter { inRound(it.id) }.map { node ->
        view.requireKnown(node.id)
        node.toDto(summaries.get(node.id), anchoring.roundIdOf(node.id), partial = view.isPartial(node.id))
    }
    val summaryOnly = if (scopeId != null) emptyList() else summaries.all
        .filter { it.id !in known && inRound(it.id) }
        .map { s -> view.requireKnown(s.id); s.toSummaryOnlyDto(anchoring.roundIdOf(s.id), partial = view.isPartial(s.id)) }
    return (full + summaryOnly).sortedBy { it.createdAtNanos }
}
```

`SessionView.isPartial(id)` = Replay and `bound.levelOf(id) == UNKNOWN`. A node rebuilt from retained events has its
creation retained, so `requireKnown` never throws for it — kept for clarity. `toSummaryOnlyDto`: `name = s.name ?: s.id`,
`state = projectionState(s.state)` (`ACTIVE` → `"RUNNING"`, otherwise the enum name, matching `ProjectionService`'s
strings), `createdAtNanos = s.firstObserved.appNanos`, `completedAtNanos = s.terminal?.appNanos`,
`currentThreadId/Name = s.lastThread` (null when terminal), `dispatcherName = s.dispatcher`,
`exceptionType/Message = s.failure`, `creationPoint = s.creationLocation`, `lastSuspensionPoint = s.lastSuspension`,
empty children lists, `summaryOnly = true`.

Hierarchy route: parse the binding, keep `scopeId`, add `roundId`, wrap in `respondReadErrors`, respond
`hierarchyOf(view, scopeId, roundId)`. For `ViewBinding.Current` only, a `SummariesUnavailableException` or
`HistoryTrimmedException` falls back to the projection nodes of `projectionOf(sessionId, session.store.all())` with
`roundId`, `summary`, `summaryState` null (the hierarchy is not a health read; E-6).

Timeline route: `ViewBinding.Current` → today's code path, unchanged. Otherwise:

```kotlin
val view = readView(session, ingest, binding, call.leakThresholdMs())
view.requireKnown(coroutineId)
val summary = view.input.summaries.get(coroutineId)
val creationRetained = view.events.any { it is CoroutineCreated && it.coroutineId == coroutineId }
val synthesized = if (!creationRetained && summary != null) listOf(summary.synthesizedCreation(view.session.sessionId)) else emptyList()
val timeline = projectionOf(sessionId, view.events, synthesized) { it.projectionService.getCoroutineTimeline(coroutineId) }
    ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse("Coroutine not found"))
// The moment of the view, not now (E-7): no open phase, no observation time to extrapolate from.
val atMoment = timeline.copy(openPhase = null, openPhaseSinceNanos = null, observedAtEpochMs = null,
    state = summary?.let { projectionState(it.state) } ?: timeline.state)
val body = appJson.encodeToJsonElement(CoroutineTimeline.serializer(), if (synthesized.isEmpty()) atMoment
    else atMoment.copy(totalDuration = null, activeDuration = null, suspendedDuration = null)).jsonObject
call.respond(HttpStatusCode.OK, JsonObject(body + ("eventsTrimmed" to JsonPrimitive(synthesized.isNotEmpty()))))
```

`synthesizedCreation`: a `CoroutineCreated` with `seq = firstObserved.seq`, `tsNanos = firstObserved.appNanos`,
`coroutineId`, `parentCoroutineId`, `label = name`, `creationPoint = creationLocation`, `origin`,
`createdAtEpochMs`, `jobId = ""`, `scopeId = ""`. It is used only inside the throwaway session; the timeline's event list
filters it out (drop the entry whose `seq` equals `firstObserved.seq` and kind is created when `eventsTrimmed`). The
current-timeline path also gains `"eventsTrimmed": false`? No — leave it byte-identical; document the field as present
on bound and replay timelines only.

- [ ] **Step 3: Run** the new test and the existing `SessionRoutesTest`, `TimelineRouteAssembledTest` → PASS.
- [ ] **Step 4: Commit** `feat(health): hierarchy with rounds and summaries from one cut, hierarchy and timeline at a seq`.

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
`coroutines/{c}/timeline?atSeq=…`), and, with the server restarted as `SESSION_MAX_EVENTS=20`, an attached session whose
long-lived root's creation is trimmed: `rounds?asOfSeq=<from health>` → 200 (kept cut), and the 409 path once the cut is
gone (wait 61 s, after a change to the root). Stop the server; confirm nothing
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
applies events[0, k), is turned into a state, JSON round-tripped (`appJson.encodeToString`/`decodeFromString` with
`SummaryTableState.serializer()`), restored, and applies events[k, n). Assert `A.toState() == B.toState()` (summaries as
lists in order, and the five scalar fields). Include histories that exceed a small reduction target and capacity: build
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

### Task 11: Core — `VizSession` rehydrates from a cut and a checkpoint; one-cut reads

**Files:**
- Modify: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt`
- Test: `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/session/VizSessionCheckpointTest.kt`

The store reads the events and the checkpoint itself (E-12's retry needs both before deciding), so the session takes
them as arguments.

- [ ] **Step 1: Failing tests:**
1. Session A (keepSummaries) fed 20 events. Session B whose store holds only events 11..20, then
   `B.rehydrateFrom(events = store.all(), seed = stateOfAFirst10)` → `B.readSummaries { it.toState() } == A.readSummaries { it.toState() }`,
   `B.summarySeedSeq == 10`, `B.lastSeq == 20`, B's hierarchy has the nodes of events 11..20.
2. A seed covering events still passed in (seed at 15, events 11..20): events ≤ 15 are not applied to the summaries
   twice (same equality); they still rebuild snapshot and projections.
3. `rehydrateFrom(events, seed = null)` behaves as `rehydrateFromStore()`; `summarySeedSeq == null`.
4. A seed newer than the newest event (`require` fails: the caller must retry, E-12).
5. `readCut { table -> … }` runs under the send lock (a concurrent `send` from another thread blocks until it returns —
   use a latch) and passes `null` when summaries are unavailable.

- [ ] **Step 2: Implement.** Make `summaryTable` a `private var` (still guarded by `sendLock`). Add:

```kotlin
    /** The last seq of the checkpoint the summaries were restored from, `null` when they were folded from the events alone. */
    @Volatile
    var summarySeedSeq: Long? = null
        private set

    /**
     * Rebuilds the read models from [events] (in seq order) and, when [seed] is given, the summaries from that checkpoint
     * plus the events after it (spec C-4). Events up to the checkpoint rebuild the snapshot and projections only. The
     * caller reads [events] and [seed] as one cut: [seed] must not be newer than the newest event.
     */
    fun rehydrateFrom(events: List<VizEvent>, seed: SummaryTableState?) {
        val newest = events.lastOrNull()?.seq ?: 0L
        require(seed == null || seed.lastEventSeq <= newest || events.isEmpty() && seed.lastEventSeq == 0L) {
            "checkpoint at ${seed?.lastEventSeq} is newer than the newest event $newest"
        }
        synchronized(sendLock) {
            if (seed != null && summaryTable != null) {
                summaryTable = SummaryTable.restore(seed)
                summarySeedSeq = seed.lastEventSeq
            }
            val after = summarySeedSeq ?: Long.MIN_VALUE
            events.forEach {
                applier.apply(it)
                if (it.seq > after) applyToSummaries(it)
            }
            if (newest > lastSentSeq) {
                lastSentSeq = newest
                seqGenerator.set(newest)
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

`rehydrateFromStore()` becomes `rehydrateFrom(store.all(), seed = null)` (empty store: nothing changes, as today).
(A trim always keeps the newest events, so a checkpoint with an empty event list does not occur; the `require` allows
only the trivial empty case.)

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

4. **Failure after a valid checkpoint** (round 1 #5): a first trim writes a checkpoint; store one event whose payload
   does not decode (insert a row with payload `"{}"`) among the next batch; the second `cleanup()` clears the checkpoint
   (`summaries` and `summaries_seq` null) and trims; `GET …/health` → `503 history-trimmed`.
5. Existing `DbRetentionPolicyTest` fixtures store `"{}"` payloads: they decode-fail, so their sessions get no
   checkpoint and are trimmed exactly as before (their assertions stay green; round 1 #18).
6. **Append/trim interleaving** (round 1 #4): a test seam `ExposedSessionStore.afterEventsReadForTest` runs, once,
   between reading the events and reading the checkpoint; it appends 5 events and runs `cleanup()` (writing a newer
   checkpoint). The read retries: the rebuilt session's summaries equal a memory table fed every event, its
   `lastSeq` = the newest seq, and its hierarchy has the nodes of the retained events (projection and summaries agree).
7. Summaries **and** watermark together (round 1 #20): ingest events and a watermark through the socket, trim, build a
   fresh session → `GET …/health` has the failed round intact and `asOf.kind == WATERMARK` (connected).

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
        val written =
            try {
                val current = read(sessionId)
                val after = current?.lastEventSeq ?: 0L
                val rows = EventsTable.selectAll()
                    .where { (EventsTable.sessionId eq sessionId) and (EventsTable.seq greater after) and (EventsTable.seq less keepFromSeq) }
                    .orderBy(EventsTable.seq to SortOrder.ASC)
                    .toList()
                when {
                    rows.isEmpty() -> current != null
                    // Trimmed before checkpoints existed: folding what is left would lose ancestry.
                    current == null && rows.first()[EventsTable.seq] > 1 -> false
                    else -> {
                        val table = current?.let(SummaryTable::restore) ?: SummaryTable()
                        rows.forEach { table.apply(decodeEvent(it[EventsTable.payload])) }
                        write(sessionId, table.toState())
                        true
                    }
                }
            } catch (e: RuntimeException) {
                // SerializationException is a RuntimeException too: a payload that does not decode lands here.
                logger.error("Summary checkpoint of session {} failed; its health becomes unavailable", sessionId, e)
                false
            }
        // The events below keepFromSeq are deleted next: a checkpoint that no longer covers them must not stay, or a read
        // would take the gap for complete history (E-12, H-3).
        if (!written) clear(sessionId)
        return written
    }
}
```

(`decodeEvent` = the `ExposedEventStore` decoder; make its `decode` an `internal` top-level function both use.) A
failed checkpoint must not leave a half-written row: the update is one statement after the fold succeeded.

`write` updates `summaries`/`summaries_seq`; `clear` sets both to null; `read` decodes, and a checkpoint that does not
decode reads as none (logged). `DbRetentionPolicy.trimEvents`: before the `deleteWhere`, call
`SummaryCheckpoints.foldBelow(sessionId, keepCutoffSeq)` (same transaction).

`ExposedSessionStore.buildSession` (E-12's cut):

```kotlin
    private fun rehydrate(session: VizSession) {
        repeat(CUT_ATTEMPTS) {
            val events = ExposedEventStore(db, session.sessionId).all()
            afterEventsReadForTest?.invoke(session.sessionId)
            val seed = transaction(db) { SummaryCheckpoints.read(session.sessionId) }
            if (seed == null || seed.lastEventSeq <= (events.lastOrNull()?.seq ?: 0L)) {
                session.rehydrateFrom(events, seed)
                return
            }
            // A trim committed between the two reads: read again, now after it.
        }
        throw SummariesUnavailableException("session ${session.sessionId}: no consistent cut after $CUT_ATTEMPTS attempts")
    }
```

(`CUT_ATTEMPTS = 3`; `afterEventsReadForTest` is an `internal @Volatile var` the test sets and clears. A throw here
surfaces from `getSession`; catch it in `getSession` and build the session without summaries instead — `keepSummaries`
on but the table marked degraded — so non-health reads still answer and health reads 503. Add
`VizSession.abandonSummaries()` (internal → public, documented) for that.)

`summariesSeeEveryEvent()` becomes `summariesSeeEveryEvent(session: VizSession)`: memory store → true; else
`session.summarySeedSeq != null`. Update the callers in `SessionHealthReads.kt` and `SessionViewReads.kt`
(`firstSeqEver` then comes from the restored table's `firstEventSeq`).

- [ ] **Step 4: Run** the new tests, `SessionHealthDatabaseModeTest`, `DbRetentionPolicyTest`, `MigrationLayoutTest`,
`SessionDeleteDatabaseModeTest`, `DemoRunDatabaseModeTest` → PASS.
- [ ] **Step 5: Commit** `feat(persistence): the trim folds events into a summary checkpoint first`.

---

### Task 13: Persisted ingest state and the "ingest open" marker (E-13)

**Files:**
- Create: `…/routes/IngestStatePersistence.kt` (interface + `PersistedIngestState`), `…/persistence/ExposedIngestStatePersistence.kt`
- Modify: `…/routes/IngestStates.kt` (hook, open connections, throttle), `…/routes/IngestRoutes.kt` (refuse when the
  open write fails), `…/persistence/ExposedSessionStore.kt` (initial state on create, `hasIngestState`),
  `…/Application.kt` (hook + startup sweep), `…/routes/SessionHealthReads.kt` (`transportOf`)
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/IngestStatePersistenceTest.kt`

`IngestStates` is at detekt's function limit for objects (11): move the persistence plumbing into a small
`internal object IngestStateWrites` (load/save/marker helpers taking the state) rather than raising the limit.

- [ ] **Step 1: Failing tests** (H2, database mode; "restart" = `IngestStates.clear()` + a new `ExposedSessionStore`
on the same `db` + `ExposedIngestStatePersistence(db).sweepOpenMarkers()` + `IngestStates.usePersistence(ExposedIngestStatePersistence(db))`):
1. App connects, sends events and a watermark at 12 s, closes with a `final` watermark → restart → `GET …/health`:
   `asOf.kind == WATERMARK`, `transport.deliveryUncertain == false` (5a's D-9b said uncertain), `GET /api/sessions/{id}`
   `lastWatermark.appNanos == 12 s`.
2. **Unclean close persisted** (round 1 #20): connects, sends a watermark, closes without `final` → restart →
   delivery-uncertain since that connection's first seq, marker null.
3. **Crash**: connection opened and a watermark received through `IngestStates` directly, no close → restart (the sweep)
   → `deliveryUncertain == true`, `sinceSeq` = that connection's first seq, revision bumped, marker null afterwards.
4. A loss reported before the restart (`lostEvents = 3`) is still 3 after it; the revision is kept.
5. A row with `ingest_state` null (set by SQL, as a pre-5b row) and stored events → still delivery-uncertain since 1.
6. Throttle: 10 watermarks within 1 s (injectable `IngestStates.clock`) write the row at most twice (open + first due).
7. **Ordered writes** (round 1 #6): a persistence fake records every write; two connections open/close and send
   watermarks from two threads (latches interleave them); the recorded writes' revisions never decrease and the last
   write equals the final in-memory state; after both closed the last write's marker is null, and while one is still
   open no write has a null marker.
8. **Open write fails** (round 1 #7): the fake throws on the first write → the socket is closed with `TRY_AGAIN_LATER`
   before any event is stored (`GET …/events` is empty); a later connection succeeds.
9. **Close write fails**: the fake throws on the close write → the row keeps its marker (the earlier open write) → restart
   → delivery-uncertain.
10. Memory mode (no hook): unchanged (5a's `IngestStatesTest` stays green).

- [ ] **Step 2: Implement.**

```kotlin
/** Where `IngestStates` keeps what must survive a restart (database mode only; spec C-3 marker, C-4). */
interface IngestStatePersistence {
    fun load(sessionId: String): PersistedIngestState?

    /** Writes the state and the "ingest open" marker (the earliest open connection's first seq, null when none) in one statement. */
    fun save(sessionId: String, state: PersistedIngestState, openSince: Long?)
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
- `@Volatile private var persistence: IngestStatePersistence?`; `fun usePersistence(p)`; `internal var clock`;
  `clear()` resets both.
- `State` gains `val openFirstSeqs = mutableListOf<Long>()`, `var lastSavedAtMs = Long.MIN_VALUE`; `stateOf` seeds a new
  state from `persistence?.load(id)`.
- Every write happens **inside `synchronized(state)`**, right after the change it records, so writes are in state order
  (a DB write every 5 s per session under that lock; ingest in database mode already writes each event to the DB).
- `connectionOpened`: add the first seq, then `save(id, state, openSince = openFirstSeqs.min())`, **letting a failure
  throw** (after removing the first seq again); the ingest route catches it and closes the socket with
  `CloseReason(TRY_AGAIN_LATER, "Ingest state could not be stored")` before reading any frame.
- `eventReceived`/`watermarkReceived`/`frameSkipped`: after the update, save when ≥ 5,000 ms (by `clock`) since the last
  save; a failure is logged and skipped (the marker written at open is still set, so a crash reads uncertain).
- `connectionClosed`: mark (as today), remove the first seq, then `save(id, state, openSince = openFirstSeqs.minOrNull())`;
  a failure is logged (the old marker stays: uncertain after a restart, never wrongly clean).
- `snapshot(id)`: the in-memory state when present, else `persistence?.load(id)` (not cached), else empty.

`ExposedIngestStatePersistence(db)`: `load` reads `ingest_state` (null → null; a value that does not decode → an empty
state with `deliveryUncertain = true` since 1, logged); `save` = one `UPDATE sessions SET ingest_state = ?,
ingest_open_since = ? WHERE id = ?`; `sweepOpenMarkers()` updates, per row with `ingest_open_since` not null, the state
to `deliveryUncertain = true`, `sinceSeq = min(sinceSeq, marker)`, `revision + 1` (when that changed) and the marker to
null, in one statement per row. `Application.kt`: right after `SessionManager.useStore(ExposedSessionStore(db, …))`,
`ExposedIngestStatePersistence(db).also { it.sweepOpenMarkers(); IngestStates.usePersistence(it) }`.

`ExposedSessionStore.createSession` writes `ingest_state = "{}"`; `hasIngestState(sessionId)` says whether the column is
set. `transportOf`: the D-9b branch (`recovered || eventsBeforeWatching`) applies only when the store reports no ingest
state for the row; for rows with a state only `eventsBeforeWatching` (events stored and no connection ever recorded).

- [ ] **Step 3: Run** the new test, `IngestStatesTest`, `IngestWatermarkTest`, `IngestRoutesTest`,
`SessionHealthDatabaseModeTest`, `SessionDeleteDatabaseModeTest` → PASS. **Step 4: Commit**
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
- Modify: core `…/session/MetricsProjection.kt` (per-origin aggregates), core `…/health/SessionHealthDerivation.kt`
  (`longLivedByOrigin`), `…/routes/SessionRoutes.kt` (metrics handler), `…/routes/RouteDtos.kt`
- Test: core `MetricsProjectionByOriginTest.kt`, extend `DeriveFullTest`; backend extend `MetricsRouteTest.kt`

- [ ] **Step 1: Failing tests.**
Core: a projection fed an `APP` root with 2 children on `Dispatchers.Default`, a `VIZCORE` coroutine on `Dispatchers.IO`
and an origin-less one, some completing → `byOrigin()` gives per bucket (`APP`, `VIZCORE`, `UNREPORTED`) active, peak
(the bucket's own peak, not a share of the total), throughput over the same 10 s window (events of that bucket's
coroutines; events not about a coroutine count only in the total) and dispatcher utilization; `rebuildFrom` reproduces it.
Backend: an attached session with an `APP` and a `VIZCORE` coroutine both created at 1 s and suspended, last event at
45 s, idle → `GET …/metrics?leakThresholdMs=30000`: `byOrigin.app.captured == 1`, `byOrigin.vizcore.captured == 1`, both
`active == 1`, `leaks` two entries with `origin` `APP`/`VIZCORE` and `aliveMs == 44_000` (frozen at the last event), the
same on a second call 1.1 s later; `leaksAvailable == true`, `retainedOnly == false`. A demo session → everything under
`byOrigin.app`. With unavailable summaries (Task 1's store seam): `leaks == []`, `leaksAvailable == false`,
`byOrigin == null`. A database session trimmed with a checkpoint → `retainedOnly == true`, `oldestRetainedAppNanos` set.
Existing threshold-echo tests stay green.

- [ ] **Step 2: Implement.**

Core `MetricsProjection`: keep, beside today's totals, `enum class OriginBucket { APP, VIZCORE, UNREPORTED }` aggregates:
an `originOf` map filled on `CoroutineCreated` (`APP`/`VIZCORE`, anything else `UNREPORTED`), per bucket the active
set's count, a peak, a 10 s event-time window (an event counts in its coroutine's bucket when it is a `CoroutineEvent`
of a known coroutine) and dispatcher counts; `rebuildFrom` clears them. New public API (no change to `MetricsSnapshot`,
which plugin code may construct):

```kotlin
data class OriginAggregate(val active: Int, val peak: Int, val throughputPerSec: Double, val dispatcherUtilization: Map<String, Int>)

fun byOrigin(nowNanos: Long = System.nanoTime()): Map<OriginBucket, OriginAggregate>
```

Core derivation: `fun longLivedByOrigin(input: HealthInput): List<Pair<CoroutineOrigin, ObservedAge>>` — the long-lived
rule of `HealthParts` over **all** coroutines (vizcore included) with the effective origin.

`RouteDtos`: `LeakDto` gains `val origin: CoroutineOrigin? = null`; `MetricsResponse` gains
`val leaksAvailable: Boolean = true`, `val byOrigin: OriginMetrics? = null`, `val retainedOnly: Boolean = false`,
`val oldestRetainedAppNanos: Long? = null`, with

```kotlin
@Serializable
data class OriginSlice(
    val captured: Int,
    val active: Int,
    val peak: Int,
    val throughputPerSec: Double,
    val dispatcherUtilization: Map<String, Int>,
    val longLived: Int,
)

@Serializable
data class OriginMetrics(val app: OriginSlice, val vizcore: OriginSlice, val unknown: OriginSlice, val untracked: Long)
```

Effective origin mapping (the derivation's): a demo puts every bucket's figures in `app` (total figures); a session whose
origin is `UNKNOWN` puts the totals in `unknown`; otherwise `APP` → `app`, `VIZCORE` → `vizcore`, `UNREPORTED` → `unknown`.
`captured` and `longLived` come from the summaries (`derived.originOf`), the rest from the projection buckets. The handler
reads `readView(session, ingest, ViewBinding.Current, leakThresholdMs)`; on `SummariesUnavailableException` /
`HistoryTrimmedException` it answers today's totals with `leaks = []`, `leaksAvailable = false`, `byOrigin = null`.
`retainedOnly` = a database session (`!summariesSeeEveryEvent` for the projection: the projection of a database session
is rebuilt from retained events) whose `retention.trimmed`; `oldestRetainedAppNanos` = `retention.oldestRetainedAppNanos`
then.

- [ ] **Step 3: Run** → PASS. **Step 4: Commit** `feat(metrics): split by origin, leak ages frozen at the last observation`.

---

### Task 16: Shares under one cut and the shared reads (E-16, C-6)

**Files:**
- Modify: `…/share/ShareRoutes.kt`, `…/share/ShareDtos.kt`, `…/share/ShareService.kt` (`resolve(token, countAccess)`),
  `…/routes/SessionRoutes.kt` (`snapshotResponseOf` from a cut), `…/routes/SessionHealthReads.kt` (health from a cut),
  route bodies extracted into functions taking `(call, session, ingestCut)` so session and shared routes share them
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/share/SharedHealthRoutesTest.kt` (H2, auth off)

- [ ] **Step 1: Failing tests** (a failing session shared with `POST …/share`):
1. `GET /api/shared/{token}` → `health.failedRoundCount == 1`, `health.asOfSeq == events.last().seq`,
   `session.eventCount == events.size`, `checks.lastRun == null`; after `POST /api/validate/session/{id}`, `checks.lastRun`
   is set and its `checkedSeq ≤ health.asOfSeq`.
2. **Watermark order** (round 1 #8): a test seam `ShareRoutes.afterIngestReadForTest` runs between the ingest snapshot and
   the session resolution; in it, the app's running coroutine suspends (event) and a newer watermark arrives. The payload's
   health has **no** possibly blocked incident for it (its watermark is the one read first, older than the suspension's
   poll), and `session.lastWatermark` equals `health.asOfWatermark`.
3. **Checks order**: a seam between the checks read and the cut runs a new validation after more events; the payload's
   `checks.lastRun.checkedSeq ≤ health.asOfSeq` (the older run is served).
4. **Cut**: a seam inside the cut appends events after it (another thread blocked on the send lock until the cut returns,
   via a latch) → events, `session.eventCount`, `session.coroutineCount` and `health.asOfSeq` all describe the cut, not
   the later events.
5. `GET /api/shared/{token}/health?atSeq=N`, `/health/items?list=incidents&asOfSeq=S`, `/rounds?asOfSeq=S`,
   `/hierarchy?roundId=…&asOfSeq=S`, `/coroutines/{c}/timeline?asOfSeq=S` (S from the payload) → 200, bodies equal to
   the session routes' for the same parameters; with the payload's cut kept they stay 200 after more events arrive;
   after `HealthCuts.clear()` and a change to a creation-trimmed coroutine → 409 `stale` (shared STALE, round 1 #20).
   Expired share → 410; unknown → 404; `access_count` grows only with payload loads.
6. A legacy-trimmed session (events deleted by SQL, no checkpoint) → payload `health == null`,
   `healthUnavailable == "history-trimmed"`.

- [ ] **Step 2: Implement.**
- `ShareService.resolve(token: String, countAccess: Boolean = true)`.
- `SharedSessionResponse` gains `val health: SessionHealth? = null`, `val healthUnavailable: String? = null`,
  `val checks: ChecksStatus? = null`.
- Payload load, in this order: `ingest = IngestStates.snapshot(id)`; `checks = ChecksResults.load(id)`; resolve the
  session; `cut = session.readCut { table -> Cut(table?.snapshot(), session.snapshot.nodes(), session.lastSeq) }`;
  `events = session.store.all().filter { it.seq <= cut.seq }`. `snapshotResponseOf(session, events, nodes = cut.nodes,
  durable = cut.summaries, ingest = ingest)` (new optional parameters; the session route keeps calling it as today, and
  `readSessionFacts` takes the summaries and ingest snapshot it is given instead of reading its own when they are passed).
  Health: refactor `readSessionHealth` so its body after the summary copy is
  `healthFrom(session, durable, ingest, events, atSeq = null, leakThresholdMs)`, which also remembers the cut (E-17);
  call it with the cut's copy, the payload's ingest snapshot and events (`cut.summaries == null` or a 503 exception →
  `healthUnavailable` = the code). `checks` = `ChecksStatus(id, checks, newEventsSince = cut.seq - checkedSeq)`.
- Shared read routes in `registerSharedPublicRoute` (all under the shared rate limit): a helper
  `withSharedSession(call) { session, ingest -> … }` reads the ingest snapshot, resolves the token with
  `countAccess = false` (410/404 as the payload route), resolves the session, then calls the same extracted bodies as
  the session routes.

- [ ] **Step 3: Run** → PASS (and the existing share tests). **Step 4: Commit** `feat(share): health and checks under one cut, shared health reads`.

---

### Task 17: API docs and types (5b-2), verification, review, PR

- [ ] **Step 1:** Docs and types as Task 9 (`$SCRATCH/part5b/openapi_5b2.py`): `GET /api/validate/session/{id}`
(`ChecksStatus`, `ChecksRun`, `RuleOutcome`), `MetricsResponse.byOrigin` (`OriginMetrics`, `OriginSlice`),
`LeakDto.origin`, `SharedSessionResponse.health/healthUnavailable/checks`, the five shared read paths; regenerate
`generated.ts`; `API_REFERENCE.md` (persistence notes: summaries survive the trim, ingest state survives a restart,
checks, shares). Gates as Task 9 Step 4. Commit `docs(api): checks, metrics by origin, shared health reads`.
- [ ] **Step 2: API check on 18901**, memory mode (checks, metrics by origin) **and database mode**
(`STORAGE_TYPE=database DB_URL='jdbc:h2:file:$SCRATCH/part5b-api/db/vizcore;CASE_INSENSITIVE_IDENTIFIERS=TRUE'
RETENTION_MAX_EVENTS_PER_SESSION=20 RETENTION_CLEANUP_INTERVAL_MINUTES=1`): create an attached session, ingest a failing
round and a watermark, close cleanly; wait for one retention pass (the trim, about a minute), `GET …/health` (failed
round intact, `retention.trimmed: true`); restart the server, `GET …/health` again (watermark kept, not uncertain);
share it and save `GET /api/shared/{token}` and the shared rounds/items. Outputs to `$SCRATCH/part5b-api/5b2-*.json`.
The same is proven by `SummaryCheckpointTest`, `SessionHealthDatabaseModeTest` and `IngestStatePersistenceTest`. Stop the server; confirm nothing listens on 18901.
- [ ] **Step 3: Codex code review** up to 3 rounds + one confirmation round (`codex-part5b2-code-rN.md`).
- [ ] **Step 4: Push `feat/health-api-b2`, open PR 5b-2** with base `feat/health-api-b` (stacked; retarget to `main`
after 5b-1 merges), #295's structure. Do not merge.
