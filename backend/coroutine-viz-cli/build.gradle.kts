plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    application
    // Shadow fat-JAR plugin (SDK-02). Pinned to exactly 9.4.3 — the johnrengelman/shadow
    // lineage's maintained successor (GradleUp org). Legitimacy verified on the Gradle
    // Plugin Portal via the Phase 11 Plan 02 Task 0 blocking-human supply-chain gate.
    // The version is pinned in settings.gradle.kts `pluginManagement` (not inline here)
    // because the org.jetbrains.intellij.platform plugin already brings Shadow onto the
    // build classpath with an "unknown version" — an inline `version "9.4.3"` then fails
    // resolution. Pinning in pluginManagement keeps the exact-9.4.3 supply-chain guarantee
    // (T-11-SC) while applying it here without a version avoids the classpath conflict.
    id("com.gradleup.shadow")
}

group = "com.jh.coroutine-visualizer"
version = "0.1.0"

application {
    mainClass.set("com.jh.proj.coroutineviz.cli.MainKt")
}

dependencies {
    // The CLI reuses core for VizEvent types + the shared appJson serializer so the
    // wire format it decodes == the SSE/replay export format byte-for-byte.
    implementation(project(":coroutine-viz-core"))
    // core exposes appJson but pulls kotlinx-serialization-json / coroutines-core as
    // `implementation` (off consumers' compile classpath), so the CLI declares them
    // directly to use the shared PolymorphicSerializer + the kotlinx Job graph.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.20")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// NOTE: intentionally NO `kotlin { jvmTarget = JVM_17 }` block here. The CLI is a
// standalone tool, not a published library, so it is JVM-21-free by design (D-09) and
// the `checkBytecode` guard does NOT scan this module.
//
// Shadow configuration is intentionally minimal: keep the default `all` classifier
// (yields coroutine-viz-cli-0.1.0-all.jar) and do NOT relocate kotlin.* / kotlinx.* —
// relocating them breaks the polymorphic-reflection decode at runtime (Pitfall 6). A
// standalone `java -jar` has no host classpath to clash with, so relocation buys nothing.
