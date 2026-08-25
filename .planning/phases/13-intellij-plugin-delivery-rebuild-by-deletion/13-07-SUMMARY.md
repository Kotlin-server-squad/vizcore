---
phase: 13-intellij-plugin-delivery-rebuild-by-deletion
plan: 07
subsystem: intellij-plugin
tags: [packaging, buildPlugin, verifyPlugin, signPlugin, agent, frontend, ide, spike]
requires:
  - "coroutine-viz-agent shadowJar (13-01, classpath-isolated)"
  - "intellij-plugin compiling with tool window + run-config extension (13-02/04/05/06)"
  - "frontend SPA build (vite)"
provides:
  - "self-contained plugin distributable zip (agent fat-jar /agent + frontend SPA /frontend bundled in the plugin jar)"
  - "verifyPlugin gate green across IC-241/242/243/251"
  - "signPlugin configured from env secrets only (unsigned zip when unset); no publish task (D-12)"
  - "resolved RESEARCH Open Q1: BUNDLE the Kotlin+coroutines runtime, relocate facades"
  - "docs/guides/intellij-plugin-distribution.md runbook"
affects:
  - "backend/coroutine-viz-agent/build.gradle.kts (spike-driven classpath isolation, committed ed62897)"
  - "intellij-plugin/build.gradle.kts (processResources + sign/verify wires)"
  - "intellij-plugin/src/main/resources/META-INF/plugin.xml (description refresh + modules.java depends)"
tech-stack:
  added:
    - "org.jetbrains.kotlin:kotlin-reflect:2.4.0 (agent fat-jar — spike fix)"
  removed: []
  patterns:
    - "java-agent classpath isolation: relocate third-party facades (slf4j), bundle a self-consistent Kotlin runtime (stdlib+reflect), keep kotlinx.coroutines un-relocated for DebugProbes"
    - "processResources from(shadowJar)+from(frontend/dist) → self-contained plugin zip"
key-files:
  created:
    - "docs/guides/intellij-plugin-distribution.md"
  modified:
    - "backend/coroutine-viz-agent/build.gradle.kts"
    - "intellij-plugin/build.gradle.kts"
    - "intellij-plugin/src/main/resources/META-INF/plugin.xml"
  deleted: []
commits:
  - "ed62897: fix(13): isolate agent fat-jar classpath — relocate slf4j + bundle kotlin-reflect"
  - "bbb7485: feat(13-07): bundle agent jar + frontend SPA into plugin; verify/sign config (IDE-04)"
  - "0f74aa1: docs(13-07): add IntelliJ plugin distribution runbook"
requirements: [IDE-01, IDE-02, IDE-04]
---

# Plan 13-07 Summary — Package the plugin (IDE-04)

> **Process note:** Task 2 was begun by a sequential executor whose connection dropped
> mid-response (after editing build.gradle.kts + plugin.xml, before committing/verifying).
> The orchestrator + user had already run the Task 1 spike together end-to-end; the
> orchestrator then finished Task 2 (verify the build, fix the surfaced compatibility
> problem, commit), Task 3, and this SUMMARY inline. All work is verified, not assumed.

## Task 1 — Coroutines-bundling spike (checkpoint:human-verify, RESOLVED)

Ran end-to-end with the user: backend on :8090, frontend dev on :3100, the agent attached to
`examples/spring-vizcore-demo`. The spike resolved **RESEARCH Open Q1** and surfaced two real,
host-breaking agent-packaging bugs (a Spring Boot executable jar loads the `-javaagent` jar on
the **system/parent-first classloader**, so the agent's bundled libs shadow the host's):

1. **slf4j collision** — bundled `slf4j-api` (un-relocated) shadowed the host's `org.slf4j`,
   breaking Logback init (`NOPLoggerFactory`), crashing the demo at startup. **Fixed**:
   `relocate("org.slf4j", "…agent.shaded.slf4j")` (commit ed62897).
2. **kotlin-reflect missing** — the agent's bundled `kotlin-stdlib` shadowed the host's but
   had no matching `kotlin-reflect`; host-triggered Kotlin reflection (Spring/Jackson) threw
   `KotlinReflectionNotSupportedError`. **Fixed**: bundle `kotlin-reflect:2.4.0` (commit ed62897).

**Decision (INPUT to Task 2): BUNDLE** the full Kotlin + coroutines runtime (stdlib + reflect +
coroutines-core + coroutines-debug, un-relocated so DebugProbes' byte-buddy introspection works),
and **relocate** third-party facades (slf4j). Bundled coroutines do **not** ABI-clash — the agent
captured a coroutine on every attach (3 sessions, `coroutineCount:1` each), with no coroutines
`NoSuchMethodError`. Supported-range guidance: the agent's Kotlin/coroutines runtime is what the
instrumented app runs against (parent-first), so it must be ≥ the host's versions; document a
runtime version-mismatch warning as future hardening.

Non-agent finding: `spring-vizcore-demo` is the Phase-7 *client-SDK* example — it self-instruments
via `VizcoreClient.start(...)` and its jar bundled a stale 3-arg client, so attaching the agent
double-instruments and the stale call `NoSuchMethodError`s. Not an agent defect. The clean visual
confirmation used the demo **standalone** (rebuilt against the current client): 6 workload rounds
→ session `spring-vizcore-demo-…` with **26 coroutines / 107 events**, rendered live in the
frontend (Coroutine Visualizer tool window, named request/db/http/compute/pipeline/io hierarchy,
metrics Active/Peak/Dispatcher-utilization). Full pipeline (client → WS → backend → SSE → FE) proven.

## Task 2 — Build wiring + verify/sign (IDE-04)

`intellij-plugin/build.gradle.kts`:
- `processResources` `dependsOn(agentJar, pnpmBuild)` and copies `:coroutine-viz-agent:shadowJar`
  → `/agent/coroutine-viz-agent.jar` and the vite `pnpm build` output (`../frontend/dist`) →
  `/frontend`. `buildPlugin` yields a self-contained `intellij-plugin-0.1.0.zip`.
- `signing { }` reads `CERTIFICATE_CHAIN` / `PRIVATE_KEY` / `PRIVATE_KEY_PASSWORD` from env or
  Gradle properties only; unset → `signPlugin` skipped → unsigned zip. No `publishPlugin` task (D-12).
- `plugin.xml` `<description>` refreshed (drops VizScope / Tree-Timeline-EventLog copy; describes
  the agent-attach + embedded JCEF live view; development-tool note).

**Compatibility fix (surfaced by verifyPlugin):** `VizcoreRunConfigurationExtension` uses the Java
plugin's `RunConfigurationExtension` / `JavaParameters`, so `plugin.xml` now declares
`<depends>com.intellij.modules.java</depends>` — without it `verifyPlugin` reported 1 compatibility
problem on every IDE. (`bundledPlugin("com.intellij.java")` was already present for compilation.)

**Verification (JDK 21):**
- `:intellij-plugin:buildPlugin` → `intellij-plugin/build/distributions/intellij-plugin-0.1.0.zip`.
- The plugin jar (`lib/intellij-plugin-0.1.0.jar`) bundles `agent/coroutine-viz-agent.jar` (~24.8 MB)
  AND `frontend/index.html` (+ 10 frontend assets).
- `:intellij-plugin:verifyPlugin` → **Compatible** on IC-241; IC-242/243/251 warnings only (no
  problems). Build SUCCESSFUL.

## Task 3 — Distribution runbook

`docs/guides/intellij-plugin-distribution.md`: buildPlugin (self-contained zip + how to inspect the
nested jar), verifyPlugin gate (sinceBuild=241/untilBuild=251.* + the modules.java depends), signPlugin
env secrets (never committed), and the **human** Marketplace upload step (no publishPlugin token wired,
D-12) — plus the development-tool security note.

## Notes / follow-ups

- A composite build task regenerates `backend/docs/index.html` (stale OpenAPI doc) as a side effect;
  reverted to keep this phase's commits focused — a separate docs sync if desired.
- 13-05's `pluginVerification` mute block (internal-API DefaultImpls) remains; the 6 internal-API
  usages are warnings, not problems.
- Marketplace publish + signing with real keys remain documented human steps (D-12).
