# Part 8 · One moment everywhere — Plan

**Spec:** [2026-11-05 session dock](../specs/2026-11-05-session-dock-design.md), S-11 (with S-2 and S-14), and the
"Modes" section of [2026-10-21 session triage](../specs/2026-10-21-session-triage-design.md) (replay and shared view).
**Branch:** `feat/part8-one-moment`, worktree `../vizcore-part8`, ports 13701 (UI) / 18701 (API).
**Goal:** in replay and in a shared link, every part of the page shows the same moment: the tree, the Inspector,
Evidence and the session metrics. The replay slider moves in seconds. A session whose early events are gone says so and
falls back to the server's durable summaries instead of pretending.

## What is on main today (the starting point)

- Replay is client-side: the page freezes its events (`useWorkspaceReplay`) and the tree is `projectCoroutines` over the
  events up to the cursor. The slider steps by event index (`use-replay.ts:163`) and announces "4.2 seconds of 12.0".
- The Inspector is **paused** in replay ("Exit replay to inspect a coroutine") and empty in a shared link ("Coroutine
  details are not included"). Selection is live-only (PD-01).
- Evidence: Threads, Locks and permits, Channels, Flows and Jobs already read the cursor's events. **Dispatchers** reads
  the live `/threads` query and shows the "Live data" notice in replay; in a shared link it shows nothing.
- The session summary (metric tiles) is live-only.
- Backend (PRs #299, #300): `…/coroutines/{id}/timeline` and `…/hierarchy` accept `atSeq` (replay: lenient, flags
  `partial` and `eventsTrimmed`/`summaryOnly`) and `asOfSeq[&asOfCut]` (bound: strict, 409 `stale`); the shared routes
  `/api/shared/{token}/…` take the same parameters; the share payload carries `health` (with `asOfSeq`) and `healthCut`.

**No backend change is needed.** Every read Part 8 needs exists. Metrics at a moment are computed in the page from the
same events and coroutines the tree shows (see M-1), which is what "agree with the tree" asks for.

## Decisions

| # | Decision |
|---|---|
| M-1 | **Metrics at a moment are the page's own projection**, not a new `/metrics?atSeq`. `lib/moment-metrics.ts`: **Active** and **dispatcher load** are read from the same coroutines the tree shows at the moment (the client projection plus M-9's summary nodes; in a shared link the loaded snapshot): active = not terminal and state known; coroutines whose state is unknown are counted apart ("2 with state unknown"); dispatcher = the coroutine's `scopeId`, as `MetricsProjection` uses, else "unknown". **Peak** and **throughput** are folded from the replayed events the way `MetricsProjection` folds them (peak = most coroutines created and not yet terminal at once; throughput = events in the 10 s of **app time** before the moment, over 10 s; the tile says "on the app's clock"). When the replay does not start at the beginning (M-8) both say "in the replayed events". Long-lived coroutines are **not evaluated at a moment** (H-3): the summary says so instead of showing "none". The live `/metrics` keeps feeding the live view, unchanged. |
| M-2 | **Seconds slider, one clock.** The slider's value is the cursor's time since the replay's first event (`lib/replay-seek.ts`). The same clock feeds every replay time on the page: the header's "Replay at …" pill, the look's header line, the "at this moment" marker and the slider's `aria-valuetext` (today `SessionWorkspace` subtracts raw stamps; it moves to `replayClock`). The origin is always the replay's first event; when that is not the session's first (M-8), the note says where the replay starts. Time is the running maximum of `tsNanos` in seq order, so it never goes back when two threads' events interleave. Dragging maps a time to the **last event at or before it**. Arrow keys on the slider step one event (as the ← → shortcuts do), Home/End go to the ends. The unit is 0.1 s, or 1 ms when the whole replay is shorter than 2 s; when every event has the same time the slider falls back to steps by event. The label beside it reads "4.2 s / 12.0 s · event 37 of 120". `aria-valuetext` stays "4.2 seconds of 12.0" (S-14). Playback timing is unchanged (ADR-017). |
| M-3 | **One moment, one read.** A small `MomentRead` value says how the Inspector's reads are bound: `{source: session | shared(token), binding: current | replay(atSeq) | bound(asOfSeq, asOfCut?)}` (`lib/moment-read.ts`). The Inspector provides it in a React context; `useCoroutineTimeline` reads it, so `TimingCard`, `EventsCard` and `CoroutineSourceStack` follow without new props. Live: `current`, exactly today's query key and behaviour. Replay: `replay(cursor seq)`. Shared link: `bound(health.asOfSeq, healthCut)` through `/api/shared/{token}/…`; shared replay: `replay(atSeq)` through the same. |
| M-4 | **The Inspector never shows another moment, and reads only when it must.** A bound read is keyed by the seq it was fetched at (S). It is shown at the cursor (C) only when the replay **holds every seq between S and C** (no gap: the live stream may have shed events the server kept) **and none of them is an event of that coroutine**; then its content at S is its content at C (the timeline holds only that coroutine's events), so moving the cursor across other coroutines' events costs no request. Otherwise the Inspector says "updating to this moment" (never the other moment's data) and fetches C after the cursor settles: 300 ms for session reads; **2 s** for shared reads, and not at all while the replay plays. The shared bucket allows 60 requests a minute per client (`share.rateLimit.requestsPerMinute`) for every shared read together; with at most two moment reads (the selected coroutine's timeline and, in an incomplete replay, the hierarchy) settling at 2 s each, the page stays at or under 60 a minute however the cursor moves; other reads come from clicks. Events with a seq after C are dropped from what is shown, as a second guard. A 429 shows "Too many requests" with Try again; 404 "not known at this moment"; 409 `stale` M-11; 503 `summaries-unavailable`/`history-trimmed` "Details unavailable: …" with the reason. The generic hook is `use-moment-query.ts`; the Inspector's timeline and M-9's hierarchy both use it. |
| M-5 | **Selection in every mode** (S-2, S-11 supersede PD-01). Clicking a node, an event or a resource selects it in replay and in a shared link too. The Inspector shows the selected coroutine at the cursor. In replay, a selected coroutine that does not exist yet at the cursor keeps its selection and reads "<name> · not created yet at this moment"; it is cleared only if it is not in the replay at all. |
| M-6b | **Boundary with Part 7.** The Problems tab and its health reads (`/health?atSeq`, "possibly blocked: not evaluated (replay)", durable failures at the cursor) are Part 7's, which does not exist yet. Part 8 gives it the binding: `healthPath(read)` in `moment-read.ts` builds `…/health` / `/api/shared/{token}/health` with the replay `atSeq`, and the Problems tab will read through the same `MomentRead`. |
| M-6 | **"At this moment" marker.** Outside the live view, the Inspector (coroutine and summary), Evidence and the session summary start with one line naming their moment: replay "At 4.2 s · event 37 of 120", shared link "As loaded · event 812". Same markup in every look: `data-look-part="moment"`. |
| M-7 | **Evidence at the moment.** Dispatchers reads the page's thread activity (projected at the cursor, or from the shared events) instead of the live `/threads` query; the "Live data" notice disappears from Evidence. No other Evidence change. |
| M-8 | **A replay that does not start at the beginning says so.** When the frozen events' first seq is after 1 (the server trimmed its history, the page's 10,000-event live buffer dropped the oldest, or the events were cleared), the time bar shows: "This replay starts at event N: earlier events are not in it, so it may be incomplete." (`data-look-part="trimmed-note"`). Peak then reads "peak in the replayed events". |
| M-9 | **Coroutines created before the replay starts come from the summaries** (triage Modes). Only when M-8 applies, the page reads `…/hierarchy?atSeq=<cursor>` (shared: `/api/shared/{token}/hierarchy?atSeq=`) through `use-moment-query` (M-4). Nodes the client projection lacks are added to the cursor's coroutines with their summary facts (creation location, last suspension point, last thread, failure, first observation, terminal time): state = the node's `summaryState`, or **"State unknown"** when `partial` (H-3). Nodes the client projects keep the client's state. A result fetched at S is used at C only when no replayed event between S and C is a creation or concerns an added node; otherwise the added nodes wait for the read at C (the tree shows the client projection meanwhile, and the note says "updating the coroutines created before it"). A failed read leaves the client projection alone and the note says "coroutines created before it could not be read". **"State unknown" is its own count**: `state-counts` gets an `unknown` bucket (part of the total, never Active), the state bar a "State unknown" chip shown only when non-zero (it filters like the others), and the run outcome and "waiting for N children" do not treat it as running. |
| M-10 | **Summary-only Inspector.** For a coroutine whose timeline at the moment comes back `eventsTrimmed`: a **Summary** card shows what the durable summary keeps (created at, last suspended at, last thread and dispatcher, failure type and message, first observed and finished times), marked "from the durable summary". Its facts come from M-9's node when the tree has one; otherwise (a shared link that is not replaying, or a coroutine the client projection holds) from `…/hierarchy` read **with the same binding** (one read, kept for that seq, through the same rate rule); Timing reads "unavailable: early events were trimmed" (no `lifetimeSoFar` estimate from what is left); Events reads "Earlier events were trimmed" and lists what remains without claiming a total. The timeline response lists only creation, start and suspension events (`ProjectionService.toSummary`), so the Events card says "recorded events" as today. For `partial`: "Its state at this moment is unknown: its events before it were trimmed." |
| M-11 | **Shared link reads are bound** (triage Modes): `asOfSeq` = the share payload's `health.asOfSeq` and `asOfCut` = `healthCut`. Without a health (`healthUnavailable`), the Inspector does not guess a binding: it shows the coroutine's identity and "Details unavailable: this link's health could not be read (<reason>)". A 409 `stale` reads "This view has moved on since the link was loaded." with Reload, which reloads the page, so the payload, the health and its cut are fetched again together. **The header's Reload in a shared view does the same** (today it calls the protected session's `refetch()`, which mixes a newer tree with the old cut, or fails without a login). **The loaded cut stays put:** the shared route sets `staleTime: Infinity` on the seeded `['sessions', id]` keys, so no later mount refetches the protected session or events and mixes newer data in. The shared view's summary uses M-1 over the loaded snapshot. |
| M-13 | **"Runs on" at a moment is "Last observed on".** The thread projection records assignments only, so outside the live view the card is titled "Last observed on", and Dispatchers' "Thread Pool Size" reads "Threads observed" when it is fed the page's activity (M-7). The live view's wording is unchanged. |
| M-12 | **Four looks.** New UI (slider label, marker, trimmed notes, summary-only text) has one markup with `data-look-part` hooks: `replay-bar`, `replay-time`, `moment`, `trimmed-note`. Classic styles them with Tailwind classes like today's page; A, B and C style them in their own `tokens.css`, keyed like the existing rules. No Classic pixel changes outside what Part 8 adds or replaces (the slider's label and the Inspector's replay/shared placeholder). |

## Files

New (each small, in its own file, so Part 6 can merge around them):
- `frontend/src/lib/replay-seek.ts` (+ test): `replayClock(events)` (running-max times), `indexAtTime`, `sliderScale`.
- `frontend/src/lib/moment-read.ts` (+ test): the `MomentRead` type, its query key and URL builder.
- `frontend/src/lib/moment-metrics.ts` (+ test): M-1.
- `frontend/src/lib/cursor-coroutines.ts` (+ test): M-9's merge (pure).
- `frontend/src/hooks/use-moment-query.ts` (+ test): M-4's read rule, shared by the timeline and the hierarchy.
- `frontend/src/components/workspace/Inspector/SummaryCard.tsx` (+ test): M-10.
- `frontend/src/hooks/use-cursor-coroutines.ts` (+ test): M-9's read.
- `frontend/src/components/workspace/MomentNote.tsx` (+ test): M-6.
- `frontend/src/components/workspace/MomentSummary.tsx` (+ test): the summary at a moment (tiles from M-1).

Changed:
- `api-client.ts`: `getTimelineAt(read, coroutineId)`, `getHierarchyAt(read)` (added at the end; existing methods untouched).
- `use-timeline.ts`: `useCoroutineTimeline` reads the context (M-3, M-4); unchanged for `current`.
- `Inspector.tsx`, `TimingCard.tsx`, `EventsCard.tsx`, `CoroutineSourceStack.tsx`: provider, M-4/M-10 texts, "suspended at" at a moment.
- `ReplayController.tsx`: M-2, `trimmed-note` slot, `data-look-part`s.
- `SessionMetrics.tsx`: optional `data` prop (skips the fetch when given).
- `DispatcherOverview.tsx`, `EvidencePanels.tsx`: M-7.
- `SessionWorkspace.tsx`: wiring (selection in every mode, moment, binding, M-9). Kept to wiring; the logic is in the files above.
- `routes/shared.$token.tsx`, `types/share.ts`, `types/api.ts`: share token, health `asOfSeq`, `healthCut`; timeline `eventsTrimmed`/`partial`.
- `lib/replay-time.ts`: uses `replayClock`.
- `coroutine-state-colors.ts`: label for `UNKNOWN` ("State unknown").
- `state-counts.ts`, `StateBar.tsx`: the `unknown` bucket and chip (M-9).
- `RunsOnCard.tsx`: M-13.
- `looks/{a-compact,b-calm,c-bold}/tokens.css`: the new hooks.

## Tests first (vitest)

1. `replay-seek`: running max; time→index picks the last event at or before; ties; empty; one event; zero span → index mode; the 1 ms unit under 2 s.
2. `ReplayController`: slider value and label in seconds; dragging to a time seeks to its event; ← → on the slider step one event; trimmed note shown only when given.
3. `moment-read`: keys and URLs for the five shapes (current, replay, bound with/without cut, shared replay, shared bound).
4. `use-moment-query` + Inspector at a cursor: **a store with a later event: the Inspector never lists it** (spec's Part 8 test); a timeline fetched at S is reused at C with no event of that coroutine between them (no request), and not reused when there is one (shows "updating", then fetches C); shared reads wait while playing; 429/404/409/503 texts; live keeps today's key.
5. Inspector: summary-only (`eventsTrimmed`) and `partial` texts; "not created yet at this moment".
6. `moment-metrics`: active/peak/throughput/dispatchers at a cursor; unknown states excluded from active; peak over the replayed events only.
7. `MomentSummary`, `MomentNote`: texts in replay and shared.
8. `cursor-coroutines` + hook: merge adds missing nodes, keeps client state for known ones, `partial` → UNKNOWN; fetched only when the replay starts after seq 1; a result is not reused across a creation or an event of an added node (**rewinding before a completion never keeps it Completed**); failure note. `state-counts`: unknown is its own bucket, never Active; the chip filters it.
9. `EvidencePanels`: Dispatchers from the passed activity; no live-data notice in replay.
10. `SessionWorkspace`: selection works in replay and shared; shared Inspector reads `/api/shared/{token}/coroutines/{id}/timeline?asOfSeq=…&asOfCut=…`; 409 shows the moved-on text; without a health it shows "Details unavailable"; the shared route keeps its seeded keys fresh forever; existing PD-01 tests updated to the new rule.
11. Looks: `looks-css.test.ts` keeps passing (every rule scoped); a test that each new hook is styled in A, B and C.

## Browser check (ports 13701/18701, backend with `SESSION_MAX_EVENTS=300` for a trimmed session)

Replay a finished demo to a moment: Inspector, Evidence (Locks, Dispatchers) and the summary agree with the tree; drag the
slider to a second, step with ← →; replay a trimmed session (note, summary-only nodes, "Events unavailable (trimmed)");
open a share link (Inspector bound, replay in the shared view). Screenshots in Classic, A, B and C, dark and light.

## Delivery

One PR if it stays near 800 lines of production code; otherwise two stacked PRs: **8a** seconds slider, moment read,
Inspector/Evidence/metrics at the cursor, marker, looks; **8b** trimmed fallbacks (M-8 to M-10) and the shared view
(M-11). CHANGELOG entry. Codex plan review (≤3 rounds) and code review (≤3 + confirmation).

## Risks and gaps

- Reads wait for the cursor to settle (300 ms; shared 1 s and paused while playing); meanwhile a part that cannot prove its data is still true at the cursor says "updating" instead of showing another moment.
- Long-lived is not evaluated at a moment (M-1); the triage health at `atSeq` could supply it later (Part 7).
- Peak counts only coroutines created in the replayed events (named as such when the replay is incomplete).
- Part 6 is changing the tree's data source at the same time; M-9 sits in its own hook so the merge is one line in
  `SessionWorkspace`.
- The Timeline's "part after the cursor is outlined" belongs to Part 10 (the Timeline view does not exist yet).
