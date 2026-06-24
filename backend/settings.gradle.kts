rootProject.name = "backend"

include("coroutine-viz-core")
include("coroutine-viz-client")
include("intellij-plugin")
project(":intellij-plugin").projectDir = file("../intellij-plugin")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
