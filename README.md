# vizcore — Kotlin Coroutine Visualizer

Real-time visualization of Kotlin coroutines: structured-concurrency trees, flow operators,
dispatchers and synchronization primitives, streamed live out of a running JVM.

vizcore is three pieces:

| Piece | Where | What it does |
|---|---|---|
| **Backend** | `backend/` | Kotlin 2.2 + Ktor 3.3 server. Event-sourced sessions, ingest over WebSocket, replay + live `SSE` to the UI. Serves on `:8080`. |
| **Frontend** | `frontend/` | React 19 + TypeScript SPA (Vite, TanStack, HeroUI, Tailwind). Consumes SSE and renders tree, graph, timeline and thread-lane views. Serves on `:3000` and proxies `/api` to the backend. |
| **SDK** | `backend/coroutine-viz-client/` | `coroutine-viz-client`, the embeddable client. Drop it into your own app, point it at a backend, and the coroutines of that JVM show up in the UI. |

The **IntelliJ plugin (`intellij-plugin/`) is postponed** and is *not* part of the default
build. It only takes part when you opt in explicitly:
`./gradlew -Pvizcore.withPlugin=true …`. Without that flag no IntelliJ Platform SDK is
downloaded and no plugin module is configured.

v1.2 ships **memory mode**: sessions live in the backend process and are gone when it stops.

## Quickstart

Two ways to get the whole stack up from a clean clone. Pick one.

### (a) Docker

Needs Docker with Compose v2. From the repository root:

```bash
docker compose up
```

That builds and starts both services and waits for the backend to report healthy:

- UI → <http://localhost:3000>
- API → <http://localhost:8080/api/ready>

If `8080` or `3000` is taken on your machine, move the host ports instead of killing
whatever owns them:

```bash
BACKEND_PORT=18081 FRONTEND_PORT=13001 docker compose up
```

### (b) Locally, without Docker

Needs **JDK 21**, **Node 24+** (`frontend/package.json` `engines`) and **pnpm**.

```bash
# terminal 1 — backend on :8080
cd backend
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; otherwise point JAVA_HOME at a JDK 21
./gradlew :run

# terminal 2 — frontend on :3000, proxying /api to :8080
cd frontend
pnpm install
pnpm dev
```

Use `./gradlew :run` — the leading colon pins the task to the root (server) project. Without
the colon, Gradle also matches the CLI module's own `run` task and the launch fails.

Default configuration needs no secrets: auth is off and storage is in-memory.

## Instrument your own app

The SDK is a plain Kotlin/JVM 17 library. `VizcoreClient.start(…)` creates a session on the
backend, installs `kotlinx-coroutines` DebugProbes in your process, and streams the
synthesized events over a WebSocket — with reconnect and session recovery handled for you.
Name your coroutines with `CoroutineName` and the labels show up in the UI.

```kotlin
import com.jh.proj.coroutineviz.client.VizcoreClient
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main() {
    val client =
        VizcoreClient.start(
            appName = "my-service",
            backendUrl = System.getenv("VIZCORE_BACKEND_URL") ?: "http://localhost:8080",
            token = System.getenv("VIZCORE_TOKEN") ?: "dev-token",
        )
    println("vizcore session: ${client.sessionIdOrNull ?: "not created yet, the backend is not reachable"}")
    try {
        runBlocking {
            launch(CoroutineName("worker")) { delay(250) }
        }
    } finally {
        client.stop() // idempotent: stops the probes, closes the socket, cancels the scope
    }
}
```

`start()` returns an already-running client, so there is no second `start` call to make.
`stop()` is safe to call from a shutdown hook.

`start()` does not fail when the backend is down or the URL is wrong, so you can call it during
your app's startup. It tries to create the session for up to a few seconds; if it cannot, it logs a
WARN with the cause and returns anyway. The client then keeps trying in the background, with the
same capped backoff it uses to reconnect, and streams the app's coroutines once the backend
answers. Until then `sessionIdOrNull` is `null` (and `currentSessionId` is empty). Events from that
window are kept up to the client's buffer limit of 10,000.

**What gets captured.** The SDK samples the JVM's coroutines about every 150 ms, so treat
the view as a sampled picture, not a complete trace:

- A coroutine that starts and finishes between two samples does not appear. Fast request
  handlers (a few milliseconds each) usually fall into this gap; slower work, such as
  outbound HTTP calls, long-running loops and waiting on I/O, shows up well
  ([#206](https://github.com/Kotlin-server-squad/vizcore/issues/206)). From the SDK release
  after 0.2.1, you can sample more often by passing an interval in milliseconds:
  `VizcoreClient.start(appName, backendUrl, token, pollIntervalMillis = 50)`, or `poll=50`
  with the Java agent. Each sample dumps every coroutine in the JVM, so keep it at 10 ms or
  more and expect more CPU use on apps with many coroutines.
- The SDK leaves its own coroutines (its poll loop, its session bookkeeping and the Ktor
  client it streams with) out of your session. A few Ktor coroutines remain, because nothing
  ties them to the SDK: the socket reader and writer for its connection
  (`cio-from-nio-reader`, `cio-to-nio-writer`), `cio-request-timeout` while it makes an HTTP
  request, and Ktor's process-wide `nonce-generator`
  ([#225](https://github.com/Kotlin-server-squad/vizcore/issues/225)).
- `stop()` drops events that have not been sent yet, so a very short program may not show
  its last completions ([#207](https://github.com/Kotlin-server-squad/vizcore/issues/207)).

**JVM flags.** DebugProbes load a Java agent into your running app. On JDK 21 this works
without flags but prints a warning, and a future JDK will refuse it by default. Two flags
make it explicit:

- `-XX:+EnableDynamicAgentLoading` allows loading an agent at runtime, without the warning.
- `-Djdk.attach.allowAttachSelf=true` lets the JVM attach to itself directly. Without it, the
  agent loader falls back to attaching from a helper process.

With the Gradle `application` plugin (`./gradlew run`):

```kotlin
// build.gradle.kts
application {
    applicationDefaultJvmArgs = listOf("-XX:+EnableDynamicAgentLoading", "-Djdk.attach.allowAttachSelf=true")
}
```

With Spring Boot (`./gradlew bootRun`), as in the worked example:

```kotlin
// build.gradle.kts
tasks.bootRun {
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Djdk.attach.allowAttachSelf=true")
}
```

The library coordinate is:

```kotlin
implementation("com.jh.coroutine-visualizer:coroutine-viz-client:0.2.1")
// the SDK uses kotlinx-coroutines at runtime only; your app (and the example above)
// needs it on the compile classpath too
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
```

**Requirements.**

| | Minimum | Why |
|---|---|---|
| Java | 17 | |
| Kotlin (your Kotlin Gradle plugin) | 2.2 | The SDK's HTTP client needs kotlin-stdlib 2.2 at runtime, and Spring Boot sets the stdlib to your Kotlin version. |
| kotlinx-coroutines | 1.10.2 | The SDK and its HTTP client are built against it. |

Spring Boot 4 meets both as it is. Spring Boot 3.x defaults to Kotlin 1.9.25 and pins
kotlinx-coroutines 1.8.1, so raise both in `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    // …
}

extra["kotlin-coroutines.version"] = "1.10.2"
```

With an older Kotlin compiler, the build fails because it cannot read the SDK's classes. If the
compiler is new enough but the app runs an older kotlin-stdlib or kotlinx-coroutines (the versions
Spring Boot pins win), `VizcoreClient.start` throws an `IllegalStateException` that names what to
change. That, and a `pollIntervalMillis` below 10, are the only cases in which `start` throws.

Each `v*` release publishes the SDK to this repository's GitHub Packages
(`https://maven.pkg.github.com/Kotlin-server-squad/vizcore`), which needs a GitHub token with
`read:packages` even for reads; see [SDK distribution](docs/guides/sdk-distribution.md). To try an
unreleased build instead, publish it to your local Maven repository and add `mavenLocal()` to your
`repositories`:

```bash
cd backend && ./gradlew :coroutine-viz-core:publishToMavenLocal :coroutine-viz-client:publishToMavenLocal
```

or depend on locally built jars — this is what the worked example does:

```bash
cd backend && ./gradlew :coroutine-viz-client:jar :coroutine-viz-core:jar
```

```kotlin
dependencies {
    implementation(
        files(
            "<vizcore>/backend/coroutine-viz-client/build/libs/coroutine-viz-client-0.2.1.jar",
            "<vizcore>/backend/coroutine-viz-core/build/libs/coroutine-viz-core-0.2.1.jar",
        ),
    )
    // flat-file jars carry no transitive metadata, so declare their runtime deps
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.ktor:ktor-client-core:3.3.2")
    implementation("io.ktor:ktor-client-cio:3.3.2")
    implementation("io.ktor:ktor-client-websockets:3.3.2")
    implementation("org.slf4j:slf4j-api:2.0.9")
}
```

A complete, runnable Spring Boot app wired this way lives in
[`examples/spring-vizcore-demo`](examples/spring-vizcore-demo).

DebugProbes attribution attaches creation-stack `file:line` information to the events that
leave your process. Treat the SDK as a **development tool** and do not point it at sensitive
production stacks.

## Verify your setup

The golden-path smoke test boots the backend fat jar in memory mode on its own port, drives
the real HTTP/WebSocket/SSE surface — exactly-once SSE delivery across a replay snapshot plus
live events, ingest across a client restart, concurrent reads, the read rate limiter — and
always takes the server back down again. It needs `python3` with
`pip install -r scripts/smoke/requirements.txt` (one pinned dependency, `websockets`):

```bash
cd backend && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew shadowJar && cd ..
scripts/smoke/run-smoke.sh "$(ls backend/build/libs/*-all.jar | head -1)"
```

It exits non-zero on any failed assertion and leaves no listener behind. `SMOKE_PORT`
(default `18080`) picks the port; a busy port is a hard failure rather than something the
script clears for you. The same `run-smoke.sh` runs in CI on pushes to `main` and on every
pull request against it.

To run the test suites directly:

```bash
cd backend && ./gradlew ktlintCheck detekt test   # Kotlin: ktlint + detekt + JUnit 5
cd frontend && pnpm test                          # TypeScript: Vitest
```

## Documentation

- [SETUP.md](SETUP.md) — full local setup, architecture overview, troubleshooting
- [CONTRIBUTING.md](CONTRIBUTING.md) — conventions, commit style, how to submit changes
- [docs/README.md](docs/README.md) — documentation index (guides, ADRs, topics)
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) — production deployment
- [CLAUDE.md](CLAUDE.md) — repository map and conventions for AI coding agents
- [CHANGELOG.md](CHANGELOG.md) — release history

## Licensing

This repository is dual-licensed by component; there is no single root license file.

- **Backend and SDK** (`backend/`, including `coroutine-viz-client`) — Apache License 2.0,
  see [backend/LICENSE](backend/LICENSE).
- **Frontend** (`frontend/`) — MIT License, see [frontend/LICENSE](frontend/LICENSE).
