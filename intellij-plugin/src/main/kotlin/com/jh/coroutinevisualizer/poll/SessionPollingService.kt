package com.jh.coroutinevisualizer.poll

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.jh.coroutinevisualizer.api.VizcoreApiClient
import com.jh.coroutinevisualizer.model.SessionModel
import com.jh.coroutinevisualizer.run.VizcoreRunConfigurationExtension
import com.jh.coroutinevisualizer.settings.VizcoreSettings

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
        // TODO(T10): read VizcoreSettings.pollIntervalMs once that field exists.
        val fresh = SessionPoller(client, DEFAULT_INTERVAL_MS)
        poller = fresh
        fresh.start(
            correlation,
            onModel = { model -> ApplicationManager.getApplication().invokeLater { listener?.invoke(model) } },
            onError = { e -> ApplicationManager.getApplication().invokeLater { errorListener?.invoke(e) } },
        )
    }

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
