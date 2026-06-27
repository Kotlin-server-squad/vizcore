# Milestones

## v1.1 Real-Code Coroutine Observability (Shipped: 2026-06-27)

**Delivered:** Point vizcore at a developer's own running Kotlin app (separate JVM), stream its coroutine events to the backend, and render them live in the existing FE with source attribution (file:line) and aggregate metrics — plus a greenfield connect/onboarding surface.

**Phases:** 8 (06, 07, 08, 08.1–08.5) · **Plans:** 21 · **Tasks:** 28
**Timeline:** 2026-06-24 → 2026-06-27 (~4 days)
**Git range:** ~110 commits, 135 files changed (~+13.2k / −0.8k incl. planning docs)
**Requirements:** 9/9 satisfied (RCO-01..07, FE-ALIGN, ONB-01)
**Audit:** `milestones/v1.1-MILESTONE-AUDIT.md` — status `tech_debt` (all reqs met, no blockers; 11/11 integration seams wired)
**Known deferred items at close:** 6 (ONB-01 wizard auto-resolve + 5 bookkeeping/v1.0-era backlog — see STATE.md Deferred Items)

### Key accomplishments

**Phase 06 — Pluggable instrumentation source (RCO-01/02/03)**
- `InstrumentationSource` interface fronting the `EventBus` + `WrapperSource` (existing wrappers formalized, zero behavior change) + composable session-created/closed listener registries.
- `DebugProbesSource`: polls `dumpCoroutinesInfo()` (~150 ms), diffs snapshots by coroutine identity, and synthesizes existing `VizEvent`s with source attribution (function + `file:line` from the creation stack, dispatcher, `CoroutineName`).

**Phase 07 — Real-app transport (RCO-04/05)**
- New `coroutine-viz-client` module: `VizcoreClient.start(appName, backendUrl, token)` authenticates via JWT, creates a server session, drives a `DebugProbesSource`, and forwards events over a Ktor-client WebSocket with reconnect/backoff.
- Backend ingest: auth-scoped, tenant-filtered `webSocket(/api/sessions/{id}/ingest)` → shared `appJson` deserialize → `VizSession.send`, reusing the existing EventStore→EventBus→SSE→FE path.
- CR-01 closed: a lifetime-scoped `OutboundBuffer` bridges the startup + reconnect-backoff windows so no event is lost (surfaced `dropped` counter, zero-loss proven).

**Phase 08 — Live real-app view + metrics (RCO-06/07)**
- DebugProbes hierarchy reconstruction (parent/scope propagation) so the existing `ProjectionService`/`CoroutineTree` populate hierarchically with zero FE change.
- Per-session `MetricsProjection` (active/peak/throughput/dispatcher-util/age-threshold leaks, wall-clock leak-age basis proven across a restart boundary) + tenant-scoped `GET /api/sessions/{id}/metrics`.
- Active-only "What's running now" live view + metrics panel + warning-styled leak list via a poll-while-live `useSessionMetrics` hook.

**Phases 08.1–08.5 — Source attribution end-to-end + FE sketch alignment (RCO-06, FE-ALIGN, ONB-01)**
- 08.3: implemented the timeline projection so per-coroutine `suspensionPoint` source frames reach the FE end-to-end (DTO + OpenAPI `SuspensionPoint` + regenerated api-types + reconciled FE contract); live Chrome UAT confirmed a real `file:line` renders and clipboard-copies (RCO-06 delivered).
- 08.4: deleted 8 duplicate-FQN `:backend` model/sync files that shadowed the SDK on the runtime classpath + added a permanent `verifyNoDuplicateSourceFqns` Gradle guard wired into `check` (closed CR-01 structurally).
- 08.1/08.2/08.5: realigned the FE to the validated sketch winners — `LiveDockPanel` IDE-dock (metric-tile header strip, live list, inline amber leaks), inline compact-chips → expand-to-full-stack `CoroutineSourceStack` with jump-to-code preserved, badged LIVE/DEMO `SessionsSidebar`-as-home + 3-step `ConnectWizard` (FE-ALIGN, ONB-01).

### Known gaps / deferred

- **ONB-01 (tech-debt):** the `ConnectWizard` auto-resolve polls its own minted session, decoupled from the separate session the real `VizcoreClient.start(appName)` creates — so auto-navigate / "Skip to live view" don't reach the connected app's session (it remains reachable via the badged sidebar). Onboarding UX polish; pipeline unaffected. Candidate for a small follow-up slice (08.6 or v1.2).
- 5 bookkeeping / pre-existing items (superseded 08.2 human-verify, missing Nyquist VALIDATION.md for 07/08.1/08.2/08.3/08.4, ~23 v1.0/Phase-3-era todos + 1 quick-task) — see STATE.md Deferred Items.

---
