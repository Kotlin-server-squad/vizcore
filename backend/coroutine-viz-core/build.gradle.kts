val kotlin_version: String by project

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("maven-publish")
    id("jacoco")
}

group = "com.jh.coroutine-visualizer"
version = "0.2.1"

dependencies {
    // Core dependencies only — no Ktor, no web framework
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // DebugProbes runtime introspection (RCO-02). implementation (NOT api) so the
    // debug module stays off consumers' compile classpath. Version-parity with
    // kotlinx-coroutines-core 1.10.2 (same JetBrains release train). JVM 17 OK.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.slf4j:slf4j-api:2.0.9")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("ch.qos.logback:logback-classic:1.6.3")
}

val includeIntegration = project.hasProperty("includeIntegration")

tasks.named<Test>("test") {
    // DebugProbes (kotlinx-coroutines-debug) attaches a byte-buddy agent dynamically
    // at install() time; allow it explicitly so the JVM does not warn / (in a future
    // JDK) refuse the dynamic agent load. Affects only the @Tag("integration") smoke IT.
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    useJUnitPlatform {
        // The timing-bearing DebugProbes smoke IT is @Tag("integration"); exclude it
        // from the deterministic gate by default. Opt in with:
        //   ./gradlew :coroutine-viz-core:test -PincludeIntegration
        // (e.g. `--tests "*DebugProbesSmokeIT*" -PincludeIntegration`).
        if (!includeIntegration) {
            excludeTags("integration")
        }
    }
    // The JaCoCo java-agent re-instruments classes at load time, which conflicts with
    // DebugProbes' byte-buddy class re-transformation ("class redefinition failed:
    // attempted to delete a method") on JDK 21. Disable coverage when the real-probes
    // smoke IT runs; the deterministic gate (no -PincludeIntegration) keeps coverage on.
    extensions.configure<JacocoTaskExtension> {
        isEnabled = !includeIntegration
    }
    if (!includeIntegration) {
        finalizedBy(tasks.jacocoTestReport)
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Maven publishing configuration
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.jh.coroutine-visualizer"
            artifactId = "coroutine-viz-core"
            version = project.version.toString()

            from(components["java"])

            pom {
                name.set("Coroutine Viz Core")
                description.set("Core library for Kotlin coroutine visualization — events, wrappers, session management, and validation")
                url.set("https://github.com/Kotlin-server-squad/vizcore")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Kotlin-server-squad/vizcore")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

// Target JVM 17. This shared library is consumed by BOTH the JVM-21 backend and
// the intellij-plugin module, which must produce JVM 17 bytecode for IntelliJ
// 2024.1 (241). IntelliJ Platform Gradle Plugin 2.16.0 strictly enforces the
// org.gradle.jvm.version variant attribute, so a 21-targeted core is rejected by
// the plugin's 17 classpath. A 17 lib runs fine on the JVM-21 backend, so 17 is
// the lowest common denominator that satisfies both consumers.
kotlin {
    // Compile to the Kotlin 2.2 language and API level and declare the matching stdlib, so apps
    // on Kotlin 2.2 or newer can read the SDK (the default, the build's own compiler version,
    // locks out every app on an older Kotlin). 2.2 is the floor anyway: Ktor 3.3 needs
    // kotlin-stdlib 2.2 at runtime, and Spring Boot pins the stdlib to the app's Kotlin version.
    coreLibrariesVersion = "2.2.21"
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
        // Compile against the JDK 17 API, not just to JVM-17 bytecode: on a JDK-21 toolchain a
        // JDK 19+ call (e.g. Thread#threadId) otherwise compiles and then throws NoSuchMethodError
        // for SDK consumers on Java 17. checkBytecode only sees class-file versions, not API use.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

// Generate sources JAR
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}
