# IntelliJ Plugin Distribution Runbook

How to build, verify, sign, and publish the **Coroutine Visualizer** IntelliJ plugin
(`intellij-plugin`, plugin id `com.jh.coroutine-visualizer`).

The plugin is a *delivery vehicle*: `buildPlugin` bundles the `coroutine-viz-agent` fat-jar
(into `/agent/coroutine-viz-agent.jar`) and the built frontend SPA (into `/frontend`) inside
the plugin jar, so the distributable is self-contained — no separate agent/frontend download.

> All Gradle commands run from the `backend/` composite root with **JDK 21**:
> `export JAVA_HOME=<path-to-jdk-21>`

## 1. Build the distributable

```bash
cd backend
./gradlew :intellij-plugin:buildPlugin
```

Produces `intellij-plugin/build/distributions/intellij-plugin-<version>.zip`.

`buildPlugin` transitively runs:
- `:coroutine-viz-agent:shadowJar` → copied to `/agent/coroutine-viz-agent.jar` in the plugin jar.
- `pnpmBuild` (`pnpm build` in `frontend/`, vite → `frontend/dist`, base `/`) → copied to `/frontend`.

If `pnpm build` fails on missing deps, run `pnpm install --frozen-lockfile` in `frontend/` once.

Verify the zip is self-contained (the resources live inside `lib/intellij-plugin-<version>.jar`):

```bash
ZIP=intellij-plugin/build/distributions/intellij-plugin-*.zip
JAR=$(unzip -Z1 $ZIP | grep 'lib/intellij-plugin-.*\.jar$')
unzip -p $ZIP "$JAR" | jar tf /dev/stdin | grep -E 'agent/coroutine-viz-agent.jar|frontend/index.html'
```

## 2. Verify compatibility (the gate)

```bash
cd backend
./gradlew :intellij-plugin:verifyPlugin
```

`verifyPlugin` runs JetBrains' plugin verifier against the IDE range declared in
`intellij-plugin/build.gradle.kts`:

- `sinceBuild = 241` (IDEA 2024.1) — the floor.
- `untilBuild = "251.*"` (IDEA 2025.1.x) — the ceiling.

It must report **Compatible** (warnings about deprecated/experimental/internal API are
acceptable; a *compatibility problem* fails the gate). The plugin declares
`<depends>com.intellij.modules.java</depends>` because the run-configuration extension uses
the Java plugin's `RunConfigurationExtension` / `JavaParameters`; without it the verifier
fails. When bumping `untilBuild`, re-run `verifyPlugin` against the new range first.

## 3. Sign (optional, keys from env secrets only)

Signing is configured via the IntelliJ Platform Gradle plugin's `signing { }` block, which
reads key material from environment variables (or matching Gradle properties) — **never from
committed files** (V6):

| Variable | Gradle property | Meaning |
|----------|-----------------|---------|
| `CERTIFICATE_CHAIN`     | `certificateChain`    | PEM certificate chain |
| `PRIVATE_KEY`           | `privateKey`          | PEM private key |
| `PRIVATE_KEY_PASSWORD`  | `privateKeyPassword`  | private-key passphrase |

```bash
cd backend
CERTIFICATE_CHAIN="$(cat chain.crt)" \
PRIVATE_KEY="$(cat private.pem)" \
PRIVATE_KEY_PASSWORD="$PASSPHRASE" \
./gradlew :intellij-plugin:signPlugin
```

When these are unset, `signPlugin` is skipped and `buildPlugin` still produces an **unsigned**
distributable zip (suitable for local install via *Settings → Plugins → Install from Disk*).
Do **not** commit keys, certificates, or passphrases.

## 4. Publish to the JetBrains Marketplace — HUMAN step (not automated)

Marketplace upload is **out of scope for the agent / CI** (D-12), mirroring the SDK's
human remote-publish precedent (Phase 11, see `docs/guides/sdk-distribution.md`). A human with
a JetBrains Marketplace vendor account performs it:

1. Build + verify + sign (steps 1–3) to produce a signed zip.
2. Upload the signed `intellij-plugin-<version>.zip` either:
   - manually at <https://plugins.jetbrains.com/> (vendor portal → *Upload update*), or
   - with a one-off `publishPlugin` run supplying a `PUBLISH_TOKEN` (a Marketplace permanent
     token) — **not wired into this build** by design; add it locally only when publishing.

There is no `publishPlugin` token configured in the repo and no on-tag publish automation.

## Security note — development tool

The embedded live view surfaces coroutine **creation-stack source paths**, which are
development/debugging information. Point the plugin only at trusted backends; treat the plugin
as a development tool, not a production-exposed surface.
