# ADR-009: Deployment Strategy

## Status
Accepted

## Date
2026-03-16

## Context
Docker support exists (Dockerfiles + docker-compose.yml) but there is no production deployment. The app is a developer tool for visualizing coroutine execution — it needs to be accessible during development but doesn't need high-availability infrastructure.

## Decision
Deploy using container-based PaaS:

- **Backend**: Deploy as a Docker container (Fly.io, Railway, or similar)
- **Frontend**: Build static assets and deploy to CDN (Vercel, Netlify, or Cloudflare Pages)
- **Alternative**: Single docker-compose deployment on a VPS for self-hosted option

### Environment Configuration
- Frontend: `VITE_API_URL` points to deployed backend
- Backend: CORS configured for deployed frontend domain
- No database required for the current deployment — in-memory event store per session (the only implemented store today; ADR-015 defines optional persistence interfaces for a future storage backend)

## Rationale
- Static frontend deployment is free/cheap and fast globally
- Backend needs a server for SSE streaming (can't be serverless)
- Docker images already built, minimal deployment config needed
- Self-hosted option via docker-compose for teams wanting internal deployment

## Consequences
- Need to add production CORS configuration (currently only localhost:3000)
- Need environment-specific Vite config for API URL
- GitHub Actions can be extended with deploy jobs
- No persistent storage is wired in — sessions are ephemeral in the current deployment. ADR-015 (dated later) supersedes the assumption that persistence will never be added: it defines optional persistence interfaces (the `*StoreInterface` seam). That design is Accepted but not yet implemented — in-memory remains the only store in code today.
