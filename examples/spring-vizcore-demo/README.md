# spring-vizcore-demo

A minimal **separate-JVM Spring Boot app** that embeds the `coroutine-viz-client` library and
streams its live coroutine activity into a running vizcore backend. Demonstrates the Phase-7
real-app transport (RCO-04): `VizcoreClient.start(...)` → JWT session create → client WebSocket →
backend ingest → existing EventStore → SSE → frontend.

## Run it

1. **Start the backend** (from `../../backend`). Default auth is off and storage is in-memory, so
   no config is needed. If port 8080 is taken, pick another:
   ```bash
   cd ../../backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) PORT=8090 ./gradlew run
   ```
2. **Run this demo**, pointing at the backend:
   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 21) VIZCORE_BACKEND_URL=http://localhost:8090 ./gradlew bootRun
   ```
3. **Watch it** in the frontend (from `../../frontend`):
   ```bash
   VITE_PROXY_TARGET=http://localhost:8090 pnpm dev
   ```
   then open the `spring-demo-named-*` session.

Config (env or `application.properties`): `VIZCORE_BACKEND_URL`, `VIZCORE_TOKEN` (any value when
backend auth is off), `VIZCORE_APP_NAME`.

## What it streams

`VizcoreDemoRunner` drives a small, **named**, bounded structured-concurrency workload
(`request-N` → `db-query-N`, `http-call-N`, `compute-N`, `pipeline-N`, `io-write-N`). Names come
through because each coroutine carries a `CoroutineName` — the adapter maps that to the UI label.

## `./gradlew spike`

`HierarchySpike.kt` is a throwaway proof that the DebugProbes real-app path can reconstruct the
parent/child tree: it inverts `Job.children` across a full `DebugProbes.dumpCoroutinesInfo()` dump
and climbs to the nearest **observed** ancestor (direct parents are intermediate
`coroutineScope`/`async` jobs DebugProbes doesn't report). This is the evidence behind the Phase-8
"reconstruct coroutine hierarchy" work.

## Notes

- Needs JDK 21 (Gradle toolchain). DebugProbes self-attaches a byte-buddy agent, so `bootRun`/`spike`
  pass `-Djdk.attach.allowAttachSelf=true`.
- `kotlin-coroutines.version` is pinned to `1.11.0` to match the vizcore jars (Spring's BOM otherwise
  forces a different version → `runBlocking` ABI mismatch).
- Depends on the prebuilt `coroutine-viz-client` / `coroutine-viz-core` jars under
  `../../backend/.../build/libs`. Rebuild them if missing:
  `cd ../../backend && ./gradlew :coroutine-viz-client:jar :coroutine-viz-core:jar`.
