# Deployment Guide

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | Backend server port |
| `API_KEY` | _(empty)_ | API key for authentication. Empty adds no key; auth is off only when no API keys (`API_KEY` or `auth.keys`) and no users with a JWT secret are configured. Clients send it as `X-API-Key: <key>` or `Authorization: Bearer <key>`; the SDK and the Java agent use the latter (their `token`) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,...` | Comma-separated allowed origins |
| `CORS_ALLOWED_METHODS` | `GET,POST,DELETE,OPTIONS` | Comma-separated allowed HTTP methods |
| `SESSION_MAX_AGE_MS` | `3600000` | Max session age before cleanup (ms) |
| `SESSION_MAX_COUNT` | `100` | Max concurrent sessions |
| `SESSION_CHECK_INTERVAL_MS` | `60000` | Retention policy check interval (ms) |
| `MAX_CONCURRENT_DEMOS` | `3` | Demos that may run on the server at the same time, across all clients. A demo holds its slot until its coroutines end, not only while its request is handled. One started while every slot is taken is refused at once with `503` and `Retry-After: 5`. `0` or less means no limit. Lists of demos, sessions, the live stream and ingest are never limited |
| `MAX_DEMO_RUN_SECONDS` | `60` | A demo still running after this many seconds has its coroutines cancelled and frees its `MAX_CONCURRENT_DEMOS` slot, so one that would never end (a deadlock, a custom scenario with a huge delay) cannot hold a slot. The longest built-in demos take about 35 s. `0` or less means no limit |
| `DEMO_TIME_SCALE` | `1.0` | How fast the paced demos run (the Basics so far): each scripted step takes its time times this. `1` runs them at a pace a person can follow, 10-30 s a demo; `0.1` ten times faster. At least `0.01`, so the steps stay apart and in order; anything else runs them as scripted. The tests and the smoke test use a small scale |
| `LOOK_EXPERIMENT_ENABLED` | `false` | Switches the look experiment on (see [the guide](guides/look-experiment.md)): new visitors get one of the session page's four looks at random, and the server counts, anonymously, how each look is used and rated. Off: nothing is assigned or recorded, and every page is as without it |
| `LOOK_EXPERIMENT` | `classic:25,a:25,b:25,c:25` | New visitors' share of each look, as `look:weight` pairs (`classic`, `a`, `b`, `c`; whole numbers 0-1000; a look left out gets 0). An invalid list falls back to the even split |
| `LOOK_SURVEY_URL` | _(empty)_ | Optional absolute `http(s)` link to an outside survey. When set, a "Tell us more" link next to the vote opens it with `look=<id>` added; when empty, no link |
| `HEALTH_FULL_SUMMARY_TARGET` | `50000` | Per session, how many coroutines keep a full summary for the health view (Problems, the Inspector). Past it, finished coroutines are reduced to a stub, oldest finished first, failed ones last: a stub keeps the coroutine's place in the tree, its state and whether it failed, but not its failure details, last suspension point or last thread. Running coroutines always stay full. Applies to in-memory and database sessions and to replays. Must be at most `HEALTH_TRACKED_CAPACITY` (a larger value is lowered to it, with a warning) |
| `HEALTH_TRACKED_CAPACITY` | `100000` | Per session, how many coroutines the health view tracks at all (full summaries plus stubs). Later coroutines are only counted, and the page says "N coroutines beyond capacity were not tracked: health covers the first M". Memory: roughly 1 KB per tracked coroutine with a full summary and about 0.65 KB per stub (an estimate from one local measurement of synthetic coroutines, including ids, names and creation sites; long names cost more), so a session at the default capacity can hold some 65-95 MB. Size `JAVA_OPTS` and the container's memory limit for the number of busy sessions you keep. A value that is not a positive whole number falls back to the default with a warning in the log. A database session checkpointed under other limits keeps everything it has: under a smaller capacity it tracks no more coroutines, under a larger one it tracks more only if it had not reached its old capacity |
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
every push to `main` and on every `v*` release tag, once the backend and frontend lint and
tests pass:

- `ghcr.io/kotlin-server-squad/vizcore/backend:{latest,<sha>,<tag>}`
- `ghcr.io/kotlin-server-squad/vizcore/frontend:{latest,<sha>,<tag>}`

`latest` follows `main`; a release tag such as `v1.2.0` is published under that exact name.

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

### Run one backend instance

Run exactly one backend instance: one per database with `STORAGE_TYPE=database`, one in all in
memory mode. Scaling out is not supported yet. Several instances behind one load balancer, or
sharing one database, do not coordinate with each other.

Part of a session's state lives in the memory of the backend process, not in the database:

- what the instance knows about an app's ingest connection: whether it is open, the app's
  latest poll watermark, when its last data arrived, and whether delivery is uncertain. In
  database mode a copy goes to the session row when the connection opens and closes, and at
  most every 5 s in between; only the instance holding the socket knows the current state
- the live stream: events and watermark signals reach only the streams opened on the
  instance the app sends to
- the health results kept briefly so that follow-up reads (Problems, a coroutine's details)
  answer from exactly what the health saw
- the correlation tokens an app uses to find its session, and, in memory mode, the sessions
  themselves
- the rate-limit buckets and the demo slots (`MAX_CONCURRENT_DEMOS`), which are counted per
  instance

An app's ingest socket and the browser's requests are separate connections. A load balancer can
send them to different instances, and sticky sessions do not help because they are different
clients. Then health, "delivery uncertain", Problems and the live views disagree depending on
which instance answers:

- the browser's instance sees the app's connection through a copy that is seconds old at best,
  and once the browser has opened its live stream there, not at all, so health reads as if no
  app were connected
- a follow-up read that lands on an instance which did not keep the health's result rebuilds it
  from the stored events, and may answer that the view is stale or show something else
- the live view does not update, because the app's events arrive on the other instance
- in memory mode a session exists only on the instance that created it; the others answer 404
- an instance that starts against a database treats every connection still marked open as
  left behind by a crash, so starting a second instance marks the first one's live sessions
  "delivery uncertain"
- the database retention spares a session an app is streaming into only when the app is
  connected to the same instance, so another instance may delete it by age

Deploying a new version: stop the old instance before starting the new one; a rolling update
that runs both for a while has the same start-up effect. `docker compose up -d` with
`docker-compose.prod.yml` already replaces the container that way.

## Monitoring

### Health check

`/api/ready` is the readiness probe both compose files wire into the container healthcheck.
It answers `{"status":"UP"}` while the session manager is reachable and heap use is under 95% (the heap left after the last garbage collection, so uncollected garbage does not count),
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
