# Deployment Runbook

Deployment strategy for the Kotlin Coroutine Visualizer. See also [ADR-009](../adr/009-deployment-strategy.md).

---

## Architecture

```
                 ┌─────────────┐
  Users ──────── │   CDN/Edge  │  ← Static frontend (Vercel / Netlify / Cloudflare Pages)
                 └──────┬──────┘
                        │ /api/*
                 ┌──────▼──────┐
                 │  Backend    │  ← Docker container (Fly.io / Railway)
                 │  Ktor :8080 │
                 └─────────────┘
```

- **Frontend**: Static assets served via CDN. Cheap, fast, globally distributed.
- **Backend**: Docker container on PaaS. Requires server for SSE streaming. Ephemeral sessions (no persistent storage).

---

## Frontend Deployment

### Vercel

```bash
cd frontend
pnpm build
# Deploy via Vercel CLI or Git integration
vercel --prod
```

Environment variables:
```
VITE_API_URL=https://your-backend.fly.dev
```

### Netlify

```bash
cd frontend
pnpm build
# dist/ directory is the publish directory
netlify deploy --prod --dir=dist
```

Add `_redirects` file in `frontend/public/`:
```
/api/*  https://your-backend.fly.dev/api/:splat  200
```

### Cloudflare Pages

Connect the repo and configure:
- Build command: `cd frontend && pnpm build`
- Build output: `frontend/dist`
- Environment variable: `VITE_API_URL`

---

## Backend Deployment

### Docker Build

```bash
cd backend
docker build -t coroutine-viz-backend .

# Test locally
docker run -p 8080:8080 coroutine-viz-backend
```

### Fly.io

```bash
# Install flyctl
brew install flyctl

# Launch (first time)
cd backend
fly launch --name coroutine-viz-backend

# Deploy
fly deploy

# Check status
fly status
fly logs
```

`fly.toml` essentials:
```toml
[http_service]
  internal_port = 8080
  force_https = true

[env]
  CORS_ALLOWED_ORIGINS = "https://your-frontend.vercel.app"
```

### Railway

```bash
# Connect repo via Railway dashboard
# Set environment variables:
#   PORT=8080
#   CORS_ALLOWED_ORIGINS=https://your-frontend.vercel.app
```

### Self-Hosted (Docker Compose)

```bash
docker compose -f docker-compose.prod.yml up -d
```

---

## Environment Variables

### Backend

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | Server port |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated allowed origins |
| `LOG_LEVEL` | `INFO` | Logging level (DEBUG, INFO, WARN, ERROR) |

### Frontend

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_URL` | `http://localhost:8080` | Backend API base URL |

---

## CORS Configuration

Production CORS must be configured to allow the frontend origin:

```kotlin
install(CORS) {
    allowHost("your-frontend.vercel.app", schemes = listOf("https"))
    allowMethod(HttpMethod.Post)
    allowMethod(HttpMethod.Delete)
    allowHeader(HttpHeaders.ContentType)
}
```

---

## Health Checks

```bash
# Backend health
curl https://your-backend.fly.dev/

# Expected: "Hello World!"
```

For PaaS health check configuration, point to `GET /` with expected `200` response.

---

## Monitoring

### Logs

```bash
# Fly.io
fly logs --app coroutine-viz-backend

# Docker
docker logs -f coroutine-viz-backend

# Railway
railway logs
```

### Key Metrics to Watch

- **SSE connection count**: Many concurrent SSE connections consume server memory
- **Event store size**: Sessions accumulate events in memory; large sessions should be cleaned up
- **Response times**: Scenario execution involves coroutine delays; monitor for unexpected latency

### Alerts

Set up alerts for:
- HTTP 5xx error rate > 1%
- Memory usage > 80% (SSE connections + event stores)
- Response time p95 > 5s

---

## Scaling

### Frontend

No special scaling needed — CDN handles it.

### Backend

- **Vertical**: Increase memory for more concurrent sessions/SSE connections
- **Horizontal**: not supported yet. Run one backend instance, also with a database: part of a
  session's live state (the app's ingest connection, the live stream, kept health results) stays
  in the process that handles it, and sticky sessions do not help, because the app and the browser
  are different clients. See [Run one backend instance](../DEPLOYMENT.md#run-one-backend-instance).

---

## Rollback

### Frontend

```bash
# Vercel
vercel rollback

# Netlify
# Use Netlify dashboard to deploy previous build
```

### Backend

```bash
# Fly.io
fly releases
fly deploy --image registry.fly.io/coroutine-viz-backend:v{previous}

# Docker
docker pull coroutine-viz-backend:{previous-tag}
docker compose up -d
```

---

## Checklist

Before deploying to production:

- [ ] `CORS_ALLOWED_ORIGINS` set to frontend URL
- [ ] `VITE_API_URL` set to backend URL
- [ ] Health check endpoint returns 200
- [ ] SSE streaming works through reverse proxy / CDN
- [ ] Frontend build succeeds with no TypeScript errors
- [ ] Backend tests pass
- [ ] Docker image builds and starts correctly
