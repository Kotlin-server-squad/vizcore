# Sync: Mutex: Stories, Pacing and Guides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the four Sync: Mutex demos what #287 gave the Channels and #289 the Patterns:
- an everyday story with real-life coroutine names;
- a pace a person can follow, 10–30 s a demo in steps of 0.5–3 s;
- a "How it works" page each.

**Architecture:**
- **Demos:** the four mutex demos in `SyncScenarios` are `suspend fun x(scope: VizScope)` that join their workers and end in 1–4 s. They become `suspend fun runX(session: VizSession, …): Job` in the shape of `PatternScenarios`: each makes its own `session.demoScope(context)`, paces each step with `pause()` and returns its root coroutine at once. The Semaphore and Combined demos in the same file stay as they are (the next group).
- **Routes:** `SyncScenarioRoutes` keeps answering once the demo has ended: the mutex routes call `runX(session).join()`. The two helpers take a `suspend (VizSession) -> Unit`; the Semaphore and Combined routes pass `{ SyncScenarios.x(it.demoScope()) }`, unchanged in behaviour. The list endpoint `/api/sync/scenarios` gets the new names and descriptions.
- **Waiting for a lock (since #292):** a coroutine waiting for a `VizMutex` is recorded Suspended (`waitKind = MUTEX`, `resourceId` = the mutex id) and Resumed when it gets the lock, unless its own job is cancelled. The guides say so plainly, and the pictures show lock waiters as `SUSPENDED`. The tree cannot tell a holder in a `pause()` from a waiter, so each guide points to the session's Lock contention panel, which shows who holds the lock and who is queued.
- **Virtual time for the exact story:** each demo takes `context: CoroutineContext` (and a result hook where the story has a result). `MutexStoriesTest` runs all four on `runTest` with a `StandardTestDispatcher`, as `CircuitBreakerStoryTest` does, and checks the exact story: who waits for which lock, in which order, the result, and the total scripted time. `MutexDemoPacingTest` runs them on real time (scale 0.1) and keeps to the pacing checks and order-free outcomes.
- **Guides:** `frontend/src/content/gallery/mutex.ts`, four pictures in `will-see.json`, `GUIDED_GROUPS` gets `"sync-mutex": 4`.

**Honesty fixes the rewrite needs:**
- **Cache Read-Through** read its `mutableMapOf` outside the lock while another coroutine wrote it: a data race on a `HashMap`, in the demo meant to show thread-safe caching. The fast path now reads a `ConcurrentHashMap`; the load still happens under the lock with a second check.
- The other three worked; they only ran too fast to follow (the bank's lock order compared `mutexId` strings, odd but the same for every transfer; it now orders by account name). The Deadlock demo already stopped its workers (#279); it now does it from a visible `watchdog` coroutine, and both deadlocked transfers are cancelled in one step.

**Model:** #287 (Channels) and #289 (Patterns). Follow their patterns, test style and wording.

**Working directory:** worktree `/Users/jirihermann/Documents/workspace-vizcore/vizcore-mutex`, branch `feat/mutex-stories-pacing`.
- Backend commands run from `backend/` with `JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home`.
- Frontend commands run from `frontend/`.

**Gates:**
- backend: `./gradlew ktlintCheck detekt test`. Restore `backend/docs/index.html` if a run rewrites it.
- frontend: `pnpm exec tsc --noEmit && pnpm lint && pnpm test && pnpm build`.

---

## The four demos

Times are scripted seconds at scale 1. Every event that decides who waits (a request, a release) is at least 0.5 s from every other one, so the order is the same on real time as on virtual time; the tests that depend on the order still run on virtual time only.

### Thread-Safe Counter: Festival Gates (`sync/mutex/counter`)

Three gates let visitors into a festival and keep one shared visitor count. A gate scans a ticket, then updates the count under the lock `visitor-count`: it reads the count, takes 1.5 s to show the new number on the board, and writes count + 1. Without the lock two gates could read the same count and one visitor would be lost.

```
count-visitors                  starts the three gates
├── gate-1     scans 0.5, 2.5, 2 s
├── gate-2     scans 1, 2.5, 1 s
└── gate-3     scans 2.5, 2, 2.5 s
               each: lock, read, show (1.5 s), write, unlock
```

- Who holds the lock: gate-1 0.5–2, gate-2 2–3.5, gate-3 3.5–5, gate-1 5–6.5, gate-2 6.5–8, gate-3 8–9.5, gate-1 9.5–11, gate-2 11–12.5, gate-3 12.5–14.
- Only gate-1's first visitor finds the lock free; the other 8 updates wait for it (0.5–2 s), and from 9 s to 9.5 s two gates wait at once.
- **The end:** the count is 9, at 14 s.
- **Labels:** `count-visitors`, `gate-1`, `gate-2`, `gate-3`.
- **Story test (virtual):** the lock goes 1, 2, 3, 1, 2, 3, 1, 2, 3; 8 of 9 lock requests wait, each recorded Suspended for `visitor-count` and then Resumed; never two holders at once; the count is 9; 14 s.
- **Pacing test (real):** the count is 9, at least one gate waited Suspended for the lock and resumed, all complete.

### Bank Account Transfer: Household Bills (`sync/mutex/bank-transfer`)

Anna (1000) and Ben (500) share bills. Each transfer locks both accounts, always Anna's first, then Ben's (a fixed order), checks the balance (1 s), takes the money (1 s), adds it (1 s), and unlocks; then it sends a receipt (1 s) outside the lock.

```
transfers                       starts a transfer at 1, 2, 3 and 5 s
├── pay-rent          Anna → Ben 400     holds both 1–4 s
├── pay-back-dinner   Ben → Anna 50      waits for Anna's account 2–4 s, holds 4–7 s
├── split-groceries   Anna → Ben 120     waits 3–7 s, holds 7–10 s
└── book-holiday      Ben → Anna 1200    waits 5–10 s; Ben has 970: declined at 11 s, tells Ben (1 s)
```

- `pay-back-dinner` moves money from Ben to Anna but still locks Anna's account first; that is why it never deadlocks (the Deadlock demo does it the other way).
- Because every transfer takes Anna's account first, Ben's account is always free when a transfer asks for it.
- **The end:** Anna 530, Ben 970 (the total stays 1500), at 12 s. The holiday is declined in any order of the transfers.
- **Labels:** `transfers`, `pay-rent`, `pay-back-dinner`, `split-groceries`, `book-holiday`.
- **Story test (virtual):** Anna's account goes to rent, dinner, groceries, holiday; dinner, groceries and holiday each wait Suspended for Anna's account and resume; nobody waits for Ben's; balances 530 / 970; 12 s.
- **Pacing test (real):** balances 530 / 970, at least one transfer waited Suspended, all complete.

### Cache Read-Through: Product Prices (`sync/mutex/cache`)

A shop server answers price requests from a cache that starts empty. A request reads the cache without a lock; on a miss it takes the lock `price-cache`, checks again, and only if the price is still missing loads it from the database (3 s, a child `load-espresso` / `load-grinder`). Then it renders the page (1 s).

```
shop-server            starts a request at 0.5, 1.5, 2.5, 5, 6, 7, 8 and 10.5 s
├── request-1  espresso   miss: lock, load-espresso 0.5–3.5 s
├── request-2  espresso   waits for the lock 1.5–3.5 s, then finds it cached
├── request-3  espresso   waits 2.5–3.5 s, then finds it cached
├── request-4  espresso   cached: no lock
├── request-5  grinder    miss: lock, load-grinder 6–9 s
├── request-6  espresso   cached: no lock, although the lock is held for the grinder
├── request-7  grinder    waits 8–9 s, then finds it cached
└── request-8  grinder    cached: no lock
```

- **The end:** two loads in all, one per product, at 11.5 s.
- **Labels:** `shop-server`, `request-1` … `request-8`, `load-espresso`, `load-grinder`.
- **Story test (virtual):** exactly one load per product, under requests 1 and 5; requests 2, 3 and 7 wait Suspended for the lock and resume, with no load; requests 4, 6 and 8 never ask for the lock; request 6 is served while request 5 holds it; 11.5 s.
- **Pacing test (real):** one load per product, and every request completes.

### Deadlock Demo: Two Transfers at Month End (`sync/mutex/deadlock-demo`)

At month end Anna pays Ben and Ben pays Anna at the same moment, but each transfer locks its payer's account first. `anna-pays-ben` locks Anna's account (0 s), checks it (1.5 s), then asks for Ben's at 1.5 s. `ben-pays-anna` starts 0.5 s later, locks Ben's account, checks it (1.5 s), and asks for Anna's at 2 s. Each holds what the other needs: a deadlock. Both show Suspended, waiting for a lock that is never released.

A `watchdog` checks every 2.5 s whether a transfer has finished. At 10 s neither has, so it cancels both. They end Cancelled, with no resume; cancelling them releases their locks. The watchdog then writes an incident report (1 s), and the demo ends at 11 s.

```
month-end
├── anna-pays-ben     holds Anna's account, waits for Ben's from 1.5 s; CANCELLED at 10 s
├── ben-pays-anna     holds Ben's account, waits for Anna's from 2 s;  CANCELLED at 10 s
└── watchdog          checks at 2.5, 5, 7.5, 10 s; cancels both; report (1 s)
```

- The demo runs on one thread (`Dispatchers.Default.limitedParallelism(1)`, or the test dispatcher), so the watchdog cancels both transfers before either can release its lock. On many threads, cancelling the first could hand its lock to the second before the second is cancelled, and the second would resume.
- **Labels:** `month-end`, `anna-pays-ben`, `ben-pays-anna`, `watchdog`.
- **Story test (virtual):** each transfer gets its own account and waits Suspended for the other's; neither ever gets its second lock nor resumes after that wait; both are Cancelled; both locks are released; the watchdog and `month-end` complete; 11 s.
- **Pacing test (real):** both transfers are Cancelled with no Resumed after their lock wait, and `month-end` completes.

---

## The guides

`frontend/src/content/gallery/mutex.ts`, `MUTEX_GUIDES`, written from the real code: story, Kotlin snippet with `// n` markers, steps with times, key idea, level and tags. Each says plainly that a coroutine waiting for the lock shows as Suspended in the tree, and points to the Lock contention panel for who holds the lock.

- **Counter:** mistake `SHARED_MUTABLE_STATE` (the lost update; the mutex is the fix).
- **Deadlock:** mistake `LEAKED_COROUTINE` (without the watchdog both would wait for ever, never completed or cancelled).
- **Bank, Cache:** none.

Pictures (`will-see.json`), each at a moment that lasts at least 1 s:
- **counter**, about 12 s: `gate-1` COMPLETED, `gate-2` SUSPENDED (holds the lock), `gate-3` SUSPENDED (waiting for it), root WAITING_FOR_CHILDREN;
- **bank-transfer**, about 6 s: `pay-rent` COMPLETED, `pay-back-dinner` SUSPENDED (holds both accounts), `split-groceries` and `book-holiday` SUSPENDED (waiting for Anna's account), root WAITING_FOR_CHILDREN;
- **cache**, about 7.5 s: requests 1–4 COMPLETED with `load-espresso` COMPLETED under request 1, `request-5` SUSPENDED with `load-grinder` SUSPENDED, `request-6` SUSPENDED (rendering, not waiting), root SUSPENDED;
- **deadlock-demo**, about 6 s: both transfers SUSPENDED, the watchdog SUSPENDED, root WAITING_FOR_CHILDREN.

---

## File map

| File | Change |
|---|---|
| `backend/.../scenarios/SyncScenarios.kt` | the four mutex demos rewritten as `runCounter`, `runBankTransfer`, `runCache`, `runDeadlock` |
| `backend/.../routes/SyncScenarioRoutes.kt` | helpers take `(VizSession)`; mutex routes join the demo; list endpoint text |
| `backend/.../routes/DemoCatalog.kt`, `openapi/documentation.yaml`, `frontend/src/test/demo-catalog.json` | names, descriptions, durations |
| `backend/detekt.yml` | `SyncScenarios.kt` excluded from `MagicNumber`, like the other demo scripts |
| `backend/src/test/.../scenarios/MutexDemoPacingTest.kt` | new: pacing and order-free outcomes on real time |
| `backend/src/test/.../scenarios/MutexStoriesTest.kt` | new: the exact stories on virtual time |
| `backend/src/test/.../scenarios/GuideLabelsTest.kt` | `start()` knows the four mutex demos |
| `frontend/src/content/gallery/mutex.ts`, `guides.ts`, `guides.test.ts`, `will-see.json` | the guides |
| `frontend/src/components/gallery/GalleryPage.test.tsx`, `HowItWorksPage.test.tsx` | "no guide yet" example moves to Sync: Semaphore; the link test covers `sync-mutex` |
| `CHANGELOG.md` | Changed (the stories) and Fixed (the cache race, the lock order) |

## Tasks

- [ ] **1. Red:** `MutexDemoPacingTest` and `MutexStoriesTest` against the new signatures; they fail to compile. Commit.
- [ ] **2. Green:** rewrite the four demos and the routes; `detekt.yml`. Tests pass; note the measured durations. Commit.
- [ ] **3. Catalog:** change `demo-catalog.json` first and see `DemoCatalogTest` fail; then `DemoCatalog.kt`, the list endpoint and OpenAPI. Commit.
- [ ] **4. Guides:** `GUIDED_GROUPS` gets `"sync-mutex": 4` and fails; write `mutex.ts`, the pictures, `GuideLabelsTest.start`; move the "no guide yet" examples. Commit.
- [ ] **5. Gates, browser check (backend 18701, frontend 13701), `scripts/demo-pacing-audit.py`, pictures against the live tree, each page dark and light; changelog; Codex review; PR.**
