# IntelliJ Plugin — Native Redesign (live coroutine tree + debugging)

**Date:** 2026-06-30
**Status:** Design — pending review
**Supersedes:** the Phase 13 JCEF/loopback embedded-frontend approach (`VizcoreToolWindowFactory` JCEF view + `LoopbackFrontendServer`)

## Problem

The shipped Phase 13 plugin embeds the *entire* vizcore React SPA inside an IntelliJ JCEF tool
window, fed by an in-plugin loopback HTTP server that proxies `/api` to the backend and serves the
bundled SPA. In practice this is the wrong design for an IDE:

- It cannot do the things that make an IDE plugin worth using — jump-to-source, editor gutter
  markers, editor↔view selection sync — because those require native IntelliJ APIs a web view
  can't reach without a clunky JS↔plugin bridge.
- It carries a large, fragile stack: JCEF availability/fallback, a loopback server with an SSE
  streaming proxy and path-traversal guard (the Phase-13 CR-01/02/03 bug surface), a `?correlation=`
  deep-link, and a ~13 MB bundled SPA — and inherits the "hidden tab pauses animation/polling" trap.
- Observed during live UAT: the user could not see live coroutine activity in the embedded view.

## Goal

Replace the embedded web view with a **fully native IntelliJ tool window**: a **live coroutine
tree** as the spine, with **debugging** built in (jump-to-source, "why is it stuck?", per-coroutine
lifecycle inspector, leak highlighting). The plugin becomes a plain Kotlin *consumer* of the backend
`/api` — no browser, no loopback server.

### Non-goals

- No backend data-model changes — all required data already exists (`HierarchyNode`,
  `SuspensionPoint`, `CoroutineTimeline`, source attribution).
- No changes to the agent or the run-config injection (Phase 13 DD-1/DD-2 are correct and kept).
- No SSE-in-plugin for v1 (poll-based; SSE is a possible later optimization).
- The web frontend (`:3100`) is untouched and remains the standalone browser experience.

## Approach (decision)

**Fully native (Swing / IntelliJ UI), poll-based, web-embedding stack deleted.** Alternatives
considered and rejected: (B) a slim purpose-built web view still in JCEF — keeps the disliked
browser stack and still needs native code for the editor features anyway; (C) hybrid native +
web-graph — YAGNI.

## Architecture & data flow

```
app + agent ──events──▶ backend (/api, default :8080, configurable)   (unchanged, Phase 13)

IntelliJ plugin (native Kotlin, no browser):
  RunWithVisualizerAction: mint correlation → health-check → inject agent → arm correlation → open tool window
  tool window opens
    → read armed correlation UUID
    → GET /api/sessions/resolve?correlation=<uuid>   (poll until it resolves to a sessionId)
    → poll GET /api/sessions/{id}/hierarchy + /metrics  every ~150–300ms  → tree + tiles + leaks
    → on select: GET /…/coroutines/{id}/timeline   → inspector + source locations
    → jump-to-source via OpenFileDescriptor;  (fast-follow) gutter markers via MarkupModel
```

> Backend port is whatever `VizcoreSettings.backendUrl` is configured to (default `http://localhost:8080`).
> Do NOT hardcode `:8090` — that was a dev-session override; `:8090` is also the *legacy* push-based
> EventReceiver port (ADR-010/014), unrelated to the `/api` this plugin consumes.

The correlation mechanism survives intact — consumed via `/api/sessions/resolve` in Kotlin instead
of a browser URL query param.

## Components

### Kept (Phase 13), unchanged
- `coroutine-viz-agent` — zero-code `-javaagent` DebugProbes capture.
- `AgentJarExtractor`, `VizcoreRunConfigurationExtension` — agent jar extraction + `-javaagent` /
  `-XX:+EnableDynamicAgentLoading` injection.
- `BackendHealthCheck` — SSRF-guarded pre-launch health check.

### Kept, simplified
- `RunWithVisualizerAction` — drop the loopback-server start and bound-port arming; keep correlation
  mint, health-check, agent inject, open tool window.
- `VizcoreLaunchState` — keep the armed correlation (per-config one-shot + project-scoped); drop
  `port` / `viewUrl()`.
- `VizcoreSettings` / `VizcoreSettingsConfigurable` — add poll interval (default 200 ms). (No
  client-side leak-threshold setting — the backend owns the threshold; see leak handling below.)

### New
- `VizcoreApiClient` — Kotlin consumer over `java.net.http`: `resolve(correlation)`,
  `hierarchy(sessionId)` (the tree source — `HierarchyNode` carries name/state/dispatcher/timing/
  exception/children), `metrics(sessionId)` (`MetricsResponse` — active/peak/dispatcherUtilization
  and **server-computed `leaks` + `leakThresholdMs`**), `timeline(sessionId, coroutineId)` (inspector).
  Parses the existing wire JSON into plugin-side models. Pure, headless-testable. (The thin
  `GET /sessions/{id}` snapshot is redundant with `/hierarchy` for the tree and is NOT on the hot
  path; use it only if a lightweight session-existence check is needed.)
- `SessionPollingService` — project `@Service`, `Disposable`. Owns the poll loop (background,
  cancel-on-dispose; never `GlobalScope`), polls `/hierarchy` + `/metrics`, exposes an observable
  `SessionModel`. **Leaks come from `/metrics` (server-computed), not recomputed client-side** —
  consistent with the web FE, and avoids duplicating leak/threshold logic the backend owns. Freeze
  toggle pauses polling.
- `SessionModel` — observable snapshot of the current session (hierarchy tree + metrics incl. the
  server-provided leak set). UI listens for change events.
- `CoroutineTreeModel` + `CoroutineTreeRenderer` — `Tree` model built from `HierarchyNode`; diffs new
  hierarchy into the existing model preserving expansion + selection; `ColoredTreeCellRenderer` draws
  state dot · name · state badge · dispatcher·reason chip · ~age · child count; state-change **flash**
  via a Swing `Timer`; rows whose id is in the server leak set (`/metrics.leaks`) are amber-tinted
  with a "⚠ ~Xs" suffix.
- `InspectorPanel` — IntelliJ UI DSL; "Suspended at" (reason, `file:line`, user-bold/library-dim
  stack, Jump), "Launched at" (`file:line`, Jump), Timing (active/suspended/total ≈ + mini timeline).
- `MetricTilesPanel` — coroutines / active / suspended / leak-risk / dispatchers.
- `VizcoreToolWindowFactory` (new, native) — bottom-anchored, horizontal split: tree left, inspector
  right (collapses when nothing selected); header metric tiles; states: not-launched / connecting /
  backend-unreachable.
- `SourceNavigator` — resolve `fileName`(+`className`) + `lineNumber` → `VirtualFile` via project-
  scoped `FilenameIndex`/class index → `OpenFileDescriptor(project, file, line-1).navigate(true)`.
  Graceful fallback to plain text when unresolved.

### Deleted (web-embedding stack)
- `LoopbackFrontendServer` + `LoopbackFrontendServerTest`
- `LoopbackServerService`
- `VizcoreToolWindowFactory` (JCEF version) — replaced by the native one
- `VizcoreViewUrl` + `VizcoreViewUrlTest`
- `JcefFallbackTest`
- packaging: the `pnpm build` → `/frontend` resource wire (the `/agent` jar wire stays)

## UI/UX decisions (validated via sketches)

- **Layout:** bottom anchor, tree-left / inspector-right (sketch Variant A).
- **Live cue:** state-change **flash** (brief tint + fade), not web animation (sketch Section C).
- **Palette:** vizcore convention — blue=running, amber=suspended **and leak** (never red), green=
  completed, red=failed, gray=created; dark-first, Inter + JetBrains Mono, `JBColor` for theme-
  awareness.
- Sketches: `.planning/sketches/13-plugin-redesign/plugin-redesign.html` (all screens/states) and
  `plugin-layout-variants.html` (layout A/B + flash demo).

## Scope split

- **v1 (must-have):** native tree + inspector + flash + metric tiles + **jump-to-source** + leak
  highlighting + tool-window states. Loopback/JCEF stack deleted.
- **Fast-follow (spike-gated):** editor **gutter markers** (▶ launch / ⏸ suspend), click ↔ tree.
  Highest-risk piece (live runtime data → editor `MarkupModel`); prove with a spike before committing
  design to it.

## Error handling

- Backend unreachable on open → "Backend not reachable" state with Open-settings / Retry (reuse
  `BackendHealthCheck`).
- Correlation not yet resolvable → "Connecting to your app…" state; keep polling `resolve` with a
  bounded, backoff-free interval until a session appears or the user closes the window.
- Source unresolved → render the path as plain (non-clickable) text; never throw.
- Poll failure mid-session → keep last model, show a transient "reconnecting" hint; resume on success.
- All background work is structured-concurrency, cancelled on tool-window/project dispose.

## Testing strategy

Mirror the existing plugin discipline (currently 25 green tests).

- **Headless unit:** `VizcoreApiClient` JSON parsing; `CoroutineTreeModel` diff (expansion/selection
  preserved across updates); `/metrics` parsing + leak-set rendering; `SourceNavigator` resolution
  logic (match / ambiguous / unresolved); metric counts.
- **Stub-backend integration:** `SessionPollingService` against an in-test `java.net.http` server
  (the pattern the deleted `LoopbackFrontendServerTest` used) — resolve→poll→model updates.
- **Light platform tests:** tool-window factory states; `OpenFileDescriptor` navigation to a fixture
  file.
- Gates unchanged: detekt + ktlint clean; `verifyPlugin` Compatible; `buildPlugin` produces a
  (smaller) zip.

## Risks

- **Gutter markers** (fast-follow): live runtime → editor markup is IntelliJ-internals-heavy →
  mitigated by spike-gating and shipping jump-to-source first.
- **Source resolution ambiguity** (same simple filename in multiple modules): mitigate with
  `className` disambiguation where available + project-scope filtering; fall back to non-clickable.
- **Poll cost** at large coroutine counts: acceptable at expected scale (dozens); SSE is the escape
  hatch if needed.

## Open questions

- Exact poll cadence (150 vs 200 vs 300 ms) — default 200 ms, expose in settings.
- Whether to keep the `?correlation=` branch in the web FE (harmless; leave as-is).
