---
phase: 13-intellij-plugin-delivery-rebuild-by-deletion
plan: 06
subsystem: infra
tags: [intellij-plugin, javaagent, run-configuration-extension, correlation, jcef, debugprobes]

# Dependency graph
requires:
  - phase: 13-01
    provides: coroutine-viz-agent fat-jar (premain VizcoreClient.start) — the -javaagent target
  - phase: 13-04
    provides: VizcoreSettings (backend URL) + BackendHealthCheck (pre-launch probe, D-05)
  - phase: 13-05
    provides: VizcoreLaunchState (project-scoped view coords), VizcoreViewUrl, VizcoreToolWindowFactory
provides:
  - "Run with Coroutine Visualizer launch path (IDE-01): armed RunConfigurationExtension injects -javaagent + -XX:+EnableDynamicAgentLoading into the target VM"
  - "ONE correlation UUID threaded byte-identically into the agent corr= arg AND the tool-window ?correlation= URL (IDE-03)"
  - "AgentJarExtractor: classpath agent jar → space-free temp file (Pitfall 6)"
affects: [13-07-build-wire, intellij-plugin-uat, frontend-correlation-route]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "RunConfigurationExtension.updateJavaParameters VM-arg patcher gated on per-config armed user-data (one-shot)"
    - "Pure internal builder/threading seams (buildAgentVmArgs, threadCorrelation) so launch logic is headless-testable without a run executor or display (D-13)"
    - "Single minted UUID armed into BOTH halves of VizcoreLaunchState (per-config + project-scoped) for agent↔view correlation identity"

key-files:
  created:
    - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/agent/AgentJarExtractor.kt
    - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/run/VizcoreRunConfigurationExtension.kt
    - intellij-plugin/src/test/kotlin/com/jh/coroutinevisualizer/run/VizcoreRunConfigurationExtensionTest.kt
    - intellij-plugin/src/test/kotlin/com/jh/coroutinevisualizer/run/CorrelationThreadingTest.kt
  modified:
    - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/actions/RunWithVisualizerAction.kt
    - intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/toolwindow/VizcoreLaunchState.kt
    - intellij-plugin/src/main/resources/META-INF/plugin.xml
    - intellij-plugin/build.gradle.kts

key-decisions:
  - "Inject -XX:+EnableDynamicAgentLoading into the TARGET app VM (not the IDE) — DebugProbes' byte-buddy dynamic attach is warned/blocked on JDK 21+ without it (T-13-13 / Pitfall 1)"
  - "AgentJarExtractor copies to Files.createTempFile (java.io.tmpdir, space-free) to dodge the IDE-config-with-spaces javaagent bug on macOS (T-13-14 / Pitfall 6)"
  - "Pre-launch health-check returns WITHOUT launching when the backend is Down — avoids infinite client retry against a dead backend (T-13-16 / D-05)"
  - "One minted UUID armed into both VizcoreLaunchState.armConfiguration (per-config, read by the extension) and arm(port, corr) (project-scoped, read by the tool window) so the agent corr= and view ?correlation= cannot drift (T-13-15 / IDE-03)"
  - "actionPerformed / resolveLaunch kept within detekt's ReturnCount budget by folding all pre-flight guards into a single resolveLaunch returning a LaunchTarget?"

patterns-established:
  - "Armed one-shot run-config patcher: extension early-returns unless VizcoreLaunchState.correlation(config) != null, then disarms after injecting (agent attaches for exactly one launch; user config never permanently modified)"
  - "Headless correlation-identity proof: threadCorrelation(uuid,...) returns the agent VM-arg AND the view URL from one UUID; the test extracts and compares the two correlation substrings"

requirements-completed: [IDE-01, IDE-03]

# Metrics
duration: 18min
completed: 2026-06-29
---

# Phase 13 Plan 06: Run-with-Visualizer Launch Path Summary

**"Run with Coroutine Visualizer" mints one correlation UUID, health-checks the backend, arms a one-shot RunConfigurationExtension that injects `-javaagent:<extracted>=...,corr=<uuid>` + `-XX:+EnableDynamicAgentLoading` into the target VM, and opens the tool window with that SAME UUID — agent↔view correlation identity proven headlessly (IDE-01 + IDE-03).**

## Performance

- **Duration:** ~18 min (Task 2 this run; Task 1 previously merged)
- **Completed:** 2026-06-29
- **Tasks:** 2/2 (Task 1 previously committed + merged; Task 2 this run)
- **Files:** 8 touched across both tasks (4 created, 4 modified)

## Accomplishments

### Task 1 — VM-arg injection (previously committed + merged: `f2451db`, merge `2161266`)

- **`AgentJarExtractor.kt`** — lazily copies the bundled classpath resource `/agent/coroutine-viz-agent.jar` to a `Files.createTempFile("coroutine-viz-agent", ".jar")` (space-free `java.io.tmpdir`, `REPLACE_EXISTING`, `deleteOnExit()`), exposing `path(): String`. Space-free path dodges the macOS `Application Support` whitespace javaagent bug (T-13-14 / Pitfall 6).
- **`VizcoreRunConfigurationExtension.kt`** — `RunConfigurationExtension` with `isApplicableFor = true` and `updateJavaParameters` that early-returns unless the config was armed (`VizcoreLaunchState.correlation(config) != null`). When armed it injects, at the front of the VM-args list, `-javaagent:$agent=app=<name>,backend=<url>,token=<token>,corr=<uuid>` then `-XX:+EnableDynamicAgentLoading`, and then **disarms** the config (one-shot — a later plain run is not patched). The arg string is built by the pure `internal buildAgentVmArgs(...)` for headless assertion.
- **`VizcoreLaunchState.kt`** — reconciled to exactly ONE class carrying BOTH halves of the handoff: project-scoped `(port, correlation)` view coords (`arm`/`viewUrl`) read by the tool window, and per-config armed user-data (`armConfiguration`/`isArmed`/`correlation`/`disarm`) read by the extension.
- **`plugin.xml`** — registers `<runConfigurationExtension implementation="...VizcoreRunConfigurationExtension"/>`.
- **`VizcoreRunConfigurationExtensionTest.kt`** — 4 passing tests: ordered `[-javaagent, -XX:+EnableDynamicAgentLoading]`; exact backend/correlation/token/app threading; space-free path yields a space-free arg; un-armed config leaves a real `JavaParameters` vmParametersList unchanged (early-return).

### Task 2 — Rebuilt action + correlation-identity proof (this run: `396c1ff`)

- **`RunWithVisualizerAction.kt`** rebuilt from the compiling no-op shell to the full launch sequence:
  1. resolve project + the user's selected runnable `RunConfigurationBase` (warn "select a run configuration first" if absent);
  2. mint ONE `correlation = UUID.randomUUID().toString()`;
  3. `BackendHealthCheck.check(VizcoreSettings.backendUrl)` — on `Down`, show the actionable warning (D-05) and return **without launching** (T-13-16);
  4. arm BOTH halves of `VizcoreLaunchState` with that SAME UUID — `armConfiguration(config, corr)` (agent path) and `getInstance(project).arm(port, corr)` (view-URL path);
  5. trigger the standard run executor via `DefaultRunExecutor` + `ExecutionEnvironmentBuilder` + `ProgramRunnerUtil.executeConfiguration` (on `ExecutionException`, disarm so the agent cannot leak into a later run);
  6. open/activate the "Coroutine Visualizer" tool window, which builds its URL from the armed correlation.
  - `update(e)` gates `isEnabledAndVisible` on a non-null project AND a selected configuration.
  - Pure `internal threadCorrelation(correlation, port, configName, backendUrl, token)` seam returns a `CorrelationThreading(agentVmArg, viewUrl)` built from `buildAgentVmArgs(...)` (Task 1) and `VizcoreViewUrl.build(...)` — both carry the SAME UUID, with no run executor or display needed.
- **`CorrelationThreadingTest.kt`** — 2 passing tests: a fixed UUID and a randomly minted UUID each threaded through `threadCorrelation`; the test extracts `corr=<uuid>` from the agent VM-arg and `?correlation=<uuid>` from the view URL and asserts both equal each other AND the minted value (IDE-03 byte-identity, T-13-15).

## Verification

- `cd backend && JAVA_HOME=<JDK21> ./gradlew :intellij-plugin:test --tests "*Correlation*" --tests "*RunConfigurationExtension*" :intellij-plugin:detekt :intellij-plugin:ktlintCheck` — **BUILD SUCCESSFUL**.
- Test results: `CorrelationThreadingTest` 2/2 pass (0 failures/errors); `VizcoreRunConfigurationExtensionTest` 4/4 pass (Task 1, unchanged).
- detekt + ktlintCheck (main + test + scripts) green under JDK 21.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] detekt `ReturnCount` (limit 2) on the rebuilt action**
- **Found during:** Task 2 (first detekt run after rebuilding `actionPerformed`).
- **Issue:** `actionPerformed` had 4 early returns (project, no-config, cast, health-down), exceeding detekt's `ReturnCount` limit of 2 (no guard-clause exclusion configured in `backend/detekt.yml`).
- **Fix:** Folded the project + selected-config + `RunConfigurationBase` cast guards into a single `resolveLaunch(e): LaunchTarget?` (rewritten as one `?: return null` + a single `return if/else` to stay within the budget itself). `actionPerformed` now has exactly 2 returns (resolve-guard + health-down).
- **Files modified:** `RunWithVisualizerAction.kt` (this plan's own Task 2 file — in-scope).
- **Commit:** `396c1ff`

No other deviations — the plan's class/method names (`buildAgentVmArgs`, `VizcoreLaunchState.arm`/`armConfiguration`, `VizcoreViewUrl.build`, `BackendHealthCheck.check`) were honored, and `-XX:+EnableDynamicAgentLoading` was injected once by the Task 1 extension (verified, not duplicated in Task 2).

## Authentication Gates

None.

## Known Stubs

- **`RunWithVisualizerAction.loopbackPort(project)`** returns `VizcoreLaunchState.port ?: 0` (placeholder `LOOPBACK_PORT_PENDING = 0`) because the `LoopbackFrontendServer` lifecycle (binding an ephemeral port) is owned outside this plan. Until the server binds, the tool window shows its "not launched"/fallback panel rather than a live `:0` URL. **Intentional and non-blocking for IDE-03**: correlation identity is independent of the port (the `?correlation=` substring is what IDE-03 asserts, and `CorrelationThreadingTest` proves it for any port including `0`). The real port wiring lands when the server lifecycle is activated (build-wire / Plan 07 + later server-start wiring).

## Threat Flags

None — no new network endpoints, auth paths, or trust-boundary surface beyond the plan's `<threat_model>` (the action patches only the user's own armed config VM args; the agent jar is the plugin's own classpath resource).
