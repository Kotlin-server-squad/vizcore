# Technology Stack

**Analysis Date:** 2026-06-11

## Languages

**Primary:**
- Kotlin 2.2.20 — backend server (`backend/`) and core library (`backend/coroutine-viz-core/`)
- TypeScript 5.7.2 — frontend web app (`frontend/`) and shared types (`shared/api-types/`)

**Secondary:**
- Java (JVM target for Kotlin compilation) — strict mode via `kotlin.code.style=official`

## Runtime

**Environment:**
- JVM 21 (Temurin, per CI `ci-backend.yml` and `ci-core.yml`)
- Node.js >= 24.0.0 (enforced via `engines` in `frontend/package.json`)

**Package Manager:**
- Gradle 9.1.0 (Gradle Wrapper) — backend and plugin
  - Wrapper: `backend/gradle/wrapper/gradle-wrapper.properties`
  - Lockfile: No explicit lock (relies on `dependencyResolutionManagement` in `backend/settings.gradle.kts`)
- pnpm >= 9.0.0 — frontend
  - Lockfile: `frontend/pnpm-lock.yaml`

## Frameworks

**Backend:**
- Ktor 3.3.2 (Netty engine) — HTTP server, SSE, routing, auth, content negotiation
  - Engine: `io.ktor.server.netty.EngineMain` (set in `application.yaml`)
  - Key plugins: `ktor-server-sse`, `ktor-server-auth`, `ktor-server-cors`, `ktor-server-compression`, `ktor-server-metrics-micrometer`
- kotlinx.coroutines 1.10.2 — core library only (no Ktor in `coroutine-viz-core`)
- kotlinx.serialization-json 1.8.1 — JSON serialization throughout

**Frontend:**
- React 19.0.0 — UI rendering
- Vite 6.0.3 — dev server and bundler (SWC via `@vitejs/plugin-react-swc`)
- TanStack Router 1.84.4 — file-based routing with `@tanstack/router-vite-plugin`
- TanStack Query 5.62.7 — server state management
- HeroUI 2.6.8 (`@heroui/react`) — component library
- Tailwind CSS 3.4.17 — utility styling
- Framer Motion 11.14.4 — animations
- Zod 3.23.8 — schema validation
- React Hook Form 7.54.2 — form management

**Testing (Backend):**
- JUnit 5 (Jupiter 5.10.1) — primary test runner
- Ktor Test Host (`ktor-server-test-host`) — integration test support
- kotlinx-coroutines-test 1.10.2 (different versions: `1.7.3` in server, `1.10.2` in core)
- JaCoCo — coverage for `coroutine-viz-core` only

**Testing (Frontend):**
- Vitest 4.1.0 — test runner
- @testing-library/react 16.3.2 — component testing
- @testing-library/user-event 14.6.1 — user interaction simulation
- jsdom 29.0.0 — DOM environment
- MSW 2.7.0 — API mocking

**Docs Site:**
- Docusaurus 3.7.0 (`docs-site/`) — static documentation site

**IntelliJ Plugin:**
- IntelliJ Platform 2.5.0 Gradle plugin — targets IntelliJ IDEA Community 2024.1
- Ktor 3.3.2 CIO engine — embedded HTTP server inside the plugin for receiving events

## Key Dependencies

**Critical:**
- `coroutine-viz-core` (internal subproject) — event types, instrumentation wrappers (VizScope, InstrumentedFlow, VizMutex, VizSemaphore), session management (EventBus, EventStore, RuntimeSnapshot, ProjectionService); published separately to GitHub Packages as `com.jh.coroutine-visualizer:coroutine-viz-core`
- `io.ktor:ktor-server-sse` — SSE streaming is the primary real-time transport between backend and frontend
- `io.micrometer:micrometer-registry-prometheus:1.6.13` — Prometheus metrics exposure

**Infrastructure:**
- `ch.qos.logback:logback-classic:1.4.14` — structured logging (SLF4J backend)
- `org.openfolder:kotlin-asyncapi-ktor:3.1.3` — AsyncAPI spec generation
- `io.ktor:ktor-server-swagger` + `ktor-server-openapi` — Swagger UI at `/openapi`
- `framer-motion:11.14.4` — animation system (significant recent work per commit history)
- `html2canvas:1.4.1` — screenshot/export capability in frontend

## Configuration

**Environment:**
- Backend configured via `backend/src/main/resources/application.yaml`
- Key variables:
  - `PORT` (default: 8080)
  - `API_KEY` (empty = auth disabled)
  - `CORS_ALLOWED_ORIGINS` (default: `http://localhost:3000,http://127.0.0.1:3000`)
  - `CORS_ALLOWED_METHODS` (default: `GET,POST,DELETE,OPTIONS`)
- Frontend env: `VITE_API_URL` used in Docker (dev proxies `/api` → `http://localhost:8080`)

**Build:**
- Backend: `backend/build.gradle.kts`, `backend/gradle.properties` (centralised version catalog)
- Core library: `backend/coroutine-viz-core/build.gradle.kts`
- Plugin: `intellij-plugin/build.gradle.kts` (shares `detekt.yml` from backend)
- Frontend: `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/tailwind.config.js`
- TypeScript strict mode enabled; path aliases: `@/` → `src/`, `@vizcor/api-types` → `../shared/api-types`
- Linting: `backend/` — ktlint 12.2.0 + detekt 1.23.7; `frontend/` — ESLint 9 flat config + Prettier 3.4.2

## Platform Requirements

**Development:**
- Docker + Docker Compose (recommended via `docker-compose.yml`)
- Or: JVM 21 + Gradle Wrapper + Node 24 + pnpm 9

**Production:**
- Docker images published to GitHub Container Registry (`ghcr.io`)
- Images: `ghcr.io/hermanngeorge15/visualizer-for-coroutines/backend:latest` and `.../frontend:latest`
- Orchestrated via `docker-compose.prod.yml`
- No external database; all session state is in-memory

---

*Stack analysis: 2026-06-11*
