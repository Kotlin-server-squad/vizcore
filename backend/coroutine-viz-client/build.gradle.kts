val kotlin_version: String by project
val ktor_version: String by project

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
}

group = "com.jh.coroutine-visualizer"
version = "0.1.0"

dependencies {
    // The embeddable client reuses core for VizEvent types + the shared appJson
    // serializer (Plan 07-01) so wire format == ingest format == SSE/FE format.
    implementation(project(":coroutine-viz-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // core exposes appJson but pulls kotlinx-serialization-json as `implementation`
    // (off consumers' compile classpath), so the client declares it directly to use
    // the shared PolymorphicSerializer + parse the bootstrap JSON response.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Ktor client — first-party JetBrains modules, pinned to the project ktor BOM
    // version (T-07-SC). CIO engine + WebSockets for the ingest send loop; core for
    // the session-create POST.
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-websockets:$ktor_version")

    implementation("org.slf4j:slf4j-api:2.0.9")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    // In-process server for the round-trip test: stand up the real ingest route
    // shape (configureWebSockets + webSocket) without a live network.
    testImplementation("io.ktor:ktor-server-test-host:$ktor_version")
    testImplementation("io.ktor:ktor-server-websockets:$ktor_version")
    testImplementation("ch.qos.logback:logback-classic:1.5.32")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Target JVM 17 exactly like coroutine-viz-core: the client is consumed by JVM apps
// and must stay on the lowest common denominator that core publishes against.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
