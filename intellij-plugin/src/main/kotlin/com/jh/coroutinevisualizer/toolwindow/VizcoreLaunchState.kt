package com.jh.coroutinevisualizer.toolwindow

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

/**
 * Two-faced launch-handoff seam for the "Run with Coroutine Visualizer" flow. There is exactly
 * ONE `VizcoreLaunchState` class on disk; it carries both halves of the handoff:
 *
 * 1. **Project-scoped view coordinates** (Plan 05): the [LoopbackFrontendServer]'s bound ephemeral
 *    port and the active correlation UUID, read by [VizcoreToolWindowFactory] to build the
 *    deep-linked live-view URL. Until a launch is armed both fields are `null` and the factory
 *    shows a "not launched yet" panel rather than a stale URL.
 *
 * 2. **Per-run-configuration armed state** (Plan 06): a one-shot `(armed flag, correlation UUID)`
 *    stashed on the specific [RunConfigurationBase] the user is launching, read by
 *    [com.jh.coroutinevisualizer.run.VizcoreRunConfigurationExtension.updateJavaParameters] so the
 *    agent is injected ONLY for the config armed by the action and the config is not permanently
 *    modified. Stored as user-data on the configuration (companion API) so it is naturally keyed
 *    per config and survives only for that launch.
 *
 * The action ([com.jh.coroutinevisualizer.actions.RunWithVisualizerAction]) mints ONE correlation
 * UUID, [arm]s the project-scoped view coordinates AND [armConfiguration]s the per-config state with
 * that SAME UUID, then triggers the run executor and opens the tool window — guaranteeing the
 * correlation in the agent VM-arg is byte-identical to the one in the view URL (IDE-03, T-13-16).
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
        val p = port
        val c = correlation
        return if (p != null && c != null) VizcoreViewUrl.build(p, c) else null
    }

    companion object {
        fun getInstance(project: Project): VizcoreLaunchState = project.getService(VizcoreLaunchState::class.java)

        /**
         * User-data key holding the armed correlation UUID on the launched run configuration.
         * Presence of a non-null value == "armed"; the extension consumes (clears) it so the
         * agent is injected for exactly one launch and the user's config is not permanently patched.
         */
        private val ARMED_CORRELATION: Key<String> = Key.create("vizcore.armed.correlation")

        /** Arm [configuration] for the next launch with [correlation] (called by the action). */
        fun armConfiguration(
            configuration: RunConfigurationBase<*>,
            correlation: String,
        ) {
            configuration.putUserData(ARMED_CORRELATION, correlation)
        }

        /** True when [configuration] has been armed by the "Run with Visualizer" action. */
        fun isArmed(configuration: RunConfigurationBase<*>): Boolean = configuration.getUserData(ARMED_CORRELATION) != null

        /**
         * The armed correlation UUID for [configuration], or `null` if it was never armed.
         * The extension reads this exactly once while building the agent VM-arg.
         */
        fun correlation(configuration: RunConfigurationBase<*>): String? = configuration.getUserData(ARMED_CORRELATION)

        /** Disarm [configuration] (one-shot consume) so a later plain run does not inject the agent. */
        fun disarm(configuration: RunConfigurationBase<*>) {
            configuration.putUserData(ARMED_CORRELATION, null)
        }
    }
}
