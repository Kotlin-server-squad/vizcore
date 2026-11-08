# Real life: Stories, Pacing and Guides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the three Real life demos what #287, #289, #296 and #302 gave the other groups:
- an everyday story, with the service-style names the demos already use (`PaymentService.processPayment` …), which the Learn tab's pipeline views (`OrderProcessingView`, `RegistrationFlowView`) read;
- a pace a person can follow: 10–30 s a demo in steps of 0.5–3 s, at least 8 separate moments, no burst with more than half the events, nothing random;
- a "How it works" page each;
- failure options (`?fail=true`, `?failEmail=true`, `?timeout=true`) that tell a true story too.

After this every gallery group has guides.

**Architecture (as #302):**
- **Demos:** the three move out of `ScenarioRunner` into a new `RealLifeScenarios` object (`ScenarioRunner` keeps the custom scenario). Each is `suspend fun runX(session: VizSession, context: CoroutineContext = EmptyCoroutineContext, <variant flag>, <result hook>): Job`: it makes its own `session.demoScope(context)`, paces every step with `pause()` (`DEMO_TIME_SCALE`) and returns its root at once. No `(a..b).random()` is left.
- **Roots:** a root's body only starts its children (and waits for them where the next step needs them); it does no work of its own. Its last children are started without a join, so the tree shows the root *Waiting for children* until they end.
- **Routes:** `ScenarioRunnerRoutes` keeps answering once the demo has ended: `RealLifeScenarios.runX(session, …).join()`. A failed demo still answers 200 (the failure is the story); `join()` does not throw.
- **Virtual time for the exact story:** `RealLifeStoriesTest` runs all six (three demos × default/variant) through `runOnVirtualTime` and checks every coroutine's parent, start, end and final state at the scripted millisecond, the result hooks and the total time, plus each picture's caption. `RealLifeDemoPacingTest` checks length and moments on virtual time and steps, names and order-free outcomes on real time (scale 0.1), for the variants too.
- **Guides:** `frontend/src/content/gallery/real-life.ts` (3 guides) registered in `guides.ts`; three pictures in `will-see.json` (checked by `GuideLabelsTest` on virtual time); `GUIDED_GROUPS` gets `'real-life': 3`.
- **Story mode:** only Basics has story anchors (`story-anchors.json`). `storyFor` returns null for a demo without anchors, so the Learn tab of a Real life session shows its pipeline view and the legend as today. No anchors are added.

## What the code did, and what is untrue about it

Structured concurrency: a `launch` child that fails cancels its parent, and the parent fails with the child's exception. `join()` never throws the child's exception; in a parent that is being cancelled it throws `CancellationException`. The old code catches that first (`catch (e: CancellationException) { throw e }`), so the `catch (e: Exception)` after it never runs; and a catch that swallowed the `CancellationException` would not stop the cancellation either: the parent is already cancelling and fails with the child's exception when its body ends.

- **User Registration, `?failEmail=true` (the known bug):** `EmailService.sendWelcome` launches `EmailService.retry-1`, which throws "SMTP server timeout". The root wraps the notifications' `join()` in `try { … } catch (e: Exception) { /* Don't fail registration if notifications fail */ }`. False: `retry-1` fails `sendWelcome`, which fails `UserService.register`; Slack and analytics are cancelled; the catch never runs. Also, there is no retry: the "retry" is the failing coroutine, and nothing tries again.
  **Fix: a real retry.** `sendWelcome` runs each try in its own child (`EmailService.attempt-1`, `-2`) inside `coroutineScope`, which hands a failed child's exception back to `sendWelcome` instead of cancelling it (as the Retry demo does since #289). `sendWelcome` catches only `SmtpTimeout`, waits 1 s and tries again; the second try sends the email and the registration completes. Had every try failed, the error would leave `sendWelcome` and fail the registration: the guide says so.
- **Order Processing, `?fail=true`:** `try { paymentJob.join() } catch (e: Exception) { logger.error("Payment failed"); throw e }` with the comment "Cancel remaining operations". The catch never runs (see above). What really happens is right, though: the declined payment fails `PaymentService.processPayment`, which fails `OrderService.processOrder`, so nothing after it starts. **Fix:** the dead try/catch goes; a plain `join()` with a comment that says what happens. The story keeps the cascade, because here it is what a shop wants: no order is saved and no confirmation sent for a declined card.
- **Report Generation, `?timeout=true`:** the analytics fetch waits 8 s and throws "timeout", which fails the report and cancels the other fetches; the catch around `join()` with "A timed-out fetch cancels this coroutine's join" is dead code too. It was no timeout either: nothing limited the call. **Fix: a real time limit that the report survives.** `AnalyticsApi.fetchMetrics` gives the analytics API at most 4 s (`withTimeoutOrNull`); the call runs in its own child, `AnalyticsApi.request`. With `?timeout=true` the API is overloaded (it would answer after 5 s): at 4 s the time limit cancels the request, `fetchMetrics` gets `null`, and the report goes out with "Analytics: not available this month". A different lesson from the other two failures: a timeout is a cancellation, and `withTimeoutOrNull` turns it into a value the caller handles.

So the three failure options show three different things: a failure that stops the work after it (order), a failure handled where it happens and retried (registration), a time limit that cancels one call and lets the rest go on (report).

**Working directory:** worktree `/Users/jirihermann/Documents/workspace-vizcore/vizcore-reallife`, branch `feat/real-life-stories`.

**Gates:** backend `./gradlew ktlintCheck detekt :test :coroutine-viz-client:test` (restore `backend/docs/index.html` if a run rewrites it); frontend `pnpm exec tsc && pnpm lint && pnpm test && pnpm build`.

---

## The three demos

Times are scripted seconds at scale 1. These demos have no locks or permits: what happens follows from `join`, `await` and the time limit, so the order of the story does not depend on which thread runs first. Still, any two moments where the tree changes for different coroutines are at least 0.5 s apart (checked by hand below), so each moment shows on its own, and the one race (the time limit against the analytics answer) is 1 s from deciding the other way.

### 1. Order Processing: Running Shoes for Anna (`order-processing`)

Anna orders running shoes. The shop's backend checks her order, asks the warehouse, charges her card, saves the order and then sends the confirmation, an SMS and an analytics event at once.

| Coroutine | Steps (s) | Runs |
|---|---|---|
| `OrderService.processOrder` (root) | starts each step and joins it; starts the three notifications and waits for children | 0–14.5 |
| `OrderValidator.validate` | 1.0 basket items, 1.0 prices, 1.5 delivery address | 0–3.5 |
| `InventoryService.checkStock` | 2.5 ask the warehouse for her size | 3.5–6.0 |
| `PaymentService.processPayment` | 1.0 contact the payment provider, 2.0 Anna confirms in her banking app, 1.0 the bank's answer | 6.0–10.0 |
| `Database.saveOrder` | 1.5 | 10.0–11.5 |
| `EmailService.sendConfirmation` | 1.5 write it, 1.5 send it | 11.5–14.5 |
| `SmsService.sendSms` | 2.0 | 11.5–13.5 |
| `AnalyticsService.trackPurchase` | 1.0 | 11.5–12.5 |

- The root is Active in each `join()` (0–11.5), then *Waiting for children* (11.5–14.5). Moments: 0, 1, 2, 3.5, 6, 7, 9, 10, 11.5, 12.5, 13, 13.5, 14.5 (13). **~14.5 s.**
- **`?fail=true`, the card is declined:** the same until 10.0, when the bank declines: `processPayment` throws `PaymentDeclined` and fails; `processOrder`, waiting in `join()`, is cancelled by it and fails with the same exception at 10.0. `saveOrder` and the notifications never start. Moments: 0, 1, 2, 3.5, 6, 7, 9, 10 (8). **10 s.** The failure is logged in one line (`intendedByDemo`), as before.

### 2. User Registration: Ben Joins a Recipe App (`user-registration`)

Ben signs up for a recipe app. The backend checks his details, makes sure the email is new, creates his account, sets up three things at once and waits for all three, then sends the welcome email, tells the team on Slack and records the sign-up.

| Coroutine | Steps (s) | Runs |
|---|---|---|
| `UserService.register` (root) | joins each step, `joinAll` the setup, starts the notifications and waits for children | 0–11.5 |
| `InputValidator.validateUserData` | 1.0 email format, 1.0 password rules | 0–2.0 |
| `UserRepository.checkExists` | 2.0 look up the email | 2.0–4.0 |
| `UserRepository.createUser` | 1.0 hash the password, 1.5 save the account | 4.0–6.5 |
| `ProfileService.createProfile` | 2.5 | 6.5–9.0 |
| `SettingsService.createDefaults` | 1.5 | 6.5–8.0 |
| `AvatarService.generateDefault` | 1.0 | 6.5–7.5 |
| `EmailService.sendWelcome` | runs each try in a child | 9.0–11.5 |
| `EmailService.attempt-1` (under `sendWelcome`) | 0.5 connect to the mail server, 2.0 send | 9.0–11.5 |
| `SlackService.notifyTeam` | 2.0 | 9.0–11.0 |
| `AnalyticsService.trackSignup` | 1.0 | 9.0–10.0 |

- The root is Active in its joins (0–9.0), then *Waiting for children* (9.0–11.5); `sendWelcome` is Active while it waits for its try. Moments: 0, 1, 2, 4, 5, 6.5, 7.5, 8, 9, 9.5, 10, 11, 11.5 (13). **~11.5 s.**
- **`?failEmail=true`, the mail server times out once:** `attempt-1` connects (9.0–9.5) and waits 2.5 s for the server, which never answers: at 12.0 it throws `SmtpTimeout` and fails. `coroutineScope` hands the exception to `sendWelcome`, which catches it, waits 1 s (12.0–13.0, Suspended) and starts `attempt-2`: 0.5 connect, 2.0 send (13.0–15.5). `sendWelcome` completes at 15.5, then the root: the registration completes; Slack and analytics are not touched by the failure. Moments: 0, 1, 2, 4, 5, 6.5, 7.5, 8, 9, 9.5, 10, 11, 12, 13, 13.5, 15.5 (16). **15.5 s.**

### 3. Report Generation: The Monthly Sales Report (`report-generation`)

On the first of the month a shop's backend builds last month's sales report for the managers: it fetches four sources at once (`async`), adds them up, writes the PDF part by part, and delivers it three ways at once.

| Coroutine | Steps (s) | Runs |
|---|---|---|
| `ReportService.generateMonthlyReport` (root) | starts four fetches (`async`) and awaits them in order; joins the aggregation and the PDF; starts the deliveries and waits for children | 0–15.0 |
| `UserApi.fetchActiveUsers` | 1.0 query, 1.5 count | 0–2.5 |
| `TransactionApi.fetchTransactions` | 2.0 first page, 2.5 second page | 0–4.5 |
| `AnalyticsApi.fetchMetrics` | `withTimeoutOrNull(4 s)` around its request; Suspended in `await` | 0–3.0 |
| `AnalyticsApi.request` (under `fetchMetrics`) | 1.0 connect, 2.0 the answer | 0–3.0 |
| `InventoryApi.fetchStockLevels` | 2.0 | 0–2.0 |
| `DataProcessor.aggregateData` | starts three calculations and waits for children | 4.5–7.0 |
| `DataProcessor.calculateRevenue` | 1.5 | 4.5–6.0 |
| `DataProcessor.calculateGrowth` | 2.0 | 4.5–6.5 |
| `DataProcessor.generateCharts` | 1.0 + 1.5 | 4.5–7.0 |
| `PdfGenerator.createReport` | runs four parts one after the other | 7.0–12.5 |
| `PdfGenerator.renderHeader` · `renderCharts` · `renderTables` · `finalize` | 1.0 · 2.0 · 1.5 · 1.0 | 7.0–8.0 · 8.0–10.0 · 10.0–11.5 · 11.5–12.5 |
| `SlackService.notifyChannel` | 1.0 | 12.5–13.5 |
| `EmailService.sendReportEmail` | 2.0 | 12.5–14.5 |
| `S3Service.uploadReport` | 2.5 | 12.5–15.0 |

- The root is Suspended in `await` while it waits for the users (0–2.5) and the transactions (2.5–4.5), Active in its joins (4.5–12.5), then *Waiting for children* (12.5–15.0). Moments: 0, 1, 2, 2.5, 3, 4.5, 5.5, 6, 6.5, 7, 8, 10, 11.5, 12.5, 13.5, 14.5, 15 (17). **15 s.** `onReport` gets the sections: users, transactions, analytics, stock.
- **`?timeout=true`, the analytics API is overloaded:** `AnalyticsApi.request` connects (0–1.0) and waits 2.0 + 2.0 s (it would answer at 5.0). At 4.0 the time limit cancels it: `request` ends Cancelled, `fetchMetrics` gets `null` and completes at 4.0. The transactions come at 4.5, and from there the run is the same; the report goes out at 15 s with "Analytics: not available this month" instead of the analytics. The time limit (4.0) is 1 s from the request's steps (3.0, 5.0) and 0.5 s from the transactions (4.5). **15 s**, the same moments plus 4.0.
- On real time (scale 0.1) the time limit (400 ms) is only 100 ms from the answer in both runs (300 ms by default, 500 ms overloaded), so the real-time tests check only what holds either way: the report completes with its four sections, and `AnalyticsApi.request` may end completed or cancelled. The exact stories are on virtual time. (Live, at scale 1, the margin is 1 s.)

### Bursts

The busiest moments are where a step ends and several children start: 11.5 s in Order (one completion, the root's body end, three children created and started and their first steps), 6.5 and 9.0 s in Registration, 0 s and 4.5 s in Report (four or three children at once). Each is far below half of a demo's events (a rough count gives at most about 25 %; the pacing test checks it).

---

## Pictures (`will-see.json`)

Checked by `GuideLabelsTest` on virtual time (the three join `VIRTUAL_TIME_DEMOS`): the tree must equal the picture for at least 0.2 s within 1 s of `atSeconds`. A = ACTIVE, S = SUSPENDED, C = COMPLETED, W = WAITING_FOR_CHILDREN.

| Demo | At | Tree | Caption |
|---|---|---|---|
| order-processing | 12.75 | `OrderService.processOrder` W; `validate`, `checkStock`, `processPayment`, `saveOrder` C; `sendConfirmation` S, `sendSms` S, `trackPurchase` C | the order is paid and saved; the analytics event is in, the SMS and the email are still being sent, and processOrder waits for them |
| user-registration | 8.5 | `UserService.register` A; `validateUserData`, `checkExists`, `createUser` C; `createProfile` S; `createDefaults`, `generateDefault` C | the account exists; the avatar and the settings are ready, the profile is not, and register waits for all three before it sends anything |
| report-generation | 3.75 | `ReportService.generateMonthlyReport` S; `fetchActiveUsers` C; `fetchTransactions` S; `fetchMetrics` C → `request` C; `fetchStockLevels` C | three of the four sources have answered; the report waits in `await` for the transactions |

---

## What the guides say

- The tree shows each coroutine's state: Created, Active, Suspended, Waiting for children, Completed, Cancelled, Failed. A coroutine in a step (`delay`) shows Suspended; one waiting in `await` too. One waiting in `join()` shows Active: the guides say "Active, waiting in join()" only where it helps, and never promise a state the tree does not show.
- Each guide tells the default run (what Run starts) and, in one sentence in the right step, what its failure option does, with the option's name (`?fail=true` …), because the Run button cannot start it.
- Mistakes: Order Processing and User Registration point to *Unhandled exception* (the registration's old code is exactly that mistake); Report Generation names none (no common mistake in the list is what its text is about).

---

## Tasks

### Task 1: stories on virtual time (TDD)
- [ ] `RealLifeStoriesTest`: one test per demo and variant, with the schedule above as exact assertions: labels, parents, each coroutine's start and end (scripted ms) and final state, the hook's result, `scriptedMillis`; the registration variant: `attempt-1` Failed at 12 000, `sendWelcome` Suspended 12 000–13 000, `attempt-2` Completed at 15 500, root Completed; the report variant: `request` Cancelled at 4 000, `fetchMetrics` Completed at 4 000, the sections without analytics; the order variant: `processPayment` and the root Failed at 10 000 with `PaymentDeclined`, no `saveOrder`.
- [ ] Captions: each picture's moment from the events (who is suspended in a delay or an await, who is active in a join).
- [ ] Run: fails (no `RealLifeScenarios`). Write `RealLifeScenarios`; run until green. Commit.

### Task 2: pacing test
- [ ] `RealLifeDemoPacingTest` as `SemaphoreDemoPacingTest`, for all six runs: on virtual time 10–30 s, ≥ 8 moments, no burst > half; on real time (scale 0.1) ≥ 10 s, every `delay` step 0.5–3 s, exact names, outcomes in any order (default runs: every coroutine completes; order variant: the payment and the root fail and nothing after them is created; registration variant: one try fails, the second completes, the root completes; report variant: the report completes).
- [ ] Seen failing against the old demos (pointing it at `ScenarioRunner` before the switch), then green. Commit.

### Task 3: routes, catalog, logs
- [ ] `ScenarioRunnerRoutes`: the three POST routes call `RealLifeScenarios.runX(…).join()`; `ScenarioRunner` keeps only the custom scenario.
- [ ] `DemoCatalog.kt` names, descriptions, durations (~15, ~12, ~15 seconds); `frontend/src/test/demo-catalog.json`; `mocks/handlers.ts`; OpenAPI summaries and descriptions (the failure options described as they now behave).
- [ ] `DemoFailureLogTest`: the order's declined card logs one INFO line and no ERROR (at scale 0.1); the registration and report variants log no ERROR at all.
- [ ] detekt: `RealLifeScenarios.kt` joins the MagicNumber excludes (each number is a step); the baseline loses the entries of the deleted functions. Commit.

### Task 4: guides and pictures
- [ ] `GuideLabelsTest`: the three join `startOnVirtualTime` / `VIRTUAL_TIME_DEMOS`; pictures into `will-see.json`; seen failing at a wrong `atSeconds`, then passing.
- [ ] `real-life.ts`, `guides.ts`; `GUIDED_GROUPS` gets `'real-life': 3`, seen failing first with the guides unregistered.
- [ ] Gallery tests: no group lacks guides now, so the "no guide yet" examples use a synthetic catalog entry in a `labs` group (`labs/new-demo`, which also has a slash for the slug test); the "links every card" test covers `real-life`. Commit.

### Task 5: gates, live check, screenshots, review, PR
- [ ] Backend and frontend gates.
- [ ] Backend on 18801, frontend on 13801; `scripts/demo-pacing-audit.py` for the three; each picture against the live tree. Screenshots: the three How it works pages, and one running demo in Classic, A, B and C, into `docs/superpowers/plans/2026-11-08-real-life-stories/`. Stop the servers.
- [ ] Codex code review; CHANGELOG (Added, and Fixed for the registration); push; PR in the style of #302; CI green. Never merge.

---

## Changes after the Codex plan review

**Round 1** (2 P2, 1 P3), all taken:
- **P2, the default report on real time:** the analytics request answers 300 ms into its 400 ms limit at scale 0.1, so a slow scheduler could cancel it. The real-time tests allow either outcome for `AnalyticsApi.request` and check only that the report completes with its four sections; the exact stories stay on virtual time.
- **P2, "the catch never sees it":** the old code's `catch (e: CancellationException)` does see the `CancellationException` that `join()` throws in a cancelled parent (and rethrows it); only the `catch (e: Exception)` after it never runs. The explanation above now says so, and the guides will not say "the catch never runs" of a cancellation catch.
- **P3:** the registration retry has 16 moments, not 15.
