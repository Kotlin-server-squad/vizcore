/**
 * The single shared source of truth for the published client-library Maven
 * coordinate shown in the ConnectWizard's "Add the client library" step.
 *
 * LOCKED canonical coordinate (Phase 9, D-06/D-08): it matches
 * `backend/coroutine-viz-client/build.gradle.kts` verbatim —
 * group `com.jh.coroutine-visualizer`, version `0.1.0`, artifact = module name
 * (`coroutine-viz-client`). Phase 11's publish job is contractually bound to
 * this exact coordinate, so it lives in ONE place and cannot drift into a
 * second hard-coded copy (the previous stale `com.jh:vizcore-client:0.1`
 * had the wrong group, artifact, AND version — D-07).
 */
export const DEP_SNIPPET = 'implementation("com.jh.coroutine-visualizer:coroutine-viz-client:0.1.0")'
