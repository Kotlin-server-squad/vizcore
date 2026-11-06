# Sync: Semaphore and Combined: Stories, Pacing and Guides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the five Sync: Semaphore demos and the one Combined demo what #296 gave the Sync: Mutex demos:
- an everyday story with real-life coroutine and resource names;
- a pace a person can follow, 10–30 s a demo in steps of 0.5–3 s, at least 8 separate moments, no burst with more than half the events;
- a "How it works" page each.

**Architecture (as #296):**
- **Demos:** the six demos in `SyncScenarios` are `suspend fun x(scope: VizScope)` that join their workers and end within about a second, some with random times (`(50..200).random()`, a 90 % payment success). They become `suspend fun runX(session: VizSession, context: CoroutineContext = EmptyCoroutineContext, …hooks): Job`: each makes its own `session.demoScope(context)`, paces every step with `pause()` (`DEMO_TIME_SCALE`) and returns its root coroutine at once. Nothing random is left.
- **Roots:** a root's body only starts its children. Where the story has arrivals, the root pauses between starts (as the Bank Transfer and Cache demos do) and shows *waiting for children* after the last start. Result hooks run in the root's `invokeOnCompletion`.
- **Routes:** `SyncScenarioRoutes` keeps answering once the demo has ended: `runX(it).join()`. `/api/sync/scenarios` gets the new names and descriptions.
- **Waits (since #292):** a coroutine waiting for a `VizSemaphore` permit is recorded Suspended (`waitKind = SEMAPHORE`, `resourceId` = the semaphore id) and Resumed when it gets the permit, also when a timeout it survives ends the wait. A coroutine waiting for a `VizMutex` likewise (`MUTEX`). The pictures show waiters as `SUSPENDED`.
- **Virtual time for the exact story:** `SemaphoreStoriesTest` runs all six through `runOnVirtualTime` (from #296) and checks who gets a permit when, who waits, who gives up, the result and the total scripted time. `SemaphoreDemoPacingTest` checks length and moments on virtual time, and steps, names and order-free outcomes on real time (scale 0.1).
- **Guides:** `frontend/src/content/gallery/semaphore.ts` (5 guides) and `combined.ts` (1), registered in `guides.ts`; six pictures in `will-see.json`; `GUIDED_GROUPS` gets `'sync-semaphore': 5, combined: 1`.

**What the guides say about the panels (the rule from Part 4, #297):**
- A coroutine waiting for a permit or a lock shows as **Suspended** in the tree, the same as one in `delay()`. The tree cannot tell a holder that is working (in a `pause`) from a waiter, so each guide points to **Evidence → Locks and permits**: per semaphore the permits in use, *n / m available* and the wait queue; per mutex the holder and its queue.
- Who holds a permit is never promised. The permit counts come from the semaphore's own snapshots (`SemaphoreStateChanged`); right after a permit is taken or given back, until the next snapshot, a holder reads "may hold a … permit · holder unknown". Where a permit is given back by a coroutine that holds none (the Bounded Buffer), the panel says "holder unknown" too. The guides say this plainly and name no holder the UI cannot know.
- That holder line is #297's text (open). On `main` today Locks and permits shows *n / m available*, the permits in use, *n waiting* and the semaphore's wait queue, with no holder lines. The guides describe the panel as #297 makes it, because the user asked for that rule; **merge #297 first**, or the holder sentence describes text that appears only once #297 lands (flagged in the PR). What the guides say about waiting (Suspended, the wait queue) is true on `main` as well.

**The wrapper fix (as #296's `VizMutex.unlock`):** `VizSemaphore.release` calls `delegate.release()` and only then sends `SemaphorePermitReleased`. A waiter resumed by that release can, on another thread, send its `SemaphorePermitAcquired` first, so the events show one more holder than there are permits for a moment (the panel's availability goes 0 → 0 → 1 instead of 0 → 1 → 0). Fix:
- each release is reported **before** its permit goes back; the delegate release is in a `finally`, so a failing event sink still frees the permit;
- a release with every permit already free throws at once (`check`), before anything is reported or untracked; the delegate is never called first;
- `newAvailablePermits` is one more than were free just before the release: the permit it frees. A waiter that takes it at once reports its acquire next, with one fewer (0 → 1 → 0). Releases at the same instant on several threads can report the same number; the state snapshot each release sends afterwards reads the delegate, and #297 reads permit counts from snapshots only. (Round 1 tried to make this number exact with a lock and waiter counts; round 2 showed that lock could deadlock and the counts go stale. Exactness was cut, by the rule for ever-smaller findings.)

Tests in `SemaphoreWaitTest` (seen failing as noted):
1. many hand-offs on `Dispatchers.Default` (1 permit, 8 workers × 400 `withPermit`): replayed in seq order, never more holders than permits. Fails on the old code (probabilistic);
2. a waiter on `Dispatchers.Unconfined` resumes inside the release, on the releasing thread: Acquired, Released, Acquired, Released, in that order. Fails on the old code every time (deterministic);
3. a failing event sink still frees the permit, and 4. a release with every permit free reports nothing. Both pass on the old code (it released first) and failed on a naive reorder (send, then release, no guard): they keep the new order safe;
5. a cancelled waiter does not take the next release: it reports 1.

**Model:** #296 (Mutex), #289 (Patterns), #287 (Channels). Follow their patterns, test style and wording.

**Working directory:** worktree `/Users/jirihermann/Documents/workspace-vizcore/vizcore-semaphore`, branch `feat/semaphore-stories-pacing`.

**Gates:** backend `./gradlew ktlintCheck detekt test` (restore `backend/docs/index.html` if a run rewrites it); frontend `pnpm exec tsc --noEmit && pnpm lint && pnpm test && pnpm build`.

---

## The six demos

Times are scripted seconds at scale 1. A permit request comes at least 0.5 s before or after any release that could decide whether it waits (a waiter handed a permit at a release is a hand-off, not a race), and every timeout fires at least 0.5 s away from any release. That is only 50 ms at the tests' scale 0.1, so on real time the tests check only what holds in any order; the exact stories, lengths, moments and pictures run on virtual time. A timeline simulator checked every schedule below before the plan was written.

### 1. Database Connection Pool: Online Shop at Lunchtime (`sync/semaphore/connection-pool`)

An online shop's backend has a pool of 3 database connections (`db-connections`, 3 permits). At lunchtime nine requests come in, one every 0.5 s. Each takes a connection, runs its query (one or two steps) and gives the connection back, then renders its page (1 s) without it.

| Request | Arrives | Query steps | Gets a connection | Gives it back | Done |
|---|---|---|---|---|---|
| `show-basket` | 0.5 | 2.5 | 0.5 | 3.0 | 4.0 |
| `search-shoes` | 1.0 | 3.0 | 1.0 | 4.0 | 5.0 |
| `order-history` | 1.5 | 2.0 + 1.5 | 1.5 | 5.0 | 6.0 |
| `track-parcel` | 2.0 | 2.5 | 3.0 (waits 1 s) | 5.5 | 6.5 |
| `write-review` | 2.5 | 2.0 | 4.0 (waits 1.5 s) | 6.0 | 7.0 |
| `checkout` | 3.0 | 1.5 + 2.0 | 5.0 (waits 2 s) | 8.5 | 9.5 |
| `wish-list` | 3.5 | 2.5 | 5.5 (waits 2 s) | 8.0 | 9.0 |
| `store-hours` | 4.0 | 1.5 | 6.0 (waits 2 s) | 7.5 | 8.5 |
| `loyalty-points` | 4.5 | 2.5 | 7.5 (waits 3 s) | 10.0 | 11.0 |

- Root `shop-backend` starts a request every 0.5 s and waits for children from 4.5 s.
- **Outcome:** never more than 3 connections in use; the first three find one free, the other six wait, each recorded Suspended (`SEMAPHORE`) and Resumed, and get a connection in the order they asked. Live run **~11 s**.

### 2. API Rate Limiter: Weather for a Trip (`sync/semaphore/rate-limiter`)

A trip planner shows the weather for eight cities. The weather service takes at most 2 calls at a time (`weather-api`, 2 permits). The planner asks for all eight at once (`vizAsync`), and `trip-page` awaits the forecasts in order and then draws the page (1.5 s).

| Call | Call time | Gets a permit | Forecast ready |
|---|---|---|---|
| `weather-rome` | 2.5 | 0 | 2.5 |
| `weather-paris` | 3.0 | 0 | 3.0 |
| `weather-vienna` | 2.0 | 2.5 | 4.5 |
| `weather-prague` | 3.0 | 3.0 | 6.0 |
| `weather-lisbon` | 2.5 | 4.5 | 7.0 |
| `weather-oslo` | 2.0 | 6.0 | 8.0 |
| `weather-split` | 3.0 | 7.0 | 10.0 |
| `weather-krakow` | 2.5 | 8.0 | 10.5 |

- Root `trip-planner` starts the eight calls and `trip-page` at once.
- `trip-page` is Suspended in `await` for each forecast in turn, then draws the page 10.5–12.0.
- **Outcome:** the burst of eight is throttled to two calls at a time; six calls wait, each Suspended (`SEMAPHORE`) then Resumed; `onPlanned` gets the eight forecasts in city order. Live run **~12 s**.
- **Common mistake:** *Too many coroutines at once* (its fix names a Semaphore). The key idea says plainly that the semaphore limits the calls, not the coroutines: for thousands of items use a fixed set of workers.

### 3. Parallel File Processor: The Tax Folder (`sync/semaphore/file-processor`)

Before a tax return goes to the accountant, an app compresses the nine files of the tax folder. The disk is fastest with two files at a time (`disk`, 2 permits). Each file is compressed in one to three steps under the permit, then uploaded (1 s) without it. The bank statements are big: they hold one permit for 7.5 s while the other permit serves four small files.

| File | Compress steps | Gets the disk | Gives it back | Done |
|---|---|---|---|---|
| `payslips.pdf` | 1.5 | 0 | 1.5 | 2.5 |
| `bank-statements.pdf` | 3.0 + 3.0 + 1.5 | 0 | 7.5 | 8.5 |
| `receipts.zip` | 2.5 | 1.5 | 4.0 | 5.0 |
| `insurance.pdf` | 1.0 | 4.0 | 5.0 | 6.0 |
| `donations.pdf` | 2.0 | 5.0 | 7.0 | 8.0 |
| `pension.pdf` | 1.5 | 7.0 | 8.5 | 9.5 |
| `childcare.pdf` | 2.5 | 7.5 | 10.0 | 11.0 |
| `invoices.zip` | 3.0 | 8.5 | 11.5 | 12.5 |
| `doctor-bills.pdf` | 2.0 | 10.0 | 12.0 | 13.0 |

- Root `tax-folder` starts all nine at once.
- **Outcome:** never more than 2 files on the disk; a permit goes to the next file the moment a file gives it back, not in rounds; seven files wait. Live run **~13 s**.

### 4. Resource Pool with Timeout: Fitting Rooms (`sync/semaphore/resource-timeout`)

A clothes shop has 2 fitting rooms (`fitting-rooms`, 2 permits). A shopper waits at most 3 s for a room: `withTimeoutOrNull(scaled(3000)) { rooms.acquire(); gotRoom = true }`, with the room released in an outer `finally` whenever `gotRoom` is set. Reading the room from the block's result would leak it: a timeout can fire just after `acquire` returns, and then `withTimeoutOrNull` returns null although the shopper holds a room. With a room, they try the clothes on (2–2.5 s) and change back (1 s), give the room back and pay at the till (1 s). Without one, they hang the clothes back and leave (1 s).

| Shopper | Arrives | Room | Outcome | Done |
|---|---|---|---|---|
| `anna` | 0.5 | 0.5–3.5 (2.0 + 1.0) | tries on | 4.5 |
| `ben` | 1.0 | 1.0–4.5 (2.5 + 1.0) | tries on | 5.5 |
| `carla` | 1.5 | 3.5–7.0 (waits 2 s) | tries on | 8.0 |
| `dan` | 2.0 | 4.5–7.5 (waits 2.5 s) | tries on | 8.5 |
| `eva` | 2.5 | gives up at 5.5 | leaves | 6.5 |
| `finn` | 3.0 | gives up at 6.0 | leaves | 7.0 |
| `gina` | 6.0 | 7.0–10.5 (waits 1 s; 2.5 + 1.0) | tries on | 11.5 |

- Root `clothes-shop` starts shoppers as they arrive (gaps 0.5–3 s) and waits for children from 6.0 s.
- The timeouts (5.5, 6.0) lie at least 0.5 s from every release (3.5, 4.5, 7.0, 7.5, 10.5).
- **Outcome:** five shoppers try on, eva and finn give up; a shopper who gives up is recorded Suspended (`SEMAPHORE`) then Resumed when the timeout ends the wait (the coroutine survives it), leaves the queue, and completes normally; `onClosed(triedOn, leftWithout)` gets `[anna, ben, carla, dan, gina]` and `[eva, finn]`. Live run **~11.5 s**.

### 5. Producer-Consumer Buffer: The Bakery Rack (`sync/semaphore/producer-consumer`)

A `baker` puts fresh loaves on a cooling rack with room for 3; a `shop-assistant` takes them off one at a time and sells them. Two semaphores count the rack: `free-places` (3 permits, all free) and `loaves-ready` (3 permits, all taken at the start, so 0 free). A small lock `rack` guards the list itself.
- The baker bakes a loaf (1 s, the sixth 1.5 s), takes a free place (waits if the rack is full), puts the loaf on the rack under `rack`, and gives a permit to `loaves-ready`.
- The assistant takes a ready loaf (waits if the rack is empty), takes it off the rack under `rack`, gives a permit back to `free-places`, and sells it (2.5 s).
- Eight loaves.

| Time | What happens | Rack |
|---|---|---|
| 0–1.0 | the assistant waits for a loaf (rack empty) | 0 |
| 1.0 | loaf 1 on the rack, taken at once | 0 |
| 2.0, 3.0 | loaves 2, 3 | 1, 2 |
| 3.5 | the assistant takes loaf 2 | 1 |
| 4.0, 5.0 | loaves 4, 5 | 2, 3 |
| 6.0 | the assistant takes loaf 3 | 2 |
| 6.5 | loaf 6 (1.5 s) | 3 |
| 7.5–8.5 | the baker waits for a free place (rack full) | 3 |
| 9.5–11.0 | the baker waits again; loaf 8 on the rack at 11.0; the baker is done | 3 |
| 13.5, 16.0, 18.5 | the assistant takes loaves 6, 7, 8 | 2, 1, 0 |
| 21.0 | loaf 8 sold; the bakery is done | 0 |

- Root `bakery` starts `baker` and `shop-assistant` at once.
- **Outcome:** loaves sold in the order baked, 1…8; never more than 3 on the rack; the baker waits twice for a free place, the assistant once for a loaf; `onSold(loaves, mostOnRack)`. Live run **~21 s**.
- **Holder unknown fits here:** the baker takes `free-places` permits and the assistant gives them back, and the other way round for `loaves-ready`, which also starts with all 3 permits taken by nobody. The guide says the panel shows "holder unknown" for these permits, and why.

### 6. E-commerce Order Processing: A Limited Sneaker Drop (`sync/combined/ecommerce`)

A web shop sells the last 3 pairs of a limited sneaker. An order checks and reserves a pair under the lock `stock` (1 s, the lock held), then pays through a payment provider that takes 2 payments at a time (`payment-gateway`, 2 permits; 2.5 s), then sends a confirmation (1 s). Ben's card is declined: his order puts the pair back under `stock` (0.5 s). An order that finds no pair tells the customer it is sold out (1 s).

| Order | Arrives | Stock lock | Payment | Outcome | Done |
|---|---|---|---|---|---|
| `order-anna` | 0.5 | 0.5–1.5 | 1.5–4.0 | bought | 5.0 |
| `order-ben` | 1.0 | 1.5–2.5 (waits 0.5 s) | 2.5–5.0 | declined; pair back 5.0–5.5 | 6.5 |
| `order-carla` | 2.0 | 2.5–3.5 (waits 0.5 s) | 4.0–6.5 (waits 0.5 s) | bought | 7.5 |
| `order-dan` | 3.0 | 3.5–4.5 (waits 0.5 s) | — | sold out (ben's pair is still reserved) | 5.5 |
| `order-eva` | 6.0 | 6.0–7.0 | 7.0–9.5 | bought (ben's pair) | 10.5 |
| `order-finn` | 9.0 | 9.0–10.0 | — | sold out | 11.0 |

- Root `sneaker-drop` starts orders as they arrive (gaps 0.5–3 s; a first draft had a 3.5 s gap before Eva) and waits for children from 9.0 s.
- **Outcome on virtual time:** three pairs sold (anna, carla, eva), ben declined, dan and finn told sold out; never more than 2 payments at once; stock never below 0 and 0 at the end; `onSettled(bought, stock)`. Live run **~11 s**.
- **On real time** Ben's pair comes back only 0.5 s (50 ms at scale 0.1) before Eva checks, so a slow scheduler could make Eva find none: the real-time test checks only that no pair is sold twice or lost (sold + left = 3), Ben never buys, and at most 2 payments run at once.
- **Common mistake:** *Shared mutable state* (checking and taking a pair without the lock would sell one pair twice).

---

## Pictures (`will-see.json`)

Each picture is checked by `GuideLabelsTest` on virtual time (all six join `VIRTUAL_TIME_DEMOS`): the tree must equal it for at least 0.2 s within 1 s of `atSeconds`. S = SUSPENDED, C = COMPLETED, W = WAITING_FOR_CHILDREN.

| Demo | At | Tree | Caption |
|---|---|---|---|
| connection-pool | 6.25 | `shop-backend` W; `show-basket`, `search-shoes`, `order-history` C; `track-parcel`, `write-review`, `checkout`, `wish-list`, `store-hours`, `loyalty-points` S | three requests done; track-parcel and write-review render without a connection; checkout, wish-list, store-hours hold the three; loyalty-points waits. All suspended: Locks and permits shows 0 / 3 available and 1 waiting |
| rate-limiter | 3.5 | `trip-planner` W; `weather-rome`, `weather-paris` C; the other six calls and `trip-page` S | rome and paris answered; vienna and prague hold the two permits; four calls wait; trip-page waits for vienna's forecast |
| file-processor | 6.5 | `tax-folder` W; `payslips.pdf`, `receipts.zip`, `insurance.pdf` C; the other six S | three small files done while bank-statements still holds a permit; donations holds the other; four files wait |
| resource-timeout | 7.25 | `clothes-shop` W; `anna`, `ben`, `eva`, `finn` C; `carla`, `dan`, `gina` S | anna and ben have paid; eva and finn gave up and left; carla pays; dan and gina are in the rooms |
| producer-consumer | 8.0 | `bakery` W; `baker` S; `shop-assistant` S | the rack is full: the baker waits for a free place while the assistant sells loaf 3. Both suspended, for different reasons |
| ecommerce | 9.25 | `sneaker-drop` W; `order-anna`, `order-ben`, `order-carla`, `order-dan` C; `order-eva`, `order-finn` S | anna and carla bought, ben was declined, dan was told sold out; eva pays for ben's returned pair; finn holds the stock lock and will find none |

---

## Tasks

### Task 1: `VizSemaphore.release` reports before it releases
- [ ] Add the `SemaphoreWaitTest` cases above; see each fail where noted.
- [ ] Fix `release()` as described; run `:coroutine-viz-core:test`.
- [ ] Commit.

### Task 2: stories on virtual time (TDD)
- [ ] `SemaphoreStoriesTest`: one test per demo, with the schedule above as exact assertions (holders in order from `SemaphorePermitAcquired`, waits from `CoroutineSuspended(SEMAPHORE)` each followed by `CoroutineResumed`, never more holders than permits replayed in seq order, the hook's result, `scriptedMillis`). Fitting rooms: eva and finn's waits end with a Resume at 5.5 / 6.0 and they complete; Bakery: the baker's two waits on `free-places`, the assistant's one on `loaves-ready`, sold 1…8; Combined: stock-lock waits for ben, carla, dan; carla's payment wait; outcomes.
- [ ] Run: fails (no such functions).
- [ ] Rewrite the six demos; run until green.
- [ ] Commit.

### Task 3: pacing test
- [ ] `SemaphoreDemoPacingTest` as `MutexDemoPacingTest`: on virtual time 10–30 s and ≥ 8 moments, no burst > half; on real time (scale 0.1) ≥ 10 s, every `delay` step 0.5–3 s, exact coroutine names, order-free outcomes (≤ permits in use replayed in seq order; every wait resumes; every semaphore ends as it started; fitting rooms: 7 shoppers, each tries on or leaves, ≤ 2 rooms; bakery: sold 1…8, rack ≤ 3; combined: sold + left = 3, ben never buys).
- [ ] Seen failing on the old demos (before Task 2's rewrite lands, or by pointing it at them), then green.
- [ ] Update `WaitsAsSuspensionDemoTest` to the new Combined API (or fold it into the story test).
- [ ] Commit.

### Task 4: routes and catalog
- [ ] `SyncScenarioRoutes`: GET and POST routes call `runX(it).join()`; `/api/sync/scenarios` names and descriptions.
- [ ] `DemoCatalog.kt`: names, descriptions, durations (~11, ~12, ~13, ~12, ~21, ~11 seconds).
- [ ] `frontend/src/test/demo-catalog.json` (kept equal by the backend's catalog test), OpenAPI summaries and descriptions.
- [ ] Commit.

### Task 5: guides and pictures
- [ ] `GuideLabelsTest`: add the six to `startOnVirtualTime` / `VIRTUAL_TIME_DEMOS`; add the pictures to `will-see.json`; see each fail first (e.g. a wrong `atSeconds`), then pass.
- [ ] `semaphore.ts`, `combined.ts`, `guides.ts`; `GUIDED_GROUPS` `'sync-semaphore': 5, combined: 1`.
- [ ] Gallery tests: the "no guide yet" example moves to Real life (`order-processing`, slug `order-processing`), the only group left without guides; the "links every card" test covers `sync-semaphore` and `combined`; the Run button test keeps Producer-Consumer under its new name.
- [ ] Commit.

### Task 6: gates, live check, review, PR
- [ ] Backend and frontend gates.
- [ ] Backend on 18801, frontend on 13801; `scripts/demo-pacing-audit.py` for the six; each picture against the live tree through the session API; guides in dark and light. Stop the servers.
- [ ] Codex code review; CHANGELOG; push; PR in the style of #296; CI green. Never merge.

---

## Changes after the Codex plan review

**Round 1** (1 P1, 5 P2), all taken:
- **P1, fitting rooms:** `withTimeoutOrNull { rooms.acquire() } != null` could leak a room taken just as the timeout fires. Now a flag set inside the block and a release in an outer `finally`. Every demo is also checked to end with each semaphore as it started.
- **P2, the wrapper's reported availability:** two releases at the same moment could both report 1, and a waiter already handed a permit still counted as waiting. First fixed with a lock and waiter counts (tests seen failing first); replaced in round 2, below.
- **P2, the fail-first claims:** the failing-sink test passes on the old code; the plan now says which tests fail where, and a deterministic hand-off test (`Dispatchers.Unconfined`) joins the probabilistic one.
- **P2, Combined on real time:** three sales and stock 0 depend on Ben's rollback beating Eva's check by 50 ms; the real-time test now checks conservation only.
- **P2, a 3.5 s root step before Eva:** Eva now comes at 6.0 s and a sixth order, Finn (9.0 s, sold out), keeps the run at 11 s. The pacing test had caught it too.
- **P2, the guides' holder wording** is #297's: kept as the user asked, with the merge order flagged (above).

**Codex's model of the event counts** (round 1): the busiest moment is t = 0 in Weather (61 of 168 events) and the Tax Folder (62 of 158), under half; every demo has 10 or more moments.

**Round 2** (1 P1, 2 P2):
- **P1:** `delegate.release()` and `session.send` ran under the new `releaseLock`; an unconfined waiter resumed inline could release another semaphore and deadlock against a second thread. **P2:** the waiter counts went stale after a hand-off whose waiter was cancelled. **P2:** the over-release guard could call the delegate's release successfully before reporting it.
- These were smaller and smaller findings from one piece of accounting, so scope was cut instead of adding more: the lock and the counts are gone. `newAvailablePermits` is one more than were free just before the release, honest about what the release frees and not exact when releases race (the snapshot after each release is). The guard now throws at once. The two tests that asserted the accounting are removed; the order tests (1, 2), the sink and guard tests and the cancelled-waiter test stay.
