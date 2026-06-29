package com.jh.coroutinevisualizer.toolwindow

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-scoped holder for the live-view coordinates the tool window needs:
 * the [LoopbackFrontendServer]'s bound ephemeral port and the active correlation UUID.
 *
 * The "Run with Coroutine Visualizer" action (Plan 06) arms this just before it opens the
 * tool window; [VizcoreToolWindowFactory] reads it to build the deep-linked URL. Until an
 * armed launch exists both fields are `null` and the factory shows a "not launched yet"
 * fallback rather than a stale URL.
 *
 * This is a thin handoff seam introduced in Plan 05 so the factory has a defined source for
 * (port, correlation); the action wiring that populates it lands in Plan 06.
 */
@Service(Service.Level.PROJECT)
class VizcoreLaunchState {
    @Volatile
    var port: Int? = null
        private set

    @Volatile
    var correlation: String? = null
        private set

    /** Arm the live-view coordinates for the next tool-window open (called by the launch action). */
    fun arm(
        port: Int,
        correlation: String,
    ) {
        this.port = port
        this.correlation = correlation
    }

    /** Clear the armed coordinates (e.g. when the launched process ends). */
    fun clear() {
        this.port = null
        this.correlation = null
    }

    /** The deep-linked loopback URL, or `null` if no launch is currently armed. */
    fun viewUrl(): String? {
        val p = port ?: return null
        val c = correlation ?: return null
        return VizcoreViewUrl.build(p, c)
    }

    companion object {
        fun getInstance(project: Project): VizcoreLaunchState = project.getService(VizcoreLaunchState::class.java)
    }
}
