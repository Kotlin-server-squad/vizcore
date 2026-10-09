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

    // kotlinx.serialization runtime for the native /api wire DTOs (the serialization compiler
    // plugin is applied above but the runtime is not transitively visible; pin to the repo-wide
    // 1.11.0 used by coroutine-viz-core/agent/cli).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

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

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.0")
    // Mocks a RunConfigurationBase for the headless un-armed early-return proof in
    // VizcoreRunConfigurationExtensionTest (no project/fixture needed). Test-only.
    testImplementation("org.mockito:mockito-core:5.23.0")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.jh.coroutine-visualizer"
        name = "Kotlin Coroutine Visualizer"
        version = project.version.toString()
        // Delivery-vehicle copy (Plan 07 / RESEARCH §State of the Art): the plugin is the
        // packaging + launch vehicle for the agent-attach pipeline, NOT a VizScope/Swing UI.
        // The stale "replace CoroutineScope with VizScope" + Tree/Timeline/EventLog tab copy
        // is removed — those tabs no longer exist (D-11). The live view is an embedded React
        // SPA fed by the backend over SSE; capture is zero-code via the bundled -javaagent jar
        // driving DebugProbes. This is a DEVELOPMENT TOOL: it streams coroutine creation-stack
        // source paths to the configured backend (T-13-19, dev-only info-disclosure mitigation).
        description =
            """
            Run any Kotlin/JVM application with live coroutine visualization, directly from IntelliJ IDEA.

            <ul>
              <li>One-click "Run with Coroutine Visualizer" — attaches a bundled Java agent to your run configuration (zero code changes).</li>
              <li>The agent captures coroutine activity via <code>kotlinx-coroutines</code> DebugProbes and streams it to the visualizer backend.</li>
              <li>A live React view is embedded in an IDE tool window (JCEF), auto-navigating to your run's session over SSE.</li>
            </ul>

            <p><b>Development tool.</b> The embedded view shows coroutine creation-stack source paths and is intended for development/debugging on trusted backends only.</p>
            """.trimIndent()
        changeNotes =
            """
            Delivery-vehicle release: bundles the coroutine-capture Java agent and the embedded live view.
            Adds "Run with Coroutine Visualizer" agent-attach, an IDE tool window hosting the live React SPA over SSE.
            """.trimIndent()
        ideaVersion {
            // sinceBuild=241 (2024.1, the resolved compile target); untilBuild=251.* confirmed
            // acceptable by the verifyPlugin gate below (Plan 07 / RESEARCH Open Q untilBuild).
            sinceBuild = "241"
            untilBuild = "251.*"
        }
        vendor {
            name = "JH"
            url = "https://github.com/hermanngeorge15/visualizer-for-coroutines"
        }
    }

    // signPlugin reads the marketplace-zip-signer key material from env/Gradle properties ONLY
    // (T-13-17, V6). Never commit keys: CERTIFICATE_CHAIN / PRIVATE_KEY / PRIVATE_KEY_PASSWORD
    // are supplied at sign time (env or -P). When unset, signPlugin is simply skipped and
    // buildPlugin still produces an UNSIGNED distributable zip (the human signs+uploads, D-12).
    signing {
        certificateChain =
            providers
                .environmentVariable("CERTIFICATE_CHAIN")
                .orElse(providers.gradleProperty("certificateChain"))
        privateKey =
            providers
                .environmentVariable("PRIVATE_KEY")
                .orElse(providers.gradleProperty("privateKey"))
        password =
            providers
                .environmentVariable("PRIVATE_KEY_PASSWORD")
                .orElse(providers.gradleProperty("privateKeyPassword"))
    }
}

// --- Packaging wire (Plan 07, D-07): bundle the agent fat-jar into the plugin's resources so
// buildPlugin produces a self-contained distributable zip. ---

// Agent fat-jar (Plan 01, coroutines scope resolved by the Task 1 spike — BUNDLED, un-relocated):
// copy the already-correct :coroutine-viz-agent:shadowJar output to /agent/coroutine-viz-agent.jar.
val agentJar = project(":coroutine-viz-agent").tasks.named("shadowJar")

tasks.named<ProcessResources>("processResources") {
    dependsOn(agentJar)
    from(agentJar) {
        into("agent")
        rename { "coroutine-viz-agent.jar" }
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
