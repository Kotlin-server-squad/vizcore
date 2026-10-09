package com.jh.coroutinevisualizer.run

import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Headless proofs for IDE-01 VM-arg injection (D-13 — no run executor, no display).
 *
 * Covers the pure [VizcoreRunConfigurationExtension.buildAgentVmArgs] seam (the exact, ordered
 * args the extension injects) and the un-armed early-return invariant against a real
 * [JavaParameters].
 */
class VizcoreRunConfigurationExtensionTest {
    @Test
    fun `buildAgentVmArgs injects javaagent then EnableDynamicAgentLoading in order`() {
        val args =
            VizcoreRunConfigurationExtension.buildAgentVmArgs(
                agentPath = "/tmp/coroutine-viz-agent123.jar",
                configName = "MyApp",
                backendUrl = "http://localhost:8080",
                token = "",
                correlation = "11111111-2222-3333-4444-555555555555",
            )

        assertEquals(2, args.size, "exactly the two agent VM flags are injected")
        // Order matters: the -javaagent comes first, the dynamic-agent flag second. Because the
        // extension prepends with addAt(0) in reversed order, this list order is the final VM order.
        assertEquals(
            "-javaagent:/tmp/coroutine-viz-agent123.jar=app=MyApp," +
                "backend=http://localhost:8080,token=,corr=11111111-2222-3333-4444-555555555555",
            args[0],
        )
        assertEquals("-XX:+EnableDynamicAgentLoading", args[1])
    }

    @Test
    fun `buildAgentVmArgs threads the exact backend url and correlation values`() {
        val correlation = "abc-DEF-0123456789"
        val backend = "https://viz.example.internal:9443"
        val args =
            VizcoreRunConfigurationExtension.buildAgentVmArgs(
                agentPath = "/var/folders/x/agent.jar",
                configName = "Bootstrap",
                backendUrl = backend,
                token = "secret-token",
                correlation = correlation,
            )

        val javaagent = args.first { it.startsWith("-javaagent:") }
        assertTrue(javaagent.contains("backend=$backend"), "exact backend URL threaded into agent args")
        assertTrue(javaagent.contains("corr=$correlation"), "exact correlation threaded into agent args")
        assertTrue(javaagent.contains("token=secret-token"), "exact token threaded into agent args")
        assertTrue(javaagent.contains("app=Bootstrap"), "config name threaded as app=")
    }

    @Test
    fun `agent path with no spaces is used verbatim (Pitfall 6 space-free temp)`() {
        // AgentJarExtractor copies to a Files.createTempFile path under java.io.tmpdir (space-free);
        // buildAgentVmArgs embeds it verbatim, so a space-free path produces a single, unbroken arg.
        val args =
            VizcoreRunConfigurationExtension.buildAgentVmArgs(
                agentPath = "/var/folders/ab/coroutine-viz-agent42.jar",
                configName = "App",
                backendUrl = "http://localhost:8080",
                token = "",
                correlation = "corr",
            )
        val javaagent = args.first()
        assertFalse(javaagent.contains(' '), "a space-free agent path yields a space-free -javaagent arg")
    }

    @Test
    fun `un-armed configuration leaves JavaParameters vmParametersList unchanged`() {
        val ext = VizcoreRunConfigurationExtension()
        val params = JavaParameters()
        val before = params.vmParametersList.parameters.toList()

        // A never-armed config: its user-data has no armed correlation, so getUserData(KEY)
        // returns null → VizcoreLaunchState.correlation == null → updateJavaParameters early-returns.
        // A Mockito mock returns null for getUserData by default, modelling exactly that.
        val unArmed: RunConfigurationBase<*> = Mockito.mock(RunConfigurationBase::class.java)

        ext.updateJavaParameters(unArmed, params, null)

        val after = params.vmParametersList.parameters.toList()
        assertEquals(before, after, "no VM args added for an un-armed config")
        assertFalse(
            after.any { it.startsWith("-javaagent:") || it == "-XX:+EnableDynamicAgentLoading" },
            "neither the -javaagent nor the dynamic-agent flag is injected when un-armed",
        )
    }
}
