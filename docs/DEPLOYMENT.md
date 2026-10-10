# Deployment Guide

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | Backend server port |
| `API_KEY` | _(empty)_ | API key for authentication (empty = auth disabled) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,...` | Comma-separated allowed origins |
| `CORS_ALLOWED_METHODS` | `GET,POST,DELETE,OPTIONS` | Comma-separated allowed HTTP methods |
| `SESSION_MAX_AGE_MS` | `3600000` | Max session age before cleanup (ms) |
| `SESSION_MAX_COUNT` | `100` | Max concurrent sessions |
| `SESSION_CHECK_INTERVAL_MS` | `60000` | Retention policy check interval (ms) |
| `JAVA_OPTS` | `-Xmx512m -Xms256m` | JVM options for backend container |
| `TAG` | `latest` | Docker image tag for prod compose |
| `BACKEND_PORT` | `8080` | Host port published for the backend (container port stays 8080) |
| `FRONTEND_PORT` | `3000` | Host port published for the frontend (container port stays 3000) |

## Images

Both images build from the **repository root** context — the backend image copies every SDK
module `backend/settings.gradle.kts` includes, and the frontend image needs `shared/api-types`
next to `frontend/` for `pnpm build` to resolve `@vizcor/api-types`:

```bash
docker build -f backend/Dockerfile  -t vizcore-backend  .
docker build -f frontend/Dockerfile -t vizcore-frontend .   # add --target dev for the Vite dev server
```

`docker compose build` does the same for the local stack. Host ports are overridable, so the
stack comes up on a machine where 8080 or 3000 is taken:

```bash
BACKEND_PORT=18081 FRONTEND_PORT=13001 docker compose up -d
```

`.github/workflows/deploy.yml` publishes both images to the repository's GHCR namespace on
every push to `main`:

- `ghcr.io/kotlin-server-squad/vizcore/backend:{latest,<sha>}`
- `ghcr.io/kotlin-server-squad/vizcore/frontend:{latest,<sha>}`

Authenticate before pulling them:

```bash
export CR_PAT=<personal access token with read:packages>
echo "$CR_PAT" | docker login ghcr.io -u <github-username> --password-stdin
docker compose -f docker-compose.prod.yml pull
```

## Docker Production

### Start

```bash
docker compose -f docker-compose.prod.yml up -d
```

### Custom configuration

```bash
TAG=v1.2.0 \
SESSION_MAX_AGE_MS=7200000 \
SESSION_MAX_COUNT=50 \
CORS_ALLOWED_ORIGINS=https://my-app.example.com \
docker compose -f docker-compose.prod.yml up -d
```

### Resource limits

| Service | Memory | CPU |
|---|---|---|
| backend | 768 MB | 1.0 |
| frontend | 128 MB | 0.5 |

Logs are rotated at 10 MB with 3 files retained per service.

## Monitoring

### Health check

`/api/ready` is the readiness probe both compose files wire into the container healthcheck.
It answers `{"status":"UP"}` while the session manager is reachable and heap use is under 95%,
and 503 otherwise. `/api/health` returns the full status payload (version, session count,
uptime, memory) and is the one to read when diagnosing:

```bash
curl -f http://localhost:${BACKEND_PORT:-8080}/api/ready
curl http://localhost:${BACKEND_PORT:-8080}/api/health   # full status payload
```

### Prometheus metrics

```bash
curl http://localhost:8080/metrics-micrometer
```

Key metrics:
- `viz.sessions.active` — active session count
- `viz.sse.clients.active` — active SSE connections

## Security

### Verify headers

```bash
curl -I http://localhost:8080/api/sessions
```

Expected headers:
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `X-XSS-Protection: 1; mode=block`
- `Referrer-Policy: strict-origin-when-cross-origin`
- `Permissions-Policy: camera=(), microphone=(), geolocation=()`

### Rate limiting

- API routes: 60 requests/minute per IP
- Session creation: 10 requests/minute per IP
- Exceeding limits returns HTTP 429

## Troubleshooting

### Session cleanup not running

Check backend logs for `Retention policy started` on startup. If missing, verify `session.*` config in `application.yaml`.

### Out of memory

Increase `JAVA_OPTS` memory limit and Docker memory constraint together:
```bash
JAVA_OPTS="-Xmx768m -Xms256m" docker compose -f docker-compose.prod.yml up -d
```
Also update `deploy.resources.limits.memory` in `docker-compose.prod.yml`.

### Log inspection

```bash
docker compose -f docker-compose.prod.yml logs -f backend
docker compose -f docker-compose.prod.yml logs -f frontend
```

### Graceful shutdown

Send `SIGTERM` to the backend container. Logs should show:
```
Application stopping — cleaning up sessions
Retention policy stopped
Session cleanup complete
```
