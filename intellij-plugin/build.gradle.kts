plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.5.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

detekt {
    config.setFrom(files("../backend/detekt.yml"))
    buildUponDefaultConfig = true
    baseline = file("detekt-baseline.xml")
}

group = "com.jh.coroutine-visualizer"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Core visualization library
    implementation(project(":coroutine-viz-core"))

    // Lightweight HTTP server for receiving events from instrumented app
    implementation("io.ktor:ktor-server-core:3.3.2")
    implementation("io.ktor:ktor-server-cio:3.3.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.3.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.2")

    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        instrumentationTools()
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.20")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.jh.coroutine-visualizer"
        name = "Kotlin Coroutine Visualizer"
        version = project.version.toString()
        description =
            """
            Visualize Kotlin coroutine execution directly in IntelliJ IDEA.

            Features:
            - Real-time coroutine hierarchy tree view
            - Timeline visualization with suspension points
            - Event log with filtering and search
            - Integration with VizScope instrumentation library
            """.trimIndent()
        changeNotes = "Initial release"
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "251.*"
        }
        vendor {
            name = "JH"
            url = "https://github.com/hermanngeorge15/visualizer-for-coroutines"
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
