# IntelliJ Plugin Native Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the plugin's embedded JCEF web view with a fully native IntelliJ tool window — a live coroutine tree + debugging inspector with jump-to-source — and delete the loopback/web-embedding stack.

**Architecture:** The plugin becomes a plain Kotlin consumer of the backend `/api`. A project `@Service` polls `/hierarchy` + `/metrics` (~200 ms), exposes an observable `SessionModel`, and drives a Swing `Tree` + inspector + metric tiles. Jump-to-source uses `OpenFileDescriptor`. The agent + run-config injection (Phase 13) are kept; loopback server, JCEF view, and SPA bundling are deleted.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (`com.intellij.ui.treeStructure.Tree`, UI DSL, `OpenFileDescriptor`, project `@Service`), `java.net.http`, `kotlinx.serialization` (already on the plugin classpath), JUnit 5 + a `com.sun.net.httpserver.HttpServer` stub for integration tests.

**Spec:** `docs/superpowers/specs/2026-06-30-intellij-plugin-native-redesign-design.md`

**Working branch:** `feat/intellij-plugin-native-redesign` (already created).

**Build note:** All Gradle runs need JDK 21 from `backend/`:
`export JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home` then `cd backend && ./gradlew :intellij-plugin:<task>`.

---

## File Structure

**New (package `com.jh.coroutinevisualizer`):**
- `api/VizcoreApiClient.kt` — `java.net.http` consumer: `resolve`, `hierarchy`, `metrics`, `timeline`
- `api/WireModels.kt` — `@Serializable` plugin-side wire DTOs (HierarchyNodeDto, MetricsDto, LeakDto, ResolveDto, TimelineDto…)
- `model/SessionModel.kt` — immutable observed state (tree nodes + metrics + leak ids)
- `model/CoroutineTreeModel.kt` — `DefaultTreeModel` builder/differ (preserves expansion+selection)
- `poll/SessionPollingService.kt` — project `@Service`, owns poll loop, exposes `SessionModel` via listener
- `toolwindow/CoroutineTreeRenderer.kt` — `ColoredTreeCellRenderer` (dot/name/badge/chip/age/child + flash + leak tint)
- `toolwindow/InspectorPanel.kt` — selected-coroutine detail (suspended-at / launched-at / timing)
- `toolwindow/MetricTilesPanel.kt` — header tiles
- `toolwindow/VizcoreToolWindowPanel.kt` — the split layout + states + wiring to the service
- `navigation/SourceNavigator.kt` — `file:line` → `OpenFileDescriptor`

**Modified:**
- `toolwindow/VizcoreToolWindowFactory.kt` — replace JCEF body with `VizcoreToolWindowPanel`
- `toolwindow/VizcoreLaunchState.kt` — drop `port` / `viewUrl()`; keep correlation halves
- `actions/RunWithVisualizerAction.kt` — drop loopback start/port arming; keep correlation + health-check + agent inject + open tool window
- `settings/VizcoreSettings.kt` + `VizcoreSettingsConfigurable.kt` — add `pollIntervalMs` (default 200)
- `build.gradle.kts` — remove the `pnpmBuild` + `/frontend` processResources wire (keep the `/agent` wire)
- `src/main/resources/META-INF/plugin.xml` — toolWindow factory unchanged id; no other change

**Deleted:**
- `server/LoopbackFrontendServer.kt`, `server/LoopbackServerService.kt`
- `toolwindow/VizcoreViewUrl.kt`
- `src/test/.../server/LoopbackFrontendServerTest.kt`, `.../toolwindow/VizcoreViewUrlTest.kt`, `.../toolwindow/JcefFallbackTest.kt`

---

## Task 1: Rebuild-by-deletion — strip the web-embedding stack, keep the build green

**Files:**
- Delete: `server/LoopbackFrontendServer.kt`, `server/LoopbackServerService.kt`, `toolwindow/VizcoreViewUrl.kt`, and the three tests above
- Modify: `toolwindow/VizcoreToolWindowFactory.kt`, `toolwindow/VizcoreLaunchState.kt`, `actions/RunWithVisualizerAction.kt`, `build.gradle.kts`

- [ ] **Step 1: Delete the files**

```bash
cd /Users/jirihermann/Documents/workspace-vizcore/vizcore
git rm intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/server/LoopbackFrontendServer.kt \
       intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/server/LoopbackServerService.kt \
       intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/toolwindow/VizcoreViewUrl.kt \
       intellij-plugin/src/test/kotlin/com/jh/coroutinevisualizer/server/LoopbackFrontendServerTest.kt \
       intellij-plugin/src/test/kotlin/com/jh/coroutinevisualizer/toolwindow/VizcoreViewUrlTest.kt \
       intellij-plugin/src/test/kotlin/com/jh/coroutinevisualizer/toolwindow/JcefFallbackTest.kt
```

- [ ] **Step 2: Simplify `VizcoreLaunchState.kt`** — remove `port`, `arm(port, correlation)`, `viewUrl()`, and `clear()`'s port handling. Keep the project-scoped `correlation` (with a no-arg `arm(correlation)`) and the per-config `armConfiguration`/`correlation`/`disarm` companion API unchanged.

```kotlin
@Service(Service.Level.PROJECT)
class VizcoreLaunchState {
    @Volatile
    var correlation: String? = null
        private set

    /** Arm the active correlation for the next tool-window open (called by the launch action). */
    fun arm(correlation: String) { this.correlation = correlation }

    /** Clear the armed correlation (e.g. when the user starts a new run). */
    fun clear() { this.correlation = null }

    companion object {
        fun getInstance(project: Project): VizcoreLaunchState = project.getService(VizcoreLaunchState::class.java)
        private val ARMED_CORRELATION: Key<String> = Key.create("vizcore.armed.correlation")
        fun armConfiguration(configuration: RunConfigurationBase<*>, correlation: String) =
            configuration.putUserData(ARMED_CORRELATION, correlation)
        fun isArmed(configuration: RunConfigurationBase<*>): Boolean = configuration.getUserData(ARMED_CORRELATION) != null
        fun correlation(configuration: RunConfigurationBase<*>): String? = configuration.getUserData(ARMED_CORRELATION)
        fun disarm(configuration: RunConfigurationBase<*>) = configuration.putUserData(ARMED_CORRELATION, null)
    }
}
```

- [ ] **Step 3: Simplify `RunWithVisualizerAction.kt`** — remove the `LoopbackServerService` import + the `ensureStarted(...)`/`arm(port, correlation)` lines. Replace those two lines (the CR-01 block) with:

```kotlin
        VizcoreLaunchState.armConfiguration(launch.configuration, correlation)
        VizcoreLaunchState.getInstance(launch.project).arm(correlation)
```
Also delete the now-unused `threadCorrelation`/`CorrelationThreading` test seam **only if** `CorrelationThreadingTest` is updated/removed in step 5; otherwise keep `threadCorrelation` but drop its `port`/`viewUrl` usage (simpler: keep the agent-arg half, drop the URL half — see step 5).

- [ ] **Step 4: Replace `VizcoreToolWindowFactory.kt` body with a temporary native placeholder** (full native panel arrives in Task 9):

```kotlin
package com.jh.coroutinevisualizer.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel

class VizcoreToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.add(JLabel("Coroutine Visualizer — native view (under construction)."))
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
```

- [ ] **Step 5: Fix `CorrelationThreadingTest`** — it asserted the agent-arg correlation equals the view-URL correlation. The view URL is gone. Reduce it to assert `buildAgentVmArgs` carries `corr=<uuid>` (drop the `VizcoreViewUrl` half). If that leaves the test trivial/duplicative of `VizcoreRunConfigurationExtensionTest`, delete `CorrelationThreadingTest` and the `threadCorrelation`/`CorrelationThreading` seam from the action.

- [ ] **Step 6: Remove the frontend packaging wire in `build.gradle.kts`** — delete the `pnpmBuild` task and the `from("../frontend/dist") { into("frontend") }` line; keep the `agentJar` → `/agent` wire and `dependsOn(agentJar)`.

```kotlin
tasks.named<ProcessResources>("processResources") {
    dependsOn(agentJar)
    from(agentJar) {
        into("agent")
        rename { "coroutine-viz-agent.jar" }
    }
}
```

- [ ] **Step 7: Build + test green**

Run: `cd backend && JAVA_HOME=<jdk21> ./gradlew :intellij-plugin:test :intellij-plugin:buildPlugin -q`
Expected: BUILD SUCCESSFUL; remaining tests pass (health, run-config extension, and the trimmed correlation test). Plugin zip builds (now ~13 MB smaller — no SPA).

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "refactor(plugin): delete web-embedding stack (loopback + JCEF + SPA bundle)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Wire models + `VizcoreApiClient`

**Files:**
- Create: `api/WireModels.kt`, `api/VizcoreApiClient.kt`
- Test: `src/test/.../api/VizcoreApiClientTest.kt`

- [ ] **Step 1: Write `WireModels.kt`** — `@Serializable` DTOs mirroring the backend wire (snake/camel as emitted by `appJson`; verify field names against `HierarchyNode`, `MetricsResponse`, `LeakDto`, `CoroutineTimeline`):

```kotlin
package com.jh.coroutinevisualizer.api

import kotlinx.serialization.Serializable

@Serializable data class ResolveDto(val sessionId: String)

@Serializable data class HierarchyNodeDto(
    val id: String, val parentId: String? = null, val children: List<String> = emptyList(),
    val name: String, val scopeId: String, val state: String,
    val createdAtNanos: Long = 0, val completedAtNanos: Long? = null,
    val dispatcherId: String? = null, val dispatcherName: String? = null,
    val currentThreadId: Long? = null, val currentThreadName: String? = null,
    val jobId: String = "", val exceptionType: String? = null, val exceptionMessage: String? = null,
    val activeChildrenIds: List<String> = emptyList(), val activeChildrenCount: Int = 0,
)

@Serializable data class LeakDto(val coroutineId: String, val label: String? = null, val aliveMs: Long)
@Serializable data class MetricsDto(
    val active: Int, val peak: Int, val throughputPerSec: Double = 0.0,
    val dispatcherUtilization: Map<String, Int> = emptyMap(),
    val leaks: List<LeakDto> = emptyList(), val leakThresholdMs: Long = 0,
)

@Serializable data class SuspensionPointDto(
    val function: String, val fileName: String? = null, val lineNumber: Int? = null, val reason: String,
)
@Serializable data class TimelineEventDto(
    val seq: Long, val tsNanos: Long, val kind: String, val threadName: String? = null,
    val dispatcherName: String? = null, val reason: String? = null, val suspensionPoint: SuspensionPointDto? = null,
)
@Serializable data class TimelineDto(
    val coroutineId: String, val name: String, val state: String,
    val totalDuration: Long? = null, val activeDuration: Long? = null, val suspendedDuration: Long? = null,
    val parentId: String? = null, val childrenIds: List<String> = emptyList(),
    val events: List<TimelineEventDto> = emptyList(),
)
```

- [ ] **Step 2: Write the failing test** (`VizcoreApiClientTest.kt`) — stand up a `com.sun.net.httpserver.HttpServer` stub and assert each call parses correctly and `resolve` returns null on 404:

```kotlin
package com.jh.coroutinevisualizer.api

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VizcoreApiClientTest {
    private var backend: HttpServer? = null
    private fun start(handler: (String) -> Pair<Int, String>): String {
        val b = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        b.createContext("/api") { ex ->
            val (code, body) = handler(ex.requestURI.toString())
            val bytes = body.toByteArray(); ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        b.start(); backend = b
        return "http://127.0.0.1:${b.address.port}"
    }
    @AfterEach fun tearDown() { backend?.stop(0) }

    @Test fun `resolve returns sessionId on 200`() {
        val url = start { 200 to """{"sessionId":"s-1"}""" }
        assertEquals("s-1", VizcoreApiClient(url).resolve("corr-x"))
    }
    @Test fun `resolve returns null on 404`() {
        val url = start { 404 to """{"error":"not found"}""" }
        assertNull(VizcoreApiClient(url).resolve("corr-x"))
    }
    @Test fun `hierarchy parses array`() {
        val url = start { 200 to """[{"id":"c1","name":"request-1","scopeId":"sc","state":"RUNNING","children":[],"jobId":"j1"}]""" }
        val nodes = VizcoreApiClient(url).hierarchy("s-1")
        assertEquals(1, nodes.size); assertEquals("request-1", nodes[0].name); assertEquals("RUNNING", nodes[0].state)
    }
    @Test fun `metrics parses leaks`() {
        val url = start { 200 to """{"active":2,"peak":5,"dispatcherUtilization":{"IO":1},"leaks":[{"coroutineId":"c9","aliveMs":12400}],"leakThresholdMs":10000}""" }
        val m = VizcoreApiClient(url).metrics("s-1")!!
        assertEquals(2, m.active); assertEquals("c9", m.leaks[0].coroutineId)
    }
}
```

- [ ] **Step 3: Run it — verify it fails** (`VizcoreApiClient` not defined).
Run: `cd backend && JAVA_HOME=<jdk21> ./gradlew :intellij-plugin:test --tests "*VizcoreApiClientTest" -q` → FAIL (unresolved reference).

- [ ] **Step 4: Implement `VizcoreApiClient.kt`**

```kotlin
package com.jh.coroutinevisualizer.api

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Native Kotlin consumer of the backend /api. The plugin no longer runs a browser, so it calls the
 * backend directly. [resolve] returns null on any non-200 (404 = not bound yet — keep polling), the
 * others throw on a transport error (the polling service catches and retries).
 */
class VizcoreApiClient(
    private val baseUrl: String,
    private val token: String = "",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun req(path: String): HttpRequest {
        val b = HttpRequest.newBuilder(URI("$baseUrl/api$path")).timeout(Duration.ofSeconds(5)).GET()
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        return b.build()
    }
    private fun send(path: String): HttpResponse<String> = http.send(req(path), HttpResponse.BodyHandlers.ofString())

    fun resolve(correlation: String): String? {
        val r = send("/sessions/resolve?correlation=${URI(null, null, correlation, null).rawPath}")
        return if (r.statusCode() == 200) json.decodeFromString<ResolveDto>(r.body()).sessionId else null
    }
    fun hierarchy(sessionId: String): List<HierarchyNodeDto> {
        val r = send("/sessions/$sessionId/hierarchy")
        return if (r.statusCode() == 200) json.decodeFromString(r.body()) else emptyList()
    }
    fun metrics(sessionId: String): MetricsDto? {
        val r = send("/sessions/$sessionId/metrics")
        return if (r.statusCode() == 200) json.decodeFromString(r.body()) else null
    }
    fun timeline(sessionId: String, coroutineId: String): TimelineDto? {
        val r = send("/sessions/$sessionId/coroutines/$coroutineId/timeline")
        return if (r.statusCode() == 200) json.decodeFromString(r.body()) else null
    }
}
```
> Note: correlation/sessionId go into the path — URL-encode them properly (the snippet uses a simple
> `rawPath` for the query; at impl time prefer `URLEncoder.encode(correlation, UTF_8)` for the query value).

- [ ] **Step 5: Run — verify pass.** Same command → 4 tests PASS.

- [ ] **Step 6: Commit** `feat(plugin): native VizcoreApiClient (/api consumer) + wire models`.

---

## Task 3: `CoroutineTreeModel` — build + diff preserving expansion/selection

**Files:** Create `model/CoroutineTreeModel.kt`; Test `src/test/.../model/CoroutineTreeModelTest.kt`

- [ ] **Step 1: Failing test** — assert (a) a flat `List<HierarchyNodeDto>` builds a correct parent/child `DefaultTreeModel`, and (b) applying an updated list reuses existing nodes (same `DefaultMutableTreeNode` identity for an unchanged id) so expansion/selection can be preserved, and updates a changed state in place.

```kotlin
@Test fun `builds parent child tree`() {
    val m = CoroutineTreeModel()
    m.apply(listOf(node("root", null, listOf("a")), node("a", "root", emptyList())))
    val root = m.treeModel.root as DefaultMutableTreeNode
    assertEquals(1, root.childCount)
    assertEquals("a", ((root.firstChild as DefaultMutableTreeNode).userObject as CoroutineRow).id)
}
@Test fun `diff reuses nodes and updates state in place`() {
    val m = CoroutineTreeModel()
    m.apply(listOf(node("a", null, state = "RUNNING")))
    val first = (m.treeModel.root as DefaultMutableTreeNode).firstChild
    m.apply(listOf(node("a", null, state = "SUSPENDED")))
    val second = (m.treeModel.root as DefaultMutableTreeNode).firstChild
    assertSame(first, second)  // same node object → JTree expansion/selection survive
    assertEquals("SUSPENDED", ((second as DefaultMutableTreeNode).userObject as CoroutineRow).state)
}
```
(`node(...)` = a small test factory building `HierarchyNodeDto`. `CoroutineRow` = a small immutable view-model: id, name, state, dispatcherName, reason?, ageMs, childCount, isLeak.)

- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** `CoroutineTreeModel` over a hidden root `DefaultTreeModel`, keyed by a `MutableMap<String, DefaultMutableTreeNode>`; on `apply(nodes, leakIds)`: upsert rows (reuse node by id, replace `userObject`), re-parent if `parentId` changed, remove ids absent from the new set, fire the minimal `DefaultTreeModel` structure/nodesChanged events. Mark `isLeak = id in leakIds`. Derive `ageMs` from `createdAtNanos` vs now.
- [ ] **Step 4: Run — pass.**
- [ ] **Step 5: Commit** `feat(plugin): CoroutineTreeModel with identity-preserving diff`.

---

## Task 4: `SessionModel` + leak/metric mapping

**Files:** Create `model/SessionModel.kt`; Test `src/test/.../model/SessionModelTest.kt`

- [ ] **Step 1: Failing test** — `SessionModel.from(hierarchy, metrics)` produces: tree rows, a leak id set (= `metrics.leaks.map { coroutineId }`), and tile counts (total, active = count of RUNNING, suspended = count of SUSPENDED, leakRisk = leaks.size, dispatchers = `metrics.dispatcherUtilization.size`). Assert a coroutine whose id is in `leaks` has `isLeak = true` and the right `aliveMs` suffix value.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** `SessionModel` (immutable data class) + `from(...)` pure mapper. No backend leak recomputation — consume `metrics.leaks` verbatim.
- [ ] **Step 4: Run — pass.**
- [ ] **Step 5: Commit** `feat(plugin): SessionModel mapping (hierarchy + server metrics/leaks)`.

---

## Task 5: `SessionPollingService`

**Files:** Create `poll/SessionPollingService.kt`; Test `src/test/.../poll/SessionPollingServiceTest.kt`

- [ ] **Step 1: Failing test (stub backend)** — start an `HttpServer` serving `/api/sessions/resolve` (→ sessionId after N polls), `/hierarchy`, `/metrics`. Start the service pointed at it with a short interval, register a listener, assert the listener receives a `SessionModel` with the expected rows within a timeout; assert `freeze()` stops further updates and `dispose()` cancels cleanly.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** as a `@Service(Service.Level.PROJECT)`, `Disposable`. Use a single-thread `ScheduledExecutorService` (NOT GlobalScope). `start(correlation)`: poll `resolve` until a sessionId, then poll `hierarchy`+`metrics` every `pollIntervalMs`, build `SessionModel.from(...)`, notify listeners on the EDT (`ApplicationManager.getApplication().invokeLater`). Catch transport errors → keep last model, set a `reconnecting` flag. `freeze()/unfreeze()`, `dispose()` shuts down the executor. Read interval + backendUrl from `VizcoreSettings.getInstance()` (application-level — no `project` arg). For the auth token use `VizcoreRunConfigurationExtension.AGENT_TOKEN` (currently `""`); `VizcoreSettings` has **no** token field, so do not read one from it.
- [ ] **Step 4: Run — pass.**
- [ ] **Step 5: Commit** `feat(plugin): SessionPollingService (poll /hierarchy + /metrics, observable model)`.

---

## Task 6: `SourceNavigator` (jump-to-source)

**Files:** Create `navigation/SourceNavigator.kt`; Test `src/test/.../navigation/SourceNavigatorTest.kt` (light platform test or pure resolution test)

- [ ] **Step 1: Failing test** — for the pure resolution logic: given a `fileName` + optional `className` and a fake file-index lookup, returns the best `VirtualFile` candidate or null (ambiguous-but-className-disambiguated → the right one; unresolved → null). Keep the IntelliJ `OpenFileDescriptor` call behind a thin seam so the logic is testable without a full fixture.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** `SourceNavigator(project)`: `resolve(fileName, className?, line)` → `VirtualFile?` via `FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))`, disambiguate by `className`/path when >1; `navigate(file, line)` → `OpenFileDescriptor(project, file, line-1, 0).navigate(true)`. Public `jumpTo(fileName, className?, line)` = resolve then navigate; returns false (caller renders plain text) when unresolved.
- [ ] **Step 4: Run — pass.**
- [ ] **Step 5: Commit** `feat(plugin): SourceNavigator jump-to-source (OpenFileDescriptor)`.

---

## Task 7: `CoroutineTreeRenderer` (+ state-change flash)

**Files:** Create `toolwindow/CoroutineTreeRenderer.kt`; Test `src/test/.../toolwindow/CoroutineTreeRendererTest.kt` (light — assert state→color/badge mapping is pure)

- [ ] **Step 1: Failing test** — a pure `stateColor(state)` / `badgeText(state)` / `dispatcherChip(row)` returns expected `JBColor`/strings for RUNNING/SUSPENDED/COMPLETED/FAILED/CREATED; leak row → amber + "⚠ ~Xs".
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** `CoroutineTreeRenderer : ColoredTreeCellRenderer`, drawing dot + name + state badge + `dispatcher · reason` chip + `~age` + child count, using `JBColor` per the vizcore palette (blue/amber/green/red/gray; leak=amber). Flash: a `FlashTracker` keyed by id remembers the previous state; when a row's state changed since last render, paint a fading background for ~1.2 s via a `javax.swing.Timer` that `repaint()`s the tree. Keep the color/text mapping in pure functions (tested in step 1).
- [ ] **Step 4: Run — pass.**
- [ ] **Step 5: Commit** `feat(plugin): coroutine tree renderer with state-change flash`.

---

## Task 8: `MetricTilesPanel` + `InspectorPanel`

**Files:** Create `toolwindow/MetricTilesPanel.kt`, `toolwindow/InspectorPanel.kt`; Test the pure formatting (e.g. `formatDuration(nanos)` → "~340ms")

- [ ] **Step 1: Failing test** — `formatApproxDuration(nanos)` → "~Xms"/"~X.Xs"; `InspectorViewModel.from(timeline, hierarchyNode)` extracts suspended-at (reason, file:line from the latest `suspensionPoint`), launched-at (creation file:line — from `timeline` first CREATED event's suspensionPoint or hierarchy), and active/suspended/total durations.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** `MetricTilesPanel(model)` (5 tiles, leak tile amber when >0) and `InspectorPanel` (IntelliJ UI DSL): header (name/state/job·scope·dispatcher), "Suspended at" card (reason pill + `file:line` + **Jump** button → `SourceNavigator.jumpTo(...)`, falls back to plain label when unresolved), "Launched at" card (+ Jump), Timing card (active/suspended/total + a simple proportional bar). `InspectorViewModel` pure mapping tested in step 1.
- [ ] **Step 4: Run — pass.**
- [ ] **Step 5: Commit** `feat(plugin): metric tiles + coroutine inspector panel`.

---

## Task 9: `VizcoreToolWindowPanel` + wire the native factory + states

**Files:** Create `toolwindow/VizcoreToolWindowPanel.kt`; Modify `toolwindow/VizcoreToolWindowFactory.kt`; Test `src/test/.../toolwindow/VizcoreToolWindowFactoryTest.kt` (light platform test for the three states)

- [ ] **Step 1: Failing test** — with no armed correlation, the panel shows the "No run yet" state; with an armed correlation but unresolved session, "Connecting…"; with a backend-down health check, "Backend not reachable". (Test the pure `stateFor(...)` selector if a full fixture is heavy.)
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** `VizcoreToolWindowPanel(project)`: a `JBSplitter` (horizontal) — left `Tree` (model from `CoroutineTreeModel`, renderer from Task 7), right `InspectorPanel`; top `MetricTilesPanel`; a toolbar (LIVE pill, poll indicator, filter, Active-only, Group-by-dispatcher, Freeze). On create: read `VizcoreLaunchState.correlation`; if null → "No run yet"; else `SessionPollingService.start(correlation)` and subscribe — update tree model + tiles on each `SessionModel`; show "Connecting…" until first model, "Backend not reachable" on health failure. Tree selection → fetch `timeline` (via the service/client) → `InspectorPanel.show(...)`. Register the panel/listeners with `toolWindow.disposable`. Replace the Task-1 placeholder factory body to instantiate this panel.
- [ ] **Step 4: Run — pass** (`test`), and **manual**: `./gradlew :intellij-plugin:runIde`, run the continuous demo, confirm the tree fills + flashes + inspector + jump-to-source work.
- [ ] **Step 5: Commit** `feat(plugin): native tool window (tree + inspector + tiles + states)`.

---

## Task 10: Settings — poll interval

**Files:** Modify `settings/VizcoreSettings.kt`, `settings/VizcoreSettingsConfigurable.kt`; Test extend `BackendHealthCheckTest`/a new settings test

- [ ] **Step 1: Failing test** — `VizcoreSettings` persists `pollIntervalMs` (default 200, clamped to a sane min e.g. 50).
- [ ] **Step 2–4: Implement + pass** — add the field to the `PersistentStateComponent` state + a spinner in the configurable. `SessionPollingService` reads it.
- [ ] **Step 5: Commit** `feat(plugin): configurable poll interval setting`.

---

## Task 11: Final gates + UAT

- [ ] **Step 1: Quality gates**
Run: `cd backend && JAVA_HOME=<jdk21> ./gradlew :intellij-plugin:detekt :intellij-plugin:ktlintCheck :intellij-plugin:verifyPlugin :intellij-plugin:test :intellij-plugin:buildPlugin -q`
Expected: all green; `verifyPlugin` Compatible; zip builds.
- [ ] **Step 2: Live UAT** — `runIde`, open `examples/spring-vizcore-demo`, set backend URL, "Run with Coroutine Visualizer" (use `--vizcore.continuous=true` for steady motion, OR a non-instrumented target to avoid the self-instrumenting double-client), open the tool window → tree fills live, flashes on change, inspector shows suspended-at/launched-at, Jump opens the editor at the right line, leaks flagged amber.
- [ ] **Step 3: Commit** any fixes; update `docs/superpowers/specs/...` "Open questions" with the chosen poll cadence.

---

## Task 12 (fast-follow, SPIKE-GATED): editor gutter markers

> Do NOT start until a spike confirms feasibility. The spike: in a `runIde`, for an open editor whose file matches a live coroutine source location, add a `RangeHighlighter` with a `GutterIconRenderer` via the editor `MarkupModel`, updated from the poller, click → select in tree. Timebox ~half a day. If the spike is clean, plan the real tasks; if not, defer and document.

- [ ] **Spike step:** prove a single gutter icon can be added/removed on the active editor from the polling callback and that clicking it can call back into the tool window. Record findings in `docs/superpowers/specs/...`.
- [ ] If green: add `navigation/CoroutineGutterService.kt` (per-editor markup sync from `SessionModel`, ▶ launch / ⏸ suspend, hover list, click→select) with tests for the pure location-matching logic.

---

## Notes for the executor
- All Gradle from `backend/` with JDK 21. `gradlew` resolves the build from CWD.
- A composite task may regenerate `backend/docs/index.html` — `git checkout -- backend/docs/index.html` before commits.
- Never `GlobalScope`; all background work cancels on dispose (CLAUDE.md).
- Follow the existing plugin test style (JUnit 5, `com.sun.net.httpserver` stub) — currently the suite is the calibration bar for "done."
- Keep commits focused and conventional (`feat:`/`refactor:`/`test:`).
