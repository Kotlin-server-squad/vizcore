---
created: 2026-06-21T17:51:56.788Z
title: DB mode shows 0 coroutines (projection not replayed from stored events)
area: general
files:
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/ExposedSessionStore.kt
  - .planning/phases/03-persistence-auth-sharing/deferred-items.md
---

## Problem

Already logged as a deferral in `.planning/phases/03-persistence-auth-sharing/deferred-items.md` (03-07 row); captured here as a tracked todo for visibility. Confirmed visually in the 2026-06-21 browser walkthrough: when the backend runs with `storage.type=database`, a session detail page shows the events badge populated (e.g. "40 events") but **"0 coroutines"** and an empty coroutine graph. Root cause: a DB-rebuilt `VizSession` does not replay stored events into its in-memory projection, so the coroutine list (driven by the projection) is empty even though the raw events persist. Memory mode renders coroutines correctly — this is specific to DB-backed sessions read after rebuild.

This makes the headline visualization empty exactly in the mode Phase 3 added (persistence), which undercuts the persistence value for the tree/graph views.

## Solution

TBD. Replay stored events through the projection when a `VizSession` is rebuilt from the DB store (in `ExposedSessionStore.getSession`, feed loaded events into the ProjectionService / RuntimeSnapshot so the coroutine list is reconstructed). Add a test asserting a DB-rebuilt session exposes the same coroutine count as the live session. Cross-reference the 03-07 deferred-items entry.
