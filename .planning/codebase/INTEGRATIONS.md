# External Integrations

**Analysis Date:** 2026-06-11

## APIs & External Services

**None (no third-party SaaS APIs):**
- The system is entirely self-contained. No Stripe, no Twilio, no third-party REST APIs are consumed.

## Data Storage

**Databases:**
- None — all session state is held in in-memory data structures within the backend JVM process.
- `SessionManager` (`backend/src/main/kotlin/com/jh/proj/coroutineviz/session/`) manages the lifecycle of `VizSession` objects in memory.
- Data does not survive backend restart.

**File Storage:**
- Local filesystem only (none in production; no S3/GCS/Azure Blob).

**Caching:**
- None — no Redis, Memcached, or HTTP caching layer.

## Authentication & Identity

**Auth Provider:**
- Custom — simple API key authentication implemented in `backend/src/main/kotlin/com/jh/proj/coroutineviz/Auth.kt`.
- Implementation: optional `X-API-Key` header check. If `auth.apiKey` env var (`API_KEY`) is empty or absent, authentication is fully disabled and all requests pass as `ApiKeyPrincipal("anonymous")`.
- No OAuth, JWT, or external identity provider.

## SSE / Streaming (Primary Real-Time Transport)

**Backend → Frontend:**
- Protocol: Server-Sent Events (SSE) via `io.ktor:ktor-server-sse`
- Endpoint: `GET /api/sessions/{id}/stream` — defined in `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt`
- Behaviour: replays all stored events from `EventStore`, then streams live events from `EventBus` (deduplication via `seq` field).
- Event format: JSON-encoded `VizEvent`, typed SSE events (e.g. `CoroutineCreated`, `CoroutineStarted`, etc.)
- Frontend consumption: `frontend/src/hooks/use-event-stream.ts` — uses native `EventSource` API; listens to 17+ named event types in both PascalCase (backend) and kebab-case (legacy) formats.
- Metrics tracked: `viz.sse.clients.active` gauge in `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt`.

**IntelliJ Plugin (alternative event path):**
- The plugin (`intellij-plugin/`) embeds a Ktor CIO HTTP server that receives events from instrumented Kotlin code running inside the IDE, bypassing the standalone backend entirely.

## Monitoring & Observability

**Metrics — Micrometer + Prometheus:**
- Library: `io.ktor:ktor-server-metrics-micrometer` + `io.micrometer:micrometer-registry-prometheus:1.6.13`
- Registry: `PrometheusMeterRegistry` configured in `backend/src/main/kotlin/com/jh/proj/coroutineviz/Monitoring.kt`
- Scrape endpoint: `GET /metrics-micrometer` (plain text Prometheus format)
- Custom gauges registered in `MetricsWiring.kt`:
  - `viz.sessions.active` — active `VizSession` count
  - `viz.sse.clients.active` — active SSE connections
- No Grafana, no Datadog, no external metrics sink configured. Prometheus pull model assumed.

**Error Tracking:**
- None — no Sentry, Rollbar, or equivalent.

**Logs:**
- SLF4J + Logback (`ch.qos.logback:logback-classic:1.4.14`)
- Logger instances per class/module via `LoggerFactory.getLogger(...)` in Kotlin, e.g. `backend/src/main/kotlin/com/jh/proj/coroutineviz/HTTP.kt`
- Output to stdout only; no log shipping configured.

## API Documentation

**Swagger / OpenAPI:**
- Swagger UI served at `GET /openapi` by Ktor's `swagger` and `openapi` plugins — configured in `backend/src/main/kotlin/com/jh/proj/coroutineviz/HTTP.kt`.

**AsyncAPI:**
- `org.openfolder:kotlin-asyncapi-ktor:3.1.3` plugin installed — exposes AsyncAPI spec for the SSE event bus.

## CI/CD & Deployment

**Container Registry:**
- GitHub Container Registry (GHCR): `ghcr.io/hermanngeorge15/visualizer-for-coroutines/`
- Images built and pushed on every push to `main` via `.github/workflows/deploy.yml`
- Auth: `GITHUB_TOKEN` secret (automatic)

**CI Pipeline — GitHub Actions:**
- `ci-backend.yml` — backend build + test
- `ci-core.yml` — `coroutine-viz-core` build + test
- `ci-frontend.yml` — frontend lint + test
- `ci-plugin.yml` — IntelliJ plugin build + test
- `deploy-docs.yml` — builds and deploys Docusaurus docs site
- `publish-maven.yml` — publishes `coroutine-viz-core` to GitHub Packages on release or `workflow_dispatch`
- `release.yml` — full release workflow

**Maven / GitHub Packages:**
- `coroutine-viz-core` is published as `com.jh.coroutine-visualizer:coroutine-viz-core` to `https://maven.pkg.github.com/hermanngeorge15/visualizer-for-coroutines`
- Auth: `GITHUB_ACTOR` + `GITHUB_TOKEN` environment variables (`backend/coroutine-viz-core/build.gradle.kts`)

**Hosting:**
- Self-hosted via Docker Compose (`docker-compose.prod.yml`) — no managed platform (Fly.io, Railway, Render) configured.
- Frontend dev container uses bind mount `./frontend/src:/app/src` for hot-reload.

## Webhooks & Callbacks

**Incoming:**
- None — no webhook receivers.

**Outgoing:**
- None — no outgoing webhooks or event push to external systems.

## Network Topology (Process Boundaries)

```
[Instrumented Kotlin App]
        │  HTTP POST events
        ▼
[Backend Ktor Server :8080]  ←── Prometheus scrape (GET /metrics-micrometer)
        │  SSE  GET /api/sessions/{id}/stream
        ▼
[Frontend Vite Dev Server :3000]  ←── proxies /api → :8080
        (Browser EventSource)

[IntelliJ Plugin]  ←── embedded Ktor CIO server
        (receives events from IDE-hosted instrumented code directly)
```

## Environment Configuration

**Required env vars:**
- `PORT` — backend HTTP port (default: 8080)
- `API_KEY` — API key for auth (empty = disabled)
- `CORS_ALLOWED_ORIGINS` — comma-separated allowed origins
- `CORS_ALLOWED_METHODS` — comma-separated HTTP methods
- `VITE_API_URL` — backend URL for Docker frontend container
- `GITHUB_TOKEN` + `GITHUB_ACTOR` — for publishing `coroutine-viz-core` to GitHub Packages

**Secrets location:**
- GitHub Actions secrets (no `.env` files committed)

---

*Integration audit: 2026-06-11*
