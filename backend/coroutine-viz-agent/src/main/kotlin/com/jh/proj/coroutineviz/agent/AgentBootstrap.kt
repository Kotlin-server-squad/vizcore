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
 *  - `token`   — the backend credential, an API key or a JWT (default `""`, enough when auth is off)
 *  - `corr`    — optional correlation token so a watching IDE can resolve this session
 *  - `poll`    — sampling interval in milliseconds (default 150); shorter catches shorter coroutines
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
    fun run(agentArgs: String?) {
        start(agentArgs)
    }

    /**
     * [run], returning the started client (or `null` when the agent disabled itself) so a test
     * can stop it.
     *
     * A backend that is down does not disable the agent (#254): [VizcoreClient.start] returns a
     * client that creates the session once the backend answers. The agent says so on stderr,
     * because the client's own WARN, which carries the cause, goes through the agent's shaded
     * SLF4J, which has no logger to write to. What still disables it is an app runtime the SDK
     * cannot run on.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun start(agentArgs: String?): VizcoreClient? {
        val args = parseArgs(agentArgs)
        val backendUrl = args["backend"] ?: "http://localhost:8080"
        return try {
            VizcoreClient
                .start(
                    appName = args["app"] ?: "app",
                    backendUrl = backendUrl,
                    token = args["token"] ?: "",
                    pollIntervalMillis = pollIntervalMillis(args["poll"]),
                    correlation = args["corr"],
                ).also { client ->
                    if (client.sessionIdOrNull == null) {
                        // Says only what is known: the cause is in the client's WARN, which the agent's
                        // shaded SLF4J has nowhere to write.
                        System.err.println(
                            "[coroutine-viz-agent] could not create a session at $backendUrl yet (is the backend " +
                                "up, the URL right and the token accepted?); the app runs normally and the agent " +
                                "keeps trying in the background",
                        )
                    }
                }
        } catch (failure: Throwable) {
            // FAIL SOFT — the agent must NEVER take the host application down. Log once and let
            // the app run uninstrumented; the visualizer simply shows no session (8c7d67c).
            System.err.println(
                "[coroutine-viz-agent] DISABLED — bootstrap against $backendUrl failed: ${failure.message}",
            )
            null
        }
    }

    /** The `poll` arg as milliseconds; the default when absent, and a message plus the default when unusable. */
    internal fun pollIntervalMillis(arg: String?): Long {
        if (arg == null) return VizcoreClient.DEFAULT_POLL_INTERVAL_MILLIS
        val millis = arg.trim().toLongOrNull()
        if (millis != null && millis >= VizcoreClient.MIN_POLL_INTERVAL_MILLIS) return millis
        System.err.println(
            "[coroutine-viz-agent] poll=$arg ignored: expected milliseconds, at least " +
                "${VizcoreClient.MIN_POLL_INTERVAL_MILLIS}; using ${VizcoreClient.DEFAULT_POLL_INTERVAL_MILLIS}",
        )
        return VizcoreClient.DEFAULT_POLL_INTERVAL_MILLIS
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
