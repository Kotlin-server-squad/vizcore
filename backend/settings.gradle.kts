rootProject.name = "backend"

// Pin the Shadow fat-JAR plugin to EXACTLY 9.4.3 (SDK-02, T-11-SC supply-chain guarantee).
// It is pinned here rather than inline in :coroutine-viz-cli because the IntelliJ Platform
// plugin already places Shadow on the build classpath with an "unknown version", which makes
// an inline `id(...) version "9.4.3"` fail resolution. pluginManagement controls the version
// for the unversioned `id("com.gradleup.shadow")` applied in the CLI module build file.
pluginManagement {
    plugins {
        id("com.gradleup.shadow") version "9.4.3"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include("coroutine-viz-core")
include("coroutine-viz-client")
include("coroutine-viz-cli")
include("intellij-plugin")
project(":intellij-plugin").projectDir = file("../intellij-plugin")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
