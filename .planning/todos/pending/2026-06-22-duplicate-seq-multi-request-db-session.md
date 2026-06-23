---
title: Duplicate sequence numbers when emitting to the same DB-backed session across requests
area: backend
severity: medium
status: fixed
fixed: 2026-06-23
found: 2026-06-22
phase: 4
requirement: event-store seq uniqueness invariant (WR-02 SSE replay dedup) / ADR-020
discovered_during: Phase-3 browser UAT deep-dive (Step 6 — Validation engine flagged it)
resolution: ExposedEventStore is now the seq authority (MAX(seq)+1 under a per-session lock)
---

## Symptom (surfaced by the in-app Validation feature)
Running validation on a DB-backed session that received MULTIPLE scenario runs reports:
- `NoDuplicateSequenceNumbers` — "Found 24 duplicate sequence numbers" (seq 3,4,5… each 2×).
- `NoEventsAfterTerminal` ×2 — a coroutine has two `CoroutineCompleted` events (a 2nd run's
  completion lands at a higher seq after the 1st run's terminal event).

## Scope (isolated empirically)
- SINGLE scenario run into a fresh DB session → 20 events, 20 distinct seqs, **0 duplicates** (×3). Clean.
- TWO runs into the SAME DB session (current, post-F2-fix backend) → **1 duplicate** (was ~21 before
  the F2 seq-watermark fix). The pre-F2 `uat-deepdive` session accumulated 24 duplicates over several runs.

## Root cause
In DB mode every request rebuilds the `VizSession` (`ExposedSessionStore.buildSession`) with a fresh
`seqGenerator = AtomicLong(0)`. `VizSession.rehydrateFromStore()` (added for the F2 fix) advances it
to `store.maxSeq` — but that read can be STALE: a prior run's coroutines emit asynchronously, so if a
new emission request rebuilds the session before the previous run's last events have persisted, the
new `seqGenerator` starts below the true max → overlapping seqs → duplicates. Concurrent emission
requests to the same session id hit the same race.

This breaks the per-session seq-uniqueness invariant that SSE replay dedup (max-seq watermark, WR-02)
and event ordering depend on.

## Mitigation already in place
The F2 fix (`seqGenerator.set(maxSeq)` on rehydrate) eliminates the bulk of duplicates (single-run is
clean; multi-run dropped from ~21 to ~1). The residual is the request-boundary / async-persist race.

## Candidate fixes
- Make the persistent store the seq authority: `ExposedEventStore.append` assigns seq atomically from
  `MAX(seq)+1` within the insert transaction (per session id), instead of the in-memory `seqGenerator`.
- Or cache/keep a single live `VizSession` per id (write-through) so one `seqGenerator` owns the session
  for its lifetime, instead of rebuilding per request.
- Add a validation/regression test: N sequential + concurrent scenario runs into one DB session →
  `NoDuplicateSequenceNumbers` passes.

## Note
Normal usage is one session per run (no duplicates). This bites the "append multiple runs into one
persisted session" pattern. The Validation engine correctly detected it — RT-02 (validation panel
crash) is FIXED and the feature is working as intended.
