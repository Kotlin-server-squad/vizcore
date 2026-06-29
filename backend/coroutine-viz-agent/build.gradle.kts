plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    // Shadow fat-JAR plugin (IDE-01). Pinned to exactly 9.4.3 — the johnrengelman/shadow
    // lineage's maintained successor (GradleUp org). Legitimacy verified on the Gradle
    // Plugin Portal via the Phase 11 Plan 02 Task 0 blocking-human supply-chain gate.
    // The version is pinned in settings.gradle.kts `pluginManagement` (not inline here)
    // because the org.jetbrains.intellij.platform plugin already brings Shadow onto the
    // build classpath with an "unknown version" — an inline `version "9.4.3"` then fails
    // resolution. Pinning in pluginManagement keeps the exact-9.4.3 supply-chain guarantee
    // (T-13-SC) while applying it here without a version avoids the classpath conflict.
    id("com.gradleup.shadow")
}

group = "com.jh.coroutine-visualizer"
version = "0.1.0"

// The agent links the embeddable client, which inherits the JVM-17 purity invariant
// (the published-library floor enforced by checkBytecode). UNLIKE the CLI (a JVM-21-free
// standalone tool), the agent jar is injected into arbitrary target JVMs, so it pins
// JVM-17 bytecode to stay broadly attachable.
kotlin {
    jvmToolchain(17)
}

dependencies {
    // The agent reuses VizcoreClient.start — the existing zero-code DebugProbes capture
    // path (D-02). The premain is a thin one-call wrapper; NO bridge code is written here.
    implementation(project(":coroutine-viz-client"))
    // The client exposes its kotlinx-coroutines / serialization-json / ktor-client deps
    // only as `implementation` (off consumers' compile classpath). Because the Shadow
    // fat-jar must BUNDLE the full client transitive runtime, declare the verified
    // transitive set explicitly (copied from examples/spring-vizcore-demo, the proven
    // runtime-complete set). kotlinx-coroutines-debug is what DebugProbes installs.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.ktor:ktor-client-core:3.3.2")
    implementation("io.ktor:ktor-client-cio:3.3.2")
    implementation("io.ktor:ktor-client-websockets:3.3.2")
    implementation("org.slf4j:slf4j-api:2.0.9")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.20")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.shadowJar {
    // The agent is a LAUNCH-TIME javaagent only: it is attached via `-javaagent:...` at
    // JVM start and runs `premain`. It does NOT transform/redefine/retransform bytecode,
    // so the manifest carries ONLY Premain-Class — no Agent-Class / Can-Redefine-Classes /
    // Can-Retransform-Classes.
    manifest {
        attributes["Premain-Class"] = "com.jh.proj.coroutineviz.agent.VizcoreAgent"
    }
    // Shadow configuration is intentionally minimal: keep the default `all` classifier
    // (yields coroutine-viz-agent-0.1.0-all.jar) and do NOT relocate kotlin.* / kotlinx.* —
    // relocating them breaks DebugProbes' byte-buddy package-name introspection at runtime
    // (RESEARCH Pitfall 4). The coroutines packages MUST ship under their real names.
}
