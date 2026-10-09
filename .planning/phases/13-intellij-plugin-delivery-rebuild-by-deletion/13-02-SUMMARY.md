---
phase: 13-intellij-plugin-delivery-rebuild-by-deletion
plan: 02
subsystem: intellij-plugin
tags: [rebuild-by-deletion, plugin, cleanup, detekt, ide]
requires:
  - "intellij-plugin module (legacy in-IDE receiver + Swing UI baseline)"
provides:
  - "legacy-free, compiling intellij-plugin module on a clean slate for Waves 2-4"
  - "io.ktor-free plugin build.gradle.kts"
  - "plugin.xml pruned of legacy registrations (action preserved)"
  - "empty detekt baseline (no deleted-file entries)"
  - "RunWithVisualizerAction reduced to a compiling AnAction shell"
affects:
  - "intellij-plugin/build.gradle.kts (Plan 05 layers agent/frontend wires)"
  - "intellij-plugin/src/main/resources/META-INF/plugin.xml (Plans 03/04/05 re-register)"
  - "RunWithVisualizerAction.kt (Plan 05 rebuilds the launch sequence)"
tech-stack:
  added: []
  removed:
    - "io.ktor:ktor-server-core:3.5.0"
    - "io.ktor:ktor-server-cio:3.5.0"
    - "io.ktor:ktor-server-content-negotiation:3.5.0"
    - "io.ktor:ktor-serialization-kotlinx-json:3.5.0"
  patterns:
    - "rebuild-by-deletion (D-11): delete legacy first to free plugin.xml + build.gradle.kts for re-registration"
key-files:
  created: []
  modified:
    - "intellij-plugin/build.gradle.kts"
    - "intellij-plugin/src/main/resources/META-INF/plugin.xml"
    - "intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/actions/RunWithVisualizerAction.kt"
    - "intellij-plugin/detekt-baseline.xml"
  deleted:
    - "intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/receiver/PluginEventReceiver.kt"
    - "intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/ui/TreePanel.kt"
    - "intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/ui/TimelinePanel.kt"
    - "intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/ui/EventLogPanel.kt"
    - "intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/ui/renderers/CoroutineNodeRenderer.kt"
    - "intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/PluginSessionManager.kt"
    - "intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/CoroutineVisualizerStartupActivity.kt"
    - "intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/settings/VisualizerSettingsConfigurable.kt"
    - "intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/CoroutineVisualizerToolWindowFactory.kt"
decisions:
  - "Empty <extensions> block removed entirely from plugin.xml after pruning the three legacy registrations (no empty container left dangling)"
  - "Stale plugin <description>/Getting-Started copy intentionally left unchanged — PATTERNS assigns the description refresh to the re-registration plan (03/05), out of this plan's file-ownership scope"
  - "detekt baseline regenerated via :intellij-plugin:detektBaseline (all prior entries pointed at deleted files); result is an empty CurrentIssues set"
requirements: [IDE-01]
metrics:
  duration: ~6 min
  tasks: 2
  files-changed: 13
  completed: 2026-06-29
---

# Phase 13 Plan 02: Rebuild-by-Deletion — Delete Legacy In-IDE Plugin Summary

Deleted the entire legacy in-IDE plugin stack (the :8090 Ktor receiver, all Swing Tree/Timeline/EventLog panels + renderer, session manager, startup activity, old settings configurable, old tabbed tool-window factory), removed all `io.ktor:ktor-server-*` deps, pruned the matching `plugin.xml` registrations, gutted `RunWithVisualizerAction` to a compiling no-op shell, and regenerated an empty detekt baseline — leaving the plugin module compiling and detekt/ktlint-clean on a fresh slate for Waves 2–4 (SC#1).

## What Was Built

### Task 1 — Delete legacy source, remove io.ktor deps, prune plugin.xml (commit c67a332)
- `git rm` of all nine legacy `.kt` files (D-11): `PluginEventReceiver.kt`, `TreePanel.kt`, `TimelinePanel.kt`, `EventLogPanel.kt`, `renderers/CoroutineNodeRenderer.kt`, `PluginSessionManager.kt`, `CoroutineVisualizerStartupActivity.kt`, `settings/VisualizerSettingsConfigurable.kt`, `CoroutineVisualizerToolWindowFactory.kt`. Empty package dirs (`receiver/`, `ui/`, `ui/renderers/`, `settings/`) were removed as a side effect of `git rm`.
- Removed the four `io.ktor:ktor-server-*` implementation lines from `build.gradle.kts`; kept `implementation(project(":coroutine-viz-core"))`, the `intellijPlatform { ... }` deps, and the test deps. No new wires added (those land in Plan 05).
- Pruned `plugin.xml`: removed the `<toolWindow ...CoroutineVisualizerToolWindowFactory>`, `<applicationConfigurable ...VisualizerSettingsConfigurable>`, and `<postStartupActivity ...CoroutineVisualizerStartupActivity>` registrations. The now-empty `<extensions>` container was removed entirely. The `<action ...RunWithVisualizerAction>` registration is preserved.

### Task 2 — Compiling shell + fresh detekt baseline (commit ddb96cf)
- `RunWithVisualizerAction.kt` gutted to a minimal `AnAction`: `actionPerformed` is a no-op guarded by `e.project ?: return`; `update` gates `isEnabledAndVisible` on a non-null project. All references to the deleted `PluginEventReceiver`/`PluginSessionManager` and the old `TODO` are gone; no imports of deleted classes remain. The real launch sequence is rebuilt in Plan 05.
- Regenerated `detekt-baseline.xml` via `:intellij-plugin:detektBaseline`. Every prior entry referenced a deleted file (PluginEventReceiver / EventLogPanel / TimelinePanel / VisualizerSettingsConfigurable / CoroutineNodeRenderer / TreePanel), so the regenerated baseline is empty (`<CurrentIssues/>`) — the module is detekt-clean without suppressions.

## Verification

Run from the `backend` Gradle composite root under JDK 21 (Azul Zulu 21):
- `./gradlew :intellij-plugin:compileKotlin` — BUILD SUCCESSFUL
- `./gradlew :intellij-plugin:detektBaseline` — BUILD SUCCESSFUL (empty baseline)
- `./gradlew :intellij-plugin:detekt :intellij-plugin:ktlintCheck` — BUILD SUCCESSFUL

Targeted greps:
- `grep -rn io.ktor intellij-plugin/` → no matches (io.ktor fully gone from the module)
- `plugin.xml` → no `postStartupActivity` / `CoroutineVisualizerToolWindowFactory` / `VisualizerSettingsConfigurable`; still registers `RunWithVisualizerAction`
- Remaining plugin source: only `actions/RunWithVisualizerAction.kt`
- `detekt-baseline.xml` → no entries naming any deleted file

## Success Criteria

- [x] All legacy in-IDE receiver + Swing UI deleted (SC#1)
- [x] `io.ktor:*` gone from the plugin module
- [x] `plugin.xml` pruned of legacy registrations; action registration preserved
- [x] Plugin module compiles + passes detekt/ktlint on a clean slate

## Threat Model Outcome

- **T-13-03** (legacy :8090 inbound socket — Information disclosure/Elevation): mitigated by outright deletion of `PluginEventReceiver`; no in-IDE listener remains.
- **T-13-04** (stale detekt baseline masking new issues — Tampering): mitigated by regenerating the baseline so no deleted-file entries can hide regressions.
- **T-13-SC** (dependency removal — accept): removal-only; four io.ktor server deps deleted, no new packages added.

## Notes for Next Plans

- This plan OWNS the first edit of `plugin.xml` + `build.gradle.kts`. Plans 03/04/05 layer the new `<toolWindow ...VizcoreToolWindowFactory>`, `<applicationConfigurable ...VizcoreSettings>`, and `<runConfigurationExtension ...VizcoreRunConfigurationExtension>` registrations + the agent/frontend `processResources` wires onto this clean slate.
- Plan 05 rebuilds `RunWithVisualizerAction`'s real body (mint correlation, health-check backend, arm launch state, run, open JCEF tool window).
- The stale `<description>` / Getting-Started copy in `plugin.xml` (VizScope replacement wording) is left for the re-registration plan to refresh (PATTERNS assigns it there); it is harmless inert metadata in the meantime.

## Deviations from Plan

None — plan executed exactly as written. No bugs, missing functionality, or blocking issues encountered; no architectural decisions required. The `../gradlew` path in the plan's verify commands was resolved to the actual wrapper at `backend/gradlew` with module tasks invoked as `:intellij-plugin:*` (the plugin module is part of the `backend` Gradle composite via `backend/settings.gradle.kts`); this is a verify-invocation detail, not a behavioral deviation.

## Self-Check: PASSED

- SUMMARY.md exists at the plan path.
- All nine legacy `.kt` files confirmed absent from disk.
- `RunWithVisualizerAction.kt` (shell) and `detekt-baseline.xml` present.
- Commits c67a332 and ddb96cf exist in git history.
