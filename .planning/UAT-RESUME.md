# UAT Deep-Dive — RESUME (paused 2026-06-22)

Collaborative browser UAT of Phase 1-3 (user clicks in MCP-connected Chrome, assistant guides + verifies).

## Running setup (restart if servers died)
- **Frontend** :3000 — `cd frontend && pnpm dev`
- **Backend** :8080 — DB mode + auth ON, seeded user. Restart with:
```bash
cd backend
export STORAGE_TYPE=database API_KEY=secret-key JWT_SECRET=uat-dev-secret-do-not-use-in-prod
export SEED_USER=alice SEED_ROLE=runner
export SEED_HASH_B64='JGFyZ29uMmlkJHY9MTkkbT0xNTM2MCx0PTIscD0xJGdMdWErbHZNOGNXbWhod2x3MkxHZ1h5djF6Z0lBZ2Z2MkFIc09Da2hXWTdONnBiRjRSdDRGVzV5dndMOUlOckRoWWU4ZnNnc2V1cVloV1h5M1B5U0pBJDVYMU5pVm4ybVhrbUdUYXFobDZBaGVtQ0pIMUhZdVVoZlpyS2RXdllsTlk='
./gradlew run
```
- **Login (real, via form):** `alice` / `vizcore123`  (F1 now fixed — base64 hash seed)
- **Health:** `curl localhost:8080/health` ; H2 file persists at `backend/data/`
- **Chrome:** MCP "Browser 1"; app at `http://localhost:3000`. Auth token lives in
  `localStorage['vizcore.jwt']`. Logout = clear that key + reload. No UI logout button (noted).
- **Test sessions:** `uat-deepdive-1782108875917` (alice, 56 events, has F6 dup-seq residue) ;
  `bob` private session for tenant test (mint bob JWT via JWT_SECRET, sub=bob).

## UAT progress
- [x] 1 Auth boundary + REAL login (F1 fixed) ✅
- [x] 2 Mint/share link ✅ (one active per session; "Create link" hidden when one exists — minor UX)
- [x] 3 Read-only /shared/:token view (anon, controls stripped) ✅
- [x] 4 Revoke → link dead ("This link is no longer available") ✅ (404 on DELETE = F4 double-fire)
- [x] 5 Tenant isolation: alice→bob session = 404 ✅
- [x] 6 Validation runs without crash (RT-02 fixed) ✅ + Timing Report
- [x] 7 Replay (scrub/play/0.5-5x speed) ✅
- [ ] 8 Export (PNG/SVG/JSON/WebM) — menu shows all 4; not yet exercised by user
- [ ] 9 Compare (/compare?a=&b=) — not started

## IN-PROGRESS feature request (where we paused)
User wants to **validate events were sent in exact/expected order**. Findings:
- `SequenceChecker.checkOrdering(events, expectedSequence)` EXISTS (SequenceChecker.kt:23) and
  `/api/validate/rules` ADVERTISES an "EventOrdering" rule — but `ValidationRoutes.kt:43-49`
  NEVER runs it (dead/advertised-only).
- **NEXT ACTION:** add an `EventsInExactOrder` rule (seq strictly increasing + contiguous: no gaps,
  no dups, no reorder), wire it into the validation run in ValidationRoutes, remove/fix the dead
  "EventOrdering" catalog entry. Then user re-runs Validation to see it.
- **Also log F7:** `ValidationRoutes` (and any other route) uses UNSCOPED `SessionManager.getSession`
  → validation isn't tenant-isolated (same class as F5; the read path was missed by 03-07).

## Findings ledger (todos/pending/2026-06-22-*.md)
- F1 auth03 YAML $ login config — **FIXED** (passwordHashB64 in UserStore + UserStoreTest)
- F2 DB-mode snapshot blank — **FIXED** (VizSession.rehydrateFromStore + ExposedSessionStoreTest)
- F3 share link uses :8080 (404 in dev) — open
- F4 revoke double-fire DELETE → 404 + toast — open
- F5 cross-tenant scenario write — **FIXED** (scoped getOrCreateSession + TenantIsolationE2ETest)
- F6 duplicate seq on multi-run DB session — open (F2 fix reduced ~21→1; boundary race remains)
- F7 (to log) ValidationRoutes unscoped getSession — tenant gap

## Code changes (UNCOMMITTED — on disk, safe across /clear)
- backend/coroutine-viz-core/.../session/{VizSession,ProjectionService}.kt (F2)
- backend/.../persistence/ExposedSessionStore.kt (F2) + ExposedSessionStoreTest.kt
- backend/.../routes/{ScenarioRunnerRoutes,FlowScenarioRoutes,PatternRoutes,SessionRoutes}.kt (F5)
- backend/.../auth/UserStore.kt (F1) + application.yaml seeded-user template + UserStoreTest.kt
- backend/.../auth/TenantIsolationE2ETest.kt (F5 regression)
- Tests green: `cd backend && ./gradlew :test :coroutine-viz-core:test`

## To resume in a fresh context
1. `claude --continue` (resumes this session) OR start fresh and read this file first.
2. Confirm servers up (health 200, frontend 200); restart per above if not.
3. Continue at: build `EventsInExactOrder` validation rule (the in-progress item), then UAT steps 8-9.
