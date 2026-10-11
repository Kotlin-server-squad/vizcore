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
Every coroutine in that JVM is captured; name them with `CoroutineName` and the labels show
up in the UI.

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
    println("streaming into session ${client.currentSessionId}")
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

The library coordinate is:

```kotlin
implementation("com.jh.coroutine-visualizer:coroutine-viz-client:0.1.0")
```

Publishing is still manual, so until a release lands in a repository you can reach, depend on
locally built jars — this is what the worked example does:

```bash
cd backend && ./gradlew :coroutine-viz-client:jar :coroutine-viz-core:jar
```

```kotlin
dependencies {
    implementation(
        files(
            "<vizcore>/backend/coroutine-viz-client/build/libs/coroutine-viz-client-0.1.0.jar",
            "<vizcore>/backend/coroutine-viz-core/build/libs/coroutine-viz-core-0.1.0.jar",
        ),
    )
    // flat-file jars carry no transitive metadata, so declare their runtime deps
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
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
