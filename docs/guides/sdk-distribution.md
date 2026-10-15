# SDK Distribution

How to publish, consume, and run the Coroutine Visualizer SDK — the embeddable
client library (`coroutine-viz-client`), the validation engine (`coroutine-viz-core`),
and the standalone CLI validator (`coroutine-viz-cli`).

All three share ONE locked Maven coordinate group: `com.jh.coroutine-visualizer`,
version `0.2.1`. The client coordinate is the single source of truth in
[`frontend/src/lib/connect-snippets.ts`](../../frontend/src/lib/connect-snippets.ts), which
the connect wizard shows; its guard test fails when that and the build scripts disagree.

---

## 1. Consume the published client (add the dependency)

The client library is published to **GitHub Packages**. Add the dependency with
the locked coordinate:

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.jh.coroutine-visualizer:coroutine-viz-client:0.2.1")
}
```

GitHub Packages requires authentication even for reads. Add the registry as a
repository, supplying your GitHub username and a **`read:packages`** token via
environment variables (never commit the token):

```kotlin
// settings.gradle.kts (or build.gradle.kts repositories {})
repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/Kotlin-server-squad/vizcore")
        credentials {
            username = System.getenv("GITHUB_ACTOR")   // your GitHub username
            password = System.getenv("GITHUB_TOKEN")   // a PAT with read:packages
        }
    }
}
```

```bash
export GITHUB_ACTOR=<your-github-username>
export GITHUB_TOKEN=<a-read:packages-PAT>
```

---

## 2. Publish (maintainer)

Pushing a `v*` release tag publishes core and client: `release.yml` calls
`publish-maven.yml` **before** it creates the GitHub Release, so a release never
exists without its SDK. The SDK keeps its own version (`0.x`, from
`build.gradle.kts`), independent of the app's release tag (`v1.x`). A version
that is already published is skipped, so a release that does not change the SDK
still goes through; bump the version in both build scripts and in
`connect-snippets.ts` to ship SDK changes.

`publish-maven.yml` can also be run by hand (Actions → Publish to Maven →
Run workflow). Publishing from a workstation needs a `write:packages` PAT:

```bash
cd backend
GITHUB_ACTOR=<your-github-username> \
GITHUB_TOKEN=<a-write:packages-PAT> \
./gradlew :coroutine-viz-core:publish :coroutine-viz-client:publish
```

Expect `BUILD SUCCESSFUL`; both `0.2.1` artifacts appear under the repository's
**Packages**.

> **GitHub Packages `0.2.1` is IMMUTABLE.** Once published, you cannot
> re-publish the same version over a wrong POM/jar — you must delete the version
> in the Packages UI first (a 409 on re-publish; see RESEARCH Pitfall 5). To
> avoid this, the POM and coordinate are asserted **locally first** via
> [`scripts/verify-pom.sh`](../../scripts/verify-pom.sh) (publishes to
> `~/.m2` and greps the resulting POM) before the immutable remote publish.

The token is read by the build via `System.getenv` (`GITHUB_ACTOR` /
`GITHUB_TOKEN`) — supply it via env only, never echo it or commit it (T-11-07).

---

## 3. Run the CLI validator

The CLI is a self-contained Shadow fat JAR. Build it:

```bash
cd backend
./gradlew :coroutine-viz-cli:shadowJar
# produces: backend/coroutine-viz-cli/build/libs/coroutine-viz-cli-0.2.1-all.jar
```

Run it against a recorded events export:

```bash
java -jar coroutine-viz-cli-0.2.1-all.jar <path/to/events.json>
```

- **Exit 0** — clean (no validation failures, no ERROR/WARNING anti-patterns).
- **Exit 1** — at least one validation failure or an ERROR/WARNING anti-pattern.
- **Exit 2** — usage error (no path given) or the input could not be parsed.

The input file is the **body of `GET /api/sessions/{id}/events`** — a bare JSON
array (`List<VizEvent>`) using the polymorphic `type` discriminator. The CLI
decodes it with core's shared `appJson` serializer and drives the **exact same**
validators and anti-pattern detector the backend route uses, so there is **zero
rule duplication** — the CLI is the published engine, not a re-implementation.

---

## 4. The `coroutineVizCheck` consumer task (zero rule duplication)

To gate **your own** build on the visualizer's rules, paste this task into
**your** project's `build.gradle.kts`. It wraps the published CLI fat JAR as a
Gradle `JavaExec` task. A non-zero CLI exit propagates as a Gradle task failure,
so a rule violation fails your build.

> This snippet is **documentation for a consumer to paste into THEIR build** —
> it is **not** a build-file edit in this repository. It drives the published
> engine, so it never duplicates a single rule (D-06, SDK-03).

```kotlin
// In a CONSUMER project's build.gradle.kts:
tasks.register<JavaExec>("coroutineVizCheck") {
    group = "verification"
    description = "Validate a recorded coroutine events export against the visualizer rules."
    // Point at the published CLI fat JAR (download it, or copy it into libs/).
    classpath = files("libs/coroutine-viz-cli-0.2.1-all.jar")
    mainClass.set("com.jh.proj.coroutineviz.cli.MainKt")
    // The events.json export to validate (body of GET /api/sessions/{id}/events).
    args(layout.projectDirectory.file("events.json").asFile.absolutePath)
}
```

```bash
./gradlew coroutineVizCheck   # non-zero CLI exit -> Gradle build failure
```

---

## 5. Local pre-push guard (bytecode purity)

Before pushing, verify the published modules stay on the JVM-17 floor and core
stays free of `io.ktor` (so the SDK never drags a web framework into a
consumer):

```bash
cd backend
./gradlew checkBytecode
```

`checkBytecode` scans the compiled `coroutine-viz-core` and `coroutine-viz-client`
class files (fails on any class above the JVM-17 major version) and greps core's
sources for `import io.ktor` lines. It is wired into `check`, and runs in CI on a
JDK 17 + 21 matrix.
