# RESUME — vizcore deep-dive audit & feature verification

**Last session:** 2026-06-11
**Stopped at:** About to run the **browser-driven (MCP Chrome) runtime walkthrough** of each feature/scenario. Blocked only by Chrome-extension pairing (see below).

## How to resume the conversation
- `claude --continue` (resumes the most recent session in this repo) — or `claude --resume` and pick this session.
- If starting fresh instead, read these in order: `.planning/STATE.md`, `.planning/VERIFICATION.md`, `.planning/ROADMAP.md`, then this file.

## What is DONE (all committed)
1. **Ingest** (`549f5bd`): 31 design docs (27 ADRs + 2 PRDs + 1 SPEC + 1 DOC) → `.planning/intel/` + backbone `PROJECT.md / REQUIREMENTS.md / ROADMAP.md / STATE.md`. 0 blockers. 3 prior blockers resolved (persistence ADR-009/015 reconciliation; two ADR cross-ref cycles broken).
2. **Codebase map** (`9df6796`): `.planning/codebase/` — STACK, ARCHITECTURE, STRUCTURE, CONVENTIONS, TESTING, INTEGRATIONS, CONCERNS.
3. **Feature verification** (`8e49a27`): `.planning/VERIFICATION.md` — all 39 requirements verified (static code + test suites + adversarial pass). Workflow script: `.planning/verify-features.workflow.js`.

### Verification scoreboard (of 39)
- ✅ Works: **1** (PROD-03 externalized CORS)
- 🟡 Partial: **13**  • 🔴 Broken: **5**  • ⬜ Missing: **20**
- Test suites: frontend **221/221 pass**, backend **BUILD SUCCESSFUL** (plugin has **zero** tests).
- **Theme:** mostly "dead code" — features implemented + unit-tested in `coroutine-viz-core` / frontend but **never wired/mounted** at runtime.
- **5 prior findings all CONFIRMED:** (1) session fork `backend/src/main/.../session/*` shadows core; (2) unbounded EventStore; (3) EventSampler unwired; (4) `/api/*` auth OPEN (authenticatedApi never called; keys plaintext-compared); (5) IntelliJ run-action javaagent is a TODO stub.
- **Recommended next:** de-fork (FND-01) → wire bounded store + retention + regression test (FND-02/03, PERS-03) → close open auth (AUTH-01/05). Then `/gsd-plan-phase 1`.

## NEXT STEP — browser walkthrough (what we were about to do)
Goal: visually verify each feature/scenario in the running React UI via the `mcp__claude-in-chrome__*` tools, step by step, and write a runtime addendum to `VERIFICATION.md`.

### Restart the servers first (they die with the CLI)
```bash
# Backend → http://localhost:8080  (health: GET /health, NOT /api/health)
cd backend && ./gradlew run
# Frontend → http://localhost:3000  (proxies /api to :8080)
cd frontend && pnpm dev
```
Wait for readiness: `curl -s --retry 40 --retry-connrefused http://localhost:8080/health` and `curl -so/dev/null -w '%{http_code}' http://localhost:3000/`.

### Chrome MCP pairing (the blocker)
`mcp__claude-in-chrome__list_connected_browsers` returned `[]` last session. The `/chrome` slash command was NOT enough — the `claude-in-chrome` MCP server pairs separately. To fix: install/enable **Claude for Chrome** (https://claude.ai/chrome), log into the SAME claude.ai account, restart Chrome. Then:
`list_connected_browsers` → (AskUserQuestion to pick) → `select_browser` → `tabs_create_mcp` → `navigate`.

### Walkthrough map (frontend routes)
- `/` (home/index) — landing
- `/scenarios` + `/scenarios/builder` — scenario list + builder; scenario views: **Registration Flow**, **Order Processing**, pipeline stages
- `/sessions` + `/sessions/:sessionId` — session list + detail (visualization panels: tree, graph, timeline, thread lanes, events list, dispatcher overview; consumes **SSE**)
- `/gallery` — gallery

### Per-feature things to confirm at runtime (cross-check vs VERIFICATION.md)
- Create session + run a scenario → does the SSE stream render the tree/graph/timeline/thread-lanes live?
- **Replay controls** (RPLY) — report says ReplayController is built but NOT mounted → expect ABSENT in UI.
- **Export buttons** (EXPT) — ExportButton built but imported nowhere → expect ABSENT.
- **Session comparison view** (CMPR) — ComparisonView built but not routed → expect ABSENT (though `GET /api/compare` backend works).
- Watch console errors + `/api/` network/SSE requests throughout.
- Optional headless cross-check: drive REST+SSE with curl (create session, run scenarios, hit `/api/sessions/compare`, share endpoints, `/health`).

## Open decisions carried forward
- Business-model & KPI competing variants UNRESOLVED — BUSINESS_ANALYSIS_V2 is working default; not blocking engineering.
- ADR-010 (IntelliJ plugin) is Proposed/advisory, not locked.
