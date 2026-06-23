---
title: Dispatcher (and viz-with-data) scenarios are implemented and working but exposed in NO catalog or Gallery tab
area: backend catalog + frontend Gallery
severity: low
status: fixed
fixed: 2026-06-23
found: 2026-06-22
phase: 3
requirement: scenario discoverability / Gallery coverage
discovered_during: Phase-3 browser UAT deep-dive (coverage comparison: catalogs vs implemented endpoints)
ledger_id: F11
resolution: added a navigable POST /api/scenarios/dispatcher + catalog entry + Gallery card (Basic)
---

## Finding (answer to "are there scenarios that could be there but aren't?")
Coverage comparison of advertised catalogs vs implemented endpoints vs the Gallery:

- ✅ No advertised-but-missing: all 31 cataloged scenarios (12 core + 4 flow + 5 patterns + 10 sync)
  are implemented and (post-F9) runnable.
- ⚠️ Implemented-but-undiscoverable:
  - `GET /api/examples/dispatcher-scenario` → 200, works.
  - `GET /api/viz/run-scenario-with-data` → 200, works.
  Neither appears in `/api/scenarios`, `/api/scenarios/flow|patterns`, `/api/sync/scenarios`, nor in
  any Gallery tab (Basic / Patterns / Flow / Sync / Channel).

The notable gap is **Dispatcher**: the frontend ships dispatcher-visualization components
(DispatcherOverview, DispatcherBadge, DispatcherOverview) but there is **no Gallery/Scenarios card to
generate dispatcher data** — so a user cannot exercise the dispatcher view through the UI even though
both the backend scenario and the frontend visualization exist. This is a "scenario that can be there
and is not."

## Impact
Low (no broken behavior), but it's a discoverability/coverage gap: a built feature (dispatcher
visualization) is effectively unreachable from the UI, and two working scenario endpoints are hidden.

## Candidate fixes
- Add the dispatcher scenario to the catalog (`/api/scenarios` or a dedicated "Dispatcher" group) and
  surface it as a Gallery card (likely a new "Dispatcher" tab or under Basic), so its events feed the
  existing DispatcherOverview view.
- Decide whether `/api/viz/run-scenario-with-data` is intended to be user-facing; if not, mark it
  internal/example-only; if yes, catalog it too.
- Add a catalog-completeness test: every runnable scenario endpoint appears in a catalog (would catch
  future drift in both directions).

## Note
These endpoints require auth (return 401 without a valid token), consistent with the rest of the API.
