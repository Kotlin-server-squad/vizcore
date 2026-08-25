// Throwaway consumer build — its ONLY purpose is to prove resolution of the
// locked client coordinate as a real external consumer would (SC#1).
//
// The coordinate below MUST be the EXACT string from
// frontend/src/lib/dep-snippet.ts:13 (Pitfall 1 — the FE snippet and the
// published artifact cannot drift).
//
// Prove resolution with:
//   gradle dependencies --configuration runtimeClasspath
// and confirm `com.jh.coroutine-visualizer:coroutine-viz-client:0.1.0` appears
// with no "Could not find" error.

plugins {
    `java-library`
}

dependencies {
    implementation("com.jh.coroutine-visualizer:coroutine-viz-client:0.1.0")
}
