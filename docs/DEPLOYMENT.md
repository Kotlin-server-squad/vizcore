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
| `PUBLIC_DEMO_MODE` | `false` | `true` makes this a server anyone may open: a visitor with no credential can list and open sessions and run the built-in demos, and nothing else. See [Public demo](#public-demo). Needs `STORAGE_TYPE=memory`; the backend refuses to start otherwise |
| `TRUSTED_PROXY_HOPS` | `0` | How many reverse proxies stand between the visitor and the backend (the frontend image's nginx counts as one). `0` never reads `X-Forwarded-For`. See [Behind a reverse proxy](#behind-a-reverse-proxy) before setting it |
| `SEED_USER`, `SEED_HASH_B64`, `SEED_ROLE`, `JWT_SECRET` | _(empty)_ | One user who can sign in on `/login`: the name, the Base64 of the password's Argon2id hash (see `auth.users` in `application.yaml`), the role (`viewer`, `runner` or `admin`; default `viewer`) and the secret the sign-in tokens are signed with. All empty: no user. With a user and a secret set, every request needs a credential, except on a public demo |
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

## Public demo

A server on a public address where anyone can watch and run the built-in demos, and nobody
without an admin credential can do more. People who want to see their own app run vizcore on
their own machine (the README's Quickstart); a public demo is not the place for it.

```bash
PUBLIC_DEMO_MODE=true \
CORS_ALLOWED_ORIGINS=https://demo.example.com \
docker compose -f docker-compose.prod.yml up -d
```

It is off by default, and with it off nothing below applies.

What a visitor (no credential) can do:

- list and open sessions, and read everything about them: the live stream, events, replay,
  export, health, Checks, compare
- start a session for a built-in demo and run that demo in it, once. "Run again" opens a new
  session. `MAX_CONCURRENT_DEMOS`, `MAX_DEMO_RUN_SECONDS` and the rate limits apply as always

What a visitor cannot do; each answers `403` with `"code": "public-demo"` and a sentence saying
what is not open to visitors, never a `401`:

- connect an app: open the ingest WebSocket, or create a session that is not for a built-in demo
- post a scenario of their own (`POST /api/scenarios/custom`)
- delete a session, which is also what Reset does, or change one in any other way. Any write
  that is not listed above as allowed is refused, so a write added to the API later is closed to
  visitors until it is opened on purpose
- create, list or revoke share links
- call the developer endpoints (`/api/viz/run-scenario-with-data`,
  `/api/examples/dispatcher-scenario`, `/api/tests/run-all`) or the `GET /api/sync/...` runs that
  open a session per call

The page follows: on a public demo it shows no Connect, no Share, Reset session or Clear events,
and no custom scenario builder, and the sessions list says that this is a public demo and how to
run vizcore yourself.

Everything on a public demo is public to read. Do not connect an app with private data to it.

### Sessions do not pile up

Public demo mode runs with `STORAGE_TYPE=memory` only. There `SESSION_MAX_AGE_MS` removes a
session that has been idle that long and `SESSION_MAX_COUNT` caps their number; a visitor's new
session removes the least recently active ones beyond the cap at once, not only at the next
sweep. Because a visitor's demo runs once in a session, a session cannot be kept alive, or grown,
by running demos into it over and over. A session an admin's app is streaming into is never
removed, and counts toward the cap: if such sessions fill it by themselves, a visitor's new
session is the one removed. With the database, sessions are kept for 30 days and their number has no
cap, which is why the two settings together are refused at startup.

### An admin on a public demo

Optional. Configure one user with the admin role and a signing secret, and sign in on `/login`:

```bash
PUBLIC_DEMO_MODE=true \
SEED_USER=owner SEED_HASH_B64=<base64 of the Argon2id hash> SEED_ROLE=admin \
JWT_SECRET=<a long random string> \
docker compose -f docker-compose.prod.yml up -d
```

An admin (a user or an API key with the `admin` role) can do everything, as on any server.
Visitors still get in without signing in. A credential that is not an admin's is treated like a
visitor, and one that is wrong gets `401`. `API_KEY` alone adds a `runner` key, not an admin.

### Not covered

Public demo mode decides who may do what. It does not cap how many people watch at once (open
live streams), and all visitors share the `MAX_CONCURRENT_DEMOS` slots: when they are taken, the
next demo gets "Server busy" for a few seconds. Put the server behind a reverse proxy and set
`TRUSTED_PROXY_HOPS`, or every visitor shares one rate-limit bucket.

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

Per client and minute (`rateLimit.*` in `application.yaml`, each with a `RATE_LIMIT_*_RPM`
variable): 1200 reads, 300 writes, 120 live-stream or ingest connects, 10 new sessions, 10
sign-in attempts. A client is the API key or user when the request has one, else its address.
Exceeding a limit returns HTTP 429 with `Retry-After`.

### Behind a reverse proxy

The backend counts a caller without a credential by the address of the connection it arrives
on. Behind a reverse proxy (Traefik, Cloudflare, the frontend image's nginx) that is the proxy,
so all visitors share one bucket: one busy visitor uses up everyone's 10 new sessions a minute.

`TRUSTED_PROXY_HOPS` tells the backend how many proxies stand in front of it. Each proxy must
append the address it received the request from to `X-Forwarded-For`; Traefik and nginx with
`$proxy_add_x_forwarded_for` do, and the frontend image's nginx is set up that way. With `N`
hops the backend takes the `N`th entry from the right, which is the visitor as the outermost
proxy saw them, and ignores everything to its left, which the visitor can make up.

| Setup | `TRUSTED_PROXY_HOPS` |
|---|---|
| Backend port reachable directly | `0` (the default) |
| Browser → frontend image (nginx) → backend | `1` |
| Browser → Traefik → frontend image (nginx) → backend | `2` |
| Browser → Cloudflare → Traefik → frontend image (nginx) → backend | `3` |
| Browser → Traefik → backend (Traefik routes `/api` itself) | `1` |

The number has to be exact, and the backend must be reachable through those proxies alone:

- too high, or the backend port also published directly: the entry the backend reads is one the
  visitor sent, so anyone can pick a new bucket for every request by sending their own
  `X-Forwarded-For`
- too low: a proxy is counted as the visitor, which is the shared bucket again

`0` never reads the header. A request with fewer entries than hops, or with an entry that is not
an IP address, is counted by the connection's address. The setting affects rate limits only.

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
