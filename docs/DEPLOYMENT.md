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

```bash
curl http://localhost:8080/health
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
