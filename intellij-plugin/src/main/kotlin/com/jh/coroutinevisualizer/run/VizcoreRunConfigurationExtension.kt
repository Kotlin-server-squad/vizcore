package com.jh.coroutinevisualizer.run

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.jh.coroutinevisualizer.agent.AgentJarExtractor
import com.jh.coroutinevisualizer.settings.VizcoreSettings
import com.jh.coroutinevisualizer.toolwindow.VizcoreLaunchState

/**
 * Patches the launched application's VM parameters with the coroutine-visualizer agent — but ONLY
 * for a run configuration the "Run with Coroutine Visualizer" action ([com.jh.coroutinevisualizer
 * .actions.RunWithVisualizerAction]) has armed (IDE-01). A plain Run/Debug of any other config is
 * left completely untouched (early-return on un-armed configs).
 *
 * When armed, [updateJavaParameters] injects, at the FRONT of the VM-args list (so they precede any
 * user `-D`/`-X` flags), in this order:
 *  1. `-javaagent:<extracted-jar>=app=<name>,backend=<url>,token=<token>,corr=<uuid>` — the Plan 01
 *     premain agent (`VizcoreClient.start`), pointed at the configured backend and carrying the
 *     correlation UUID the action minted.
 *  2. `-XX:+EnableDynamicAgentLoading` — REQUIRED on JDK 21+ (JEP 451): DebugProbes dynamically
 *     attaches a byte-buddy agent at runtime, which is warned/blocked without this flag, silently
 *     yielding zero captured events (RESEARCH Pitfall 1 / T-13-13).
 *
 * The agent-arg string is built by the pure, `internal` [buildAgentVmArgs] so the headless test can
 * assert the exact injected args (both flags, exact backend/correlation values) without a real
 * launch (D-13). The agent path comes from [AgentJarExtractor] (space-free temp file, Pitfall 6 /
 * T-13-14); the backend URL from [VizcoreSettings]; the correlation from the armed
 * [VizcoreLaunchState].
 */
class VizcoreRunConfigurationExtension : RunConfigurationExtension() {
    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = true

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        // Only patch a config the "Run with Visualizer" action explicitly armed.
        val correlation = VizcoreLaunchState.correlation(configuration) ?: return

        val settings = VizcoreSettings.getInstance()
        val args =
            buildAgentVmArgs(
                agentPath = AgentJarExtractor.path(),
                configName = configuration.name,
                backendUrl = settings.backendUrl,
                token = AGENT_TOKEN,
                correlation = correlation,
            )

        // addAt(0, ...) keeps our flags at the front; add in reverse so the final order matches
        // buildAgentVmArgs (index 0 = -javaagent, index 1 = -XX:+EnableDynamicAgentLoading).
        args.asReversed().forEach { params.vmParametersList.addAt(0, it) }

        // One-shot: consume the armed state so a subsequent plain run of this config is not patched.
        VizcoreLaunchState.disarm(configuration)
    }

    companion object {
        /**
         * The agent's auth token. Currently empty (the local backend accepts unauthenticated
         * loopback clients, D-04); threaded through verbatim so a future token surfaces here.
         */
        internal const val AGENT_TOKEN: String = ""

        /**
         * Pure builder for the agent VM-args, in injection order:
         *  - index 0: `-javaagent:<agentPath>=app=<configName>,backend=<backendUrl>,token=<token>,corr=<correlation>`
         *  - index 1: `-XX:+EnableDynamicAgentLoading`
         *
         * Extracted so the headless test asserts the exact args (both flags + the threaded backend
         * and correlation values) without invoking a real run executor. The `corr=` value is the
         * SAME UUID armed by the launch action on the run configuration (IDE-03 identity).
         */
        internal fun buildAgentVmArgs(
            agentPath: String,
            configName: String,
            backendUrl: String,
            token: String,
            correlation: String,
        ): List<String> {
            val javaagent =
                "-javaagent:$agentPath=app=$configName,backend=$backendUrl,token=$token,corr=$correlation"
            return listOf(javaagent, "-XX:+EnableDynamicAgentLoading")
        }
    }
}
