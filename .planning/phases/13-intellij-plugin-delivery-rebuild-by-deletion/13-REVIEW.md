---
phase: 13-intellij-plugin-delivery-rebuild-by-deletion
reviewed: 2026-06-29T00:00:00Z
depth: standard
files_reviewed: 26
files_reviewed_list:
  - backend/coroutine-viz-agent/build.gradle.kts
  - backend/coroutine-viz-agent/src/main/kotlin/com/jh/proj/coroutineviz/agent/VizcoreAgent.kt
  - backend/coroutine-viz-agent/src/test/kotlin/com/jh/proj/coroutineviz/agent/VizcoreAgentArgsTest.kt
  - backend/settings.gradle.kts
  - docs/guides/intellij-plugin-distribution.md
  - frontend/src/routes/index.test.tsx
  - frontend/src/routes/index.tsx
  - intellij-plugin/build.gradle.kts
  - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/actions/RunWithVisualizerAction.kt
  - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/agent/AgentJarExtractor.kt
  - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/health/BackendHealthCheck.kt
  - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/run/VizcoreRunConfigurationExtension.kt
  - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/server/LoopbackFrontendServer.kt
  - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/settings/VizcoreSettings.kt
  - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/settings/VizcoreSettingsConfigurable.kt
  - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/toolwindow/VizcoreLaunchState.kt
  - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/toolwindow/VizcoreToolWindowFactory.kt
  - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/toolwindow/VizcoreViewUrl.kt
  - intellij-plugin/src/main/resources/META-INF/plugin.xml
  - intellij-plugin/src/test/kotlin/com/jh/coroutinevisualizer/health/BackendHealthCheckTest.kt
  - intellij-plugin/src/test/kotlin/com/jh/coroutinevisualizer/run/CorrelationThreadingTest.kt
  - intellij-plugin/src/test/kotlin/com/jh/coroutinevisualizer/run/VizcoreRunConfigurationExtensionTest.kt
  - intellij-plugin/src/test/kotlin/com/jh/coroutinevisualizer/server/LoopbackFrontendServerTest.kt
  - intellij-plugin/src/test/kotlin/com/jh/coroutinevisualizer/toolwindow/JcefFallbackTest.kt
  - intellij-plugin/src/test/kotlin/com/jh/coroutinevisualizer/toolwindow/VizcoreViewUrlTest.kt
findings:
  critical: 3
  warning: 6
  info: 4
  total: 13
status: issues_found
---

# Phase 13: Code Review Report

**Reviewed:** 2026-06-29
**Depth:** standard
**Files Reviewed:** 26
**Status:** issues_found

## Summary

This phase rebuilt the IntelliJ plugin as an agent-attach delivery vehicle: a javaagent
fat-jar (`coroutine-viz-agent`), a loopback `/api` reverse-proxy + SPA server, a backend
health check with an SSRF guard, a JCEF tool window with a Swing fallback, a
run-configuration extension that injects `-javaagent` VM args, and a `?correlation=`
deep-link in the SPA root.

The SSRF guard, VM-arg injection ordering, build-time secret handling (env/property-only
signing keys), and the correlation-threading identity are all sound and well-tested. The
unit tests, however, exercise each unit in isolation and **mask three integration-level
defects that make the headline feature (the embedded live view) non-functional**:

1. The loopback server is never started anywhere in production code, and its bound port is
   never threaded into the launch state — so the tool window always builds a `:0` URL.
2. The `/api` proxy fully buffers the upstream response (`readBytes()`), which deadlocks on
   the SSE stream the SPA depends on for live events.
3. The static handler emits no `Content-Type`, so the SPA's ES-module scripts are refused
   by the browser; it is also vulnerable to classpath path traversal out of `/frontend`.

There are no committed secrets and no command/SQL injection. The defects are
correctness/wiring failures concentrated in `LoopbackFrontendServer` and its (missing)
lifecycle owner.

## Critical Issues

### CR-01: Loopback server is never started; tool-window view URL is always `http://127.0.0.1:0/...`

**File:** `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/server/LoopbackFrontendServer.kt` (whole class), `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/actions/RunWithVisualizerAction.kt:62,126`, `intellij-plugin/src/main/resources/META-INF/plugin.xml`

**Issue:** `LoopbackFrontendServer` is instantiated and `.start()`ed nowhere in production
code (grep of `intellij-plugin/src/main` finds only its own definition, the tool-window doc
comment, and the `VizcoreViewUrl` doc comment — no construction, no startup activity, no
service registration in `plugin.xml`). Consequently `VizcoreLaunchState.port` is never set
to a real bound port. The action arms the view coordinates with
`arm(loopbackPort(project), correlation)`, where `loopbackPort` reads
`VizcoreLaunchState.getInstance(project).port ?: LOOPBACK_PORT_PENDING` — but `port` is
still `null` at that moment (nothing ever set it), so it always resolves to
`LOOPBACK_PORT_PENDING = 0`. `VizcoreViewUrl.build` then produces
`http://127.0.0.1:0/?correlation=<uuid>`. Port 0 is not a connectable target, so both the
JCEF browser and the Swing-fallback "Open in browser" button point at a dead URL. The
embedded live view — the entire point of the delivery vehicle — cannot load. The isolated
unit tests pass because `LoopbackFrontendServerTest` starts its own instance and
`VizcoreViewUrlTest` feeds a literal port; the missing production wiring is never exercised.

**Fix:** Register a project-level service (or a `ProjectActivity` / tool-window lifecycle
owner) that constructs `LoopbackFrontendServer(settings.backendUrl)`, calls `start()`, and
writes the bound `server.port` into `VizcoreLaunchState` before the tool window builds its
URL. Order the action so the server is bound first, e.g.:
```kotlin
val server = project.getService(LoopbackServerService::class.java).ensureStarted()
VizcoreLaunchState.getInstance(project).arm(server.port, correlation)
```
Register the server as a `Disposable` child of the project so it stops on close.

### CR-02: `/api` proxy buffers the whole response body — deadlocks the SSE live stream

**File:** `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/server/LoopbackFrontendServer.kt:79-83`

**Issue:** `handleApiProxy` reads the upstream response with
`bodyStream?.use { it.readBytes() }` and only then calls `sendResponseHeaders` /
writes the body. The SPA consumes live events via a browser `EventSource` against
`/api/sessions/<id>/stream` (`frontend/src/lib/api-client.ts:184-188`, base `/api` at line
27), which when the SPA is served from this loopback server is reverse-proxied through this
exact method. An SSE response is an open-ended `text/event-stream` with no `Content-Length`;
`readBytes()` blocks until EOF, which never comes, so the proxy hangs and the client
receives nothing. The headline "live React view auto-navigating over SSE" never streams a
single event. Additionally, `sendResponseHeaders(status, body.size)` sends a fixed
`Content-Length`, which is incompatible with streaming even if the buffering were removed.
The proxy also never copies upstream response headers back (see WR-01), so even non-SSE
responses lose their `Content-Type`.

**Fix:** Stream the body instead of buffering, and use chunked output (length `0` in
`sendResponseHeaders` for an unknown length):
```kotlin
val status = connection.responseCode
// copy upstream response headers back (Content-Type, Cache-Control, etc.)
connection.headerFields.forEach { (name, values) ->
    if (name != null) values.forEach { exchange.responseHeaders.add(name, it) }
}
val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
exchange.sendResponseHeaders(status, 0L) // 0 => chunked, no fixed Content-Length
exchange.responseBody.use { out -> stream?.use { it.copyTo(out) } }
```
Verify a live SSE session streams end-to-end through the proxy as part of acceptance.

### CR-03: Static handler is path-traversable out of `/frontend` and serves scripts with no MIME type

**File:** `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/server/LoopbackFrontendServer.kt:131-145`

**Issue:** Two defects in one handler.
(a) **Path traversal:** `resourceLoader("$FRONTEND_ROOT$path")` builds a classpath path
directly from `exchange.requestURI.path` with no normalization or prefix containment check.
A request such as `GET /../agent/coroutine-viz-agent.jar` yields the resource path
`/frontend/../agent/coroutine-viz-agent.jar`, which `ClassLoader.getResourceAsStream`
normalizes to `/agent/coroutine-viz-agent.jar` — serving the bundled agent jar (and, more
generally, any classpath resource outside `/frontend`). The server is loopback-only, but any
local process / any page able to issue a request to the ephemeral port can read arbitrary
bundle resources. The SPA-fallback (`?: resourceLoader(".../index.html")`) further masks
misses, so traversal failures are silent.
(b) **Missing Content-Type:** the handler never sets a `Content-Type` header. Vite emits the
SPA entry as `<script type="module">`; browsers enforce strict MIME checking for module
scripts and **refuse to execute** a module served without a JavaScript MIME type
(`text/javascript`). Even if CR-01/CR-02 are fixed, the SPA will fail to boot in the JCEF
view because `assets/*.js` arrives with no/incorrect content type.

**Fix:** Normalize and contain the path, then set a content type:
```kotlin
val raw = exchange.requestURI.path
val rel = if (raw == "/" || raw.isEmpty()) "/index.html" else raw
// reject traversal: resolve against a fixed root and verify containment
val normalized = URI(rel).normalize().path
if (normalized.contains("..")) { respondPlain(exchange, 400, "bad path"); return }
val bytes = resourceLoader("$FRONTEND_ROOT$normalized") ?: resourceLoader("$FRONTEND_ROOT/index.html")
...
exchange.responseHeaders.add("Content-Type", contentTypeFor(normalized)) // .js -> text/javascript, .css -> text/css, .html -> text/html
```

## Warnings

### WR-01: Proxy drops all upstream response headers (status/body only)

**File:** `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/server/LoopbackFrontendServer.kt:79-83`

**Issue:** `handleApiProxy` forwards only the status code and body. Upstream
`Content-Type`, `Content-Encoding`, `Cache-Control`, `Set-Cookie`, `Location`, etc. are
never copied to the client. JSON API responses reach the SPA with no `Content-Type`, and any
redirect (`instanceFollowRedirects = false`) loses its `Location`. This degrades or breaks
clients that branch on content type.

**Fix:** Copy `connection.headerFields` onto `exchange.responseHeaders` before
`sendResponseHeaders` (skipping hop-by-hop headers such as `Transfer-Encoding`,
`Connection`, and the auto-managed `Content-Length`). See CR-02 fix snippet.

### WR-02: Launch state is never cleared — stale correlation/port leaks across launches

**File:** `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/toolwindow/VizcoreLaunchState.kt:48-52`, `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/actions/RunWithVisualizerAction.kt`

**Issue:** `VizcoreLaunchState.clear()` exists but is called by no production code (grep
finds no caller). The project-scoped `port`/`correlation` are overwritten only on the next
"Run with Visualizer". After a launched process ends, re-opening the tool window still builds
a deep link to the previous run's correlation, auto-navigating the live view to a stale
session. The doc comment claims "when the launched process ends" clears it, but no process-end
listener is wired.

**Fix:** Register a `ProcessListener` (via the run extension's `attachToProcess`, or an
`ExecutionListener`) that calls `VizcoreLaunchState.getInstance(project).clear()` on
`processTerminated`, so the tool window reverts to the "not launched" panel.

### WR-03: `instanceFollowRedirects = false` on the proxy silently breaks backend 3xx

**File:** `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/server/LoopbackFrontendServer.kt:101`

**Issue:** Redirects are disabled on the upstream connection and (per WR-01) the `Location`
header is dropped. If the configured backend ever answers a 301/302/307 (auth redirect,
trailing-slash normalization, HTTPS upgrade), the proxy returns a bodyless 3xx with no
`Location`, and the SPA cannot follow it. Combined with the buffering in CR-02 this is a
quiet failure mode.

**Fix:** Either forward the `Location` header (and let the browser follow), or document and
test that the backend never redirects under `/api`. At minimum copy `Location` through.

### WR-04: `pnpmBuild` Exec task is non-hermetic and unconditional

**File:** `intellij-plugin/build.gradle.kts:116-128`

**Issue:** `processResources` always depends on `pnpmBuild`, which shells out to
`pnpm build` with no input/output declarations. It is not cacheable or up-to-date-checked
(runs every build), assumes `pnpm` is on `PATH`, and fails opaquely if the frontend deps are
not installed (the runbook's `pnpm install --frozen-lockfile` hint is manual). A missing or
mismatched `pnpm` makes `buildPlugin` non-reproducible across machines/CI.

**Fix:** Declare `inputs.dir("../frontend/src")` + lockfile and
`outputs.dir("../frontend/dist")` so Gradle can skip when unchanged, and fail with an
actionable message if `pnpm` is absent. Consider gating on a property for offline builds.

### WR-05: `apply()` silently rewrites a blank/invalid backend URL to the default; no validation surfaced

**File:** `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/settings/VizcoreSettingsConfigurable.kt:46-50`

**Issue:** The settings page accepts any text. `apply()` only coerces empty → default; it
never runs `BackendHealthCheck.validate`. A user can save `ftp://x` or `not a url`, and the
`Configurable` reports success. The bad value is only rejected much later inside
`BackendHealthCheck.check` (returning `Down` with the validation reason), surfacing as a
confusing "backend down" warning at launch rather than a settings-time error.

**Fix:** In `apply()` (or implement `Configurable` validation), call
`BackendHealthCheck.validate(text)` and throw `ConfigurationException(reason)` on
`Invalid`, so the user gets immediate, located feedback.

### WR-06: `RunConfigurationExtension.updateJavaParameters` disarm races the action's failure-path disarm

**File:** `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/run/VizcoreRunConfigurationExtension.kt:41,58`, `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/actions/RunWithVisualizerAction.kt:99-113`

**Issue:** The armed correlation is stored as user-data on the shared
`RunConfigurationBase` and consumed (`disarm`) inside `updateJavaParameters`. If the user (or
another tool) triggers a plain Run of the same configuration between `armConfiguration` and
the executor invoking `updateJavaParameters`, the agent is injected into that unrelated run.
The arm/disarm is a one-shot flag on a long-lived shared object with no run-instance scoping,
so concurrent or interleaved runs of the same config can mis-attribute the agent.

**Fix:** Scope the armed state to the specific `ExecutionEnvironment`/run instance rather
than the persistent configuration (e.g. key on the environment's `executionId` /
`RunnerSettings`), or arm-and-immediately-execute under a lock so no foreign run can observe
the armed flag.

## Info

### IN-01: `validate` uses `URI(value)` which under-rejects some malformed inputs

**File:** `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/health/BackendHealthCheck.kt:48-68`

**Issue:** `java.net.URI` is lenient (it accepts many strings that
`URI(...).toURL()` later rejects), and the scheme/host checks run on whatever it parsed. The
guard is adequate for the SSRF objective (scheme allow-list + host required), but relying on
`URI` parsing for validation is brittle. Consider `URI(value).toURL()` round-tripping in
`validate` so an unconstructable URL is rejected at validation time, not at probe time.

### IN-02: `index.tsx` `useEffect` omits `navigate` from deps; relies on assumed stability

**File:** `frontend/src/routes/index.tsx:80-86`

**Issue:** The effect uses `navigate` but lists only `[data]`. The comment asserts
`navigate` is stable; if TanStack ever returns a non-stable `navigate`, the one-shot guard
still protects against double-navigation, but `react-hooks/exhaustive-deps` will flag this.
Low risk given the guard. Add `navigate` to deps (the `resolvedRef` guard already prevents
re-fire) or document the lint suppression.

### IN-03: `validateSearch` returns the un-trimmed correlation while gating on a trimmed length

**File:** `frontend/src/routes/index.tsx:27-33`

**Issue:** The presence check uses `search.correlation.trim().length > 0` but the returned
value is the raw `search.correlation` (un-trimmed). A `?correlation=%20uuid%20` would pass
the gate and be threaded with surrounding whitespace into the resolve query key and request.
Harmless for real UUIDs, but inconsistent. Return the trimmed value.

### IN-04: Distribution runbook recommends an unauthenticated, sniffed install path without caveat

**File:** `docs/guides/intellij-plugin-distribution.md:75-77,94-98`

**Issue:** The runbook is accurate about env-only signing keys (good), but instructs
installing the *unsigned* zip via "Install from Disk" as the routine local path without
noting that unsigned plugins bypass Marketplace integrity checks. Given the plugin injects a
`-javaagent` into arbitrary JVMs, a one-line note that the unsigned-install path is for
trusted local builds only would be prudent. Documentation-only.

---

_Reviewed: 2026-06-29_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
