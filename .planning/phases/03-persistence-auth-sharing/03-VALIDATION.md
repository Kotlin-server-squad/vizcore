---
phase: 03
slug: persistence-auth-sharing
status: approved
nyquist_compliant: true
wave_0_complete: false
created: 2026-06-20
updated: 2026-06-21
---

# Phase 03 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework (backend)** | JUnit 5 (Jupiter) + Ktor Test Host (`ktor-server-test-host`) + kotlinx-coroutines-test; H2 in-memory/file for DB tests |
| **Framework (frontend)** | Vitest + Testing Library + MSW 2.x |
| **Config file (backend)** | `backend/build.gradle.kts` (`useJUnitPlatform()`); per-test config via `testApplication { environment { config = ... } }` |
| **Config file (frontend)** | Vite/Vitest config; colocated `*.test.ts(x)` |
| **Quick run command (backend)** | `cd backend && ./gradlew test --tests "*<Area>*"` |
| **Quick run command (frontend)** | `cd frontend && pnpm test --run <file>` |
| **Full suite command** | `cd backend && ./gradlew test` + `cd frontend && pnpm test` |
| **Estimated runtime** | backend ~60-120s (H2 + test host); frontend ~20-40s |

---

## Sampling Rate

- **After every task commit:** Run the focused `--tests "*<Area>*"` (backend) or `pnpm test --run <file>` (frontend) for the area touched
- **After every plan wave:** Run `cd backend && ./gradlew test` + `cd frontend && pnpm test`
- **Before `/gsd-verify-work`:** Full backend + frontend suites green; `detekt`/`ktlintCheck` + ESLint clean
- **Max feedback latency:** ~120 seconds (full backend suite)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 03-01-01 | 01 | 1 | PERS-01 | T-03-01/02/03 | parameterized SQL; no logged secret; versioned migration | integration (H2) | `./gradlew test --tests "*ExposedSessionStoreTest"` | ❌ W0 | ⬜ pending |
| 03-01-02 | 01 | 1 | PERS-02 | T-03-01 | round-trip via PolymorphicSerializer; parameterized | integration (H2 file) | `./gradlew test --tests "*PersistenceRestartTest"` | ❌ W0 | ⬜ pending |
| 03-02-01 | 02 | 2 | AUTH-02, AUTH-03 | T-03-04/05/07 | constant-time SHA-256; Argon2id verify; uniform 401 | E2E/unit | `./gradlew test --tests "*JwtAuthTest" --tests "*ApiKeyStore*"` | ❌ W0 | ⬜ pending |
| 03-02-02 | 02 | 2 | AUTH-01, AUTH-05 | T-03-06/08 | deny-by-default when configured; fail-open when not | E2E (test host) | `./gradlew test --tests "*AuthTest" --tests "*JwtAuthTest"` | ⚠️ extend AuthTest | ⬜ pending |
| 03-03-01 | 03 | 3 | AUTH-04 | T-03-09/10 | tenant filter on every read; explicit tenancy thread | E2E/integration | `./gradlew test --tests "*TenantIsolationTest"` | ❌ W0 | ⬜ pending |
| 03-03-02 | 03 | 3 | PERS-03 | T-03-11/12 | active-share guard; parameterized bulk delete | integration (H2) | `./gradlew test --tests "*DbRetentionPolicyTest"` | ⚠️ new sibling of RetentionPolicyTest | ⬜ pending |
| 03-04-01 | 04 | 3 | SHAR-01, SHAR-02 | T-03-14/16 | parameterized share CRUD; owner-scoped revoke | E2E (H2) | `./gradlew test --tests "*ShareRoutesTest"` | ⚠️ rewrite ShareTokenServiceTest | ⬜ pending |
| 03-04-02 | 04 | 3 | SHAR-02 | T-03-13 | per-IP rate limit -> 429 | E2E | `./gradlew test --tests "*RateLimitTest"` | ❌ W0 | ⬜ pending |
| 03-05-01 | 05 | 4 | AUTH-03 | T-03-18/19 | client only attaches; no header when off | component (Vitest+MSW) | `pnpm test --run src/lib/auth-store.test.ts src/lib/api-client.test.ts` | ❌ W0 | ⬜ pending |
| 03-05-02 | 05 | 4 | AUTH-03 | T-03-20/21 | password field; generic 401 copy; no cred logging | component | `pnpm test --run src/routes/login.test.tsx` | ❌ W0 | ⬜ pending |
| 03-06-01 | 06 | 5 | SHAR-02 | T-03-22/23/25 | readOnly gates mutation; 429/410/404 states | component | `pnpm test --run src/routes/shared.$token.test.tsx` | ❌ W0 | ⬜ pending |
| 03-06-02 | 06 | 5 | SHAR-01, SHAR-02 | T-03-24 | revocable/expiring token in copied URL | component | `pnpm test --run src/components/share/ShareDialog.test.tsx src/components/share/ManageShares.test.tsx` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

Each plan creates its own failing tests as the first action of its first task (Nyquist: tests precede impl). The MISSING test files to scaffold:

- [ ] `backend/.../persistence/ExposedSessionStoreTest.kt` — H2-backed; PERS-01 (Plan 01)
- [ ] `backend/.../persistence/PersistenceRestartTest.kt` — survives-restart + JSONB/JSON round-trip; PERS-02 (Plan 01)
- [ ] `backend/.../JwtAuthTest.kt` — token issuance + protected-route acceptance + ApiKeyStore unit; AUTH-02/03 (Plan 02)
- [ ] Extend `backend/.../AuthTest.kt` — SHA-256 keys, dual-provider, public allowlist, pass-through-when-unconfigured; AUTH-01/05 (Plan 02)
- [ ] `backend/.../auth/TenantIsolationTest.kt` — cross-tenant denial + ADMIN bypass + key-name fallback + auth-off global; AUTH-04 (Plan 03)
- [ ] `backend/.../persistence/DbRetentionPolicyTest.kt` — max-age + event-trim + active-share exclusion; PERS-03 (Plan 03)
- [ ] `backend/.../share/ShareRoutesTest.kt` — full endpoint matrix (201/200/410/404/204); rewrite ShareTokenServiceTest; SHAR-01/02 (Plan 04)
- [ ] `backend/.../share/RateLimitTest.kt` — per-IP 429; SHAR-02/D-12 (Plan 04)
- [ ] `frontend/src/lib/auth-store.test.ts`, `frontend/src/lib/api-client.test.ts` — Bearer + 401 + SSE token (Plan 05)
- [ ] `frontend/src/routes/login.test.tsx` — login success/401/network/loading (Plan 05)
- [ ] `frontend/src/routes/shared.$token.test.tsx` — read-only render + 410/404/429 + no-chrome (Plan 06)
- [ ] `frontend/src/components/share/ShareDialog.test.tsx`, `ManageShares.test.tsx` — create/copy/expiry-map + list/revoke-confirm (Plan 06)
- [ ] Backend test DB harness: H2 in-memory/file fixture + Flyway-migrate-before-test helper (shared base, mirror `BaseTestService.kt`) — created in Plan 01, reused by 02/03/04 DB tests

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| PostgreSQL prod-profile persistence | PERS-01/02 | No `postgres` service in docker-compose yet; H2 covers dev/test (RESEARCH Env Availability) | Add a `postgres` service + `STORAGE_TYPE=database` + PG url; restart backend; confirm sessions survive (deferred to deploy config, not a phase blocker) |
| Per-IP limit behind a reverse proxy | SHAR-02/D-12 | `origin.remoteHost` reflects proxy IP unless `XForwardedHeaders` is installed (RESEARCH Pattern 9) | Deploy behind nginx/Docker proxy with XForwardedHeaders; confirm distinct client IPs get distinct buckets |
| RS256 prod JWT keys | AUTH-03 | Requires mounted PEM key pair (RESEARCH Open Question 2); HMAC dev path is tested | Provide `auth.jwt.privateKeyPath`/`publicKeyPath`; confirm token signs/verifies with RS256 |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 120s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-06-21
