package com.jh.coroutinevisualizer.run

import com.jh.coroutinevisualizer.actions.RunWithVisualizerAction
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless proof of IDE-03 correlation identity (D-13 — no run executor, no display).
 *
 * The "Run with Coroutine Visualizer" action mints exactly ONE correlation UUID and threads it
 * through [RunWithVisualizerAction.threadCorrelation], which builds the agent
 * `-javaagent:...corr=<uuid>` VM-arg (via Plan 06 Task 1's
 * [VizcoreRunConfigurationExtension.buildAgentVmArgs]).
 *
 * This test extracts the correlation substring out of the agent arg and asserts it is
 * byte-identical to the minted UUID — the single minted UUID reaches the agent without drift
 * (T-13-15). (VM-arg injection itself is proven separately by [VizcoreRunConfigurationExtensionTest].)
 */
class CorrelationThreadingTest {
    @Test
    fun `the minted correlation appears verbatim as corr in the agent arg`() {
        val correlation = "11111111-2222-3333-4444-555555555555"

        val threaded =
            RunWithVisualizerAction.threadCorrelation(
                correlation = correlation,
                configName = "MyApp",
                backendUrl = "http://localhost:8080",
                token = "",
            )

        val agentCorr = extractAgentCorrelation(threaded.agentVmArg)

        assertEquals(
            correlation,
            agentCorr,
            "the minted correlation must appear verbatim as corr=<uuid> in the agent VM-arg",
        )
    }

    @Test
    fun `a randomly minted UUID also threads into the agent arg`() {
        // Use a real UUID (as the action mints) to prove the identity is not specific to a literal.
        val correlation = UUID.randomUUID().toString()

        val threaded =
            RunWithVisualizerAction.threadCorrelation(
                correlation = correlation,
                configName = "Bootstrap",
                backendUrl = "https://viz.example.internal:9443",
                token = "secret-token",
            )

        assertTrue(
            threaded.agentVmArg.contains("corr=$correlation"),
            "agent VM-arg carries the exact minted correlation",
        )
        assertEquals(
            correlation,
            extractAgentCorrelation(threaded.agentVmArg),
            "the extracted correlation equals the minted UUID",
        )
    }

    /** Pull the `corr=` value out of a `-javaagent:<path>=...,corr=<uuid>` arg. */
    private fun extractAgentCorrelation(agentVmArg: String): String =
        agentVmArg
            .substringAfter("corr=")
            .substringBefore(',')
}
