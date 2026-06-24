package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A "see it work" DEMO of the Phase 06 [DebugProbesSource] — the UAT proof for a
 * phase with no UI. It installs REAL kotlinx `DebugProbes`
 * (`enableCreationStackTraces = true`), launches several named coroutines doing
 * realistic work from ARBITRARY user code (an "order fetch", a flow collect, a
 * throwing task), drives a handful of real poll ticks, then PRINTS the
 * synthesized [com.jh.proj.coroutineviz.events.VizEvent]s read back from
 * `session.store` as a readable table so a human can SEE the source attribution
 * (coroutine name, state transition, function, file:line) flowing out of
 * otherwise-uninstrumented code.
 *
 * It is also a real test: it asserts events were synthesized and that at least
 * one carries a non-null `file:line` from the creation/suspension stack — i.e.
 * RCO-03 attribution actually round-trips through the source.
 *
 * Timing-bearing (real probes + real polls) → `@Tag("integration")` so the
 * deterministic gate excludes it. Run it (and surface its stdout) with:
 *   ./gradlew :coroutine-viz-core:test --tests "*DebugProbesDemoIT*" \
 *       -PincludeIntegration --info
 *
 * Attribution rides EXISTING event fields (no new VizEvent field, RCO-03):
 * `CoroutineName` → `label`; function/file:line/reason → the `CoroutineSuspended`
 * event's `SuspensionPoint`.
 */
@Tag("integration")
class DebugProbesDemoIT {
    @Test
    fun `DEMO - real DebugProbes captures arbitrary coroutines and attributes them to source`() =
        runBlocking {
            val session = VizSession("debugprobes-demo")
            val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = 40.milliseconds,
                    installProbes = true,
                )

            try {
                source.start()

                // --- Arbitrary, uninstrumented "user" code doing realistic work. ---
                // 1) An order fetch that suspends on I/O-like delays.
                appScope.launch(CoroutineName("order-fetch")) {
                    fetchOrder()
                }
                // 2) A pricing pipeline that collects a flow.
                appScope.launch(CoroutineName("price-pipeline")) {
                    pricesFlow().onEach { delay(60) }.collect { /* render */ }
                }
                // 3) Inventory check that suspends, then throws (failure path).
                appScope.launch(CoroutineName("inventory-check")) {
                    runCatching { checkInventoryThenFail() }
                }
                // 4) A long-lived heartbeat that stays parked across all polls.
                appScope.launch(CoroutineName("heartbeat")) {
                    delay(1_000)
                }

                // Let several real polls fire so transitions are observed.
                delay(400)
                source.stop()
                appScope.cancel()

                val events = session.store.all().filterIsInstance<CoroutineEvent>()
                printEventTable(events)

                assertTrue(events.isNotEmpty(), "real DebugProbes should produce synthesized events")

                val attributed =
                    events
                        .filterIsInstance<CoroutineSuspended>()
                        .mapNotNull { it.suspensionPoint }
                        .filter { it.fileName != null && it.lineNumber != null }
                assertTrue(
                    attributed.isNotEmpty(),
                    "expected at least one synthesized event with non-null file:line source attribution",
                )
            } finally {
                source.stop()
                appScope.cancel()
                // Do NOT force-uninstall DebugProbes: repeated install/uninstall via
                // the byte-buddy agent can corrupt class re-transformation on JDK 21.
                // The install is process-global and harmless for this single run.
            }
        }

    private suspend fun fetchOrder() {
        delay(120) // network round-trip
        delay(120) // db read
    }

    private fun pricesFlow() =
        flow {
            repeat(5) {
                emit(it * 10)
                delay(40)
            }
        }

    private suspend fun checkInventoryThenFail() {
        delay(90)
        error("inventory service unavailable")
    }

    private fun printEventTable(events: List<CoroutineEvent>) {
        val nameW = 16
        val stateW = 20
        val fnW = 26
        val locW = 30
        val sep = "+-${"-".repeat(nameW)}-+-${"-".repeat(stateW)}-+-${"-".repeat(fnW)}-+-${"-".repeat(locW)}-+"

        fun row(
            name: String,
            state: String,
            fn: String,
            loc: String,
        ) = "| ${name.padEnd(nameW)} | ${state.padEnd(stateW)} | ${fn.padEnd(fnW)} | ${loc.padEnd(locW)} |"

        val out = StringBuilder()
        out.appendLine()
        out.appendLine("=== DebugProbesSource DEMO — synthesized events from arbitrary coroutines ===")
        out.appendLine("Total CoroutineEvents captured from session.store: ${events.size}")
        out.appendLine(sep)
        out.appendLine(row("coroutine name", "state transition", "function", "file:line"))
        out.appendLine(sep)
        events.forEach { e ->
            val sp = (e as? CoroutineSuspended)?.suspensionPoint
            val fn = sp?.function ?: "-"
            val loc =
                when {
                    sp?.fileName != null && sp.lineNumber != null -> "${sp.fileName}:${sp.lineNumber}"
                    sp?.fileName != null -> sp.fileName!!
                    else -> "-"
                }
            out.appendLine(row(e.label ?: "(unnamed)", e.kind, fn, loc))
        }
        out.appendLine(sep)
        // Print as one block so it survives parallel test stdout interleaving.
        println(out.toString())
    }
}
