---
phase: 01
slug: foundation-production-readiness
status: secured
threats_open: 0
threats_total: 40
asvs_level: 1
created: 2026-06-12
---

# SECURITY — Phase 01: Foundation Production Readiness

**Audit date:** 2026-06-12
**ASVS Level:** 1
**Auditor stance:** every mitigation assumed absent until proven present in current code (post review-fix round WR-02/WR-03/WR-06/WR-14/IN-10).
**Register:** union of `<threat_model>` blocks across plans 01-01 … 01-15 = 40 threats (22 mitigate, 18 accept, 0 transfer). Note: the audit brief cited 39; the actual union of the 15 plan blocks enumerates 40.

**Result: SECURED — 40/40 closed (22 mitigations verified in code, 18 accepted risks logged below).**

---

## Verified Mitigations (disposition: mitigate)

| Threat ID | Category | Component | Evidence (current code) |
|-----------|----------|-----------|-------------------------|
| T-01-01 | DoS | SSE serialization completeness | `backend/src/main/kotlin/com/jh/proj/coroutineviz/VizEventSerializersModule.kt:21-22` (polymorphic module, 66 subclasses), `:106-107` (`appJson` wires module); consumed by `Serialization.kt:17` and `routes/SessionRoutes.kt`; D-04 completeness guard `backend/src/test/kotlin/com/jh/proj/coroutineviz/events/VizEventSerializersModuleTest.kt:18` (every subclass must be registered) |
| T-01-03 | Tampering (supply chain) | Maven/npm installs, plan 01-01 | No dependency added: last `backend/build.gradle.kts` change in phase is the approved logstash commit `ba6e455` (01-05); `frontend/package.json` last touched pre-phase (`3a349b8`) |
| T-01-04 | DoS (client crash) | ValidationPanel render | `frontend/src/components/validation/ValidationPanel.tsx:71` (`{data && …}` guard), `:81-88` reads real `{results, timing}` shape via `data.results.filter(...)`; `:130` `<TimingReportView timing={data.timing} />`; component test `ValidationPanel.test.tsx` colocated |
| T-01-05 | Tampering (supply chain) | npm installs, plan 01-02 | `frontend/package.json` unchanged since pre-phase commit `3a349b8` (git log) — no new npm packages |
| T-01-06 | DoS | EventStore unbounded growth | `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/EventStore.kt:26-27,42` (`ArrayDeque`, eviction `while (events.size > maxEvents)`); wired via `SessionManager.kt:52` → `VizSession.kt:52`; regression test `EventStoreTest.kt:146-147` (`store.size() <= 500`, `size() == all().size`) |
| T-01-07 | Tampering (input validation) | `session.maxEvents` env parse | `backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt:18-19` — `toIntOrNull() ?: 10_000` safe default; passed to `SessionManager.configure` at `:20` |
| T-01-08 | Tampering | session-fork reintroduction | `backend/src/test/kotlin/com/jh/proj/coroutineviz/ForkDeletionTest.kt:46` (`SESSION_FORK_DIR`), `:90` (FND-01 static guard); `backend/src/main/.../session/` directory does not exist (verified) |
| T-01-10 | Spoofing/Elevation | CORS origins | `backend/src/main/kotlin/com/jh/proj/coroutineviz/HTTP.kt:26-40` — origins from `cors.allowedOrigins` config, `allowHost` per-host, zero `anyHost()` matches in main source; `CorsConfigTest.kt:70-78` rejects `http://evil.example.com` |
| T-01-SC | Tampering (supply chain) | logstash-logback-encoder | `backend/build.gradle.kts:44` — pinned `net.logstash.logback:logstash-logback-encoder:8.1`; human legitimacy gate recorded in 01-05-SUMMARY (T-01-SC resolved) |
| T-01-06-02 | Tampering | sseClientsGauge accuracy | `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:204` (increment after 404 guard — sole entry point), `:265-269` (CancellationException rethrown), `:272-273` (`finally { sseClientsGauge.decrementAndGet() }` — every disconnect path decrements exactly once); value-asserting test `MetricsWiringTest.kt` |
| T-01-06-SC | Tampering (supply chain) | ktor-client-sse test dep | Closed vacuously: dependency never installed — artifact does not exist; deviation documented in 01-06-SUMMARY ("Removed non-existent ktor-client-sse dependency"); no `ktor-client-sse` match in any `*.kts`/`*.toml` |
| T-01-07-01 | Tampering | duplicate wrappers FQCNs | `backend/src/main/.../wrappers/` directory does not exist (verified); `ForkDeletionTest.kt:49,70` (`WRAPPERS_FORK_DIR` guard, 11 class names), `:75-85` (anti-vacuous-pass cwd assertion) |
| T-01-07-02 | Repudiation | lost fork-only behavior | 01-07-SUMMARY: pre-deletion parity diff confirmed zero non-import differences in all 11 files; full-suite parity verification in Task 2; no reconciliation needed (import-only divergence) |
| T-01-08-01 | DoS | unparented scenario coroutines | `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:66-68` — `Job(session.sessionScope.coroutineContext[Job])` parents VizScope to the session scope; session close cancels scenarios |
| T-01-08-02 | Tampering | broken cancel semantics | `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeCancellationTest.kt:30` (`VizScope default context has a Job`), `:40` (`cancelAndJoin stops a running coroutine`) |
| T-01-09-01 | Tampering | terminal-last event ordering | `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeTerminalOrderingTest.kt:35,66-74` — asserts terminal event carries highest seq; plus `VizScopeAsyncTerminalOrderingTest.kt` for async path |
| T-01-10-01 | Info disclosure (correctness) | TimingReport unit mismatch | `backend/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzer.kt:9` (`NANOS_PER_MILLI = 1_000_000L`), `:64,81,96` (ns/MILLI divisions); magnitude tests `TimingAnalyzerTest.kt:168` ("milliseconds not nanoseconds"), `:204` (magnitude-sanity 5s scenario) — but see unregistered flag UF-01 |
| T-01-11-01 | Info disclosure (correctness) | discriminator normalization | `frontend/src/lib/event-discriminator.test.ts:96` ("defined, non-empty kind for all 17 backend type values"), `:196` ("maps all 17 backend type values to a defined kind") |
| T-01-12-01 | DoS | session-page polling loop | Zero `setInterval` matches in `frontend/src/routes` + `frontend/src/components` (non-test); debounced SSE invalidation `use-event-stream.ts:56` (`INVALIDATION_DEBOUNCE_MS = 400`); gated thread polling `use-thread-activity.ts:27-37` (`refetchInterval: isLive ? 5000 : 2000`) |
| T-01-13-01 | DoS | refetch/invalidation cadence | `frontend/src/hooks/use-event-stream.ts:56` (debounce 400ms caps ceiling), `:64` (`INVALIDATION_MAX_WAIT_MS = 1000` floors interval), `:248-253` (max-wait-capped flush logic) |
| T-01-14-01 | Tampering/DoS | buildThreadLanes malformed input | `frontend/src/lib/thread-lanes.test.ts:98` (empty map / missing dispatcherName / zero span), `:115-121` (span-0 → utilization 0, `Number.isNaN(...) === false`), `:126` (unmatched RELEASED after eviction); render guarded by `threadActivity ?` conditional |
| T-01-15-01 | DoS | frontend SSE reconnect loop | `frontend/src/hooks/use-event-stream.ts:72` (`SSE_MAX_RETRIES = 5`), `:78` (`SSE_RETRY_MAX_DELAY_MS = 8000`), `:168-175` (bounded exponential backoff, eventSource closed + nulled before scheduling — no double connection), transient branch defers to native EventSource reconnect; open resets retry budget (`:150-154`) |

---

## Accepted Risks Log (disposition: accept)

All entries below are formally accepted for Phase 1 (local/dev deployment; Phase 3 / ADR-016 adds auth). This log is the authoritative record closing each accept disposition.

| Threat ID | Component | Accepted Risk | Rationale |
|-----------|-----------|---------------|-----------|
| T-01-02 | `CoroutineFailed(cause::class.simpleName, cause.message)` | Exception class + message on event stream | Developer-authored teaching content, no PII; Phase 3 adds auth |
| T-01-09 | `/api/health`, `/api/ready` | Session count, memory, version exposed | No secrets/PII; Phase 3 auth gates it |
| T-01-11 | `@redocly/cli` | Dev-time npm validator | Well-known tool, not in runtime bundle |
| T-01-12 | `/metrics` | Session counts/buffer sizes tagged by sessionId | No secrets/PII; Phase 3 auth gates it |
| T-01-13 | prod JSON logs | Coroutine labels/messages in LogstashEncoder output | Developer-authored content, no PII |
| T-01-06-01 | sseClientsGauge | Many-SSE-connection attacker | Observability counter only; rate limiting tracked as PERF-04 (Phase 4); finally-decrement prevents upward leak |
| T-01-07-SC | plan 01-07 installs | None | No packages installed (deletion-only plan) |
| T-01-08-SC | plan 01-08 installs | None | No packages installed (one source edit + one test) |
| T-01-09-02 | plan 01-09 installs | None | Reorder-only change, no new deps |
| T-01-10-02 | plan 01-10 installs | None | Arithmetic-only change, no new deps |
| T-01-11-02 | plan 01-11 installs | None | Logic + test only, no new deps |
| T-01-12-02 | plan 01-12 installs | None | UI logic + copy + test only, no new deps |
| T-01-13-02 | plan 01-13 installs | None | In-repo TypeScript edits only, no new deps |
| T-01-14-02 | Threads tab rendering | Coroutine/thread ids and names rendered | Already shown elsewhere in UI; no PII |
| T-01-14-SC | plan 01-14 installs | None | No new deps |
| T-01-15-02 | `: connected` SSE comment frame | Static comment string | Carries no data beyond the stream itself (`SessionRoutes.kt:212`) |
| T-01-15-03 | server full-history replay per reconnect | Pre-existing replay cost (REVIEW WR-01) | Client-side seq dedup neutralizes UI impact (`SessionRoutes.kt:256-263`); server-side Last-Event-ID replay deferred |
| T-01-15-SC | plan 01-15 installs | None | No new deps |

---

## Unregistered Flags (WARNING — not blockers)

| Flag | Description | Suggested Action |
|------|-------------|------------------|
| UF-01 | Duplicate FQCN `com.jh.proj.coroutineviz.checksystem.TimingAnalyzer` exists in BOTH `backend/src/main/.../checksystem/TimingAnalyzer.kt` (ms-converting, carries T-01-10-01 fix) and `backend/coroutine-viz-core/src/main/.../checksystem/TimingAnalyzer.kt` (documents nanosecond durations). `backend` depends on `coroutine-viz-core` (`build.gradle.kts:28`). This is the exact duplicate-FQCN classloader-ordering risk class that T-01-07-01 mitigated for `wrappers/` — but `checksystem/` is NOT covered by `ForkDeletionTest`. If fat-jar/classpath ordering ever resolves the core copy, the T-01-10-01 ns→ms mitigation is silently undone with no failing test. | Reconcile the checksystem fork into one module (or extend ForkDeletionTest to guard `checksystem/`) in a future phase |

SUMMARY `## Threat Flags` sections (01-01, 01-03, 01-04, 01-05, 01-13, 01-15) all map to registered threat IDs — informational, no additional unregistered surface declared by executors.

---

## Notes

- All evidence verified against CURRENT code (post WR-02/WR-03/WR-06/WR-14/IN-10 hardening): seq+append atomicity comment honored at `SessionRoutes.kt:251-255`; bounded live buffer `SSE_LIVE_BUFFER_CAPACITY` / `DROP_OLDEST` at `SessionRoutes.kt:226-239`; CancellationException rethrow at `SessionRoutes.kt:265-269`.
- No implementation files were modified by this audit.
