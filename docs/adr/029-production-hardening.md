# ADR 029: Production Hardening

## Status

Accepted

## Context

The application has solid core functionality (event sourcing, SSE streaming, 52+ tests, Docker, CI/CD) but lacks several production hardening measures:

1. **Session memory leaks** — sessions are never cleaned up automatically
2. **Raw error exposure** — unhandled exceptions return stack traces to clients
3. **No security headers** — missing standard HTTP security headers
4. **No rate limiting** — API endpoints have no request throttling
5. **Docker resource limits** — production containers have no memory/CPU bounds

A `RetentionPolicy` class already exists in `coroutine-viz-core` with 8 tests but was never wired into the application. The app module also contained duplicate `SessionManager`, `VizSession`, and `EventStore` classes that shadowed the more capable core library versions.

## Decision

### Session lifecycle

Wire the existing `RetentionPolicy` from `coroutine-viz-core` with configurable parameters via environment variables:
- `SESSION_MAX_AGE_MS` (default: 3,600,000 = 1 hour)
- `SESSION_MAX_COUNT` (default: 100)
- `SESSION_CHECK_INTERVAL_MS` (default: 60,000 = 1 minute)

Register an `ApplicationStopped` hook for graceful shutdown that stops the retention policy, clears sessions, and cancels the retention scope.

Delete the app-level `SessionManager`, `VizSession`, and `EventStore` duplicates in favor of the core library versions which include `createdAtMs` (needed for TTL), `maxEvents` (bounded event stores), and lifecycle callbacks.

### Error handling

Use Ktor's `StatusPages` plugin for centralized error handling:
- `IllegalArgumentException` → 400 JSON
- `Throwable` → 500 JSON (no stack traces exposed to clients)
- 404 status → JSON `{ "error": "Not found", "status": 404 }`

Server-side logging via SLF4J preserves debugging capability.

### Security headers

Added via Ktor `DefaultHeaders` plugin and nginx configuration:
- `X-Content-Type-Options: nosniff` — prevent MIME sniffing
- `X-Frame-Options: DENY` — prevent clickjacking
- `X-XSS-Protection: 1; mode=block` — legacy XSS protection
- `Referrer-Policy: strict-origin-when-cross-origin` — control referrer leakage
- `Permissions-Policy: camera=(), microphone=(), geolocation=()` — disable unused APIs

### Rate limiting

Using Ktor's `RateLimit` plugin with two tiers:
- **API routes**: 60 requests/minute per IP
- **Session creation**: 10 requests/minute per IP
- SSE connections are long-lived and naturally exempt after initial connection

### Docker production hardening

- Resource limits: backend 768MB/1CPU, frontend 128MB/0.5CPU
- Log rotation: `max-size: 10m, max-file: 3`
- Health check: `/health` endpoint (not `/`), `start_period: 30s`
- Image pinning: `${TAG:-latest}` variable
- JVM tuning: `JAVA_OPTS` env var with default `-Xmx512m -Xms256m`

## Consequences

- Sessions are automatically cleaned up, preventing unbounded memory growth
- Error responses are consistent JSON format, no stack trace leakage
- Standard security headers protect against common web vulnerabilities
- Rate limiting prevents abuse without impacting normal usage
- Docker containers are resource-bounded and log-rotated
- Core library duplicates are removed, reducing maintenance burden
