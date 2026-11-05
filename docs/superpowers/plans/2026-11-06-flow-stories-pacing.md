# Flow Demos: Stories, Pacing and Guides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The four Flow demos tell an everyday story at a pace a person can follow (10–30 s, 0.5–3 s steps), and each gets a "How it works" guide.

**Architecture:**
- **Model:** this follows #282 (Basics pacing) and #286 (Basics guides).
- **Backend:** `FlowScenarios` is rewritten in the `BasicScenarios` shape. Each demo takes the session, opens its own `demoScope()`, paces its steps with `pause()`, and returns its root `Job`.
- **Routes:** the Flow routes still answer once the demo has ended, by joining that `Job`. `DemoRunRoutesTest` relies on this ("demos run one after another are never refused").
- **Frontend:** the guides go in `content/gallery/flow.ts` and their pictures in `will-see.json`. `GuideLabelsTest` checks the pictures against real runs.

**Tech Stack:** Kotlin 2.2 / Ktor 3.3 / kotlinx.coroutines Flow with the vizcore instrumented wrappers; React 19 / Vitest.

**Spec:** `docs/superpowers/specs/2026-11-05-gallery-how-it-works-design.md` (G-4: guides follow each group's story PR). Story list agreed with the user:
- Simple → stock ticker
- Operators → search as you type
- StateFlow → shopping cart
- SharedFlow → notifications to all screens

**Worktree:** `/Users/jirihermann/Documents/workspace-vizcore/vizcore-flow`, branch `feat/flow-stories-pacing`. A parallel PR does Channels; keep shared-file edits additive.

**Gates:**
- **Backend** (`backend/`): `JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home ./gradlew ktlintCheck detekt test`. Restore `backend/docs/index.html` if a test run rewrites it.
- **Frontend** (`frontend/`): `pnpm vitest run && pnpm exec tsc --noEmit && pnpm lint && pnpm build`.

---

## The four demos (scripted time, scale 1)

| Demo | Root | Children | What happens | Ends |
|---|---|---|---|---|
| `flow/simple` | `stock-ticker` | `price-chart`, `price-alert` | A cold flow, `acme-prices`, sends 6 prices, one every 1.5 s, to each collector; a collector draws each price for 0.5 s. `price-chart` collects from 0 s and `price-alert` from 4 s. The alert gets its **own** run, from the first price: cold, one run per collector. | ~16 s |
| `flow/operators` | `search-screen` | `show-results`, `type-query` | `type-query` types "ko" (a key every 0.5 s), thinks 2.5 s, types "tlin", thinks 2.5 s, then types " flow". `show-results` runs the keystrokes through `debounce(1.2 s)`, then `filter { length >= 3 }`, then `map { ask the search service, 1.5 s }`, then `take(2)`, then shows each result for 0.5 s. "ko" is debounced but filtered out; "kotlin" and "kotlin flow" are searched. | ~13.7 s |
| `flow/stateflow` | `shopping-cart` | `cart-badge`, `add-items`, `checkout-summary` | `cart-total` starts at 0. `add-items` sets 25, 40 and 52 two seconds apart, then 64 after 3 s and 70 after 2 s more. `cart-badge` collects from 0 s (0, 25, 40, 52, 64, 70). `checkout-summary` opens at 7 s and gets **52 at once**, the latest value, then 64 and 70. | ~11.5 s |
| `flow/sharedflow` | `notification-center` | `home-screen`, `chat-screen`, `settings-screen`, `push-server`, `profile-screen` | Three open screens subscribe. `push-server` waits for them, then sends 5 notifications 2.5 s apart; every screen gets each one at the same moment and draws it (0.5, 1 or 1.5 s). `profile-screen` opens at 6 s and gets only the last 3: a SharedFlow without replay gives late subscribers nothing from before. | ~14 s |

Every `pause` is 0.5–3 s. Collections end on their own (`take` / `transformWhile`), so no coroutine ends cancelled.

**Two changes after the Codex plan review** (`codex-plan-flow.md`):
- **Late subscribers are synchronised, never timed.** Otherwise, at a scale of 0.02 the margins are 20–40 ms, and a `take(n)` could miss a value and wait for ever.
  - SharedFlow: `push-server` completes a `CompletableDeferred` after notification 2. The root awaits it, pauses 1 s, then opens `profile-screen`. `push-server` waits for `subscriptionCount >= 4` before notification 3.
  - StateFlow: the root waits for `cart-total == 52`, pauses 1 s, then opens `checkout-summary`. `add-items` waits for `subscriptionCount >= 2` before its 3 s step towards 64.
  - The cart collectors stop on the final total (`transformWhile { emit(it); it != 70 }`), not after a count, because a StateFlow may conflate values.
- **A collector waiting for the next value shows as ACTIVE.** The flow wrappers emit no suspension for it. The pictures in `will-see.json` must show what a real run shows; they are taken from real runs in Task 5.

---

### Task 1: `scaled()` for real-time operators

`debounce` waits in real time, so its timeout must scale with the demo, like `pause`.

**Files:**
- Modify: `backend/src/main/kotlin/com/jh/proj/coroutineviz/scenarios/DemoPace.kt`
- Test: `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/DemoPaceTest.kt` (create, or extend if it exists)

- [ ] **Step 1: Write the failing test.** `VizScope(session, DemoPace(0.1)).scaled(1200) == 120`, and a scope without `DemoPace` returns 1200.
- [ ] **Step 2: Run it and see it fail** (unresolved `scaled`).
- [ ] **Step 3: Implement it**, and make `pause` use it:

```kotlin
/** [millis] of scripted time as real milliseconds at this scope's [DemoPace]; for operators that wait in real time. */
fun VizScope.scaled(millis: Long): Long = this.coroutineContext[DemoPace]?.scaled(millis) ?: millis

suspend fun VizScope.pause(millis: Long) = vizDelay(scaled(millis))
```

- [ ] **Step 4: Run the test and see it pass. Commit:** `feat(backend): scaled() gives real-time operators the demo's pace`.

### Task 2: Pacing test (red)

**Files:** Create `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/FlowDemoPacingTest.kt`, modelled on `BasicsDemoPacingTest`. It has the same `Run` helper, `SCALE = 0.1`, `execute`, and the same four parameterised checks: 10–30 s; steps 0.5–3 s with at least 5 of them; at least 8 moments with no burst over half; exact labels. It runs over the ids `flow/simple`, `flow/operators`, `flow/stateflow` and `flow/sharedflow`. `start(id, session)` calls `FlowScenarios.simpleFlow(session)` and so on.

Behaviour tests read the instrumented flow events. `FlowValueEmitted` has `coroutineId` and `valuePreview`, `FlowValueTransformed` has `inputValuePreview`, and `SharedFlowEmission` has `valuePreview`:

- **Simple:** `price-chart` and `price-alert` each receive all six prices in order, starting with the first (`FlowValueEmitted` by the collector's coroutine). `price-alert`'s first price arrives after `price-chart` already has at least 2, so the flow ran again for the alert. Every coroutine completes.
- **Operators:** the search service is asked only for `["kotlin", "kotlin flow"]`, from the `FlowValueTransformed` input values of the `map` stage. "ko" reached the filter and was dropped (`FlowValueFiltered` with `passed = false`, value "ko"). The "kotlin" search starts at least 1 s of scripted time after the keystroke "kotlin". Everything completes.
- **StateFlow:** `cart-badge` receives `0, 25, 40, 52, 64, 70`. `checkout-summary` receives `52, 64, 70`, and its first value comes within 0.3 s of scripted time after it starts, before any newer value is set. Everything completes.
- **SharedFlow:**
  - home, chat and settings each receive the five notifications in order;
  - for each notification, those three receive it within 0.2 s of scripted time of each other;
  - `profile-screen` receives exactly the last three;
  - everything completes.

- [ ] **Step 1: Write the test.**
- [ ] **Step 2: Run it.** `./gradlew test --tests '*FlowDemoPacingTest*'` fails to compile, because the `FlowScenarios` signatures take a `VizScope`, not a session. **Commit the red test** (`test(backend): Flow demos must run at a pace a person can follow (red)`) only once it compiles. Otherwise commit it together with Task 3.

### Task 3: Rewrite `FlowScenarios` (green)

**Files:**
- Modify: `backend/src/main/kotlin/com/jh/proj/coroutineviz/scenarios/FlowScenarios.kt` (full rewrite, as in the table above)
- Modify: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/FlowScenarioRoutes.kt`

```kotlin
object FlowScenarios {
    private val ACME_PRICES = listOf(101.2, 101.8, 100.9, 102.4, 103.1, 102.7)
    private val NOTIFICATIONS = listOf("Ana sent you a message", "Your order has shipped", "Meeting in 10 minutes", "Battery at 15%", "Ana is calling")
    private const val DEBOUNCE_MILLIS = 1200L

    suspend fun simpleFlow(session: VizSession): Job =
        coroutineScope {
            session.demoScope().vizLaunch("stock-ticker") {
                // Cold: this block runs again, from the first price, for every collector.
                val prices = vizFlow(label = "acme-prices") {
                    for (price in ACME_PRICES) {
                        pause(1500)
                        emit(price)
                    }
                }
                vizLaunch("price-chart") { prices.collect { pause(500) } }
                pause(2000)
                pause(2000)
                vizLaunch("price-alert") { prices.collect { pause(500) } }
            }
        }

    suspend fun flowOperators(session: VizSession): Job =
        coroutineScope {
            session.demoScope().vizLaunch("search-screen") {
                val keystrokes = vizSharedFlow<String>(label = "keystrokes")
                vizLaunch("show-results") {
                    vizWrap(keystrokes, label = "search-box")
                        .vizDebounce(scaled(DEBOUNCE_MILLIS))
                        .vizFilter { it.length >= 3 }
                        .vizMap { query -> pause(1500); "results for \"$query\"" }
                        .vizTake(2)
                        .collect { pause(500) }
                }
                vizLaunch("type-query") {
                    keystrokes.subscriptionCount.first { it > 0 }
                    type(keystrokes, "", "ko")
                    pause(2500)
                    type(keystrokes, "ko", "kotlin")
                    pause(2500)
                    type(keystrokes, "kotlin", "kotlin flow")
                }
            }
        }

    private suspend fun VizScope.type(keystrokes: MutableSharedFlow<String>, typed: String, until: String) {
        for (end in typed.length + 1..until.length) {
            pause(500)
            keystrokes.emit(until.take(end))
        }
    }

    suspend fun stateFlowDemo(session: VizSession): Job =
        coroutineScope {
            session.demoScope().vizLaunch("shopping-cart") {
                val total = vizStateFlow(0, label = "cart-total")
                vizLaunch("cart-badge") { total.take(6).collect { pause(500) } }
                vizLaunch("add-items") {
                    for (newTotal in listOf(25, 40, 52)) { pause(2000); total.value = newTotal }
                    pause(3000); total.value = 64
                    pause(2000); total.value = 70
                }
                pause(2000); pause(2500); pause(2500)
                vizLaunch("checkout-summary") { total.take(3).collect { pause(500) } }
            }
        }

    suspend fun sharedFlowDemo(session: VizSession): Job =
        coroutineScope {
            session.demoScope().vizLaunch("notification-center") {
                val notifications = vizSharedFlow<String>(label = "notifications")
                listOf("home-screen" to 500L, "chat-screen" to 1000L, "settings-screen" to 1500L).forEach { (screen, draw) ->
                    vizLaunch(screen) { notifications.take(NOTIFICATIONS.size).collect { pause(draw) } }
                }
                vizLaunch("push-server") {
                    notifications.subscriptionCount.first { it >= 3 }
                    NOTIFICATIONS.forEach { pause(2500); notifications.emit(it) }
                }
                pause(2000); pause(2000); pause(2000)
                vizLaunch("profile-screen") { notifications.take(3).collect { pause(500) } }
            }
        }
}
```

Format the code as ktlint requires (one statement per line, and so on), and add KDoc like `BasicScenarios`.

**Routes:** each Flow route becomes:

```kotlin
val session = call.getOrCreateSession(call.request.queryParameters["sessionId"], "flow-simple")
// Answers once the demo has ended, as it always has: a client running demos one after another never meets the demo limit.
FlowScenarios.simpleFlow(session).join()
call.respond(HttpStatusCode.OK, session.toCompletionResponse("Simple Flow completed"))
```

In the legacy `GET /api/scenarios/flow` list, update the names, descriptions and durations to match the catalog.

- [ ] **Step 1: Implement it.** Then run `./gradlew test --tests '*FlowDemoPacingTest*' --tests '*DemoRunRoutesTest*'` and expect PASS. If a behaviour assertion fails, fix the scenario, not the test, unless the test is wrong about the scenario's own design.
- [ ] **Step 2: Commit.** `feat(backend): the Flow demos tell an everyday story at a pace you can follow`.

### Task 4: Catalog

**Files:**
- `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/DemoCatalog.kt` (the 4 Flow `Demo(...)` lines)
- `frontend/src/test/demo-catalog.json` (the same 4 entries)
- `frontend/src/components/sessions/NewDemoSessionModal.test.tsx` (`'Simple Flow'` → the new name)

The new entries:
- `Demo("flow/simple", "Simple Flow: Stock Ticker", "A stock ticker: a cold flow sends six prices to each screen that collects it; a second screen gets its own run from the first price.", "~16 seconds")`
- `Demo("flow/operators", "Flow Operators: Search as You Type", "Search as you type: keystrokes are debounced, short queries filtered out, and the rest mapped to search results, in that order.", "~14 seconds")`
- `Demo("flow/stateflow", "StateFlow: Shopping Cart", "A shopping cart: the total always has a value, and a checkout page that opens later gets the latest total at once.", "~12 seconds")`
- `Demo("flow/sharedflow", "SharedFlow: Notifications", "Notifications: every open screen gets each one at the same moment; a screen opened later gets only what comes after.", "~14 seconds")`

- [ ] **Step 1: Change the JSON first.** Then run `./gradlew test --tests '*DemoCatalogTest*'`. It must FAIL, because the catalog and its frontend copy differ.
- [ ] **Step 2: Change `DemoCatalog.kt`.** `DemoCatalogTest` now passes, and so does the modal vitest after its name update.
- [ ] **Step 3: Commit.** `feat: the Flow demos' new names and stories in the catalog`.

### Task 5: Guides (frontend)

**Files:**
- Create: `frontend/src/content/gallery/flow.ts`, exporting `FLOW_GUIDES: DemoGuide[]`
- Modify: `frontend/src/content/gallery/guides.ts`, which becomes `[...BASICS_GUIDES, ...FLOW_GUIDES]`
- Modify: `frontend/src/content/gallery/guides.test.ts`, to require a guide for every demo in the guided groups `['basics', 'flow']`, with the Flow count being 4
- Modify: `frontend/src/content/gallery/will-see.json`, adding 4 entries
- Modify: `frontend/src/components/gallery/HowItWorksPage.test.tsx`, where the no-guide example `flow-simple` / `flow/simple` becomes `sync-mutex-counter` / `sync/mutex/counter`

- [ ] **Step 1: Write the failing guard.** Extend `guides.test.ts` to the `flow` group and run `pnpm vitest run src/content/gallery`. It FAILS: there is no guide for `flow/simple`.
- [ ] **Step 2: Write `flow.ts`.** Write it from the real code in Task 3, in the style of `basics.ts`: plain short sentences, `// n` markers 1…n, and durations written `// takes 1.5 s`.

| Guide | Level | Tags | Mistake |
|---|---|---|---|
| Simple | beginner | `cold flow`, `flow { }`, `collect` | none |
| Operators | intermediate | `debounce`, `filter`, `map`, `take` | none |
| StateFlow | intermediate | `StateFlow`, `latest value` | `SHARED_MUTABLE_STATE`: a plain `var total` changed by several coroutines instead of one state holder |
| SharedFlow | intermediate | `SharedFlow`, `broadcast`, `replay` | `LEAKED_COROUTINE`: collecting a SharedFlow never ends on its own, so a screen that is not cancelled with its scope leaks |

  Each guide says that the Flow views (the Flow panel in the session) show the values themselves, while the tree shows the coroutines.
- [ ] **Step 3: Add the `will-see.json` pictures.** One per demo, at a moment where the tree shape is telling:
  - **Simple, at about 6 s:** `stock-ticker` is waiting for children, `price-chart` and `price-alert` both suspended.
  - **Operators, at about 7.5 s:** `type-query` is suspended (thinking), `show-results` suspended (asking the search service).
  - **StateFlow, at about 7.5 s:** `checkout-summary` is suspended (drawing 52), `add-items` is suspended, and `cart-badge` is suspended.
  - **SharedFlow, at about 6.5 s:** all 4 screens and `push-server` are suspended.

  Choose the exact moment from a real run. A quick way is to print the tree states from `FlowDemoPacingTest`'s events, then adjust `atSeconds` so that `GuideLabelsTest` passes.
- [ ] **Step 4: Run the frontend tests.** `pnpm vitest run` should PASS.
- [ ] **Step 5: Commit.** `feat(frontend): How it works guides for the Flow demos`.

### Task 6: `GuideLabelsTest` covers Flow

**Files:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/GuideLabelsTest.kt`. In `start()`, add the 4 Flow ids, mapped to the `FlowScenarios` calls.

- [ ] **Step 1: Run it before adding the ids.** `./gradlew test --tests '*GuideLabelsTest*'` FAILS with "add it to GuideLabelsTest.start".
- [ ] **Step 2: Add the ids and run it again.** Expect PASS. If a picture does not match, fix `will-see.json` against the real run.
- [ ] **Step 3: Commit.** `test(backend): GuideLabelsTest checks the Flow pictures`.

### Task 7: Gates, browser check, changelog, PR

- [ ] **Gates.** Run all the backend and frontend gates.
- [ ] **Browser check.**
  - Start the backend with `PORT=18311 CORS_ALLOWED_ORIGINS=http://localhost:13311 ./gradlew :run`, and the frontend with `VITE_PROXY_TARGET=http://localhost:18311 pnpm dev --port 13311 --strictPort`.
  - Run each Flow demo from the Gallery and check that it lasts 10–30 s, that the tree fills step by step, and that the Flow panel shows the values.
  - Open each guide in dark and light, and compare its picture with the live tree at its moment.
  - Save one screenshot per guide in the scratchpad.
- [ ] **Changelog.** Under Unreleased → Changed, add: "The Flow demos tell an everyday story at a pace you can follow: a stock ticker, search as you type, a shopping cart and notifications, 12–16 s each, with How it works pages".
- [ ] **Push and open the PR.** End the body with the attribution line. Run up to 3 Codex review rounds, fix the real findings with tests, wait for green CI, and update the description through the REST API.
