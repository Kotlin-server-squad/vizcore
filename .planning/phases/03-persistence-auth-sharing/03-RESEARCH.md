# Phase 3: Persistence, Auth & Sharing - Research

**Researched:** 2026-06-20
**Domain:** Ktor 3.3 backend persistence (Exposed/HikariCP/Flyway), route-level auth (X-API-Key SHA-256 + JWT), revocable share tokens & rate limiting; React 19 + TanStack Router frontend auth UX + read-only shared view
**Confidence:** HIGH (codebase + ADRs are authoritative and read directly; all library versions verified against Maven Central; auth/persistence are well-trodden Ktor patterns)

## Summary

Phase 3 turns vizcore from a single-tenant in-memory tool into something safe to self-host for a team, **without changing the zero-config local experience**. Three independent, opt-in capabilities sit behind the existing interface-seam pattern: an optional JDBC store (Exposed 1.x + HikariCP) behind `SessionStoreInterface`/`EventStoreInterface`, route-level auth (X-API-Key + JWT) that is *fail-open when unconfigured*, and revocable/expiring/rate-limited share tokens for read-only session viewing.

The codebase already contains **partial scaffolding** that the planner must reconcile rather than build from zero: `Auth.kt` has a single-key plaintext `configureAuth()` + `authenticatedApi()` wrapper (but it is NOT yet wired into `Routing.kt`); `ShareTokenService` exists as an in-memory `object` with a *different shape* than ADR-019 (no `createdBy`/`accessCount`/`lastAccessedAt`, no DB); `RetentionPolicy` exists but operates on in-memory sessions with non-ADR defaults; and the `SessionStoreInterface`/`EventStoreInterface` seams exist but their **method signatures differ from the ADR-015 sketch** (the real interfaces are session-centric: `createSession`/`getSession`/`record`/`all`/`since`, not the ADR's `create`/`append`/`getAll`). The plan must build against the *real* interfaces in the codebase, not the ADR's illustrative ones.

**Primary recommendation:** Implement persistence as a second pair of implementations behind the existing seams (`ExposedSessionStore`, `ExposedEventStore`), selected by `storage.type`; keep `SessionManager`/`EventStore` as the default in-memory impls. Layer auth as a config-gated install that registers BOTH an `api-key` provider (SHA-256 multi-key) and a `jwt` provider, then wrap non-public routes in a single `authenticatedApi()` that accepts either credential — and when neither is configured, `authenticatedApi()` must pass through transparently (the existing anonymous-principal trick, generalized). Use Ktor's **first-party `RateLimit` plugin keyed by IP** for the public shared endpoint. On the frontend, route ALL `/api` traffic (including the SSE stream) through credential-aware helpers in `api-client.ts`, add `/login` + `/shared/$token` TanStack routes, and reuse `SessionDetails` behind a `readOnly` prop — do not fork it.

## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** JWT users are **config-seeded** (`{username, passwordHash, role}` loaded at startup, mirroring the API-key store), NOT a DB users table. `POST /api/auth/token` validates against this set and issues the JWT. **No** registration / password-reset / user-CRUD endpoints or UI this phase.
- **D-02:** Password hashes are pre-computed and supplied as config (env-interpolated), never plaintext. Exact KDF (argon2id vs bcrypt) is **Claude's discretion** — pick a vetted, salted KDF and document it.
- **D-03:** **Tenant = JWT `sub` (userId)** when JWT auth is used. When only API keys are configured, tenant falls back to the API-key **`name`** field. ADMIN bypasses the tenant filter.
- **D-04:** Auth is **opt-in / fail-open-when-unconfigured**. With no key/JWT config every route stays public. `/health`, `/openapi.json`, and `/api/auth/token` are always public even when auth is on.
- **D-04a (hard product guarantee):** Out-of-the-box build MUST run with **no auth required** — `git clone` → run → fully usable, no credentials, no login screen. Auth is a pure **runtime toggle**, never a build/compile dependency, never mandatory. Default-off must be preserved deliberately.
- **D-04b:** **persistence-ON + auth-OFF is intentional and supported** (global shared sessions, single-team/self-hosted/solo). Tenant isolation only engages when auth is configured. Do NOT force auth when persistence is enabled. A non-blocking startup log ("persistence enabled without auth — sessions are publicly visible") is an acceptable nicety but must not gate startup.
- **D-05:** **401-triggered login route.** Any `/api` 401 routes the app to `/login` (username + password → `POST /api/auth/token`). On success store the JWT and attach `Authorization: Bearer <jwt>` on subsequent `/api` calls.
- **D-06:** Token storage = **localStorage** (survives reload / supports refresh). In-memory copy is source of truth; rehydrate from localStorage on load.
- **D-07:** **Auth-off is invisible.** When auth is not configured the app never sees a 401, so `/login` never appears — current zero-friction UX unchanged.
- **D-08:** Frontend uses **JWT Bearer**; `X-API-Key` remains the programmatic path (CLI, IntelliJ plugin, CI). `authenticatedApi()` must accept either credential type on protected routes.
- **D-09:** `/shared/:token` renders the **full viewer minus controls**: tree/graph/timeline/thread-lanes + replay (play/scrub/speed) + export (PNG/SVG/WebM/JSON), all read-only. The token is the credential (no login on the shared route).
- **D-10:** Hidden/removed in shared mode: Run scenario, record-to-DB/any mutation, the session-list & nav chrome, Compare, Settings. Shared route is a standalone minimal shell, NOT the authenticated app layout.
- **D-11:** Share-creation UI: a **Share** action opens a small dialog with an **expiry picker (1d/7d/30d/never)** → `POST /api/sessions/:id/share` → **copy-link** affordance.
- **D-12:** Rate-limit the public `GET /api/shared/:token` **per-IP** (default **~60 req/min**, configurable), returning **429** on exceed. Per-IP (not per-token).
- **D-13:** Revocation surfaces as a **"Manage shares" list** on the owning session — rows show token (short), expiry, `access_count`, `last_accessed_at`, each with a **Revoke** button → `DELETE /api/sessions/:id/shares/:token`; subsequent access returns **410/404** (backed by `GET /api/sessions/:id/shares`).

### Claude's Discretion

Password hash algorithm (D-02), JWT secret/key management mechanics, HikariCP tuning beyond ADR-015 defaults, Flyway migration file layout, exact rate-limiter implementation (in-memory token-bucket vs library), and SSE live-write↔DB interaction (write-through vs buffered) — all left to research/planning, constrained by the ADRs. **This research makes a concrete recommendation for each below.**

### Deferred Ideas (OUT OF SCOPE)

- User registration / password-reset / user-CRUD UI — config-seeded users only this phase.
- OAuth / SSO — ADR-016 is API-key + JWT only.
- Share permissions beyond `READ_ONLY` — ADR-019 locks `SharePermission.READ_ONLY`.
- API-key management endpoints (DB-backed key CRUD) — follow-up, not required by AUTH-01..05.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PERS-01 | Optional JDBC store (Exposed + HikariCP, H2 dev / PG prod) behind the seam, selectable via `storage.type=database` | Standard Stack (Exposed 1.3.0 + HikariCP 6.3.0); Architecture Pattern 1 (seam selection); Don't-Hand-Roll (ORM, pool) |
| PERS-02 | Sessions & events survive restart (Flyway-migrated, events as JSONB) | Pattern 2 (Flyway H2+PG layout); Pattern 3 (JSONB column via exposed-json); Code Examples |
| PERS-03 | Retention policy (max-age TTL + max-events trim) as background process | Pattern 4 (DB-aware retention); reconcile with existing in-memory `RetentionPolicy`; Pitfall 6 (don't delete sessions with active shares) |
| AUTH-01 | Non-public routes wrapped in `authenticatedApi()`; `/health`, `/openapi.json`, token endpoint stay open | Pattern 5 (dual-provider install + pass-through); Pitfall 1 (pure runtime toggle) |
| AUTH-02 | API keys compared as SHA-256 hashes (not plaintext) | Code Examples (SHA-256 key store); replaces current plaintext `Auth.kt` |
| AUTH-03 | JWT (`/api/auth/token`, HMAC dev / RS256 prod, refresh) issues `UserPrincipal` with VIEWER/RUNNER/ADMIN | Standard Stack (ktor-server-auth-jwt + java-jwt); Pattern 6 (config-seeded users + token endpoint); password4j for D-02 |
| AUTH-04 | Sessions filtered by authenticated user (tenant isolation), no cross-tenant reads | Pattern 7 (`forUser` filter + D-03 tenancy resolution); Pitfall 3 (tenancy column on sessions) |
| AUTH-05 | Route-level auth enforcement covered by E2E tests | Validation Architecture (extend `AuthTest.kt` pattern); reject-without / allow-with for both credential types |
| SHAR-01 | Create share token (1d/7d/30d/never) via `POST /api/sessions/:id/share` | Pattern 8 (DB-backed ShareToken rewrite); Code Examples (endpoint); depends on PERS-* |
| SHAR-02 | Read-only shared view via `GET /api/shared/:token`; revocable + rate-limited | Pattern 8 + 9 (shared read endpoint, RateLimit plugin); frontend `/shared/$token` route |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Session/event persistence | API/Backend (Exposed store impl) | Database/Storage (H2/PG, JSONB) | Storage is a backend concern behind the existing seam; DB owns durability |
| Retention/cleanup loop | API/Backend (background coroutine) | Database/Storage | A server-lifecycle coroutine deleting rows; must run only when DB store active |
| API-key + JWT validation | API/Backend (Ktor Authentication) | — | Auth is enforced at the route boundary; credentials never validated client-side |
| Password hashing | API/Backend (KDF verify at login) | — | Hashing/verification is server-only; hashes supplied as config |
| Tenant isolation | API/Backend (`forUser` store filter) | Database/Storage (WHERE owner=?) | Filtering belongs in the store layer so every read path is covered uniformly |
| Token issuance/storage (JWT) | API/Backend (issue) + Browser (store) | — | Backend signs; browser persists in localStorage (D-06) and attaches as Bearer |
| Share token lifecycle | API/Backend (service + DB) | Database/Storage (shares table) | Tokens are server-authoritative; cascade-delete tied to sessions |
| Rate limiting shared endpoint | API/Backend (Ktor RateLimit plugin) | — | Per-IP throttling is an edge/server concern at the route boundary |
| Login UX / 401 handling | Frontend Server (TanStack route) | Browser (localStorage) | `/login` is client routing; redirect driven by api-client interceptor |
| Read-only shared shell | Frontend Server (standalone route) | Browser | Standalone shell reusing `SessionDetails` with `readOnly` prop |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.jetbrains.exposed:exposed-core` | **1.3.0** | Type-safe SQL DSL | ADR-015 choice; JetBrains' Kotlin ORM, now stable 1.x |
| `org.jetbrains.exposed:exposed-jdbc` | 1.3.0 | JDBC transaction backend for Exposed | Required runtime for Exposed DSL over JDBC |
| `org.jetbrains.exposed:exposed-json` | 1.3.0 | `json()`/`jsonb()` column types | JSONB event payload storage (PERS-02) |
| `org.jetbrains.exposed:exposed-kotlin-datetime` | 1.3.0 | `timestamp`/`Instant` columns | created_at / expires_at / last_accessed_at columns |
| `com.zaxxer:HikariCP` | **6.3.0** | JDBC connection pool | ADR-015 choice; de-facto JVM pool |
| `org.flywaydb:flyway-core` | **11.8.2** | Versioned schema migrations | PERS-02 ("Flyway-migrated schema") |
| `org.flywaydb:flyway-database-postgresql` | 11.8.2 | Flyway PG support module (required in Flyway 10+) | PG dialect lives in a separate module since Flyway 10 |
| `com.h2database:h2` | **2.3.232** | Dev/embedded DB + test DB | ADR-015 dev DB |
| `org.postgresql:postgresql` | **42.7.7** | Production JDBC driver | ADR-015 prod DB |
| `io.ktor:ktor-server-auth-jwt` | (Ktor BOM, line 3.3.x) | JWT auth provider for Ktor | AUTH-03; pulls `com.auth0:java-jwt` transitively |
| `io.ktor:ktor-server-rate-limit` | (Ktor BOM, line 3.3.x) | First-party per-key rate limiting | SHAR-02 / D-12; keys by IP, returns 429 + Retry-After |
| `com.password4j:password4j` | **1.8.2** | Argon2id / bcrypt password hashing+verify | D-02 KDF; supports Argon2id natively, salted, constant-time verify |

> **Version note (LANDMINE):** ADR-015's Kotlin snippets use the **Exposed 0.x DSL** (`SchemaUtils.createMissingTablesAndColumns`, old transaction style). Exposed **1.x is a breaking API change** (package moves to `org.jetbrains.exposed.v1.*`, `Database.connect` + `transaction {}` still exist but DSL imports differ). Plan against the 1.3.0 API, and consult Context7/official Exposed 1.x docs when writing the store — do not copy the ADR snippets verbatim. `[VERIFIED: Maven Central — central.sonatype.com returned 1.3.0 as latest stable]`

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `io.ktor:ktor-server-auth` | already present | Base Authentication plugin | Already a dependency; host for api-key + jwt providers |
| `at.favre.lib:bcrypt` | 0.10.2 | Bcrypt-only alternative to password4j | Only if you reject argon2id; smaller dep surface |
| `org.jetbrains.exposed:exposed-dao` | 1.3.0 | Active-record DAO layer | NOT recommended — use the DSL only (simpler, matches ADR) |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Ktor first-party `RateLimit` | `at.flaxoos:ktor-server-rate-limiting` (extra plugins) or hand-rolled token bucket | First-party plugin already does per-IP keying + 429 + Retry-After; no extra dep, no maintenance. Hand-rolling re-implements a solved problem (Pitfall 5). |
| password4j (argon2id) | Spring Security Crypto `Argon2PasswordEncoder` (6.5.1) | Spring crypto drags in `spring-security-core`/`spring-core` transitive weight into a non-Spring app; password4j is purpose-built and lighter. |
| Exposed JSON `jsonb()` | Store payload as `TEXT` and serialize manually | `jsonb()` gives native PG JSONB indexing/querying for analytics (ADR-015 rationale); H2 maps it to a JSON/CLOB type transparently. |
| Flyway | Exposed `SchemaUtils.create` (dev only) | ADR-015 explicitly wants Flyway for production reproducibility; SchemaUtils is fine ONLY as the H2-dev fallback if you choose, but a single Flyway path for both DBs is cleaner (Pattern 2). |

**Installation (backend `build.gradle.kts` — Ktor BOM manages Ktor artifact versions, so no version on ktor-* lines):**
```kotlin
// coroutine-viz-core/build.gradle.kts — interfaces + in-memory impls stay here;
// the Exposed impls may live in the :backend app module to keep core dependency-free,
// OR in a new core subpackage. RECOMMEND: put Exposed impls in :backend (app) module so
// coroutine-viz-core stays a zero-DB, publishable SDK (it currently has NO Ktor/DB deps).

// backend/build.gradle.kts
implementation("org.jetbrains.exposed:exposed-core:1.3.0")
implementation("org.jetbrains.exposed:exposed-jdbc:1.3.0")
implementation("org.jetbrains.exposed:exposed-json:1.3.0")
implementation("org.jetbrains.exposed:exposed-kotlin-datetime:1.3.0")
implementation("com.zaxxer:HikariCP:6.3.0")
implementation("org.flywaydb:flyway-core:11.8.2")
implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
implementation("org.postgresql:postgresql:42.7.7")
implementation("com.h2database:h2:2.3.232")
implementation("io.ktor:ktor-server-auth-jwt")     // version from Ktor BOM (io.ktor.plugin 3.3.2)
implementation("io.ktor:ktor-server-rate-limit")   // version from Ktor BOM
implementation("com.password4j:password4j:1.8.2")
```

> **SDK-purity LANDMINE:** `coroutine-viz-core` is a publishable MIT SDK with **only** coroutines + serialization + slf4j deps (verified in its build.gradle.kts). Do NOT add Exposed/HikariCP/JDBC to the core module — that would force every SDK consumer to pull a database driver. Keep the *interfaces* in core (they already are) and put `ExposedSessionStore`/`ExposedEventStore` in the `:backend` application module.

## Package Legitimacy Audit

All packages discovered from official docs / Maven Central authoritative registry and version-verified this session.

| Package | Registry | Age | Source Repo | Verdict | Disposition |
|---------|----------|-----|-------------|---------|-------------|
| org.jetbrains.exposed:exposed-* | Maven Central | mature (JetBrains, 1.x stable) | github.com/JetBrains/Exposed | OK | Approved |
| com.zaxxer:HikariCP | Maven Central | mature (de-facto JVM pool) | github.com/brettwooldridge/HikariCP | OK | Approved |
| org.flywaydb:flyway-core | Maven Central | mature (Redgate) | github.com/flyway/flyway | OK | Approved |
| org.flywaydb:flyway-database-postgresql | Maven Central | 11.x (split module since v10) | github.com/flyway/flyway | OK | Approved |
| org.postgresql:postgresql | Maven Central | mature (official JDBC driver) | github.com/pgjdbc/pgjdbc | OK | Approved |
| com.h2database:h2 | Maven Central | mature | github.com/h2database/h2database | OK | Approved |
| io.ktor:ktor-server-auth-jwt | Maven Central | mature (JetBrains, same line as in-use Ktor) | github.com/ktorio/ktor | OK | Approved |
| io.ktor:ktor-server-rate-limit | Maven Central | mature (JetBrains, first-party) | github.com/ktorio/ktor | OK | Approved |
| com.password4j:password4j | Maven Central | mature (1.8.x, actively maintained) | github.com/Password4j/password4j | OK | Approved |
| at.favre.lib:bcrypt | Maven Central | mature (0.10.x) | github.com/patrickfav/bcrypt | OK | Approved (alt only) |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
                          ┌─────────────────────────────────────────────────────────┐
  Browser (React 19)      │                    Ktor 3.3 Backend                      │
                          │                                                           │
  ┌────────────────┐      │   install order (Application.module):                    │
  │ api-client.ts  │      │   Compression → HTTP/CORS → Auth(install) → Monitoring   │
  │  fetchJson()   │──────┼──▶ ┌──────────────────────────────────────────────┐      │
  │  + Bearer hdr  │ /api │    │  Authentication plugin                       │      │
  │  401→/login    │◀─401─┼────│   provider "api-key"  (SHA-256 multi-key)    │      │
  └───────┬────────┘      │    │   provider "jwt"      (HMAC dev / RS256 prod)│      │
          │               │    └──────────────────────────────────────────────┘      │
          │ SSE           │              │ authenticatedApi { } wraps non-public      │
  ┌───────▼────────┐      │              ▼                                            │
  │ SSE stream     │      │   ┌──────────────────────────────────────────────┐       │
  │ (token in URL  │──────┼──▶│ Routes                                        │       │
  │  query param)  │      │   │  PUBLIC: /health /openapi.json /api/auth/token│       │
  └────────────────┘      │   │          GET /api/shared/:token (RateLimit IP)│       │
                          │   │  PROTECTED (authenticatedApi):                │       │
  ┌────────────────┐      │   │    /api/sessions* /api/scenarios* /share* ... │       │
  │ /shared/$token │      │   └───────────────────┬──────────────────────────┘       │
  │  standalone    │      │                       │ forUser(principal) filter         │
  │  readOnly shell│      │                       ▼                                   │
  └────────────────┘      │   ┌──────────────────────────────────────────────┐       │
                          │   │ SessionStoreInterface / EventStoreInterface  │       │
                          │   │   storage.type=memory  → SessionManager/EventStore   │
                          │   │   storage.type=database→ ExposedSessionStore/...     │
                          │   └───────────────┬───────────────┬──────────────┘       │
                          │     write-through │               │ read                  │
                          │   VizSession.send─┘               ▼                       │
                          │   (store.append +      ┌───────────────────────┐         │
                          │    DB persist)         │ HikariCP → H2 / Postgres│        │
                          │                        │  sessions / events(JSONB)│       │
                          │   RetentionPolicy ─────│  shares (FK cascade)     │       │
                          │   (bg coroutine,       └───────────────────────┘         │
                          │    deletes expired,                                       │
                          │    skips active-share sessions)                           │
                          └─────────────────────────────────────────────────────────┘
```

### Recommended Project Structure
```
backend/src/main/kotlin/com/jh/proj/coroutineviz/
├── Auth.kt                    # REWRITE: dual-provider install + authenticatedApi pass-through
├── auth/
│   ├── ApiKeyStore.kt         # SHA-256 multi-key store, loaded from config (AUTH-02)
│   ├── UserStore.kt           # config-seeded {username, passwordHash, role} (D-01)
│   ├── JwtConfig.kt           # HMAC dev / RS256 prod secret/key loading (AUTH-03)
│   ├── Principals.kt          # ApiKeyPrincipal(name, role) + UserPrincipal(userId, role) + Role
│   └── Tenancy.kt             # principal → tenantId resolution (D-03), forUser(...) filter
├── persistence/
│   ├── DatabaseFactory.kt     # Hikari DataSource + Database.connect + Flyway.migrate (PERS-01/02)
│   ├── tables/SessionsTable.kt, EventsTable.kt, SharesTable.kt   # Exposed table objects
│   ├── ExposedSessionStore.kt # SessionStoreInterface impl (PERS-01)
│   ├── ExposedEventStore.kt   # EventStoreInterface impl, JSONB payload (PERS-02)
│   └── DbRetentionPolicy.kt   # DB-aware retention; skip sessions w/ active shares (PERS-03)
├── share/
│   ├── ShareService.kt        # REWRITE ShareTokenService → DB-backed, ADR-019 shape
│   └── ShareRoutes.kt         # POST share / GET shared / GET shares / DELETE share
└── Routing.kt                 # wrap non-public registrations in authenticatedApi {}

backend/src/main/resources/db/migration/
├── common/V1__core_schema.sql   # sessions, events, shares (portable DDL)
└── postgresql/V2__jsonb.sql     # (only if PG-specific JSONB index needed)

frontend/src/
├── lib/api-client.ts          # add Bearer header, 401 interception, share endpoints, token-aware SSE
├── lib/auth-store.ts          # NEW: localStorage + in-memory JWT source of truth (D-06)
├── routes/login.tsx           # NEW /login (D-05)
├── routes/shared.$token.tsx   # NEW standalone read-only shell (D-09/D-10)
└── components/SessionDetails.tsx  # add readOnly?: boolean prop — DO NOT FORK
```

### Pattern 1: Config-selected store behind the existing seam (PERS-01)
**What:** Bind `SessionStoreInterface`/`EventStoreInterface` to either the in-memory or Exposed impl based on `storage.type`, exactly mirroring the bounded-vs-unbounded EventStore selection Phase 1 already shipped.
**When to use:** Wiring storage in `Application.module()`.
**Critical:** The real interfaces in the codebase are **session-centric**, not the ADR-015 sketch. `SessionStoreInterface` = `createSession/getSession/listSessions/deleteSession/clearAll`; `EventStoreInterface` = `record/all/since/byCoroutine/count/clear`. The Exposed impls must satisfy *these* signatures. `EventStoreInterface` is currently **per-session** (each `VizSession` owns an `EventStore`), so `ExposedEventStore` is constructed with a `sessionId` and scopes all queries to it.
```kotlin
// Application.module()
val storageType = environment.config.propertyOrNull("storage.type")?.getString() ?: "memory"
if (storageType == "database") {
    val db = DatabaseFactory.init(environment.config)   // Hikari + connect + Flyway.migrate
    SessionManager.useStore(ExposedSessionStore(db))    // or pass a factory for per-session ExposedEventStore
    logger.info("Persistence enabled (database)")
    if (apiKey/jwt unconfigured) logger.warn("persistence enabled without auth — sessions are publicly visible") // D-04b
} // else: default in-memory, no behavior change (D-04a)
```

### Pattern 2: Flyway migration layout for H2 (dev) + PostgreSQL (prod)
**What:** A single versioned migration set that runs on both engines. Use **portable ANSI DDL** in `db/migration/common/` and configure Flyway `locations` to include the common path plus an engine-specific path only if needed.
**When to use:** `DatabaseFactory.init` after `Database.connect`, before serving traffic.
```kotlin
Flyway.configure()
    .dataSource(hikariDataSource)
    .locations("classpath:db/migration/common")  // + "classpath:db/migration/$engine" if PG-specific DDL exists
    .baselineOnMigrate(true)
    .load()
    .migrate()
```
**JSONB portability:** PostgreSQL has a native `JSONB` type; H2 (2.3.x) supports a `JSON` type. To keep ONE migration file, declare the column as `JSON` (H2 accepts `JSON`; on PG you may use `JSONB` via an engine-specific override migration if you want GIN indexing). **Simplest portable choice:** store payload as `JSON`/`TEXT` in the shared migration and rely on the Exposed `jsonb()`/`json()` column mapping for (de)serialization; add a PG-only `V_/postgresql` migration that `ALTER ... TYPE jsonb` + GIN index **only** if analytics querying is needed (it is not required by PERS-01..03). Document this as the chosen tradeoff.

### Pattern 3: JSONB event payload with the kind discriminator dual-stored (PERS-02)
**What:** Per ADR-015, store `kind` BOTH as a top-level indexed column AND inside the JSONB payload (the payload is the polymorphic-serialized `VizEvent`, which already carries `kind`). On read, deserialize the payload with the existing `PolymorphicSerializer(VizEvent::class)` + `appJson` (the same module SSE already uses).
**Why:** The frontend already consumes events via `appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)` (see `SessionRoutes.kt` `toSse()`). Reuse that exact serializer so round-tripped DB events are byte-compatible with SSE/`/events` output — no new wire shape, no frontend change.

### Pattern 4: DB-aware retention reconciled with existing in-memory RetentionPolicy (PERS-03)
**What:** The existing `RetentionPolicy` deletes in-memory sessions by age/count via `SessionStoreInterface`. For DB mode, retention must run SQL deletes (max-age TTL on `sessions.created_at`, max-events trim on `events`). Two viable shapes: (a) extend the existing class to operate purely through the store interface (works if the Exposed store's `deleteSession`/list reflect DB rows), or (b) a dedicated `DbRetentionPolicy` that issues bulk SQL. **Recommend (b)** for events trimming (a per-session `DELETE ... WHERE seq < (max - N)` is far cheaper than loading events through the interface).
**Hard constraint (ADR-019):** retention MUST NOT delete a session that has a non-expired share link. The delete query must be `LEFT JOIN shares ... WHERE shares.token IS NULL OR shares.expires_at < now()` (or a `NOT EXISTS` guard). See Pitfall 6.
**Config (ADR-015):** `retention.maxAgeDays=30`, `maxEventsPerSession=100000`, `cleanupIntervalMinutes=60`. Note these differ from the in-memory defaults baked into the current `RetentionPolicy` (1h / 100 sessions / 1min) — the DB policy uses the ADR values.

### Pattern 5: Dual-provider auth install that is fail-open when unconfigured (AUTH-01, D-04/D-04a)
**What:** `configureAuth()` reads BOTH the api-key config and the jwt/user config. It always `install(Authentication)`, registering an `api-key` provider and a `jwt` provider. The single `authenticatedApi()` wrapper applies BOTH providers (Ktor `authenticate("api-key", "jwt")` accepts a request authenticated by *either*). When NEITHER is configured, the providers' `validate`/`authenticate` blocks short-circuit by setting an anonymous principal (the technique already in `Auth.kt`), so wrapped routes pass through publicly — preserving D-04a.
```kotlin
fun Route.authenticatedApi(build: Route.() -> Unit) {
    if (authDisabled) { build(); return }              // pure pass-through, no auth plugin in path
    authenticate("api-key", "jwt", optional = false) { build() }
}
```
**Recommendation:** prefer the explicit `if (authDisabled) build()` branch over the anonymous-principal trick for clarity — it makes "default-off" a single readable line and avoids any challenge/401 path when auth is off. Compute `authDisabled` once at startup from config.
**Always-public routes** (`/health`, `/openapi.json`, `POST /api/auth/token`, `GET /api/shared/:token`) are registered OUTSIDE `authenticatedApi {}`.

### Pattern 6: Config-seeded users + token endpoint with KDF verify (AUTH-03, D-01/D-02)
**What:** Load users at startup from config (`auth.users: [{username, passwordHash, role}]`, env-interpolated). `POST /api/auth/token` looks up the username, verifies the submitted password against the stored hash with password4j (`Password.check(plain, hash)` — constant-time), and on success signs a JWT with claims `sub=username`, `role`, `iat`, `exp`. Refresh tokens (7-day, ADR-016) stored server-side (in-memory map is acceptable this phase; persist later).
**KDF choice (D-02): Argon2id via password4j.** Rationale: Argon2id is the modern OWASP-recommended default (memory-hard, resistant to GPU/ASIC attacks); password4j ships Argon2id with sane defaults and a one-line salted verify. Document a CLI/utility to pre-compute hashes (`Password.hash(plain).withArgon2().result`) since hashes are supplied as config (D-02). Bcrypt (at.favre.lib:bcrypt 0.10.2) is the acceptable fallback if the deploy target cannot afford Argon2's memory cost.

### Pattern 7: Tenant isolation via store filter + D-03 tenancy resolution (AUTH-04)
**What:** Resolve `tenantId` from the principal: `UserPrincipal.userId` (JWT `sub`) when JWT; else `ApiKeyPrincipal.name` when api-key; ADMIN role → no filter. Apply via a `forUser(principal)` wrapper around the store (ADR-016) OR a `WHERE owner_tenant = ?` clause in the Exposed store. **Recommend** a `tenantId` column on `sessions` populated at creation from the current principal, with reads filtered in `ExposedSessionStore.listSessions/getSession`. When auth is off, `tenantId` is null/global and no filter applies (D-04b global-shared behavior).
**Landmine:** the current `createSession` has no principal context. The plan must thread the resolved `tenantId` into session creation (e.g., a `createSession(name, tenantId)` overload or a coroutine-context element) — do not retrofit a global mutable.

### Pattern 8: DB-backed ShareToken rewrite to ADR-019 shape (SHAR-01/02, D-11/D-13)
**What:** The existing `ShareTokenService` (in-memory `object`, fields `token/sessionId/createdAtMs/expiresAtMs/readOnly`) does NOT match ADR-019 (`createdBy`, `accessCount`, `lastAccessedAt`, `permission`, nullable `expiresAt` for "never"). **Rewrite** as a DB-backed service over a `shares` table. `GET /api/shared/:token` increments `access_count` and sets `last_accessed_at` on each successful read, returns `{session, events[]}`, returns **410** for expired, **404** for unknown/revoked. `DELETE` returns **204**.
**Expiry mapping (D-11):** `"1d"|"7d"|"30d"` → `now + N days`; `"never"` → `expires_at = NULL`.

### Pattern 9: Per-IP rate limit on the public shared endpoint (SHAR-02, D-12)
**What:** Use Ktor's **first-party `RateLimit` plugin**, keyed by remote host, scoped to only the shared GET route.
```kotlin
install(RateLimit) {
    register(RateLimitName("shared")) {
        rateLimiter(limit = 60, refillPeriod = 60.seconds)        // D-12 default, configurable
        requestKey { call -> call.request.origin.remoteHost }     // per-IP (D-12)
    }
}
routing {
    rateLimit(RateLimitName("shared")) {
        get("/api/shared/{token}") { /* ... */ }                  // returns 429 + Retry-After on exceed
    }
}
```
**Behind a proxy (Docker/Nginx):** `remoteHost` may be the proxy IP. If deployed behind a reverse proxy, install `XForwardedHeaders` so `origin.remoteHost` reflects the real client (otherwise all viewers share one bucket). Flag for the deploy config.

### Anti-Patterns to Avoid
- **Forcing auth when persistence is on** — violates D-04b. Persistence and auth are independent toggles.
- **Adding DB deps to `coroutine-viz-core`** — breaks the publishable SDK (SDK-01). Keep Exposed in `:backend`.
- **Forking `SessionDetails` for the shared view** — violates D-10 reuse mandate; add a `readOnly` prop.
- **Using `EventSource` with an Authorization header** — the browser `EventSource` API CANNOT set custom headers (Pitfall 2). Token must travel another way.
- **Copying ADR-015 Exposed 0.x snippets** — 1.x API differs; consult current Exposed docs.
- **Plaintext key comparison** (current `Auth.kt`) — AUTH-02 requires SHA-256 hashed comparison.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JDBC connection pooling | Custom connection manager | HikariCP 6.3.0 | Pool sizing, leak detection, timeouts are subtle; ADR-015 mandate |
| SQL schema migrations | Ad-hoc `CREATE TABLE IF NOT EXISTS` on boot | Flyway 11.8.2 | Versioned, repeatable, ordered; PERS-02 mandate |
| Password hashing | SHA-256/MD5 of password, custom salt loop | password4j Argon2id | Salting, work-factor, constant-time verify; rolling your own is a CVE |
| JWT sign/verify | Manual base64 + HMAC | ktor-server-auth-jwt + java-jwt | Claim validation, exp/iat, alg confusion attacks handled |
| Per-IP rate limiting | In-memory `Map<ip, counter>` + timer | Ktor `RateLimit` plugin | Token-bucket, refill, 429 + Retry-After + headers built-in (D-12) |
| Polymorphic event (de)serialization | Custom JSON mapper for DB payload | Existing `appJson` + `PolymorphicSerializer(VizEvent::class)` | Already the SSE wire format; reuse guarantees round-trip compatibility |
| Token generation | Custom random string | `UUID.randomUUID()` (already used) | Cryptographically adequate v4 UUID; matches ADR-019 model |

**Key insight:** Almost every primitive this phase needs (pool, migrations, hashing, JWT, rate limit, polymorphic JSON) has a battle-tested JVM library that the ADRs already point to. The genuine engineering work is the *seam wiring* (config-selected stores, fail-open auth, tenancy threading, reusing `SessionDetails`), not the primitives.

## Runtime State Inventory

> Phase 3 is greenfield-additive (new tables, new routes, new frontend routes), not a rename/refactor. This section is included because it touches stored state and a credential surface.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | New: `sessions`/`events`/`shares` tables (created by Flyway). Existing in-memory sessions are ephemeral and NOT migrated — switching `storage.type=database` starts empty by design. | Flyway migration creates schema; no data migration of in-memory state (it's transient). |
| Live service config | New `storage:` and `auth:` config blocks in `application.yaml` (+ env vars `DB_USER`/`DB_PASSWORD`, `API_KEY_HASH_*`, JWT secret/key, `auth.users`). docker-compose gains a `postgres` service for prod profile. | Add config blocks following the Phase-1 pattern; extend docker-compose. |
| OS-registered state | None. | None — verified: no OS-level registrations involved. |
| Secrets/env vars | JWT HMAC secret (dev) / RS256 private+public key (prod); API key SHA-256 hashes; DB password; per-user password hashes. All supplied via env-interpolated config, never in git. | Document required env vars; ensure `.env`/secrets are git-ignored; never log secrets. |
| Build artifacts | New `:backend` deps (Exposed/Hikari/Flyway/PG/H2/password4j). `coroutine-viz-core` artifact MUST stay DB-free for SDK-01. | Add deps to `:backend` only; verify core's POM is unchanged. |

## Common Pitfalls

### Pitfall 1: Auth becomes mandatory by accident (violates D-04a)
**What goes wrong:** Wrapping routes in `authenticate(...)` unconditionally makes every protected route 401 when no credentials are configured — breaking `git clone → run`.
**Why it happens:** Ktor's `authenticate {}` always challenges unless a principal is set; a naive wrap forgets the unconfigured case.
**How to avoid:** `authenticatedApi()` branches: if `authDisabled` (no api-key AND no users/jwt configured) it calls `build()` directly with no auth plugin in the path. Test explicitly (the existing `AuthTest` "when no API key configured, requests pass" is the template — extend to cover the jwt-also-unconfigured case).
**Warning signs:** A fresh checkout shows a login screen or returns 401 on `/api/sessions`.

### Pitfall 2: Browser EventSource cannot send the Bearer token (SSE auth gap)
**What goes wrong:** The frontend opens the SSE stream with `new EventSource(url)` (see `api-client.createEventSource` + `use-event-stream.ts`). `EventSource` has NO API to set request headers, so `Authorization: Bearer` cannot be attached — protected SSE streams would 401 in auth-on mode.
**Why it happens:** WHATWG EventSource spec omits header configuration by design.
**How to avoid:** Three options, recommend (a): (a) pass the JWT as a **query parameter** on the stream URL (`/api/sessions/{id}/stream?token=...`) and have the Ktor `jwt` provider also read the token from the query param for that route; (b) keep the live SSE stream public but tenant-scoped by session ownership (acceptable when auth is off; risky when on); (c) replace EventSource with `fetch`+`ReadableStream` (supports headers but loses native reconnect). For the **`/shared/:token`** route the token is already the credential in the path — no Bearer needed there. Plan must address the authenticated-app SSE path explicitly.
**Warning signs:** Live updates stop working only when `API_KEY`/JWT is configured; `/events` (a normal fetch) still works.

### Pitfall 3: Tenancy not threaded into session creation (AUTH-04 hole)
**What goes wrong:** Sessions get created without an owner, so the `forUser` filter has nothing to filter on and cross-tenant reads leak.
**Why it happens:** `createSession(name)` has no principal context today.
**How to avoid:** Thread the resolved `tenantId` (D-03) into creation and persist it on the `sessions` row; filter all reads in the Exposed store. When auth is off, owner is null/global (D-04b).
**Warning signs:** Two different API keys see each other's sessions when auth is on.

### Pitfall 4: Existing scaffolding diverges from ADR contracts (silent shape mismatch)
**What goes wrong:** `ShareTokenService`/`RetentionPolicy` exist but with different fields/defaults than ADR-019/015; reusing them as-is ships an API that doesn't match the frontend contract (no `access_count`/`last_accessed_at` → Manage-shares columns are empty).
**How to avoid:** Treat the existing services as throwaway in-memory prototypes; rewrite to the ADR shapes against the DB. Update their tests (`ShareTokenServiceTest`, `RetentionPolicyTest`) accordingly.
**Warning signs:** Manage-shares list (D-13) shows blank Views/Last-accessed columns.

### Pitfall 5: Hand-rolled rate limiter (re-solving a solved problem)
**What goes wrong:** A custom `Map<ip,count>` leaks memory, lacks refill/Retry-After, and is hard to scope per-route.
**How to avoid:** Use the Ktor `RateLimit` plugin (Pattern 9). It returns 429 + Retry-After and scopes via `rateLimit(RateLimitName(...))`.

### Pitfall 6: Retention deletes a session with an active share link (ADR-019 violation)
**What goes wrong:** The cleanup loop deletes an old session that still has a live, non-expired share token; the shared link 404s unexpectedly.
**Why it happens:** Max-age TTL ignores share state.
**How to avoid:** The retention delete must exclude sessions referenced by a non-expired share (`NOT EXISTS (SELECT 1 FROM shares WHERE session_id = s.id AND (expires_at IS NULL OR expires_at > now()))`). Cascade-delete handles the reverse (deleting a session removes its shares).
**Warning signs:** A shared link that worked yesterday returns 404 after the cleanup interval.

### Pitfall 7: Flyway PG module missing in Flyway 10+
**What goes wrong:** `flyway-core` alone throws "no database support" against PostgreSQL since Flyway 10 split dialects into separate modules.
**How to avoid:** Include `org.flywaydb:flyway-database-postgresql:11.8.2` (H2 support is in core). Verified against Maven Central.

## Code Examples

### SHA-256 API key store (AUTH-02) — replaces plaintext compare in current Auth.kt
```kotlin
// Source: derived from ADR-016 + existing Auth.kt structure
class ApiKeyStore(private val keys: List<KeyEntry>) {   // loaded from config: [{name, hash, role}]
    data class KeyEntry(val name: String, val sha256Hash: String, val role: Role)
    fun validate(rawKey: String): KeyEntry? {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawKey.toByteArray()).joinToString("") { "%02x".format(it) }
        // constant-time compare to avoid timing leaks
        return keys.firstOrNull { MessageDigest.isEqual(it.sha256Hash.toByteArray(), digest.toByteArray()) }
    }
}
```

### Token endpoint + JWT issue (AUTH-03, D-01/D-02)
```kotlin
// Source: ktor-server-auth-jwt docs + ADR-016
post("/api/auth/token") {                                  // ALWAYS public
    val creds = call.receive<LoginRequest>()               // {username, password}
    val user = userStore.find(creds.username)
    if (user == null || !Password.check(creds.password, user.passwordHash).withArgon2()) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials")); return@post
    }
    val token = JWT.create()
        .withSubject(user.username).withClaim("role", user.role.name)
        .withIssuedAt(Instant.now()).withExpiresAt(Instant.now().plus(Duration.ofHours(1)))
        .sign(Algorithm.HMAC256(jwtSecret))                // RS256 in prod via JwtConfig
    call.respond(mapOf("token" to token /*, "refreshToken" to ... */))
}
```

### Frontend api-client with Bearer + 401 interception (D-05/D-06/D-08)
```typescript
// Source: existing api-client.ts fetchJson, extended
import { getToken, clearToken } from './auth-store'
private async fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
  const token = getToken()                                  // in-memory source of truth, rehydrated from localStorage
  const res = await fetch(`${API_BASE_URL}${url}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),   // D-08: omitted when auth-off (D-07)
      ...options?.headers,
    },
  })
  if (res.status === 401) { clearToken(); router.navigate({ to: '/login' }); throw new Error('Unauthorized') } // D-05
  if (!res.ok) { /* existing error handling */ }
  return res.json()
}
// SSE: token via query param (Pitfall 2) — EventSource cannot set headers
createEventSource(sessionId: string): EventSource {
  const token = getToken()
  const q = token ? `?token=${encodeURIComponent(token)}` : ''
  return new EventSource(`${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}/stream${q}`)
}
```

### Read-only shared shell reusing SessionDetails (D-09/D-10)
```typescript
// Source: existing routes/sessions/$sessionId.tsx pattern; standalone shell (NO <Layout>)
export const Route = createFileRoute('/shared/$token')({ component: SharedView })
function SharedView() {
  const { token } = Route.useParams()
  // fetch GET /api/shared/:token (public, token=credential); handle 410/404/429 → EmptyState copy (UI-SPEC)
  return <SessionDetails sessionId={data.session.sessionId} readOnly /> // readOnly hides Run/record/Compare/Settings
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Exposed 0.x DSL (`SchemaUtils.createMissingTablesAndColumns`, old imports) — used in ADR-015 | Exposed 1.x (`org.jetbrains.exposed.v1.*`, stable API) | 1.0.0 GA → 1.3.0 (2026) | ADR snippets are stale; write against 1.x docs |
| Flyway single-jar DB support | Dialect modules split out (`flyway-database-postgresql`) | Flyway 10 (2023) | Must add the PG module or PG migrations fail (Pitfall 7) |
| In-memory `ShareTokenService` (object), in-memory `RetentionPolicy` | DB-backed share/retention to ADR-019/015 shape | This phase | Rewrite, don't reuse scaffolding as-is (Pitfall 4) |
| Plaintext single-key `authenticatedApi` (current `Auth.kt`) | SHA-256 multi-key + JWT dual provider | This phase | Current Auth.kt is a prototype to replace |

**Deprecated/outdated:**
- ADR-015 Exposed code snippets — illustrative only; the 1.x API differs.
- `ktor-server-auth` `Principal` interface — Ktor 3.x deprecated the marker `Principal` interface (note the `@file:Suppress("DEPRECATION")` already in `Auth.kt`/`AuthTest.kt`). New principals can be plain data classes; consult Ktor 3.3 auth docs for the current `validate { }` return type. Carry the suppression or migrate per current docs.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Putting Exposed impls in `:backend` (not `coroutine-viz-core`) is the right module boundary to keep the SDK DB-free | Standard Stack / Structure | If core must own the impls for some packaging reason, module layout shifts (low risk — SDK purity is the stated goal) |
| A2 | JWT-as-query-param is the chosen SSE auth mechanism | Pitfall 2 | If rejected on security grounds (token in URL/logs), fall back to fetch-stream or session-scoped public SSE; affects frontend stream code |
| A3 | A single portable Flyway migration set (JSON not JSONB) satisfies PERS-02 without PG-specific GIN indexing | Pattern 2/3 | If analytics querying on payload is later required, add a PG-only migration; no blocking impact this phase |
| A4 | Tenancy implemented as a `tenantId` column on `sessions` (vs the ADR's `FilteredSessionStore` decorator) | Pattern 7 | Either works; column approach assumed for query-side filtering simplicity |
| A5 | DB retention is a new `DbRetentionPolicy` rather than reusing the in-memory `RetentionPolicy` | Pattern 4 | If the planner prefers extending the existing class through the interface, events-trim performance suffers but correctness holds |
| A6 | Argon2id (password4j) is the KDF over bcrypt | Pattern 6 | Both are vetted; if deploy constraints forbid Argon2 memory cost, bcrypt is the documented fallback |
| A7 | Ktor `RateLimit` + `XForwardedHeaders` correctly yields per-client IP behind the project's Docker/proxy setup | Pattern 9 | Without XForwarded handling all shared viewers share one bucket; deploy-config concern, easily corrected |

## Open Questions

1. **Refresh-token persistence scope**
   - What we know: ADR-016 wants 7-day server-side refresh tokens.
   - What's unclear: whether refresh tokens must survive restart this phase (would need a `refresh_tokens` table) or an in-memory map is acceptable.
   - Recommendation: in-memory map this phase (config-seeded users, low volume); add a table later if needed. Confirm with planner/user.

2. **RS256 key provisioning for prod**
   - What we know: HMAC dev / RS256 prod per ADR-016.
   - What's unclear: where prod RS256 keys come from (mounted PEM files? env?).
   - Recommendation: load PEM path from config (`auth.jwt.privateKeyPath`/`publicKeyPath`); default to HMAC secret when unset (dev). Flag as deploy config.

3. **Per-session vs shared `ExposedEventStore`**
   - What we know: `EventStoreInterface` is per-`VizSession` today (each session owns an `EventStore`).
   - What's unclear: whether to keep that 1:1 model (construct `ExposedEventStore(sessionId)` per session) or introduce a single shared store scoping by sessionId.
   - Recommendation: keep the per-session model to minimize blast radius on `VizSession`; the Exposed store just scopes queries by its `sessionId`.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK / Gradle (Kotlin 2.2.20, Ktor 3.3.2) | All backend work | ✓ | gradle.properties: kotlin 2.2.20, ktor 3.3.2 | — |
| H2 (embedded) | Dev/test DB (PERS-01/02) | ✓ (added as dep, no service) | 2.3.232 | — |
| PostgreSQL service | Prod persistence profile | ✗ (docker-compose has no postgres service yet) | — | H2 file mode for non-prod; add `postgres` to docker-compose for prod |
| pnpm / Node (frontend) | Frontend routes/client | ✓ | per frontend toolchain | — |
| Reverse proxy (XForwarded) | Correct per-IP rate limiting in prod | ✗ (compose exposes backend directly) | — | Acceptable for dev; document XForwardedHeaders for proxied prod |

**Missing dependencies with no fallback:** none (H2 covers dev/test; PG is a prod-profile add, not a phase blocker).
**Missing dependencies with fallback:** PostgreSQL service (use H2 for dev/test; add PG service to docker-compose for the prod profile).

## Validation Architecture

> `workflow.nyquist_validation` is enabled (config.json). Section included.

### Test Framework
| Property | Value |
|----------|-------|
| Framework (backend) | JUnit 5 (Jupiter) 5.10.1 + Ktor Test Host (`ktor-server-test-host`) + kotlinx-coroutines-test |
| Framework (frontend) | Vitest 4.1 + Testing Library + MSW 2.7 (mock service worker) |
| Config file (backend) | `backend/build.gradle.kts` (`useJUnitPlatform()`); test resources via `application.yaml` overrides in `testApplication { environment { config = ... } }` |
| Config file (frontend) | Vite/Vitest config; existing `*.test.tsx` colocated |
| Quick run command (backend) | `cd backend && ./gradlew test --tests "*AuthTest" --tests "*Share*" --tests "*Exposed*"` |
| Full suite (backend) | `cd backend && ./gradlew test` |
| Quick run command (frontend) | `cd frontend && pnpm test --run <file>` |
| Full suite (frontend) | `cd frontend && pnpm test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PERS-01 | `storage.type=database` selects Exposed store; CRUD via interface | integration (H2) | `./gradlew test --tests "*ExposedSessionStoreTest"` | ❌ Wave 0 |
| PERS-02 | Sessions+events survive "restart" (new store instance, same H2 file/url reads prior rows); JSONB round-trips via PolymorphicSerializer | integration (H2) | `./gradlew test --tests "*PersistenceRestartTest"` | ❌ Wave 0 |
| PERS-03 | Retention deletes by max-age + trims events; SKIPS sessions with active shares | unit/integration | `./gradlew test --tests "*DbRetentionPolicyTest"` | ⚠️ rewrite existing `RetentionPolicyTest` |
| AUTH-01 | Non-public routes wrapped; public routes (`/health`,`/openapi.json`,`/api/auth/token`,`/api/shared`) stay open; pass-through when unconfigured | E2E (test host) | `./gradlew test --tests "*AuthTest"` | ⚠️ extend existing `AuthTest` |
| AUTH-02 | Wrong/absent key → 401; correct SHA-256 key → 200 | E2E | `./gradlew test --tests "*AuthTest"` | ⚠️ extend (current test uses plaintext) |
| AUTH-03 | `POST /api/auth/token` with seeded creds returns JWT; JWT on protected route → 200; bad creds → 401 | E2E | `./gradlew test --tests "*JwtAuthTest"` | ❌ Wave 0 |
| AUTH-04 | Tenant A cannot read tenant B's sessions; ADMIN sees all | E2E/integration | `./gradlew test --tests "*TenantIsolationTest"` | ❌ Wave 0 |
| AUTH-05 | reject-without / allow-with for BOTH api-key and JWT | E2E | `./gradlew test --tests "*AuthTest" --tests "*JwtAuthTest"` | ⚠️ extend |
| SHAR-01 | `POST /api/sessions/:id/share` with each expiry → 201 + url + expiresAt; "never" → null expiry | E2E | `./gradlew test --tests "*ShareRoutesTest"` | ⚠️ rewrite `ShareTokenServiceTest` + new route test |
| SHAR-02 | `GET /api/shared/:token` → 200 {session,events}; expired → 410; unknown/revoked → 404; increments access_count/last_accessed; 429 over rate limit; DELETE → 204 | E2E | `./gradlew test --tests "*ShareRoutesTest" --tests "*RateLimitTest"` | ❌ Wave 0 |
| AUTH/SHAR (FE) | api-client attaches Bearer; 401→/login; shared route renders read-only; expiry/copy/revoke flows | component (Vitest+MSW) | `pnpm test --run src/lib/api-client.test.ts src/routes/login.test.tsx src/routes/shared.$token.test.tsx` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** the focused `--tests "*<Area>*"` command for the area touched (backend) or `pnpm test --run <file>` (frontend).
- **Per wave merge:** `cd backend && ./gradlew test` + `cd frontend && pnpm test`.
- **Phase gate:** full backend + frontend suites green before `/gsd-verify-work`; lint clean (`detekt`/`ktlint`, ESLint).

### Wave 0 Gaps
- [ ] `ExposedSessionStoreTest.kt` / `ExposedEventStoreTest.kt` — H2-backed, covers PERS-01
- [ ] `PersistenceRestartTest.kt` — survives-restart + JSONB round-trip, covers PERS-02
- [ ] `DbRetentionPolicyTest.kt` — rewrite/replace existing `RetentionPolicyTest`; covers PERS-03 incl. active-share exclusion
- [ ] `JwtAuthTest.kt` — token issuance + protected-route acceptance, covers AUTH-03
- [ ] `TenantIsolationTest.kt` — cross-tenant denial + ADMIN bypass, covers AUTH-04
- [ ] `ShareRoutesTest.kt` — rewrite `ShareTokenServiceTest`; full endpoint matrix (201/200/410/404/204), covers SHAR-01/02
- [ ] `RateLimitTest.kt` — 60/min per-IP → 429, covers SHAR-02/D-12
- [ ] Extend `AuthTest.kt` — SHA-256 keys, dual-provider, public-route allowlist, pass-through-when-unconfigured (AUTH-01/02/05)
- [ ] Frontend: `api-client.test.ts` (Bearer + 401), `login.test.tsx`, `shared.$token.test.tsx`, share-dialog + manage-shares component tests
- [ ] Test DB harness: H2 in-memory/file fixture + Flyway-migrate-before-test helper (shared `conftest`-equivalent base class, mirror `BaseTestService.kt`)

## Security Domain

> `security_enforcement` not explicitly false in config — included.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | password4j Argon2id KDF (D-02); no plaintext storage; constant-time verify; JWT via ktor-server-auth-jwt |
| V3 Session Management | yes | Short-lived JWT (exp/iat), server-side refresh; JWT in localStorage (D-06) — accept XSS tradeoff vs UX (documented decision) |
| V4 Access Control | yes | Role table VIEWER/RUNNER/ADMIN (ADR-016); tenant isolation via `forUser`/`tenantId` filter (AUTH-04); deny-by-default on protected routes |
| V5 Input Validation | yes | zod on frontend forms (login, share); Ktor `receive<T>` + serialization for typed bodies; validate `expiresIn` enum server-side |
| V6 Cryptography | yes | Argon2id (never hand-rolled); SHA-256 for API-key compare with constant-time `MessageDigest.isEqual`; RS256 prod JWT |
| V7 Error Handling/Logging | yes | Never log secrets/tokens/hashes; generic 401 message ("Invalid credentials") to avoid user-enumeration |
| V11 Rate Limiting / Anti-automation | yes | Ktor RateLimit per-IP on shared endpoint (D-12); ADR-019 also suggests 10/min/user on share creation |

### Known Threat Patterns for Ktor + JWT + JDBC

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| SQL injection via JSONB/session id | Tampering | Exposed DSL parameterizes all queries; never string-concat SQL |
| JWT `alg=none` / algorithm confusion | Spoofing | java-jwt requires explicit `Algorithm`; verify with the same alg; never accept `none` |
| Timing attack on API-key compare | Information disclosure | `MessageDigest.isEqual` constant-time compare (Code Examples) |
| Token-in-URL leakage (SSE query param, share links) | Information disclosure | Short-lived JWT; share tokens are revocable + expiring; avoid logging full URLs; consider `Referrer-Policy` |
| Share-link brute force / scraping | DoS / Info disclosure | Per-IP rate limit 429 (D-12); UUID v4 tokens (122-bit entropy) |
| User enumeration at login | Info disclosure | Uniform "Invalid credentials" for unknown-user AND bad-password (UI-SPEC copy already does this) |
| Cross-tenant read | Elevation of privilege | `forUser` filter applied in store on EVERY read path; tested by `TenantIsolationTest` |
| Retention deleting shared sessions | DoS (availability of shared link) | NOT-EXISTS-active-share guard in cleanup (Pitfall 6) |

## Project Constraints (from CLAUDE.md)

- **Kotlin:** follow `detekt` + `ktlint`; use structured concurrency; **never `GlobalScope`** (retention/refresh loops must launch in an application/session scope, e.g. `VizSession.sessionScope` or a dedicated app scope — the existing `RetentionPolicy.start(scope, ...)` already takes a scope; preserve this).
- **TypeScript:** strict mode; ESLint flat config; avoid `any` (type the auth-store, share DTOs, shared-view response).
- **Tests:** colocated (`*.test.ts(x)` FE; `src/test/` BE).
- **Commits:** conventional (`feat:`/`fix:`/`docs:`); PRs focused, < 500 lines where possible.
- **HeroUI (UI-SPEC):** reuse `Modal`/`Input`/`Button`/`Card`/`Table`/`Select`, `EmptyState`, `ErrorAlert`, `RecordConfirmModal` pattern, `lib/toast.ts`; do NOT introduce shadcn or a second design system; `/shared` and `/login` are OUTSIDE the `Layout` nav chrome.

## Sources

### Primary (HIGH confidence)
- Codebase (read directly): `Auth.kt`, `AuthTest.kt`, `Application.kt`, `Routing.kt`, `SessionRoutes.kt`, `VizSession.kt`, `SessionManager.kt`, `EventStore.kt`, `SessionStoreInterface.kt`, `EventStoreInterface.kt`, `ShareTokenService.kt`, `RetentionPolicy.kt`, `SessionInfo.kt`, `VizEvent.kt`, `application.yaml`, `build.gradle.kts` (both), `gradle.properties`, `frontend/src/lib/api-client.ts`, `main.tsx`, `routes/__root.tsx`, `routes/sessions/$sessionId.tsx`, `SessionDetails.tsx`, `use-event-stream.ts`, `docker-compose.yml`.
- ADR-015 (persistence), ADR-016 (auth), ADR-019 (sharing) — authoritative.
- `03-CONTEXT.md` (D-01..D-13), `03-UI-SPEC.md`, `REQUIREMENTS.md` — locked decisions/requirements.
- Maven Central / central.sonatype.com (authoritative registry) — verified versions: Exposed 1.3.0, HikariCP 6.3.0, Flyway 11.8.2 (+ `flyway-database-postgresql`), PostgreSQL 42.7.7, H2 2.3.232, ktor-server-auth-jwt (3.x line), ktor-server-rate-limit (3.x line), password4j 1.8.2, at.favre.lib:bcrypt 0.10.2.
- Ktor official docs (ktor.io/docs/server-rate-limit.html) — RateLimit plugin: per-IP `requestKey { call.request.origin.remoteHost }`, `rateLimiter(limit, refillPeriod)`, route-scoped `rateLimit(RateLimitName(...))`, returns 429 + Retry-After.

### Secondary (MEDIUM confidence)
- WebSearch (Ktor RateLimit, Exposed) — used only to locate official docs; the Exposed version from generic search was stale (0.17.x) and was corrected against the authoritative registry.

### Tertiary (LOW confidence)
- None relied upon for recommendations.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all versions verified against the authoritative Maven Central registry this session; libraries are all ADR-mandated or first-party.
- Architecture: HIGH — grounded in directly-read source; the seam, config pattern, and SSE/event flow are confirmed in code, not assumed.
- Pitfalls: HIGH — the EventSource-header gap, scaffolding/ADR shape divergence, Exposed 0.x→1.x break, and Flyway PG-module split are concrete, verifiable facts.
- KDF / SSE-token-transport / Flyway-JSONB-portability recommendations: MEDIUM — multiple valid approaches; the chosen one is justified and flagged in the Assumptions Log for user confirmation.

**Research date:** 2026-06-20
**Valid until:** ~2026-07-20 (stable libraries; re-verify Exposed 1.x / Ktor BOM minor versions if planning slips a month)
