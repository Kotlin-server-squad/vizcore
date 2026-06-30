package com.jh.coroutinevisualizer.actions

import com.intellij.execution.ExecutionException
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.jh.coroutinevisualizer.health.BackendHealthCheck
import com.jh.coroutinevisualizer.run.VizcoreRunConfigurationExtension
import com.jh.coroutinevisualizer.settings.VizcoreSettings
import com.jh.coroutinevisualizer.toolwindow.VizcoreLaunchState
import java.util.UUID

/**
 * Action: "Run with Coroutine Visualizer" (IDE-01 + IDE-03).
 *
 * The launch sequence (RESEARCH Architecture diagram + Pattern 2 sequence):
 *  1. resolve the project and the user's currently selected run configuration;
 *  2. mint ONE correlation UUID;
 *  3. health-check the configured backend ([BackendHealthCheck]) — if it is Down, surface the
 *     actionable warning (D-05) and return WITHOUT launching (T-13-16: no infinite client retry
 *     against a dead backend);
 *  4. arm BOTH halves of [VizcoreLaunchState] with that SAME UUID — the per-config armed state (read
 *     by [VizcoreRunConfigurationExtension.updateJavaParameters] to inject `corr=<uuid>` into the
 *     agent VM-arg) AND the project-scoped correlation (consumed by the native tool window);
 *  5. trigger the standard run executor for that configuration;
 *  6. open/activate the Vizcore tool window.
 *
 * The single minted UUID therefore flows, byte-identical, into the agent `corr=` arg — proven
 * headlessly by [threadCorrelation] / `CorrelationThreadingTest`. Never `GlobalScope`; all IDE work
 * runs on the platform-managed EDT via the action callback.
 */
class RunWithVisualizerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        // Resolve project + selected runnable config in one guard (warns on its own if absent),
        // keeping actionPerformed within the project's ReturnCount budget.
        val launch = resolveLaunch(e) ?: return

        // (2) ONE correlation UUID minted here flows into BOTH the agent arg AND the view URL.
        val correlation = UUID.randomUUID().toString()

        // (3) Pre-launch health-check (D-05 / T-13-16): warn and do NOT launch when the backend is down.
        val backendUrl = VizcoreSettings.getInstance().backendUrl
        val health = BackendHealthCheck.check(backendUrl)
        if (health is BackendHealthCheck.HealthStatus.Down) {
            warn(launch.project, health.message)
            return
        }

        // (4) Arm BOTH halves of the launch state with the SAME correlation — the per-config armed
        // state (consumed by the extension to inject the agent) AND the project-scoped correlation.
        VizcoreLaunchState.armConfiguration(launch.configuration, correlation)
        VizcoreLaunchState.getInstance(launch.project).arm(correlation)

        // (5) Trigger the standard run executor for the selected config; the extension reads the
        // armed per-config state and injects the agent. (6) then opens the tool window.
        triggerRun(launch.project, launch.settings, launch.configuration)
        openToolWindow(launch.project)
    }

    /**
     * Resolve the project and the user's selected, runnable run configuration into a single
     * [LaunchTarget] — or `null` (after surfacing the "select a run configuration first" warning when
     * a project is present) when there is no project, no selected config, or it is not a
     * [RunConfigurationBase] the extension can patch. Folding all the pre-flight guards here keeps
     * [actionPerformed] within the project's ReturnCount budget.
     */
    private fun resolveLaunch(e: AnActionEvent): LaunchTarget? {
        val project = e.project ?: return null
        val selected = RunManager.getInstance(project).selectedConfiguration
        val configuration = selected?.configuration as? RunConfigurationBase<*>
        return if (selected != null && configuration != null) {
            LaunchTarget(project, selected, configuration)
        } else {
            warn(project, NO_CONFIG_MESSAGE)
            null
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasRunnableConfig = project != null && RunManager.getInstance(project).selectedConfiguration != null
        e.presentation.isEnabledAndVisible = hasRunnableConfig
    }

    /**
     * Run the standard "Run" executor for [settings]. On failure the per-config armed state is
     * disarmed so a stale arm cannot leak the agent into a later plain run.
     */
    private fun triggerRun(
        project: Project,
        settings: RunnerAndConfigurationSettings,
        configuration: RunConfigurationBase<*>,
    ) {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        try {
            val environment = ExecutionEnvironmentBuilder.create(executor, settings).build()
            ProgramRunnerUtil.executeConfiguration(environment, false, true)
        } catch (ex: ExecutionException) {
            // Launch could not even start — un-arm so the agent is not injected into a later run.
            VizcoreLaunchState.disarm(configuration)
            warn(project, "could not start the run configuration: ${ex.message}")
        }
    }

    /** Open + activate the Vizcore tool window; it builds its URL from the armed correlation. */
    private fun openToolWindow(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    }

    private fun warn(
        project: Project,
        message: String,
    ) {
        LOG.warn("Run with Coroutine Visualizer: $message")
        Messages.showWarningDialog(project, message, MESSAGE_TITLE)
    }

    companion object {
        private val LOG = Logger.getInstance(RunWithVisualizerAction::class.java)

        internal const val TOOL_WINDOW_ID: String = "Coroutine Visualizer"
        internal const val MESSAGE_TITLE: String = "Run with Coroutine Visualizer"
        internal const val NO_CONFIG_MESSAGE: String = "select a run configuration first"

        /**
         * Pure, headless correlation-threading seam (IDE-03). Given ONE [correlation], returns the
         * agent VM-arg string (carrying `corr=<correlation>`, built by the Plan 06 Task 1
         * [VizcoreRunConfigurationExtension.buildAgentVmArgs]). The action mints exactly one
         * correlation and threads it through here, so a test can assert the correlation substring
         * appears verbatim in the agent arg WITHOUT a run executor or a display.
         */
        internal fun threadCorrelation(
            correlation: String,
            configName: String,
            backendUrl: String,
            token: String = VizcoreRunConfigurationExtension.AGENT_TOKEN,
        ): CorrelationThreading {
            val agentVmArg =
                VizcoreRunConfigurationExtension
                    .buildAgentVmArgs(
                        agentPath = "/placeholder/coroutine-viz-agent.jar",
                        configName = configName,
                        backendUrl = backendUrl,
                        token = token,
                        correlation = correlation,
                    ).first { it.startsWith("-javaagent:") }
            return CorrelationThreading(agentVmArg = agentVmArg)
        }
    }

    /**
     * The agent side of the correlation handoff for one launch: the agent `-javaagent:...corr=<uuid>`
     * VM-arg. The test asserts the correlation substring extracted from it matches the minted UUID.
     */
    internal data class CorrelationThreading(
        val agentVmArg: String,
    )

    /** Resolved launch coordinates for one "Run with Visualizer" invocation. */
    private data class LaunchTarget(
        val project: Project,
        val settings: RunnerAndConfigurationSettings,
        val configuration: RunConfigurationBase<*>,
    )
}
