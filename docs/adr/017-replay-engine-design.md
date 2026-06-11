# ADR-017: Replay Engine Design

## Status
Accepted

## Date
2026-03-18

## Context
The backend stores events with sequence numbers (`seq`) and timestamps, but the frontend has no way to replay a completed session or step through events for analysis. Time-travel debugging of coroutine execution is a key differentiator for this tool. Users need to scrub through events to understand execution order, spot race conditions, and debug timing issues.

## Decision
Implement a client-side replay engine in the React frontend. The backend already stores all necessary data — no backend changes are required.

### ReplayController Component
A toolbar component rendered inside `SessionDetails` when viewing a completed (or paused) session:

```
[|<] [<] [>||] [>] [>|]  [====o=========] 42/318  [0.5x] [1x] [2x] [5x]
 rew  step play step fast   progress bar   counter    speed selector
 start back     fwd  fwd
```

Controls:
- **Play/Pause** — toggle continuous playback
- **Step Forward** — advance one event
- **Step Back** — rewind one event
- **Rewind to Start** — reset to event 0
- **Fast Forward** — jump to the last event
- **Progress Scrubber** — drag to any event position
- **Speed Selector** — 0.5x, 1x, 2x, 5x playback speed

### State Machine
Manage playback state with a React state machine (useReducer):

```
States: IDLE | PLAYING | PAUSED | STEPPING_FORWARD | STEPPING_BACK

Transitions:
  IDLE       → PLAYING          (play)
  IDLE       → STEPPING_FORWARD (step-forward)
  PLAYING    → PAUSED           (pause)
  PLAYING    → IDLE             (reached-end)
  PAUSED     → PLAYING          (play)
  PAUSED     → STEPPING_FORWARD (step-forward)
  PAUSED     → STEPPING_BACK    (step-back)
  PAUSED     → IDLE             (stop)
  STEPPING_* → PAUSED           (step-complete)
```

### Event Rendering During Playback
- Events are sorted by `stepIndex` (primary) and `timestamp` (secondary)
- A `currentEventIndex` pointer tracks the playback position
- All panels (CoroutineTree, ThreadLanes, EventsList, DispatcherOverview) receive a `visibleEvents` slice: `events.slice(0, currentEventIndex + 1)`
- During playback, `currentEventIndex` advances on a `setInterval` timer. The interval is `baseDelay / speed` where `baseDelay` is derived from the time delta between consecutive events (clamped to 50ms-2000ms range to avoid too-fast or too-slow playback)

### useReplayEngine Hook
```typescript
function useReplayEngine(events: VizEvent[]) {
  // Returns:
  //   state: ReplayState
  //   currentIndex: number
  //   visibleEvents: VizEvent[]
  //   controls: { play, pause, stop, stepForward, stepBack, seekTo, setSpeed }
}
```

### Panel Synchronization
All visualization panels subscribe to the same `visibleEvents` array. When the replay index changes:
1. `CoroutineTree` rebuilds the hierarchy from visible events only
2. `ThreadLanesView` shows thread assignments up to the current event
3. `EventsList` scrolls to and highlights the current event
4. `DispatcherOverview` reflects the state at the current point in time
5. Channel/Flow panels update their state accordingly

### Keyboard Shortcuts
- `Space` — play/pause
- `ArrowRight` — step forward
- `ArrowLeft` — step back
- `Home` — rewind to start
- `End` — fast forward to end
- `1-4` — set speed (0.5x, 1x, 2x, 5x)

## Alternatives Considered

### Server-Driven Replay via SSE
Re-emit events from the backend over a new SSE connection with controlled timing. This would reuse the existing SSE infrastructure but adds network latency to every step, makes scrubbing slow (requires round-trip), and puts load on the server for a fundamentally client-side operation.

### WebSocket Bidirectional Protocol
Use WebSockets for play/pause/seek commands from client and event delivery from server. Over-engineered for replay — the client already has all the events. WebSockets add connection management complexity with no benefit when data is fully client-side.

### requestAnimationFrame Instead of setInterval
Using rAF would tie playback to the display refresh rate, which is smoother for animations but makes speed control harder to implement and pauses when the tab is backgrounded (which could be desirable or confusing).

## Consequences

### Positive
- Zero backend changes — works with the existing event store and API
- Instant scrubbing since all events are already loaded in the client
- All panels stay synchronized through a single shared state
- Keyboard shortcuts enable efficient debugging workflows
- State machine prevents invalid state transitions (e.g., stepping while playing)

### Negative
- Large sessions (100K+ events) may cause UI lag during scrubbing — may need virtualization or progressive loading
- Client-side replay means the replayed state is a reconstruction, not the actual runtime state — edge cases in event ordering may produce slightly different visualizations
- All events must be loaded upfront, increasing initial memory footprint for large sessions
- Adding new panels requires integrating them with the replay engine's `visibleEvents` contract

## Related
- ADR-011: Animation System Design (animations should respect replay speed)

## See also
- ADR-018: Export System Design — video export builds on the replay engine. The dependency runs one way (ADR-018 → ADR-017); this is an advisory back-reference, not a dependency edge.
