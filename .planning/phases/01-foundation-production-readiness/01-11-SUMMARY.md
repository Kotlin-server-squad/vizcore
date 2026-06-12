---
phase: 01-foundation-production-readiness
plan: 11
subsystem: frontend
tags: [discriminator, normalization, jobs-tab, event-kind, tdd, regression-test]
dependency_graph:
  requires: []
  provides: [event-discriminator-test, kind-normalization-proof]
  affects: [frontend/src/lib/utils.ts, frontend/src/hooks/use-event-stream.ts, frontend/src/components/SessionDetails.tsx]
tech_stack:
  added: []
  patterns: [TDD regression test, raw-payload-driven test, end-to-end discriminator mapping proof]
key_files:
  created:
    - frontend/src/lib/event-discriminator.test.ts
  modified: []
decisions:
  - Implementation in utils.ts and use-event-stream.ts was already correct — no code changes needed; the gap was the absence of a test proving it
  - Test uses raw backend {type} payloads (no pre-normalization) to exercise the real wire shape
  - SessionDetails.tsx allEvents derivation confirmed correct on both REST and SSE paths
metrics:
  duration: "~4 min"
  completed: "2026-06-12"
  tasks: 2
  files_changed: 1
---

# Phase 01 Plan 11: Event Discriminator Normalization Summary

## One-liner

Regression test proving raw backend `{type}` payloads normalize to `kind` for all 17 backend event types, closing the Jobs-tab gap via verified end-to-end discriminator mapping.

## What Was Built

### Task 1: Audit and verify normalizeEvent + SSE kind assignment

Audited the full `normalizeEvent` path in `frontend/src/lib/utils.ts` and the SSE listener in `frontend/src/hooks/use-event-stream.ts`.

**Audit result**: Both paths were already correct:
- `normalizeEvent` correctly maps the `type` field to `kind` via `EVENT_TYPE_TO_KIND` for all 17 backend event types, deletes `type` from the result, and preserves already-normalized events by reference
- `use-event-stream.ts` calls `normalizeEvent` on every SSE payload, and the fallback (`event.kind = eventType`) only fires when `event.kind` is falsy AFTER normalization — it cannot overwrite a real kind

The gap was the absence of a regression test exercising these contracts with raw wire-shape payloads.

Created `frontend/src/lib/event-discriminator.test.ts` (Task 1 commit: 91d7e78) with tests that consume raw `{type: 'JobStateChanged', ...}` payloads (no kind field) and assert:
- `normalizeEvent` returns `kind === 'JobStateChanged'`
- `JOB_EVENT_KINDS.has(kind)` is true
- `type` field is removed from the normalized event
- All 17 backend type values produce a defined, non-empty kind
- Already-normalized events are returned with kind preserved (same object reference)

### Task 2: SessionDetails jobStates audit + discriminator test extension

Audited `SessionDetails.tsx` `jobStates` useMemo: it filters `allEvents` for `event.kind === 'JobStateChanged'`. `allEvents` is either `liveEvents` (normalized by `useEventStream` per Task 1) or `storedEvents` (normalized by `apiClient.getSessionEvents → normalizeEvents`). Both paths are properly normalized — no change required.

Extended `event-discriminator.test.ts` (Task 2 commit: e7f4d12) with the SessionDetails simulation test:
- Builds a raw REST payload array (`[{type: 'CoroutineCreated'}, {type: 'JobStateChanged', jobId: 'j1'}, {type: 'JobStateChanged', jobId: 'j2'}, ...]`)
- Runs through `normalizeEvents`
- Replicates the `jobStates` Map-by-jobId derivation logic
- Asserts `jobStates.size === 2` (correct non-zero count)

## .kind Consumer Audit

All consumers of `event.kind` receive normalized events:

| Consumer | How events arrive | Normalized? |
|----------|-------------------|-------------|
| `SessionDetails.tsx` → `jobStates` | `allEvents`: REST via `apiClient.getSessionEvents` + `normalizeEvents`; SSE via `useEventStream` + `normalizeEvent` | Yes |
| `useEventCategories` | `useSessionEvents` → `apiClient.getSessionEvents` → `normalizeEvents` | Yes |
| `EventsList.tsx`, `VirtualizedEventList.tsx`, `CoroutineTimelineView.tsx` | Props from SessionDetails `allEvents` | Yes |
| `ChannelTimeline.tsx`, `EventHighlight.tsx`, `OrderProcessingView.tsx`, `RegistrationFlowView.tsx` | Props from parent components that use normalized stores | Yes |
| `use-actor-events.ts`, `use-channel-events.ts`, `use-flow-events.ts`, `use-job-events.ts`, `use-select-events.ts`, `use-sync-events.ts`, `use-anti-patterns.ts` | `useSessionEvents` → `apiClient.getSessionEvents` → `normalizeEvents` | Yes |
| `use-enhanced-hierarchy.ts`, `use-timeline.ts` | REST endpoints (timeline, hierarchy) that return event arrays | Yes |

No consumer reads `event.kind` from an un-normalized event.

## Tests

All 242 tests pass:
- `frontend/src/lib/event-discriminator.test.ts`: 17 tests (new)
- `frontend/src/hooks/use-event-categories.test.ts`: 8 tests (pre-existing, unchanged)
- `frontend/src/components/SessionDetails.test.tsx`: 5 tests (pre-existing, unchanged)

## Deviations from Plan

None — plan executed as written. The implementation in `utils.ts` and `use-event-stream.ts` was already correct; the plan's "hardening" step confirmed no changes were needed. The key artifact (regression test) was created as required.

## Known Stubs

None.

## Threat Surface Scan

No new network endpoints, auth paths, file access patterns, or schema changes introduced. The threat model's T-01-11-01 (information disclosure via discriminator miss → silent empty tabs) is now mitigated by the regression test.

## Self-Check: PASSED

- `frontend/src/lib/event-discriminator.test.ts` exists: FOUND
- Task 1 commit 91d7e78 exists: confirmed via `git log`
- Task 2 commit e7f4d12 exists: confirmed via `git log`
- 242 tests pass: confirmed
