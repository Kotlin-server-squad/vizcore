# Coroutine Visualizer - Setup Guide

Complete guide for setting up and running the Coroutine Visualizer project.

For the short version — what vizcore is, the two-command quickstart, the SDK snippet and the smoke test — see [README.md](README.md).

## Architecture Overview

```
┌─────────────────────┐         ┌─────────────────────┐
│      Frontend       │   API   │       Backend       │
│   (React + Vite)    │ ──────► │   (Kotlin + Ktor)   │
│   localhost:3000    │  /api   │   localhost:8080    │
└─────────────────────┘         └─────────────────────┘
         │                               │
         │ proxy                         │ serves
         ▼                               ▼
    /api/* requests           REST + SSE endpoints
    forwarded to :8080        Coroutine instrumentation
```

**Dependency:** Frontend depends on Backend for API calls. Start Backend first.

---

## Prerequisites

### Backend Requirements

| Requirement | Version | Check Command |
|-------------|---------|---------------|
| JDK | 17+ | `java -version` |
| Gradle | 8+ (wrapper included) | `./gradlew --version` |

### Frontend Requirements

| Requirement | Version | Check Command |
|-------------|---------|---------------|
| Node.js | 24.0.0+ | `node --version` |
| pnpm | 9.0.0+ | `pnpm --version` |

**Installing pnpm:**
```bash
# Using Corepack (recommended, ships with Node)
corepack enable pnpm

# Or via npm
npm install -g pnpm
```

---

## Quick Start

### 1. Start Backend (Terminal 1)

```bash
cd backend
./gradlew :run
```

Wait for output:
```
Application - Responding at http://0.0.0.0:8080
```

### 2. Start Frontend (Terminal 2)

```bash
cd frontend
pnpm install
pnpm dev
```

Wait for output:
```
VITE v6.0.3  ready in XXX ms
➜  Local:   http://localhost:3000/
```

### 3. Open Browser

Navigate to **http://localhost:3000**

---

## Backend Details

### Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.2.20 | Language |
| Ktor | 3.3.2 | Web framework |
| Netty | (via Ktor) | HTTP server |
| kotlinx.serialization | (via Ktor) | JSON serialization |
| Logback | 1.4.14 | Logging |
| Micrometer + Prometheus | 1.6.13 | Metrics |
| JUnit 5 | 5.10.1 | Testing |

### Available Commands

```bash
# Development
./gradlew :run              # Start dev server (port 8080)
./gradlew test             # Run tests

# Production
./gradlew build            # Build everything
./gradlew buildFatJar      # Build executable JAR with dependencies

# Docker
./gradlew buildImage       # Build Docker image
./gradlew runDocker        # Run with Docker
```

### Configuration

Server config in `src/main/resources/application.yaml`:
- Port: `8080`
- Host: `0.0.0.0`

### API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/sessions` | List all sessions |
| POST | `/api/sessions` | Create new session |
| GET | `/api/sessions/{id}` | Get session snapshot |
| DELETE | `/api/sessions/{id}` | Delete session |
| GET | `/api/sessions/{id}/events` | Get stored events |
| GET | `/api/sessions/{id}/stream` | SSE event stream |
| GET | `/api/sessions/{id}/hierarchy` | Coroutine hierarchy |
| GET | `/api/sessions/{id}/threads` | Thread activity |
| GET | `/api/scenarios` | List scenarios |
| POST | `/api/scenarios/{id}` | Run scenario |
| POST | `/api/scenarios/custom` | Run custom scenario |
| GET | `/openapi` | Swagger UI |
| GET | `/metrics-micrometer` | Prometheus metrics |

### Verify Backend is Running

```bash
curl http://localhost:8080/api/sessions
# Should return: []
```

---

## Frontend Details

### Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| React | 19.0.0 | UI framework |
| TypeScript | 5.7.2 | Type safety |
| Vite | 6.0.3 | Build tool |
| TanStack Router | 1.84.4 | Routing |
| TanStack Query | 5.62.7 | Server state |
| HeroUI | 2.6.8 | Component library |
| Tailwind CSS | 3.4.17 | Styling |
| Framer Motion | 11.14.4 | Animations |

### Available Commands

```bash
# Development
pnpm install       # Install dependencies
pnpm dev           # Start dev server (port 3000)

# Production
pnpm build         # TypeScript check + production build
pnpm preview       # Preview production build

# Code Quality
pnpm lint          # Run ESLint
pnpm format        # Format with Prettier
```

### Proxy Configuration

Frontend proxies `/api/*` requests to backend (`vite.config.ts`):

```typescript
server: {
  port: 3000,
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true,
    },
  },
},
```

### Offline Development (Mock Mode)

Frontend can run without backend using Mock Service Worker:

1. Initialize MSW:
   ```bash
   npx msw init public/
   ```

2. Enable in `src/main.tsx`:
   ```typescript
   if (import.meta.env.DEV) {
     const { worker } = await import('./mocks/browser')
     await worker.start()
   }
   ```

---

## Running Both Services

### Option 1: Two Terminals (Recommended for Development)

**Terminal 1 - Backend:**
```bash
cd backend && ./gradlew :run
```

**Terminal 2 - Frontend:**
```bash
cd frontend && pnpm dev
```

### Option 2: Background Backend

```bash
# Start backend in background
cd backend && ./gradlew :run &

# Start frontend
cd frontend && pnpm dev
```

### Option 3: Using a Process Manager

Create `Procfile` in root:
```
backend: cd backend && ./gradlew :run
frontend: cd frontend && pnpm dev
```

Use with `foreman`, `overmind`, or similar tools.

---

## Troubleshooting

### Backend Issues

| Problem | Solution |
|---------|----------|
| Port 8080 in use | `lsof -i :8080` to find process, kill it |
| Gradle build fails | `./gradlew clean` then retry |
| Java version mismatch | Ensure JDK 17+ is installed and `JAVA_HOME` is set |

### Frontend Issues

| Problem | Solution |
|---------|----------|
| Port 3000 in use | `lsof -i :3000` to find process, kill it |
| pnpm not found | `corepack enable pnpm` or `npm i -g pnpm` |
| Node version too old | Install Node 24+ via nvm: `nvm install 24` |
| API calls fail (CORS) | Ensure backend is running on port 8080 |
| Type errors | Run `pnpm build` to see all TS errors |

### Connection Issues

**"Failed to fetch" errors in browser:**
1. Check backend is running: `curl http://localhost:8080/api/sessions`
2. Check browser console for CORS errors
3. Verify proxy config in `vite.config.ts`

**SSE stream not connecting:**
1. Check backend logs for connection attempts
2. Verify session ID exists
3. Try refreshing the browser

---

## Development Workflow

### Typical Session

1. **Start backend** → Wait for "Responding at http://0.0.0.0:8080"
2. **Start frontend** → Wait for Vite ready message
3. **Open browser** → http://localhost:3000
4. **Create session** → Click "New Session" or run a scenario
5. **Observe events** → Watch real-time coroutine visualization

### Making Changes

**Backend changes:**
- Gradle auto-reloads on save (if using `--continuous`)
- Or restart: `Ctrl+C` then `./gradlew :run`

**Frontend changes:**
- Vite HMR auto-updates browser
- No restart needed

### Running Tests

```bash
# Backend tests
cd backend && ./gradlew test

# Frontend (lint + type check)
cd frontend && pnpm lint && pnpm build
```

---

## Environment Variables

### Backend

Set in `gradle.properties` or environment:

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 8080 | Server port |

### Frontend

Create `.env.local` for local overrides:

```env
# Example: Change API target (not usually needed)
VITE_API_URL=http://localhost:8080
```

---

## Production Build

### Backend

```bash
cd backend
./gradlew buildFatJar
java -jar build/libs/backend-all.jar
```

### Frontend

```bash
cd frontend
pnpm build
# Output in dist/ - serve with any static file server
pnpm preview  # Preview locally
```

### Docker (Backend)

```bash
cd backend
./gradlew buildImage
docker run -p 8080:8080 backend
```

---

## Ports Summary

| Service | Port | URL |
|---------|------|-----|
| Frontend (dev) | 3000 | http://localhost:3000 |
| Backend | 8080 | http://localhost:8080 |
| Swagger UI | 8080 | http://localhost:8080/openapi |
| Prometheus Metrics | 8080 | http://localhost:8080/metrics-micrometer |
