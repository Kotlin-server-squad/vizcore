---
created: 2026-06-21T17:51:56.788Z
title: Share dialog silently no-ops in memory mode (no user feedback)
area: ui
files:
  - frontend/src/components/share/ShareDialog.tsx
  - frontend/src/components/share/ManageShares.tsx
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt
---

## Problem

Found during the 2026-06-21 browser runtime walkthrough. Sharing is DB-backed (plan 03-04), so with the default `storage.type=memory` the share routes return 404 (`GET /api/sessions/{id}/shares` → 404, `POST .../share` → 404). The Share dialog still renders a "Create link" button; clicking it gets a 404 and **silently no-ops** — no error toast, no explanation. The user can't tell sharing is unavailable vs. broken.

## Solution

TBD. Options: (a) detect memory mode (e.g. a capability flag from `/health` or a config endpoint) and disable/hide the Share button with a tooltip "Sharing requires storage.type=database"; or (b) surface the 404 as an inline error/toast in ShareDialog explaining persistence is required. Prefer (a) — don't show an action that can't work. Confirm the backend exposes (or add) a way for the frontend to know whether persistence/sharing is enabled.
