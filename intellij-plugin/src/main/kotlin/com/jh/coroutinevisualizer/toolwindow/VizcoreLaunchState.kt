package com.jh.coroutinevisualizer.toolwindow

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

/**
 * Launch-handoff seam for the "Run with Coroutine Visualizer" flow. There is exactly ONE
 * `VizcoreLaunchState` class on disk; it carries both halves of the handoff:
 *
 * 1. **Project-scoped correlation**: the active correlation UUID for the current launch, armed by
 *    [com.jh.coroutinevisualizer.actions.RunWithVisualizerAction] and consumed by the native view.
 *
 * 2. **Per-run-configuration armed state** (Plan 06): a one-shot `(armed flag, correlation UUID)`
 *    stashed on the specific [RunConfigurationBase] the user is launching, read by
 *    [com.jh.coroutinevisualizer.run.VizcoreRunConfigurationExtension.updateJavaParameters] so the
 *    agent is injected ONLY for the config armed by the action and the config is not permanently
 *    modified. Stored as user-data on the configuration (companion API) so it is naturally keyed
 *    per config and survives only for that launch.
 *
 * The action mints ONE correlation UUID, [arm]s the project-scoped state AND [armConfiguration]s the
 * per-config state with that SAME UUID, guaranteeing the correlation in the agent VM-arg is
 * byte-identical to the one threaded through the launch.
 */
@Service(Service.Level.PROJECT)
class VizcoreLaunchState {
    @Volatile
    var correlation: String? = null
        private set

    /** Arm the correlation for the next launch (called by the launch action). */
    fun arm(correlation: String) {
        this.correlation = correlation
    }

    /** Clear the armed correlation (e.g. when the launched process ends). */
    fun clear() {
        this.correlation = null
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
