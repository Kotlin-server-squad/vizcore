---
phase: 03-persistence-auth-sharing
plan: 01
subsystem: database
tags: [exposed, hikaricp, flyway, h2, postgres, jdbc, kotlinx-serialization, ktor]

# Dependency graph
requires:
  - phase: 01-foundation
    provides: SessionStoreInterface/EventStoreInterface seams + bounded EventStore + appJson PolymorphicSerializer
  - phase: 02
    provides: SSE /events wire shape (appJson + PolymorphicSerializer(VizEvent::class))
provides:
  - "Optional JDBC persistence behind SessionStoreInterface/EventStoreInterface (storage.type=database)"
  - "DatabaseFactory (Hikari + Exposed connect + Flyway.migrate)"
  - "V1 Flyway schema: sessions, events, shares (portable CLOB-payload DDL)"
  - "ExposedSessionStore + ExposedEventStore (JSON payload via SSE serializer)"
  - "VizSession injectable event-store-factory seam (default = in-memory)"
  - "SessionManager.useStore() façade for transparent store selection"
affects: [03-02-auth, 03-03-tenancy-retention, 03-04-sharing]

# Tech tracking
tech-stack:
  added:
    - "org.jetbrains.exposed:exposed-{core,jdbc,json,kotlin-datetime}:1.3.0"
    - "com.zaxxer:HikariCP:6.3.0"
    - "org.flywaydb:flyway-core + flyway-database-postgresql:11.8.2"
    - "org.postgresql:postgresql:42.7.7"
    - "com.h2database:h2:2.3.232"
  patterns:
    - "Config-selected store behind the existing seam (storage.type branch in Application.module)"
    - "Single portable Flyway migration set (db/migration/common) for H2 + PG"
    - "DB event payload (de)serialized with the EXACT SSE PolymorphicSerializer (wire parity)"
    - "Façade delegation: SessionManager.useStore() swaps backing store without touching route call sites"

key-files:
  created:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/DatabaseFactory.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/ExposedSessionStore.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/ExposedEventStore.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/tables/SessionsTable.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/tables/EventsTable.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/tables/SharesTable.kt
    - backend/src/main/resources/db/migration/common/V1__core_schema.sql
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/persistence/ExposedSessionStoreTest.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/persistence/PersistenceRestartTest.kt
  modified:
    - backend/build.gradle.kts
    - backend/src/main/resources/application.yaml
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/SessionManager.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt

key-decisions:
  - "VizSession event-store seam = injectable eventStoreFactory ctor param (default in-memory EventStore); DB store injects ExposedEventStore — no DB dep leaks into coroutine-viz-core"
  - "SessionStore indirection = SessionManager.useStore() façade (lowest blast radius: 22 route call sites unchanged)"
  - "store typed as EventStoreInterface; VizSession.send uses store.record (was store.append); MetricsWiring onEvict via EventStore smart-cast"
  - "JSON-vs-JSONB: payload stored as portable CLOB/text, (de)serialized in-app; PG JSONB+GIN deferred (not required by PERS-01..03)"
  - "H2 URLs use CASE_INSENSITIVE_IDENTIFIERS=TRUE so Exposed's quoted lowercase columns (e.g. \"name\") match H2's default uppercase folding"
  - "Exposed 1.3.0: use top-level org.jetbrains.exposed.v1.core.{eq,greater,and}; the SqlExpressionBuilder member ops are deprecated-as-error"

patterns-established:
  - "Persistence impls live in :backend only — coroutine-viz-core stays a zero-DB publishable SDK"
  - "DatabaseFactory.init has two overloads: ApplicationConfig (prod) and DataSource (tests)"

requirements-completed: [PERS-01, PERS-02]

# Metrics
duration: ~40min
completed: 2026-06-21
---

# Phase 3 Plan 01: Persistence Foundation Summary

**Optional JDBC persistence (Exposed 1.3.0 + HikariCP + Flyway) behind the existing SessionStore/EventStore seams, selectable via `storage.type=database`, with sessions and events surviving a backend restart and round-tripping byte-identically through the SSE serializer.**

## Performance

- **Duration:** ~40 min
- **Started:** 2026-06-21T07:38:02Z
- **Completed:** 2026-06-21
- **Tasks:** 2
- **Files modified:** 15 (9 created, 6 modified)

## Accomplishments
- Exposed 1.3.0 JDBC stack added to `:backend` only; `coroutine-viz-core` stays DB-free (SDK purity preserved).
- `V1__core_schema.sql` Flyway migration creates `sessions`, `events`, `shares` with portable ANSI DDL (H2 dev + PG prod from one file).
- `DatabaseFactory` builds a HikariCP pool (ADR-015 sizing), connects Exposed, and runs Flyway with `baselineOnMigrate`.
- `ExposedSessionStore`/`ExposedEventStore` implement the real session-centric seams; event payloads use the exact SSE `PolymorphicSerializer` so DB ↔ wire is byte-identical.
- `storage.type` store-selection wired in `Application.module()`; `memory` default is unchanged (D-04a), persistence does not force auth on (D-04b, warns instead).
- Tests: `ExposedSessionStoreTest` (Flyway DDL + CRUD, PERS-01) and `PersistenceRestartTest` (H2 file restart + serializer parity, PERS-02) all green; full backend + core suites pass.

## Task Commits

1. **Task 1: Wave-0 tests + Exposed deps + Flyway V1 schema + DatabaseFactory + store impls** - `b2df999` (feat)
2. **Task 2: store selection wiring + restart test** - `01c881a` (feat)

_TDD note: the store impls (`ExposedSessionStore`/`ExposedEventStore`) were authored in Task 1 because Task 1's `ExposedSessionStoreTest` references `ExposedSessionStore` for CRUD; Task 2 focused on store selection + the restart test (see Deviations)._

## Files Created/Modified
- `backend/build.gradle.kts` - Exposed/Hikari/Flyway/PG/H2 deps (:backend only)
- `backend/src/main/resources/application.yaml` - `storage:` block (type/database/retention), env-interpolated
- `backend/src/main/resources/db/migration/common/V1__core_schema.sql` - sessions/events/shares DDL
- `backend/.../persistence/DatabaseFactory.kt` - Hikari + Database.connect + Flyway.migrate
- `backend/.../persistence/tables/{SessionsTable,EventsTable,SharesTable}.kt` - Exposed table objects
- `backend/.../persistence/ExposedSessionStore.kt` - SessionStoreInterface impl
- `backend/.../persistence/ExposedEventStore.kt` - EventStoreInterface impl, JSON payload
- `backend/.../Application.kt` - `configureStorage()` storage.type branch
- `backend/.../session/VizSession.kt` - injectable event-store-factory seam; `store: EventStoreInterface`
- `backend/.../session/SessionManager.kt` - `useStore()` façade delegation
- `backend/.../MetricsWiring.kt` - onEvict via `EventStore` smart-cast
- `backend/.../persistence/ExposedSessionStoreTest.kt`, `PersistenceRestartTest.kt` - PERS-01/02 coverage

## Decisions Made
- **VizSession event-store seam:** added an `eventStoreFactory: (String) -> EventStoreInterface` constructor param defaulting to the in-memory `EventStore`. The DB store injects `ExposedEventStore(db, sessionId)`. This keeps coroutine-viz-core DB-free.
- **SessionStore indirection:** `SessionManager.useStore(store)` makes `SessionManager` a façade that delegates lifecycle ops to a configured backing store, leaving all 22 route call sites untouched (lower blast radius than introducing a new accessor).
- **JSON vs JSONB portability:** `payload`/`metadata` are CLOB/`text`, (de)serialized in-app via the SSE serializer. Native PG `JSONB`+GIN indexing is deferred — not required by PERS-01..03; a PG-only `ALTER ... TYPE jsonb` migration can be added later for analytics.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Exposed 1.x typed `store` broke `store.append`/`store.onEvict` call sites**
- **Found during:** Task 1 (VizSession seam change)
- **Issue:** Re-typing `VizSession.store` from `EventStore` to `EventStoreInterface` broke `VizSession.send` (`store.append`), `MetricsWiring` (`store.onEvict`), and `BoundedStoreWiringTest` (`store.append`).
- **Fix:** `send` now calls `store.record`; `MetricsWiring` casts `(session.store as? EventStore)?.onEvict` (eviction is an in-memory concern); test switched to `store.record`.
- **Files modified:** VizSession.kt, MetricsWiring.kt, BoundedStoreWiringTest.kt
- **Verification:** core + backend suites green
- **Committed in:** b2df999

**2. [Rule 1 - Bug] H2 column-case mismatch ("name" not found)**
- **Found during:** Task 1 (running ExposedSessionStoreTest)
- **Issue:** Flyway's unquoted `name` column folds to `NAME` on H2, but Exposed emits quoted `"name"` (case-sensitive) → `Column "name" not found`.
- **Fix:** Added `CASE_INSENSITIVE_IDENTIFIERS=TRUE` to H2 test + default URLs.
- **Files modified:** application.yaml, ExposedSessionStoreTest.kt (and PersistenceRestartTest.kt uses the same flag)
- **Verification:** all CRUD tests pass
- **Committed in:** b2df999 / 01c881a

**3. [Rule 3 - Blocking] Exposed 1.3.0 deprecated `SqlExpressionBuilder` member operators (as compile errors)**
- **Found during:** Task 1 (first backend compile)
- **Issue:** `import ...SqlExpressionBuilder.eq/greater` are deprecated in 1.3.0 and the build treats the deprecation as an error.
- **Fix:** Switched to top-level `org.jetbrains.exposed.v1.core.{eq, greater, and}`.
- **Files modified:** ExposedEventStore.kt, ExposedSessionStore.kt
- **Verification:** compiles clean
- **Committed in:** b2df999

**4. [Process] Store impls authored in Task 1 instead of Task 2**
- **Reason:** Task 1's `ExposedSessionStoreTest` exercises `ExposedSessionStore` CRUD, which requires the impl to exist. The impls were therefore written in Task 1; Task 2 delivered the store-selection wiring + restart test as planned. No scope change, same files.

---

**Total deviations:** 3 auto-fixed (2 blocking, 1 bug) + 1 process resequencing
**Impact on plan:** All auto-fixes were required for correctness/compilation. No scope creep; all planned artifacts delivered.

## Issues Encountered
- **detekt** cannot run locally (`Invalid value (24) passed to --jvm-target`; detekt 1.23.7 max is JVM 22 vs local JVM 24) — environmental, pre-existing, logged in `deferred-items.md`.
- **ktlint** reports pre-existing `multiline-expression-wrapping` violations in `Application.kt`, `MetricsWiring.kt`, `routes/SessionRoutes.kt`, `VizEventSerializersModule.kt` (untouched lines) — out of scope, logged in `deferred-items.md`. All NEW persistence files are ktlint-clean.

## Known Stubs
- `sessions.tenant_id` and `sessions.scenario`/`metadata` are persisted as `null` for now; `tenant_id` is declared in the V1 schema so Plan 03-03 can populate tenancy without a second migration. This is intentional and documented (the seam exists; the data wiring is a later plan's concern).
- `SharesTable` + the `shares` table are created but unused in this plan — Plan 03-04 (sharing) wires the DB-backed ShareService against them. Intentional (the V1 migration front-loads all three tables to avoid a later migration).

## Threat Flags
None — all new surface (JDBC store queries, credential handling, Flyway integrity) was already enumerated in the plan `<threat_model>` (T-03-01/02/03/SC) and mitigated: Exposed DSL parameterizes every query, the DB password is never logged, and the schema is a versioned Flyway migration.

## Next Phase Readiness
- Persistence seam is live and config-gated; Plan 03-02 (auth) and 03-04 (sharing) can build on the DB.
- `shares` table + `SharesTable` object ready for the DB-backed ShareService (Plan 03-04).
- `tenant_id` column ready for tenancy threading (Plan 03-03); retention config keys present in `application.yaml`.

## Self-Check: PASSED

All created files exist on disk (DatabaseFactory, ExposedSessionStore, ExposedEventStore, V1__core_schema.sql, both test files, SUMMARY). Both task commits (`b2df999`, `01c881a`) present in git history.

---
*Phase: 03-persistence-auth-sharing*
*Completed: 2026-06-21*
