package com.jh.proj.coroutineviz.agent

import com.jh.proj.coroutineviz.client.VizcoreClient
import java.lang.instrument.Instrumentation

/**
 * Launch-time Java agent (IDE-01, D-01/D-03).
 *
 * Attached to a target JVM via `-javaagent:coroutine-viz-agent-<ver>-all.jar=k=v,...`,
 * the JVM invokes [premain] before the application's `main`. The premain is a THIN
 * wrapper: it parses the comma-separated `k=v` arg string and delegates to the existing
 * [VizcoreClient.start], which ALREADY constructs and drives a `DebugProbesSource`
 * (the zero-code capture path — RESEARCH Contradiction #1). No instrumentation, no
 * bytecode transformation, and NO new capture/bridge code is written here: the agent
 * simply bootstraps the established client with zero changes to the target app's source.
 *
 * Recognised args (all optional, defaulted):
 *  - `app`     — application name shown in vizcore (default `"app"`)
 *  - `backend` — backend base URL (default `"http://localhost:8080"`)
 *  - `token`   — dev JWT for session create (default `""`)
 *  - `corr`    — optional correlation token so a watching IDE can resolve this session
 *
 * The returned [VizcoreClient] is intentionally NOT captured: it owns a private
 * `SupervisorJob` scope (never `GlobalScope`, CLAUDE.md) that keeps it alive for the
 * JVM's lifetime.
 */
object VizcoreAgent {
    /**
     * Java agent entry point. Called by the JVM at launch (NOT reflectively by app code).
     *
     * @param agentArgs the raw `=`-suffixed arg string from `-javaagent:jar=<here>` (nullable)
     * @param inst the JVM-supplied [Instrumentation] handle (unused — we do not transform bytecode)
     */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun premain(
        agentArgs: String?,
        inst: Instrumentation,
    ) {
        val args = parseArgs(agentArgs)
        VizcoreClient.start(
            appName = args["app"] ?: "app",
            backendUrl = args["backend"] ?: "http://localhost:8080",
            token = args["token"] ?: "",
            correlation = args["corr"],
        )
    }

    /**
     * Parse the comma-separated `k=v` agent arg string into a map (malformed-tolerant,
     * RESEARCH Security V5 / T-13-01). Tokens lacking `=` are dropped (never throw); keys
     * and values are trimmed. `null`/blank input yields an empty map so [premain] falls
     * back entirely to defaults.
     *
     * `internal` so the arg-parse unit test can exercise it directly without invoking
     * [premain] (which would attempt a real network start).
     */
    internal fun parseArgs(agentArgs: String?): Map<String, String> =
        agentArgs
            ?.split(',')
            ?.filter { it.contains('=') }
            ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            ?: emptyMap()
}
