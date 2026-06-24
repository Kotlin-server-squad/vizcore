package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * ONE end-to-end smoke test that installs REAL DebugProbes, launches a single
 * named coroutine that delays, runs a few real polls through a real
 * [DebugProbesSource], and asserts the synthesized event's ATTRIBUTION fields
 * (not merely "an event exists") for the known launch site (06-REVIEWS.md).
 *
 * Timing-bearing → `@Tag("integration")` so the deterministic gate excludes it
 * (`:coroutine-viz-core:test` excludes the `integration` tag by default). Run it
 * explicitly with:
 *   ./gradlew :coroutine-viz-core:test --tests "*DebugProbesSmokeIT*" -PincludeIntegration
 *
 * Accepted v1 tradeoff (Pitfall 2): a coroutine that starts+finishes between
 * polls is invisible, so the launched coroutine delays long enough to be caught.
 */
@Tag("integration")
class DebugProbesSmokeIT {
    @Test
    fun `real DebugProbes synthesizes events with attribution for a known launch site`() =
        runBlocking {
            val session = VizSession("smoke-it")
            val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = 40.milliseconds,
                    installProbes = true,
                )

            try {
                source.start()
                // Launch a named coroutine that stays alive across several polls.
                val work =
                    appScope.launch(CoroutineName("smoke-worker")) {
                        delay(500)
                    }

                // Let several real polls fire.
                delay(300)
                source.stop()
                work.cancel()

                val events = session.store.all()
                assertTrue(events.isNotEmpty(), "real DebugProbes should produce synthesized events")

                // Attribution: the launch site label and/or creation-frame function should be present.
                val created = events.filterIsInstance<CoroutineCreated>()
                assertTrue(created.isNotEmpty(), "expected at least one CoroutineCreated")
                val hasNamedWorker = created.any { it.label == "smoke-worker" }
                assertTrue(
                    hasNamedWorker,
                    "expected the named 'smoke-worker' coroutine to be captured with its CoroutineName label",
                )
            } finally {
                source.stop()
                appScope.cancel()
                // Do NOT force-uninstall: repeatedly install/uninstall-ing DebugProbes
                // in one JVM via the dynamic byte-buddy agent can corrupt class
                // re-transformation state (JDK 21). The install is process-global and
                // harmless to leave for the JVM's lifetime in this single smoke run.
            }
        }
}
