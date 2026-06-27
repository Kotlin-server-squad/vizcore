# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v1.1 — Real-Code Coroutine Observability

**Shipped:** 2026-06-27
**Phases:** 8 (06, 07, 08, 08.1–08.5) | **Plans:** 21 | **Tasks:** 28
**Requirements:** 9/9 satisfied (RCO-01..07, FE-ALIGN, ONB-01) | **Audit:** `tech_debt` (no blockers, 11/11 integration seams wired)

### What Was Built
- A pluggable `InstrumentationSource` layer + `DebugProbesSource` that captures any running app's coroutines (poll/diff/synthesize) with source attribution — no manual wrapping.
- An embeddable `coroutine-viz-client` module + WebSocket ingest endpoint that streams a remote JVM app's coroutines into a backend session, reusing the whole existing EventStore→EventBus→SSE→FE path (zero-loss across reconnect via a lifetime-scoped `OutboundBuffer`).
- A live real-app view: hierarchy reconstruction, per-coroutine `file:line` source frames with jump-to-code, aggregate metrics (active/peak/throughput/dispatcher-util/leaks), an IDE-docked FE layout, and a badged LIVE/DEMO sessions home + connect wizard.

### What Worked
- **Reuse-first transport design.** The client→backend ingest path published into the existing session pipeline, so SSE/Projection/FE were pure reuse — the new surface area was small and the FE largely unchanged for live render.
- **Backward-discovery of a runtime-only bug.** The 08.2→08.3→08.4 chain caught a same-FQN model-shadowing trap that unit tests (compiled per-module in isolation) structurally could not — then closed it permanently with an assembled-app test + a `verifyNoDuplicateSourceFqns` build guard.
- **Sketch-validated FE alignment.** The `sketch-findings-vizcore` design contract gave 08.1/08.2/08.5 concrete winning markup to converge on, avoiding open-ended FE churn.
- **The milestone audit earned its keep.** Phase verifications all passed in isolation; the cross-phase integration check is what surfaced the ONB-01 wizard/session decoupling that no single phase saw.

### What Was Inefficient
- **FE alignment took three insertions (08.1, 08.2, 08.5) plus a backend insertion (08.3).** The original Phase 8 shipped code that wasn't user-reachable (unmounted components) and didn't match the validated sketch — surfaced only at live UAT. Aligning the FE to the locked sketch *before* building Phase 8 would have collapsed four phases into one.
- **A backend stub shipped undetected (08.3).** `ProjectionService.getCoroutineTimeline` returned `events: []`; the FE drawer was built and mounted (08.2) against an endpoint that had no data, so the gap only appeared in live Chrome UAT.
- **Contract drift between hand-written FE `api.ts` and generated types** required a reconciliation pass (08.3-02, `tsNanos` vs `timestamp`).

### Patterns Established
- **Live-app E2E features need a live-UAT gate, not just green unit tests.** jsdom/mocked tests pass while the real backend returns empty or the real client mints a different session id. Add a 3-process demo-harness live UAT as an explicit wave for any real-app pipeline phase.
- **Cross-module duplication gets a build guard, not a code-review note.** The `verifyNoDuplicateSourceFqns`-into-`check` pattern is the template for "tests can't see this; the build must."
- **Assembled-app (Ktor Test Host over the real classpath) tests** are required to catch shadowing/wiring bugs that module-isolated tests miss.

### Key Lessons
1. **Mount + data + shape must land together.** A surface (08.2), its data (08.3 backend stub), and its contract (08.3-02 generated types) shipping in separate phases produced an empty, unreachable, drifted feature until all three converged. Slice vertically (surface→data→contract) per feature, not horizontally.
2. **Lock the FE design contract before building the FE.** Phase 8 built a live view that then needed three alignment passes to match the already-validated sketch winners.
3. **The integration audit is the milestone's unique value.** Per-phase verification can be 8/8 green while a cross-phase seam (wizard session id ≠ client session id) is silently broken.

### Cost Observations
- Model mix: not instrumented this milestone.
- Sessions: not tracked precisely (work spanned ~4 days, 2026-06-24→27, ~110 commits).
- Notable: heavy use of zero-new-dependency constraints (IN-12 literal-Tailwind, no new FE/BE deps across 08.x) kept the surface area auditable.

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Phases | Plans | Key Change |
|-----------|--------|-------|------------|
| v1.1 | 8 | 21 | First formal milestone close; introduced live-UAT gates + build-guards for runtime-only bugs |

### Cumulative Quality

| Milestone | FE Tests | New Deps Added | Notes |
|-----------|----------|----------------|-------|
| v1.1 | 507 (vitest, end of 08.5) | 0 across 08.x (1 new Gradle module `coroutine-viz-client`) | zero-new-dep discipline held |

### Top Lessons (Verified Across Milestones)

1. *(first milestone — trends accumulate from here)* Live-app/E2E features require a live-UAT gate beyond unit tests.
2. *(first milestone)* Cross-phase integration audit catches seams that all-green per-phase verification misses.
