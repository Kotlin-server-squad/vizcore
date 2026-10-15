import java.util.zip.ZipFile

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

    implementation("org.openfolder:kotlin-asyncapi-ktor:3.2.3")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-auth")
    // JWT auth (AUTH-03) — version from the Ktor BOM (io.ktor.plugin) so it tracks ktor-server-auth.
    implementation("io.ktor:ktor-server-auth-jwt")
    // Argon2id password verification for the token endpoint (AUTH-03, D-02). Maven Central, vetted.
    implementation("com.password4j:password4j:1.8.4")
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

    // OpenTelemetry / OTLP observability (OTEL-01/02) — backend-ONLY per D-12.
    // NEVER add these to coroutine-viz-core / coroutine-viz-client: OTel bytecode in
    // a publishable module fails the Phase-11 `checkBytecode` guard. First-party CNCF
    // io.opentelemetry artifacts (RESEARCH Package Legitimacy Audit: all Approved).
    // BOM pins the version; all other coordinates inherit it (no per-artifact versions).
    // Do NOT add opentelemetry-sdk-extension-autoconfigure — it eagerly constructs/sets
    // a global, defeating the OTEL-01 construction gate (RESEARCH anti-pattern).
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.63.0"))
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-netty")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    implementation("io.ktor:ktor-server-config-yaml")

    // Persistence (PERS-01/02) — Exposed 1.x DSL + HikariCP pool + Flyway migrations.
    // These live on :backend ONLY — coroutine-viz-core stays a zero-DB publishable SDK.
    implementation("org.jetbrains.exposed:exposed-core:1.5.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.5.0")
    implementation("org.jetbrains.exposed:exposed-json:1.5.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:1.5.0")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
    implementation("org.postgresql:postgresql:42.7.11")
    implementation("com.h2database:h2:2.4.240")
    testRuntimeOnly("com.h2database:h2:2.4.240")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-content-negotiation")
    // Client WebSockets plugin for the ingest-route tests (RCO-05) — Ktor BOM.
    testImplementation("io.ktor:ktor-client-websockets")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")

    // JUnit 5 (Jupiter) for new dispatcher tests
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    // OTel in-memory span exporter for Wave 0 span-shape assertions (plans 02/03) —
    // version inherited from the opentelemetry-bom above. Backend-only (D-12).
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
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

// PERF-06 guard: fail the build if the publishable SDK modules drift off the JVM-17
// floor (D-07) OR if coroutine-viz-core gains an io.ktor import (D-08, breaks the
// IntelliJ-241 17-targeted plugin variant). Models the verifyNoDuplicateSourceFqns
// idiom but scans COMPILED output, so it MUST dependsOn the modules' `classes` tasks
// (RESEARCH Pitfall 4 — the bytes must exist) and FAIL on an empty classes dir rather
// than pass vacuously. The backend app (com.jh.proj) legitimately uses io.ktor (see the
// application { mainClass = "io.ktor.server.netty.EngineMain" } block above) and is NOT
// scanned — only the two publishable, web-framework-free modules are.
val checkBytecode by tasks.registering {
    group = "verification"
    description =
        "Fails if coroutine-viz-core/client emit a class with bytecode major > 61 (JVM 17) " +
        "or if coroutine-viz-core sources import io.ktor."
    dependsOn(
        project(":coroutine-viz-core").tasks.named("classes"),
        project(":coroutine-viz-client").tasks.named("classes"),
    )
    doLast {
        val jvm17Major = 61 // class-file major version for Java 17
        val offenders = mutableListOf<String>()
        for (path in listOf(":coroutine-viz-core", ":coroutine-viz-client")) {
            val classesDir =
                project(path)
                    .layout.buildDirectory
                    .dir("classes/kotlin/main")
                    .get()
                    .asFile
            val classFiles =
                if (classesDir.isDirectory) {
                    classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
                } else {
                    emptyList()
                }
            if (classFiles.isEmpty()) {
                // Pitfall 4: an input-less guard must error, not pass vacuously.
                throw GradleException(
                    "checkBytecode found no compiled classes for $path under " +
                        "${classesDir.path} — the modules must compile before the scan runs.",
                )
            }
            for (classFile in classFiles) {
                val bytes = classFile.readBytes()
                // 0xCAFEBABE header: magic[0..3], minor[4..5], major[6..7].
                // A guard that cannot parse a class must fail HARD (treat as an
                // offender), not crash with IndexOutOfBounds or silently trust it.
                if (bytes.size < 8) {
                    offenders += "  - ${classFile.path} (truncated: ${bytes.size} bytes, cannot read class version)"
                    continue
                }
                val magic =
                    ((bytes[0].toInt() and 0xFF) shl 24) or
                        ((bytes[1].toInt() and 0xFF) shl 16) or
                        ((bytes[2].toInt() and 0xFF) shl 8) or
                        (bytes[3].toInt() and 0xFF)
                if (magic != -0x35014542) { // 0xCAFEBABE
                    offenders += "  - ${classFile.path} (bad magic 0x${magic.toUInt().toString(16)}, not a class file)"
                    continue
                }
                val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
                if (major > jvm17Major) {
                    offenders += "  - ${classFile.path} (major $major > $jvm17Major)"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Bytecode above the JVM-17 floor (major > $jvm17Major) in a publishable module:\n" +
                    offenders.joinToString("\n"),
            )
        }

        // D-08: coroutine-viz-core must stay io.ktor-free. Match IMPORT LINES, not a
        // whole-file substring — three core KDoc comments literally say "no io.ktor"
        // (documentation, not imports) and MUST pass.
        val coreSrc =
            project(":coroutine-viz-core")
                .layout.projectDirectory
                .dir("src/main/kotlin")
                .asFile
        val ktorImporters =
            coreSrc
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    file.readLines().any { line -> line.trimStart().startsWith("import io.ktor") }
                }.map { "  - ${it.path}" }
                .sorted()
                .toList()
        if (ktorImporters.isNotEmpty()) {
            throw GradleException(
                "coroutine-viz-core must stay io.ktor-free (it backs the IntelliJ-241 JVM-17 " +
                    "plugin variant), but these sources import io.ktor:\n" +
                    ktorImporters.joinToString("\n"),
            )
        }
    }
}

// #123 guard: the fat jar (Ktor `buildFatJar` -> Shadow) must carry the UNION of every
// META-INF/services entry on the runtime classpath. Without merging, the last jar to supply
// a service file wins — flyway-database-postgresql's Plugin file clobbered flyway-core's, so
// DB mode died at startup with "Unsupported Database: H2 2.3" while every test (run on the
// exploded classpath) stayed green. Generic on purpose: any library can hit the same trap.
val verifyFatJarServiceFiles by tasks.registering {
    group = "verification"
    description = "Fails if the fat jar is missing any META-INF/services entry present on the runtime classpath."
    val fatJar = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
    val runtimeJars = configurations.named("runtimeClasspath")
    inputs.file(fatJar)
    inputs.files(runtimeJars)
    doLast {
        fun serviceEntries(zip: ZipFile): Map<String, Set<String>> =
            zip
                .entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                .associate { entry ->
                    entry.name to
                        zip
                            .getInputStream(entry)
                            .bufferedReader()
                            .readLines()
                            .map { it.substringBefore('#').trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                }
        val expected = mutableMapOf<String, MutableSet<String>>()
        runtimeJars.get().filter { it.isFile && it.extension == "jar" }.forEach { jar ->
            ZipFile(jar).use { zip ->
                serviceEntries(zip).forEach { (name, lines) -> expected.getOrPut(name) { mutableSetOf() } += lines }
            }
        }
        val actual = ZipFile(fatJar.get().asFile).use { serviceEntries(it) }
        val missing =
            expected
                .flatMap { (name, lines) -> (lines - actual[name].orEmpty()).map { "  - $name: $it" } }
                .sorted()
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Fat jar ${fatJar.get().asFile.name} is missing ${missing.size} service entries " +
                    "(configure mergeServiceFiles() on shadowJar):\n" + missing.joinToString("\n"),
            )
        }
    }
}

// #123: merge (concatenate) every META-INF/services file instead of letting the last jar win.
// Shadow 9 excludes duplicate paths BEFORE transformers run (duplicatesStrategy EXCLUDE), so the
// service files must be let through as duplicates for mergeServiceFiles() to see them all.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
}

tasks.named("check") { dependsOn(verifyNoDuplicateSourceFqns) }
tasks.named("check") { dependsOn(checkBytecode) }
tasks.named("check") { dependsOn(verifyFatJarServiceFiles) }
