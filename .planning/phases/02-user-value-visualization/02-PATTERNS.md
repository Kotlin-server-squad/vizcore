# Phase 2 — Pattern Map

> New/changed files mapped to their closest existing analog. Planner: new code MUST match the analog's structure, naming, and test conventions.
> Derived from direct codebase read (see 02-RESEARCH.md Sources). Analog file:line cited.

---

## Frontend — new modules

| New file (planned) | Role | Closest analog | What to copy from the analog |
|--------------------|------|----------------|------------------------------|
| `frontend/src/lib/export-svg.ts` | Serialize an `<svg>` panel to a standalone download | `frontend/src/lib/export-png.ts` | Async fn taking `(el, filename?)`; `Blob` + `URL.createObjectURL` + temp `<a>` click + `revokeObjectURL` in `finally` (export-png.ts:33-43); default filename `coroutine-viz-{Date.now()}.svg` |
| `frontend/src/lib/record-replay.ts` | Pure WebM recording pipeline (codec cascade, capture loop, duration estimate, abort) | `frontend/src/lib/export-png.ts` (download idiom) + `frontend/src/lib/thread-lanes.ts` (pure lib pattern) | Keep React-free: take refs/callbacks, return controls; download via the export-png Blob idiom |
| `frontend/src/lib/projections/project-coroutines.ts` | `VizEvent[]` → `CoroutineNode[]` (replay OQ-1) | `frontend/src/lib/thread-lanes.ts` `buildThreadLanes` (pure events→viewmodel reducer) + backend `RuntimeSnapshot`/`ProjectionService.kt` (authoritative state machine — reimplement in TS) | Pure function, `useMemo`-friendly; output shape MUST match what `buildCoroutineTree(coroutines)` consumes |
| `frontend/src/lib/projections/project-thread-activity.ts` | `VizEvent[]` → `ThreadActivity` | same as above | Output shape MUST match `buildThreadLanes` input (`Map<threadId, ThreadEvent[]>`) |
| `frontend/src/components/export/ExportMenu.tsx` | Dropdown: PNG / SVG / JSON / Record (ADR-018) | `ReplayController.tsx` Dropdown usage (`:116-135` `Dropdown`/`DropdownTrigger`/`DropdownMenu`/`DropdownItem`) | HeroUI `Dropdown` idiom; icon buttons via `react-icons/fi` |
| `frontend/src/components/comparison/SyncedTreePair.tsx` | Two side-by-side trees w/ selection sync + delta badges (D-19/D-20) | `CoroutineTreeGraph.tsx` (tree render) + `ComparisonView.tsx:234,248` (warning/secondary delta chips) | Reuse `warning` (A-only) / `secondary` (B-only) `Chip` colors; lift `selectedCoroutineId` |
| `frontend/src/routes/compare.tsx` | `/compare` route w/ `?a=&b=` (D-10) | `frontend/src/routes/{gallery,scenarios,sessions}` + `routes/index.tsx`, `__root.tsx` | TanStack file route; `validateSearch` for `a`/`b`; mount `<ComparisonView>` |

## Frontend — changed files

| File | Change | Pattern to preserve |
|------|--------|---------------------|
| `frontend/src/components/replay/ReplayController.tsx` | Add Stop (=seek 0 + paused), FastForward (seek last), recording state (red dot+elapsed+Stop, D-23), keyboard shortcuts | Keep existing `ButtonGroup`/`Slider`/`Dropdown` structure (`:75-135`), `Fi*` icons, `aria-label` on every icon button, `data-testid` |
| `frontend/src/components/SessionDetails.tsx` | Mount sticky ReplayController (D-13) above `<Tabs>` (`:389`); add replay-vs-live data source (`:70`); REPLAY chip + new-events badge (D-15); "live data — not replayed" notice on projection tabs | Mirror the existing `allEvents = streamEnabled ? liveEvents : storedEvents` ternary; reuse `useMemo` derivations; keep tab structure |
| `frontend/src/hooks/use-event-stream.ts` | Gate `invalidateQueries`/`refetch` while replay active (D-02); keep EventSource open | DO NOT touch dedup (`seenSeqsRef`), bounded retry, max-wait debounce (`:230-255`) — gate only the invalidation effect |
| `frontend/src/hooks/use-replay.ts` | Reconcile delay clamp 10ms→50–2000ms (RPLY-02) | One-line change at `:97-98` (see RESEARCH Code Examples); keep the hook's API |
| `frontend/src/lib/export-png.ts` | Add ADR-018 info header (session name/timestamp/event count) (D-08) | Keep `html2canvas` opts (scale 2, bg `#18181b`, useCORS); composite header before capture |
| `frontend/src/lib/api-client.ts` | `compareSessions` URL → `/sessions/compare?a=&b=` (D-09) | Keep `fetchJson` idiom (`:134-138`) |
| `frontend/src/components/comparison/ComparisonView.tsx` | Seed A/B from route search params; render `<SyncedTreePair>` below tables (D-11/D-19) | Keep existing `Card`/`Select`/`Table` structure + `data-testid`s |
| `frontend/src/App`/`__root.tsx` provider | Mount `<ToastProvider>` (HeroUI 2.7) before any `addToast` | Mount at root, above routes (issue #5086 ordering) |

## Backend — changed files

| File | Change | Pattern to preserve |
|------|--------|---------------------|
| `backend/.../routes/ComparisonRoutes.kt` | `/api/compare?sessionA=&sessionB=` → `/api/sessions/compare?a=&b=` (D-09); keep `getSession` null→404 (D-12) | Keep `BadRequest` on missing params, `NotFound` on unknown id (`:15-39`), `respond(OK, comparison)` |
| `backend/coroutine-viz-core/.../session/ComparisonService.kt` | Add thread-utilization delta field to `SessionComparison` (CMPR-01) | Match existing `@Serializable data class` + the `computeDurationNanos` private-helper style (`:119`) |
| `backend/src/main/resources/openapi/documentation.yaml` | Document new compare path + thread-delta field | Hand-maintained YAML (Phase-1 convention) |
| `shared/api-types/openapi.json` + regen | Sync copy; `openapi-typescript openapi.json -o generated.ts` | `shared/api-types/package.json` `generate` script |
| `backend/src/main/.../events/`, `.../checksystem/` | Delete FQCN forks; resolve against core; land TimingAnalyzer ns→ms fix in core | Same big-bang delete + compile-against-core pattern as Phase 1 FND-01 |
| `backend/src/test/.../ForkDeletionTest.kt` | Extend to assert zero classes under `events/` + `checksystem/` | Match existing fork-guard assertion style |

## New tests — mirror these existing test files

| New test | Mirror | Key technique |
|----------|--------|---------------|
| `export-svg.test.ts`, `record-replay.test.ts` | `frontend/src/lib/export-png.test.ts` | `vi.mock` browser APIs; mock `URL.createObjectURL`/`document.createElement('a')` |
| `use-replay.test.ts` | existing hook tests under `frontend/src/hooks/*.test.ts` | `@testing-library/react` `renderHook`/`act` |
| `projections/*.test.ts` | `frontend/src/lib/*.test.ts` pure-fn tests | server snapshot as oracle (MSW fixture → `project*()` deep-equal) |
| `*Comparison*` backend test | `backend/src/test/.../routes/*Test.kt` (Ktor `testApplication`) | `testApplication { client.get("/api/sessions/compare?a=&b=") }` |
