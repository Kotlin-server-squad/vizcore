# Changelog

All notable changes to this project will be documented in this file.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- Session page, trimmed history and shared links (Part 8b): a replay that does not start at the session's beginning says so ("This replay starts at event 21: earlier events are not in it, so it may be incomplete"), and the coroutines created before it come from the server's summaries at the cursor; one whose state there cannot be rebuilt reads "State unknown" (its own chip, never counted as active). Selecting a coroutine whose early events were trimmed shows what its summary keeps (where it was created and last suspended, its last thread, its failure) and no made-up duration. In a shared link the Inspector now works: it reads through the link, bound to the moment the link was loaded; if the session has moved on it says so and Reload loads the link again
- Session page, replay (Part 8a of the session dock): every part shows the replay's moment. The slider moves in seconds ("4.2 s / 12.0 s · event 37 of 120"; dragging seeks to the last event at or before that time, ← → step one event). The Inspector is no longer paused: click a coroutine in replay and it shows its timing, where it is suspended, where it last ran and its events at the cursor, read from the server at that event (`atSeq`), never a later one; a coroutine selected before it exists says "not created yet at this moment". Evidence's Dispatchers follows the cursor too, and with nothing selected the Inspector shows the session's metrics at that moment (active and dispatcher load as the tree shows them, peak and throughput from the events up to it; long-lived is not evaluated at a moment). In a shared link these parts say they show the link as it was loaded. Works in all four looks
- SDK: the session can now judge an attached app. Each poll tells the backend when it looked (at most once a second), so a coroutine running for over 10 s reads as possibly blocked; `stop()` sends the remaining events and a closing message within 5 s (`stop(timeoutMillis)`), so a clean shutdown no longer reads "events may have been lost"; every coroutine says whether it is the app's or vizcore's own connection (Ktor's `nonce-generator`), or unknown when DebugProbes run without creation stacks; suspension points say whether the frame was observed or is the creation site; a coroutine launched through `VizScope` into the client's session is reported once, and its children keep it as their parent. Servers without session health are not sent the poll messages
- A Look switch on the session page, for a trial of three new looks next to today's (Classic, still the default): the "⋯" menu's "Look · trial" section offers Classic, A · Compact, B · Calm and C · Bold, and a link such as `/sessions/<id>?look=b` picks one too. The choice is remembered in the browser; an unknown look reads as Classic. Only the session page changes; other pages, exports and recorded replays stay Classic. A, B and C carry only their colours, type, density and spacing so far; their layouts follow, and the looks that lose the trial are deleted
- Look A · Compact (trial, `?look=a`) now has its own layout, from its IDE sketch: the session header and the main view's toolbar are flat bars edge to edge, the tree sits on the page with square 1 px cards, the state chips read as a legend, and the dock is flat with underlined tabs and a borderless Inspector. Same data and buttons as Classic, which is unchanged
- Look B · Calm (trial, `?look=b`) now looks like its sketch on the session page: a white header band with a plain sentence under the title (how the run is going and what is waiting, e.g. "Running for at least 6.0 s. 2 coroutines are waiting for account-anna."), the tree on the page instead of in a box, soft pill controls, calm graph cards (name, state pill and what it waits for or holds; the root and its first row of children readable in the first view) and a white dock with soft tabs. Classic is unchanged
- Look C · Bold (trial, `?look=c`) gets its own layout, from its sketch: pill buttons and dock tabs, Space Grotesk headings and graph states, graph cards without internal ids, and a "Right now" callout with a state bar above the main view, saying by name what holds and what waits. Its dock starts at 17 % of the window (a look may now set its own start; the default stays 40 %, and a size you gave the dock still wins), so the root and its first row of children fit the first view. Presentation only; Classic is unchanged
- "New demo session" offers all 32 demos, grouped: real life, basics, channels, Flow, patterns, Mutex, Semaphore and combined. Flow, patterns and the Mutex and Semaphore demos were unreachable since the gallery was retired. `GET /api/scenarios` lists every one with its `group`, `groupLabel` and `method`; a demo refused by the demo limit reads "Server busy" instead of a server failure
- `MAX_CONCURRENT_DEMOS` (default 3): at most this many demos run on the server at once, across all clients. A demo holds its slot until its coroutines end; one started while every slot is taken gets `503` with `Retry-After`
- `MAX_DEMO_RUN_SECONDS` (default 60): a demo still running after this many seconds is cancelled and frees its slot
- The mutex deadlock demo stops its two deadlocked workers after showing the deadlock, instead of leaving them waiting for ever
- A `VizMutex` waiter cancelled while queued leaves the queue (a `MutexQueueChanged` without it), instead of being shown waiting for ever
- Database mode: an event sent into a session that was deleted meanwhile is dropped instead of failing with a foreign-key error
- `DEMO_TIME_SCALE` (default 1): how fast the paced demos run; each scripted step takes its time times this, so `0.1` runs them ten times faster (at least `0.01`). Both Compose files pass it on, as they now do `MAX_CONCURRENT_DEMOS` and `MAX_DEMO_RUN_SECONDS`. The tests and the smoke test use a small scale
- Light mode: a ☾ / ☀ switch in the top bar changes between the dark and the new light theme; dark stays the default and the choice is remembered in the browser. Exported PNG and SVG, and recorded replays, keep the dark look
- The Gallery is back as a page (`/gallery`), linked with Sessions in the top bar: every demo by topic, in tabs that follow the server's groups and stay in the URL (`?group=basics`), each with Run. A "Common mistakes" tab explains the 10 mistakes the anti-pattern detector knows, what goes wrong and what to do instead; the five it detects today are marked, and their demos are still to come. Links such as `/gallery?group=mistakes#LEAKED_COROUTINE` open one mistake
- "How it works" pages for the 7 Basics demos (`/gallery/nested`, `/gallery/deep-nesting`, …), linked from their Gallery cards: the story, the code with numbered steps, what the session tree shows at one moment of the run, the key idea and a related common mistake, with Run this example. A backend test runs each demo and checks that every coroutine the page pictures is really created at the depth it is drawn at
- "How it works" pages for the 4 Flow demos (`/gallery/flow-simple`, `/gallery/flow-operators`, `/gallery/flow-stateflow`, `/gallery/flow-sharedflow`), checked against real runs like the Basics pages

#### Production Hardening
- Session TTL with automatic cleanup via `RetentionPolicy` (configurable via `SESSION_MAX_AGE_MS`, `SESSION_MAX_COUNT`)
- Graceful shutdown hook that stops retention policy and clears sessions
- Global error handler via Ktor StatusPages (400/404/500 JSON responses, no stack traces exposed)
- Security headers: `X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`, `Referrer-Policy`, `Permissions-Policy`
- Rate limiting: 60 req/min per IP for API routes, 10 req/min for session creation
- Frontend meta tags: description, theme-color, Open Graph, Twitter Card
- SVG favicon for coroutine visualization branding
- Docker production hardening: resource limits (768MB/1CPU backend, 128MB/0.5CPU frontend), log rotation, health check fix, image pinning, JVM tuning
- Deployment guide (`docs/DEPLOYMENT.md`)
- ADR 029: Production Hardening

#### Documentation & Planning
- Documentation reorganization: `docs/guides/`, `docs/planning/`, `docs/topics/`, `docs/adr/`
- User Guide (`docs/guides/USER_GUIDE.md`)
- API Reference (`docs/guides/API_REFERENCE.md`)
- Deployment Runbook (`docs/guides/DEPLOYMENT.md`)
- CONTRIBUTING.md with development workflow and conventions
- CHANGELOG.md with version history
- Consolidated ROADMAP.md replacing 4 outdated planning docs (was 605 unchecked items, now accurate)
- ADR-015: Persistence Strategy (Exposed + H2/PostgreSQL)
- ADR-016: Authentication Architecture (API key + JWT + tenant isolation)
- ADR-017: Replay Engine Design (frontend time-travel)
- ADR-018: Export System Design (PNG/SVG/video)
- ADR-019: Session Sharing (token-based read-only access)
- ADR-020: Performance & Scaling (sampling, metrics, bounded stores)
- ADR-021: SDK Distribution (GitHub Packages, CLI tool)
- ADR-022: Frontend Testing Strategy (Playwright, Storybook, Chromatic)
- Implementation analysis for all remaining features (`docs/planning/IMPLEMENTATION_ANALYSIS.md`)
- GitHub Issues created for all remaining work (~50 issues across 13 epics)

#### Features (prior work)
- IntelliJ plugin skeleton (`intellij-plugin/`)
- Documentation site (`docs-site/`)
- Flow scenarios: simple, operators, stateflow, sharedflow
- Pattern scenarios: retry, producer-consumer, fan-out/fan-in, supervisor, circuit breaker
- Sync scenario routes: mutex, semaphore, combined
- Validation dashboard with score, severity filtering, and category grouping
- Actor pattern visualization panel
- Select expression visualization panel
- Exception propagation overlay
- Virtualized event list for performance
- Keyboard navigation and skip-navigation accessibility
- Animation system with framer-motion variants and reduced-motion support
- Core library extraction (`backend/coroutine-viz-core/`)
- GitHub Actions CI for core and plugin
- Dependabot configuration
- PR template and issue templates
- CODEOWNERS file

### Changed
- The seven Basics demos tell an everyday story and run at a pace you can follow: 12-16 s each, in steps of 0.5-3 s, where most used to end within a second and showed a finished tree straight away. Their coroutines are named after the story: Nested is a product page (`load-product-page`, `fetch-price`, `fetch-review-photos`), Parallel a dashboard, Cancellation a user closing a screen, Deep Nesting a search indexer walking folders, Mixed a checkout, Exception one backend call failing (`fetch-loyalty-points` answers HTTP 500), and Dispatchers photo thumbnails downloaded on IO and resized on Default
- The three Channels demos tell an everyday story at a pace you can follow, 13-23 s each, where they used to end within two seconds, and each has a How it works page (`/gallery/channel-rendezvous`, …). Rendezvous is a chef handing plates to a waiter (`chef`, `waiter`) who must both be at the pass; Buffered a print queue with 5 slots that fills while the `office` sends faster than the `printer` prints; Fan-Out a support desk where 12 tickets go to 3 agents (`agent-1` … `agent-3`), each to exactly one, and wait in the queue in the morning rush
- The four Flow demos tell an everyday story at a pace you can follow, 12-16 s each in steps of 0.5-3 s: Simple Flow is a stock ticker (a cold flow runs again, from the first price, for a screen that collects later), Operators is search as you type (`debounce`, `filter`, `map`, stopping after the results for the whole query), StateFlow a shopping cart (a checkout opened later gets the latest total at once) and SharedFlow notifications to every open screen (a screen opened later gets only what follows). They still answer once the demo has ended
- The five Patterns demos tell an everyday story at a pace you can follow, 12-22 s each in steps of 0.5-3 s, where they used to end within two seconds, and each has a How it works page (`/gallery/patterns-retry`, …). Retry is a weather app retrying an overloaded server after 0.75, 1.5 and 3 s (`attempt-1` … `attempt-4`); Producer-Consumer a coffee bar where the `cashier` fills an order rail with room for 3 and the `barista` empties it; Fan-Out / Fan-In a holiday album, 9 photos shared by 3 resizers and collected by one `album`; Supervisor a nightly backup whose message backup fails while photos and contacts carry on; Circuit Breaker a shop whose payment provider is down, so the breaker opens after 3 failed charges, rejects orders at once for 5.5 s, then lets one trial through and closes
- The four Sync: Mutex demos tell an everyday story at a pace you can follow, 11-14 s each in steps of 0.5-3 s, where they used to end within one to four seconds, and each has a How it works page (`/gallery/sync-mutex-counter`, …). Thread-Safe Counter is three festival gates counting visitors under one lock (`gate-1` … `gate-3`), so no visitor is lost; Bank Transfer a household's bills, every transfer locking Anna's account first, so they queue instead of deadlocking and the one Ben cannot afford is declined; Cache Read-Through a shop server where eight requests for two prices cause one database load each (`load-espresso`, `load-grinder`); Deadlock two transfers at month end that each hold one account and wait for the other's until a `watchdog` cancels both. The guides say that a coroutine waiting for a lock shows as suspended, and their pictures show it. The routes still answer once the demo has ended
- The five Sync: Semaphore demos and the Combined demo tell an everyday story at a pace you can follow, 11-21 s each in steps of 0.5-3 s, where they used to end within about a second (some with random times), and each has a How it works page (`/gallery/sync-semaphore-connection-pool`, …). Connection Pool is an online shop at lunchtime, nine requests sharing 3 database connections (`show-basket` … `loyalty-points`); Rate Limiter a trip planner asking a weather service that takes two calls at a time for eight forecasts (`weather-rome` … `weather-krakow`, `trip-page`); File Processor a tax folder compressed two files at a time while the big bank statements hold one permit; Resource Timeout two fitting rooms, where shoppers wait at most 3 s and Eva and Finn give up; Producer-Consumer a bakery rack with room for 3 loaves, counted by two semaphores (`baker`, `shop-assistant`); Combined a limited sneaker drop, the stock under a lock and payments two at a time, where a declined card puts its pair back. The guides say that a coroutine waiting for a permit or a lock shows as suspended, and name no permit holder the panel cannot know. The routes still answer once the demo has ended
- License: the whole repository is Apache License 2.0, with `LICENSE` and `NOTICE` at the root; the frontend was MIT before
- Backend Dockerfile `ENTRYPOINT` now supports `$JAVA_OPTS` environment variable
- Docker prod health check uses `/health` instead of `/`
- Nginx config: added security headers, static asset caching, disabled `server_tokens`
- Consolidated Claude Code skills to monorepo root
- Restored separate tabs for Events/Threads panels
- Excluded `dist/` from ESLint
- Retired outdated planning docs (TODO_STEP_BY_STEP, BACKEND/FRONTEND_IMPLEMENTATION_TASKS, IMPLEMENTATION_TODOS)
- Purged `docs/old/` (11 archived files), empty stubs, and duplicate business analysis

### Removed
- Duplicate `SessionManager`, `VizSession`, and `EventStore` in app module (now uses core library versions with `createdAtMs`, `maxEvents`, bounded stores)

### Fixed
- A coroutine waiting for a `VizMutex`, a `VizSemaphore` permit, or a channel send or receive (`send`, `receive`, `receiveCatching`, `for (x in channel)`) is shown Suspended instead of Active. The wrappers try first and, only when they must wait, send `CoroutineSuspended` with the new optional `waitKind` and `resourceId`, then `CoroutineResumed` when the wait ends, also when a closed channel or a timeout ends it. `select` clauses are not covered yet. The coroutine timeline API (`GET /api/sessions/{id}/coroutines/{coroutineId}/timeline`) carries both fields, and the Channels "How it works" guides say a waiting chef or office shows as suspended
- The Cache Read-Through demo read its cache, a plain `HashMap`, outside the lock while another coroutine wrote it: a data race in the demo meant to show thread-safe caching. The lock-free read now goes to a `ConcurrentHashMap`
- `VizMutex` reports `MutexUnlocked` before it releases the lock. It reported it after, so on several threads the next holder's `MutexLockAcquired` could come first, and the late unlock then showed the mutex free, with no holder, in the Lock contention panel while the next coroutine held it
- `VizSemaphore` reports `SemaphorePermitReleased` before it gives the permit back. It reported it after, so on several threads the waiter that got the permit could report `SemaphorePermitAcquired` first, and the events showed one more holder than there are permits. A permit is given back even if reporting it fails, and a release with every permit free fails before anything is reported
- The Resource Timeout demo could keep a permit for ever: a timeout that fired just after `acquire()` made `withTimeoutOrNull` return null although the permit was taken, and the demo then never released it
- `VizSemaphore` counts the permits each coroutine holds. A permit released by a coroutine that holds none is reported with `holderUnknown` instead of being charged to an arbitrary holder, and `SemaphoreStateChanged` gains `activeHolderPermits` and `unknownHolderPermits`
- `VizSemaphore.release` and `tryAcquire` name the calling coroutine; they could not find it before. A `for` loop over a channel names its coroutine instead of `iterator`
- `CoroutineFailed` carries the exception's stack trace (up to 50 frames); it was always empty
- The Retry, Supervisor and Circuit Breaker demos do what their names say: before, the first failure cancelled the retry before it could retry, cancelled the supervisor's other children, and cancelled the circuit breaker's whole run before it could open
- A channel never reports more elements than it can hold: where a send met a receive, the two updates of the buffer counter could land in either order, so a rendezvous channel showed a filled slot, a full buffer one element more than its capacity, or the size went negative
- `InstrumentedStateFlow.subscriptionCount` follows its collectors; it returned a snapshot taken when it was read, so waiting on it for a new collector never ended
- ESLint no longer lints build output in `dist/`
- Tab navigation restored for Events and Threads panels

## [0.1.0] - 2024-11-20

### Added
- Initial monorepo setup (merged `vizcor-fe` and `vizcor-be`)
- Event-sourced backend with Ktor 3.3 and SSE streaming
- 48+ event types across coroutine, job, flow, channel, dispatcher, sync, actor, select packages
- Session management with EventBus, EventStore, RuntimeSnapshot
- Instrumentation wrappers: VizScope, InstrumentedFlow, InstrumentedChannel, VizMutex, VizSemaphore
- React 19 frontend with TanStack Router and Query
- Visualization panels: Coroutine Tree, Graph, Events List, Thread Lanes, Dispatcher Overview
- Channel panel with buffer gauge and timeline
- Flow panel with operator chain and value trace
- Sync panel with mutex/semaphore state and deadlock detection
- Job panel with structured concurrency tracking
- Validation panel with rule engine
- Docker Compose development environment
- Basic and realistic scenario catalog
- Shared API types generation from OpenAPI spec
- GitHub Actions CI for frontend and backend
- Architecture Decision Records (ADR 001-014)
