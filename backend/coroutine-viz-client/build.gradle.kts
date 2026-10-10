val kotlin_version: String by project
val ktor_version: String by project

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    id("maven-publish")
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
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    // In-process server for the round-trip test: stand up the real ingest route
    // shape (configureWebSockets + webSocket) without a live network.
    testImplementation("io.ktor:ktor-server-test-host:$ktor_version")
    testImplementation("io.ktor:ktor-server-websockets:$ktor_version")
    testImplementation("ch.qos.logback:logback-classic:1.6.3")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Target JVM 17 exactly like coroutine-viz-core: the client is consumed by JVM apps
// and must stay on the lowest common denominator that core publishes against.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        // Compile against the JDK 17 API, not just to JVM-17 bytecode: on a JDK-21 toolchain a
        // JDK 19+ call (e.g. Thread#threadId) otherwise compiles and then throws NoSuchMethodError
        // for SDK consumers on Java 17. checkBytecode only sees class-file versions, not API use.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

// Maven publishing configuration — mirrors coroutine-viz-core's already-working
// block (D-02). The locked coordinate com.jh.coroutine-visualizer:coroutine-viz-client:0.1.0
// MUST match frontend/src/lib/dep-snippet.ts verbatim (the ConnectWizard snippet).
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.jh.coroutine-visualizer"
            artifactId = "coroutine-viz-client"
            version = project.version.toString()

            from(components["java"])

            pom {
                name.set("Coroutine Viz Client")
                description.set("Embeddable client library for streaming coroutine events to a vizcore backend")
                url.set("https://github.com/hermanngeorge15/visualizer-for-coroutines")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/hermanngeorge15/visualizer-for-coroutines")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
