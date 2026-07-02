package com.jh.coroutinevisualizer.poll

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.jh.coroutinevisualizer.api.VizcoreApiClient
import com.jh.coroutinevisualizer.model.SessionModel
import com.jh.coroutinevisualizer.run.VizcoreRunConfigurationExtension
import com.jh.coroutinevisualizer.settings.VizcoreSettings
import com.jh.coroutinevisualizer.toolwindow.ViewMode

/**
 * Project-scoped owner of the live poll loop (replaces the deleted loopback/browser path). Builds a
 * [VizcoreApiClient] from settings, runs a [SessionPoller], and delivers each [SessionModel] to the
 * registered listener on the EDT. Stops the poller on project dispose.
 */
@Service(Service.Level.PROJECT)
class SessionPollingService : Disposable {
    @Volatile private var poller: SessionPoller? = null

    @Volatile private var listener: ((SessionModel) -> Unit)? = null

    @Volatile private var errorListener: ((Throwable) -> Unit)? = null

    fun setListener(
        onModel: (SessionModel) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        listener = onModel
        errorListener = onError
    }

    fun start(correlation: String) {
        poller?.stop()
        val settings = VizcoreSettings.getInstance()
        val client = VizcoreApiClient(settings.backendUrl, VizcoreRunConfigurationExtension.AGENT_TOKEN)
        // pollIntervalMs is already clamped by VizcoreSettings; fall back if it ever reads non-positive.
        val interval = settings.pollIntervalMs.toLong().takeIf { it > 0 } ?: DEFAULT_INTERVAL_MS
        val fresh = SessionPoller(client, interval)
        poller = fresh
        fresh.start(
            correlation,
            onModel = { model -> ApplicationManager.getApplication().invokeLater { listener?.invoke(model) } },
            onError = { e -> ApplicationManager.getApplication().invokeLater { errorListener?.invoke(e) } },
        )
    }

    /**
     * Switches the live-view mode, retuning the REAL poll cadence: LIVE keeps the settings interval,
     * ALL drops to the slow [ViewMode.ALL_INTERVAL_MS] (D-19). The active session is retained — start()
     * is not rebuilt, so no re-resolve and no second poller thread (Pitfall 5). No-op before start().
     */
    fun setMode(mode: ViewMode) {
        val live =
            VizcoreSettings
                .getInstance()
                .pollIntervalMs
                .toLong()
                .takeIf { it > 0 } ?: DEFAULT_INTERVAL_MS
        poller?.setInterval(ViewMode.intervalMsFor(mode, live))
    }

    /** The resolved session id for the active poll, or null until the agent binds the correlation. */
    fun currentSessionId(): String? = poller?.currentSessionId()

    fun freeze() {
        poller?.freeze()
    }

    fun unfreeze() {
        poller?.unfreeze()
    }

    override fun dispose() {
        poller?.stop()
        poller = null
    }

    companion object {
        private const val DEFAULT_INTERVAL_MS = 200L

        fun getInstance(project: Project): SessionPollingService = project.getService(SessionPollingService::class.java)
    }
}
