rootProject.name = "backend"

// Pin the Shadow fat-JAR plugin to EXACTLY 9.4.3 (SDK-02, T-11-SC supply-chain guarantee).
// It is pinned here rather than inline in :coroutine-viz-cli because the IntelliJ Platform
// plugin places Shadow on the build classpath with an "unknown version" whenever the plugin
// is opted in with -Pvizcore.withPlugin=true (see the include below), which makes an inline
// `id(...) version "9.4.3"` fail resolution in that configuration. The pin therefore stays
// even though the plugin is excluded by default: pluginManagement controls the version for
// the unversioned `id("com.gradleup.shadow")` applied in the CLI module build file.
pluginManagement {
    plugins {
        id("com.gradleup.shadow") version "9.6.1"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include("coroutine-viz-core")
include("coroutine-viz-client")
include("coroutine-viz-cli")
include("coroutine-viz-agent")
// The IntelliJ plugin is postponed (not part of v1.2, #130) and is NOT part of the default
// build: it is only configured when the build is explicitly opted in. Everything that would
// otherwise pull the IntelliJ Platform plugin onto the classpath — and the JetBrains
// toolchain it resolves — stays out of `./gradlew build`, `test`, `ktlintCheck` and `detekt`
// unless -Pvizcore.withPlugin=true is passed. CI builds it from ci-plugin.yml only.
if (providers.gradleProperty("vizcore.withPlugin").orNull == "true") {
    include("intellij-plugin")
    project(":intellij-plugin").projectDir = file("../intellij-plugin")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
