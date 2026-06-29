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
 * through [RunWithVisualizerAction.threadCorrelation], which produces BOTH halves of the launch
 * handoff:
 *  - the agent `-javaagent:...corr=<uuid>` VM-arg (built by Plan 06 Task 1's
 *    [VizcoreRunConfigurationExtension.buildAgentVmArgs]), and
 *  - the tool-window `http://127.0.0.1:<port>/?correlation=<uuid>` view URL
 *    (built by [com.jh.coroutinevisualizer.toolwindow.VizcoreViewUrl]).
 *
 * This test extracts the correlation substring out of EACH output and asserts they are
 * byte-identical — the single minted UUID reaches the agent and the view without drift (T-13-15).
 * (VM-arg injection itself is proven separately by [VizcoreRunConfigurationExtensionTest].)
 */
class CorrelationThreadingTest {
    @Test
    fun `one minted correlation is byte-identical in the agent arg and the view url`() {
        val correlation = "11111111-2222-3333-4444-555555555555"

        val threaded =
            RunWithVisualizerAction.threadCorrelation(
                correlation = correlation,
                port = 51234,
                configName = "MyApp",
                backendUrl = "http://localhost:8080",
                token = "",
            )

        val agentCorr = extractAgentCorrelation(threaded.agentVmArg)
        val urlCorr = extractUrlCorrelation(threaded.viewUrl)

        assertEquals(
            correlation,
            agentCorr,
            "the minted correlation must appear verbatim as corr=<uuid> in the agent VM-arg",
        )
        assertEquals(
            correlation,
            urlCorr,
            "the minted correlation must appear verbatim as ?correlation=<uuid> in the view URL",
        )
        assertEquals(
            agentCorr,
            urlCorr,
            "the agent-arg correlation and the view-URL correlation must be byte-identical (IDE-03)",
        )
    }

    @Test
    fun `a randomly minted UUID also threads identically into both paths`() {
        // Use a real UUID (as the action mints) to prove the identity is not specific to a literal.
        val correlation = UUID.randomUUID().toString()

        val threaded =
            RunWithVisualizerAction.threadCorrelation(
                correlation = correlation,
                port = 0,
                configName = "Bootstrap",
                backendUrl = "https://viz.example.internal:9443",
                token = "secret-token",
            )

        assertTrue(
            threaded.agentVmArg.contains("corr=$correlation"),
            "agent VM-arg carries the exact minted correlation",
        )
        assertTrue(
            threaded.viewUrl.contains("correlation=$correlation"),
            "view URL carries the exact minted correlation (UUIDs have no URL-reserved chars)",
        )
        assertEquals(
            extractAgentCorrelation(threaded.agentVmArg),
            extractUrlCorrelation(threaded.viewUrl),
            "both extracted correlations are equal for a randomly minted UUID",
        )
    }

    /** Pull the `corr=` value out of a `-javaagent:<path>=...,corr=<uuid>` arg. */
    private fun extractAgentCorrelation(agentVmArg: String): String =
        agentVmArg
            .substringAfter("corr=")
            .substringBefore(',')

    /** Pull the `correlation=` value out of a `http://...?correlation=<uuid>` URL. */
    private fun extractUrlCorrelation(viewUrl: String): String =
        viewUrl
            .substringAfter("correlation=")
            .substringBefore('&')
}
