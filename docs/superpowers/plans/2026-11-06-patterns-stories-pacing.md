# Patterns: Stories, Pacing and Guides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the five Patterns demos what #287 gave the Channels:
- an everyday story with real-life coroutine names;
- a pace a person can follow, 10–30 s a demo in steps of 0.5–3 s;
- a "How it works" page each.

**Architecture:**
- **Demos:** `PatternScenarios` already holds the five demos, but as `suspend fun x(scope: VizScope)` that joins its root and ends in 1–2 s. It is rewritten in the shape of `ChannelScenarios`: `suspend fun runX(session: VizSession): Job`, which makes its own `session.demoScope()`, paces each step with `pause()` (`DemoPace`, `scenarios.timeScale`) and returns the root coroutine at once, without joining it.
- **Routes:** `PatternRoutes` calls the new functions. The pattern routes have always answered once the demo has ended, so they keep doing that with `.join()`, as the Flow routes did in #288. The old `runPatternScenario` helper goes.
- **Tests:** a `PatternsDemoPacingTest` mirrors `ChannelsDemoPacingTest`. The guides join `frontend/src/content/gallery/`, and `GuideLabelsTest` checks their pictures.

**Honesty fixes the rewrite needs:** the old demos did not do what their names say.
- **Retry:** each attempt was a plain child of `retry-controller`, so the first failure cancelled the controller and nothing was retried.
- **Supervisor:** the children were plain `launch`es, so `failing-service-B` cancelled its siblings, the opposite of a supervisor.
- **Circuit breaker:** the same: the first failing `downstream-call` cancelled the whole demo before the breaker could open.

The new code isolates failures where the story says so:
- **Retry and circuit breaker:** each try runs in `coroutineScope { }`, which rethrows the child's failure to the caller instead of cancelling it.
- **Supervisor and circuit breaker:** independent work runs under `supervisorScope { }`.

Failures are marked as intended (`intendedByDemo()`), so where one reaches `demoFailureHandler` it is logged in one line.

**Waiting on a channel:** a parallel branch (`feat/waits-suspended`) will show a coroutine waiting on a channel, mutex or semaphore as *suspended* instead of *active*. So:
- every `will-see.json` picture is taken at a moment when no pictured coroutine waits inside a channel `send` or `receive`. They all sit in a `pause()` or are done;
- no guide says which state the tree shows while a coroutine waits on a channel. The guides point to the Channels panel for the wait.

**Model:** #287 (Channels) and #288 (Flow). Follow their patterns, test style and wording.

**Working directory:** worktree `/Users/jirihermann/Documents/workspace-vizcore/vizcore-patterns`, branch `feat/patterns-stories-pacing`.
- Backend commands run from `backend/` with `JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home`.
- Frontend commands run from `frontend/`.

**Gates:**
- backend: `./gradlew ktlintCheck detekt test`. Restore `backend/docs/index.html` if a run rewrites it.
- frontend: `pnpm exec tsc --noEmit`, `pnpm lint`, `pnpm test`, `pnpm build`.

**Changes while building (after the Codex plan review and the first test runs):**
- **Fan-Out / Fan-In timing:** the plan left the resize times open, and the first run ended at 10.3 s, just over the 10 s floor. The times are now fixed per photo (2.5, 1.5, 2, 3, 1, 2, 2.5, 1.5, 2 s), a photo is picked every 0.6 s and the album needs 1 s a photo. The album gets the photos as 2, 1, 3, 5, 4, …, the resizers are done at about 7.8 s, and the demo ends at about 11.7 s.
- **The breaker on a test clock:** `CircuitBreaker` is a small internal class with a replaceable clock. `CircuitBreakerTest` checks open, reject, half-open, reopen and "cancellation is not a failure" without real time. The demo's own boundaries stay 1 s (scripted) away from every order.
- **Retry backoff from the recorded steps:** the test reads `load-forecast`'s recorded delays, exactly 0.75, 1.5, 3 and 1 s. The real gaps between tries are only checked broadly (at least 80 % of the wait), since at test scale 0.3 s is 30 ms.
- **A rejected order fails "at once":** this now means within 0.5 s (scripted), not 0.2 s. The main proof is that it has no `charge` child.
- **The picture rule, enforced:** `GuideLabelsTest` now also tracks channel sends and receives. A picture only matches while no pictured coroutine is inside one; a coroutine that has ended counts as waiting for nothing. The two Channels pictures that show such a wait on purpose (`channel-rendezvous`, `channel-buffered`) are listed as exceptions. The new check was seen failing on exactly those two before they were listed.
- **Logging:** the retry's caught failures never reach `demoFailureHandler`; the retry loop logs them at debug. Only the supervised failures (a backup, an order) reach the handler, and it logs them in one line.
- **Breaker open for 5.5 s, not 5 s (Codex round 1):** at 5 s order 5 came only 1 s (100 ms real at test scale) before the breaker half-opened. A delay never ends early, so the threads can only make an order later: order 6 (6 s after the opening) is always past the cooldown, and only order 5 needs room. At 5.5 s it has 1.5 s, and the test checks that room (at least 1.2 s, seen failing at 5 s).
- **The gallery tests:** `GalleryPage.test.tsx` and `HowItWorksPage.test.tsx` used Patterns as their "no guide yet" example. They now use Sync: Mutex, and the "links every card" test also covers Channels and Patterns.

---

## The five demos

Times are scripted seconds at scale 1.

### Retry with Backoff: Weather Forecast (`patterns/retry`)

The weather app loads today's forecast. The weather server is overloaded until it has answered three times: each of the first three tries takes 1.5 s (connect 0.5 s, wait 1 s) and gets HTTP 503. The app retries with exponential backoff: it waits 0.75 s, then 1.5 s, then 3 s. The fourth try gets the forecast, and the app shows it (1 s).

```
load-forecast            tries, waits 0.75 s / 1.5 s / 3 s between tries, shows the forecast
├── attempt-1            FAILED at 1.5 s   (503)
├── attempt-2            FAILED at 3.75 s
├── attempt-3            FAILED at 6.75 s
└── attempt-4            COMPLETED at 11.25 s
```

- Each try is `coroutineScope { vizAsync("attempt-n") { … }.await() }` inside a `retry(times = 4, initialDelay = 750, factor = 2)` loop that catches the failure and waits.
- The demo runs each try in its own coroutine so it shows in the tree. In an app you would call `fetchForecast()` directly; the guide says so.
- **The end:** about 12.3 s.
- **Labels:** `load-forecast`, `attempt-1` … `attempt-4`.
- **Behaviour test:** attempts 1–3 failed and attempt 4 completed. The waits between the end of a try and the start of the next are about 0.75, 1.5 and 3 s (±0.3 s), each double the one before. The root completed.

### Producer-Consumer: Coffee Bar (`patterns/producer-consumer`)

A cashier takes orders and puts them on the order rail, a channel `orders` with room for 3. A barista takes the next order from the rail and makes the drink. Each works at their own pace.

```
coffee-bar
├── cashier   takes 10 orders: 6 in the morning rush (0.7 s, then 0.6 s each), then 4 quiet ones (3 s each);
│             orders.send(order); closes the rail after the last
└── barista   orders.receive(), then makes the drink (2, 2, 1.5 ×4, then 1 s ×4)
```

- **0–0.7 s:** the barista waits for the first order.
- **The rush:** the rail holds 3 orders at 2.5 s. With order 6 the cashier has to wait for a free place, from 3.7 s until 4.7 s.
- **The quiet spell:** the barista catches up and waits for the next order, from 12.7 s until 13.7 s and from 14.7 s until 16.7 s.
- **The end:** the cashier closes the rail after order 10; the barista makes it and is done at about 17.7 s.
- The events that decide who waits are at least 1 s apart, so the waits do not depend on thread timing.
- **Labels:** `coffee-bar`, `cashier`, `barista`.
- **Behaviour test:**
  - the rail fills to 3;
  - the cashier waits at least once, for 0.5 s or more;
  - the barista waits for an order at least twice after the first, for 0.5 s or more each time;
  - the barista gets all ten orders, in order, and both complete.

### Fan-Out / Fan-In: Holiday Album (`patterns/fan-out-fan-in`)

A phone makes a holiday album from 9 photos. Three resizers share the work (fan-out); a single album coroutine collects the finished photos as they come in (fan-in).

```
make-album                 joins the resizers, then closes `resized`
├── pick-photos            sends photo-1 … photo-9 into `photos`, one every 0.5 s; closes it after the last
├── resizer-1              photos.receive(), resizes it (1–3 s, fixed per photo), resized.send(photo)
├── resizer-2              the same
├── resizer-3              the same
└── album                  resized.receive(), adds the photo to the album (0.8 s)
```

- `photos` and `resized` are buffered (`Channel.BUFFERED`), so `pick-photos` and the resizers never wait in `send`.
- Built with: a photo picked every 0.6 s, resize times 2.5, 1.5, 2, 3, 1, 2, 2.5, 1.5, 2 s, and 1 s per photo in the album (see the changes while building).
- **One photo, one resizer:** every photo goes to exactly one resizer.
- **Fan-in:** the album gets the photos in the order they are finished, not the order they were picked.
- **The end:** at about 11.7 s.
- **Labels:** `make-album`, `pick-photos`, `resizer-1` … `resizer-3`, `album`.
- **Behaviour test:**
  - each photo is received by exactly one resizer, and each resizer gets at least 2;
  - the album gets all 9, exactly once, and not in picking order;
  - all complete.

### Supervisor: Nightly Backup (`patterns/supervisor`)

At night the phone backs up photos, contacts and messages, side by side, under `supervisorScope`. The message backup fails at 4 s (the server answers HTTP 507, no space). Photos and contacts keep going; then the phone sends a report.

```
nightly-backup             supervisorScope { … }, then send-report
├── backup-photos          5 × 2.5 s, COMPLETED at 12.5 s
├── backup-contacts        4 × 2 s, COMPLETED at 8 s
├── backup-messages        2 × 2 s, then FAILED at 4 s
└── send-report            1 s, after the three: "2 of 3 backed up"
```

- **Labels:** `nightly-backup`, `backup-photos`, `backup-contacts`, `backup-messages`, `send-report`.
- **Behaviour test:**
  - `backup-messages` failed;
  - `backup-photos` and `backup-contacts` completed, both after the failure;
  - `send-report` started after the last of the three;
  - the root completed, not failed or cancelled.

### Circuit Breaker: Payment Provider (`patterns/circuit-breaker`)

A shop charges each order through a payment provider. The provider is down until 12 s. A charge to the down provider takes 1.5 s and fails (HTTP 503); a charge to the working provider takes 1 s. Orders come one at a time, the next 2 s after the last is done.

The breaker:
- **Opens** after 3 failures in a row.
- **Rejects** every order for 5.5 s once open: the order fails at once, without calling the provider.
- **Half-opens** after the 5.5 s: it lets one trial order through. If it succeeds, the breaker closes; if it fails, the breaker opens again.

```
checkout                       supervisorScope: order-1 … order-8, one at a time
├── order-1 ── charge-1        FAILED at 1.5 s
├── order-2 ── charge-2        FAILED at 5 s
├── order-3 ── charge-3        FAILED at 8.5 s: the breaker opens until 14 s
├── order-4                    FAILED at 10.5 s at once: rejected, no charge
├── order-5                    FAILED at 12.5 s at once: rejected
├── order-6 ── charge-6        14.5 s: the trial; the provider is back, COMPLETED; the breaker closes
├── order-7 ── charge-7        COMPLETED
└── order-8 ── charge-8        COMPLETED at about 21.5 s
```

- The breaker's 5.5 s and the provider's 12 s are measured with `System.nanoTime()` and scaled with `scaled()`, so they keep the demo's pace.
- The nearest order is 1 s from each boundary, so the outcome does not depend on thread timing.
- **Labels:** `checkout`, `order-1` … `order-8`, `charge-1` … `charge-3`, `charge-6` … `charge-8`.
- **Behaviour test:**
  - orders 1–3 and their charges failed;
  - orders 4 and 5 failed within 0.2 s of starting, with no charge;
  - order 6 started at least 5.5 s after `charge-3` failed, and order 5 at least 1.2 s before that;
  - orders 6–8 and their charges completed;
  - the root completed.

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/scenarios/PatternScenarios.kt` | rewrite | the five paced demos, in the shape of `ChannelScenarios` |
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/PatternRoutes.kt` | modify | call the new functions and join them; the list endpoint's names, descriptions, durations |
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/DemoCatalog.kt` | modify | the five names, descriptions and durations |
| `backend/src/main/resources/openapi/documentation.yaml` | modify | the five descriptions |
| `backend/detekt.yml` | modify | `PatternScenarios.kt` excluded from `MagicNumber`, like the other demo scripts |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/PatternsDemoPacingTest.kt` | create | the pacing and behaviour tests |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/GuideLabelsTest.kt` | modify | `start()` knows the pattern demos |
| `frontend/src/test/demo-catalog.json` | modify | kept equal to the server (`DemoCatalogTest`) |
| `frontend/src/content/gallery/patterns.ts` | create | the five guides |
| `frontend/src/content/gallery/guides.ts` | modify | `GUIDES` includes `PATTERNS_GUIDES` |
| `frontend/src/content/gallery/guides.test.ts` | modify | `GUIDED_GROUPS` gets `patterns: 5` |
| `frontend/src/content/gallery/will-see.json` | modify | five pictures |
| `frontend/src/components/gallery/GalleryPage.test.tsx`, `HowItWorksPage.test.tsx` | modify | their "no guide yet" example moves from Patterns to Sync: Mutex |
| `frontend/src/components/sessions/NewDemoSessionModal.test.tsx` | modify | the renamed Producer-Consumer |
| `CHANGELOG.md` | modify | Unreleased entry |

---

### Task 1: The pacing and behaviour tests (red)

- [ ] **Step 1: Write `PatternsDemoPacingTest`.** Copy `ChannelsDemoPacingTest`: `Run`, `run(id)` cached per id, `execute` with `DemoRuns(maxConcurrent = 0, timeScale = SCALE)` and SCALE 0.1. Add the four parameterized tests over `patternIds()`:
  - 10–30 s;
  - every delay step 0.5–3 s, at least 5 steps;
  - at least 8 moments 0.2 s apart, with no moment holding more than half the events;
  - labels equal to `storyLabels[id]`.

  Add the five behaviour tests listed with the demos above.
- [ ] **Step 2: Run it and see it fail.** `./gradlew test --tests '*PatternsDemoPacingTest*'`. Expected: it does not compile, because `PatternScenarios.runRetry` and the others don't exist.
- [ ] **Step 3: Commit (red).** `test(backend): the Patterns demos must be paced and tell their stories (red)`

### Task 2: The paced demos (green)

- [ ] **Step 1: Rewrite `PatternScenarios`** as `ChannelScenarios` is written: a KDoc per demo telling the story and times, `session.demoScope()`, `viz.vizLaunch(root) { … }`, and `pause()` for every step. Receive with `receiveCatching().getOrNull() ?: break`. Use fixed per-item times, so runs repeat.
- [ ] **Step 2: Wire it up.** `PatternRoutes` calls `PatternScenarios.runRetry(session).join()` and the others, then answers as before. Update the list endpoint, and exclude the file in `detekt.yml`.
- [ ] **Step 3: Run the tests and see them pass.** Tune a number only where the story above allows it, and note the measured durations.
- [ ] **Step 4: Commit.** `feat(backend): the Patterns demos tell an everyday story at a pace you can follow`

### Task 3: Catalog and docs

- [ ] **Step 1: Change the JSON first.** Update the five entries in `demo-catalog.json`. `./gradlew test --tests '*DemoCatalogTest*'` must FAIL.
- [ ] **Step 2: Update the server.** Update `DemoCatalog.kt` (and OpenAPI) to match, with the measured durations. `DemoCatalogTest` passes. Fix the modal test's `Producer-Consumer (Patterns)` name.
- [ ] **Step 3: Commit.** `docs(backend): the Patterns demos' catalog entries tell their stories`

### Task 4: The guides (red, then green)

- [ ] **Step 1: Widen the guard.** `GUIDED_GROUPS = { basics: 7, channels: 3, flow: 4, patterns: 5 }`. `pnpm vitest run src/content/gallery` must FAIL.
- [ ] **Step 2: Write `patterns.ts`.** `PATTERNS_GUIDES` comes from the real code: a story, a Kotlin snippet with `// n` markers, steps with times, a key idea, and a level and tags. Mistakes:
  - **Supervisor** gets `UNHANDLED_EXCEPTION`. Without `supervisorScope`, the failed message backup would cancel the photos and contacts, as in the Exception demo.
  - **Producer-Consumer** gets `SHARED_MUTABLE_STATE`. A shared list that both change is the mistake; handing the orders over a channel is the fix.
  - **Fan-Out / Fan-In** gets `LEAKED_COROUTINE`. If `make-album` never closes `resized`, the album waits for ever.
  - **Retry** and **Circuit breaker** get none, unless one honestly fits.

  Spread `PATTERNS_GUIDES` into `GUIDES`. Move the "no guide yet" examples in `GalleryPage.test.tsx` and `HowItWorksPage.test.tsx` to Sync: Mutex.
- [ ] **Step 3: Add the pictures.** Add five pictures to `will-see.json`, each at a moment where the lesson shows and no pictured coroutine is in a channel `send` or `receive`:
  - **retry**, about 6 s: attempts 1 and 2 failed, attempt 3 running;
  - **producer-consumer**, about 2 s: the cashier taking an order and the barista making a drink, both in a `pause()`;
  - **fan-out-fan-in:** all three resizers resizing and the album adding a photo, at a moment from the run;
  - **supervisor**, about 6 s: messages failed, photos and contacts still backing up;
  - **circuit-breaker**, about 11.5 s: orders 1–3 failed with their charges, order 4 rejected, `checkout` waiting for the next order.

  Add the five ids to `GuideLabelsTest.start`. Adjust a picture or its `atSeconds` to what the run shows, never the other way round.
- [ ] **Step 4: Commit.** `feat(frontend): How it works pages for the Patterns demos`

### Task 5: Gates, browser check, changelog, PR

- [ ] **Gates.** Run all of them.
- [ ] **Browser check.**
  - Start the backend on 18701 and the frontend on 13701, starting sessions at least 7 s apart.
  - Run each demo and time it.
  - Compare each picture with the live tree at its moment, through the session API.
  - View each guide in dark and light, with screenshots in the scratchpad.
- [ ] **Changelog.** Under Unreleased → Changed.
- [ ] **PR.** Up to 3 Codex review rounds on the diff; fix P1/P2 with tests; push; open the PR; do not merge.
