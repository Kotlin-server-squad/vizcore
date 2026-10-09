/**
 * The copy-paste setup the Connect wizard shows (#126).
 *
 * Single source of truth for the published client-library coordinates and the
 * `VizcoreClient.start(...)` call. Both are guarded against the real sources by
 * connect-snippets.test.ts, which reads
 * `backend/coroutine-viz-client/build.gradle.kts` and `VizcoreClient.kt` — so a
 * signature or coordinate change fails the frontend build instead of shipping a
 * snippet that does not compile.
 */

/** Released SDK version. The ONE place the wizard's version string comes from. */
export const SDK_VERSION = '0.1.0'

/** Maven coordinates (mirror `coroutine-viz-client/build.gradle.kts` publishing). */
export const SDK_GROUP = 'com.jh.coroutine-visualizer'
export const SDK_ARTIFACT = 'coroutine-viz-client'

/** The GitHub Packages repository the SDK is published to. */
export const SDK_REPOSITORY_URL =
  'https://maven.pkg.github.com/hermanngeorge15/visualizer-for-coroutines'

/** Fully qualified name of the SDK entry point. */
export const SDK_CLIENT_FQN = 'com.jh.proj.coroutineviz.client.VizcoreClient'

/** Placeholder app name the user is expected to replace. */
export const APP_NAME_PLACEHOLDER = 'my-app'

/** build.gradle.kts additions: the repository (GitHub Packages needs credentials) + dependency. */
export function dependencySnippet(version: string = SDK_VERSION): string {
  return [
    'repositories {',
    '    mavenCentral()',
    '    maven {',
    `        url = uri("${SDK_REPOSITORY_URL}")`,
    '        credentials { // GitHub user + token with read:packages',
    '            username = System.getenv("GITHUB_ACTOR")',
    '            password = System.getenv("GITHUB_TOKEN")',
    '        }',
    '    }',
    '}',
    '',
    'dependencies {',
    `    implementation("${SDK_GROUP}:${SDK_ARTIFACT}:${version}")`,
    '}',
  ].join('\n')
}

export interface StartSnippetOptions {
  appName: string
  /** Base URL of the vizcore server the SDK should stream to. */
  backendUrl: string
  /**
   * True when this server requires authentication. The SDK then needs a real
   * token, read from the environment; with auth off an empty token is accepted.
   */
  authRequired: boolean
  /** One-off token that ties the app's session to this wizard. */
  correlation: string
}

/** Escape a value for a Kotlin string literal. */
function kotlinString(value: string): string {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$')}"`
}

/**
 * The `VizcoreClient.start(...)` call, with every required argument of the
 * current SDK signature:
 * `start(appName: String, backendUrl: String, token: String, correlation: String? = null)`.
 */
export function startSnippet({ appName, backendUrl, authRequired, correlation }: StartSnippetOptions): string {
  const name = appName.trim() || APP_NAME_PLACEHOLDER
  const tokenLine = authRequired
    ? '    token = System.getenv("VIZCORE_TOKEN") ?: error("Set VIZCORE_TOKEN"),'
    : '    token = "", // this vizcore server runs without auth'
  return [
    `import ${SDK_CLIENT_FQN}`,
    '',
    '// Call once at application startup (e.g. first line of main).',
    'val vizcore = VizcoreClient.start(',
    `    appName = ${kotlinString(name)},`,
    `    backendUrl = ${kotlinString(backendUrl)},`,
    tokenLine,
    `    correlation = ${kotlinString(correlation)},`,
    ')',
    'Runtime.getRuntime().addShutdownHook(Thread { vizcore.stop() })',
  ].join('\n')
}
