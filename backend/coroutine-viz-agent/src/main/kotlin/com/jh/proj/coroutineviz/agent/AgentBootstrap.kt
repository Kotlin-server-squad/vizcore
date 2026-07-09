package com.jh.proj.coroutineviz.agent

import com.jh.proj.coroutineviz.client.VizcoreClient

/**
 * Kotlin bootstrap for the launch-time Java agent (IDE-01, D-01/D-03).
 *
 * Reached NOT directly by the JVM but reflectively by the dependency-free
 * [com.jh.proj.coroutineviz.agent.boot.VizcoreAgentPremain] shim, which loads this object
 * through the child-first [com.jh.proj.coroutineviz.agent.boot.AgentClassLoader] (15-13). By
 * the time [run] executes, the agent's HTTP/serialization stack resolves from the fat jar
 * rather than the target app's system classpath — the durable fix for the exploded-classpath
 * attach corruption (a mixed ktor/serialization stack mis-read Content-Length keep-alive
 * responses into an empty body).
 *
 * [run] is a THIN wrapper: it parses the comma-separated `k=v` arg string and delegates to the
 * existing [VizcoreClient.start], which ALREADY constructs and drives a `DebugProbesSource`
 * (the zero-code capture path — RESEARCH Contradiction #1). No instrumentation, no bytecode
 * transformation, and NO new capture/bridge code is written here.
 *
 * Recognised args (all optional, defaulted):
 *  - `app`     — application name shown in vizcore (default `"app"`)
 *  - `backend` — backend base URL (default `"http://localhost:8080"`)
 *  - `token`   — dev JWT for session create (default `""`)
 *  - `corr`    — optional correlation token so a watching IDE can resolve this session
 *
 * The returned [VizcoreClient] is intentionally NOT captured: it owns a private `SupervisorJob`
 * scope (never `GlobalScope`, CLAUDE.md) that keeps it alive for the JVM's lifetime.
 */
object AgentBootstrap {
    /**
     * Bootstrap entry point, invoked reflectively by the premain shim as
     * `AgentBootstrap.run(agentArgs)` through the child-first loader.
     *
     * Belt-and-braces fail-soft: the shim already wraps this call in `try/catch(Throwable)`,
     * but this inner catch keeps the established `DISABLED` diagnostic (with the backend URL)
     * and guarantees a normal return even if the shim's contract were ever relaxed.
     *
     * @param agentArgs the raw `=`-suffixed arg string from `-javaagent:jar=<here>` (nullable)
     */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun run(agentArgs: String?) {
        val args = parseArgs(agentArgs)
        val backendUrl = args["backend"] ?: "http://localhost:8080"
        try {
            VizcoreClient.start(
                appName = args["app"] ?: "app",
                backendUrl = backendUrl,
                token = args["token"] ?: "",
                correlation = args["corr"],
            )
        } catch (failure: Throwable) {
            // FAIL SOFT — the agent must NEVER take the host application down. Log once and let
            // the app run uninstrumented; the visualizer simply shows no session (8c7d67c).
            System.err.println(
                "[coroutine-viz-agent] DISABLED — bootstrap against $backendUrl failed: ${failure.message}",
            )
        }
    }

    /**
     * Parse the comma-separated `k=v` agent arg string into a map (malformed-tolerant,
     * RESEARCH Security V5 / T-13-01). Tokens lacking `=` are dropped (never throw); keys
     * and values are trimmed. `null`/blank input yields an empty map so [run] falls back
     * entirely to defaults.
     *
     * `internal` so the arg-parse unit test can exercise it directly without invoking [run]
     * (which would attempt a real network start).
     */
    internal fun parseArgs(agentArgs: String?): Map<String, String> =
        agentArgs
            ?.split(',')
            ?.filter { it.contains('=') }
            ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            ?: emptyMap()
}
