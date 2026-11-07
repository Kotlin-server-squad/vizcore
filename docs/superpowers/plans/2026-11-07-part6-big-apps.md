# Part 6 · Big apps — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A session with hundreds or thousands of coroutines stays readable (spec S-12): one round at a time with a
round picker and "find in all rounds" (triage T-4), the "vizcore connection (n)" toggle, "group by name" with a group
summary, and Find that reaches a coroutine past the 200-node cap. When the cap or the 10,000-event live history trims
what is shown, the page says so.

**Spec:** [`2026-11-05-session-dock-design.md`](../specs/2026-11-05-session-dock-design.md) — S-12, S-2 ("a selection
is a coroutine, a group or a resource"), S-5 ("an attached app: Tree (grouped)"), the Part 6 row, "Where triage's
frontend work goes" (round picker, find in all rounds, vizcore toggle → Part 6), the Part 6 Testing bullet
("sibling-only grouping (two parents with `order-*` children stay apart); Find beyond the cap"). Triage
[`2026-10-21-session-triage-design.md`](../specs/2026-10-21-session-triage-design.md): T-4, Definitions (Origin,
Rounds), C-3 (`/rounds`), Frontend (`CoroutineCanvas`: round picker, find, vizcore toggle "shown only when n > 0").

**Builds on:** Part 3 (dock), Part 4 (one selection, `Selection`), Part 5 (#299 `/rounds` with `q`, `problemsOnly`,
`includeVizcore`, `ungroupedMatches`; #300 metrics by origin), triage PR 3 (#298, the SDK sends origin), the look
switch and looks A/B/C (#301, #303, #304, #305).

**Branches:** two stacked PRs, both from the worktree `../vizcore-part6`:
- **6a** `feat/part6-big-apps` from `origin/main` (5d84f87b): the backend fields, the round bar (picker, Ungrouped,
  vizcore toggle, origin note), Find in all rounds with past-the-cap rendering, the cap and history notes.
- **6b** `feat/part6-groups`, stacked on 6a: group by name, the group card in Graph and List, the group selection and its
  Inspector summary, Find expanding a group.

Gates: frontend `pnpm exec tsc --noEmit && pnpm lint && pnpm test && pnpm build`; backend
`./gradlew ktlintCheck detekt test` (6a touches it). Never commit `backend/docs/index.html` or
`backend/docs/.swagger-codegen/VERSION`.

---

## Decisions taken while planning

### Where the tree's round and origin come from (backend, 6a)

- **`GET /api/sessions/{id}` nodes gain `roundId` and `origin`** (both nullable, additive). `snapshotResponseOf`
  already reads the durable summaries for the session facts (`readSessionFacts`); it now also computes
  `RoundAnchoring.of(summaries)` and each node's **effective** origin (the derivation's rule, made public as
  `SessionHealthDerivation.effectiveOrigin(summary, kind, sessionOrigin)`: a demo is `APP`, a session whose origin is
  `UNKNOWN` is `UNKNOWN` everywhere, else the summary's origin, `UNKNOWN` when absent). A node without a summary (not
  folded yet, or summaries unavailable) gets both `null`. A node with a summary but no round (its ancestry reaches an
  untracked parent) gets its origin and `roundId: null` — that is "ungrouped" (triage Rounds).
  A share payload's `session` (same function, its cut's summaries) carries the fields too.
- **Nodes and summaries come from one cut.** Today `snapshotResponseOf` copies the nodes, then `readSessionFacts` copies
  the summaries separately; an event in between could give old node states a newer round partition. Without a share
  cut, the snapshot now takes both under the session's send lock (`session.readCut`, as `ReadCut.of` does) and hands
  that cut to `readSessionFacts`.
- **`summaryOnlyCount`** (new, on the snapshot, same cut): coroutines the summaries know that the snapshot's nodes do
  not hold, vizcore's own left out — a database session reloaded after its events were trimmed rebuilds its nodes from
  the retained events, while its summaries come back from the checkpoint. The page does not draw those coroutines (it
  has no node for them; `/hierarchy` builds summary-only nodes by replaying the whole store, too heavy for a 5 s
  refresh); it says so (see "What the page says when it trims"). Drawing and inspecting them is **Part 8**'s: the
  spec gives it "triage's trimmed-history fallbacks" (coroutines whose creation event was trimmed come from the
  durable summaries, a summary-only Inspector); today's page does not draw them either.
- **Nodes without a summary** (origin null) in a session whose other nodes have one: not folded yet, or beyond the
  summary table's capacity (`SummaryTable` stops tracking new coroutines at its limit while the snapshot keeps them).
  Their round is unknown, and the page never guesses it: they are a view of their own, "Round unknown (n)" in the
  picker, and Find, which searches summaries, says "N coroutines without a summary were not searched".
- **`asOfAppNanos`** (new, on `RoundsPage`): the derivation's own observation time (`health.asOf.appNanos`: the
  watermark, the ingest or the last event — the moment its problem marks are about; null before any observation). Problem marks and find results are health statements (H-2): the picker and the find
  results say "as of <time into the capture>", or "as of event N" without a time.
- **Why not `/hierarchy?roundId=` for every refresh, as C-3 sketches.** `hierarchyOf` replays the whole retained event
  store into a throwaway projection per request (`projectionOf`); the live view refreshes every 5 s
  (`LIVE_REFRESH_MIN_INTERVAL_MS`), and a big app keeps up to 100,000 events. The snapshot already reads the summaries,
  and round anchoring is linear in them. The snapshot also keeps one source for the tree's states, the state bar, the
  Inspector and selection (today's `session.coroutines`), so no two reads at different moments disagree about a
  coroutine's state. The full payload stays as it is otherwise (C-3: "unchanged"; making it compact is "Later").
  `/hierarchy?roundId=` is still used, once, on demand: the group summary's shared creation site (6b).
- No round, origin or problem logic in the frontend (D-8): the frontend filters and groups by the server's `roundId`
  and `origin`; round order, problem marks and find matches come from `/rounds`.

### Which coroutines the main view shows (6a)

- **Rounds mode** is on in the **live and paused view** when the snapshot's nodes carry round facts (any node with a
  non-null `origin`) and `GET …/rounds` answered. Otherwise — replay, the shared view (Part 8 binds both to one moment),
  a session without usable summaries (503), an older backend — the page is today's page: no round bar, all coroutines,
  the cap. For an attached app whose rounds are unavailable, the toolbar says "Rounds unavailable for this session"
  (H-3), nothing more.
- **The view (the round choice)** is one of: *All rounds*, one *round* (by `roundId`, keeping its `anchorId`),
  *Ungrouped* (coroutines with an origin and no round) or *Round unknown* (coroutines without a summary); the last two
  are listed only when there are any. The pool the canvas renders is
  the snapshot's coroutines filtered by that choice, then the existing state filter / show-completed / cap logic.
- **Default:** an attached app (`session.kind === 'ATTACHED'`, else not a demo by id) opens on the **first round the
  server lists** (problem rounds first, then newest, T-4); a demo opens on *All rounds* (a learner sees the whole run, as
  today). The default is pinned once, when the first page of rounds arrives (like the opening dock tab): new rounds in a
  busy app never move the view. A user's choice wins and is kept for the page's life.
- **A round that is regrouped** (a root qualifying as a container, or its promotion cleared; triage Rounds): when no
  node carries the chosen `roundId` any more, the view follows its anchor — the round the anchor node is in now. When
  the anchor is gone too, it falls back to the default.
- **While rounds load** for an attached app, the canvas shows "Loading rounds…" instead of all coroutines, so the first
  view does not flash the whole app and then jump to one round.
- **The round picker** is a native `<select>` (keyboard and screen-reader behaviour for free, no HeroUI popover in a
  toolbar): "All rounds", the listed rounds, "Ungrouped (n)" and, when the server has more, "Show 50 more rounds…" (picks
  the next page; the choice stays where it was). Problem rounds sit in an `<optgroup label="With a problem">`, the rest
  in "Newest first". An option reads `request-17 · at 1 m 2 s · 6 coroutines · in progress` / `· failed` /
  `· possibly blocked`; a vizcore round adds `· vizcore connection`. The anchor's time is time into the capture
  (`anchorCreatedNanos − captureStartAppNanos`, `formatDuration`); without a capture start, the anchor name alone.
  **Counts are the snapshot's** members of that round, vizcore's left out when the toggle is off (fresh with every
  refresh, and they agree with the tree and the state bar); order, problem kinds, `inProgress` and the anchor come from
  `/rounds`. The problem group's label carries the list's moment: "With a problem · as of 1 m 4 s" (H-2).
- **The chosen round always has an option.** A round chosen through Find, or pushed past the listed rounds by newer
  ones, keeps an option of its own at the top of "Newest first" ("request-3 · … · not in the list above" is not needed:
  the option is just there), labelled from the last `RoundEntry` seen for it (the page keeps every entry it has read,
  from the list and from find results, by round id) **without its status words**: anchor, time and the snapshot's
  count only, since that entry's problem marks belong to an older read and the option sits outside the group that
  carries the current "as of".
- The picker is shown when rounds mode is on and there are at least two choices, or always for an attached app (it
  names which round you are looking at). A one-round demo shows no picker: Classic's demo page is unchanged.
- **The state bar counts the view** (the chosen round, Ungrouped, or All), not the whole session: it is the filter of
  what the canvas shows. Its counts in All rounds with the vizcore toggle off leave vizcore coroutines out, and its
  "Long-lived" count is the leak set intersected with the view (today `deriveStateCounts` counts the whole leak set).
- **Empty views say why** (H-3, H-4): a round where the state chip matches nothing — "No <state> coroutines in this
  round"; All rounds or Ungrouped empty only because the toggle hides vizcore's coroutines — "Only vizcore's own
  coroutines were captured so far. Turn on vizcore connection to see them."; the session-wide empty states stay as
  today.

### Requests (6a)

- The live refresh stays at three reads (snapshot, threads, metrics; `request-budget` test). The rounds list
  (`useRounds`, one query, key `['session-rounds', id, includeVizcore, limit]`) is **one request whatever is listed**:
  it starts at `limit=50`, and "Show 50 more rounds…" raises the limit (100, 150, 200 — the API's largest page) instead
  of loading further pages, so a refresh never costs more than one read. Past 200, the picker's last entry reads
  "N older rounds are not listed: Find reaches them by name or creation site" (disabled), from `RoundsPage.total`.
- The list is re-read **at most every 15 s, and only after a snapshot refresh** while the view is live (the stream on
  and connected or polling), and when the picker gets focus in the live view and the list is older than 2 s. In the
  **paused view** it is read with the page and on Refresh only, so it never runs ahead of the frozen tree. Budget: busy
  stream 3 reads / 5 s + 1 / 15 s = 40 a minute (was 36); SSE down, the fallback poll every 10 s brings the list every
  20 s: 18 + 3 = 21 a minute (was 18). Both stay well under 60; the test asserts the new bounds with a fixture of
  several hundred rounds.
- Find is one request per submit (Enter or the button), never per keystroke. Its result is not refreshed live; it says
  when it was searched ("Found 3 · searched at 1 m 4 s").

### The vizcore toggle and origin (6a)

- **"vizcore connection (n)"**, a checkbox, shown only when n > 0, where n is the number of snapshot coroutines whose
  origin is `VIZCORE` (what the toggle reveals; the same figure as the metrics' `byOrigin.vizcore.captured` up to one
  refresh). Off by default. Off: vizcore coroutines are left out of All rounds, Ungrouped and the state bar, `/rounds`
  is read with `includeVizcore=false` (vizcore rounds without a problem are not listed), and Find does not return them.
  The server filtered rounds by their anchor's origin only; `/rounds?q=` now also leaves vizcore matches (members and
  ungrouped) out **before** counting and capping them unless `includeVizcore=true`. A vizcore coroutine inside an app
  round is left out of the round's pool and count by its node `origin` (presentation, D-8).
  On: `includeVizcore=true`, vizcore rounds are listed and marked, the coroutines are shown.
- Turning it off while a vizcore round is chosen goes back to the default view.
- **Origin unknown** (an attached app whose `session.origin === 'UNKNOWN'`, triage Origin): no toggle (n is 0), and a
  muted note in the round bar: "origin not available: vizcore's own coroutines may be included".

### Find in all rounds, past the cap (6a; 6b adds the group)

- A search field "Find in all rounds" in the round bar. Submitting calls `/rounds?q=…&includeVizcore=…&limit=200`
  and lists the matches under the field, **grouped by round** (the server's order: problem rounds first, then newest),
  ungrouped matches last. The contract is explicitly partial, and the list says exactly what it holds: each round
  lists its first 50 matches (the server's cap per round, `Capped`); when it has more, "first 50 of 300 matches in
  request-3 are listed. Coroutines with the same name and location cannot be told apart here: refine the search, or
  open the round." with an "Open this round" button; when more than 200 rounds matched, "N more rounds matched and
  are not listed: refine the search". **The honest rule, not a continuation:** a find cannot single out one of many
  coroutines that share name, function and file — nothing the page holds distinguishes them — so the page says so
  instead of promising that refining reaches them. Opening the round draws its members up to the cap, with the cap
  note. The summary line reads "Found 312 in 4 rounds · as of 1 m 4 s"
  (sums of `matches.total`), or "Nothing matched in the captured coroutines · as of …" with, for an attached app, the
  sampling sentence (H-4). Each match reads its name and state (from the snapshot). A match the snapshot does not hold
  is listed, disabled: "not drawn: the page's snapshot does not hold it".
- **Choosing a match** (S-12): switches the view to the match's round (or Ungrouped); clears the filters that would hide
  it — the state chip goes back to *all* when it does not match, Show completed turns on when the match is finished; turns
  the vizcore toggle on when the match is a vizcore coroutine (only reachable with it on, but kept for safety); renders it
  **even past the cap** (it and its ancestors in the view are added to the rendered set, after the first 200); selects it
  (Part 4's selection) and opens the Inspector (restoring a maximised dock); and **brings it into view**: the graph pans
  to centre the card at the current zoom (zooming in to the look's readable floor when the whole-tree fit is smaller),
  the List scrolls it into view. The cap note then counts the extra cards it took: "Showing 203 of 1,234 coroutines in
  this round: the first 200, and 3 more to show the one you found."
- Escape or "Close" hides the results; the field keeps its text.

### What the page says when it trims (6a)

- The cap note replaces "N more coroutines": "Showing 200 of 1,234 coroutines in this round. Find reaches one by its
  name or creation site." (in All rounds: "in this view"). Part of the canvas footer (`data-look-part="cap-note"`).
- **The live history:** when the live stream holds `LIVE_EVENTS_MAX` (10,000) events, a note under the canvas: "The
  Events tab, the second lines and the Locks, Channels, Flows and Jobs sections read the newest 10,000 events this page
  received." (Threads and Dispatchers are server reads and are not named; the sentence is true whether or not older
  events were dropped, so no claim is made about how many.)
- **Coroutines known only from their summaries** (`summaryOnlyCount > 0`): a note under the canvas, "N coroutines are
  known only from their summaries (their events were trimmed) and are not drawn." Find still lists them (disabled).
- **Cap wording by unit.** 6a counts coroutines (a card is a coroutine). In 6b a card can be a group, so the note counts
  both: "Showing 200 of 350 cards (1,234 coroutines in this round). Find reaches one by its name or creation site."

### Found while building 6a (browser check on the sample app)

- **The cap's set is stable.** The snapshot lists nodes in a hash map's order, which reshuffles as a busy app adds
  coroutines, so "the first 200" changed with every 5 s refresh (cards came and went, and a found coroutine could slip
  into the first 200 and back). When a pool exceeds the cap, the 200 kept are now chosen by id and drawn in the pool's
  order; a pool within the cap is drawn whole, as before (demos unchanged).
- **The summary capacity is reached in practice.** The sample app with 300 fan-out passes 100,000 coroutines in about
  15 minutes; after three hours it had 1.37 million. The newest coroutines then have no summary, no round and no find:
  "Round unknown (1,265,004)" and Find's "not searched" line are what the page can honestly say. Raising or reshaping
  the capacity is backend work outside this part.
- **A round's first fit is tiny in Classic.** 300 siblings in one row fit at a scale too small to read (Classic fits the
  whole tree, by design); A, B and C keep their readable floor. Group by name (6b) folds such rows into one card.

### Group by name (6b)

- **"Group by name"**, a checkbox in the round bar, shown in rounds mode for a single round or Ungrouped (where every
  shown coroutine shares one round, or has none); not in All rounds, where two siblings can be in different rounds
  (a container root's children are each their own round). On by default for an attached app, off for a demo.
- **Siblings only** (S-12): coroutines with the same `parentId` and the same `roundId` whose labels are a common stem
  plus a trailing number (`/^(.*\D)(\d+)$/`: `order-17`, `order-18` → `order-*`) form a group when there are at least
  two. A coroutine without a label, or whose label has no trailing number, is never grouped. Ancestry is never hidden:
  the parent stays, the group takes the place of its members under it. Two parents with `order-*` children make two
  groups. Pure function `groupSiblings(coroutines)` in `src/lib/name-groups.ts`.
- **The group node** is a synthetic tree node (`id = group:<parentId|root>:<roundId|none>:<stem>`) between the parent
  and its members. **Collapsed** (the default): a leaf card — `order-*`, "group of 12", the state mix
  ("8 Completed · 3 Active · 1 Failed", state words, never colour alone) and "+30 below them" when the members have
  descendants. **Expanded:** the same card with its members (and their subtrees) under it. The card's button
  "Expand"/"Collapse" toggles; clicking the card elsewhere selects the group. Group edges are neutral (`currentColor`),
  the card `data-state="GROUP"`, so no look colours a group as one state.
- **The cap counts cards**, after grouping: a collapsed group of 300 is one card.
- **The group selection** joins `Selection`: `{ kind: 'group', id }`. It opens the Inspector with the **group
  summary**: `order-* · group of 12 siblings`, the parent's name, counts by state, "+30 coroutines below them", the
  members as buttons (first 50; selecting one selects that coroutine), Expand/Collapse, and **the shared creation site**:
  read once from `GET …/hierarchy?roundId=<round>` (a query keyed by session and round, fetched while the group is
  selected, not refreshed live); when every member's `creationPoint` names the same function, file and line, "All
  created at OrderService.kt:42 (processOrder)"; when they differ, "Created at 3 different places"; when none is known,
  "creation site unavailable" (H-3). Ungrouped groups (no round) skip the fetch and say "creation site unavailable".
  A group that no longer exists (members finished into other states never dissolves it; a regroup or a filter can)
  clears the selection, like a coroutine that is gone.
- **Find expands every enclosing group** of the match — its own and every collapsed group one of its ancestors is a
  member of — and forces the whole path (ancestors, expanded group nodes) past the cap. The group summary's member
  buttons use the same reveal, so a member chosen there is drawn and in view, not only selected.
- **The shared creation site needs complete knowledge.** "All created at OrderService.kt:42 (processOrder)" only when
  the hierarchy answer covers every current member and each has the same `creationPoint` (function, file, line);
  otherwise the known sites with their counts and the rest as unavailable ("at OrderService.kt:42 for 5; unavailable
  for 7"); "Loading creation sites…" and "Creation sites unavailable (the request failed)". The answer is re-read when
  the group's membership changes (its member ids are part of the query key).
- **Events tab with a group selected:** the events about its members ("Events for order-* · 12 coroutines"), with
  "All events" to clear the selection, like a coroutine.

### Looks (both PRs)

- One markup in every look, with new `data-look-part` hooks: `round-bar`, `round-picker`, `round-find`,
  `round-find-results`, `round-find-match`, `vizcore-toggle`, `group-toggle`, `origin-note`, `cap-note`,
  `history-note` (6a); `node` with `data-kind="group"` and inside it `group-name`, `group-count`, `group-mix`,
  `group-below`, `group-expand`; `group-row` in the List view; `group-summary` in the Inspector (6b).
- Classic gets Tailwind classes in the style of today's toolbar (small text, `default-100` tray, `default-200` borders);
  nothing that exists today changes pixels except where Part 6 replaces it (the "N more coroutines" line).
- A, B and C get rules in their own `tokens.css` (bar, find results, notes, group summary), scoped
  `[data-look='x'] [data-look-part='…']` as the existing rules (`looks-css.test.ts`), and in `card.css` (the group card
  at their card size), scoped `[data-card-look='x'] …` as that file's rules are (`cards-css.test.ts`): an export drops
  the page's `data-look` and keeps the graph's card look.
  A: flat 1 px controls on the toolbar bar, the group card in its 188 × 66 box (name and count on one line, mix on the
  second). B: calm, rounded, muted. C: bold.

### Big apps to test with

- The SDK sample app `examples/spring-vizcore-demo` (a Spring app with `coroutine-viz-client`, continuous mode) gains
  one property, `vizcore.fan-out` (env `VIZCORE_FAN_OUT`, default 0): each `request-N` also launches
  `fetch-1 … fetch-<fan-out>` children (siblings with a trailing number, so they group; 300 of them pass the cap). Its runBlocking root never completes and gets a new `request-N` every round, so it
  is a container root and every request is a round of its own. The SDK's own leftovers (Ktor CIO coroutines) arrive
  tagged `VIZCORE` (#298), so the toggle shows on real data.
- Browser check on backend 18601, frontend 13601: the app with `VIZCORE_FAN_OUT=300`, continuous; round picker,
  Show more rounds, vizcore toggle on and off, Find `fetch-250` (past the cap), Ungrouped (if any), group expand and the
  group summary; a demo (E-commerce Orders) to show the demo page unchanged. Screenshots in all four looks, dark and
  light, at 1440 and 1024 px, saved under `docs/superpowers/plans/2026-11-07-part6-big-apps/`. The snapshot's node
  order is a hash map's, so the Find target is one checked to be absent from the capped view before searching.

### Not in this part

- Replay and the shared view keep today's page (no rounds): Part 8 binds them to one moment (rounds at `atSeq`, share
  endpoints).
- Problem marks link to the Problems tab and "Show" in Part 7.
- List and Timeline "All rounds" (T-4) beyond what the picker offers: the List view has All rounds through the picker
  like the Graph; Timeline is Part 10.

## Review history

- **Codex code review R1 (6a) — request changes** (6 P2, 1 P3), all taken: a found coroutine's ancestors survive the
  state chip; a failed round list comes back (Refresh, and the live rule gated on the last error too); the reveal
  re-centres while the canvas settles (restoring the dock) until the user pans or zooms; a round the snapshot holds
  nothing of counts 0; Round unknown does not promise Find ("Find cannot search them"); a closed search sends
  nothing when the toggle changes; the live-history note shows in the live view only.
- **Codex code review R2 (6a) — request changes** (3 P2), all taken: a chip or Show completed chosen after a find ends
  the forced path; a submitted search keeps the vizcore rule it was submitted with (one request per submit); a capped
  view always has the round bar's Find that its cap note points to (a one-round demo then shows Find, no picker).
- **Codex code review R3 (6a) — request changes** (1 P2), taken: find results are filtered by the vizcore rule they
  were searched with, as the server counted and capped them, not by the current toggle.

- **Codex plan review R1 — request changes** (9 P1, 7 P2, 1 P3), all taken: an infinite query refetched every loaded
  page (now one request, the limit grows); find matches are capped per round (now an explicitly partial, per-round
  result with "refine"); vizcore members of app rounds and vizcore matches leaked past the toggle (now filtered by
  node origin); nodes and summaries were read at two moments (now one cut); summary-only coroutines of a reloaded
  database session vanished silently (now `summaryOnlyCount` and a note); problem marks had no "as of" and the paused
  view could fetch a newer list (now `asOfAppNanos`, and no re-read while paused); the chosen round could lose its
  option; Find revealed only the match's own group and group members were only selected (now the whole path); group
  card CSS must use `data-card-look`; the creation site claimed "all" on partial knowledge; cap wording mixed cards and
  coroutines; a revealed node could stay off screen and a maximised dock hid the Inspector; the history note named
  server-read sections; the Long-lived chip counted the whole session; group Events claimed a scope it did not have;
  empty views reused session-wide wording; the Find target in the browser check was not proven past the cap.
- **Codex plan review R2 — request changes** (3 P1, 4 P2, 1 P3). Taken: vizcore matches are now left out on the
  server before counting and capping; "as of" is the derivation's `asOf`; a preserved option drops its stale status
  words; nodes without a summary get a "Round unknown" view and Find says it did not search them; Task 8/5/9/10 texts
  aligned. Two decided by the honest rule rather than another round (the coordinator's standing rule): coroutines that
  share name, function and file cannot be singled out by any find — the page now says so and offers to open the round,
  instead of claiming refining reaches them; summary-only coroutines of a reloaded database session stay disclosed but
  undrawn, because drawing them from durable summaries is Part 8's per the spec.
- **Codex plan review R3 (final) — request changes** (no P1; 1 P2, 2 P3), fixed without a fourth round: the cap note,
  the "older rounds" entry and the find texts no longer promise that Find reaches every coroutine ("Find reaches one by
  its name or creation site"; matches sharing a name and creation site "cannot be told apart by a search"; Ungrouped
  offers no "open the round"); Task 1 tests a poll watermark later than the last event; task texts synced (Round
  unknown, the round branch's vizcore rule).

---

## File structure

| File | PR | Change | Responsibility |
|---|---|---|---|
| `backend/coroutine-viz-core/.../health/SessionHealthDerivation.kt` | 6a | modify | `effectiveOrigin` public |
| `backend/src/main/.../routes/RouteDtos.kt` | 6a | modify | `CoroutineNodeDto.roundId`, `.origin` |
| `backend/src/main/.../routes/SessionHealthReads.kt` | 6a | modify | `SessionFacts.summaries` (the copy it already reads) |
| `backend/src/main/.../routes/SessionRoutes.kt` | 6a | modify | `snapshotResponseOf`: one cut, the fields, `summaryOnlyCount` |
| `backend/src/main/.../routes/SessionRoundsReads.kt` | 6a | modify | `RoundsPage.asOfAppNanos` |
| `backend/src/test/.../routes/SessionSnapshotRoundsTest.kt` | 6a | create | the fields: two roots, container root, vizcore, unknown origin, demo, no summaries, orphan |
| `backend/src/main/resources/openapi/documentation.yaml` | 6a | modify | the two fields |
| `frontend/src/types/api.ts` | 6a | modify | node `roundId`/`origin`, snapshot `kind`/`origin`/`captureStartAppNanos`, `RoundEntry`, `RoundsPage` |
| `frontend/src/lib/api-client.ts` (+ test) | 6a | modify | `getRounds` |
| `frontend/src/hooks/use-rounds.ts` (+ test) | 6a | create | `useRounds` (infinite, 15 s refresh rule), `useRoundSearch` |
| `frontend/src/lib/round-view.ts` (+ test) | 6a | create | pure: the choice → pool; default; follow anchor; Ungrouped; vizcore filter; option labels; reveal (filters to clear, forced ids) |
| `frontend/src/components/workspace/RoundBar.tsx` (+ test) | 6a | create | picker, find, toggles, notes |
| `frontend/src/components/workspace/CoroutineCanvas.tsx` (+ test) | 6a | modify | round bar slot, cap note, history note, loading rounds |
| `frontend/src/components/SessionWorkspace.tsx` (+ tests) | 6a | modify | wiring |
| `frontend/src/hooks/use-event-stream.ts` | 6a | none | (`LIVE_EVENTS_MAX` already exported) |
| `frontend/src/components/SessionWorkspace.request-budget.test.tsx` | 6a | modify | `/rounds` in the fake server; 40 a minute |
| `frontend/src/looks/{a-compact,b-calm,c-bold}/tokens.css` | 6a, 6b | modify | the new parts |
| `examples/spring-vizcore-demo/.../SpringVizcoreDemoApplication.kt`, `README.md`, `application.properties` | 6a | modify | `vizcore.fan-out` |
| `frontend/src/lib/name-groups.ts` (+ test) | 6b | create | `groupSiblings` |
| `frontend/src/lib/selection.ts` (+ test) | 6b | modify | `{ kind: 'group' }` |
| `frontend/src/components/CoroutineTreeGraph.tsx` (+ test) | 6b | modify | group card, neutral edges |
| `frontend/src/components/CoroutineTree.tsx` (+ test) | 6b | modify | group row |
| `frontend/src/components/workspace/Inspector/GroupSummary.tsx` (+ test) | 6b | create | the group summary |
| `frontend/src/hooks/use-round-hierarchy.ts` | 6b | create | `/hierarchy?roundId=` once |
| `frontend/src/looks/{a-compact,b-calm,c-bold}/card.css` | 6b | modify | the group card |
| `CHANGELOG.md` | both | modify | entries |

---

## Tasks — 6a

### Task 1 · Backend: `roundId` and `origin` on snapshot nodes

- [ ] Test first (`SessionSnapshotRoundsTest`, Ktor `testApplication` like the existing session route tests): a session
  fed events through the same ingest path the existing health tests use:
  1. two roots `a` (children `a1`, `a2`) and `b` → `a`, `a1`, `a2` carry `round:a`, `b` carries `round:b`;
  2. a container root (never completes, 3 children, 2 completed) → each child its own round, the root a singleton;
  3. an attached session with a reported `VIZCORE` coroutine → `origin: "VIZCORE"`, others `APP`;
  4. an attached session whose producer reports no origin → every node `UNKNOWN`;
  5. a demo session → `APP`;
  6. a child whose parent was never captured → `origin` set, `roundId: null`;
  7. summaries unavailable → both fields absent/null, the rest of the payload unchanged;
  8. `summaryOnlyCount`: 0 normally; a summary without a node (an app coroutine) counts, a vizcore one does not;
  9. `/rounds` carries `asOfAppNanos`, the health's `asOf` (a poll watermark later than the last event wins);
  10. find leaves vizcore matches out before counting and capping, unless `includeVizcore=true`.
  Watch it fail (fields missing).
- [ ] Make `effectiveOrigin` public on `SessionHealthDerivation` with the kind and session origin as parameters
  (unchanged behaviour; its existing callers pass the same values).
- [ ] `SessionFacts` keeps the `durable` summary copy it already reads; `snapshotResponseOf` computes
  `RoundAnchoring.of(it)` and fills `roundId = anchoring.roundIdOf(id)`, `origin = effectiveOrigin(...)` for nodes with
  a summary.
- [ ] OpenAPI: the two fields. `./gradlew ktlintCheck detekt test`. Restore `backend/docs/*` before committing.

### Task 2 · Client: types, `getRounds`, `useRounds`, `useRoundSearch`

- [ ] Tests first: `getRounds` builds `/sessions/{id}/rounds?limit=50&includeVizcore=true&q=…` (only the given
  parameters, encoded); `useRounds` reads one page at `limit=50`, "more" re-reads one page at 100, 150, 200 and no
  further; the key changes with `includeVizcore`; `useRoundSearch` sends nothing until a query is submitted and one
  request per submit.
- [ ] Implement. The 15 s refresh rule lives in the workspace (Task 5), not the hook: the hook exposes `refetch` and
  `dataUpdatedAt`.

### Task 3 · Pure round view (`src/lib/round-view.ts`)

- [ ] Tests first, then implement:
  - `hasRoundFacts(coroutines)`: any node with a non-null `origin`.
  - `poolFor(coroutines, choice, { includeVizcore })`: All → every node except `VIZCORE` when off; round → nodes with
    that `roundId`, vizcore rule as All; Ungrouped → nodes with an origin and `roundId == null`, vizcore rule as All;
    Round unknown → nodes without an origin.
  - `resolveChoice(choice, coroutines, fallback)`: a round no node carries follows its anchor's current round; gone →
    fallback.
  - `defaultChoice({ attached, firstRound })`.
  - `ungroupedCount`, `vizcoreCount`.
  - `roundOptionLabel(entry, counts, captureStartAppNanos)`: the texts above, including vizcore and problem kinds, and
    the no-capture-start form.
  - `revealPlan(match, { stateFilter, showCompleted, includeVizcore, coroutines })`: which filters to clear and which
    ids to force (the match and its ancestors in the pool).
  - `capRendered(pool, cap, forcedIds)`: the first `cap` plus the forced ones not among them, and the counts for the
    cap note.

### Task 4 · `RoundBar` and the canvas notes

- [ ] Tests first (`RoundBar.test.tsx`, `CoroutineCanvas.test.tsx`): the picker lists All rounds, the rounds in their
  optgroups, Ungrouped (n) only when n > 0, and "Show 50 more rounds…" only with a next page; choosing it calls
  `onLoadMore` and keeps the value; the vizcore checkbox only when n > 0, labelled "vizcore connection (3)"; the origin
  note; Find submits on Enter and on the button, shows matches with round and state, disables a match not in the view,
  "and 12 more", Escape closes; the cap note texts (plain, with a found coroutine, All rounds wording); the history note
  only at 10,000 events; "Loading rounds…"; "Rounds unavailable for this session".
- [ ] Implement `RoundBar` (presentational; every derivation in the caller) and the canvas footer.

### Task 5 · Wiring in `SessionWorkspace`

- [ ] Tests first (`SessionWorkspace.rounds.test.tsx`, `apiClient` mocked like the sibling tests):
  - an attached session opens on the first listed round; the tree shows only its members; the state bar counts them;
  - a demo opens on All rounds, and shows no picker with one round;
  - a new round arriving in a refresh does not move the view;
  - a regrouped round follows its anchor;
  - the vizcore toggle hides and shows vizcore coroutines and re-reads rounds with `includeVizcore=true`;
  - Find past the cap: a round of 250 members, find the 240th → the view switches to its round, a Completed chip filter
    is cleared, the match is rendered (graph mocked to list ids) and selected, the Inspector opens, the cap note says
    "201 of 250";
  - a match in Ungrouped switches to Ungrouped;
  - replay and the shared view render no round bar;
  - rounds 503 → today's page, and for an attached app the "unavailable" note.
- [ ] Implement: `roundChoice` state with the pinned default; `includeVizcore` state; the pool through `poolFor` before
  the existing filter/cap memo; `forcedIds` state set by a reveal, cleared when the view changes; the rounds refresh
  rule (snapshot `dataUpdatedAt` effect: refetch when older than 15 s; picker focus: older than 5 s); pass the bar to the
  canvas.
- [ ] Update `SessionWorkspace.request-budget.test.tsx`: the fake server answers `/rounds` with several hundred rounds
  and the snapshot's node carries `roundId`/`origin`; "Show more" raised to 200; the busy-stream bound is 40 a minute
  (at least 30) and the SSE-down bound 21.

### Task 6 · Looks for the round bar (A, B, C) and the sample app

- [ ] Style the new parts in `a-compact/tokens.css`, `b-calm/tokens.css`, `c-bold/tokens.css`; `looks-css.test.ts`
  stays green (every selector scoped).
- [ ] `vizcore.fan-out` in the sample app, README note.
- [ ] Browser check and screenshots (see "Big apps to test with"); CHANGELOG; gates; Codex code review; PR 6a.

## Tasks — 6b

### Task 7 · `groupSiblings`

- [ ] Tests first: `order-17`, `order-18` under one parent → one group `order-*` of 2; two parents each with `order-*`
  children → two groups; same parent, different `roundId` → no group; a label without a trailing number, a null label
  → never grouped; `a1`/`a2` (stem `a`) group; a single `order-1` stays a card; members keep their subtrees; the synthetic
  node takes the first member's place; collapsed hides members and descendants and reports `below`; expanded keeps them
  under the group; state mix counts.

### Task 8 · Group card (Graph, List) and the group selection

- [ ] Tests first: the graph renders a group card with its name, "group of 12", the mix and Expand; Enter on the card
  selects the group, the Expand button toggles without selecting; edges into a group are neutral; the List view renders a
  group row the same way; `Selection` accepts `{ kind: 'group' }` and Events lists only its members' events ("Events for
  order-* · 12 coroutines"), never unrelated ones.
- [ ] Implement; `data-kind="group"` and the `group-*` parts.

### Task 9 · Group summary in the Inspector

- [ ] Tests first: counts by state, "+30 below them", members as buttons (selecting one selects the coroutine), the
  shared creation site (all same → "All created at …"; differing → "Created at 3 different places"; none → "creation
  site unavailable", partial knowledge, loading, error), one `/hierarchy?roundId=` request per group membership (a new
  member re-reads), none for an Ungrouped group, Expand/Collapse.
- [ ] Implement `GroupSummary`, `useRoundHierarchy`.

### Task 10 · Wiring, Find expands groups, looks, PR 6b

- [ ] Tests first: group by name on by default for an attached app in a round, off for a demo, absent in All rounds;
  the cap counts cards; Find of a match nested under a collapsed group's member expands every enclosing group and
  forces the path; a group summary's member button reveals; a selected group that is gone is cleared.
- [ ] Group card in `a-compact/card.css`, `b-calm/card.css`, `c-bold/card.css`; group summary in the tokens.
- [ ] Browser check and screenshots; CHANGELOG; gates; Codex code review; PR 6b.

---

## Testing summary

- Backend: `SessionSnapshotRoundsTest` (Task 1).
- Frontend: `round-view.test.ts`, `use-rounds.test.ts`, `api-client.test.ts`, `RoundBar.test.tsx`,
  `CoroutineCanvas.test.tsx`, `SessionWorkspace.rounds.test.tsx`, the request budget (6a); `name-groups.test.ts`,
  `selection.test.ts`, graph and list group tests, `GroupSummary.test.tsx`, workspace group tests (6b). The spec's Part 6
  bullet: sibling-only grouping (Task 7) and Find beyond the cap (Task 5, Task 10).
- Browser: the sample app with 300 fan-out in continuous mode, and a demo, in four looks, dark and light, 1440 and 1024.

## Risks

- **The snapshot gets heavier** by two short fields per node. Accepted; compact payloads are "Later" (C-3).
- **The round list is up to 15 s behind** a busy app (counts are not: they are the snapshot's). New rounds appear on
  the next list refresh or when the picker gets focus.
- **A native `<select>`** cannot show rich option content (marks are words). Accepted for keyboard and screen-reader
  behaviour.
