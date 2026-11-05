# API Reference

Base URL: `http://localhost:8080`

All endpoints return JSON unless noted. SSE endpoints return `text/event-stream`.

---

## Sessions

### Create Session

```
POST /api/sessions?name={optional-name}
```

Response `201`:
```json
{
  "sessionId": "uuid-or-name",
  "message": "Session created successfully"
}
```

### List Sessions

```
GET /api/sessions
```

Response `200`: Array of session objects.

### Get Session Details

```
GET /api/sessions/{id}
```

Response `200`:
```json
{
  "sessionId": "string",
  "coroutineCount": 5,
  "eventCount": 42,
  "coroutines": [
    {
      "id": "coro-1",
      "jobId": "job-1",
      "parentId": null,
      "scopeId": "scope-1",
      "label": "root",
      "state": "COMPLETED"
    }
  ],
  "kind": "ATTACHED",
  "startedAtEpochMs": 1792000000000,
  "captureStartAppNanos": 512000000000,
  "origin": "REPORTED",
  "appConnected": true,
  "lastIngestEpochMs": 1792000012345,
  "lastWatermark": { "appNanos": 524000000000, "appEpochMs": 1792000012300, "receivedEpochMs": 1792000012310 },
  "oldestRetainedSeq": 1,
  "trimmed": false
}
```

The session facts (spec 2026-10-21, C-3): `kind` is `DEMO` for vizcore's own runs (`scenario-…` ids) and
`ATTACHED` otherwise; `captureStartAppNanos` is the app-clock time of the earliest event captured, the origin of
"time into the capture" (`null` when unknown); `origin` is `REPORTED` once the producer reports coroutine origins
(always for demos), else `UNKNOWN`; `appConnected` says whether the app's ingest socket is open on this backend now;
`lastIngestEpochMs` is when the last event frame arrived; `lastWatermark` is the SDK's latest poll watermark;
`trimmed` says older events were dropped (`oldestRetainedSeq` is the oldest one kept). The share payload's `session`
has the same shape.

### Get Session Health

```
GET /api/sessions/{id}/health[?atSeq=N][&leakThresholdMs=M]
```

The session's derived health (spec 2026-10-21, C-3), for a refetch at most once per second: failed rounds, ungrouped
failures and possibly blocked coroutines (`incidents`, failures first, newest first), observations (long-lived,
long-suspended), origin counts, transport and retention facts. Every list is capped at 50 with its `total`. Without
`atSeq` the health is live; with it, the health at that event (clamped to the last one), where possibly blocked is not
evaluated (`REPLAY`). `leakThresholdMs` works as on `/metrics`.

Response `200` (trimmed to one incident):
```json
{
  "state": "PROBLEMS",
  "asOf": { "kind": "LAST_EVENT", "appNanos": 4000000000, "appEpochMs": null, "receivedEpochMs": null, "seq": 31 },
  "asOfSeq": 31,
  "sessionOrigin": "REPORTED",
  "problemCount": 1,
  "failedRoundCount": 1,
  "ungroupedFailureCount": 0,
  "possiblyBlockedCount": 0,
  "incidents": {
    "items": [
      {
        "type": "FAILED_ROUND",
        "id": "failed:round:c-1",
        "roundId": "round:c-1",
        "anchorId": "c-1",
        "failures": {
          "items": [
            { "coroutineId": "c-4", "name": "payment", "exceptionType": "java.lang.IllegalStateException",
              "message": "Payment declined", "failedAtSeq": 28, "detailsDropped": false }
          ],
          "total": 1
        },
        "cancelledAfter": { "items": ["c-5"], "total": 1 },
        "firstFailureSeq": 28,
        "sortSeq": 28
      }
    ],
    "total": 1
  },
  "possiblyBlocked": { "status": "NOT_EVALUATED", "reason": "DEMO" },
  "observations": { "longLived": { "items": [], "total": 0 }, "longSuspended": { "items": [], "total": 0 } },
  "counts": { "app": 6, "vizcore": 0, "unknown": 0, "untracked": 0 },
  "failuresAtLeast": false,
  "vizcoreConnection": { "captured": 0, "failed": 0, "failures": { "items": [], "total": 0 } },
  "transport": { "lostEvents": 0, "deliveryUncertain": false, "sinceSeq": null },
  "roundCount": 1,
  "problemRoundCount": 1,
  "problemRounds": { "items": [{ "roundId": "round:c-1", "anchorId": "c-1", "anchorCreatedNanos": 1000000, "kinds": ["FAILED"] }], "total": 1 },
  "asOfWatermark": null,
  "retention": { "oldestRetainedSeq": 1, "oldestRetainedAppNanos": 1000000, "trimmed": false }
}
```

`possiblyBlocked.reason` is one of `REPLAY`, `DEMO`, `EVENTS_DROPPED`, `HISTORY_TRIMMED`, `OVERFLOW`, `NO_WATERMARK`
(highest precedence first). An app that has not sent a poll watermark (SDKs before triage) reads `NO_WATERMARK` while
connected; once its connection ends without a final watermark, delivery is uncertain for the rest of the session
(`transport.deliveryUncertain`, failures read "at least").

Responses: `400` when `atSeq` is not a non-negative integer, `404` when the session is unknown, `503` with
`code: "summaries-unavailable"` when the session's summaries were abandoned, or `code: "history-trimmed"` for a
database session whose early events were trimmed (its health returns once summaries are persisted, Part 5b).

### Delete Session

```
DELETE /api/sessions/{id}
```

Response `200`: `{"message": "Session closed"}`

### Get All Events

```
GET /api/sessions/{id}/events
```

Response `200`: JSON array of `VizEvent` objects with `kind` field injected.

### Get Coroutine Hierarchy

```
GET /api/sessions/{id}/hierarchy?scopeId={optional}
```

Response `200`: Hierarchy tree structure.

### Get Thread Activity

```
GET /api/sessions/{id}/threads
```

Response `200`: Thread activity data grouped by dispatcher.

### Get Coroutine Timeline

```
GET /api/sessions/{id}/coroutines/{coroutineId}/timeline
```

Response `200`: Timeline data with suspension points for a specific coroutine.

For a coroutine that has not finished, `openPhase` (`ACTIVE` or `SUSPENDED`) and
`openPhaseSinceNanos` give the phase since its last recorded transition. `createdAtNanos`,
`createdAtEpochMs` and `observedAtEpochMs` (the backend's wall clock) let a client count that
phase up to now; `activeDuration` and `suspendedDuration` cover closed intervals only.

### SSE Event Stream

```
GET /api/sessions/{id}/stream
```

**Content-Type**: `text/event-stream`

Behavior:
1. Replays all stored events (history)
2. Streams live events filtered by sequence number (no duplicates)
3. Each SSE message includes `data`, `event` (kind), and `id` fields
4. The app's poll watermarks arrive as `event: watermark` frames without an `id`, data
   `{"appNanos", "appEpochMs", "receivedEpochMs"}`: a signal to refetch the session's health

---

## Scenarios

### List All Scenarios

```
GET /api/scenarios
```

Response `200`:
```json
{
  "scenarios": [
    {"name": "nested", "category": "basic", "description": "..."},
    ...
  ]
}
```

### Basic Scenarios

All accept optional `?sessionId={id}` query parameter. Creates new session if omitted.

```
POST /api/scenarios/nested          # Nested coroutine hierarchy
POST /api/scenarios/parallel        # Parallel execution
POST /api/scenarios/cancellation    # Cancellation demo
POST /api/scenarios/deep-nesting?depth=5  # Configurable depth
POST /api/scenarios/mixed           # Mixed sequential/parallel
POST /api/scenarios/exception       # Exception handling
```

Response `200`:
```json
{
  "success": true,
  "sessionId": "auto-123456",
  "message": "Scenario completed. Connect to /api/sessions/{sessionId}/stream for live events.",
  "coroutineCount": 5,
  "eventCount": 42
}
```

### Channel Scenarios

```
POST /api/scenarios/channel-rendezvous   # Zero-buffer rendezvous
POST /api/scenarios/channel-buffered     # Buffered channel (capacity 3)
POST /api/scenarios/channel-fan-out      # Fan-out pattern (1 producer, 3 workers)
```

### Realistic Service Simulations

```
POST /api/scenarios/order-processing?fail=false       # E-commerce order flow
POST /api/scenarios/user-registration?failEmail=false  # Registration with parallel setup
POST /api/scenarios/report-generation?timeout=false    # Data pipeline with parallel delivery
```

### Pattern Scenarios

```
GET  /api/scenarios/patterns                      # List all patterns
POST /api/scenarios/patterns/retry                # Exponential backoff retry
POST /api/scenarios/patterns/producer-consumer    # Channel-based communication
POST /api/scenarios/patterns/fan-out-fan-in       # Work distribution
POST /api/scenarios/patterns/supervisor           # Error isolation
POST /api/scenarios/patterns/circuit-breaker      # Resilience pattern
```

### Flow Scenarios

```
GET  /api/scenarios/flow              # List flow scenarios
POST /api/scenarios/flow/simple       # Emit and collect 5 values
POST /api/scenarios/flow/operators    # Chain map and filter
POST /api/scenarios/flow/stateflow    # Mutable state with observers
POST /api/scenarios/flow/sharedflow   # Broadcast to subscribers
```

### Custom Scenario

```
POST /api/scenarios/custom
Content-Type: application/json
```

Request body:
```json
{
  "name": "My Custom Scenario",
  "description": "Optional description",
  "sessionId": "optional-session-id",
  "root": {
    "id": "root-coro",
    "label": "root",
    "parentId": null,
    "actions": [
      {"type": "delay", "params": {"durationMs": "100"}},
      {"type": "log", "params": {"message": "Hello"}}
    ],
    "children": [
      {
        "id": "child-1",
        "label": "worker",
        "parentId": "root-coro",
        "actions": [
          {"type": "throw", "params": {"exceptionType": "RuntimeException", "message": "Test error"}}
        ],
        "children": []
      }
    ]
  }
}
```

Action types: `delay`, `throw`/`exception`, `log`, `custom`

---

## Synchronization Primitives

### List Sync Scenarios

```
GET /api/sync/scenarios
```

### Mutex Scenarios

```
GET /api/sync/mutex/counter          # Thread-safe counter
GET /api/sync/mutex/bank-transfer    # Consistent lock ordering
GET /api/sync/mutex/cache            # Read-through pattern
GET /api/sync/mutex/deadlock-demo    # Intentional deadlock detection
```

### Semaphore Scenarios

```
GET /api/sync/semaphore/connection-pool     # DB connection limiting
GET /api/sync/semaphore/rate-limiter        # API throttling
GET /api/sync/semaphore/file-processor      # I/O throttling
GET /api/sync/semaphore/resource-timeout    # Timeout handling
GET /api/sync/semaphore/producer-consumer   # Bounded buffer
```

### Combined Scenarios

```
GET /api/sync/combined/ecommerce    # Mutex + Semaphore order processing
```

Response `200`:
```json
{
  "success": true,
  "sessionId": "sync-scenario-name",
  "scenario": "Thread-Safe Counter",
  "message": "Scenario completed successfully",
  "eventCount": 25
}
```

---

## Validation

### List Validation Rules

```
GET /api/validate/rules
```

Response `200`: Array of rule objects with `id`, `name`, `description`, `category`, `severity`.

Categories: Lifecycle, Hierarchy, Structured Concurrency, Threading, Resource, Exception Handling.

### Validate Session (Legacy)

```
POST /api/validate/session/{id}
```

Response `200`:
```json
{
  "sessionId": "string",
  "results": [...],
  "timing": {...},
  "appConnected": true,
  "stillRunning": 3
}
```

`appConnected` is true while an app streams into the session. Its unfinished coroutines (`stillRunning`) are then reported as still running, not as "started but never terminated".

### Comprehensive Validation Report

```
POST /api/validate/session/{id}/report
```

Response `200`:
```json
{
  "sessionId": "string",
  "score": 85,
  "findings": [...],
  "summary": {
    "total": 2,
    "errors": 0,
    "warnings": 2,
    "info": 0,
    "ruleCount": 25,
    "categoriesChecked": ["threading", "resource"]
  },
  "timing": {...},
  "legacyResults": [...]
}
```

---

## Event Types (48+)

| Category | Events |
|----------|--------|
| **Coroutine** | CoroutineCreated, CoroutineStarted, CoroutineSuspended, CoroutineResumed, CoroutineBodyCompleted, CoroutineCompleted, CoroutineCancelled, CoroutineFailed |
| **Job** | JobStateChanged, JobJoinRequested, JobJoinCompleted, JobCancellationRequested |
| **Channel** | ChannelCreated, ChannelSendStarted, ChannelSendSuspended, ChannelSendCompleted, ChannelReceiveStarted, ChannelReceiveSuspended, ChannelReceiveCompleted, ChannelClosed, ChannelBufferStateChanged |
| **Flow** | FlowCreated, FlowCollectionStarted, FlowCollectionCompleted, FlowCollectionCancelled, FlowValueEmitted, FlowValueTransformed, FlowValueFiltered, FlowOperatorApplied, FlowBackpressure, FlowBufferOverflow |
| **Reactive** | StateFlowValueChanged, SharedFlowEmission, SharedFlowSubscription |
| **Deferred** | DeferredAwaitStarted, DeferredAwaitCompleted, DeferredValueAvailable |
| **Sync** | MutexCreated, MutexLockRequested, MutexLockAcquired, MutexUnlocked, MutexTryLockFailed, MutexQueueChanged, SemaphoreCreated, SemaphoreAcquireRequested, SemaphorePermitAcquired, SemaphorePermitReleased, SemaphoreTryAcquireFailed, SemaphoreStateChanged, DeadlockDetected, PotentialDeadlockWarning |
| **Dispatcher** | DispatcherSelected, ThreadAssigned |
| **Select** | SelectStarted, SelectClauseRegistered, SelectClauseWon, SelectCompleted |
| **Actor** | ActorCreated, ActorMessageSent, ActorMessageProcessing, ActorMessageProcessed, ActorStateChanged, ActorMailboxChanged, ActorClosed |
| **Other** | WaitingForChildren, SuspensionPoint, AntiPatternDetected, ValidationFindingEmitted |

---

## Common Response Codes

| Code | Meaning |
|------|---------|
| `200` | Success |
| `201` | Session created |
| `400` | Missing required parameters |
| `404` | Session or resource not found |
| `500` | Scenario execution failed |

## Design Patterns

- **Replay + Live**: SSE endpoints replay history first, then stream new events
- **Session Isolation**: Each session has independent event store and snapshot
- **Ad-hoc Sessions**: Sessions created automatically via `?sessionId` query params
- **Event Kind Injection**: VizEvent serialization adds `kind` field automatically
