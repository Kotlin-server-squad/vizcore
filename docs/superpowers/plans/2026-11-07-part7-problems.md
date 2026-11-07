# Part 7 · Problems — Plan

**Spec:** [2026-11-05 session dock](../specs/2026-11-05-session-dock-design.md): S-8, S-3 (connection pill, last data, start
time), S-5 (problem / attached app open on Problems), the Part 7 row and "Where triage's frontend work goes"; the
[2026-10-21 session triage](../specs/2026-10-21-session-triage-design.md) it refers to: H-1 to H-4, T-1, T-2, T-3, T-5, T-8,
"Health strip states", "Cards", "Lifetimes", "Modes", C-2, C-3, D-8.
**Branch:** `feat/part7-problems` (worktree `../vizcore-part7`), stacked on `feat/part6-groups` (#312). Ports 13801 (UI) /
18801 (API).
**Goal:** the dock's first tab answers "what is wrong?" from the health API only, the header says whether the app is
connected and when its last data was observed (on the app's clock), the tree cards carry triage's lifetime, start
location and observation badges, and the old frontend guesses (#232's "may be blocked" hint, `LeakList`, the validation
chip) are gone.

## Starting point

- Health API (Part 5): `GET /api/sessions/{id}/health[?atSeq=N]` returns `SessionHealth` (state, `asOf`, counts, capped
  incidents, `possiblyBlocked` evaluation with reason, observations, transport, retention, `asOfWatermark`); 503 with
  `summaries-unavailable` / `history-trimmed` when it cannot be derived. `/api/shared/{token}/health?atSeq=N` for a shared
  replay. `health/items` pages the lists past 50. `GET /api/validate/session/{id}` returns the last checks run
  (`ChecksStatus`). The share payload carries `health`, `healthCut`, `healthUnavailable` and `checks`.
- `GET /api/sessions/{id}` carries `kind`, `startedAtEpochMs`, `appConnected`, `lastIngestEpochMs`, `lastWatermark`
  (the frontend type does not declare the last four yet). The SSE stream sends `event: watermark` frames the page ignores.
- Part 8 (on main) gives `healthPath(read)`, `useMomentQuery`, `ShareBinding`; Part 6 (base) gives rounds, Find
  (`chooseFound`: switch round, clear a hiding filter, force past the cap, open groups, select, reveal).
- **This branch merges main** (Part 8b, look experiment E2), which #310–#312 do not hold yet: Part 7 needs 8b's shared
  binding. Until #310–#312 take main, this PR's diff also shows those main commits; the PR says so.

## Decisions

| # | Decision |
|---|---|
| P-1 | **One health read per moment** (`hooks/use-session-health.ts`). Live view: `GET …/health`, key `['session-health', id]`, never polled on a timer while the stream is up. The stream refreshes it on its own throttle, **at most every 10 s** (`LIVE_HEALTH_MIN_INTERVAL_MS`), after accepted events and after `watermark` frames (an attached app's quiet polls, so a blocked coroutine appears without new events). With the stream on but its connection down, health joins the fallback poll at 20 s (the round list's cadence). The header's Refresh refetches it too. While the stream is on, a snapshot or a health older than 15 s is re-read (`hooks/use-quiet-refresh.ts`; each tracked on its own `dataUpdatedAt`): a quiet or disconnected app (an unclean close sends no frame) still updates the connection pill and "last data", and a watermark-only stream, whose health refreshes every 10 s, still re-reads its snapshot every 15 s. At most 8 requests a minute, none on a busy stream. A paused view refreshes nothing (triage "Paused view": health freezes with the tree, "as of" stays). Replay: `GET …/health?atSeq=<cursor>` through `useMomentQuery` (300 ms settle; shared replay 2 s and held while playing, as M-4), `changesBetween` always true. Shared link, not replaying: the payload's `health` (no request); without one, the payload's `healthUnavailable`. **Budget:** the Inspector's 2 s open-phase timeline poll (`followOpenPhase`) goes with the app-clock estimate it fed (P-7); instead the selected coroutine's live timeline is re-read when the page has received events of that coroutine since the last read, at most every 10 s (a coroutine that suspends and resumes between two snapshots stays ACTIVE in both, so the state-change refetch alone would miss it); a busy stream is snapshot, threads, metrics every 5 s (36) + rounds every 15 s (4) + health every 10 s (6) = 46 a minute (test ceiling 40 → 46), 52 with a selected coroutine that keeps getting events; SSE down: 21 → 24. |
| P-2 | **The Problems tab presents the health and nothing else (S-8, D-8).** First line, by `state`: `PROBLEMS` "N problem(s) detected · as of <t>" with the per-kind counts ("Failed 2 (1 round · 1 ungrouped) · Possibly blocked 1"); `NO_PROBLEMS` green "No problems detected in the captured coroutines · as of <t>"; `NO_FAILURES_PARTIAL` neutral "No failures detected in the captured coroutines · as of <t>" plus why it is not clean (the lines below); `NO_APP_COROUTINES` neutral "No application coroutines captured · nothing to evaluate yet · as of <t>" with the sampling note (H-4); `AWAITING_FIRST_DATA` "Waiting for the first data from the app" (no "as of"). Every state that reports nothing detected (`NO_PROBLEMS`, `NO_FAILURES_PARTIAL`, `NO_APP_COROUTINES`) carries the sampling note (H-4: "vizcore samples an attached app's coroutines on a poll; one that starts and ends between two polls may not appear"). A health that cannot be read: "Problems not evaluated (<reason>)" (503 reasons in words; other errors "could not be read" with Try again). Then, as quiet lines: possibly blocked ("Nothing possibly blocked" when evaluated, else "Possibly blocked: not evaluated (<reason>)", reasons `NO_WATERMARK` "no poll data from this app", `DEMO` "demo session: the in-process wrappers do not observe every suspension", `REPLAY` "replay", `OVERFLOW`, `EVENTS_DROPPED`, `HISTORY_TRIMMED` in words); `failuresAtLeast` → counts read "at least N"; `transport` lost/uncertain → "some events may have been lost on the way from the app; results may be incomplete"; `counts.untracked > 0` → "N coroutines beyond capacity were not tracked: health covers the first M"; `sessionOrigin = UNKNOWN` → "origin not available: vizcore's own connection may be included"; replay with `retention.trimmed` → "replay starts at <t>; earlier events were dropped, so this may be incomplete"; `asOf.lowerBound` → "as of at least <t>". |
| P-3 | **"As of" and the clock (H-2, T-2).** `asOf.appEpochMs` (the app's wall clock, from the watermark) → "as of 14:02:31"; else `asOf.appNanos − captureStartAppNanos` → "as of 4.2 s into the capture"; else "as of event N". `REPLAY_CURSOR` always time into the capture and the event number. The browser's clock is never used for "as of", "last data" or ages. Formatting an epoch with the browser's locale is display only. |
| P-4 | **The problem list.** Incidents in the health's order (kind, then newest). `FAILED_ROUND`: "Failed round · <anchor name>", "N failed · K cancelled after (context)", then one row per listed failure: name, "Type: message" (or "details dropped"), **Show**; "and N more" when `failures.total > items`. `UNGROUPED_FAILURE`: one row, "round unavailable: its ancestry could not be resolved". `POSSIBLY_BLOCKED`: name, "running for 12.3 s with no suspension observed" (`runningForNanos`), "on <thread>" or "last thread not observed for the current run", **Show**. When `incidents.total > items.length`: "Showing 50 of N problems; the round picker lists every problem round" (no paging in this part; honest cut). Names come from the incident; anchor names from the page's coroutines, else the anchor id. |
| P-5 | **Show (S-2, T-3).** Show reuses Part 6's `chooseFound(id)`: it opens the coroutine's round, clears a filter that would hide it, draws it past the cap, opens its groups, selects it, opens the Inspector, gives a maximised main view the dock back and brings the card into view; then focus moves to the Inspector heading (`id="inspector-heading"`, `tabIndex={-1}`). For a coroutine the page's coroutines do not hold (trimmed, or not created yet at the replay cursor), Show selects it without a canvas node (T-3): the selection is kept while the health lists it, and the Inspector opens **from the incident only**: its name, the problem cards of P-6, and "Not drawn in the tree at this moment: shown from the health; its events may have been trimmed", with no timeline read. |
| P-6 | **Inspector per problem kind (T-3).** The workspace looks the selected coroutine up in the current health (`problemOf(health, id)`: a failure entry of a failed round or ungrouped failure, or a possibly blocked incident). Failed: new `ExceptionCard` (type, message or "details dropped"; for a round, "1 of 3 failed coroutines in this round · 4 cancelled after the first failure (context, not a cause)") → `SuspendedAtCard` → `TimingCard` → `RunsOnCard` → `IdentityCard`; Events on the right. Possibly blocked: a new `ObservedCard` built from the incident only (the thread of the **current run** from `lastThread`, else "not observed for the current run"; the dispatcher; the last suspension point only when its provenance is `REAL_FRAME`, else "suspension location unavailable") replaces `RunsOnCard` and `SuspendedAtCard` (which pick the latest assignment and any frame, without the current-run and provenance rules) → new `ClueCard` (only with `eventLoopClue`; triage's conditional advice, never a verdict) → `TimingCard` with the incident's transitions ("Running for 12.3 s with no suspension observed", "Resumed at 2.0 s" as time into the capture, "Earlier transitions were not retained" on `transitionsGap`, "No suspension was ever observed" on `firstObservedRunning`) → `IdentityCard`; Events on the right. The problem cards come from the health and say so: "as of <t>" (the rest of the live Inspector reads now, as today; the health is not re-read per selection). Anything else: today's order. When the coroutine is not in the health's lists but a list is cut at 50 (`incidents.total > items`, or a round's `failures.total > items`), the Inspector says "Problem details: not among the problems the health lists (it lists the first 50)" instead of implying none. |
| P-7 | **Remove the frontend guesses (T-5, #232).** `TimingCard` loses the app-clock estimate (`liveTiming`, `useNow`) and the "may be blocked" hint, and the Inspector stops its 2 s open-phase poll that fed them: an unfinished coroutine's total is the span of its recorded events, labelled **"at least"** (it cannot reach to the latest poll; 7b shows the server's lifetime instead, P-9, and keeps "at least" where the node has none). `LeakList` and its tests are deleted; `SessionMetrics` loses its leaks card and `SessionSummary` its list. `SessionSummary`'s count line uses the health's counts ("N app coroutines captured · M of vizcore's own connection · K origin unknown"), replacing the "arrives with the health API" placeholder. The live summary's tiles read `/metrics`' `byOrigin.app` slice (active, peak, throughput, dispatcher load: the app's, vizcore's own connection left out, T-3); without that slice they show the aggregate labelled "all captured coroutines (origin not available)". When `/metrics` says `retainedOnly`, its peak and throughput tiles read "in retained events since <t>" (`oldestRetainedAppNanos` as time into the capture). `MomentSummary` (Part 8, M-1) is unchanged. `StateBar`'s leak chip reads **"Long-lived"** (same filter); its validation `action` chip and prop are removed. |
| P-6b | **Suspension provenance everywhere (triage "Suspension-point provenance").** The timeline's suspend rows carry the event's `provenance` (backend `TimelineEventSummary.provenance`, nullable; one JUnit test). `CoroutineSourceStack` and `useSuspensionPoints` look at the **newest** suspension first: when its provenance is `CREATION_FALLBACK` or `STALE` (or it has no frame), "Last suspended at" reads "suspension location unavailable"; older real frames stay in the list as history, never as the last point. A missing provenance (demos' in-process wrappers, older producers) keeps today's display: those producers read the frame themselves. |
| P-8 | **Observations (S-8, T-5, T-8).** Under the problems, never counted: **"Long-lived (n)"** (`observations.longLived.total`), a button that applies the state bar's Long-lived filter and shows the tree (it un-maximises nothing, leaves the dock open); "Long suspended (n)" as text; "vizcore connection: N captured, no failed coroutines captured" / "M failed" when `vizcoreConnection.captured > 0`. Outside the live view the Long-lived filter's ids are the health's `longLived.items` (the live view keeps `/metrics`' complete list); when `total > items` the tab says "the filter shows the first 50". **Checks status** (T-8): "Instrumentation checks: not run" [Run checks], "passed at event N · M new events since", "failed at event N · K rules failed" (opens the checks modal), "status unavailable" when it cannot be read (never "not run"); M is the page's latest seq minus `checkedSeq` (no polling), else the server's `newEventsSince`. Read once from `GET /api/validate/session/{id}` and again after a run. The checks modal, opened without a fresh run in this visit, lists the persisted run's per-rule outcomes ("Last run at event N: <rule> failed (3)") above its Run button. A shared link uses the payload's `checks`, shows the status text only and offers no modal and no Run. |
| P-9 | **Tree cards (C-2, triage "Cards"), PR 7b.** `GET /api/sessions/{id}` nodes gain `lifetimeNanos`, `lifetimeKind` (`FINISHED` / `SO_FAR` / `AT_LEAST`) and `creationLocation` from the durable summary (null without one). Lifetime follows triage "Lifetimes", computed in core (`CoroutineLifetime.of(summary, liveWatermarkNanos)`, unit-tested): finished = first observation → terminal; unfinished = first observation → the later of the live watermark (attached, live only) and its own last observation, "so far"; without a watermark, to its last observation, "at least". The Inspector's Timing card shows the same lifetime when the node has it, so card and Inspector agree. Graph cards add a meta line (`node-meta`: "12.3 s so far · OrderService.kt:42") and a badge row (`node-badges` / `node-badge` with `data-badge`) from the current health: "Possibly blocked", "Long-lived · 2 m 3 s" and "Long suspended · 45 s" (frozen ages from the health); the list view (`CoroutineTree`) shows the same badges and meta after the name. A coroutine in a failed round's `cancelledAfter` reads "Cancelled · after the failure" (server-classified). The triage card text "Completed (recorded) · a child failed" is **not** built: it would need the frontend to classify a child's failure (D-8); left for a server-supplied flag. Replay nodes come from the client projection, which has no summary: they get badges from the cursor's health but no meta line (a documented gap, not a claim). Badges cover the coroutines the health lists (the first 50 of each list). |
| P-10 | **Header (S-3, T-2).** Attached apps only (not demos): a connection pill "App connected" / "App disconnected" from the session's `appConnected` (live and replay; not in a shared link, which shows what was loaded), then "Last data <t>" from the session's ingest facts, not the cursor: the app's wall clock of its last watermark (`lastWatermark.appEpochMs`), else the backend's receipt time (`lastIngestEpochMs`, labelled "received <t>"), else nothing; and "started <t>" from `startedAtEpochMs`. These come with the snapshot, which the quiet refresh of P-1 re-reads at least every 15 s while the stream is on. One small `ConnectionFacts` component in its own file, passed to `SessionHeader` as one optional node prop (no restructuring). |
| P-11 | **The dock (S-4, S-5).** `problems` is the first tab. Its label carries a count badge (`data-look-part="dock-tab-badge"`) only when `problemCount > 0`. Opening tab: remembered tab, else a Gallery demo still running → Learn, else **an attached app → Problems**, else a session whose health shows problems → Problems, else Inspector. The decision waits for the first health answer (success or failure) except for the Learn case; it is still made once. |
| P-12 | **Four looks.** Same markup everywhere with hooks: `problems`, `problems-headline`, `problems-line`, `problem`, `problem-show`, `observations`, `checks-status`, `connection`, `dock-tab-badge`, `exception-card`, `clue-card`, `node-meta`, `node-badges`, `node-badge`. Classic gets Tailwind classes in the page's existing idiom. A, B and C style them in their `tokens.css` (and `card.css` for the card parts), keyed `[data-look='x']` / `[data-card-look='x']` like the existing rules. Card sizes: every look's `card.ts` is re-measured in the browser with the worst case (two badges, a two-line activity line, a long name) and raised as needed so no child is cut off. No Classic pixel changes elsewhere. |

## Delivery: one PR

Planned as two stacked PRs (7a: health, tab, header, Inspector; 7b: card facts). Built, the two share the same wiring
(`SessionWorkspace`, `Inspector`, `TimingCard`): the cards' lifetime is also the Inspector's Timing total, and the badges
come from the same health read. Splitting them would have meant two reviews of the same hunks, so it ships as **one
PR** (about 1,750 lines of production code, most of it in new small files; the rest is tests).

**Card sizes, measured (P-12).** Each look's card grew by one row (lifetime, creation site and badges on one line; the
meta text gives way first): Classic 240 → 260, A 66 → 84, B 82 → 104, C 104 → 128 (worst case: a two-line name, a
two-line second line, two badges; nothing cut off, measured in Chrome). B's first view no longer held the root and its
first row with a 40 % dock, so B's dock starts at **35 %** (`dockShare`); A and C still fit (C's canvas is 467 px).

## Files

7a new: `hooks/use-session-health.ts`, `hooks/use-checks-status.ts`, `hooks/use-quiet-refresh.ts`,
`components/workspace/Inspector/ObservedCard.tsx`, `lib/health-text.ts` (sentences, reasons, as-of,
problem lookup — pure), `components/workspace/ProblemsPanel.tsx`, `components/workspace/ConnectionFacts.tsx`,
`components/workspace/Inspector/ExceptionCard.tsx`, `components/workspace/Inspector/ClueCard.tsx`, tests for each.
7a changed: backend `models/CoroutineTimeline.kt` + the projection that fills it (provenance, P-6b),
`CoroutineSourceStack.tsx`, `use-timeline.ts`, `SessionMetrics.tsx` (retained-only note), `types/api.ts` (health, checks, session facts), `types/share.ts`, `lib/api-client.ts` (`getHealthAt`,
`getChecksStatus`), `hooks/use-event-stream.ts` (health in the live refresh; watermark listener), `DockTabs.tsx`,
`lib/dock-tab-memory.ts`, `SessionWorkspace.tsx` (wiring), `SessionHeader.tsx` (one prop), `Inspector.tsx`, `TimingCard.tsx`,
`StateBar.tsx`, `SessionMetrics.tsx`, `SessionSummary.tsx`, `routes/shared.$token.tsx`, `hooks/use-one-moment.ts`
(`ShareBinding` gains `health` and `checks`), looks' `tokens.css`; deleted `LeakList.tsx` (+ test).
7b: core `health/CoroutineLifetime.kt` (+ test), `routes/RouteDtos.kt`, `routes/SessionRoutes.kt` (+ test),
`CoroutineTreeGraph.tsx`, `lib/card-facts.ts` (badges per id from the health — pure), looks' `card.css` / `card.ts`.

## Tests first

7a (vitest): `health-text` — every state's sentence, each not-evaluated reason, as-of from wall clock / capture time /
event, lower bound, at least, untracked, transport, unknown origin, replay trimmed; `problemOf`. `ProblemsPanel` — the
three tab states of the spec (problems, nothing detected, not evaluated), a failed round with its rows and Show, possibly
blocked row, "Showing 50 of N", Long-lived button calls the filter, checks status in its four states. `use-quiet-refresh`
— re-reads after 15 s without a refresh, never on a busy stream, never paused. `use-session-health`
— live key and no polling; replay reads `?atSeq` after settling; shared uses the payload and reads `/api/shared/…?atSeq`
in replay. `use-event-stream` — a `watermark` frame refreshes health at most every 5 s. `DockTabs` — badge only above
zero. `dock-tab-memory` — the opening rules. `Inspector` — Failed order with `ExceptionCard` first, possibly blocked
order with `ObservedCard`, `ClueCard` and the transition list (no `RunsOnCard`/`SuspendedAtCard`; a `CREATION_FALLBACK`
suspension reads "unavailable"), default order otherwise, the "not among the listed problems" note, the incident-only
Inspector. `TimingCard` — no hint, no estimate.
`ConnectionFacts` — connected / disconnected, last data from `appEpochMs`, from `receivedEpochMs`, none; never
`Date.now()`. `StateBar` — "Long-lived", no action chip. `SessionWorkspace` — Problems opens first for an attached app and
for a demo with a failure; Show selects and focuses the Inspector heading; replay reads health at the cursor; shared view
shows the payload's health. Request budget — ≤ 46 a minute on a busy stream (with a running coroutine selected), ≤ 24 with SSE down, 0 when idle
with the stream off.
Looks: the new hooks are styled in A, B and C (`looks-css`/`look.test` pattern).
7b: core JUnit for the lifetime rule (finished, so far with watermark, at least without, watermark older than last
observation); route test that nodes carry the fields; vitest for `card-facts` and the card's meta line, badges and
context words; `cards-css` keeps passing.

## Browser check

Backend 18801, frontend 13801. A demo with a failure (Problems shows it; Show selects it; ExceptionCard), a clean demo
("nothing detected … as of <t>"), "possibly blocked: not evaluated (demo session)", the SDK example app with
`VIZCORE_FAN_OUT` (connection pill, last data, possibly blocked after a blocking request), replay and a shared link.
Screenshots in all four looks, dark and light, 1440 and 1024 px.

## Risks

- Health refreshes at most every 10 s live, not every second (P-1): a blocked coroutine shows within ~10 s, and the
  tab's "as of" says when it was observed.
- The live Inspector reads now while its problem cards are the health's (P-6); they are labelled "as of", not bound.
- Lists capped at 50 are not paged in this part (P-4, P-8, P-9); the page says when it shows part of a list.
- Merge churn with Parts 9/10 in `SessionWorkspace` and `SessionHeader`: new logic sits in its own files; the workspace
  gains wiring only.

## Codex plan review

- Built after the review: the budget test also covers a selected coroutine that keeps getting events (≤ 52 a minute).
- **Round 3** (2 P2), both fixed: the newest suspension decides "Last suspended at" (P-6b); the live summary shows the
  app's metrics from `byOrigin.app` (P-7). Three rounds done; no further plan round.
- **Round 2** (5 findings, 2 P1, 3 P2), all fixed: provenance applies to every "last suspension point", with the
  timeline carrying it (P-6b); an unfinished span reads "at least" (P-7); the quiet refresh tracks the snapshot and the
  health apart (P-1); the selected coroutine's timeline is re-read after its own events, at most every 10 s (P-1); the
  live summary qualifies retained-only figures (P-7).
- **Round 1** (16 findings, 7 P1, 9 P2). Fixed: Show for a coroutine not on the page opens an incident-only Inspector
  (P-5); possibly blocked uses an `ObservedCard` from the incident, so the thread is the current run's and only a
  `REAL_FRAME` suspension point is shown (P-6); "Completed (recorded) · a child failed" dropped, it would be frontend
  classification (P-9); a quiet-refresh re-reads snapshot and health after 15 s without one, so a disconnect without
  events is seen (P-1); a selected coroutine not in a capped list says so instead of implying no problem (P-6); the
  budget recomputed and the 2 s open-phase poll removed (P-1, P-7); health joins the fallback poll and Refresh (P-1);
  checks status distinguishes "unavailable" and the modal shows the persisted run (P-8); Inspector and card lifetimes
  agree (P-9); the session summary splits counts by origin (P-7); sampling note on every no-detection state (P-2); the
  ungrouped wording claims no cause (P-4); "Last data" comes from the session's ingest facts, not the replay cursor
  (P-10). Chosen instead of the suggestion: the live Inspector's problem cards are labelled "as of <t>" rather than
  binding every live read to the health's cut (finding 2); list view gets badges and meta, replay cards stay without a
  meta line (finding 13); `MomentSummary` keeps Part 8's M-1 (finding 12).
