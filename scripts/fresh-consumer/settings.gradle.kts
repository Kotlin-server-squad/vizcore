// Throwaway consumer project — proves a REAL external consumer can resolve the
// locked client coordinate. It has its OWN settings.gradle.kts (NOT part of the
// backend composite build) so it resolves coroutine-viz-client as a published
// external artifact, not a project dependency.
//
// Resolution proof:
//   - mavenLocal() (listed FIRST) proves it NOW, after scripts/verify-pom.sh has
//     run publishToMavenLocal for 0.1.0 (agent-runnable staging proof).
//   - The GitHub Packages repo proves it AFTER the human remote publish (Task 4);
//     comment out mavenLocal() to prove the remote path in isolation.
//
// Run:
//   cd scripts/fresh-consumer
//   gradle dependencies --configuration runtimeClasspath
//   # post-publish remote proof:
//   GITHUB_ACTOR=<user> GITHUB_TOKEN=<read:packages-PAT> \
//     gradle dependencies --configuration runtimeClasspath --refresh-dependencies

rootProject.name = "fresh-consumer"

dependencyResolutionManagement {
    repositories {
        // Agent-runnable staging proof: resolves the 0.1.0 published locally by
        // scripts/verify-pom.sh (publishToMavenLocal).
        mavenLocal()
        mavenCentral()
        // Post-human-publish remote proof (Task 4): GitHub Packages.
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
