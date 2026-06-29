package com.jh.coroutinevisualizer.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Project-scoped owner of the [LoopbackFrontendServer] lifecycle.
 *
 * This closes the wiring gap found by the Phase 13 code-review + verification (CR-01): the server
 * class existed and was unit-tested in isolation, but NO production code path ever started it, so
 * `VizcoreLaunchState.port` stayed `null` and the tool window built a dead `http://127.0.0.1:0/...`
 * URL. [ensureStarted] lazily binds the loopback server on the first "Run with Coroutine
 * Visualizer" launch and returns its bound ephemeral port; a later launch reuses the running
 * server, or restarts it when the configured backend URL changed (so the single-target `/api`
 * reverse-proxy never points at a stale backend). The server is stopped when the project is
 * disposed (this service is a project [Disposable]).
 *
 * Registered automatically as a light service via [Service] — no `plugin.xml` entry needed.
 */
@Service(Service.Level.PROJECT)
class LoopbackServerService : Disposable {
    private val logger = Logger.getInstance(LoopbackServerService::class.java)

    @Volatile
    private var server: LoopbackFrontendServer? = null

    @Volatile
    private var startedBackendUrl: String? = null

    /**
     * Start the loopback server (if not already running for [backendUrl]) and return its bound
     * ephemeral port. Restarts the server when [backendUrl] differs from the running instance so a
     * settings change is honored. `synchronized` so two concurrent launches cannot double-bind.
     */
    @Synchronized
    fun ensureStarted(backendUrl: String): Int {
        val running = server
        if (running != null && running.isRunning && startedBackendUrl == backendUrl) {
            return running.port
        }
        running?.stop()
        val fresh = LoopbackFrontendServer(backendUrl)
        fresh.start()
        server = fresh
        startedBackendUrl = backendUrl
        logger.info("Loopback frontend server ensured on port ${fresh.port} → backend $backendUrl")
        return fresh.port
    }

    /** The bound port if the server is currently running, else `null` (no launch armed yet). */
    fun portOrNull(): Int? = server?.takeIf { it.isRunning }?.port

    override fun dispose() {
        server?.stop()
        server = null
    }

    companion object {
        fun getInstance(project: Project): LoopbackServerService = project.getService(LoopbackServerService::class.java)
    }
}
