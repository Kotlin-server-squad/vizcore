---
title: Share "copy link" points to backend origin (:8080) → 404 for recipient in split-port dev setup
area: backend
severity: medium
status: fixed
fixed: 2026-06-23
found: 2026-06-22
phase: 3
requirement: SHAR-01 / D-11 / ADR-019
discovered_during: Phase-3 browser UAT deep-dive (mint share link)
resolution: ShareDialog builds the link from window.location.origin + /shared/<token>
---

## Symptom (reproduced)
Minting a share link via the Share dialog produces
`http://localhost:8080/shared/<token>` (the copy-link affordance + input value).
- `GET :8080/shared/<token>` → **404** (backend serves no SPA route; that path is a frontend route).
- `GET :3000/shared/<token>` → 200 (the route that actually renders the read-only view).
- `GET :8080/api/shared/<token>` → 200 (the public data endpoint is fine).

So the link a user copies and sends is unusable in the documented dev setup
(`backend ./gradlew run` on :8080 + `frontend pnpm dev` on :3000, CLAUDE.md): the recipient
gets a 404 and would have to hand-edit the port to :3000.

## Root cause
`app.publicBaseUrl` (`APP_PUBLIC_BASE_URL`) defaults to empty, so the backend derives the share
base URL from the request origin (application.yaml:35-36). The frontend's api-client calls reach the
backend via Vite's proxy, so the backend sees its own origin (`localhost:8080`) and builds
`http://localhost:8080/shared/<token>` instead of the frontend origin `http://localhost:3000`.

In a same-origin production deployment (frontend served behind the same host as the backend) this is
fine, but: (a) it is broken out-of-the-box in the documented split-port dev workflow, and (b) it
silently depends on operators setting `APP_PUBLIC_BASE_URL` correctly, with no warning when unset.

## Candidate fixes
- In dev, set/proxy so the derived origin is the frontend, OR have the frontend build the share URL
  from `window.location.origin` + the returned token rather than trusting the backend's absolute URL.
- Document `APP_PUBLIC_BASE_URL` as required for any split-origin deployment; log a startup warning
  when sharing is enabled and `publicBaseUrl` is unset.
- Consider returning a path (`/shared/<token>`) and letting the client resolve the origin.

## Note
Related to the prior runtime-audit "share-UX" todo and
`2026-06-21-share-ui-silent-noop-in-memory-mode.md`. This is the dev-link-port variant.
