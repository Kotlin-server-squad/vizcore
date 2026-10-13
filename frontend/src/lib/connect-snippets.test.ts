import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import {
  SDK_ARTIFACT,
  SDK_CLIENT_FQN,
  SDK_GROUP,
  SDK_REPOSITORY_URL,
  SDK_VERSION,
  dependencySnippet,
  startSnippet,
} from './connect-snippets'

/**
 * Guards the Connect wizard's snippets against the REAL SDK sources (#126).
 *
 * The wizard once shipped `VizcoreClient.start(appName = …, correlation = …)`,
 * which does not compile: `backendUrl` and `token` have no defaults. These
 * tests read the Kotlin source and the client's build script from the repo and
 * fail as soon as the generated snippet and the SDK disagree.
 */

const here = dirname(fileURLToPath(import.meta.url))
const clientModule = resolve(here, '../../../backend/coroutine-viz-client')
const clientSource = readFileSync(
  resolve(clientModule, 'src/main/kotlin/com/jh/proj/coroutineviz/client/VizcoreClient.kt'),
  'utf8',
)
const buildScript = readFileSync(resolve(clientModule, 'build.gradle.kts'), 'utf8')

interface KotlinParam {
  name: string
  type: string
  hasDefault: boolean
}

/** Parameters of the companion `fun start(...)` that returns a VizcoreClient. */
function sdkStartParams(): KotlinParam[] {
  const match = /fun\s+start\s*\(([^)]*)\)\s*:\s*VizcoreClient/.exec(clientSource)
  if (!match) throw new Error('VizcoreClient.start(...): VizcoreClient factory not found')
  return match[1]!
    .split(',')
    .map(p => p.trim())
    .filter(Boolean)
    .map(p => {
      const m = /^(\w+)\s*:\s*([\w.?<>]+)\s*(=.*)?$/.exec(p)
      if (!m) throw new Error(`Unparseable parameter: ${p}`)
      return { name: m[1]!, type: m[2]!, hasDefault: m[3] !== undefined }
    })
}

/** Named arguments of the `VizcoreClient.start(...)` call in a snippet. */
function snippetArgs(snippet: string): Map<string, string> {
  const body = /VizcoreClient\.start\(([\s\S]*?)\n\)/.exec(snippet)
  if (!body) throw new Error('snippet has no VizcoreClient.start( … ) call')
  const args = new Map<string, string>()
  for (const raw of body[1]!.split('\n')) {
    const line = raw.replace(/\/\/.*$/, '').trim().replace(/,$/, '')
    if (!line) continue
    const m = /^(\w+)\s*=\s*(.+)$/.exec(line)
    if (!m) throw new Error(`positional or unparseable argument: ${raw}`)
    args.set(m[1]!, m[2]!)
  }
  return args
}

const snippet = startSnippet({
  appName: 'my-app',
  backendUrl: 'http://localhost:8080',
  authRequired: false,
  correlation: '00000000-0000-4000-8000-000000000000',
})

describe('start snippet vs the SDK signature', () => {
  const params = sdkStartParams()

  it('reads the expected signature shape from VizcoreClient.kt', () => {
    expect(params.map(p => p.name)).toContain('appName')
    expect(params.length).toBeGreaterThanOrEqual(3)
  })

  it('passes every parameter that has no default', () => {
    const args = snippetArgs(snippet)
    for (const p of params.filter(p => !p.hasDefault)) {
      expect(args.has(p.name), `missing required argument "${p.name}"`).toBe(true)
    }
  })

  it('passes only parameters that exist, with String-typed values for String parameters', () => {
    const args = snippetArgs(snippet)
    const byName = new Map(params.map(p => [p.name, p]))
    for (const [name, value] of args) {
      const param = byName.get(name)
      expect(param, `unknown argument "${name}"`).toBeDefined()
      if (param!.type.replace('?', '') === 'String') {
        expect(value, `${name} must be a Kotlin string expression`).toMatch(/^"|^System\.getenv\(/)
      }
    }
  })

  it('also covers the auth-required variant', () => {
    const args = snippetArgs(
      startSnippet({ appName: 'x', backendUrl: 'http://h:1', authRequired: true, correlation: 'c' }),
    )
    for (const p of params.filter(p => !p.hasDefault)) expect(args.has(p.name)).toBe(true)
    expect(args.get('token')).toContain('VIZCORE_TOKEN')
  })

  it('imports the class from the package it is declared in', () => {
    const pkg = /^package\s+([\w.]+)/m.exec(clientSource)![1]
    expect(SDK_CLIENT_FQN).toBe(`${pkg}.VizcoreClient`)
    expect(snippet).toContain(`import ${SDK_CLIENT_FQN}`)
  })

  it('uses the backend URL and correlation it was given, and escapes the app name', () => {
    expect(snippet).toContain('backendUrl = "http://localhost:8080"')
    expect(snippet).toContain('correlation = "00000000-0000-4000-8000-000000000000"')
    expect(snippet).toContain('token = ""')
    const tricky = startSnippet({ appName: 'a"b$c', backendUrl: 'u', authRequired: false, correlation: 'c' })
    expect(tricky).toContain('appName = "a\\"b\\$c"')
  })
})

describe('dependency snippet vs the client build script', () => {
  it('uses the published group, artifact and version', () => {
    expect(buildScript).toContain(`groupId = "${SDK_GROUP}"`)
    expect(buildScript).toContain(`artifactId = "${SDK_ARTIFACT}"`)
    expect(buildScript).toMatch(new RegExp(`^version = "${SDK_VERSION.replace(/\./g, '\\.')}"`, 'm'))
    expect(dependencySnippet()).toContain(`implementation("${SDK_GROUP}:${SDK_ARTIFACT}:${SDK_VERSION}")`)
  })

  it('includes the repository block the artifact is published to, with credentials', () => {
    expect(buildScript).toContain(`url = uri("${SDK_REPOSITORY_URL}")`)
    const dep = dependencySnippet()
    expect(dep).toContain(`url = uri("${SDK_REPOSITORY_URL}")`)
    expect(dep).toMatch(/credentials\s*\{[\s\S]*username[\s\S]*password[\s\S]*\}/)
  })
})
