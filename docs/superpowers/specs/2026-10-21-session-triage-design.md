# Session page, triage first — honest health on the shared domain

**Date:** 2026-10-21
**Status:** Design — pending review
**Target:** vizcore 1.3
**Relation to the 2026-08-23 SPA workspace redesign:** this is that spec's **sub-project 4** (shared
domain projections, D-8): problem definitions, round anchors and suspension sites move into
`coroutine-viz-core` and onto the session API, widened with what the workspace needs to answer "is
anything wrong?". It **revises D-4** (the state bar stays as the canvas filter but stops being the problem
surface), **refines D-7** (inspector order per problem kind), **supersedes** the 08-23 row "Validation →
folded into the state bar as problem chips", and keeps D-1, D-5, D-6 and sub-project 3's "no tabs in the
workspace" outcome. Round *presentation* (the plugin's collapse economy in `RoundGrouping`/`RoundTreeModel`)
stays in each client; the plugin adopts the shared derivation in sub-project 5.
**Sketch:** design canvas "vizcore session page — triage-first sketch" (three boards: a request failed,
a coroutine may be blocking a thread, no app coroutines captured). Boards 1 and 3 use real data from two
flowwave sessions; board 2 is reconstructed from a UAT finding and uses placeholders. The sketch shows
evidence as tabs; this spec keeps sections (T-5).

## Problem

The deep-dive UAT of the 1.2.0 release with a real Spring Boot app (flowwave, 2026-10-20/21) showed that
the workspace has the answers but does not lead with them:

1. **The page is ~10,800 px tall.** A static teaching panel ("Structured Concurrency Behavior") sits above
   the data; the Threads evidence section alone is ~8,000 px of raw assignment rows.
2. **The problem signals are mostly false alarms on a real app.** The state bar's "Potential leaks 3" were
   vizcore's own connection coroutines (`cio-from-nio-reader`, `cio-to-nio-writer`, `nonce-generator`,
   the leftovers documented in #230). The leak rule is age-based, so servers, streams and consumers that
   live long by design also count.
3. **The page claims more than vizcore observes.** "Live" means the browser's SSE stream, not the app's
   connection (#237). Leak ages keep growing after the app disconnects. Thread times are monotonic
   nanoseconds rendered as clock times. Nothing says that 150 ms sampling can miss short coroutines.
4. **The real bug of the UAT was invisible.** A blocking Kafka `.get()` resumed on Lettuce's Redis
   event-loop thread by `Dispatchers.Unconfined` froze Redis for 60 s. No view pointed at it.
5. **The same domain is still computed twice** (D-8): the plugin's `ProblemDerivation` and the SPA's
   state-bar/leak logic disagree on what a problem is.

## Goal

Within five seconds of opening a session, a developer debugging their own app sees whether a problem was
**detected in what vizcore captured**, and reaches it in one click. The page never claims more than
vizcore observed, and the definition of a problem lives once, in `coroutine-viz-core`.

### Non-goals

- No change to the IA of sub-project 1 (sessions home, demo and compare as actions).
- No new visualization internals: `CoroutineTreeGraph` (as changed by #246), `CoroutineTree`,
  `CoroutineTimelineView` and `ThreadLanesView` are re-hosted, not rewritten.
- No root naming (#217) and no terminal-event ordering fix (#244); the page degrades gracefully without
  them (see "Dependencies").
- No live "running for N s" counter and no running stack (listed under "Later").
- No frozen share snapshots: a share link keeps reading the live session, as today.
- No plugin UI work (sub-project 5).

## Honesty rules (apply to every surface)

- **H-1 Observed, not inferred.** Text states what was captured ("last observed", "after the failure",
  "no suspension observed") and never a cause the data does not record.
- **H-2 Always say "as of".** Every health statement names its observation time (see `asOf` in C-3).
- **H-3 Unknown is shown as unknown.** A fact never captured reads "unavailable"; a signal that cannot be
  evaluated reads "not evaluated" with the reason. Neither ever counts as healthy.
- **H-4 Sampling is named.** Wherever the page says nothing was detected, it says it is about the
  captured coroutines, and the sampling interval is explained where a user first meets an empty result.

## Definitions (owned by `coroutine-viz-core`, D-8)

**Origin.** `APP`, `VIZCORE` or `UNKNOWN`.
- #230 already drops the coroutines vizcore itself launches before they are diffed; that stays.
- The leftovers #230 could not drop (`cio-from-nio-reader`, `cio-to-nio-writer`, `cio-request-timeout`,
  `nonce-generator`) have no parent job and run on `Dispatchers.IO` / `Dispatchers.Default`, so neither
  the dispatcher nor the job tree identifies them. The only candidate signal is the creation stack: the SDK
  tags a coroutine `VIZCORE` when its creation stack contains a frame of the SDK client package
  (`com.jh.proj.coroutineviz.client`). The public wrapper API (`com.jh.proj.coroutineviz.wrappers`, e.g.
  `VizScope.vizLaunch`) is not part of that rule, so app coroutines launched through it stay `APP`.
- A coroutine launched through the wrappers is captured once: in an attached session the wrapper events own
  it, and the DebugProbes path skips any coroutine whose context carries `VizCoroutineElement`, so it never
  also appears under a `dp-` id. PR 3
  starts by verifying this on the leftovers; if a leftover's creation stack has no SDK frame, it stays
  `APP`. That is acceptable because long-lived is an observation, not a problem: such leftovers no longer
  raise a false problem, they only appear in "Long-lived (n)".
- The SDK already installs DebugProbes with `enableCreationStackTraces = true` (`DebugProbesInstall`); when
  creation stacks are nevertheless unavailable (for example IntelliJ's debugger installs DebugProbes
  without them first, see #217) or the SDK predates origin support, the session's origin is `UNKNOWN`: every coroutine is
  `UNKNOWN`, nothing is hidden, and the page says "origin not available" next to counts.
- `VIZCORE` coroutines are excluded from app counts, problems and observations and appear only when the
  "vizcore connection (n)" toggle is on; their failures are shown as "vizcore connection" health. Both
  cover only the captured leftovers: coroutines #230 drops before diffing are never captured, and the
  wording says "captured" ("vizcore connection: 3 captured, no failed coroutines captured").

**Rounds.** A round is an anchor plus its subtree, anchored as in the plugin's `RoundGrouping`: each
genuine root (no parent), and, for a container root (never completes, at least 3 direct children, at
least half of their subtrees completed), each direct child, with the container itself a singleton round.
Anchoring moves into core unchanged (structural signals only, no name or time heuristics). Round ids are
**derived**, never stored: `SessionHealthDerivation` computes them from the current summaries (live) or
from the replay fold, so a root that becomes a container re-keys its descendants consistently.
Promotion is **sticky while the root runs**: the first time a running root qualifies, its summary records
`promotedAtSeq`, and new active children never demote it. When the root itself reaches a terminal state,
the promotion is cleared **unless a direct child was created after `promotedAtSeq`**. An ordinary request
whose three or more children finish before it (children always finish before their parent) qualifies
with all its children already created, so it is one round again once it finishes; a long-lived app root
keeps getting new children after promotion, so a captured shutdown or scope cancellation does not merge
its request rounds back into one. A request that launches children in waves after qualifying stays split
(accepted). The live view re-keys at most twice per root (when it qualifies and when it finishes), and the
replay fold applies the same rule at each sequence. This deliberately differs from the plugin's per-pass
recomputation, which can flip back and forth while the root runs.

**Problem kinds.** Only two kinds are problems:

| Kind | Rule | Replaces |
|---|---|---|
| `FAILED` | terminal state failed with an exception that is not a `CancellationException` (as `ProblemDerivation.isRealException`, except that an unknown type counts as a failure: nothing says it was cancelled) | plugin `EXCEPTION`; SPA "Failed" chip |
| `POSSIBLY_BLOCKED` | last observed state is running, and the time from its latest observed start/resume to the SDK's latest poll watermark (both on the app clock) is over 10 s (#232's threshold) | new; supersedes #232's frontend "may be blocked" hint |

**Observations** (shown, never counted as problems): `LONG_LIVED` (today's leaks: alive longer than the
leak threshold, default 30 s as today, ages frozen at the last observation), `LONG_SUSPENDED` (suspended
longer than 30 s, the plugin's `LONG_SUSPENDED_THRESHOLD_NANOS`, now a core constant), and cancellations
(a terminal state, shown as context of an incident).

**Incidents and the headline count.**
- **Failed round:** all `FAILED` coroutines of one round are one incident, whatever order their terminal
  events were recorded in (the count does not depend on #244). It lists its failures ("its parent failed
  too") and, as context, the cancellations in that round recorded after its first failure ("4 cancelled
  after"). No causal claim is made.
- **Ungrouped failure:** a `FAILED` coroutine whose round cannot be determined (its summary has no
  resolvable ancestry) is one incident on its own.
- **Possibly blocked:** each `POSSIBLY_BLOCKED` coroutine is one incident. A round can hold both a
  failed-round incident and possibly blocked incidents (a sibling can keep running, e.g. under a
  `SupervisorJob`); a single coroutine is never in two incidents, because a failed coroutine is terminal.
- **Headline:** "N problems detected" = failed rounds + ungrouped failures + possibly blocked. Chips per
  kind: "Failed (rounds · ungrouped)", "Possibly blocked". Observations are badges on cards and in the
  inspector, never incidents.

**Lifetimes.** Finished: first observation to terminal event. Active: first observation to the later of
the latest watermark and its own last event, labelled "so far"; without a watermark, to its last event,
labelled "at least".

**Suspension-point provenance.** `REAL_FRAME` (the SDK read a suspension frame), `CREATION_FALLBACK` (the
SDK found no usable frame and sent the creation frame, as `DebugProbesEventSynthesizer` does today),
`STALE` (set by the backend when a newer suspension arrives without a frame and the summary keeps the
older location). Only `REAL_FRAME` is shown as "last suspension point"; the others read "suspension
location unavailable".

**Event-loop clue.** When a possibly blocked coroutine's thread **in its current running phase** (its last
thread assignment's sequence is later than its latest start or resume, both stored in C-2) starts with a known event-loop prefix (core list: `lettuce-nioEventLoop-`, `lettuce-epollEventLoop-`, `reactor-http-nio-`,
`reactor-tcp-nio-`, `nioEventLoopGroup-`, `epollEventLoopGroup-`), its payload names the match. The
inspector shows it as a **Clue**, never a verdict, with conditional advice: "If the code it is running
makes a blocking call, it holds that thread … If it does block, move that call to
`withContext(Dispatchers.IO) { … }`." Without a thread assignment in the current phase there is no clue,
and "Last thread" reads "not observed for the current run".

## Decisions

**T-1 — A health strip replaces the state bar as the problem surface (revises D-4).** D-4 rejected a
problems strip because it "opens on four zeroes" for a demo. This strip never shows zero tiles: it is one
line, non-zero problem kinds are chips, everything else is a quiet sentence. The state bar keeps its
filter role inside the coroutine panel; its leak chip becomes the "Long-lived (n)" observation filter and
its validation action chip moves to the checks status (T-8).

**T-2 — Two statuses, not one "Live".** App connection (connected / disconnected, from the backend's
ingest state) and View (updating / paused / no new data / loaded at <t>) are separate pills, followed by
"Last data <t>", the start time and the rung badge (D-5/D-6). Closes #237.

**T-3 — The inspector is the only place for details.** A chip opens the problem list; "Show" switches to
the problem's round, clears incompatible filters, reveals and selects the node, and moves focus to the
inspector heading. For an incident without a resolvable round (an ungrouped failure, or a possibly blocked
coroutine whose ancestry is unknown), "Show" opens the coroutine directly in the inspector, without a
canvas selection. Order:
- Failed: new `ExceptionCard` (type, message, round effect) → `SuspendedAtCard` (start and last suspension
  location) → `TimingCard` (lifetime) → `EventsCard` → `IdentityCard`.
- Possibly blocked: `RunsOnCard` + `SuspendedAtCard` as "Observed" → new `ClueCard` (only with a clue) →
  `TimingCard` showing the transition list → `EventsCard` → `IdentityCard`.
- Anything else: D-7's order, unchanged.
- Nothing selected: the session summary (replaces today's metric tiles): app coroutines captured, app peak,
  throughput, dispatcher load, vizcore connection count, events; event-derived figures carry "in retained
  events since <t>" once trimming has happened. In shared and replay views (no `/metrics` access today)
  the counts come from the health payload, and app peak, throughput and dispatcher load are omitted.

**T-4 — One round at a time.** A round picker (newest first, with state and problem marks; problem rounds
listed first) plus "find in all rounds" applies to Graph, List and Timeline alike; List and Timeline also
offer "All rounds". "Show" switches rounds. Refs #216 (layout and scaling part).

**T-5 — Evidence stays in sections, collapsed by default (keeps the no-tabs outcome).** Each section has a
one-line summary when collapsed. Threads become a table grouped by thread name ("coroutines seen",
"assignments observed", last assignment as time into the capture), capped with "Show all". Dispatchers,
Jobs and the events drawer stay as today. Locks / Flows / Channels say "none captured" with D-5's unlock
guidance. The standalone `LeakList` is removed; long-lived coroutines are reached through the
"Long-lived (n)" filter, with ages frozen at the last observation.

**T-6 — Teaching moves behind "Learn".** `StructuredConcurrencyInfo` becomes a panel toggled from the
header, collapsed by default and remembered per browser. Its failure text is qualified: in an ordinary job
a child's failure fails the parent, which cancels the other children; under a `SupervisorJob` they keep
running. It also explains sampling (H-4).

**T-7 — Header actions.** Pause view, Replay, Learn, and "⋯" (Compare, Export, Share).

**T-8 — Instrumentation checks are not health.** Checks validate vizcore's event stream, not the app. Their
status sits beside the strip with freshness and three states: "not run", "passed at event N · M new events
since", "failed at event N · K rules failed" (opens today's checks modal). They never count towards "no
problems".

## Page

```
┌ flowwave-backend  [ATTACHED] ───────────────────────────────────────────────────┐
│ ● App connected  ⟳ View updating  Last data <t> · started <t> · 28 captured      │
│                                       [Pause view] [Replay] [Learn] [⋯]           │
├──────────────────────────────────────────────────────────────────────────────────┤
│ 1 problem detected  [✕ Failed 1]  Nothing possibly blocked · Long-lived 0 · as of <t> │
│                                   Instrumentation checks: not run [Run]          │
│   FAILED dashboard.failing-part  IllegalStateException: …  round 21:45:59 [Show] │
├───────────────────────────────────────────────┬──────────────────────────────────┤
│ Graph | List | Timeline   state bar (filter)  │ Inspector                        │
│ Round [21:45:59 · 7 · failed ▾] [find…] [□ vizcore connection (3)]               │
│ ┌ canvas (#246) ────────────────────────────┐ │  dashboard.failing-part  FAILED  │
│ │              <round anchor>               │ │  Exception · Where · Lifetime ·  │
│ │  failing-part  tasks  notifications  pets │ │  Events · Identity               │
│ └───────────────────────────────────────────┘ │                                  │
├───────────────────────────────────────────────┴──────────────────────────────────┤
│ ▸ Threads · 18 names (89 JVM threads) ▸ Dispatchers · 4 ▸ Jobs ▸ Locks · none captured … │
└──────────────────────────────────────────────────────────────────────────────────┘
```

**Health strip states.**
- Problems: "N problems detected", one chip per non-zero kind, the problem list open under the first chip.
- None, with app coroutines evaluated and both problem kinds evaluated: green "No problems detected in the
  captured coroutines · as of <t>".
- None, but possibly blocked not evaluated (older SDK, demo, replay, overflow): neutral (not green) "No
  failures detected in the captured coroutines · possibly blocked not evaluated (<reason>) · as of <t>".
- No app coroutines captured: neutral (not green) "No application coroutines captured · nothing to
  evaluate yet · as of <t>", with the sampling explanation.
- Origin `UNKNOWN`: never the "no application coroutines" state; the strip evaluates all captured
  coroutines and says "origin not available: vizcore's own connection may be included".
- Awaiting first observation (a new session with no data yet): "Waiting for the first data from the app";
  no "as of" until data exists.
- Events lost or delivery uncertain: failures read "at least N", possibly blocked is `NOT_EVALUATED`
  (reason `EVENTS_DROPPED`, because a lost suspension could make a coroutine look blocked) for the rest of
  the session, including after reconnects, no green state is shown, and the strip says "some events may
  have been lost on the way from the app; results may be incomplete".
- Possibly blocked not evaluable: the quiet line says "Possibly blocked: not evaluated (<reason>)" —
  "no poll data from this app" (no watermark: captured before 1.3 or an older SDK), "demo session", or
  "replay".
- The quiet line also carries "Long-lived (n)", "vizcore connection: no failed coroutines captured"
  (transport errors are only logged by the SDK today).

**Cards** show name, state as icon + text (never colour alone), lifetime, start location, and observation
badges. Cancelled coroutines keep the palette's cancelled look; in a failed round they read "Cancelled ·
after the failure". A root recorded Completed above a failed child (#244 not yet fixed) reads
"Completed (recorded) · a child failed".

## Modes

- **Live:** health from `GET /api/sessions/{id}/health`, refetched on new events and on watermark messages,
  throttled to once per second, so a blocked coroutine appears during quiet polls.
- **Paused view:** health and canvas freeze; the View pill says "Paused"; "as of" stays where it was.
- **Replay:** health follows the cursor via `GET …/health?atSeq=N`. The backend folds the retained events
  up to N into transient summaries with the same updater as C-2; ancestry for coroutines whose creation
  event was trimmed comes from the durable summaries (parent links never change). Only `FAILED` incidents
  and ungrouped failures, including failures known from the durable summaries whose terminal sequence is
  at or before the cursor even when their events were trimmed; "Possibly blocked: not evaluated
  (replay)". When the oldest retained event is
  later than the session start, the strip says "replay starts at <t>; earlier events were dropped, so this
  may be incomplete". "Show" lists only problems present at the cursor; when a node cannot be rebuilt, it
  opens the inspector from the coroutine's durable summary and incident details, with its retained events
  if any, else "events unavailable (trimmed)". Leaving replay returns to live.
- **Shared:** the share link reads the live session, as today. The page loads once: the View pill reads
  "Loaded at <t>" with "Reload"; health is labelled with the session's last observation at load time,
  never the load time, and every later read (rounds, hierarchy, items) is bound to that load's `asOfSeq`,
  so navigation never mixes newer data with the loaded health. Selection and the inspector work read-only; replay uses
  `GET /api/shared/{token}/health?atSeq=N`.
- **Demo sessions:** same page (D-6); coroutines are `APP`; possibly blocked is not evaluated ("demo
  session": the in-process wrappers do not observe every suspension, so elapsed time is not evidence);
  `asOf` is the last event.

## Backend, core and SDK contract

**C-1 Core health derivation.** `coroutine-viz-core` gains `SessionHealthDerivation` (pure). Inputs: the
coroutine summaries (C-2), the latest watermark (or none), the session kind (attached / demo), the leak
and long-suspended thresholds, and, for possibly blocked coroutines only, their retained transition events.
Output: `SessionHealth` (C-3). It absorbs `ProblemDerivation`'s domain (real-exception rule; ordering:
problem kind, then newest first) and `RoundGrouping`'s anchoring; presentation (age labels, collapse
economy) stays in each client. The core also defines the wire types used below (watermark message, origin
field, provenance field). Table-tested in core.

**C-2 Durable per-coroutine summary.** The session keeps, per coroutine, updated on each event by one
updater and never rebuilt from retained events: origin, parent id, name, creation location,
first observation, last transition kind with app-clock time and sequence, terminal state with time and
sequence, failure type and message, last suspension point with provenance, last thread and dispatcher with the sequence of that thread
assignment, `lastModifiedSeq` (the sequence of the last event that changed any field), and for roots
`promotedAtSeq` and whether a direct child was created after it (see Rounds).
Round ids and observations (long-lived, long-suspended) are not stored: they depend on the tree, the
request's leak threshold and the latest watermark, and are computed by `SessionHealthDerivation`.
A cumulative counter of coroutines beyond capacity is kept beside them.
- **Reduction:** a best-effort target of 50,000 full summaries per session (configurable); active
  coroutines always keep their full summary, and the capacity below is the hard limit. Over the target,
  finished non-failed
  summaries are reduced oldest first, then failed ones, to a **structural stub** (id, parent id, origin, name,
  creation location, creation time, terminal state with time and sequence, failed flag, and for roots
  `promotedAtSeq` and its growth flag; search keeps working on name and location), which keeps round anchoring, round
  order and "cancelled after the failure" correct. Once a failed summary was
  reduced, its incident shows without exception details ("details dropped").
- **Stubs are never discarded** (they are small: identity, ancestry, name, location, times, flags), so round anchoring,
  round order, incident counts and "cancelled after the failure" stay exact for every tracked coroutine.
- **Capacity:** a session tracks at most 100,000 coroutines (summaries plus stubs, configurable). Beyond
  that, new coroutines are not tracked, only counted; possibly blocked becomes `NOT_EVALUATED` (reason
  `OVERFLOW`), no green state is shown, and the strip says "N coroutines beyond capacity were not tracked:
  health covers the first M". Failure counts then read "at least N".

**C-3 Session API.**
- `GET /api/sessions/{id}` gains `startedAtEpochMs`, `captureStartAppNanos` (app-clock time of the
  session's first event, stored durably on the session and the origin of every "time into the capture"), `appConnected`, `lastIngestEpochMs`, `lastWatermark`
  (app-clock nanos and the epoch ms it was received), `oldestRetainedSeq`, `trimmed`, `origin`
  (`REPORTED` | `UNKNOWN`), `kind` (`ATTACHED` | `DEMO`).
- `GET /api/sessions/{id}/health[?atSeq=N][&leakThresholdMs=M]` returns `SessionHealth`, a bounded
  payload for the once-per-second refetch (the leak threshold parameter behaves as on `/metrics`, so the
  strip and the state bar filter agree). Every list is capped at 50 entries, newest first, with its total
  count; the rest pages through `GET …/health/items?list=&cursor=&limit=` (same `leakThresholdMs`, and
bound to the response's `asOfSeq`, see below), where `list` is
`incidents` (optionally `kind=`), `round-members` (with `incidentId`: the failed coroutines and the
cancellations after of one failed round), `long-lived` or `long-suspended`. Retained
  transitions are capped at the 20 most recent per coroutine, the gap marked:
  - `asOf`: null before the first observation, else kind `WATERMARK` | `INGEST` | `LAST_EVENT` |
    `REPLAY_CURSOR`, app-clock nanos, the app's wall-clock time of that observation when known (the
    watermark carries it), and separately the backend's receipt time; "as of" labels use the app's time,
    else time into the capture (a shared view uses the session's `WATERMARK` or `INGEST`
    at load time). For `REPLAY_CURSOR` no wall-clock time is given (events carry only monotonic time); the page
    shows the cursor as time into the capture and its event number;
  - `incidents[]`: `FAILED_ROUND` (round id, failed coroutines with type and message, cancellations after),
    `UNGROUPED_FAILURE` (coroutine, type, message), `POSSIBLY_BLOCKED` (coroutine, round id, last thread,
    dispatcher, last suspension point with provenance, retained transitions `[{kind, appNanos, seq}]`,
    `transitionsGap`, `firstObservedRunning`, `eventLoopClue` or null);
  - `possiblyBlocked`: `EVALUATED` | `NOT_EVALUATED` with reason (`NO_WATERMARK`, `DEMO`, `REPLAY`,
    `OVERFLOW`, `EVENTS_DROPPED`);
  - `observations`: long-lived and long-suspended coroutine ids with frozen ages;
  - `counts`: app, vizcore, unknown origin; `failuresAtLeast` flag;
  - `vizcoreConnection`: captured count, failed count and the first 50 failures (the "vizcore connection"
    health; also in the share response);
  - `transport`: events lost before ingest and whether delivery is uncertain (from the watermark messages,
    see C-7; the backend also sets it when an ingest connection ends without a clean close, and, because a
    crash runs no close handler, persists an "ingest open" marker per session that marks the session
    uncertain when it is still set at startup), sticky for the session across reconnects, with the sequence after which it first occurred;
  - `retention`: oldest retained seq and time, `trimmed`;
  - `asOfSeq` and `asOfWatermark`: the last event sequence and the watermark the response reflects (`atSeq`
    and no watermark in replay). Every follow-up read for that view — `health/items`, `rounds`,
    `hierarchy`, the inspector's coroutine timeline (`…/coroutines/{coroutineId}/timeline`), live or
    shared — passes both; cursors are keyset cursors on sequence. The backend answers
    from that point: items first observed later are excluded, and possibly blocked and long-lived use the
    bound watermark. When a coroutine the read needs has changed since (its summary's `lastModifiedSeq`,
    updated by any field change including thread and dispatcher assignments, is later than `asOfSeq`) and its state at `asOfSeq` can no longer be rebuilt from retained events, the read
    answers `STALE` instead of mixing states; the page then says "this view has moved on" and refetches
    health (live) or offers "Reload" (shared, replay). The view moves to newer values only on its next
    health refetch (live) or on "Reload" (shared);
  - `roundCount`, `problemRoundCount` and `problemRounds[]` (round id, anchor id, anchor creation time,
    problem kinds), capped at 50 like every list; the rest via `/rounds?problemsOnly=true`.
- `GET /api/sessions/{id}/rounds?cursor=&limit=&q=[&problemsOnly=true][&atSeq=N]` pages through all rounds (derived): round id, anchor
  id, anchor creation time, in progress, counts by state, problem flag; newest first with problem rounds
  first; `q` is the server-side "find in all rounds" (matches coroutine names and creation locations).
- `GET /api/sessions/{id}/hierarchy` nodes gain the C-2 fields the cards and inspector render and their
  derived `roundId`; `?roundId=` limits the response to one round and `?atSeq=N` gives the replay fold's
  view. The workspace loads one round at a time through it.
- The existing full payloads (`GET /api/sessions/{id}` with all coroutines, the share response with all
  events) are unchanged; making them compact for very large sessions is out of scope (see "Later").
- `GET /api/sessions/{id}/metrics` splits by origin; leak ages are frozen at the last observation.
- The SSE stream forwards watermark messages.
- Ingest accepts the watermark message, the origin field and the provenance field (older SDKs omit them).

**C-4 Persistence.** In database-backed sessions the summaries are persisted with the session and used
after old events are trimmed. The latest watermark is held in memory by the backend's live ingest
connection registry (`IngestConnections`), which every read consults while the app is connected; the
session column, written at most every 5 s and on disconnect, serves reads after the connection ended or
the backend restarted.

**C-5 Checks persistence.** The backend keeps the last checks result per session (checked event seq, run
time, per-rule pass/fail) and serves it on `GET /api/validate/session/{id}`; `POST` there still runs a
new check. `StateBar`'s validation action chip is removed in favour of the T-8 status.

**C-6 Shares.** The public share payload carries the C-3 session fields, the health at load time and the
last checks result, all taken under one consistent cut (session state, events and health read at the same
`asOfSeq`). The share token gets read-only equivalents of the round reads:
`GET /api/shared/{token}/health[?atSeq=N]`, `…/health/items`, `…/rounds` and `…/hierarchy`, with the
same parameters, so the picker, "Show" and replay work the same in a shared view.

**C-7 SDK.** Sends the watermark (poll time on the app clock, at most once per second, also when a poll
produced no events) through the same ordered outbound buffer, after that poll's events, so the backend
never applies a poll's watermark before the events that poll produced; it carries the poll's app
wall-clock time and the SDK's cumulative count of lost events:
outbound-buffer overflow and bus evictions (today only logged), plus a flag when a connection ended
uncleanly while events may have been in flight (there is no delivery acknowledgement). `stop()` gets a
new bounded shutdown deadline (default 5 s, configurable; today it cancels immediately): it first sets an
atomic capture cutoff by closing the client's session to every producer (the poll loop and wrapper
`send()` calls alike; anything sent after the cutoff is outside the capture, rejected and not counted as
lost), then lets the bus subscriber hand every event captured before the cutoff to the outbound buffer,
drains the buffer, and finally sends a close message carrying the cumulative loss count, which is final
because no producer can add to it any more. The backend treats a close as clean only
when that final message arrived; if any step misses the deadline, the close reports delivery uncertain, the origin per coroutine (creation-stack rule above; `UNKNOWN` session origin
without creation stacks), and suspension provenance. These live in the DebugProbes poll loop and synthesizer in `coroutine-viz-core` plus
`VizcoreClient`.

## Frontend

- `SessionHeader`: two status pills, last data, start time, rung badge, actions per T-7, Learn toggle.
- New `HealthStrip` (strip, chips, problem list, checks status) reading `useSessionHealth` (live: refetch
  on events and watermark, throttled; replay: `atSeq`; shared: share payload and share endpoint).
- `StateBar` moves into the coroutine panel toolbar as the filter; leak chip → "Long-lived (n)";
  validation chip removed.
- `CoroutineCanvas`: round picker (paged from `/rounds`), "find in all rounds" (server-side `q`), "vizcore connection (n)" toggle (shown only
  when n > 0);
  `CoroutineTreeGraph` cards render the C-2 fields and observation badges.
- Inspector per T-3: new `ExceptionCard`, `ClueCard` and session summary; summary-only inspector (no canvas node, events possibly unavailable) for ungrouped incidents and trimmed replay failures.
- `SessionMetrics` tiles move into the empty inspector's session summary; `LeakList` is removed (T-5).
- `EvidencePanels`: sections collapsed by default with summaries; `ThreadTimeline` → grouped table.
- `StructuredConcurrencyInfo` → Learn panel.
- `lib/format-duration.ts` for lifetimes ("2.5 s", "12 h 46 m", "so far", "at least").
- #232's frontend app-clock estimate and "may be blocked" hint are removed; the inspector reads
  `POSSIBLY_BLOCKED` from the health payload.
- Colours from `src/styles/palette.ts`; state colours through `coroutine-state-colors.ts`.
- No health, round or problem logic in the frontend beyond presentation (D-8).

**Accessibility.** Problem chips are buttons with `aria-expanded` and `aria-controls` on the problem list;
"Show" moves focus to the inspector heading; graph cards are focusable (Tab, Enter to inspect) and keep
#246's zoom, fit and full-screen buttons; collapsed evidence sections are disclosure buttons with
`aria-expanded`; connector lines and the selection ring meet 3:1 against the canvas; state is always icon
+ text; a possibly blocked coroutine's lifetime is a list of transitions, not a colour bar.

## Delivery

**Before any of this:** the 1.2.0 release PRs #230, #231, #232 and #234 and the graph PR #246 are merged
(this design builds on #230's SDK-owned exclusion and dispatcher, #232's open-phase timing and threshold,
and #246's canvas).

| PR | Content | Depends on |
|---|---|---|
| 1 | Core: wire types, summary updater, round anchoring, `SessionHealthDerivation`, thresholds (C-1, C-2 in memory), with fixtures for the three sketch boards | release PRs |
| 2 | Backend: ingest of the new fields, API (C-3), persistence (C-4), checks (C-5), shares (C-6) | 1 |
| 3 | SDK: watermark, origin by creation stack (verified on the leftovers first), provenance (C-7) | 1 (end to end with 2) |
| 4 | Frontend: header, health strip, problem navigation, inspector per kind, cards, Learn, removal of #232's hint, `LeakList` and the validation chip | 2 (works with an older SDK: possibly blocked "not evaluated", origin `UNKNOWN`) |
| 5 | Frontend: round picker and search, vizcore toggle, evidence sections and thread table | 4 |

Each PR: tests first, Codex review until clean, headless Chromium check against a live flowwave session
where the change is visible.

## Dependencies

- **#244** (parent terminal event before children's): counting is order-independent; only the root's
  recorded state reads "Completed (recorded) · a child failed" until #244 is fixed.
- **#217** (root naming): until fixed, anchors show as unnamed with their creation point.
- **#221**: long-lived becomes an observation, as recommended there.

## Testing

- Core: table tests for `SessionHealthDerivation` — failed rounds with child-first and parent-first
  terminal order, container-root anchoring including an anchor flip that re-keys a failed round and
  sticky promotion (new active children do not demote it; the root finishing does unless it grew after
  promotion, so a request whose children finish first stays one round and a cancelled app root keeps its
  rounds), a failed round with a possibly blocked sibling,
  the clue only from a current-phase thread,
  stubs keeping anchoring and round order after reduction, capacity overflow (untracked count, `OVERFLOW`,
  no green state, "at least"), ungrouped failures, cancellation exclusion, origin
  (`APP`/`VIZCORE`/`UNKNOWN`), possibly blocked with and without watermark, demo, first-observed-running,
  transitions gap, event-loop clue prefixes, observations, `atSeq` folding, summary cap and "at least".
- Backend: summaries and watermark survive trimming and reload (in memory and database), a database
  session's health read sees each new watermark while the app is connected, an unclean close and a
  restart with the "ingest open" marker set both make delivery uncertain, `health` shapes
  for live, replay and shared, checks persistence, watermark on SSE, ingest from an older SDK.
- SDK: watermark during quiet polls, watermark ordered after its poll's events with a delayed buffer, dropped-event count after an outbound buffer overflow, `stop()`
  draining before a clean close and reporting what it could not send, origin from creation stacks (the four leftovers, an app coroutine,
  an app coroutine launched through `VizScope.vizLaunch` stays `APP` and is counted once (not also as a
  `dp-` coroutine), no creation stacks → `UNKNOWN`),
  provenance values.
- Frontend: strip states (problems, none, no app coroutines, not evaluated, unknown origin, awaiting
  first observation, at least, details unavailable),
  "Show" navigation and focus, round picker, replay labels and the summary-only inspector, shared labels,
  header pills, Learn persistence, evidence summaries; existing suites stay green.
- End to end: the three sketch boards reproduced with flowwave (`/api/uat/dashboard?fail=true`,
  `/api/uat/long` for possibly blocked, a disconnected session), plus a demo session.

## Risks

- **Event-loop names change between library versions.** The clue is advisory; a missed match only hides
  it.
- **Watermark traffic** adds one small message per second per app and, in database mode, one session
  update every 5 s.
- **Summary growth** is bounded by the C-2 caps; the cost of hitting them is stated on the page.
- **The creation-stack origin rule may not reach every leftover**; those stay `APP` and only appear as
  long-lived observations.
- **Revising D-4** changes a tested surface; `StateBar` tests are kept for its filter role.
- **Round anchoring flips** early in a session (a root becomes a container after 3 children); promotion is
  sticky while the root runs, so the picker re-keys at most twice per root (when it qualifies and when it
  finishes).

## Later (not in 1.3)

- SDK observed running streak and running stack: a live "running for N s" and "Now at <frame>".
- Sending SDK transport errors so "vizcore connection" health can cover them.
- Frozen share snapshots.
- Compact initial session and share payloads for sessions far beyond today's sizes.
- Plugin alignment to the shared derivation (sub-project 5).
