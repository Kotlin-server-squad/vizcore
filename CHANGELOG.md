# Changelog

All notable changes to this project will be documented in this file.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

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
