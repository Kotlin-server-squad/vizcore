# Channels: Stories, Pacing and Guides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the three Channels demos what #282 and #286 gave the Basics:
- an everyday story with real-life coroutine names;
- a pace a person can follow, 10–30 s a demo in steps of 0.5–3 s;
- a "How it works" page each.

**Architecture:**
- **Demos:** they move out of `ScenarioRunner` into a new `ChannelScenarios` object, the way the Basics moved into `BasicScenarios`. Each step is paced with `pause()` (`DemoPace`, `scenarios.timeScale`). Each demo returns its root coroutine without joining it, as the Basics do.
- **Tests:** a `ChannelsDemoPacingTest` mirrors `BasicsDemoPacingTest`. The guides join the existing content in `frontend/src/content/gallery/`, and `GuideLabelsTest` checks their pictures.

**Tech Stack:**
- backend: Kotlin, kotlinx.coroutines, JUnit 5;
- frontend: React 19 and Vitest. There is no new page code; the guides are content.

**Model:** #282 (Basics pacing) and #286 (Basics guides). Follow their patterns, test style and wording.

**Working directory:** worktree `/Users/jirihermann/Documents/workspace-vizcore/vizcore-channels`, branch `feat/channels-stories-pacing`.
- Backend commands run from `backend/` with `JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home`.
- Frontend commands run from `frontend/`.

**Gates:**
- backend: `./gradlew ktlintCheck detekt test`. Restore `backend/docs/index.html` if a run rewrites it.
- frontend: `pnpm vitest run`, `pnpm exec tsc --noEmit`, `pnpm lint`, `pnpm build`.

**Parallel work:** a Flow PR touches the same shared files: `DemoCatalog.kt`, `demo-catalog.json`, `will-see.json`, `guides.ts`, `guides.test.ts`, `GuideLabelsTest.start` and `CHANGELOG.md`. Keep those edits small and additive.

**Changes while building (after the Codex plan review and the first test runs):**
- **Buffer size in core:** `InstrumentedChannel` updates its buffer counter after the delegate's send or receive returns. Where a send meets a receive, the two updates land in either order, so a rendezvous channel reported 1, a full buffer capacity + 1, and the size could go negative. The reported size is now clamped to what the channel can hold, with two `InstrumentedChannelTest` cases. Without this, "the print queue fills to 5" was flaky, and the Channels panel drew wrong sizes.
- **Fan-Out queue check:** the test reads real queue waits (send completed → receive completed) instead of `bufferSizes().max() >= 1`. With the size clamped, a hand-over to a waiting agent still reads 1 for a moment.
- **Fan-Out timing:** at one ticket every 0.8 s the three agents kept up, and no ticket ever waited. The demo now has a morning rush: tickets 1-6 arrive 0.5 s apart, then one every 1.2 s. Tickets 4-6 wait about 1 s each, and the demo ends at about 13 s.
- **Buffered timing:** at one job every 0.7 s the office waited only 0.24 s with job 7. It now sends a job every 0.6 s: the queue is full at 3.6 s, job 7 waits 4.2-5.5 s and job 8 waits 6.1-8 s.
- **`guides.test.ts`:** the guard keeps its list, now `guided`, so `noUnusedLocals` still passes.
- **`detekt.yml`:** `ChannelScenarios.kt` is excluded from `MagicNumber`, like `BasicScenarios.kt` (the numbers are step lengths).

---

## The three demos

Times are scripted seconds at scale 1.

### Rendezvous: Chef and Waiter (`channel-rendezvous`)

A rendezvous channel `pass` (capacity 0) connects the chef and the waiter. A plate changes hands only when both are at the pass.

```
dinner-service
├── chef     cooks 5 plates (2 s, then 1.5 s each), and hands each one over: pass.send(plate)
└── waiter   pass.receive(), then carries the plate out (1.5 s) and comes back (1.5 s)
```

- **0–2 s:** the waiter waits at the pass for the first plate (receiver suspended).
- **Plates 2–5:** the chef is ready 1.5 s before the waiter is back, so the chef waits at the pass (sender suspended).
- **Hand-overs** happen at 2, 5, 8, 11 and 14 s. The chef closes the pass after the fifth and completes at about 14 s.
- **The end:** the waiter serves the last plate, finds the pass closed, and completes at about 17 s.

**Labels:** `dinner-service`, `chef`, `waiter`.

### Buffered Channel: Print Queue (`channel-buffered`)

A buffered channel `print-queue` holds 5 jobs.

```
office-printing
├── office    sends 8 jobs, one every 0.7 s: queue.send(job); closes the queue after the last
└── printer   warms up (1.5 s + 1.5 s), then queue.receive() and prints each job (2.5 s)
```

- **The queue fills:** jobs 1–4 queue up while the printer warms up. The printer takes job 1 at 3 s.
- **Full:** by 4.2 s the queue holds 5 jobs.
- **The office has to wait:** job 7 waits for a free slot until 5.5 s, and job 8 until 8 s. Then the office closes the queue and completes.
- **The end:** the printer drains the queue and completes at about 23 s.

**Labels:** `office-printing`, `office`, `printer`.

### Fan-Out: Support Desk (`channel-fan-out`)

A buffered channel `ticket-queue` (capacity 3) feeds three agents.

```
support-desk
├── ticket-inbox   receives 12 tickets, one every 0.8 s: queue.send(ticket); closes the queue after the last
├── agent-1        queue.receive(), then handles the ticket (1–3 s, fixed per ticket)
├── agent-2        the same
└── agent-3        the same
```

- **One ticket, one agent:** every ticket goes to exactly one free agent.
- **When all three are busy,** new tickets wait in the queue.
- **The end:** at about 12–13 s.

**Labels:** `support-desk`, `ticket-inbox`, `agent-1`, `agent-2`, `agent-3`.

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/scenarios/ChannelScenarios.kt` | create | the three paced demos |
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/scenarios/ScenarioRunner.kt` | modify | remove the three old channel demos |
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt` | modify | call `ChannelScenarios` |
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/DemoCatalog.kt` | modify | the three names, descriptions and durations |
| `backend/src/main/resources/openapi/documentation.yaml` | modify | the three descriptions |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/ChannelsDemoPacingTest.kt` | create | the pacing and behaviour tests |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/GuideLabelsTest.kt` | modify | `start()` knows the channel demos |
| `frontend/src/test/demo-catalog.json` | modify | kept equal to the server (`DemoCatalogTest`) |
| `frontend/src/content/gallery/channels.ts` | create | the three guides |
| `frontend/src/content/gallery/guides.ts` | modify | `GUIDES` includes `CHANNELS_GUIDES` |
| `frontend/src/content/gallery/guides.test.ts` | modify | the guard covers Basics and Channels |
| `frontend/src/content/gallery/will-see.json` | modify | three pictures |
| `CHANGELOG.md` | modify | Unreleased entry |

---

### Task 1: The pacing and behaviour tests (red)

**Files:** create `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/ChannelsDemoPacingTest.kt`.

- [ ] **Step 1: Write the test.** Copy `BasicsDemoPacingTest`'s structure: `Run`, `run(id)` cached per id, `execute` with `DemoRuns(maxConcurrent = 0, timeScale = SCALE)` and SCALE 0.1, and the four parameterized tests over `channelIds()`:
  - 10–30 s;
  - every delay step 0.5–3 s, at least 5 steps;
  - at least 8 moments 0.2 s apart, with no moment holding more than half the events;
  - labels equal to `storyLabels[id]`.

  `start(id, session)` maps the ids to `ChannelScenarios.runRendezvous`, `runBuffered` and `runFanOut`. Add three behaviour tests, reading the channel events (`ChannelSendStarted`, `ChannelSendCompleted`, `ChannelReceiveStarted`, `ChannelReceiveCompleted`, `ChannelBufferStateChanged`), whose `coroutineId` maps to a label through `CoroutineCreated`:

```kotlin
@Test
fun `a plate changes hands only when the chef and the waiter are both at the pass`() {
    val run = run("channel-rendezvous")
    val sends = run.sendsBy("chef")          // (started, completed) pairs in scripted ms, in order
    val receives = run.receivesBy("waiter")
    assertEquals(5, sends.size)
    assertEquals(5, receives.size)
    // The waiter waits for the first plate; the chef waits for the waiter for each later one.
    assertTrue(receives.first().let { it.second - it.first } >= 1_000, "the waiter waits at the pass for the first plate")
    sends.drop(1).forEach { (started, completed) -> assertTrue(completed - started >= 1_000, "the chef waits at the pass") }
    // Each hand-over completes both sides at the same moment.
    sends.zip(receives).forEach { (s, r) -> assertTrue(abs(s.second - r.second) < 200, "hand-over at ${s.second} / ${r.second}") }
    assertTrue(run.labels.all { run.completed(it) })
}

@Test
fun `the print queue fills to 5, the office waits for a free slot, and the printer drains it`() {
    val run = run("channel-buffered")
    val sizes = run.bufferSizes()             // ChannelBufferStateChanged.currentSize in order
    assertEquals(5, sizes.max(), "the queue fills up")
    assertEquals(0, sizes.last(), "the printer empties it")
    val waited = run.sendsBy("office").count { (started, completed) -> completed - started >= 500 }
    assertTrue(waited >= 2, "the office waits for a free slot at least twice, waited $waited times")
    assertTrue(run.endedAt("office") < run.endedAt("printer") - 10_000, "the printer keeps printing long after the office is done")
    assertTrue(run.labels.all { run.completed(it) })
}

@Test
fun `each ticket goes to exactly one agent, and all three agents work`() {
    val run = run("channel-fan-out")
    val byAgent = (1..3).associate { "agent-$it" to run.receivedValuesBy("agent-$it") }
    val all = byAgent.values.flatten()
    assertEquals((1..12).map { "ticket-$it" }.sorted(), all.sorted(), "every ticket exactly once")
    byAgent.forEach { (agent, tickets) -> assertTrue(tickets.size >= 2, "$agent handled $tickets") }
    assertTrue(run.bufferSizes().max() >= 1, "tickets wait in the queue while all agents are busy")
    assertTrue(run.labels.all { run.completed(it) })
}
```

  The `Run` helpers:
  - `sendsBy(label)` and `receivesBy(label)` pair each `*Started` with the next `*Completed` of the same coroutine, in `seq` order, as scripted ms;
  - `receivedValuesBy(label)` is the `valueDescription` of that coroutine's `ChannelReceiveCompleted` events;
  - `bufferSizes()` reads `ChannelBufferStateChanged.currentSize` in `seq` order.

  The values are plain strings, so `valueDescription` is the value itself (`InstrumentedChannel` uses `toString()`).

- [ ] **Step 2: Run it and see it fail.**
`./gradlew test --tests '*ChannelsDemoPacingTest*'`. Expected: it does not compile, because `ChannelScenarios` doesn't exist.

- [ ] **Step 3: Commit (red).** `test(backend): the Channels demos must be paced and tell their stories (red)`

### Task 2: The paced demos (green)

**Files:** create `ChannelScenarios.kt`; modify `ScenarioRunner.kt` and `ScenarioRunnerRoutes.kt`.

- [ ] **Step 1: Write `ChannelScenarios`.**
  - Write it as `BasicScenarios` is written: a KDoc per demo telling the story and times, `session.demoScope()`, `viz.vizChannel(...)`, `viz.vizLaunch(root) { vizLaunch(child) {...} }`, and `pause()` for every step.
  - Receive with `receiveCatching().getOrNull() ?: break`, not `for (x in channel)`. The iterator records its receives under the coroutine id `iterator`.
  - Ticket handling times are a fixed list indexed by ticket number, so runs are repeatable: `3000, 2000, 2500, 1500, 3000, 2000, 1000, 2500, 3000, 1500, 2000, 2500`.

- [ ] **Step 2: Wire it up.** Delete `runChannelRendezvous`, `runChannelBuffered` and `runChannelFanOut` from `ScenarioRunner` (and the `Channel` import if it becomes unused). In `ScenarioRunnerRoutes`, call `ChannelScenarios.runRendezvous(session)`, `runBuffered` and `runFanOut`, as the Basics routes do: the call returns once the demo has started.

- [ ] **Step 3: Run the tests and see them pass.**
`./gradlew test --tests '*ChannelsDemoPacingTest*' --tests '*DemoCatalogTest*' --tests '*DemoRunRoutesTest*'`. Expected: PASS. Note the measured durations, which feed Task 3 and the guides.

- [ ] **Step 4: Commit.** `feat(backend): the Channels demos tell an everyday story at a pace you can follow`

### Task 3: Catalog and docs

**Files:** `DemoCatalog.kt`, `frontend/src/test/demo-catalog.json`, `documentation.yaml`.

- [ ] **Step 1: Update the catalog.** Change only the three entries:
  - **"Rendezvous: Chef and Waiter".** The chef hands each plate to the waiter at the pass, and a plate changes hands only when both are there: the waiter waits for the first plate, then the chef waits for the waiter for each next one. Duration "~17 seconds".
  - **"Buffered Channel: Print Queue".** The office sends 8 jobs faster than the printer prints. The 5-slot queue fills, the office waits for a free slot, and the printer drains the queue. Duration "~23 seconds".
  - **"Fan-Out: Support Desk".** 12 tickets arrive at a support desk with 3 agents. Each ticket goes to exactly one free agent, and when all three are busy, tickets wait in the queue. Duration "~13 seconds".

  Use the measured times. Copy the new entries into `demo-catalog.json`, which `DemoCatalogTest` checks. Update the three OpenAPI descriptions to match.

- [ ] **Step 2: Run the tests.** Run `./gradlew test --tests '*DemoCatalogTest*'` and `pnpm vitest run src/components/sessions src/components/gallery`. Expected: PASS.

- [ ] **Step 3: Commit.** `docs(backend): the Channels demos' catalog entries tell their stories`

### Task 4: The guides (red, then green)

**Files:** `guides.test.ts`, `channels.ts`, `guides.ts`, `will-see.json`, `GuideLabelsTest.kt`.

- [ ] **Step 1: Widen the guard.** In `guides.test.ts`, replace "has a guide for every Basics demo" with:

```ts
it('has a guide for every Basics and Channels demo', () => {
  const guided = catalog.scenarios.filter((s) => s.group === 'basics' || s.group === 'channels').map((s) => s.id)
  expect(guided).toHaveLength(10)
  for (const id of guided) expect(GUIDES[id], id).toBeDefined()
})
```

  Run `pnpm vitest run src/content/gallery`. Expected: FAIL, since the channel guides are missing.

- [ ] **Step 2: Write `channels.ts`.**
  - Write `CHANNELS_GUIDES: DemoGuide[]` from the real `ChannelScenarios` code, in the style of `basics.ts`: a story, a Kotlin snippet with `// n` markers, steps with times, a key idea, plus level and tags.
  - Mistakes:
    - **Rendezvous** gets `LEAKED_COROUTINE`. If the chef never closes the pass, the waiter waits forever.
    - **Fan-Out** gets `COROUTINE_EXPLOSION`. One coroutine per ticket, with no limit, is the mistake; a fixed set of workers reading a channel is the fix.
    - **Buffered** gets none, unless one honestly fits.
  - Say plainly that while a coroutine waits in `send` or `receive`, the tree shows it as *active*. The Channels panel shows the waiting send or receive and how full the buffer is.
  - Spread `CHANNELS_GUIDES` into `GUIDES` in `guides.ts`.

- [ ] **Step 3: Add the pictures.**
  - Add three pictures to `will-see.json`, each a moment where the lesson shows:
    - **rendezvous**, about 7 s: the chef at the pass (active), the waiter serving (suspended);
    - **buffered**, about 7 s: the office waiting for a free slot (active), the printer printing (suspended);
    - **fan-out**, about 3.3 s: all three agents busy and the inbox between tickets.
  - Add the three ids to `GuideLabelsTest.start`.
  - Run `./gradlew test --tests '*GuideLabelsTest*'` and `pnpm vitest run src/content/gallery`. Adjust a picture or its `atSeconds` to what the run really shows, never the other way round. Expected: PASS.

- [ ] **Step 4: Commit.** `feat(frontend): How it works pages for the Channels demos`

### Task 5: Gates, browser check, changelog, PR

- [ ] **Step 1: Gates.** Run all of them, listed at the top.

- [ ] **Step 2: Browser check.**
  - Start the backend with `PORT=18301 CORS_ALLOWED_ORIGINS=http://localhost:13301 ./gradlew :run` and the frontend with `VITE_PROXY_TARGET=http://localhost:18301 pnpm dev --port 13301`.
  - Run each demo, time it (10–30 s), and watch the tree and the Channels panel.
  - Open each guide in dark and light, and compare its picture with the live tree at the stated moment.
  - Save one screenshot per guide in the scratchpad.

- [ ] **Step 3: Changelog.** Add under Unreleased → Changed: "The Channels demos tell an everyday story at a pace you can follow, and each has a How it works page".

- [ ] **Step 4: PR.** Push and open the PR. Run up to 3 Codex review rounds, which also check the guides against `ChannelScenarios.kt`. Fix real findings with tests, get CI green, and update the description through the REST API.
