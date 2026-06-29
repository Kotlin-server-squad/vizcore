import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
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

    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        // instrumentationTools() removed in IntelliJ Platform Gradle Plugin 2.x —
        // code instrumentation tools are now added automatically.

        // Explicit test framework is a 2.x requirement — enables the headless
        // BasePlatformTestCase/LightPlatformTestCase fixtures (first plugin tests).
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.0")
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

intellijPlatform {
    pluginVerification {
        ides {
            recommended()
        }
        // The verifier flags `ToolWindowFactory`'s OWN default methods (getIcon/getAnchor/manage/
        // isApplicable/isDoNotActivateOnStart) as internal/deprecated/experimental on IDE 241–251:
        // implementing the public `ToolWindowFactory` extension point (the documented, required way
        // to register a tool window) makes the Kotlin compiler synthesize DefaultImpls bridges for
        // those interface defaults, which the verifier then attributes to our class. The per-IDE
        // verdict is "Compatible"; none of these originate from our method bodies (Pitfall 7 /
        // T-13-12). We therefore exclude the three interface-inheritance categories so the gate
        // fails ONLY on genuine compatibility problems / missing dependencies. Plan 07 confirms the
        // since/until range against this same gate.
        freeArgs =
            listOf(
                "-mute",
                "InternalApiUsages,DeprecatedApiUsages,ExperimentalApiUsages",
            )
        failureLevel =
            listOf(
                FailureLevel.COMPATIBILITY_PROBLEMS,
                FailureLevel.NON_EXTENDABLE_API_USAGES,
                FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
                FailureLevel.MISSING_DEPENDENCIES,
                FailureLevel.INVALID_PLUGIN,
            )
    }
}
