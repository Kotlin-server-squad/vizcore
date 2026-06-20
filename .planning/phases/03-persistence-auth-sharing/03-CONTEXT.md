# Phase 3: Persistence, Auth & Sharing - Context

**Gathered:** 2026-06-20
**Status:** Ready for planning

<domain>
## Phase Boundary

vizcore becomes safe to deploy for multiple users. Three capabilities, all opt-in so the
zero-config local dev experience is unchanged:
1. **Persistence (PERS-01..03):** an optional JDBC store behind the `SessionStoreInterface`/
   `EventStoreInterface` seam, selectable via `storage.type=database`, with sessions/events
   surviving restart and a background retention policy.
2. **Auth (AUTH-01..05):** route-level auth (`X-API-Key` and JWT) with VIEWER/RUNNER/ADMIN
   roles and tenant isolation, **enforced only when configured**. This is an open-source tool —
   the default build requires **no auth at all** (public, zero-config); auth is a pure runtime
   opt-in. See D-04a/D-04b.
3. **Sharing (SHAR-01..02):** revocable, expiring share tokens that open a rate-limited,
   read-only session view.

**This phase does NOT** add user registration/password-reset flows, OAuth/SSO, share
permissions beyond READ_ONLY, or any new visualization capability. Tech stack is locked by
ADR-015/016/019 (see Canonical References) — discussion captured only the product/UX
decisions those ADRs leave open.
</domain>

<decisions>
## Implementation Decisions

### Auth identity & tenancy (Area 1)
- **D-01:** JWT users are **config-seeded**, not a DB users table. Users are declared in
  config/env as `{username, passwordHash, role}` and loaded at startup (mirrors the API-key
  store pattern in ADR-016). `POST /api/auth/token` validates credentials against this set and
  issues the JWT. **No** registration, password-reset, or user-CRUD endpoints/UI this phase.
- **D-02:** Password hashes are pre-computed and supplied as config (env-interpolated), never
  plaintext. Exact hash algorithm (argon2id vs bcrypt) is **Claude's discretion** for the
  planner/researcher — pick a vetted, salted KDF; document the choice.
- **D-03:** **Tenant = JWT `sub` (userId)** when JWT auth is used. When only API keys are
  configured (no JWT), the tenant falls back to the API-key **`name`** field, so AUTH-04
  isolation holds in both modes. ADMIN bypasses the tenant filter (per ADR-016 role table).
- **D-04:** Auth is **opt-in / fail-open-when-unconfigured**: with no `API_KEY`/JWT config,
  every route stays public (today's behavior). `/health`, `/openapi.json`, and
  `/api/auth/token` are always public even when auth is on.
- **D-04a (open-source default — hard requirement):** This project ships **open-source**, so the
  out-of-the-box build MUST run with **no authentication required** — `git clone` → run → fully
  usable, no credentials, no login screen. Auth is a pure **runtime toggle** (set `API_KEY` or the
  `auth:` config block); it is never a build/compile dependency and must never become mandatory to
  use the tool. Default-off is a product guarantee the planner must preserve, not an incidental
  behavior.
- **D-04b (auth-off + persistence = global shared sessions):** Because auth and persistence are
  independent toggles, the **persistence-ON + auth-OFF** combination is **intentional and
  supported**: stored sessions are visible to anyone who can reach the server (single-team /
  self-hosted / solo use). Tenant isolation only engages when auth is configured. This is the
  documented expected behavior — do NOT add a forced auth requirement when persistence is enabled.
  A non-blocking startup log noting "persistence enabled without auth — sessions are publicly
  visible" is acceptable as a nicety (Claude's discretion) but must not gate startup.

### Frontend auth UX (Area 2)
- **D-05:** **401-triggered login route.** When any `/api` call returns 401, the app routes to a
  `/login` screen (username + password → `POST /api/auth/token`). On success the JWT is stored
  and attached as `Authorization: Bearer <jwt>` on all subsequent `/api` calls.
- **D-06:** Token storage: **localStorage** (so the session survives reload / supports refresh).
  Keep the in-memory copy as source of truth; rehydrate from localStorage on load.
- **D-07:** **Auth-off is invisible.** When auth is not configured the app never sees a 401, so
  no login screen ever appears — the current zero-friction UX is preserved unchanged.
- **D-08:** The **frontend uses JWT Bearer**; `X-API-Key` remains the path for programmatic
  consumers (CLI, IntelliJ plugin, CI). The backend's `authenticatedApi()` must accept either
  credential type on protected routes.

### Shared read-only view (Area 3)
- **D-09:** `/shared/:token` renders the **full viewer minus controls**: coroutine tree / graph
  / timeline / thread-lanes **plus replay (play/scrub/speed) and export (PNG/SVG/WebM/JSON)** —
  all read-only. The token itself is the credential (no login required for the shared route).
- **D-10:** **Hidden/removed** in shared mode: Run scenario, record-to-DB / any mutation, the
  session-list & nav chrome, Compare, and Settings. The shared route is a standalone minimal
  shell, not the authenticated app layout.
- **D-11:** Share-creation UI: a **Share** action on a session opens a small dialog with an
  **expiry picker (1d / 7d / 30d / never)** → `POST /api/sessions/:id/share` → **copy-link**
  affordance for the returned URL.

### Share limits & revocation UX (Area 4)
- **D-12:** Rate-limit the public `GET /api/shared/:token` endpoint **per-IP** (rough default
  **~60 req/min**, configurable), returning **429** on exceed. Per-IP (not per-token) so one
  abusive viewer can't exhaust a legitimate link's budget for everyone.
- **D-13:** Revocation surfaces as a **"Manage shares" list** on the owning session — rows show
  token (short), expiry, `access_count`, `last_accessed_at`, each with a **Revoke** button.
  Revoke issues `DELETE /api/sessions/:id/shares/:token`; subsequent access returns **410/404**
  per ADR-019. (Backed by `GET /api/sessions/:id/shares`.)

### Claude's Discretion
- Password hash algorithm (D-02), JWT secret/key management mechanics, HikariCP tuning beyond
  ADR-015 defaults, Flyway migration file layout, the exact rate-limiter implementation
  (in-memory token-bucket vs library), and how the SSE live-write path interacts with the DB
  store (write-through vs buffered) — all left to research/planning, constrained by the ADRs.
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Persistence
- `docs/adr/015-persistence-strategy.md` — Exposed + HikariCP, H2 dev / PostgreSQL prod,
  `SessionStoreInterface`/`EventStoreInterface` seam, `sessions`/`events` schema, JSONB event
  format, HikariCP pool defaults, retention policy (max-age TTL + max-events trim), and the
  `storage:` config block. **Authoritative for all PERS-* work.**

### Auth
- `docs/adr/016-authentication-architecture.md` — `X-API-Key` (SHA-256 hashed, multi-key),
  JWT (HMAC dev / RS256 prod, `/api/auth/token`, refresh), `UserPrincipal` + VIEWER/RUNNER/ADMIN
  role permission table, tenant-isolation filter (`forUser`), and the always-public route list.
  **Authoritative for all AUTH-* work.** Note: this Context's D-01 (config-seeded users) and
  D-03 (tenancy fallback) fill the user-identity gap the ADR leaves open.

### Sharing
- `docs/adr/019-session-sharing.md` — `ShareToken` model, `shares` table schema (incl.
  `access_count`/`last_accessed_at`), and the four endpoints (`POST /api/sessions/:id/share`,
  `GET /api/shared/:token`, `GET /api/sessions/:id/shares`, `DELETE /api/sessions/:id/shares/:token`)
  with their 201/200/410/404/204 responses. Depends on ADR-015 persistence. **Authoritative for
  all SHAR-* work.**

### Requirements
- `.planning/REQUIREMENTS.md` — PERS-01..03, AUTH-01..05, SHAR-01..02 acceptance text + the note
  that SHAR-* depend on PERS-* (shares table needs the DB).
</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **In-memory `EventStore` / `SessionManager`** (`coroutine-viz-core`): become the in-memory
  implementations behind the new `SessionStoreInterface`/`EventStoreInterface` — the DB stores
  are the second implementation, selected by config.
- **Phase-1 config pattern**: health/logging/CORS already read from config profiles; the new
  `storage:` / `auth:` blocks should follow the same config-loading convention.
- **Frontend `api-client.ts`**: single choke point for `/api` calls — the JWT Bearer header and
  401→/login interception belong here (one place, like the Phase-2 auth-less client).
- **`SessionDetails` + panels (Phase 2)**: the shared read-only view reuses these components with
  controls stripped — do NOT fork them; gate the interactive affordances behind a `readOnly` prop
  or a shared-route wrapper.

### Established Patterns
- **Interface-seam + config-selected implementation** is the de-fork-friendly pattern Phase 1/2
  already used (e.g. bounded vs unbounded store) — reuse it for storage and auth.
- **Opt-in, default-off** mirrors Phase-1's bounded-store and Phase-2's replay gating: no behavior
  change unless explicitly configured.

### Integration Points
- Ktor route registration — wrap non-public routes in `authenticatedApi()` (AUTH-01).
- SSE / event append path — every event write must also persist when `storage.type=database`.
- Frontend router — new `/login` and `/shared/:token` routes (TanStack Router, as in Phase 2).
</code_context>

<specifics>
## Specific Ideas

- "Manage shares" table columns explicitly: link (short), expiry, hits (`access_count`),
  last accessed, Revoke — matches the `shares` table fields already in ADR-019.
- Expiry picker values are exactly `1d / 7d / 30d / never` (maps to ADR-019 `expiresIn`).
- Shared view is a standalone shell (no app nav), not the authenticated layout with panels hidden.
</specifics>

<deferred>
## Deferred Ideas

- **User registration / password-reset / user-CRUD UI** — explicitly out of scope; config-seeded
  users only this phase. A future phase if real multi-user management is needed.
- **OAuth / SSO** — not in scope; ADR-016 is API-key + JWT only.
- **Share permissions beyond READ_ONLY** (e.g. editable/commentable shares) — ADR-019 locks
  `SharePermission.READ_ONLY`; richer permissions are a future idea.
- **API-key management endpoints (DB-backed key CRUD)** — ADR-016 mentions migrating keys to the
  DB "once persistence exists"; treated as a follow-up, not required by AUTH-01..05.

### Reviewed Todos (not folded)
The 7 pending todos matched only on generic keywords (backend/events/phase) and belong to
Phase-2 cleanup (events-package fork, shared-api-types regen) or repo housekeeping — none are
persistence/auth/sharing scope, so none were folded. They remain in `.planning/todos/pending/`.
</deferred>

---

*Phase: 3-persistence-auth-sharing*
*Context gathered: 2026-06-20*
