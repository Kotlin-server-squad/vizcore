# Deferred Items — Phase 03

Out-of-scope discoveries logged during execution. Do NOT fix as part of the
current task; track here for a future dedicated cleanup.

| Item | Detail | Discovered | Status |
|------|--------|------------|--------|
| Pre-existing ktlint violations (main) | `Application.kt`, `MetricsWiring.kt`, `routes/SessionRoutes.kt`, `VizEventSerializersModule.kt` carry `multiline-expression-wrapping` style violations that pre-date Plan 03-01. New persistence files are ktlint-clean. | 03-01 | Deferred (out of scope) |
| Pre-existing ktlint violations (test) | `ktlintTestSourceSetCheck` fails on test files unrelated to 03-01. | 03-01 | Deferred (out of scope) |
| detekt JVM-target incompatibility | `./gradlew detekt` fails with `Invalid value (24) passed to --jvm-target` — detekt 1.23.7 supports max JVM 22 but the local toolchain reports 24. Environmental; not caused by 03-01. | 03-01 | Deferred (environmental) |
| Pre-existing ktlint violations (test, residual) | `MetricsWiringTest.kt:100` + `VizScopeCompletionHandlerTest.kt:41` carry inline-comment violations flagged `cannot be auto-corrected`; both files untouched by Plan 03-02. All 03-02 auth files (main + test) are ktlint-clean. `routes/SessionRoutes.kt` main-source violations were incidentally auto-fixed by `ktlintFormat` when 03-02 edited that file. | 03-02 | Deferred (out of scope) |
