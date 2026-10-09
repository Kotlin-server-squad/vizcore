// Throwaway consumer project — proves a REAL external consumer can resolve the
// locked client coordinate. It has its OWN settings.gradle.kts (NOT part of the
// backend composite build) so it resolves coroutine-viz-client as a published
// external artifact, not a project dependency.
//
// Resolution proof:
//   - The staging proof opts IN to mavenLocal() via -PuseLocal, after
//     scripts/verify-pom.sh has run publishToMavenLocal for 0.1.0
//     (agent-runnable staging proof).
//   - The REMOTE proof (GitHub Packages) is the DEFAULT. Because 0.1.0 is an
//     immutable coordinate, leaving mavenLocal() always-on would let a stale
//     ~/.m2 copy permanently mask a remote that was never published (or was
//     published with a broken POM) — silently degrading SC#1 into a local-cache
//     proof. So mavenLocal() is OFF unless explicitly requested.
//
// Run:
//   cd scripts/fresh-consumer
//   # staging (local) proof — opt in:
//   gradle dependencies --configuration runtimeClasspath -PuseLocal
//   # post-publish REMOTE proof (default; force a fresh fetch from GitHub Packages):
//   GITHUB_ACTOR=<user> GITHUB_TOKEN=<read:packages-PAT> \
//     gradle dependencies --configuration runtimeClasspath --refresh-dependencies

rootProject.name = "fresh-consumer"

dependencyResolutionManagement {
    repositories {
        // Agent-runnable staging proof: resolves the 0.1.0 published locally by
        // scripts/verify-pom.sh (publishToMavenLocal). Gated behind -PuseLocal so
        // the remote path is the default and a stale local artifact cannot mask a
        // broken remote publish.
        if (providers.gradleProperty("useLocal").isPresent) {
            mavenLocal()
        }
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
