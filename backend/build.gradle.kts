val kotlin_version: String by project
val logback_version: String by project
val prometheus_version: String by project

plugins {
    kotlin("jvm") version "2.3.21"
    id("io.ktor.plugin") version "3.3.2"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

detekt {
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
    baseline = file("detekt-baseline.xml")
}

group = "com.jh.proj"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

dependencies {
    // Core library (events, wrappers, session, validation)
    implementation(project(":coroutine-viz-core"))

    implementation("org.openfolder:kotlin-asyncapi-ktor:3.2.2")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-auth")
    // JWT auth (AUTH-03) — version from the Ktor BOM (io.ktor.plugin) so it tracks ktor-server-auth.
    implementation("io.ktor:ktor-server-auth-jwt")
    // Argon2id password verification for the token endpoint (AUTH-03, D-02). Maven Central, vetted.
    implementation("com.password4j:password4j:1.8.2")
    implementation("io.ktor:ktor-server-compression")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-swagger")
    implementation("io.ktor:ktor-server-openapi")
    implementation("io.ktor:ktor-server-sse")
    // WebSocket ingest endpoint (RCO-05) — version from the Ktor BOM (io.ktor.plugin 3.3.2),
    // mirroring ktor-server-sse. First-party JetBrains, same trust basis (RESEARCH T-07-SC).
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-default-headers")
    // Per-IP rate limiting for the public shared read (SHAR-02, D-12) — version from the Ktor BOM.
    implementation("io.ktor:ktor-server-rate-limit")
    implementation("io.ktor:ktor-server-metrics-micrometer")
    implementation("io.micrometer:micrometer-registry-prometheus:$prometheus_version")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-netty")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")
    implementation("io.ktor:ktor-server-config-yaml")

    // Persistence (PERS-01/02) — Exposed 1.x DSL + HikariCP pool + Flyway migrations.
    // These live on :backend ONLY — coroutine-viz-core stays a zero-DB publishable SDK.
    implementation("org.jetbrains.exposed:exposed-core:1.3.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.3.0")
    implementation("org.jetbrains.exposed:exposed-json:1.3.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:1.3.0")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.h2database:h2:2.3.232")
    testRuntimeOnly("com.h2database:h2:2.3.232")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-content-negotiation")
    // Client WebSockets plugin for the ingest-route tests (RCO-05) — Ktor BOM.
    testImplementation("io.ktor:ktor-client-websockets")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")

    // JUnit 5 (Jupiter) for new dispatcher tests
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// ── PERF-05 dev-only load harness (D-09/D-10/D-11) ───────────────────────────
// A SEPARATE `loadHarness` source set + JavaExec task that drives sustained
// synthetic VizEvent load straight at EventBus.send to stress the Plan 01-03
// egress hardening. Because it is its OWN source set (not `main`), it is excluded
// from `jar`/`shadowJar` by default — nothing here is ever added to a jar `from(...)`,
// so the harness can NEVER ship in the production artifact (D-09). The source set
// compiles/runs against `main`'s output + runtime classpath so it can see
// VizSession / EventBus / the egress primitives. It lives ONLY in :backend and does
// NOT touch coroutine-viz-core / coroutine-viz-client build files, so the JVM-17
// purity of those publishable modules is untouched (Pitfall P6); the harness may run
// on the backend's JVM 21.
val loadHarness: SourceSet =
    sourceSets.create("loadHarness") {
        compileClasspath += sourceSets["main"].output + configurations["runtimeClasspath"]
        runtimeClasspath += output + compileClasspath
    }

// The thin `main()` entrypoint in src/loadHarness/ flooding via EgressLoadDriver (main).
tasks.register<JavaExec>("loadHarness") {
    group = "verification"
    description = "Dev-only: floods synthetic VizEvents at EventBus.send to stress the egress chain (PERF-05)."
    mainClass.set("com.jh.proj.coroutineviz.harness.LoadHarnessMain")
    classpath = loadHarness.runtimeClasspath
}

// CR-01 guard: fail the build if any .kt FQN (== relative path under src/main/kotlin)
// exists in BOTH :backend and :coroutine-viz-core main sources. Same-FQN duplicates land
// on one flat runtime classpath and the JVM loads whichever it enumerates first (unspecified
// order) — the exact hazard that produced the live 08.3 HTTP 500. Scan the WHOLE tree, not
// models/ only, so a drifted sync/DeadlockDetector.kt cannot slip past.
val verifyNoDuplicateSourceFqns by tasks.registering {
    group = "verification"
    description = "Fails if any .kt FQN (relative path) exists in BOTH :backend and :coroutine-viz-core main sources."
    val backendRoot = layout.projectDirectory.dir("src/main/kotlin")
    val coreRoot = project(":coroutine-viz-core").layout.projectDirectory.dir("src/main/kotlin")
    inputs.dir(backendRoot)
    inputs.dir(coreRoot)
    doLast {
        fun rels(dir: java.io.File) =
            dir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(dir).invariantSeparatorsPath }
                .toSet()
        val dups = (rels(backendRoot.asFile) intersect rels(coreRoot.asFile)).sorted()
        if (dups.isNotEmpty()) {
            throw GradleException(
                "Duplicate same-FQN Kotlin sources across :backend and :coroutine-viz-core " +
                    "(remove the :backend copies — they shadow the SDK at runtime):\n" +
                    dups.joinToString("\n") { "  - $it" },
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyNoDuplicateSourceFqns) }
