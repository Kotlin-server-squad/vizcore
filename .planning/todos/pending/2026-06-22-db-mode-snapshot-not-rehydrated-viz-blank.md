---
title: storage.type=database → session snapshot never rehydrated; ALL visualization panels blank
area: backend
severity: high
found: 2026-06-22
status: fixed
fixed: 2026-06-22
phase: 3
requirement: PERS-01 / PERS-02 (and undermines core REQ-core-visualization in DB mode)
discovered_during: Phase-3 browser UAT deep-dive (run scenario under storage=database)
---

## ✅ RESOLUTION (2026-06-22)
Fixed via snapshot/projection rehydration on DB-backed session reconstruction:
- `ProjectionService.rebuildFrom(events)` — clears + replays events into the hierarchy/thread read models.
- `VizSession.rehydrateFromStore()` — replays `store.all()` through the `EventApplier` (snapshot) and
  `projectionService.rebuildFrom` (hierarchy/threads), advances the seq watermark; does NOT re-record
  to the store or re-broadcast on the bus.
- `ExposedSessionStore.buildSession()` calls `rehydrateFromStore()` after construction.
- Regression test `ExposedSessionStoreTest."getSession rehydrates the snapshot and hierarchy from
  persisted events"` (asserts coroutineCount > 0 + non-empty hierarchy from a fresh read).

Verified in DB mode (browser + REST): persisted session shows `coroutineCount=6`, full nested tree
(parent → 3 children → grandchildren), Threads tab "6 Threads", and survives a backend restart (PERS-01).
4 files changed (+91/-1). Core + persistence test suites green.

## Symptom (reproduced in browser + REST)
With `storage.type=database`, after running a scenario into a session:
- `GET /api/sessions/{id}` returns `eventCount: 56` but `coroutineCount: 0`, `coroutines: []`.
- UI Coroutines tab: "No coroutines in this session yet." Events tab: "No events yet."
  (header badge shows "56 events" — fetched from a different source than the list components).
- Threads / hierarchy tree also blank.

With `storage.type=memory` (default) the SAME scenario projects correctly:
`coroutineCount: 4`, full hierarchy `parent → child-1, child-2 …` all `COMPLETED`.

So the core product value (SEE coroutine execution) is **broken whenever DB persistence is on**.

## Root cause
`GET /api/sessions/{id}` serves `session.snapshot.coroutines` and the Threads/hierarchy tabs read
`session.projectionService.*` (SessionRoutes.kt:117-120, 188, 206) — i.e. the **in-memory**
RuntimeSnapshot/ProjectionService.

`ExposedSessionStore.getSession()` → `buildSession(sessionId)` (ExposedSessionStore.kt:156-163)
constructs a **fresh `VizSession` with an empty `RuntimeSnapshot`** backed by an `ExposedEventStore`.
The persisted events are loaded on demand by `ExposedEventStore.all()` (so `eventCount` is right),
but **nothing replays those events through `ProjectionService` to rebuild the snapshot** on load.
`ExposedSessionStore` also has no session caching, so each read rebuilds an empty-snapshot session.

(There is already a `buildSessionEventStoreCoroutineCount(sessionId)` helper at
ExposedSessionStore.kt:164 that counts coroutines from the event store — evidence the empty-snapshot
problem was partially known for `listSessions`, but the GET-by-id and tab read-paths still use the
empty `snapshot`/`projectionService`.)

## Why tests/prior UAT missed it
- Phase 3 had **no browser UAT** (only `03-VERIFICATION.md`, code-level).
- Persistence tests (`PersistenceRestartTest`, `ExposedSessionStoreTest`) assert events/rows survive
  restart — they do NOT assert that a DB-loaded session reprojects its coroutine snapshot.
- Phase 1/2 UAT ran in memory mode, where the snapshot is the live in-memory one and works.

## Candidate fixes
- In `ExposedSessionStore.buildSession`/`getSession`, after attaching the event store, replay
  `eventStore.all()` through a `ProjectionService` to rebuild `snapshot` before returning; OR
- Cache the live `VizSession` per id (write-through to DB) so the in-memory snapshot built during
  emission is the one served on read; OR
- Have the read-path DTO project from `session.store.all()` (event-sourced) instead of the
  in-memory `snapshot` when the store is DB-backed.
- Add a regression test: persist a scenario, fetch via a NEW store instance, assert
  `coroutineCount > 0` and hierarchy is non-empty.
