# The look experiment (E1 backend, E2 frontend)

Date: 2026-11-06. Branches: `feat/look-experiment` (E1, into `main`), `feat/look-experiment-ui` (E2, stacked on E1).

## Goal

The session page has four looks (Classic, A · Compact, B · Calm, C · Bold; PRs #301, #303, #304, #305).
All four stay. Instead of one person choosing, other people help decide: new visitors are split across the
looks, the server measures a few anonymous facts per look, people can rate the look they use, and a results
page applies a fixed decision rule. Everything stays on the operator's own vizcore server.

## Decisions (from the user, built exactly)

1. Split: new visitors get a look at random by server weights (`LOOK_EXPERIMENT=classic:25,a:25,b:25,c:25`,
   the default), changeable without a release. `LOOK_EXPERIMENT_ENABLED` switches it on and off; off = no
   assignment, no recording, every page as today. Sticky per browser (localStorage). A browser that already
   picked a look keeps it and is recorded as "chose". `?look=` still wins. First-time assigned visitors get
   a small dismissible hint that ⋯ → Look switches.
2. Measured, anonymous, per look: seen (a session page shown in the look), time in look (visible time on the
   session page, batched, sent on pagehide via sendBeacon), switched away (from → to). No feature use, no IP,
   no user agent, no session ids or names; only a random browser id (UUID in localStorage).
3. Vote: 1–5 per look plus an optional comment (≤ 500 chars). A small dismissible card after a few minutes of
   real use in a look, or on a later visit; always reachable from ⋯ → "Rate this look". One vote per look per
   anonymous id; a new vote replaces the old.
4. `LOOK_SURVEY_URL` (optional): a "Tell us more" link next to the vote, with `look=<id>` added. Hidden when unset.
5. Results: `/experiments/looks` and `GET /api/experiments/looks/results`, one row per look.
6. Decision rule, constants in one place (`LookDecisionRule`), status on the results page in plain English.

## Decisions I took (flagged in the PRs)

- **Default off.** `LOOK_EXPERIMENT_ENABLED` defaults to `false`: a self-hosted vizcore does not start
  measuring its users or showing them a hint without its operator asking for it. The weights default to 25
  each. The user's own deployment sets `LOOK_EXPERIMENT_ENABLED=true`.
- **An assigned look is not a choice.** The assignment is stored in the experiment's own key, not in
  `vizcore-look` (the user's choice). So when the experiment is switched off, an assigned visitor reads as
  Classic again ("every page as today"), while a look a person picked in the menu stays theirs as today.
  Order: `?look=` link > the person's choice (`vizcore-look`) > the experiment's assignment (only while on) >
  Classic.
- **A first visit through a `?look=` link** counts as "chose" (not randomly assigned, so it must not count in
  the split); the link look is what they saw.
- **Switched away** counts only a change made in ⋯ → Look (a person's act), not a link and not the assignment.
- **"Unless clearly worse"**: when the best-rated look's switch-away rate is more than 10 points above the
  runner-up's, the runner-up wins, and the page says why.
- **Experiment start** = the first recorded visitor (earliest `first_seen_at`); no extra setting.
- **The rule is over all four looks**, whatever their weights: a weight only steers new visitors. A look with
  weight 0 can still be chosen, rated and win; it is marked "not in the split" on the page.
- **Beacon with auth on.** The frontend authenticates with a JWT in the `Authorization` header, which
  `sendBeacon` cannot send. With no token (auth off, the common case) the batch goes by `sendBeacon`; with a
  token it goes by `fetch(..., { keepalive: true })` with the header, which also survives pagehide. (The token
  is never put into a URL.)
- **Writes follow the session page's access**: every endpoint sits in `authenticatedApi` like the sessions
  list (anyone who can open a session page can write the anonymous events; when auth is off everything is
  open, as the rest of the API).

## E1 — backend

Package `com.jh.proj.coroutineviz.experiment`.

### Config (`LookExperimentConfig`)

`application.yaml`:

```yaml
lookExperiment:
  enabled: ${LOOK_EXPERIMENT_ENABLED:false}
  weights: ${LOOK_EXPERIMENT:classic:25,a:25,b:25,c:25}
  surveyUrl: ${LOOK_SURVEY_URL:}
```

- Weights: `id:weight` pairs, comma separated, integers 0–1000. Unknown ids, bad numbers or a total of 0 →
  log a warning and use the default (all 25). A known look left out has weight 0.
- Survey URL: kept only when it parses as an absolute `http`/`https` URL, else ignored with a warning.
- Known looks live in one list, `LookIds.ALL = [classic, a, b, c]` with their labels (`Classic`, `A · Compact`,
  `B · Calm`, `C · Bold`), next to the decision rule. Deleting a look later means editing this list and the
  frontend registry; each side has a test pinning the list, and a comment in both files says so.

### Endpoints (all in `authenticatedApi` + the `api` rate-limit scope)

- `GET /api/experiments/looks/config` → `{ enabled: false }` when off; else
  `{ enabled: true, weights: { classic: 25, … }, surveyUrl: "…" | null }`.
- `POST /api/experiments/looks/events` — body (JSON; read as text so a `sendBeacon` Blob of any content type
  parses):
  `{ anonId: UUID, events: [ { type: "entry", look, kind: "assigned"|"chose" } | { type: "seen", look } |
  { type: "time", look, ms } | { type: "switch", from, to } ] }`.
  Validation: `anonId` a UUID; ≤ 50 events; looks known; `ms` 1–43 200 000 (12 h, clamped above); `from ≠ to`;
  unknown fields ignored; anything else → 400 (whole batch). Off → 404 `{"error":"The look experiment is off"}`.
  → 204.
- `POST /api/experiments/looks/votes` — `{ anonId, look, rating: 1..5, comment?: string ≤ 500 }` (trimmed;
  empty → null). Replaces this id's vote for this look. Off → 404. → 204.
- `GET /api/experiments/looks/results` → see Results. Works when off too (reading past data), with
  `enabled: false`.

Both write bodies are read through a byte limit before anything is parsed (`readBounded`: at most 16 KiB for
events, 4 KiB for a vote, counted while streaming, so a chunked body without `Content-Length` is cut off too)
→ 413 `{"error":"Request too large"}`.

Per-anonymous-id write limit (in addition to the per-client `api` write bucket): `AnonRateLimiter`, a fixed
one-minute window, 30 event batches and 10 votes per id per minute → 429 with `Retry-After`. Its map is an
LRU bounded to 10 000 ids.

### Storage (`LookExperimentStore`)

Interface, two implementations, chosen like sessions: `ExposedLookExperimentStore` when `storage.type=database`
(the `DatabaseKey` handle), else `InMemoryLookExperimentStore`.

```kotlin
fun recordEntry(anonId, look, kind, at): Boolean   // first entry per id only; false when full
fun addSeen(anonId, look)
fun addTime(anonId, look, ms)
fun addSwitch(anonId, from, to)
fun putVote(anonId, look, rating, comment, at)
fun snapshot(): ExperimentData                       // raw rows for the results
```

A seen/time/switch/vote for an id with no entry yet creates a visitor row with no entry kind (it still counts
in its look's measurements, not in assigned/chose). Bounds (both stores): at most 50 000 visitors; events and
votes from a new id beyond that are dropped (logged once). Per visitor at most 4 looks × 4 looks rows, so
everything else is bounded by the visitor cap.

Migration `common/V3__look_experiment.sql` (portable H2/PostgreSQL, `text` not `clob`):

```sql
create table look_visitors (anon_id varchar(36) primary key, entry_look varchar(16), entry_kind varchar(8),
  first_seen_at bigint not null);
create table look_exposure (anon_id varchar(36) not null, look varchar(16) not null,
  sessions_seen integer not null default 0, visible_ms bigint not null default 0, primary key (anon_id, look));
create table look_switches (anon_id varchar(36) not null, from_look varchar(16) not null,
  to_look varchar(16) not null, times integer not null default 0, primary key (anon_id, from_look, to_look));
create table look_votes (anon_id varchar(36) not null, look varchar(16) not null, rating integer not null,
  comment text, voted_at bigint not null, primary key (anon_id, look));
```

Writes in the Exposed store run in one `transaction` each, serialised by a lock in the store (one JVM writes;
update-then-insert without races). No foreign keys to sessions (none are stored).

### Results (`LookResults.compute(data, config, now)`)

Per look (all four, in registry order):

- `assigned`, `chose`: visitors whose entry is this look with that kind.
- `sessionsSeen`: sum of `sessions_seen`.
- `visitorsSeen`: ids with `sessions_seen > 0` in this look.
- `medianTimeMs`: median over ids with time in this look of their total visible ms (null when none).
- `switchAwayRate`: ids that switched away from this look ÷ `visitorsSeen` (null when 0); `switchedTo`:
  `{ to: ids }`.
- `votes`, `averageRating` (null when 0, two decimals), `comments`: newest first, at most 200,
  `{ rating, comment, at }` (no id).
- `weight`, `inSplit` (weight > 0).

Top level: `enabled`, `startedAt` (ms, null when no visitor), `day` (1-based, null before start), the rule's
constants, `decision`, `tooLittleData` + `warning`.

### Decision rule (`LookDecisionRule`, one object, KDoc'd)

```kotlin
const val MIN_VOTES_PER_LOOK = 30
const val MAX_DAYS = 14
const val CLEARLY_WORSE_SWITCH_AWAY_POINTS = 10.0   // percentage points
const val TOO_CLOSE_RATING = 0.3
```

`decide(rows, startedAt, now)` → `Decision(status, text, winner?, keep: List<LookId>)`:

- No visitor yet → `NOT_STARTED`, "Not started: nobody has seen a look in the experiment yet."
- Running while some look has < 30 votes and fewer than 14 days have passed →
  `RUNNING`, "Not decided yet: B has 12 of 30 votes, day 4 of 14." (the look with the fewest votes;
  ties → registry order).
- Else (every look ≥ 30 votes, or ≥ 14 days elapsed). Candidates: looks with ≥ 1 vote,
  sorted by average (desc), then votes (desc), then registry order.
  - 0 candidates → `NO_VOTES`, "Ended without votes: no look was rated in 14 days."
  - 1 candidate → `WINNER`, "B wins: it is the only look anyone rated (4.2 from 12 votes)."
  - top two averages differ by ≤ 0.3 → `TOO_CLOSE`, keep both, "Too close: B (4.2) and C (4.0) are within 0.3.
    Keep the top two and run longer."
  - best's switch-away rate − runner-up's > 10 points (both known) → `WINNER` runner-up, "C wins: B is rated
    higher (4.5 vs 4.0), but 32 % switch away from it against 15 % from C."
  - else `WINNER` best, "B wins: rated 4.5 from 31 votes, ahead of C (4.0)."
- `tooLittleData` is true while the status is `NOT_STARTED` or `RUNNING`, with the warning "Too little data
  yet: these numbers can still change a lot."

### Tests (E1, JUnit + Ktor test host; each seen failing first)

- `LookExperimentConfigTest`: defaults, parsing, bad weights → default, survey URL validation, off.
- `LookDecisionRuleTest`: every branch above, the 0.3 and 10-point edges, day counting.
- `LookResultsTest`: counts, median (odd/even), switch-away rate and destinations, comments order and cap.
- `InMemoryLookExperimentStoreTest` + `ExposedLookExperimentStoreTest` (shared contract on both): first entry
  wins, vote replaced, visitor cap.
- `LookExperimentRoutesTest` (test host, real `module()`-like wiring): config on/off, events accepted (JSON
  and `text/plain` body), validation 400s, an oversized body (with and without `Content-Length`) → 413, off → 404 and nothing stored, vote replace, per-id 429, results
  shape; auth on → 401 without a credential on all four, 2xx with an API key.
- `MigrationLayoutTest` keeps passing (V3 only in `common/`); `PostgresMigrationTest` runs V3 in CI.

### Docs (E1)

`docs/DEPLOYMENT.md`: the three variables. `docs/guides/look-experiment.md`: what is collected, what is not,
where it is kept, how to switch it off, how to read the results and the rule. README links it. Compose files
pass the three variables. CHANGELOG entry. OpenAPI `documentation.yaml`: the four endpoints.

## E2 — frontend

Folder `frontend/src/looks/experiment/` (deleted with the experiment).

- `constants.ts`: `VOTE_AFTER_MS = 3 min` visible time in a look; `LATER_VISIT_DELAY_MS = 30 s`; storage key
  `vizcore-look-experiment`; flush interval 30 s.
- `storage.ts`: one JSON value: `{ anonId, entry?: { look, kind }, assigned?: LookId,
  hintDismissed, votedLooks, dismissedVote, visits: {look: n}, visibleMs: {look: ms} }`. Tolerant of
  garbage and blocked storage (works in memory for the visit).
- `assign.ts`: `pickWeighted(weights, random)` and `decideEntry({ link, choice, state, config, random })`
  → `{ entry?: {look, kind}, assigned?: LookId }` (pure; tested for fairness with a seeded random).
- `api.ts`: `getExperimentConfig()` (raw fetch with the Bearer when there is one; any failure → off),
  `sendEvents(batch, { beacon })` (sendBeacon without a token, `fetch keepalive` with one; never throws, never
  routes to /login), `sendVote()`, `getResults()` (apiClient).
- `useLookExperimentEntry(link)`: on the session route, before `LookProvider`. Reads the link once on mount.
  The config is fetched when the first session page mounts (never at module load, so login, shared views and
  other pages make no experiment request) and reused across session pages; a failed fetch is not kept, so the
  next session page asks again (e.g. after logging in). Until it answers, `assigned` is null, so a browser with a stored
  assignment shows Classic first and its assigned look once the server says the experiment is on (a short
  switch while the session itself loads); off → `assigned` stays null, so the page is exactly today's. On, and no entry yet: link → chose(link);
  a stored choice → chose(choice); else assign by weights, store it, show the hint.
- `LookProvider` gets an optional `assigned?: LookId | null`: used when there is no link, no choice in this
  visit and none stored; follows changes. Existing behaviour unchanged when absent (Classic tests stay).
- `LookExperimentLayer` (inside `LookProvider`, around the page): context `{ enabled, openVote, switched }`;
  measures: `seen` on each session page shown (keyed by the route's `sessionId`, which the layer gets as a
  prop and never sends) and on each look change; visible time per look (visibilitychange), batched with the
  rest; flush on `pagehide` and `visibilitychange → hidden` (beacon), every 30 s while non-empty, and on unmount.
  Renders the hint and the vote card, fixed in the bottom-right corner, small, above nothing important; nothing
  when off.
- `SessionHeader`: when the experiment is on, the Look section gets "Rate this look" (opens the card), and a
  menu change of look calls `switched(from, to)`. Off: the menu is exactly today's.
- `LookVoteCard`: "How do you like this look? B · Calm", five buttons 1–5 (a radio group), an optional comment
  (counter, max 500), Send, ×. After sending: "Thanks, your rating is saved. You can change it any time in
  ⋯ → Rate this look." and, when set, "Tell us more" (`surveyUrl` + `look=<id>`, new tab, `noopener`).
  Shown after 3 min visible time in the look (summed over visits), or on a later visit after 30 s; never when
  this look was voted on or the card dismissed for it; always from the menu.
- `LookHint`: "You are seeing B · Calm, one of four looks we are trying. Switch any time in ⋯ → Look." ×.
  Only for a browser whose entry is "assigned", until dismissed or the person picks a look.
- `/experiments/looks` route: the decision line in a banner, the warning, a table (look, in split, assigned,
  chose, sessions seen, median time, switch-away rate with destinations, votes, average), and the comments per
  look, newest first. Not in the nav; linked from the guide. Classic (no LookProvider).

Tests (vitest, each seen failing first): a stored assignment with a late "off" config never shows the
assigned look; a second session page in the same look counts a second seen; `assign.test.ts` (fairness over 40 000 draws within ±2 points, weights
0, sticky, link wins, choice → chose), `storage.test.ts`, `LookProvider` assigned precedence
(`look-context.test.tsx`), the layer (seen, time on hidden/pagehide via a mocked `sendBeacon`, keepalive with a
token, switch recorded only from the menu, nothing at all when off), the vote card (timing, replace, comment
limit, survey link with `look=`), the hint, the header's menu item only when on, the results page (rows,
decision line, warning). Shared view: no experiment requests (`shared.$token.test.tsx`).

## Browser check (after E2)

Backend on 18501 (`LOOK_EXPERIMENT_ENABLED=true`, `LOOK_SURVEY_URL=https://example.org/survey`, memory and then
database mode), frontend on 13501. Playwright `channel: 'chrome'`, a fresh context per visitor:

- 40 visitors → each look gets roughly a quarter; reloads keep the look (sticky); a context with
  `vizcore-look=b` already set is recorded as chose b; `?look=c` wins.
- A switch in ⋯ → Look appears in the results as from → to; a second vote replaces the first; the survey link
  carries `look=<id>`; the results page's rows and decision line match the API; the hint shows only for
  assigned first visits.
- OFF: the session page against `main` (Classic) — 0 differing pixels; no experiment write requests.
- Screenshots: the hint, the vote card and the results page in each look.

## Plan review

Codex, two rounds. Round 1: a stored assignment applied before the config answered (off would flash the
assigned look) → applied only once the server says on; seen missed when going from one session to another
(the provider outlives the keyed workspace) → keyed by session id; weights changed the rule → the rule is over
all four looks; unbounded write bodies → streaming byte limit, 413. Round 2: the config promise started at
module load (route tree imports the module, so login and shared views would ask, and a 401 before login would
stick) → started on the first session page mount, failures not cached.

## Out of scope

Feature-use tracking (the user said no); an admin reset (clear the tables, or restart in memory mode); exports.
