val kotlin_version: String by project
val logback_version: String by project
val prometheus_version: String by project

plugins {
    kotlin("jvm") version "2.3.21"
    id("io.ktor.plugin") version "3.3.2"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.2.0"
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
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")

    // JUnit 5 (Jupiter) for new dispatcher tests
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
